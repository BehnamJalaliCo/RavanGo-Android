package com.ravango.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** Keeps the screen on while in composition (teleprompter, recording, export). */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Forces full brightness (used by the front "screen flash" and prompter readability boost). */
@Composable
fun MaxBrightness(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        if (enabled) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
}

fun Context.shareMedia(uri: Uri, mimeType: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.shareText(text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
