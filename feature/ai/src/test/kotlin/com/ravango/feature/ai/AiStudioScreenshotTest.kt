package com.ravango.feature.ai

import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.Entitlements
import com.ravango.core.model.Plan
import com.ravango.core.testing.PHONE_QUALIFIERS
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AiSetupIssue
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.engine.ai.api.ContentPlatform
import com.ravango.engine.ai.api.Tone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AiStudioScreenshotTest {

    private val ready = AiAvailability(configured = true, provider = AiProviderId.RAVANGO_GATEWAY, consentRequired = false)
    private val proEntitlements = Entitlements(plan = Plan.PRO, aiCreditsPerMonth = 500, aiCreditsRemaining = 342)

    private val hub = AiStudioUiState(
        availability = ready,
        entitlements = proEntitlements,
        history = listOf(
            HistoryItem(id = "h1", tool = AiTool.HOOKS, input = "بهره‌وری", output = "۱. اگر فقط یک عادت را امروز تغییر دهید، این باشد…", variants = emptyList()),
            HistoryItem(id = "h2", tool = AiTool.SCRIPT, input = "Morning routine", output = "Most mornings start the same way: an alarm, a phone, and a scroll.", variants = emptyList()),
        ),
    )

    private val scriptOutput = """
        سلام دوستان! امروز می‌خواهم سه عادت ساده را با شما در میان بگذارم که بهره‌وری من را دو برابر کرد.

        اولین عادت، برنامه‌ریزی شب قبل است. فقط پنج دقیقه قبل از خواب، سه کار مهم فردا را بنویسید.
    """.trimIndent()

    private val toolBase = hub.copy(
        tool = AiTool.SCRIPT,
        input = "سه عادت ساده برای بهره‌وری بیشتر در کارهای روزانه",
        options = ToolOptions(platform = ContentPlatform.INSTAGRAM, tone = Tone.FRIENDLY, durationSec = 60, useDuration = true),
    )

    private val hooks = listOf(
        "اگر فقط یک عادت را امروز تغییر دهید، این باشد.",
        "۹۰٪ آدم‌ها صبحشان را اشتباه شروع می‌کنند؛ شما هم؟",
        "این ترفند پنج‌دقیقه‌ای زندگی کاری من را عوض کرد.",
        "Stop scrolling — this 5-minute habit doubled my output.",
    )

    @Test fun hub() = capture("ai-hub", hub)
    @Test fun hubSmallPhone() = capture("ai-hub-small", hub, SMALL_PHONE)
    @Test fun hubSetupNeeded() = capture(
        "ai-hub-setup",
        AiStudioUiState(
            availability = AiAvailability(false, AiProviderId.RAVANGO_GATEWAY, true, issue = AiSetupIssue.SIGN_IN_REQUIRED),
            entitlements = Entitlements(aiCreditsRemaining = 3),
            eyeContactState = CapabilityState.REQUIRES_SERVICE,
        ),
    )
    @Test fun toolIdle() = capture("ai-tool-idle", toolBase)
    @Test fun toolStreaming() = capture("ai-tool-streaming", toolBase.copy(generation = GenerationState.Streaming, output = scriptOutput.take(120)))
    @Test fun toolDone() = capture("ai-tool-done", toolBase.copy(generation = GenerationState.Done(creditsUsed = 2), output = scriptOutput))
    @Test fun toolVariants() = capture(
        "ai-tool-variants",
        toolBase.copy(tool = AiTool.HOOKS, generation = GenerationState.Done(1), output = hooks.joinToString("\n"), variants = hooks, selectedVariants = setOf(0, 2)),
    )
    @Test fun toolFailed() = capture("ai-tool-failed", toolBase.copy(generation = GenerationState.Failed(ErrorKind.QUOTA, AiErrors.NO_CREDITS)))

    private fun capture(name: String, state: AiStudioUiState, qualifiers: String = PHONE_QUALIFIERS) = captureAllVariants(name, qualifiers) {
        WithDeviceLocale { AiStudioContent(
            state = state, onBack = {}, onOpenTool = {}, onRestore = {}, onOpenSettings = {}, onSignIn = {}, onGetCredits = {}, onOpenProjects = {},
            toolActions = AiToolActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}),
        ) }
    }

    private companion object {
        const val SMALL_PHONE = "w360dp-h740dp"
    }
}
