package com.ravango.feature.projects

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import com.ravango.core.testing.captureAllVariants
import com.ravango.feature.projects.list.ProjectItem
import com.ravango.feature.projects.list.ProjectSort
import com.ravango.feature.projects.list.ProjectsContent
import com.ravango.feature.projects.list.ProjectsTab
import com.ravango.feature.projects.list.ProjectsUiState
import com.ravango.feature.projects.templates.TemplatesContent
import com.ravango.feature.projects.templates.TemplatesUiState
import org.junit.Test
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NOW = System.currentTimeMillis()
private const val HOUR = 3_600_000L

@Composable
private fun t(fa: String, en: String) = if (LocalLayoutDirection.current == LayoutDirection.Rtl) fa else en

@Composable
private fun items(): List<ProjectItem> = listOf(
    Triple(t("معرفی محصول جدید", "New product launch"), ProjectStatus.EDITING, AspectRatioSpec.Portrait9x16),
    Triple(t("ولاگ سفر به یزد و کاشان در پاییز", "Autumn trip to Yazd and Kashan"), ProjectStatus.EXPORTED, AspectRatioSpec.Landscape16x9),
    Triple(t("پادکست هفتگی", "Weekly podcast"), ProjectStatus.RECORDED, AspectRatioSpec.Square1x1),
    Triple(t("آموزش آشپزی", "Cooking class"), ProjectStatus.EDITING, AspectRatioSpec.Portrait4x5),
    Triple(t("تیزر کافه", "Café teaser"), ProjectStatus.EXPORTED, AspectRatioSpec.Portrait9x16),
    Triple(t("مصاحبه", "Interview"), ProjectStatus.RECORDED, AspectRatioSpec.Landscape16x9),
).mapIndexed { i, (title, status, aspect) ->
    ProjectItem(
        project = Project(
            id = "p$i", title = title, status = status, aspectRatio = aspect,
            durationUs = listOf(47L, 312L, 1260L, 95L, 28L, 640L)[i] * 1_000_000, createdAt = NOW - 100 * HOUR, updatedAt = NOW - (i * 7 + 1) * HOUR,
        ),
        thumbnail = null, hasDraft = status != ProjectStatus.EXPORTED, hasExport = status == ProjectStatus.EXPORTED, audioOnly = i == 2,
    )
}

@Composable
private fun Projects(state: ProjectsUiState) = ProjectsContent(
    state = state,
    snackbar = remember { SnackbarHostState() },
    onBack = {}, onTab = {}, onQuery = {}, onSort = {}, onOpen = {}, onToggleSelect = {}, onSelectAll = {}, onClearSelection = {},
    onDelete = {}, onRename = { _, _ -> }, onDuplicate = { _, _ -> }, onShare = {}, onImport = {},
)

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
class ProjectsScreenshotTest {

    @Test
    fun loaded() = captureAllVariants("projects-loaded") { SyncLocale();
        val list = items()
        Projects(ProjectsUiState(loading = false, items = list, totalProjects = list.size, storageBytes = 1_840_000_000L))
    }

    @Test
    fun selection() = captureAllVariants("projects-selection") { SyncLocale();
        val list = items()
        Projects(ProjectsUiState(loading = false, items = list, totalProjects = list.size, storageBytes = 1_840_000_000L, selection = setOf("p0", "p3")))
    }

    @Test
    fun empty() = captureAllVariants("projects-empty") { SyncLocale(); Projects(ProjectsUiState(loading = false)) }

    @Test
    fun emptyDrafts() = captureAllVariants("projects-empty-drafts") { SyncLocale();
        Projects(ProjectsUiState(loading = false, tab = ProjectsTab.DRAFTS, totalProjects = 4, sort = ProjectSort.NAME))
    }

    @Test
    fun loading() = captureAllVariants("projects-loading") { SyncLocale(); Projects(ProjectsUiState(loading = true)) }

    @Test
    fun smallPhone() = captureAllVariants("projects-small", qualifiers = "w360dp-h740dp") { SyncLocale();
        val list = items()
        Projects(ProjectsUiState(loading = false, items = list, totalProjects = list.size, storageBytes = 420_000_000L))
    }

    @Test
    fun templates() = captureAllVariants("templates") { SyncLocale();
        TemplatesContent(TemplatesUiState(templates = BuiltInPresets.templates), remember { SnackbarHostState() }, onBack = {}, onSelect = {})
    }

    @Test
    fun templatesFull() = captureAllVariants("templates-full", qualifiers = "w412dp-h1700dp") { SyncLocale();
        TemplatesContent(TemplatesUiState(templates = BuiltInPresets.templates), remember { SnackbarHostState() }, onBack = {}, onSelect = {})
    }
}
