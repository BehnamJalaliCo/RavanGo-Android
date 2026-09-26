package com.ravango.feature.home

import com.google.common.truth.Truth.assertThat
import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.Draft
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.Project
import com.ravango.core.model.UserAccount
import com.ravango.feature.home.common.inferAspectRatio
import org.junit.Test

class HomeLogicTest {

    @Test fun `day parts cover the whole clock`() {
        assertThat(dayPartFor(5)).isEqualTo(DayPart.MORNING)
        assertThat(dayPartFor(11)).isEqualTo(DayPart.MORNING)
        assertThat(dayPartFor(12)).isEqualTo(DayPart.AFTERNOON)
        assertThat(dayPartFor(16)).isEqualTo(DayPart.AFTERNOON)
        assertThat(dayPartFor(17)).isEqualTo(DayPart.EVENING)
        assertThat(dayPartFor(20)).isEqualTo(DayPart.EVENING)
        assertThat(dayPartFor(21)).isEqualTo(DayPart.NIGHT)
        assertThat(dayPartFor(0)).isEqualTo(DayPart.NIGHT)
        assertThat(dayPartFor(4)).isEqualTo(DayPart.NIGHT)
        assertThat(dayPartFor(24)).isEqualTo(DayPart.NIGHT)
    }

    @Test fun `greeting name prefers first name then email`() {
        val base = UserAccount(id = "1", provider = AuthProviderType.EMAIL_OTP)
        assertThat(greetingNameFor(null)).isNull()
        assertThat(greetingNameFor(base.copy(displayName = "  سارا  احمدی "))).isEqualTo("سارا")
        assertThat(greetingNameFor(base.copy(displayName = " ", email = "reza@example.com"))).isEqualTo("reza")
        assertThat(greetingNameFor(base)).isNull()
    }

    @Test fun `read time rounds up and guards tiny speeds`() {
        assertThat(readTimeSeconds(0, 140)).isEqualTo(0)
        assertThat(readTimeSeconds(1, 140)).isEqualTo(1)
        assertThat(readTimeSeconds(140, 140)).isEqualTo(60)
        assertThat(readTimeSeconds(141, 140)).isEqualTo(61)
        assertThat(readTimeSeconds(40, 0)).isEqualTo(60)
    }

    @Test fun `focus reorders templates without losing any`() {
        val templates = BuiltInPresets.templates
        val ordered = orderTemplatesByFocus(templates, parseFocus("podcast, business"))
        assertThat(ordered.map { it.id }.take(3)).containsExactly("tpl-podcast", "tpl-linkedin", "tpl-product").inOrder()
        assertThat(ordered).containsExactlyElementsIn(templates)
        assertThat(orderTemplatesByFocus(templates, parseFocus(null))).isEqualTo(templates)
        assertThat(orderTemplatesByFocus(templates, parseFocus("unknown"))).isEqualTo(templates)
    }

    @Test fun `continue picks the latest recent draft of a live project`() {
        val now = 10_000_000_000L
        val p1 = Project(id = "p1", title = "A", createdAt = 0, updatedAt = 0)
        val p2 = Project(id = "p2", title = "B", createdAt = 0, updatedAt = 0)
        val gone = Project(id = "p3", title = "C", createdAt = 0, updatedAt = 0, deletedAt = 5)
        val doc = EditorDocument()
        val drafts = listOf(
            Draft("p1", doc, 1, now - 60_000),
            Draft("p2", doc, 1, now - 10_000),
            Draft("p3", doc, 1, now - 1_000),
            Draft("missing", doc, 1, now),
        )
        val projects = listOf(p1, p2, gone).associateBy { it.id }
        assertThat(continueCandidate(drafts, projects, now)?.first?.id).isEqualTo("p2")
        val stale = listOf(Draft("p1", doc, 1, now - 5L * 24 * 60 * 60 * 1000))
        assertThat(continueCandidate(stale, projects, now)).isNull()
    }

    @Test fun `aspect inference for imported media`() {
        assertThat(inferAspectRatio(1080, 1920)).isEqualTo(AspectRatioSpec.Portrait9x16)
        assertThat(inferAspectRatio(1920, 1080)).isEqualTo(AspectRatioSpec.Landscape16x9)
    }
}
