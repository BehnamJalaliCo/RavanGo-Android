package com.ravango.feature.scripts.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ravango.core.common.log.RgLog

/**
 * Starts the floating teleprompter owned by `:feature:teleprompter` without a compile-time dependency between
 * features: the service is addressed by its class name and actions (see `FloatingPrompterService.CLASS_NAME`).
 * The service re-validates the overlay permission and Pro entitlement itself.
 */
internal object FloatingPrompterStarter {
    private const val SERVICE_CLASS = "com.ravango.feature.teleprompter.floating.FloatingPrompterService"
    private const val ACTION_SHOW = "com.ravango.prompter.floating.SHOW"
    private const val EXTRA_SCRIPT_ID = "script_id"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(context: Context, scriptId: String): Boolean = runCatching {
        val intent = Intent(ACTION_SHOW)
            .setClassName(context.packageName, SERVICE_CLASS)
            .putExtra(EXTRA_SCRIPT_ID, scriptId)
        ContextCompat.startForegroundService(context, intent)
        true
    }.getOrElse {
        RgLog.e("ScriptEditor", "could not start the floating prompter", it)
        false
    }
}
