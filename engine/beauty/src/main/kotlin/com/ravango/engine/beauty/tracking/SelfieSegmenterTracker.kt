package com.ravango.engine.beauty.tracking

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.effects.MaskSmoother
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPipe Image Segmenter with the selfie segmentation model (`assets/selfie_segmenter.tflite`, Apache-2.0) in
 * LIVE_STREAM mode. Same hand-off pattern as [FaceLandmarkerTracker]: the camera GL thread fills a downscaled RGBA
 * readback and [commit]s it; only one frame is in flight, nothing queues up; GPU delegate first with CPU fallback.
 *
 * On the result thread the person-confidence mask is downscaled ×[MASK_FACTOR] and temporally smoothed
 * ([MaskSmoother]), then double-buffered; the GL thread copies the newest mask with [pollNew] (no allocation).
 */
internal class SelfieSegmenterTracker(context: Context) {

    enum class State { INITIALIZING, READY, FAILED }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rg-selfie-segmenter").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }

    @Volatile var state: State = State.INITIALIZING; private set
    @Volatile var usingGpu: Boolean = false; private set
    @Volatile private var closed = false

    // Executor state.
    private var segmenter: ImageSegmenter? = null
    private var model: ByteBuffer? = null
    private var consecutiveErrors = 0
    private var lastTimestampMs = -1L

    private val busy = AtomicBoolean(false)
    @Volatile private var busySinceNs = 0L
    @Volatile private var input: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    @Volatile private var inputW = 0
    @Volatile private var inputH = 0
    @Volatile private var inputFrameNs = 0L
    private val task = Runnable { run() }

    // Result thread state.
    private val smoother = MaskSmoother()
    private var floats = FloatArray(0)
    private var back = ByteArray(0)
    @Volatile private var resetRequested = false

    // Double buffer.
    private val lock = Any()
    private var front = ByteArray(0)
    private var frontW = 0
    private var frontH = 0
    private var frontSeq = 0L
    private var consumedSeq = 0L

    val isReady: Boolean get() = state == State.READY && !closed
    val isFailed: Boolean get() = state == State.FAILED

    /** True once at least one mask was produced since the last [reset]. */
    @Volatile var hasMask: Boolean = false; private set

    fun start() {
        executor.execute { initialize(preferGpu = true) }
    }

    /** Forgets the temporal history (e.g. after the effect was off for a while). */
    fun reset() {
        resetRequested = true
        hasMask = false
    }

    fun canAccept(nowNs: Long = System.nanoTime()): Boolean {
        if (!isReady) return false
        if (!busy.get()) return true
        if (nowNs - busySinceNs > STALL_TIMEOUT_NS) {
            input = ByteBuffer.allocateDirect(input.capacity()).order(ByteOrder.nativeOrder())
            busy.set(false)
            return true
        }
        return false
    }

    fun acquireInput(width: Int, height: Int): ByteBuffer {
        val bytes = width * height * 4
        if (input.capacity() != bytes) input = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        input.clear()
        return input
    }

    fun commit(width: Int, height: Int, frameTimestampNs: Long): Boolean {
        if (!isReady || !busy.compareAndSet(false, true)) return false
        busySinceNs = System.nanoTime()
        inputW = width
        inputH = height
        inputFrameNs = frameTimestampNs
        try {
            executor.execute(task)
        } catch (t: Throwable) {
            busy.set(false)
            return false
        }
        return true
    }

