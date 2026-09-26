package com.ravango.engine.beauty.tracking

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPipe Face Landmarker in LIVE_STREAM mode: 478 landmarks (468 mesh + 10 iris), 52 blendshapes and the
 * facial transformation matrix for up to [DetectionFrame.MAX_FACES] faces.
 *
 * - The model (`assets/face_landmarker.task`) is memory-mapped when stored uncompressed, otherwise read into a
 *   direct buffer, on the tracker's own thread; the GPU delegate is tried first with an automatic CPU fallback
 *   (also at runtime, after repeated GPU errors).
 * - "Latest frame only": the camera GL thread copies a downscaled RGBA readback into [acquireInput]'s buffer and
 *   calls [commit]; while a detection is in flight new frames are simply not submitted, so nothing queues up.
 * - Timestamps handed to `detectAsync` are strictly increasing milliseconds derived from the frame clock.
 * - Results are double-buffered under a lock; [pollNew] copies the newest one on the GL thread (no allocation).
 */
internal class FaceLandmarkerTracker(context: Context) {

    enum class State { INITIALIZING, READY, FAILED }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rg-face-landmarker").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    }

    @Volatile var state: State = State.INITIALIZING; private set
    @Volatile var usingGpu: Boolean = false; private set
    @Volatile private var closed = false

    // Executor-thread state.
    private var landmarker: FaceLandmarker? = null
    private var model: ByteBuffer? = null
    private var consecutiveErrors = 0
    private var lastTimestampMs = -1L

    // Hand-off between the GL thread and the executor.
    private val busy = AtomicBoolean(false)
    @Volatile private var busySinceNs = 0L
    @Volatile private var input: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    @Volatile private var inputW = 0
    @Volatile private var inputH = 0
    @Volatile private var inputAspect = 1f
    @Volatile private var inputFrameNs = 0L
    private val detectTask = Runnable { runDetection() }

    // Double-buffered results.
    private val lock = Any()
    private val back = DetectionFrame()
    private val front = DetectionFrame()
    private var frontSeq = 0L
    private var consumedSeq = 0L
    /** Frame time (ns) of the image each in-flight timestamp belongs to; only one is in flight at a time. */
    @Volatile private var pendingFrameNs = 0L
    @Volatile private var pendingAspect = 1f

    val isReady: Boolean get() = state == State.READY && !closed
    val isFailed: Boolean get() = state == State.FAILED

    fun start() {
        executor.execute { initialize(preferGpu = true) }
    }

