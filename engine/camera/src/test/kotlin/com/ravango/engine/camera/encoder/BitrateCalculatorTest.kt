package com.ravango.engine.camera.encoder

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BitrateProfile
import com.ravango.core.model.VideoCodec
import org.junit.Test

class BitrateCalculatorTest {

    @Test
    fun `1080p30 h264 high is about 14 Mbps`() {
        assertThat(BitrateCalculator.videoBitrate(1920, 1080, 30, VideoCodec.H264, BitrateProfile.HIGH)).isEqualTo(13_900_000)
        assertThat(BitrateCalculator.videoBitrate(1080, 1920, 30, VideoCodec.H264, BitrateProfile.HIGH)).isEqualTo(13_900_000)
    }

    @Test
    fun `hevc needs less than h264 and higher profiles need more`() {
        val h264 = BitrateCalculator.videoBitrate(3840, 2160, 30, VideoCodec.H264, BitrateProfile.STANDARD)
        val hevc = BitrateCalculator.videoBitrate(3840, 2160, 30, VideoCodec.HEVC, BitrateProfile.STANDARD)
        val max = BitrateCalculator.videoBitrate(3840, 2160, 30, VideoCodec.H264, BitrateProfile.MAX)
        assertThat(hevc).isLessThan(h264)
        assertThat(max).isGreaterThan(h264)
    }

    @Test
    fun `frame rate scales sub-linearly`() {
        val b30 = BitrateCalculator.videoBitrate(1920, 1080, 30, VideoCodec.H264, BitrateProfile.STANDARD)
        val b60 = BitrateCalculator.videoBitrate(1920, 1080, 60, VideoCodec.H264, BitrateProfile.STANDARD)
        assertThat(b60).isGreaterThan(b30)
        assertThat(b60).isLessThan(b30 * 2)
    }

    @Test
    fun `bitrate respects bounds and the encoder range`() {
        assertThat(BitrateCalculator.videoBitrate(160, 90, 24, VideoCodec.HEVC, BitrateProfile.STANDARD)).isEqualTo(BitrateCalculator.MIN_VIDEO_BPS)
        assertThat(BitrateCalculator.videoBitrate(3840, 2160, 60, VideoCodec.H264, BitrateProfile.MAX, 1_000_000..40_000_000)).isEqualTo(40_000_000)
    }

    @Test
    fun `audio bitrate and totals`() {
        assertThat(BitrateCalculator.audioBitrate(192)).isEqualTo(192_000)
        assertThat(BitrateCalculator.audioBitrate(16)).isEqualTo(64_000)
        assertThat(BitrateCalculator.totalBitsPerSecond(10_000_000, 192_000)).isEqualTo(10_293_920)
    }
}
