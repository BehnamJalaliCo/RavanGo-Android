package com.ravango.engine.audio.internal

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioInputType
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioInternalsTest {

    @Test
    fun deviceTypeMapping() {
        assertThat(InputTypeMapper.map(15)).isEqualTo(AudioInputType.BUILT_IN)
        assertThat(InputTypeMapper.map(3)).isEqualTo(AudioInputType.WIRED)
        assertThat(InputTypeMapper.map(11)).isEqualTo(AudioInputType.USB)
        assertThat(InputTypeMapper.map(22)).isEqualTo(AudioInputType.USB)
        assertThat(InputTypeMapper.map(12)).isEqualTo(AudioInputType.USB)
        assertThat(InputTypeMapper.map(7)).isEqualTo(AudioInputType.BLUETOOTH)
        assertThat(InputTypeMapper.map(26)).isEqualTo(AudioInputType.BLUETOOTH_LE)
        assertThat(InputTypeMapper.map(9)).isEqualTo(AudioInputType.HDMI)
        assertThat(InputTypeMapper.map(18)).isNull() // telephony
        assertThat(InputTypeMapper.map(25)).isNull() // remote submix
        assertThat(InputTypeMapper.map(16)).isNull() // FM tuner
    }

    @Test
    fun resolvePrefersNamedThenTypeThenBuiltIn() {
        val builtIn = AudioInputDevice(1, "Phone microphone", AudioInputType.BUILT_IN)
        val usbA = AudioInputDevice(2, "Rode · USB microphone", AudioInputType.USB)
        val usbB = AudioInputDevice(3, "Zoom · USB microphone", AudioInputType.USB)
        val all = listOf(usbA, usbB, builtIn)
        assertThat(AudioDeviceCatalog.resolve(AudioInputType.USB, "Zoom · USB microphone", all)).isEqualTo(usbB)
        assertThat(AudioDeviceCatalog.resolve(AudioInputType.USB, "Gone", all)).isEqualTo(usbA)
        assertThat(AudioDeviceCatalog.resolve(AudioInputType.BLUETOOTH, null, all)).isEqualTo(builtIn)
        assertThat(AudioDeviceCatalog.resolve(AudioInputType.BUILT_IN, null, emptyList())).isNull()
    }

    @Test
    fun converterPassesThroughSameFormat() {
        val c = PcmConverter(48_000, 1)
        assertThat(c.isPassthrough).isTrue()
    }

    @Test
    fun converterMonoToStereoAndBack() {
        val up = PcmConverter(48_000, 2)
        up.setSource(48_000, 1)
        val mono = ShortArray(480) { (it * 10).toShort() }
        var produced = up.convert(mono, mono.size)
        // One frame of look-behind: first call yields n−1 frames, then streaming continues seamlessly.
        assertThat(produced).isEqualTo(479 * 2)
        for (i in 0 until 479) {
            assertThat(up.output[2 * i]).isEqualTo(mono[i])
            assertThat(up.output[2 * i + 1]).isEqualTo(mono[i])
        }
        produced = up.convert(mono, mono.size)
        assertThat(produced).isEqualTo(480 * 2)
        assertThat(up.output[0]).isEqualTo(mono[479]) // carried-over frame

        val down = PcmConverter(48_000, 1)
        down.setSource(48_000, 2)
        val stereo = ShortArray(20) { if (it % 2 == 0) 1000 else 3000 }
        val n = down.convert(stereo, stereo.size)
        assertThat(n).isEqualTo(9)
        assertThat(down.output[0]).isEqualTo(2000.toShort())
    }

    @Test
    fun converterResamplesWithCorrectRate() {
        val c = PcmConverter(48_000, 1)
        c.setSource(44_100, 1)
        var total = 0
        var maxErr = 0.0
        var outIndex = 0
        repeat(100) { block ->
            val input = ShortArray(441) { i -> (10000 * sin(2 * PI * 440 * (block * 441 + i) / 44_100)).toInt().toShort() }
            val n = c.convert(input, input.size)
            for (i in 0 until n) {
                val expected = 10000 * sin(2 * PI * 440 * outIndex / 48_000.0)
                maxErr = maxOf(maxErr, abs(c.output[i] - expected))
                outIndex++
            }
            total += n
        }
        assertThat(total.toDouble()).isWithin(2.0).of(48_000.0)
        assertThat(maxErr).isLessThan(60.0) // linear interpolation error on a 440 Hz tone
    }

    @Test
    fun clockUsesHardwareTimestampAndStaysMonotonic() {
        val clock = PresentationClock(48_000)
        clock.onHardwareTimestamp(framePosition = 4800, nanoTime = 1_000_000_000L)
        assertThat(clock.ptsFor(framesBefore = 4800, chunkFrames = 480, nowNs = 0)).isEqualTo(1_000_000_000L)
        assertThat(clock.ptsFor(framesBefore = 5280, chunkFrames = 480, nowNs = 0)).isEqualTo(1_010_000_000L)
        // A timestamp moving backwards never produces a non-increasing pts.
        clock.onHardwareTimestamp(framePosition = 0, nanoTime = 0)
        assertThat(clock.ptsFor(framesBefore = 480, chunkFrames = 480, nowNs = 0)).isEqualTo(1_010_000_001L)
    }

    @Test
    fun clockFallbackAnchorsOnFirstRead() {
        val clock = PresentationClock(48_000)
        val first = clock.ptsFor(framesBefore = 0, chunkFrames = 480, nowNs = 5_000_000_000L)
        assertThat(first).isEqualTo(4_990_000_000L)
        assertThat(clock.ptsFor(framesBefore = 480, chunkFrames = 480, nowNs = 9_999_999_999L)).isEqualTo(5_000_000_000L)
        clock.reset(44_100)
        val afterRestart = clock.ptsFor(framesBefore = 0, chunkFrames = 441, nowNs = 6_000_000_000L)
        assertThat(afterRestart).isEqualTo(5_990_000_000L)
    }

    @Test
    fun ballisticsHoldDecayAndClip() {
        val b = LevelBallistics(holdMs = 500, decayDbPerSecond = 20f, clipHoldMs = 1000)
        var l = b.update(-6f, -12f, clipped = true, nowMs = 0)
        assertThat(l.peakDbfs).isEqualTo(-6f)
        assertThat(l.clipping).isTrue()
        l = b.update(-40f, -40f, clipped = false, nowMs = 400)
        assertThat(l.peakDbfs).isEqualTo(-6f) // held
        assertThat(l.rmsDbfs).isLessThan(-12f)
        l = b.update(-40f, -40f, clipped = false, nowMs = 1400)
        assertThat(l.peakDbfs.toDouble()).isWithin(0.01).of(-26.0) // 1 s × 20 dB/s
        assertThat(l.clipping).isFalse()
    }

    @Test
    fun adtsHeaderRoundTrip() {
        val header = ByteArray(7)
        Adts.writeHeader(header, Adts.frequencyIndex(48_000), 2, 371)
        val h = Adts.parseHeader(header)!!
        assertThat(h.frequencyIndex).isEqualTo(3)
        assertThat(h.channels).isEqualTo(2)
        assertThat(h.frameLength).isEqualTo(378)
        assertThat(h.headerSize).isEqualTo(7)
        assertThat(Adts.sampleRate(h.frequencyIndex)).isEqualTo(48_000)
        // AudioSpecificConfig for AAC-LC 48 kHz stereo is 0x11 0x90.
        assertThat(Adts.audioSpecificConfig(3, 2)).isEqualTo(byteArrayOf(0x11, 0x90.toByte()))
        assertThat(Adts.parseHeader(byteArrayOf(0, 0, 0, 0, 0, 0, 0))).isNull()
    }

    @Test
    fun ringBufferWrapsAndDropsOnOverflow() {
        val ring = ShortRing(8)
        assertThat(ring.write(shortArrayOf(1, 2, 3, 4, 5, 6), 0, 6)).isEqualTo(6)
        val dst = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        assertThat(ring.read(dst, 4)).isEqualTo(4)
        assertThat(ring.write(shortArrayOf(7, 8, 9, 10, 11, 12, 13), 0, 7)).isEqualTo(6) // one dropped
        val out = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        assertThat(ring.read(out, 100)).isEqualTo(8)
        out.flip()
        val values = ShortArray(8) { out.short }
        assertThat(values.toList()).containsExactly(5.toShort(), 6.toShort(), 7.toShort(), 8.toShort(), 9.toShort(), 10.toShort(), 11.toShort(), 12.toShort()).inOrder()
    }
}