    /**
     * Copies the newest mask into [out] (capacity ≥ width × height bytes) and returns its size packed as
     * `width shl 16 or height`, or 0 when there is nothing new.
     */
    fun pollNew(out: ByteBuffer): Int {
        synchronized(lock) {
            if (frontSeq == consumedSeq || frontW == 0) return 0
            consumedSeq = frontSeq
            val n = frontW * frontH
            if (out.capacity() < n) return 0
            out.clear()
            out.put(front, 0, n)
            out.position(0)
            return (frontW shl 16) or frontH
        }
    }

    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            runCatching { segmenter?.close() }
            segmenter = null
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
            RgLog.e(TAG, "Selfie segmenter model could not be loaded", t)
            state = State.FAILED
            return
        }
        runCatching { segmenter?.close() }
        segmenter = null
        val order = if (preferGpu) arrayOf(Delegate.GPU, Delegate.CPU) else arrayOf(Delegate.CPU)
        for (delegate in order) {
            try {
                segmenter = ImageSegmenter.createFromOptions(appContext, options(buffer, delegate))
                usingGpu = delegate == Delegate.GPU
                consecutiveErrors = 0
                state = State.READY
                RgLog.i(TAG, "Selfie segmenter ready on $delegate in ${SystemClock.elapsedRealtime() - started} ms")
                return
            } catch (t: Throwable) {
                RgLog.w(TAG, "Selfie segmenter init on $delegate failed", t)
            }
        }
        state = State.FAILED
    }

    private fun options(buffer: ByteBuffer, delegate: Delegate): ImageSegmenter.ImageSegmenterOptions =
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .setResultListener { result: ImageSegmenterResult, _: MPImage -> onResult(result) }
            .setErrorListener { e: RuntimeException -> onError(e) }
            .build()

    private fun loadModel(): ByteBuffer {
        try {
            appContext.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (_: Exception) {
            // Compressed in the APK.
        }
        val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    private fun run() {
        val seg = segmenter
        if (closed || seg == null) { busy.set(false); return }
        try {
            val ms = maxOf(lastTimestampMs + 1, inputFrameNs / 1_000_000L)
            lastTimestampMs = ms
            input.rewind()
            val image = ByteBufferImageBuilder(input, inputW, inputH, MPImage.IMAGE_FORMAT_RGBA).build()
            seg.segmentAsync(image, ms)
        } catch (t: Throwable) {
            busy.set(false)
            onError(t)
        }
    }

    /** MediaPipe callback thread. */
    private fun onResult(result: ImageSegmenterResult) {
        try {
            consecutiveErrors = 0
            val masks = result.confidenceMasks().orElse(null) ?: return
            if (masks.isEmpty()) return
            // One mask = person confidence; multi-class models put the background first.
            val invert = masks.size > 1
            val mask = masks[0]
            val w = mask.width
            val h = mask.height
            if (w <= 0 || h <= 0) return
            val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder())
            val fb = buffer.asFloatBuffer()
            val n = w * h
            if (fb.remaining() < n) return
            if (floats.size != n) floats = FloatArray(n)
            fb.get(floats, 0, n)
            if (invert) for (i in 0 until n) floats[i] = 1f - floats[i]
            if (resetRequested) { resetRequested = false; smoother.reset() }
            val factor = if (w > 300) MASK_FACTOR else 1
            val outW = (w / factor).coerceAtLeast(1)
            val outH = (h / factor).coerceAtLeast(1)
            if (back.size != outW * outH) back = ByteArray(outW * outH)
            smoother.update(floats, w, h, factor, back)
            synchronized(lock) {
                if (front.size != back.size) front = ByteArray(back.size)
                System.arraycopy(back, 0, front, 0, back.size)
                frontW = smoother.width
                frontH = smoother.height
                frontSeq++
            }
            hasMask = true
        } catch (t: Throwable) {
            RgLog.w(TAG, "Could not read segmentation result", t)
        } finally {
            busy.set(false)
        }
    }

    private fun onError(e: Throwable) {
        busy.set(false)
        consecutiveErrors++
        if (consecutiveErrors == 1 || consecutiveErrors == MAX_ERRORS) RgLog.w(TAG, "Selfie segmenter error ($consecutiveErrors)", e)
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
        private const val TAG = "SelfieSegmenter"
        const val MODEL_ASSET = "selfie_segmenter.tflite"
        /** Masks wider than 300 px are box-downscaled by this factor before upload. */
        const val MASK_FACTOR = 2
        /** Largest mask (bytes) the GL side must be able to take. */
        const val MAX_MASK_BYTES = 512 * 512
        private const val MAX_ERRORS = 3
        private const val STALL_TIMEOUT_NS = 1_000_000_000L
    }
}
