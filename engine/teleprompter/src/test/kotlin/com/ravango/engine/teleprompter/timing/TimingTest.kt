package com.ravango.engine.teleprompter.timing

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import org.junit.Test

class TimingTest {

    @Test
    fun `word index counts words starting before an offset`() {
        val parsed = ScriptMarkup.parse("one two  three")
        val index = parsed.words
        assertThat(index.wordsBefore(0)).isEqualTo(0)
        assertThat(index.wordsBefore(1)).isEqualTo(1) // inside "one"
        assertThat(index.wordsBefore(4)).isEqualTo(1) // start of "two"
        assertThat(index.wordsBefore(5)).isEqualTo(2)
        assertThat(index.wordsBefore(parsed.text.length)).isEqualTo(3)
        assertThat(index.wordsIn(4, parsed.text.length)).isEqualTo(2)
    }

    @Test
    fun `duration uses words per minute plus fixed pause time`() {
        assertThat(PrompterTiming.durationMs(120, 0, 120)).isEqualTo(60_000)
        assertThat(PrompterTiming.durationMs(60, 2, 120)).isEqualTo(30_000 + 2 * PrompterTiming.PAUSE_MS)
        assertThat(PrompterTiming.durationMs(0, 0, 120)).isEqualTo(0)
    }

    @Test
    fun `wpm is clamped to the supported range`() {
        assertThat(PrompterTiming.clampWpm(5)).isEqualTo(40)
        assertThat(PrompterTiming.clampWpm(1000)).isEqualTo(400)
    }

    @Test
    fun `remaining time excludes notes and counts remaining pauses`() {
        val parsed = ScriptMarkup.parse("a b [pause] c d [[not counted words here]] e f")
        assertThat(parsed.totalWords).isEqualTo(6)
        val wpm = 60 // one word per second
        assertThat(PrompterTiming.totalMs(parsed, wpm)).isEqualTo(6_000 + PrompterTiming.PAUSE_MS)
        val atC = parsed.text.indexOf('c')
        assertThat(PrompterTiming.remainingMs(parsed, atC, wpm)).isEqualTo(4_000)
        assertThat(PrompterTiming.elapsedAtMs(parsed, atC, wpm)).isEqualTo(2_000 + PrompterTiming.PAUSE_MS)
        assertThat(PrompterTiming.remainingMs(parsed, parsed.text.length, wpm)).isEqualTo(0)
    }

    @Test
    fun `section navigation behaves like a media player`() {
        val sections = listOf(0, 10, 20)
        assertThat(SectionNavigator.currentIndex(sections, 15)).isEqualTo(1)
        assertThat(SectionNavigator.currentIndex(listOf(5), 2)).isEqualTo(-1)
        assertThat(SectionNavigator.nextIndex(sections, 10)).isEqualTo(2)
        assertThat(SectionNavigator.nextIndex(sections, 25)).isNull()
        // Inside section 1 → back to its start.
        assertThat(SectionNavigator.previousIndex(sections, 15)).isEqualTo(1)
        // At the start of section 1 → section 0.
        assertThat(SectionNavigator.previousIndex(sections, 10)).isEqualTo(0)
        // At the first section start → beginning.
        assertThat(SectionNavigator.previousIndex(sections, 0)).isNull()
    }

    @Test
    fun `uniform lines scroll at exactly the words per minute rate`() {
        // 10 lines, 100 px tall, 10 words each. At 120 wpm a line takes 5 s → 20 px/s.
        val profile = uniformProfile(lines = 10, height = 100f, words = 10)
        assertThat(profile.velocityAt(450f, 120)).isWithin(0.01f).of(20f)
        // Doubling speed doubles velocity; font size (line height) scales velocity linearly.
        assertThat(profile.velocityAt(450f, 240)).isWithin(0.01f).of(40f)
        val bigFont = uniformProfile(lines = 10, height = 200f, words = 10)
        assertThat(bigFont.velocityAt(900f, 120)).isWithin(0.01f).of(40f)
    }

    @Test
    fun `short lines do not cause extreme speed jumps`() {
        val words = intArrayOf(10, 10, 1, 10, 10, 0, 10)
        val n = words.size
        val profile = ScrollProfile(
            FloatArray(n) { it * 100f }, FloatArray(n) { (it + 1) * 100f }, words, IntArray(n), IntArray(n) { it * 50 },
        )
        val average = 1f / profile.averageSecondsPerPixel(120)
        for (y in 0..700 step 10) {
            val v = profile.velocityAt(y.toFloat(), 120)
            assertThat(v).isAtMost(average / ScrollProfile.MIN_FACTOR + 0.01f)
            assertThat(v).isAtLeast(average / ScrollProfile.MAX_FACTOR - 0.01f)
        }
    }

    @Test
    fun `line lookup and bounds`() {
        val profile = uniformProfile(lines = 3, height = 50f, words = 4)
        assertThat(profile.lineAt(-10f)).isEqualTo(0)
        assertThat(profile.lineAt(75f)).isEqualTo(1)
        assertThat(profile.lineAt(1000f)).isEqualTo(2)
        assertThat(profile.startY).isEqualTo(25f)
        assertThat(profile.endY).isEqualTo(125f)
        assertThat(profile.fractionIn(1, 75f)).isWithin(1e-4f).of(0.5f)
    }

    private fun uniformProfile(lines: Int, height: Float, words: Int) = ScrollProfile(
        tops = FloatArray(lines) { it * height },
        bottoms = FloatArray(lines) { (it + 1) * height },
        words = IntArray(lines) { words },
        pauses = IntArray(lines),
        lineStarts = IntArray(lines) { it * 40 },
    )
}
