package com.ravango.engine.camera.muxer

import com.ravango.core.common.log.RgLog
import java.io.File

/** Result of turning a recording directory into a single MP4. */
internal data class FinalizedRecording(val file: File, val durationUs: Long, val manifest: RecordingManifest)

/**
 * Turns the finished segments of a recording directory into the final MP4 next to it and removes the directory.
 * Used after a normal stop and by crash recovery. When concatenation fails, the segments are kept so a later
 * attempt (next launch) can retry; nothing recorded is ever deleted on failure.
 */
internal object RecordingFinalizer {
    private const val TAG = "RecFinalizer"

    fun finalize(dir: File, manifest: RecordingManifest, outputDir: File): FinalizedRecording? {
        val segments = manifest.segments.filter { File(dir, it.file).let { f -> f.exists() && f.length() > 0 } }.sortedBy { it.index }
        if (segments.isEmpty()) {
            RgLog.w(TAG, "no finished segments in ${dir.name}")
            dir.deleteRecursively()
            return null
        }
        val output = uniqueFile(outputDir, manifest.finalName)
        val duration = try {
            if (segments.size == 1) {
                // A single segment is already a complete MP4 starting at 0: move it (instant, lossless).
                val src = File(dir, segments[0].file)
                if (!src.renameTo(output)) {
                    src.copyTo(output, overwrite = true)
                }
                segments[0].durationUs
            } else {
                Mp4Concatenator.concat(dir, segments, output)
            }
        } catch (e: Exception) {
            RgLog.e(TAG, "finalizing ${dir.name} failed; segments kept for retry", e)
            output.delete()
            return null
        }
        dir.deleteRecursively()
        return FinalizedRecording(output, maxOf(duration, manifest.totalDurationUs), manifest)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mp4")
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$n.$ext")
            n++
        }
        return candidate
    }
}
