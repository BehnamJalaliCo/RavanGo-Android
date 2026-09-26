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

    private fun encodersFor(mime: String): List<MediaCodecInfo> =
        encoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .sortedByDescending { if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.") }

    fun availableCodecs(): Set<VideoCodec> = VideoCodec.entries.filterTo(LinkedHashSet()) { encodersFor(it.mimeType).isNotEmpty() }

    @Synchronized
    fun supports(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean {
        val key = (codec.ordinal.toLong() shl 60) or (width.toLong() shl 40) or (height.toLong() shl 20) or fps.toLong()
        return cache.getOrPut(key) {
            encodersFor(codec.mimeType).any { info ->
                runCatching {
                    val video = info.getCapabilitiesForType(codec.mimeType).videoCapabilities ?: return@runCatching false
                    video.areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
            }
        }
    }

    /** Bitrate range of the preferred encoder for [codec]. */
    fun bitrateRange(codec: VideoCodec): IntRange? = encodersFor(codec.mimeType).firstNotNullOfOrNull { info ->
        runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities?.bitrateRange?.let { it.lower..it.upper } }.getOrNull()
    }

    /** Preferred (hardware first) encoder able to encode the exact configuration. */
    fun encoderFor(codec: VideoCodec, width: Int, height: Int, fps: Int): MediaCodecInfo? =
        encodersFor(codec.mimeType).firstOrNull { info ->
            runCatching { info.getCapabilitiesForType(codec.mimeType).videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true }.getOrDefault(false)
        } ?: encodersFor(codec.mimeType).firstOrNull()

    private companion object {
        const val TAG = "EncoderSupport"
    }
}
