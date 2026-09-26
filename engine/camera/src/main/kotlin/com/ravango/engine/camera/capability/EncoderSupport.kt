package com.ravango.engine.camera.capability

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.VideoCodec
import javax.inject.Inject
import javax.inject.Singleton

/** Queries MediaCodec encoders (cached). */
@Singleton
class EncoderSupport @Inject constructor() {

    private val encoders: List<MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
            .onFailure { RgLog.e(TAG, "MediaCodecList failed", it) }
            .getOrDefault(emptyList())
    }

    private val cache = HashMap<Long, Boolean>()
    private val hardwareCache = HashMap<Long, Boolean>()

    private fun encodersFor(mime: String): List<MediaCodecInfo> =
        encoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .sortedByDescending { !isSoftware(it) }

    fun availableCodecs(): Set<VideoCodec> = VideoCodec.entries.filterTo(LinkedHashSet()) { encodersFor(it.mimeType).isNotEmpty() }

    @Synchronized
    fun supports(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean =
        cache.getOrPut(key(codec, width, height, fps)) {
            encodersFor(codec.mimeType).any { info -> canEncode(info, codec, width, height, fps) }
        }

    /** True when a hardware (non-software) encoder can encode the configuration. */
    @Synchronized
    fun supportsInHardware(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean =
        hardwareCache.getOrPut(key(codec, width, height, fps)) {
            encodersFor(codec.mimeType).any { info -> !isSoftware(info) && canEncode(info, codec, width, height, fps) }
        }

    /** Bitrate range of the preferred encoder for [codec]. */
    fun bitrateRange(codec: VideoCodec): IntRange? = encodersFor(codec.mimeType).firstNotNullOfOrNull { info ->
        runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities?.bitrateRange?.let { it.lower..it.upper } }.getOrNull()
    }

    /** Preferred (hardware first) encoder able to encode the exact configuration. */
    fun encoderFor(codec: VideoCodec, width: Int, height: Int, fps: Int): MediaCodecInfo? =
        encodersFor(codec.mimeType).firstOrNull { info -> canEncode(info, codec, width, height, fps) }
            ?: encodersFor(codec.mimeType).firstOrNull()

    private fun canEncode(info: MediaCodecInfo, codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean =
        runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true }
            .getOrDefault(false)

    private fun key(codec: VideoCodec, width: Int, height: Int, fps: Int): Long =
        (codec.ordinal.toLong() shl 60) or (width.toLong() shl 40) or (height.toLong() shl 20) or fps.toLong()

    companion object {
        private const val TAG = "EncoderSupport"

        /** Platform software encoders (OMX.google.* / c2.android.*), too slow for high-resolution realtime video. */
        fun isSoftwareName(name: String?): Boolean {
            if (name == null) return false
            val n = name.lowercase()
            return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")
        }

        fun isSoftware(info: MediaCodecInfo): Boolean {
            if (isSoftwareName(info.name)) return true
            return Build.VERSION.SDK_INT >= 29 && (info.isSoftwareOnly || !info.isHardwareAccelerated)
        }
    }
}
