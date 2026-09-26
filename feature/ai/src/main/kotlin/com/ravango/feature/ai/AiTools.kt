package com.ravango.feature.ai

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.ui.graphics.vector.ImageVector
import com.ravango.engine.ai.api.TextTask

enum class ToolGroup(@StringRes val title: Int) {
    WRITE(R.string.ai_group_write),
    IMPROVE(R.string.ai_group_improve),
    PUBLISH(R.string.ai_group_publish),
    TRANSLATE(R.string.ai_group_translate),
}

/** Which inputs a tool asks for. */
enum class InputKind { TOPIC, TEXT, BULLETS }

/** One tile in the AI Studio hub, backed by a [TextTask]. */
enum class AiTool(
    val task: TextTask,
    val group: ToolGroup,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val icon: ImageVector,
    val input: InputKind,
) {
    SCRIPT(TextTask.GENERATE_SCRIPT, ToolGroup.WRITE, R.string.ai_tool_script, R.string.ai_tool_script_desc, Icons.Rounded.AutoAwesome, InputKind.TOPIC),
    IDEAS(TextTask.IDEAS, ToolGroup.WRITE, R.string.ai_tool_ideas, R.string.ai_tool_ideas_desc, Icons.Rounded.Lightbulb, InputKind.TOPIC),
    BULLETS(TextTask.BULLETS_TO_SCRIPT, ToolGroup.WRITE, R.string.ai_tool_bullets, R.string.ai_tool_bullets_desc, Icons.AutoMirrored.Rounded.FormatListBulleted, InputKind.BULLETS),

    REWRITE(TextTask.REWRITE, ToolGroup.IMPROVE, R.string.ai_tool_rewrite, R.string.ai_tool_rewrite_desc, Icons.Rounded.EditNote, InputKind.TEXT),
    SHORTEN(TextTask.SHORTEN, ToolGroup.IMPROVE, R.string.ai_tool_shorten, R.string.ai_tool_shorten_desc, Icons.AutoMirrored.Rounded.ShortText, InputKind.TEXT),
    LENGTHEN(TextTask.LENGTHEN, ToolGroup.IMPROVE, R.string.ai_tool_lengthen, R.string.ai_tool_lengthen_desc, Icons.Rounded.UnfoldMore, InputKind.TEXT),
    TONE(TextTask.CHANGE_TONE, ToolGroup.IMPROVE, R.string.ai_tool_tone, R.string.ai_tool_tone_desc, Icons.Rounded.Mood, InputKind.TEXT),
    GRAMMAR(TextTask.FIX_GRAMMAR, ToolGroup.IMPROVE, R.string.ai_tool_grammar, R.string.ai_tool_grammar_desc, Icons.Rounded.Spellcheck, InputKind.TEXT),
    CONVERSATIONAL(TextTask.FORMAL_TO_CONVERSATIONAL, ToolGroup.IMPROVE, R.string.ai_tool_conversational, R.string.ai_tool_conversational_desc, Icons.Rounded.RecordVoiceOver, InputKind.TEXT),
    SUMMARIZE(TextTask.SUMMARIZE, ToolGroup.IMPROVE, R.string.ai_tool_summarize, R.string.ai_tool_summarize_desc, Icons.Rounded.Summarize, InputKind.TEXT),

    HOOKS(TextTask.HOOKS, ToolGroup.PUBLISH, R.string.ai_tool_hooks, R.string.ai_tool_hooks_desc, Icons.Rounded.Bolt, InputKind.TOPIC),
    CTA(TextTask.CTA, ToolGroup.PUBLISH, R.string.ai_tool_cta, R.string.ai_tool_cta_desc, Icons.Rounded.Campaign, InputKind.TOPIC),
    TITLES(TextTask.TITLES, ToolGroup.PUBLISH, R.string.ai_tool_titles, R.string.ai_tool_titles_desc, Icons.Rounded.Title, InputKind.TOPIC),
    CAPTION(TextTask.CAPTION, ToolGroup.PUBLISH, R.string.ai_tool_caption, R.string.ai_tool_caption_desc, Icons.Rounded.Tag, InputKind.TOPIC),
    DESCRIPTION(TextTask.DESCRIPTION, ToolGroup.PUBLISH, R.string.ai_tool_description, R.string.ai_tool_description_desc, Icons.Rounded.Description, InputKind.TOPIC),

    TRANSLATE(TextTask.TRANSLATE, ToolGroup.TRANSLATE, R.string.ai_tool_translate, R.string.ai_tool_translate_desc, Icons.Rounded.Translate, InputKind.TEXT),
    ;

    val isList: Boolean get() = task in LIST_TASKS
    val usesTone: Boolean get() = task in TONE_TASKS
    val toneRequired: Boolean get() = task == TextTask.CHANGE_TONE
    val usesPlatform: Boolean get() = task !in setOf(TextTask.FIX_GRAMMAR, TextTask.TRANSLATE, TextTask.SUMMARIZE)
    val usesAudience: Boolean get() = group == ToolGroup.WRITE || group == ToolGroup.PUBLISH
    /** Duration is required for scripts, optional ("target length") for resizing tools. */
    val durationMode: DurationMode get() = when (task) {
        TextTask.GENERATE_SCRIPT, TextTask.BULLETS_TO_SCRIPT -> DurationMode.REQUIRED
        TextTask.SHORTEN, TextTask.LENGTHEN, TextTask.SUMMARIZE -> DurationMode.OPTIONAL
        else -> DurationMode.NONE
    }
    val maxVariants: Int get() = if (task == TextTask.CAPTION) 5 else 10

    enum class DurationMode { NONE, OPTIONAL, REQUIRED }

    companion object {
        private val LIST_TASKS = setOf(TextTask.HOOKS, TextTask.CTA, TextTask.TITLES, TextTask.IDEAS, TextTask.CAPTION)
        private val TONE_TASKS = setOf(
            TextTask.GENERATE_SCRIPT, TextTask.BULLETS_TO_SCRIPT, TextTask.REWRITE, TextTask.CHANGE_TONE, TextTask.LENGTHEN,
            TextTask.HOOKS, TextTask.CTA, TextTask.CAPTION, TextTask.IDEAS, TextTask.TITLES, TextTask.DESCRIPTION,
        )

        /** Resolves the `tool` route argument: an [AiTool] name or a [TextTask] name, case-insensitive. */
        fun fromArg(arg: String?): AiTool? {
            val key = arg?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name == key } ?: entries.firstOrNull { it.task.name == key }
        }
    }
}