    /** True when a new frame may be submitted now (ready, and no detection in flight or the last one timed out). */
    fun canAccept(nowNs: Long = System.nanoTime()): Boolean {
        if (!isReady) return false
        if (!busy.get()) return true
        if (nowNs - busySinceNs > STALL_TIMEOUT_NS) {
            // A frame dropped inside the graph produces no callback: recover instead of waiting forever. Use a fresh
            // input buffer in case the graph still references the old one.
            RgLog.w(TAG, "Face landmarker stalled; resubmitting")
            input = ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.nativeOrder())
            busy.set(false)
            return true
        }
        return false
    }

    /** The RGBA buffer (top-down rows) to fill for a [width]×[height] frame. Only valid when [canAccept]. */
    fun acquireInput(width: Int, height: Int): ByteBuffer {
        val bytes = width * height * 4
        if (input.capacity() != bytes) input = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        input.clear()
        return input
    }

    /**
     * Starts detection on the buffer filled after [acquireInput]. [aspect] is the frame's width / height, used to
     * convert normalized landmarks into isotropic frame space. [frameTimestampNs] is the analysed frame's time.
     */
    fun commit(width: Int, height: Int, aspect: Float, frameTimestampNs: Long): Boolean {
        if (!isReady || !busy.compareAndSet(false, true)) return false
        busySinceNs = System.nanoTime()
        inputW = width
        inputH = height
        inputAspect = aspect
        inputFrameNs = frameTimestampNs
        try {
            executor.execute(detectTask)
        } catch (t: Throwable) {
            busy.set(false)
            return false
        }
        return true
    }

    /** Copies the newest unconsumed result into [out]; false when there is nothing new. */
    fun pollNew(out: DetectionFrame): Boolean {
        synchronized(lock) {
            if (frontSeq == consumedSeq) return false
            consumedSeq = frontSeq
            out.copyFrom(front)
            return true
        }
    }

    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            runCatching { landmarker?.close() }
            landmarker = null
            model = null
        }
        executor.shutdown()
    }

    // ------------------------------------------------------------------------------------------ executor thread

    private fun initialize(preferGpu: Boolean) {
        if (closed) return
        val started = SystemClock.elapsedRealtime()
        val buffer = model ?: try {
            loadModel().also { model = it }
        } catch (t: Throwable) {
            RgLog.e(TAG, "Face landmarker model could not be loaded", t)
            state = State.FAILED
            return
        }
        runCatching { landmarker?.close() }
        landmarker = null
        val order = if (preferGpu) arrayOf(Delegate.GPU, Delegate.CPU) else arrayOf(Delegate.CPU)
        for (delegate in order) {
            try {
                landmarker = FaceLandmarker.createFromOptions(appContext, options(buffer, delegate))
                usingGpu = delegate == Delegate.GPU
                consecutiveErrors = 0
                state = State.READY
                RgLog.i(TAG, "Face landmarker ready on $delegate in ${SystemClock.elapsedRealtime() - started} ms")
                return
            } catch (t: Throwable) {
                // UnsatisfiedLinkError / RuntimeException from the native graph: try the next delegate.
                RgLog.w(TAG, "Face landmarker init on $delegate failed", t)
            }
        }
        state = State.FAILED
    }

    private fun options(buffer: ByteBuffer, delegate: Delegate): FaceLandmarker.FaceLandmarkerOptions =
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(DetectionFrame.MAX_FACES)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result: FaceLandmarkerResult, _: MPImage -> onResult(result) }
            .setErrorListener { e: RuntimeException -> onError(e) }
            .build()

    private fun loadModel(): ByteBuffer {
        // Memory-map when the asset is stored uncompressed (zero copy); otherwise read it into a direct buffer.
        try {
            appContext.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (_: Exception) {
            // Compressed in the APK: fall through.
        }
        val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    private fun runDetection() {
        val lm = landmarker
        if (closed || lm == null) { busy.set(false); return }
        try {
            val ms = maxOf(lastTimestampMs + 1, inputFrameNs / 1_000_000L)
            lastTimestampMs = ms
            pendingFrameNs = inputFrameNs
            pendingAspect = inputAspect
            input.rewind()
            val image = ByteBufferImageBuilder(input, inputW, inputH, MPImage.IMAGE_FORMAT_RGBA).build()
            lm.detectAsync(image, ms)
        } catch (t: Throwable) {
            busy.set(false)
            onError(t)
        }
    }

    /** MediaPipe callback thread. */
    private fun onResult(result: FaceLandmarkerResult) {
        try {
            consecutiveErrors = 0
            val faces = result.faceLandmarks()
            val count = minOf(faces.size, DetectionFrame.MAX_FACES)
            val aspect = pendingAspect
            val blend = result.faceBlendshapes().orElse(null)
            val matrices = result.facialTransformationMatrixes().orElse(null)
            var written = 0
            for (f in 0 until count) {
                val points = faces[f]
                if (points.size < FaceLandmarkIndex.LANDMARK_COUNT) continue
                val dst = back.landmarks[written]
                for (i in 0 until FaceLandmarkIndex.LANDMARK_COUNT) {
                    val p = points[i]
                    dst[i * 3] = p.x() * aspect
                    dst[i * 3 + 1] = p.y()
                    dst[i * 3 + 2] = p.z() * aspect
                }
                val shapes = blend?.getOrNull(f)
                back.hasBlendshapes[written] = shapes != null
                if (shapes != null) {
                    val out = back.blendshapes[written]
                    out.fill(0f)
                    for (k in shapes.indices) {
                        val c = shapes[k]
                        val index = c.index().takeIf { it in 0 until DetectionFrame.BLENDSHAPE_COUNT } ?: k
                        if (index < DetectionFrame.BLENDSHAPE_COUNT) out[index] = c.score()
                    }
                }
                val m = matrices?.getOrNull(f)
                back.hasMatrix[written] = m != null && m.size >= 16
                if (m != null && m.size >= 16) System.arraycopy(m, 0, back.matrices[written], 0, 16)
                written++
            }
            back.faceCount = written
            back.timestampNs = pendingFrameNs
            synchronized(lock) {
                front.copyFrom(back)
                frontSeq++
            }
        } catch (t: Throwable) {
            RgLog.w(TAG, "Could not read face landmarker result", t)
        } finally {
            busy.set(false)
        }
    }

    private fun onError(e: Throwable) {
        busy.set(false)
        consecutiveErrors++
        if (consecutiveErrors == 1 || consecutiveErrors == MAX_ERRORS) RgLog.w(TAG, "Face landmarker error ($consecutiveErrors)", e)
        if (consecutiveErrors >= MAX_ERRORS && !closed) {
            if (usingGpu) {
                RgLog.w(TAG, "Falling back to the CPU delegate")
                state = State.INITIALIZING
                executor.execute { initialize(preferGpu = false) }
            } else {
                state = State.FAILED
            }
        }
    }

    companion object {
        private const val TAG = "FaceLandmarker"
        const val MODEL_ASSET = "face_landmarker.task"
        private const val MAX_ERRORS = 3
        private const val STALL_TIMEOUT_NS = 1_000_000_000L
    }
}
