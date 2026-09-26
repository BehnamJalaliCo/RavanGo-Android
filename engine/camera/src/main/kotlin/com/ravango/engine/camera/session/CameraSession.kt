package com.ravango.engine.camera.session

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface
import com.ravango.core.common.diagnostics.Diagnostics
import com.ravango.core.common.log.RgLog
import com.ravango.engine.camera.CameraErrorKind
import java.util.concurrent.Executor

/**
 * One Camera2 device + capture session with a single SurfaceTexture output and a TEMPLATE_RECORD repeating
 * request. Every method must be called on [handler]'s thread; all callbacks arrive there too.
 * Stale callbacks from a previous open are ignored via a generation counter.
 */
internal class CameraSession(
    private val manager: CameraManager,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onStreaming(cameraId: String)
        fun onCameraError(kind: CameraErrorKind, message: String?, recoverable: Boolean)
        fun onResult(result: CaptureSnapshot)
    }

    /** Values read from capture results (no allocation beyond what the framework does). */
    class CaptureSnapshot {
        var afState: Int = CameraMetadata.CONTROL_AF_STATE_INACTIVE
        var iso: Int = 0
        var exposureNs: Long = 0
        var focusDistance: Float = 0f
        var colorTransform: ColorSpaceTransform? = null
    }

    private val executor = Executor { handler.post(it) }
    private val snapshot = CaptureSnapshot()
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var target: Surface? = null
    private var params: RequestParams = RequestParams()
    private var lastDropped: List<String> = emptyList()

    /** True when the repeating request is the minimal fallback (template defaults + fps range). */
    val usingMinimalRequest: Boolean get() = params.minimal

    var cameraId: String? = null
        private set

    val isOpen: Boolean get() = device != null
    val isStreaming: Boolean get() = session != null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.CONTROL_AF_STATE)?.let { snapshot.afState = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { snapshot.iso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { snapshot.exposureNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { snapshot.focusDistance = it }
            if (params.awbGains == null) result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { snapshot.colorTransform = it }
            listener.onResult(snapshot)
        }
    }

    @SuppressLint("MissingPermission")
    fun open(id: String, surface: Surface, initial: RequestParams) {
        close()
        val gen = ++generation
        cameraId = id
        target = surface
        params = initial
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) { camera.close(); return }
                    device = camera
                    createSession(gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen != generation) return
                    device = null
                    session = null
                    RgLog.w(TAG, "camera $id disconnected")
                    listener.onCameraError(CameraErrorKind.IN_USE, "disconnected", recoverable = true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen != generation) return
                    device = null
                    session = null
                    val kind = when (error) {
                        ERROR_CAMERA_IN_USE -> CameraErrorKind.IN_USE
                        ERROR_MAX_CAMERAS_IN_USE -> CameraErrorKind.MAX_CAMERAS_IN_USE
                        ERROR_CAMERA_DISABLED -> CameraErrorKind.DISABLED
                        ERROR_CAMERA_SERVICE -> CameraErrorKind.SERVICE
                        else -> CameraErrorKind.DEVICE
                    }
                    RgLog.e(TAG, "camera $id error $error")
                    listener.onCameraError(kind, "error $error", recoverable = kind != CameraErrorKind.DISABLED)
                }
            }, handler)
        } catch (e: CameraAccessException) {
            RgLog.e(TAG, "openCamera failed", e)
            listener.onCameraError(mapAccess(e), e.message, recoverable = e.reason != CameraAccessException.CAMERA_DISABLED)
        } catch (e: SecurityException) {
            listener.onCameraError(CameraErrorKind.PERMISSION, e.message, recoverable = false)
        } catch (e: IllegalArgumentException) {
            listener.onCameraError(CameraErrorKind.NO_CAMERA, e.message, recoverable = false)
        }
    }

    /** Recreates the capture session (e.g. after the buffer size changed). */
    fun reconfigure() {
        if (device == null) return
        runCatching { session?.close() }
        session = null
        createSession(generation)
    }

    fun update(newParams: RequestParams) {
        params = newParams
        submitRepeating()
    }

    /** Runs an AF scan at the current regions (tap-to-focus or AF lock). */
    fun triggerAutoFocus() {
        val s = session ?: return
        val d = device ?: return
        val t = target ?: return
        // No AF triggers on the minimal fallback request, nor when the planner left AF_MODE out (fixed focus).
        if (params.minimal || RequestKey.AF_MODE !in params.plan()) return
        try {
            val cancel = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(t)
                params.applyTo(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }
            s.capture(cancel.build(), captureCallback, handler)
            val start = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(t)
                params.applyTo(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            s.capture(start.build(), captureCallback, handler)
        } catch (e: Exception) {
            RgLog.w(TAG, "AF trigger failed", e)
        }
    }

    fun close() {
        generation++
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        cameraId = null
    }

    private fun createSession(gen: Int) {
        val d = device ?: return
        val surface = target ?: return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) { runCatching { s.close() }; return }
                session = s
                if (submitRepeating()) listener.onStreaming(d.id)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != generation) return
                RgLog.e(TAG, "session configuration failed")
                listener.onCameraError(CameraErrorKind.CONFIGURATION, "configure failed", recoverable = false)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)), executor, callback)
                // Session parameters let the HAL pick the right sensor mode for the fps range / stabilization up front.
                // The minimal fallback request (for a camera that keeps failing) sends none.
                if (!params.minimal) {
                    runCatching {
                        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        params.applyTo(builder)
                        config.sessionParameters = builder.build()
                    }
                }
                d.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(surface), callback, handler)
            }
        } catch (e: CameraAccessException) {
            RgLog.e(TAG, "createCaptureSession failed", e)
            listener.onCameraError(mapAccess(e), e.message, recoverable = true)
        } catch (e: IllegalArgumentException) {
            listener.onCameraError(CameraErrorKind.CONFIGURATION, e.message, recoverable = false)
        } catch (e: IllegalStateException) {
            listener.onCameraError(CameraErrorKind.DEVICE, e.message, recoverable = true)
        }
    }

    private fun submitRepeating(): Boolean {
        val s = session ?: return false
        val d = device ?: return false
        val t = target ?: return false
        return try {
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(t)
            val plan = params.applyTo(builder)
            s.setRepeatingRequest(builder.build(), captureCallback, handler)
            reportDropped(d.id, plan.dropped)
            true
        } catch (e: CameraAccessException) {
            RgLog.e(TAG, "setRepeatingRequest failed", e)
            listener.onCameraError(mapAccess(e), e.message, recoverable = true)
            false
        } catch (e: IllegalStateException) {
            // Session closed underneath us (camera switched / disconnected); the device callback reports the cause.
            RgLog.w(TAG, "session not usable", e)
            false
        } catch (e: IllegalArgumentException) {
            RgLog.e(TAG, "invalid request", e)
            // Retry with the safest request so the preview never dies because of one bad manual value.
            if (!params.minimal) {
                Diagnostics.record(TAG, "camera ${d.id} rejected the request (${e.message}); using template defaults + fps range")
                params = params.minimalCopy()
                submitRepeating()
            } else {
                false
            }
        }
    }

    /** Logs (once per change) the requested values this camera does not support and that were left out. */
    private fun reportDropped(cameraId: String, dropped: List<String>) {
        if (dropped == lastDropped) return
        lastDropped = dropped
        if (dropped.isNotEmpty()) Diagnostics.record(TAG, "camera $cameraId: unsupported request values left out: ${dropped.joinToString("; ")}")
    }

    private fun mapAccess(e: CameraAccessException): CameraErrorKind = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> CameraErrorKind.IN_USE
        CameraAccessException.MAX_CAMERAS_IN_USE -> CameraErrorKind.MAX_CAMERAS_IN_USE
        CameraAccessException.CAMERA_DISABLED -> CameraErrorKind.DISABLED
        CameraAccessException.CAMERA_DISCONNECTED -> CameraErrorKind.IN_USE
        else -> CameraErrorKind.DEVICE
    }

    private companion object {
        const val TAG = "CameraSession"
    }
}
