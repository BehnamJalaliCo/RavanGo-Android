package com.ravango.engine.ai.speech

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.WordTiming
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.TranscriptSegment
import com.ravango.engine.ai.prompts.PromptLibrary
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.api.TextTask
import com.ravango.core.model.AiOperation
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechPipelineTest {

    @Test
    fun `whisper verbose json with words is parsed into segments`() {
        val body = """
            {"task":"transcribe","language":"persian","duration":3.2,"text":"سلام دوستان خوبید",
             "segments":[{"id":0,"start":0.0,"end":3.0,"text":" سلام دوستان خوبید","no_speech_prob":0.01}],
             "words":[{"word":"سلام","start":0.1,"end":0.5},{"word":"دوستان","start":0.6,"end":1.2},{"word":"خوبید","start":1.3,"end":2.0}]}
        """.trimIndent()
        val t = WhisperParser.parse(body)!!
        assertThat(t.language).isEqualTo("fa")
        assertThat(t.segments).hasSize(1)
        assertThat(t.segments[0].words.map { it.text }).containsExactly("سلام", "دوستان", "خوبید").inOrder()
        assertThat(t.segments[0].words[1].startUs).isEqualTo(600_000)
    }

    @Test
    fun `segment-only responses get estimated word timings`() {
        val t = WhisperParser.parse("""{"language":"english","segments":[{"start":1.0,"end":3.0,"text":"hello big world"}]}""")!!
        val words = t.segments.single().words
        assertThat(words).hasSize(3)
        assertThat(words.first().startUs).isEqualTo(1_000_000)
        assertThat(words.last().endUs).isAtMost(3_000_000)
        assertThat(words.zipWithNext().all { (a, b) -> a.endUs <= b.startUs }).isTrue()
    }

    @Test
    fun `merger offsets chunks and removes duplicated overlap words`() {
        fun chunk(vararg words: Pair<String, Long>) = Transcript(
            "en",
            listOf(TranscriptSegment(words.first().second, words.last().second + 300_000, words.joinToString(" ") { it.first }, words.map { WordTiming(it.first, it.second, it.second + 300_000) })),
        )
        // Chunk 0 covers 0–10 s, chunk 1 starts at 8.5 s (1.5 s overlap). "overlap" is spoken at 9.0 s.
        val c0 = TranscriptMerger.ChunkResult(0, 10_000_000, chunk("start" to 1_000_000, "overlap" to 9_000_000))
        val c1 = TranscriptMerger.ChunkResult(8_500_000, 10_000_000, chunk("overlap" to 500_000, "end" to 3_000_000))
        val merged = TranscriptMerger.merge(listOf(c1, c0))
        val words = merged.segments.flatMap { it.words }
        assertThat(words.map { it.text }).containsExactly("start", "overlap", "end").inOrder()
        assertThat(words.last().startUs).isEqualTo(11_500_000)
    }

    @Test
    fun `resampler converts 48k sine to 16k with correct length and frequency`() {
        val input = FloatArray(48_000) { (0.5 * sin(2 * PI * 440 * it / 48_000.0)).toFloat() }
        val r = Resampler(48_000, 16_000)
        val out = r.process(input.copyOfRange(0, 24_000)) + r.process(input.copyOfRange(24_000, 48_000))
        assertThat(out.size).isWithin(3).of(16_000)
        // Count zero crossings ≈ 2 × 440.
        val crossings = out.toList().zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }
        assertThat(crossings).isWithin(10).of(880)
    }

    @Test
    fun `utterance segmenter finds speech bursts`() {
        val rate = 16_000
        val samples = ShortArray(rate * 4)
        // Speech-like bursts at 0.5–1.5 s and 2.5–3.2 s, silence elsewhere (low noise).
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            val loud = (t in 0.5..1.5) || (t in 2.5..3.2)
            val v = if (loud) 8000 * sin(2 * PI * 220 * t) else ((i * 7919) % 41 - 20).toDouble()
            samples[i] = v.toInt().toShort()
        }
        val u = UtteranceSegmenter.segment(samples, rate)
        assertThat(u).hasSize(2)
        assertThat(u[0].startSample.toDouble() / rate).isWithin(0.2).of(0.5)
        assertThat(u[1].endSample.toDouble() / rate).isWithin(0.2).of(3.2)
    }

    @Test
    fun `target words use Persian and English speaking rates`() {
        assertThat(PromptLibrary.targetWords(60, "fa")).isEqualTo(138)
        assertThat(PromptLibrary.targetWords(60, "en")).isEqualTo(150)
    }

    @Test
    fun `prompt spec picks credits and list mode`() {
        val script = PromptLibrary.build(TextRequest(TextTask.GENERATE_SCRIPT, "موضوع", targetDurationSec = 60))
        assertThat(script.operation).isEqualTo(AiOperation.TEXT_LARGE)
        assertThat(script.system).contains("138")
        assertThat(script.system).contains("[pause]")
        val hooks = PromptLibrary.build(TextRequest(TextTask.HOOKS, "topic", outputLanguage = "en", variants = 4))
        assertThat(hooks.isList).isTrue()
        assertThat(hooks.operation).isEqualTo(AiOperation.TEXT_SMALL)
        assertThat(hooks.system).contains("exactly 4")
        val translate = PromptLibrary.build(TextRequest(TextTask.TRANSLATE, "## Intro\n==hi==", outputLanguage = "fa"))
        assertThat(translate.system).contains("`[pause]`")
    }
}
