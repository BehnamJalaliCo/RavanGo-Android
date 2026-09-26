package com.ravango.engine.editor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ravango.core.common.log.RgLog
import com.ravango.engine.editor.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the process alive while [ExportController] exports, showing progress with a cancel
 * action. It stops itself as soon as the export finishes and posts a completion/failure notification.
 */
@AndroidEntryPoint
class ExportService : Service() {
    @Inject lateinit var controller: ExportController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification(controller.state.value), type)
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException: the export still runs while the app is visible.
            RgLog.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.collectLatest { state ->
                    if (state.isActive) {
                        notify(NOTIFICATION_ID, progressNotification(state))
                    } else {
                        ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        finishedNotification(state)?.let { notify(RESULT_NOTIFICATION_ID, it) }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(state: ExportState): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, progress) = when (state) {
            is ExportState.Preparing -> getString(if (state.step == PrepareStep.REVERSING) R.string.editor_engine_notif_preparing_reverse else R.string.editor_engine_notif_preparing) to state.progress
            is ExportState.Running -> getString(R.string.editor_engine_notif_exporting) to state.progress
            else -> getString(R.string.editor_engine_notif_exporting) to 0f
        }
        val percent = (progress * 100).roundToInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(getString(R.string.editor_engine_notif_percent, percent))
            .setProgress(100, percent, state is ExportState.Preparing && progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.editor_engine_notif_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun finishedNotification(state: ExportState): Notification? {
        val text = when (state) {
            is ExportState.Succeeded -> getString(R.string.editor_engine_notif_done)
            is ExportState.Failed -> getString(R.string.editor_engine_notif_failed)
            else -> return null
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (state is ExportState.Succeeded) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    companion object {
        private const val TAG = "ExportService"
        const val CHANNEL_ID = "editor_export"
        private const val NOTIFICATION_ID = 7301
        private const val RESULT_NOTIFICATION_ID = 7302
        private const val ACTION_CANCEL = "com.ravango.editor.export.CANCEL"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.editor_engine_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.editor_engine_notif_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }
}
