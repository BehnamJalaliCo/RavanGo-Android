package com.ravango.feature.home.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.ProjectTemplate
import com.ravango.feature.home.R
import java.util.Locale

@StringRes
fun templateNameRes(nameKey: String): Int? = when (nameKey) {
    "template_reel" -> R.string.home_template_reel
    "template_youtube" -> R.string.home_template_youtube
    "template_short" -> R.string.home_template_short
    "template_linkedin" -> R.string.home_template_linkedin
    "template_product" -> R.string.home_template_product
    "template_tutorial" -> R.string.home_template_tutorial
    "template_podcast" -> R.string.home_template_podcast
    else -> null
}

@StringRes
fun templateDescriptionRes(descriptionKey: String): Int? = when (descriptionKey) {
    "template_reel_desc" -> R.string.home_template_reel_desc
    "template_youtube_desc" -> R.string.home_template_youtube_desc
    "template_short_desc" -> R.string.home_template_short_desc
    "template_linkedin_desc" -> R.string.home_template_linkedin_desc
    "template_product_desc" -> R.string.home_template_product_desc
    "template_tutorial_desc" -> R.string.home_template_tutorial_desc
    "template_podcast_desc" -> R.string.home_template_podcast_desc
    else -> null
}

/** Remote/unknown keys ("template_new_thing") degrade to a readable title instead of a raw key. */
private fun prettifyKey(key: String): String =
    key.removePrefix("template_").removeSuffix("_desc").replace('_', ' ').replaceFirstChar { it.titlecase() }

@Composable
@ReadOnlyComposable
fun templateName(template: ProjectTemplate): String =
    templateNameRes(template.nameKey)?.let { stringResource(it) } ?: prettifyKey(template.nameKey)

@Composable
@ReadOnlyComposable
fun templateDescription(template: ProjectTemplate): String =
    templateDescriptionRes(template.descriptionKey)?.let { stringResource(it) } ?: ""

/** "45 s" / "8 min" with localized digits. */
@Composable
fun formatSuggestedDuration(seconds: Int): String {
    val locale = currentLocale()
    return if (seconds < 60) {
        pluralStringResource(R.plurals.home_duration_seconds, seconds, seconds.toString().localizeDigits(locale))
    } else {
        val minutes = (seconds + 30) / 60
        pluralStringResource(R.plurals.home_duration_minutes, minutes, minutes.toString().localizeDigits(locale))
    }
}

/** Aspect label with localized digits, e.g. "۹:۱۶". Always LTR so the ratio reads correctly. */
fun aspectLabel(aspect: AspectRatioSpec, locale: Locale): String = "⁦${aspect.label.localizeDigits(locale)}⁩"

/** Translates built-in outline headings into the UI language using the screen's (per-app locale) context. */
fun translateHeading(context: Context, heading: String, locale: Locale): String? {
    val parsed = parseOutlineHeading(heading) ?: return null
    return when (parsed) {
        is OutlineHeading.MainPoint -> context.getString(R.string.home_outline_main_point, parsed.number.toString().localizeDigits(locale))
        is OutlineHeading.Step -> context.getString(R.string.home_outline_step, parsed.number.toString().localizeDigits(locale))
        is OutlineHeading.Fixed -> when (parsed.id) {
            "hook" -> R.string.home_outline_hook
            "hook_3s" -> R.string.home_outline_hook_3s
            "value" -> R.string.home_outline_value
            "cta" -> R.string.home_outline_cta
            "intro" -> R.string.home_outline_intro
            "outro" -> R.string.home_outline_outro
            "payoff" -> R.string.home_outline_payoff
            "loop_back" -> R.string.home_outline_loop_back
            "insight" -> R.string.home_outline_insight
            "story" -> R.string.home_outline_story
            "takeaway" -> R.string.home_outline_takeaway
            "problem" -> R.string.home_outline_problem
            "product" -> R.string.home_outline_product
            "benefits" -> R.string.home_outline_benefits
            "offer" -> R.string.home_outline_offer
            "learn" -> R.string.home_outline_learn
            "recap" -> R.string.home_outline_recap
            "opening" -> R.string.home_outline_opening
            "topic" -> R.string.home_outline_topic
            "guest_questions" -> R.string.home_outline_guest_questions
            "closing" -> R.string.home_outline_closing
            else -> null
        }?.let(context::getString)
    }
}

/** Remembers a translator bound to the current configuration (re-created when the language changes). */
@Composable
fun rememberHeadingTranslator(): (String) -> String? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = currentLocale()
    return remember(context, configuration, locale) { { heading: String -> translateHeading(context, heading, locale) } }
}

/**
 * A miniature canvas preview: a rounded frame at the template's aspect ratio with a "speaker" silhouette,
 * script lines and (optionally) a caption bar — conveys the format at a glance.
 */
@Composable
fun AspectFramePreview(
    aspect: AspectRatioSpec,
    accent: Color,
    autoCaptions: Boolean,
    modifier: Modifier = Modifier,
    frameColor: Color = Color.White,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val maxW = size.width * 0.86f
        val maxH = size.height * 0.86f
        val ratio = aspect.ratio
        val (w, h) = if (maxW / maxH > ratio) (maxH * ratio) to maxH else maxW to (maxW / ratio)
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val corner = CornerRadius(minOf(w, h) * 0.09f)
        drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(left + 2.dp.toPx(), top + 4.dp.toPx()), Size(w, h), corner)
        drawRoundRect(frameColor.copy(alpha = 0.92f), Offset(left, top), Size(w, h), corner)
        drawRoundRect(
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.08f)), startY = top, endY = top + h),
            Offset(left, top), Size(w, h), corner,
        )
        // Speaker silhouette.
        val cx = left + w / 2
        val headR = minOf(w, h) * 0.13f
        val headY = top + h * 0.40f
        drawCircle(accent.copy(alpha = 0.55f), headR, Offset(cx, headY))
        drawRoundRect(
            accent.copy(alpha = 0.45f),
            Offset(cx - headR * 2f, headY + headR * 1.25f),
            Size(headR * 4f, (top + h) - (headY + headR * 1.25f)),
            CornerRadius(headR * 1.6f, headR * 1.6f),
        )
        // Prompter lines near the top (eye line).
        val lineH = maxOf(2.dp.toPx(), h * 0.025f)
        val lineW = listOf(0.62f, 0.48f, 0.55f)
        lineW.forEachIndexed { i, f ->
            val lw = w * f
            val lx = if (rtl) left + w - w * 0.12f - lw else left + w * 0.12f
            drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(lx, top + h * 0.09f + i * lineH * 2.3f), Size(lw, lineH), CornerRadius(lineH / 2))
        }
        if (autoCaptions) {
            val capW = w * 0.64f
            val capH = maxOf(6.dp.toPx(), h * 0.07f)
            drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(cx - capW / 2, top + h * 0.78f), Size(capW, capH), CornerRadius(capH / 2))
            drawRoundRect(Color.White.copy(alpha = 0.95f), Offset(cx - capW * 0.36f, top + h * 0.78f + capH * 0.38f), Size(capW * 0.72f, capH * 0.24f), CornerRadius(capH))
        }
        drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(left, top), Size(w, h), corner, style = Stroke(1.dp.toPx()))
    }
}
