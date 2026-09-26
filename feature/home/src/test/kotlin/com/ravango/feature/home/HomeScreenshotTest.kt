package com.ravango.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.Plan
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import com.ravango.core.model.Script
import com.ravango.core.model.service.SyncPhase
import com.ravango.core.testing.captureAllVariants
import org.junit.Test
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/** 10:30 local time → "Good morning". */
private val NOW: Long = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 26, 10, 30, 0) }.timeInMillis
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

@Composable
private fun t(fa: String, en: String) = if (LocalLayoutDirection.current == LayoutDirection.Rtl) fa else en

@Composable
private fun loadedState(plan: Plan = Plan.FREE, signedIn: Boolean = false): HomeUiState {
    val projects = listOf(
        Project(id = "p1", title = t("معرفی محصول جدید", "New product launch"), status = ProjectStatus.EDITING, durationUs = 47_000_000, createdAt = NOW - 30 * HOUR, updatedAt = NOW - 25 * MINUTE),
        Project(id = "p2", title = t("ولاگ سفر به یزد", "Yazd travel vlog"), status = ProjectStatus.EXPORTED, aspectRatio = AspectRatioSpec.Landscape16x9, durationUs = 312_000_000, createdAt = NOW - 80 * HOUR, updatedAt = NOW - 26 * HOUR),
        Project(id = "p3", title = t("پادکست هفتگی — قسمت ۱۲", "Weekly podcast — episode 12"), status = ProjectStatus.RECORDED, aspectRatio = AspectRatioSpec.Square1x1, durationUs = 1_260_000_000, createdAt = NOW - 200 * HOUR, updatedAt = NOW - 72 * HOUR),
    ).map { HomeProject(it, thumbnail = null, audioOnly = it.id == "p3") }
    val scripts = listOf(
        Script(id = "s1", title = t("سه نکته برای تولید محتوای بهتر", "Three tips for better content"), body = "کلمه ".repeat(210), createdAt = NOW - 5 * HOUR, updatedAt = NOW - 2 * HOUR),
        Script(id = "s2", title = t("تبلیغ کوتاه کافه", "Café promo"), body = "word ".repeat(45), createdAt = NOW - 50 * HOUR, updatedAt = NOW - 40 * HOUR),
    )
    return HomeUiState(
        loading = false,
        signedIn = signedIn,
        userName = if (signedIn) t("سارا", "Sara") else null,
        plan = plan,
        syncPhase = if (signedIn) SyncPhase.IDLE else SyncPhase.DISABLED,
        recentProjects = projects,
        recentScripts = scripts,
        scriptCount = 12,
        templates = BuiltInPresets.templates,
        continueItem = ContinueItem(projects.first(), NOW - 25 * MINUTE),
    )
}

@Composable
private fun Home(state: HomeUiState) = HomeContent(state, NOW, onNavigate = {}, onTeleprompter = {}, onImport = {}, onTemplate = {})

/**
 * Robolectric changes the resource locale per variant but not [Locale.getDefault], which the app's number/byte
 * formatters use (on a device the per-app language updates both). Keep them in sync so digits render as on device.
 */
@Composable
private fun SyncLocale() {
    Locale.setDefault(LocalConfiguration.current.locales[0])
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenshotTest {

    @Test
    fun loadedFreeGuest() = captureAllVariants("home-loaded") { SyncLocale(); Home(loadedState()) }

    /** Tall canvas so every section (projects, scripts, templates) is reviewed, not only the first viewport. */
    @Test
    fun loadedFull() = captureAllVariants("home-full", qualifiers = "w412dp-h1900dp") { SyncLocale(); Home(loadedState(Plan.PRO, signedIn = true)) }

    @Test
    fun newUser() = captureAllVariants("home-new-user") { SyncLocale();
        Home(HomeUiState(loading = false, templates = BuiltInPresets.templates))
    }

    @Test
    fun loading() = captureAllVariants("home-loading") { SyncLocale(); Home(HomeUiState(loading = true)) }

    @Test
    fun smallPhone() = captureAllVariants("home-small", qualifiers = "w360dp-h740dp") { SyncLocale(); Home(loadedState(Plan.LIFETIME, signedIn = true)) }
}
