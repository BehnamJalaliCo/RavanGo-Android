package com.ravango.core.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Permission groups requested just-in-time, each with its own rationale copy. */
enum class AppPermission(val manifest: List<String>) {
    CAMERA(listOf(Manifest.permission.CAMERA)),
    MICROPHONE(listOf(Manifest.permission.RECORD_AUDIO)),
    BLUETOOTH(if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()),
    NOTIFICATIONS(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),

    /** Only needed on Android 9 and lower to save into the shared gallery; newer versions use MediaStore without permission. */
    LEGACY_STORAGE(if (Build.VERSION.SDK_INT <= 28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()),
}

enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED }

fun Context.isGranted(permission: AppPermission): Boolean =
    permission.manifest.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Stable
class PermissionRequester internal constructor(
    val permissions: List<AppPermission>,
    private val context: Context,
    private val launch: (Array<String>) -> Unit,
) {
    var status by mutableStateOf(computeStatus(askedBefore = false))
        internal set
    internal var askedBefore = false

    val allGranted: Boolean get() = status == PermissionStatus.GRANTED

    internal fun computeStatus(askedBefore: Boolean): PermissionStatus {
        if (permissions.all { context.isGranted(it) }) return PermissionStatus.GRANTED
        val activity = context.findActivity() ?: return PermissionStatus.DENIED
        val showRationale = permissions.flatMap { it.manifest }.any { activity.shouldShowRequestPermissionRationale(it) }
        return if (askedBefore && !showRationale) PermissionStatus.PERMANENTLY_DENIED else PermissionStatus.DENIED
    }

    fun refresh() { status = computeStatus(askedBefore) }

    /** Launches the system dialog, or opens app settings when the user has permanently denied the permission. */
    fun request() {
        if (status == PermissionStatus.PERMANENTLY_DENIED) {
            context.openAppSettings()
            return
        }
        val needed = permissions.flatMap { it.manifest }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) status = PermissionStatus.GRANTED else launch(needed.toTypedArray())
    }
}

/**
 * Remembers a requester for [permissions]. Status refreshes automatically when returning from Settings.
 * [onResult] is invoked after the system dialog closes.
 */
@Composable
fun rememberPermissionRequester(
    vararg permissions: AppPermission,
    onResult: (granted: Boolean) -> Unit = {},
): PermissionRequester {
    val context = LocalContext.current
    var holder: PermissionRequester? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        holder?.let { r ->
            r.askedBefore = true
            r.refresh()
            onResult(r.allGranted)
        }
    }
    val requester = remember(permissions.toList()) {
        PermissionRequester(permissions.toList(), context) { launcher.launch(it) }
    }
    holder = requester
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { requester.refresh() }
    return requester
}
