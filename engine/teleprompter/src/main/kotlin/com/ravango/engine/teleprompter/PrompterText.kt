package com.ravango.engine.teleprompter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ravango.core.designsystem.theme.toFontFamily
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.PrompterTextAlign
import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.teleprompter.markup.MarkupKind
import com.ravango.engine.teleprompter.markup.ParsedScript
import com.ravango.engine.teleprompter.markup.ScriptMarkup

/** Inline-content id used for `[pause]` chips in prompter annotated strings. */
const val PROMPTER_PAUSE_INLINE_ID: String = "rg_prompter_pause"

/**
 * Builds the styled text for a parsed script: highlights get a translucent [highlightColor] background, emphasis is
 * bold, section headings are compact accent labels, director notes are dimmed italics and pauses become inline
 * chips (pair with [prompterInlineContent]). Offsets are identical to [ParsedScript.text].
 */
fun buildPrompterAnnotatedString(
    parsed: ParsedScript,
    textColor: Color,
    highlightColor: Color,
    baseWeight: Int = 500,
): AnnotatedString = buildAnnotatedString {
    val text = parsed.text
    var last = 0
    for (span in parsed.spans) {
        if (span.kind != MarkupKind.PAUSE) continue
        if (span.start > last) append(text, last, span.start)
        appendInlineContent(PROMPTER_PAUSE_INLINE_ID, ScriptMarkup.PAUSE_CHAR.toString())
        last = span.end
    }
    if (last < text.length) append(text, last, text.length)

    val emphasisWeight = FontWeight((baseWeight + 300).coerceIn(700, 900))
    for (span in parsed.spans) {
        val style = when (span.kind) {
            MarkupKind.HIGHLIGHT -> SpanStyle(background = highlightColor.copy(alpha = 0.34f))
            MarkupKind.EMPHASIS -> SpanStyle(fontWeight = emphasisWeight)
            MarkupKind.SECTION -> SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold, fontSize = 0.7.em, letterSpacing = 0.02.em)
            MarkupKind.NOTE -> SpanStyle(color = textColor.copy(alpha = 0.48f), fontStyle = FontStyle.Italic, fontSize = 0.72.em)
            MarkupKind.PAUSE -> null
        } ?: continue
        if (span.end > span.start) addStyle(style, span.start, span.end)
    }
}

/** Inline content map rendering `[pause]` cues as rounded chips with a pause glyph. */
fun prompterInlineContent(color: Color): Map<String, InlineTextContent> = mapOf(
    PROMPTER_PAUSE_INLINE_ID to InlineTextContent(
        Placeholder(width = 1.9.em, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) { PauseChip(color) },
)

@Composable
private fun PauseChip(color: Color) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.6f), shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barH = size.height * 0.46f
            val barW = (size.height * 0.13f).coerceAtLeast(1.5f)
            val gap = barW * 1.1f
            val top = (size.height - barH) / 2f
            val cx = size.width / 2f
            val radius = CornerRadius(barW / 2f, barW / 2f)
            drawRoundRect(color, Offset(cx - gap / 2f - barW, top), Size(barW, barH), radius)
            drawRoundRect(color, Offset(cx + gap / 2f, top), Size(barW, barH), radius)
        }
    }
}

/** The prompter's text style for [settings] in the resolved content direction. */
fun prompterTextStyle(settings: TeleprompterSettings, contentDirection: LayoutDirection): TextStyle {
    val size = settings.fontSizeSp.coerceIn(TeleprompterSettings.MIN_FONT_SP, TeleprompterSettings.MAX_FONT_SP)
    return TextStyle(
        color = Color(settings.textColor),
        fontFamily = settings.font.toFontFamily(),
        fontWeight = FontWeight(settings.fontWeight.coerceIn(100, 900)),
        fontSize = size.sp,
        lineHeight = (size * settings.lineSpacing.coerceIn(0.9f, 3f)).sp,
        letterSpacing = settings.letterSpacingEm.coerceIn(-0.1f, 0.5f).em,
        textAlign = when (settings.textAlign) {
            PrompterTextAlign.START -> TextAlign.Start
            PrompterTextAlign.CENTER -> TextAlign.Center
            PrompterTextAlign.END -> TextAlign.End
            PrompterTextAlign.JUSTIFY -> TextAlign.Justify
        },
        textDirection = when (settings.direction) {
            ContentDirection.RTL -> TextDirection.Rtl
            ContentDirection.LTR -> TextDirection.Ltr
            // Each paragraph follows its own first strong character; neutral paragraphs follow the script.
            ContentDirection.AUTO -> if (contentDirection == LayoutDirection.Rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr
        },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

/** Remembers the annotated text for [parsed] (e.g. for the script editor's preview). */
@Composable
fun rememberPrompterAnnotatedString(parsed: ParsedScript, textColor: Color, highlightColor: Color, baseWeight: Int = 500): AnnotatedString =
    remember(parsed, textColor, highlightColor, baseWeight) { buildPrompterAnnotatedString(parsed, textColor, highlightColor, baseWeight) }
