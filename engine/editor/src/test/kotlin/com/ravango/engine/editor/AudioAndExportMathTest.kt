package com.ravango.engine.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.ColorAdjustments
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.FilterPreset
import com.ravango.core.model.VideoCodec
import com.ravango.engine.editor.audio.GainEnvelope
import com.ravango.engine.editor.audio.SpeechActivityAnalyzer
import com.ravango.engine.editor.composition.CanvasSizing
import com.ravango.engine.editor.effects.GradeParams
import com.ravango.engine.editor.export.ExportMath
import com.ravango.engine.editor.media.WaveformProvider
import org.junit.Test

class AudioAndExportMathTest {
    @Test
    fun gainEnvelope_fadesAndVolume() {
        val e = GainEnvelope(volume = 0.5f, fadeInUs = S, fadeOutUs = S, durationUs = 10 * S)
        assertThat(e.gainAt(0)).isEqualTo(0f)
        assertThat(e.gainAt(5 * S)).isEqualTo(0.5f)
        assertThat(e.gainAt(500_000)).isWithin(1e-3f).of(0.25f)
        assertThat(e.gainAt(10 * S)).isEqualTo(0f)
        assertThat(GainEnvelope(1f).isUnity).isTrue()
    }

    @Test
    fun gainEnvelope_ducksAroundSpeech() {
        val e = GainEnvelope(volume = 1f, duckRanges = listOf(2 * S..3 * S), duckGain = 0.3f, duckRampUs = 200_000)
        assertThat(e.gainAt(2_500_000)).isWithin(1e-3f).of(0.3f)
        assertThat(e.gainAt(1 * S)).isEqualTo(1f)
        assertThat(e.gainAt(1_900_000)).isWithin(1e-3f).of(0.65f)
    }

    @Test
    fun mergeRanges_mergesOverlaps() {
        assertThat(GainEnvelope.mergeRanges(listOf(5L..8L, 1L..3L, 2L..4L))).containsExactly(1L..4L, 5L..8L).inOrder()
    }

    @Test
    fun speechDetection_findsLoudRegion() {
        val levels = FloatArray(100) { if (it in 30..59) -20f else -60f }
        val ranges = SpeechActivityAnalyzer.detect(levels, 30_000)
        assertThat(ranges).hasSize(1)
        assertThat(ranges.single().first).isEqualTo(30 * 30_000L)
    }

    @Test
    fun canvasSizing_evenAndOriented() {
        assertThat(CanvasSizing.size(AspectRatioSpec.Portrait9x16, 1080)).isEqualTo(1080 to 1920)
        assertThat(CanvasSizing.size(AspectRatioSpec.Landscape16x9, 720)).isEqualTo(1280 to 720)
        assertThat(CanvasSizing.size(AspectRatioSpec.Square1x1, 480)).isEqualTo(480 to 480)
        val (w, h) = CanvasSizing.size(AspectRatioSpec.Portrait4x5, 1080)
        assertThat(w % 2).isEqualTo(0); assertThat(h % 2).isEqualTo(0)
        assertThat(h).isEqualTo(1350)
    }

    @Test
    fun export_clampsToPlan() {
        val free = Entitlements()
        val clamped = ExportMath.clamp(ExportSettings(resolutionShortSide = 2160, frameRate = 60), free)
        assertThat(clamped.resolutionShortSide).isEqualTo(1080)
        assertThat(clamped.frameRate).isEqualTo(30)
        val pro = Entitlements(maxExportShortSide = 2160, maxExportFps = 60)
        assertThat(ExportMath.clamp(ExportSettings(resolutionShortSide = 2160, frameRate = 60), pro).frameRate).isEqualTo(60)
        assertThat(ExportMath.clampResolution(1000, 2160)).isEqualTo(720)
    }

    @Test
    fun autoBitrate_scalesWithPixelsAndCodec() {
        val h264 = ExportMath.autoBitrate(1080, 1920, 30, VideoCodec.H264)
        val hevc = ExportMath.autoBitrate(1080, 1920, 30, VideoCodec.HEVC)
        val uhd = ExportMath.autoBitrate(2160, 3840, 30, VideoCodec.H264)
        assertThat(h264).isIn(5_000_000..10_000_000)
        assertThat(hevc).isLessThan(h264)
        assertThat(uhd).isGreaterThan(h264 * 3)
    }

    @Test
    fun estimateBytes() {
        val bytes = ExportMath.estimateBytes(10 * S, 8_000_000, 192_000, includeAudio = true)
        assertThat(bytes).isIn(10_000_000L..11_000_000L)
    }

    @Test
    fun waveformSlice_takesPeaks() {
        val data = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f)
        val s = WaveformProvider.slice(data, 4 * S, 0, 4 * S, 2)
        assertThat(s.toList()).containsExactly(0.9f, 0.3f).inOrder()
    }

    @Test
    fun grade_blendsLookAndAdjustments() {
        assertThat(GradeParams.resolve(FilterPreset.NONE, 1f, ColorAdjustments()).isNeutral).isTrue()
        val half = GradeParams.resolve(FilterPreset.MONO, 0.5f, ColorAdjustments(exposure = 0.2f))
        assertThat(half.saturation).isWithin(1e-4f).of(-0.5f)
        assertThat(half.exposure).isWithin(1e-4f).of(0.2f)
        assertThat(GradeParams.resolve(FilterPreset.NOIR, 1f, ColorAdjustments(saturation = -1f)).saturation).isEqualTo(-1f)
    }
}
