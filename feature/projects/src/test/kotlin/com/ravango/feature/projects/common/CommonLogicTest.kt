package com.ravango.feature.projects.common

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.AspectRatioSpec
import org.junit.Test

class AspectInferenceTest {

    @Test fun `vertical phone video maps to 9x16`() {
        assertThat(inferAspectRatio(1080, 1920)).isEqualTo(AspectRatioSpec.Portrait9x16)
        assertThat(inferAspectRatio(720, 1280)).isEqualTo(AspectRatioSpec.Portrait9x16)
    }

    @Test fun `landscape video maps to 16x9`() {
        assertThat(inferAspectRatio(3840, 2160)).isEqualTo(AspectRatioSpec.Landscape16x9)
    }

    @Test fun `square and near square snap to 1x1`() {
        assertThat(inferAspectRatio(1080, 1080)).isEqualTo(AspectRatioSpec.Square1x1)
        assertThat(inferAspectRatio(1000, 1030)).isEqualTo(AspectRatioSpec.Square1x1)
    }

    @Test fun `instagram portrait maps to 4x5 and photos to 3x4`() {
        assertThat(inferAspectRatio(1080, 1350)).isEqualTo(AspectRatioSpec.Portrait4x5)
        assertThat(inferAspectRatio(3024, 4032)).isEqualTo(AspectRatioSpec.Portrait3x4)
    }

    @Test fun `ultra wide maps to cinema`() {
        assertThat(inferAspectRatio(2560, 1080)).isEqualTo(AspectRatioSpec.Cinema21x9)
    }

    @Test fun `unknown size falls back to vertical`() {
        assertThat(inferAspectRatio(0, 0)).isEqualTo(AspectRatioSpec.Portrait9x16)
        assertThat(inferAspectRatio(-1, 100)).isEqualTo(AspectRatioSpec.Portrait9x16)
    }
}

class RelativeTimeTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test fun buckets() {
        assertThat(relativeTime(now - 10_000, now)).isEqualTo(RelativeTime.JustNow)
        assertThat(relativeTime(now + 30_000, now)).isEqualTo(RelativeTime.JustNow)
        assertThat(relativeTime(now - 5 * minute, now)).isEqualTo(RelativeTime.Minutes(5))
        assertThat(relativeTime(now - 3 * hour, now)).isEqualTo(RelativeTime.Hours(3))
        assertThat(relativeTime(now - 30 * hour, now)).isEqualTo(RelativeTime.Yesterday)
        assertThat(relativeTime(now - 4 * day, now)).isEqualTo(RelativeTime.Days(4))
        assertThat(relativeTime(now - 10 * day, now)).isEqualTo(RelativeTime.Date(now - 10 * day))
    }
}

class TemplateOutlineTest {
    private val outline = "## Hook\n\n## Main point 2\n\nSome body\n## Custom part\n"

    @Test fun `extracts headings in order`() {
        assertThat(outlineHeadings(outline)).containsExactly("Hook", "Main point 2", "Custom part").inOrder()
    }

    @Test fun `localizes known headings and keeps body and unknown headings`() {
        val result = localizeOutline(outline) { h -> if (h == "Hook") "قلاب" else null }
        assertThat(result).isEqualTo("## قلاب\n\n## Main point 2\n\nSome body\n## Custom part\n")
    }

    @Test fun `parses fixed and numbered headings`() {
        assertThat(parseOutlineHeading("Call to action")).isEqualTo(OutlineHeading.Fixed("cta"))
        assertThat(parseOutlineHeading("Hook (3s)")).isEqualTo(OutlineHeading.Fixed("hook_3s"))
        assertThat(parseOutlineHeading("What you'll learn")).isEqualTo(OutlineHeading.Fixed("learn"))
        assertThat(parseOutlineHeading("Main point 3")).isEqualTo(OutlineHeading.MainPoint(3))
        assertThat(parseOutlineHeading("step 12")).isEqualTo(OutlineHeading.Step(12))
        assertThat(parseOutlineHeading("Something else")).isNull()
    }

    @Test fun `every built-in heading is recognised`() {
        com.ravango.core.data.seed.BuiltInPresets.templates.flatMap { outlineHeadings(it.scriptOutline) }.forEach { heading ->
            assertThat(parseOutlineHeading(heading)).isNotNull()
        }
    }
}
