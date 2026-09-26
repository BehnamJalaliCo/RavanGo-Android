package com.ravango.engine.beauty.tracking

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.ravango.core.common.log.RgLog
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous ML Kit face-contour detection with "latest frame only" semantics: [submit] is ignored while a
 * detection is running, so the camera thread never queues work or waits.
 *
 * Results are double-buffered: the detection thread writes the back buffer and swaps under a lock; the GL thread
 * copies the front buffer in [pollNew]. Contour points are converted to frame space (isotropic, y down).
 */
internal class FaceTracker {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rg-face-detect").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var detector: FaceDetector? = null
    private var bitmap: Bitmap? = null
    @Volatile private var closed = false

    private val lock = Any()
    private val front = FaceLandmarks()
    private val back = FaceLandmarks()
    private var frontSeq = 0L
    private var frontTimestampNs = 0L
    private var frontHasLandmarks = false
    private var frontFaceCount = 0
    private var frontTrackingId = -1
    private var consumedSeq = 0L

    /** Metadata of the result returned by the last successful [pollNew]. */
    var resultTimestampNs = 0L; private set
    var resultHasLandmarks = false; private set
    var resultFaceCount = 0; private set
    var resultTrackingId = -1; private set

    @Volatile var consecutiveFailures = 0; private set

    /** True when detection keeps failing (e.g. the model cannot be loaded): features needing a face stay off. */
    val unavailable: Boolean get() = consecutiveFailures >= MAX_FAILURES

    val isIdle: Boolean get() = !busy.get() && !closed

    /**
     * Copies [pixels] (RGBA, top-down rows, [width]×[height]) into the detection bitmap and starts detection.
     * Must only be called when [isIdle]; returns false if the detector was busy.
     */
    fun submit(pixels: ByteBuffer, width: Int, height: Int, timestampNs: Long): Boolean {
        if (closed || unavailable || !busy.compareAndSet(false, true)) return false
        try {
            val bmp = bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap?.recycle(); bitmap = it }
            pixels.position(0)
            bmp.copyPixelsFromBuffer(pixels)
            val det = detector ?: createDetector().also { detector = it }
            det.process(InputImage.fromBitmap(bmp, 0))
                .addOnCompleteListener(executor) { task ->
                    try {
                        if (task.isSuccessful) {
                            consecutiveFailures = 0
                            publish(task.result, width, height, timestampNs)
                        } else {
                            onFailure(task.exception)
                        }
                    } finally {
                        busy.set(false)
                    }
                }
            return true
        } catch (t: Throwable) {
            onFailure(t)
            busy.set(false)
            return false
        }
    }

    /** Copies the newest unconsumed result into [out]. Returns false when there is nothing new. */
    fun pollNew(out: FaceLandmarks): Boolean {
        synchronized(lock) {
            if (frontSeq == consumedSeq) return false
            consumedSeq = frontSeq
            resultTimestampNs = frontTimestampNs
            resultHasLandmarks = frontHasLandmarks
            resultFaceCount = frontFaceCount
            resultTrackingId = frontTrackingId
            if (frontHasLandmarks) out.copyFrom(front)
            return true
        }
    }

    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            runCatching { detector?.close() }
            detector = null
            // The bitmap may still be referenced by a finishing detection; let GC reclaim it.
            bitmap = null
        }
        executor.shutdown()
    }

    private fun createDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .setExecutor(executor)
            .build()
        return FaceDetection.getClient(options)
    }

    private fun onFailure(t: Throwable?) {
        consecutiveFailures++
        if (consecutiveFailures == 1 || consecutiveFailures == MAX_FAILURES) {
            RgLog.w(TAG, "Face detection failed ($consecutiveFailures)", t)
        }
    }

    /** Runs on the detection thread. */
    private fun publish(faces: List<Face>, width: Int, height: Int, timestampNs: Long) {
        // ML Kit only returns contours for the most prominent face; prefer the largest face that has them.
        var best: Face? = null
        var bestArea = -1
        for (f in faces) {
            if (f.getContour(FaceContour.FACE) == null) continue
            val area = f.boundingBox.width() * f.boundingBox.height()
            if (area > bestArea) { bestArea = area; best = f }
        }
        val ok = best != null && fill(best, height.toFloat())
        synchronized(lock) {
            if (ok) front.copyFrom(back)
            frontHasLandmarks = ok
            frontFaceCount = faces.size
            frontTrackingId = best?.trackingId ?: -1
            frontTimestampNs = timestampNs
            frontSeq++
        }
    }

    private fun fill(face: Face, height: Float): Boolean {
        val inv = 1f / height
        for (c in 0 until Contour.COUNT) {
            val pts = face.getContour(ML_KIT_TYPES[c])?.points ?: return false
            if (pts.size != Contour.SIZES[c]) return false
            for (i in pts.indices) {
                val p = pts[i]
                back.set(c, i, p.x * inv, p.y * inv)
            }
        }
        return true
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val MIN_FACE_SIZE = 0.15f
        private const val MAX_FAILURES = 8

        /** ML Kit contour type for each [Contour] index. */
        private val ML_KIT_TYPES = intArrayOf(
            FaceContour.FACE,
            FaceContour.LEFT_EYEBROW_TOP,
            FaceContour.LEFT_EYEBROW_BOTTOM,
            FaceContour.RIGHT_EYEBROW_TOP,
            FaceContour.RIGHT_EYEBROW_BOTTOM,
            FaceContour.LEFT_EYE,
            FaceContour.RIGHT_EYE,
            FaceContour.UPPER_LIP_TOP,
            FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP,
            FaceContour.LOWER_LIP_BOTTOM,
            FaceContour.NOSE_BRIDGE,
            FaceContour.NOSE_BOTTOM,
            FaceContour.LEFT_CHEEK,
            FaceContour.RIGHT_CHEEK,
        )
    }
}
