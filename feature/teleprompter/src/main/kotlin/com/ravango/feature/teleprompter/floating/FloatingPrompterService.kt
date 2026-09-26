package com.ravango.feature.teleprompter.floating

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.ProFeature
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.feature.teleprompter.R
import com.ravango.feature.teleprompter.data.effectiveSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Commands delivered from the notification to the overlay's prompter controller. */
internal enum class FloatingCommand { TOGGLE }

/** What the overlay UI needs from its hosting service. */
internal interface FloatingHost {
    val script: StateFlow<Script?>
    val settings: StateFlow<TeleprompterSettings?>
    val commands: Flow<FloatingCommand>
    fun moveBy(dx: Float, dy: Float)
    fun resizeBy(dw: Float, dh: Float)
    fun onPlayingChanged(playing: Boolean)
    fun saveReadingPosition(charOffset: Int)
    fun close()
}

/**
 * Foreground service hosting the floating teleprompter: a draggable, resizable `TYPE_APPLICATION_OVERLAY` window
 * with a [ComposeView]. The service itself is the view tree's Lifecycle-, SavedStateRegistry- and
 * ViewModelStore-owner, which Compose requires outside an Activity.
 */
@AndroidEntryPoint
internal class FloatingPrompterService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner, FloatingHost {

    @Inject lateinit var scripts: ScriptRepository
    @Inject lateinit var prefs: PreferencesDataSource
    @Inject lateinit var entitlements: EntitlementProvider
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlay: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private val scriptId = MutableStateFlow<String?>(null)
    private val _script = MutableStateFlow<Script?>(null)
    private val _settings = MutableStateFlow<TeleprompterSettings?>(null)
    private val _commands = MutableSharedFlow<FloatingCommand>(extraBufferCapacity = 8)
    private var playing = false

    override val script: StateFlow<Script?> = _script.asStateFlow()
    override val settings: StateFlow<TeleprompterSettings?> = _settings.asStateFlow()
    override val commands: Flow<FloatingCommand> = _commands.asSharedFlow()

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.prompter_floating_channel))
                .setShowBadge(false)
                .build(),
        )
        observeScript()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeScript() {
        lifecycleScope.launch {
            scriptId.filterNotNull()
                .flatMapLatest { id -> combine(scripts.observeScript(id), prefs.prompterDefaults) { s, d -> s to effectiveSettings(s, d) } }
                .collect { (s, effective) ->
                    if (s == null) {
                        RgLog.w(TAG, "script no longer exists; closing floating prompter")
                        close()
                        return@collect
                    }
                    val titleChanged = _script.value?.title != s.title
                    _script.value = s
                    _settings.value = effective
                    if (titleChanged) updateNotification()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW -> {
                // Always enter the foreground first: startForegroundService() requires it even if we bail out.
                startInForeground()
                val id = intent.getStringExtra(EXTRA_SCRIPT_ID)
                when {
                    id.isNullOrBlank() -> close()
                    !Settings.canDrawOverlays(this) -> {
                        RgLog.w(TAG, "overlay permission missing")
                        close()
                    }
                    !entitlements.has(ProFeature.FLOATING_PROMPTER) -> {
                        RgLog.w(TAG, "floating prompter requires Pro")
                        close()
                    }
                    else -> {
                        if (scriptId.value != id) {
                            _script.value = null
                            scriptId.value = id
                        }
                        showOverlay()
                    }
                }
            }
            ACTION_TOGGLE -> _commands.tryEmit(FloatingCommand.TOGGLE)
            ACTION_CLOSE -> close()
            else -> if (overlay == null) close()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, FloatingPrompterService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this, 2, Intent(this, FloatingPrompterService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.prompter_ic_notification)
            .setContentTitle(_script.value?.title?.ifBlank { null } ?: getString(R.string.prompter_floating_title))
            .setContentText(getString(if (playing) R.string.prompter_floating_playing else R.string.prompter_floating_paused))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                if (playing) R.drawable.prompter_ic_pause else R.drawable.prompter_ic_play,
                getString(if (playing) R.string.prompter_pause else R.string.prompter_play),
                toggle,
            )
            .addAction(R.drawable.prompter_ic_close, getString(com.ravango.core.ui.R.string.action_close), close)
            .build()
    }

    private fun updateNotification() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
            .onFailure { RgLog.w(TAG, "notification update failed", it) }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.9f).toInt()
        val height = (metrics.heightPixels * 0.36f).toInt().coerceAtLeast(minHeightPx())
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the app underneath keeps keyboard/volume input; touches outside pass through.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (metrics.widthPixels - width) / 2
            y = (metrics.heightPixels * 0.07f).toInt()
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingPrompterService)
            setViewTreeViewModelStoreOwner(this@FloatingPrompterService)
            setViewTreeSavedStateRegistryOwner(this@FloatingPrompterService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { StudioTheme { FloatingPrompterWindow(host = this@FloatingPrompterService) } }
        }
        try {
            windowManager.addView(view, lp)
            overlay = view
            params = lp
        } catch (e: Exception) {
            // BadTokenException / SecurityException when the overlay permission was revoked meanwhile.
            RgLog.e(TAG, "could not add overlay window", e)
            close()
        }
    }

    private fun minWidthPx() = (MIN_WIDTH_DP * resources.displayMetrics.density).toInt()
    private fun minHeightPx() = (MIN_HEIGHT_DP * resources.displayMetrics.density).toInt()

    override fun moveBy(dx: Float, dy: Float) {
        val view = overlay ?: return
        val lp = params ?: return
        val metrics = resources.displayMetrics
        val keepVisible = (48 * metrics.density).toInt()
        lp.x = (lp.x + dx.toInt()).coerceIn(-lp.width + keepVisible, metrics.widthPixels - keepVisible)
        lp.y = (lp.y + dy.toInt()).coerceIn(0, metrics.heightPixels - keepVisible)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    override fun resizeBy(dw: Float, dh: Float) {
        val view = overlay ?: return
        val lp = params ?: return
        val metrics = resources.displayMetrics
        lp.width = (lp.width + dw.toInt()).coerceIn(minWidthPx(), metrics.widthPixels)
        lp.height = (lp.height + dh.toInt()).coerceIn(minHeightPx(), metrics.heightPixels)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    override fun onPlayingChanged(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        updateNotification()
    }

    override fun saveReadingPosition(charOffset: Int) {
        val id = scriptId.value ?: return
        appScope.launch { runCatching { scripts.saveStartOffset(id, charOffset) } }
    }

    override fun close() {
        removeOverlay()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { windowManager.removeViewImmediate(view) } }
        overlay = null
        params = null
    }

    override fun onDestroy() {
        removeOverlay()
        viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        /** Fully-qualified class name, for modules that start the service without a compile-time dependency. */
        const val CLASS_NAME = "com.ravango.feature.teleprompter.floating.FloatingPrompterService"
        const val ACTION_SHOW = "com.ravango.prompter.floating.SHOW"
        const val ACTION_TOGGLE = "com.ravango.prompter.floating.TOGGLE"
        const val ACTION_CLOSE = "com.ravango.prompter.floating.CLOSE"
        const val EXTRA_SCRIPT_ID = "script_id"

        private const val TAG = "FloatingPrompter"
        private const val CHANNEL_ID = "rg_floating_prompter"
        private const val NOTIFICATION_ID = 0x5250
        private const val MIN_WIDTH_DP = 200
        private const val MIN_HEIGHT_DP = 150
    }
}
