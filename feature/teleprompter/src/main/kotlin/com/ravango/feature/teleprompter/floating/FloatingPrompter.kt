package com.ravango.feature.teleprompter.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ravango.core.common.log.RgLog
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.ui.AppPermission
import com.ravango.core.ui.PermissionStatus
import com.ravango.core.ui.rememberPermissionRequester
import com.ravango.feature.teleprompter.R
import com.ravango.core.ui.R as UiR

/** Entry points for the floating (overlay) teleprompter. */
object FloatingPrompter {
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Deep link to this app's "Display over other apps" switch. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts (or retargets) the floating prompter for [scriptId]. Caller must have checked [canDrawOverlays]. */
    fun start(context: Context, scriptId: String): Boolean = runCatching {
        val intent = Intent(context, FloatingPrompterService::class.java)
            .setAction(FloatingPrompterService.ACTION_SHOW)
            .putExtra(FloatingPrompterService.EXTRA_SCRIPT_ID, scriptId)
        ContextCompat.startForegroundService(context, intent)
        true
    }.getOrElse {
        RgLog.e("FloatingPrompter", "could not start the floating prompter", it)
        false
    }

    fun stop(context: Context) {
        context.startService(Intent(context, FloatingPrompterService::class.java).setAction(FloatingPrompterService.ACTION_CLOSE))
    }
}

/**
 * Returns a launcher for the floating prompter that handles, in order: the Pro gate ([isPro] / [onRequirePro]),
 * the overlay permission (explained, then deep-linked to system settings, resumed automatically on return) and,
 * on Android 13+, the notification permission used for the play/pause/close controls.
 */
@Composable
fun rememberFloatingPrompterLauncher(
    isPro: Boolean,
    onRequirePro: () -> Unit,
    onStarted: () -> Unit,
): (scriptId: String) -> Unit {
    val context = LocalContext.current
    var pendingScriptId by rememberSaveable { mutableStateOf<String?>(null) }
    var showOverlayRationale by remember { mutableStateOf(false) }
    val started by rememberUpdatedState(onStarted)
    val requirePro by rememberUpdatedState(onRequirePro)

    fun launchNow(id: String) {
        pendingScriptId = null
        if (FloatingPrompter.start(context, id)) started()
    }

    val notifications = rememberPermissionRequester(AppPermission.NOTIFICATIONS) { _ ->
        // The prompter works without the notification; it only adds shade controls.
        pendingScriptId?.let(::launchNow)
    }

    fun continueAfterOverlay(id: String) {
        if (Build.VERSION.SDK_INT >= 33 && notifications.status == PermissionStatus.DENIED) {
            pendingScriptId = id
            notifications.request()
        } else {
            launchNow(id)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val id = pendingScriptId
        if (id != null && !showOverlayRationale && FloatingPrompter.canDrawOverlays(context)) continueAfterOverlay(id)
    }

    if (showOverlayRationale) {
        RgConfirmDialog(
            title = stringResource(R.string.prompter_floating_permission_title),
            message = stringResource(R.string.prompter_floating_permission_message),
            confirmText = stringResource(UiR.string.permission_open_settings),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = {
                showOverlayRationale = false
                runCatching { context.startActivity(FloatingPrompter.overlaySettingsIntent(context)) }
                    .onFailure { RgLog.w("FloatingPrompter", "overlay settings unavailable", it) }
            },
            onDismiss = {
                showOverlayRationale = false
                pendingScriptId = null
            },
        )
    }

    return remember(isPro) {
        { id: String ->
            when {
                !isPro -> requirePro()
                !FloatingPrompter.canDrawOverlays(context) -> {
                    pendingScriptId = id
                    showOverlayRationale = true
                }
                else -> continueAfterOverlay(id)
            }
        }
    }
}
