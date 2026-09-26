package com.ravango.feature.scripts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.testing.PHONE_QUALIFIERS
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.ai.api.TextTask
import com.ravango.engine.ai.api.Tone
import com.ravango.feature.scripts.editor.AiAssistContent
import com.ravango.feature.scripts.editor.AiBlock
import com.ravango.feature.scripts.editor.AiUiState
import com.ravango.feature.scripts.editor.EditorStats
import com.ravango.feature.scripts.editor.EditorUiState
import com.ravango.feature.scripts.editor.SaveStatus
import com.ravango.feature.scripts.editor.ScriptEditorContent
import com.ravango.feature.scripts.library.CreateContent
import com.ravango.feature.scripts.library.MoveToFolderContent
import com.ravango.feature.scripts.library.ScriptActionsContent
import com.ravango.feature.scripts.library.ScriptsContent
import com.ravango.feature.scripts.library.ScriptsUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScriptsScreenshotTest {

    // ------------------------------------------------------------------ library

    @Test fun libraryLoaded() = library("scripts-library", ScriptsSamples.loaded)
    @Test fun librarySmallPhone() = library("scripts-library-small", ScriptsSamples.loaded, SMALL_PHONE)
    @Test fun librarySearch() = library("scripts-library-search", ScriptsSamples.searching)
    @Test fun libraryEmpty() = library("scripts-library-empty", ScriptsSamples.empty)
    @Test fun libraryEmptySearch() = library("scripts-library-empty-search", ScriptsSamples.emptySearch)
    @Test fun libraryLoading() = library("scripts-library-loading", ScriptsSamples.loading)

    @Test fun sheetActions() = captureAllVariants("scripts-sheet-actions") {
        SheetFrame { ScriptActionsContent(ScriptsSamples.items[0].script, {}, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun sheetMove() = captureAllVariants("scripts-sheet-move") {
        SheetFrame { MoveToFolderContent(ScriptsSamples.folders, current = "f1", onSelect = {}, onNewFolder = {}) }
    }

    @Test fun sheetCreate() = captureAllVariants("scripts-sheet-create") { SheetFrame { CreateContent({}, {}, {}) } }

    // ------------------------------------------------------------------ editor

    private val editorState = EditorUiState(
        loading = false,
        scriptId = "s1",
        title = "سه عادت ساده برای بهره‌وری بیشتر",
        body = TextFieldValue(ScriptsSamples.persianBody),
        canUndo = true,
        saveStatus = SaveStatus.SAVED,
    )
    private val stats = EditorStats(words = 186, pauses = 1, durationMs = 82_000, wordsPerMinute = 140)

    @Test fun editor() = editor("scripts-editor", editorState)
    @Test fun editorPreview() = editor("scripts-editor-preview", editorState.copy(preview = true))
    @Test fun editorEnglish() = editor("scripts-editor-english", editorState.copy(title = "How I film in one take", body = TextFieldValue(ScriptsSamples.englishBody)))
    @Test fun editorNew() = editor("scripts-editor-new", EditorUiState(loading = false), EditorStats())
    @Test fun editorSmall() = editor("scripts-editor-small", editorState, qualifiers = SMALL_PHONE)

    @Test fun aiSheetTools() = ai("scripts-ai-tools", AiUiState(tone = Tone.FRIENDLY))
    @Test fun aiSheetStreaming() = ai(
        "scripts-ai-streaming",
        AiUiState(task = TextTask.SHORTEN, running = true, output = "سلام دوستان! امروز سه عادت ساده را می‌گویم که بهره‌وری‌ام را دو برابر کرد.", onSelection = true),
    )
    @Test fun aiSheetDone() = ai(
        "scripts-ai-done",
        AiUiState(task = TextTask.REWRITE, completed = true, output = "سلام دوستان! امروز سه عادت ساده را با شما در میان می‌گذارم که بهره‌وری من را دو برابر کرد. اولین عادت، برنامه‌ریزی شب قبل است."),
    )
    @Test fun aiSheetHooks() = ai(
        "scripts-ai-hooks",
        AiUiState(
            task = TextTask.HOOKS, completed = true, output = "…",
            items = listOf("اگر فقط یک عادت را امروز تغییر دهید، این باشد.", "۹۰٪ آدم‌ها صبحشان را اشتباه شروع می‌کنند.", "Stop scrolling — this 5-minute habit doubled my output."),
        ),
    )
    @Test fun aiSheetBlocked() = ai("scripts-ai-blocked", AiUiState(block = AiBlock.NOT_CONFIGURED))

    private fun library(name: String, state: ScriptsUiState, qualifiers: String = PHONE_QUALIFIERS) =
        captureAllVariants(name, qualifiers) {
            WithDeviceLocale { ScriptsContent(
                state = state, onBack = {}, onSort = {}, onQueryChange = {}, onFilter = {}, onNewFolder = {},
                onFolderLongPress = {}, onCreate = {}, onOpen = {}, onLongPress = {}, onFavorite = {}, onDelete = {},
            ) }
        }

    private fun editor(name: String, state: EditorUiState, s: EditorStats = stats, qualifiers: String = PHONE_QUALIFIERS) =
        captureAllVariants(name, qualifiers) {
            WithDeviceLocale { ScriptEditorContent(
                state = state, stats = s, onBack = {}, onUndo = {}, onRedo = {}, onPreview = {}, onNavigate = {},
                onTitleChange = {}, onBodyChange = {}, onDirection = {}, onEdit = {}, onAi = {},
            ) }
        }

    private fun ai(name: String, state: AiUiState) = captureAllVariants(name) {
        SheetFrame { AiAssistContent(state, hasSelection = false, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
    }

    /** Mimics the design-system modal bottom sheet (scrim, rounded top, drag handle) so sheet bodies can be captured. */
    @Composable
    private fun SheetFrame(content: @Composable ColumnScope.() -> Unit) {
        val colors = RgTheme.colors
        WithDeviceLocale {}
        Box(Modifier.fillMaxSize().background(colors.background).background(colors.scrim), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl))
                    .background(colors.backgroundElevated)
                    .padding(bottom = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 5.dp).clip(CircleShape).background(colors.outlineStrong))
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }

    private companion object {
        const val SMALL_PHONE = "w360dp-h740dp"
    }
}
