package com.ravango.engine.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.PcmDecoder
import com.ravango.core.media.toUri
import com.ravango.core.model.MediaKind
import com.ravango.engine.editor.effects.ImageLoading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small frames for the timeline's thumbnail strips. Frames are grabbed at the nearest sync sample (fast), scaled to
 * [heightPx] and kept in an LRU (by byte size). One [MediaMetadataRetriever] is kept open per recently used source.
 */
@Singleton
class ThumbnailProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val retrievers = object : LruCache<String, MediaMetadataRetriever>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: MediaMetadataRetriever, newValue: MediaMetadataRetriever?) {
            runCatching { oldValue.release() }
        }
    }
    /** All grabs are serialized: retrievers are shared and evicting one while in use would release it mid-call. */
    private val grabLock = Mutex()

    fun cached(uri: String, timeUs: Long, heightPx: Int): Bitmap? = cache.get(key(uri, timeUs, heightPx))

    suspend fun frame(uri: String, kind: MediaKind, timeUs: Long, heightPx: Int): Bitmap? {
        val k = key(uri, timeUs, heightPx)
        cache.get(k)?.let { return it }
        return withContext(io) {
            grabLock.withLock {
                cache.get(k)?.let { return@withLock it }
                val bmp = try {
                    if (kind == MediaKind.IMAGE) ImageLoading.decode(context, uri, heightPx * 2)?.let { scale(it, heightPx) }
                    else grab(uri, timeUs, heightPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RgLog.w(TAG, "Thumbnail failed for $uri@$timeUs", e)
                    null
                }
                bmp?.also { cache.put(k, it) }
            }
        }
    }

    private fun grab(uri: String, timeUs: Long, heightPx: Int): Bitmap? {
        val r = synchronized(retrievers) {
            retrievers.get(uri) ?: MediaMetadataRetriever().also { mmr ->
                val u = uri.toUri()
                if (u.scheme == null || u.scheme == "file") mmr.setDataSource(u.path) else mmr.setDataSource(context, u)
                retrievers.put(uri, mmr)
            }
        }
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            // Ask for a frame about 1.8x the target height, then scale down precisely.
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, heightPx * 4, heightPx * 2)
        } else {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } ?: return null
        return scale(frame, heightPx)
    }

    private fun scale(b: Bitmap, heightPx: Int): Bitmap {
        if (b.height <= heightPx) return b
        val w = (b.width * heightPx.toFloat() / b.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, heightPx, true).also { if (it !== b) b.recycle() }
    }

    /** Quantized to 1/10 s so neighbouring requests share cache entries. */
    private fun key(uri: String, timeUs: Long, heightPx: Int) = "$uri@${timeUs / 100_000}@$heightPx"

    fun trimMemory() {
        cache.evictAll()
        synchronized(retrievers) { retrievers.evictAll() }
    }

    private companion object {
        const val TAG = "Thumbs"
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/**
 * Peak waveforms for audio lanes and clip audio. One array per source (≈50 buckets per second), cached in memory;
 * the UI slices the trimmed window.
 */
@Singleton
class WaveformProvider @Inject constructor(private val decoder: PcmDecoder) {
    private val cache = LruCache<String, FloatArray>(48)
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun cached(uri: String): FloatArray? = cache.get(uri)

    suspend fun waveform(uri: String, durationUs: Long): FloatArray? {
        cache.get(uri)?.let { return it }
        if (durationUs <= 0) return null
        return locks.getOrPut(uri) { Mutex() }.withLock {
            cache.get(uri)?.let { return@withLock it }
            val buckets = bucketsFor(durationUs)
            val data = try {
                decoder.waveform(uri, buckets, durationUs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w("Waveform", "Waveform failed for $uri", e)
                return@withLock null
            }
            normalize(data).also { cache.put(uri, it) }
        }
    }

    companion object {
        const val BUCKETS_PER_SECOND = 50

        fun bucketsFor(durationUs: Long): Int = ((durationUs / 1_000_000.0) * BUCKETS_PER_SECOND).toInt().coerceIn(16, 40_000)

        /** Scales peaks so the loudest is 1 (with a floor so quiet files still show shape). */
        fun normalize(data: FloatArray): FloatArray {
            val max = data.maxOrNull() ?: 0f
            if (max <= 0f) return data
            val k = 1f / maxOf(max, 0.05f)
            return FloatArray(data.size) { (data[it] * k).coerceIn(0f, 1f) }
        }

        /** Values for the source window [startUs, endUs) resampled to [count] points (max of each bin). */
        fun slice(data: FloatArray, sourceDurationUs: Long, startUs: Long, endUs: Long, count: Int): FloatArray {
            if (count <= 0 || data.isEmpty() || sourceDurationUs <= 0) return FloatArray(count.coerceAtLeast(0))
            val out = FloatArray(count)
            val from = (startUs.toDouble() / sourceDurationUs * data.size)
            val to = (endUs.toDouble() / sourceDurationUs * data.size)
            val span = (to - from) / count
            for (i in 0 until count) {
                val a = (from + i * span).toInt().coerceIn(0, data.lastIndex)
                val b = (from + (i + 1) * span).toInt().coerceIn(a + 1, data.size)
                var m = 0f
                for (j in a until b) if (data[j] > m) m = data[j]
                out[i] = m
            }
            return out
        }
    }
}
