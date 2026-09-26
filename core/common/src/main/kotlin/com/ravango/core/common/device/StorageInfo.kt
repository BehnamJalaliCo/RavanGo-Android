package com.ravango.core.common.device

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageSnapshot(val availableBytes: Long, val totalBytes: Long) {
    val usedFraction: Float get() = if (totalBytes == 0L) 0f else 1f - availableBytes.toFloat() / totalBytes

    /** Seconds of recording that fit at [bitsPerSecond], keeping [reserveBytes] free for the system and muxer overhead. */
    fun recordableSeconds(bitsPerSecond: Long, reserveBytes: Long = RESERVE_BYTES): Long {
        if (bitsPerSecond <= 0) return Long.MAX_VALUE
        val usable = (availableBytes - reserveBytes).coerceAtLeast(0)
        return usable * 8 / bitsPerSecond
    }

    companion object {
        const val RESERVE_BYTES = 300L * 1024 * 1024
    }
}

@Singleton
class StorageInfo @Inject constructor(@ApplicationContext private val context: Context) {

    /** Directory where recordings are written before being published to the gallery. */
    val mediaDir: File get() = (context.getExternalFilesDir("media") ?: File(context.filesDir, "media")).apply { mkdirs() }
    val cacheMediaDir: File get() = File(context.cacheDir, "media").apply { mkdirs() }
    val exportsDir: File get() = File(mediaDir, "exports").apply { mkdirs() }
    val recordingsDir: File get() = File(mediaDir, "recordings").apply { mkdirs() }

    fun snapshot(dir: File = mediaDir): StorageSnapshot = runCatching {
        val stat = StatFs(dir.absolutePath)
        StorageSnapshot(stat.availableBytes, stat.totalBytes)
    }.getOrDefault(StorageSnapshot(0, 0))
}
