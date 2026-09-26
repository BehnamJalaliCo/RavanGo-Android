package com.ravango.engine.ai.prompts

import com.ravango.core.model.AiOperation
import com.ravango.engine.ai.api.ContentPlatform
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.api.TextTask
import com.ravango.engine.ai.api.Tone
import com.ravango.engine.ai.llm.EffortHint
import kotlin.math.roundToInt

/** Everything needed to run one text task against a provider. */
data class PromptSpec(
    val system: String,
    val user: String,
    val maxTokens: Int,
    val effort: EffortHint,
    val operation: AiOperation,
    /** The output is a numbered list of alternatives (see [com.ravango.engine.ai.api.AiTextService.parseList]). */
    val isList: Boolean,
)

/**
 * System prompts for every [TextTask], written in the output language (Persian or English) so register and
 * typography instructions are natural. Other output languages use the English prompt plus an explicit language line.
 *
 * Shared rules: teleprompter-friendly spoken language, RavanGo markup (explained to the model), target length from
 * speaking rate (Persian ≈ 2.3 words/s, English ≈ 2.5 words/s), numbered lists for list tasks, no preamble.
 */
object PromptLibrary {

    const val PERSIAN_WORDS_PER_SECOND = 2.3
    const val ENGLISH_WORDS_PER_SECOND = 2.5

    val LIST_TASKS = setOf(TextTask.HOOKS, TextTask.CTA, TextTask.TITLES, TextTask.IDEAS, TextTask.CAPTION)

    private val SCRIPT_TASKS = setOf(TextTask.GENERATE_SCRIPT, TextTask.BULLETS_TO_SCRIPT, TextTask.LENGTHEN)

    /** Target spoken word count for a duration in the given language. */
    fun targetWords(durationSec: Int, language: String): Int {
        val wps = if (language.startsWith("fa")) PERSIAN_WORDS_PER_SECOND else ENGLISH_WORDS_PER_SECOND
        return (durationSec * wps).roundToInt().coerceAtLeast(10)
    }

    fun build(request: TextRequest): PromptSpec {
        val lang = request.outputLanguage.lowercase().ifBlank { "fa" }
        val persian = lang.startsWith("fa")
        val variants = request.variants.coerceIn(1, 10)
        val isList = request.task in LIST_TASKS && !(request.task == TextTask.CAPTION && variants == 1)
        val system = if (persian) PersianPrompts.system(request, variants) else EnglishPrompts.system(request, variants, lang)
        val user = userMessage(request, persian)
        val large = request.task in SCRIPT_TASKS || request.input.length > LARGE_INPUT_CHARS
        val effort = when (request.task) {
            TextTask.GENERATE_SCRIPT, TextTask.BULLETS_TO_SCRIPT, TextTask.LENGTHEN, TextTask.REWRITE,
            TextTask.CHANGE_TONE, TextTask.FORMAL_TO_CONVERSATIONAL, TextTask.IDEAS, TextTask.HOOKS -> EffortHint.MEDIUM
            else -> EffortHint.LOW
        }
        // max_tokens bounds thinking + answer; scale with input for rewrite-style tasks.
        // The gateway meters requests above SMALL_MAX_TOKENS as TEXT_LARGE, so keep these tiers aligned.
        val maxTokens = if (large) LARGE_MAX_TOKENS else SMALL_MAX_TOKENS
        return PromptSpec(system, user, maxTokens, effort, if (large) AiOperation.TEXT_LARGE else AiOperation.TEXT_SMALL, isList)
    }

    private const val LARGE_INPUT_CHARS = 6_000
    const val SMALL_MAX_TOKENS = 8_000
    const val LARGE_MAX_TOKENS = 16_000

    private fun userMessage(r: TextRequest, persian: Boolean): String = buildString {
        val topicTask = r.task == TextTask.GENERATE_SCRIPT || r.task == TextTask.IDEAS
        val tag = if (topicTask) "topic" else "text"
        if (persian) {
            append(if (topicTask) "موضوع:" else "متن ورودی:")
        } else {
            append(if (topicTask) "Topic:" else "Input text:")
        }
        append("\n<").append(tag).append(">\n")
        append(r.input.trim())
        append("\n</").append(tag).append(">")
        val extra = r.extraInstructions?.trim().orEmpty()
        if (extra.isNotEmpty()) {
            append(if (persian) "\n\nتوضیحات تکمیلی کاربر:\n" else "\n\nAdditional instructions from the user:\n")
            append(extra)
        }
    }

    internal fun platformNote(p: ContentPlatform, persian: Boolean): String = if (persian) {
        when (p) {
            ContentPlatform.INSTAGRAM -> "اینستاگرام (ریلز): جملهٔ اول باید در ۲ ثانیه مخاطب را نگه دارد؛ ریتم تند، جمله‌های کوتاه، لحن صمیمی."
            ContentPlatform.YOUTUBE -> "یوتیوب (ویدیوی بلند): مقدمهٔ جذاب، ساختار روشن با بخش‌بندی، جمع‌بندی و دعوت به اشتراک."
            ContentPlatform.YOUTUBE_SHORTS -> "یوتیوب شورتز: زیر ۶۰ ثانیه، قلاب قوی در ابتدا، یک ایدهٔ اصلی، پایان سریع."
            ContentPlatform.TIKTOK -> "تیک‌تاک: خیلی سریع و پرانرژی، قلاب فوری، زبان روزمره، یک پیام واحد."
            ContentPlatform.LINKEDIN -> "لینکدین: حرفه‌ای ولی انسانی، تجربهٔ شخصی و نکتهٔ کاربردی، بدون اغراق."
            ContentPlatform.TELEGRAM -> "تلگرام: مخاطب کانال؛ روشن، مفید و قابل اشتراک."
            ContentPlatform.PODCAST -> "پادکست: گفتاری و روایی، گذارهای نرم بین بخش‌ها، بدون اتکا به تصویر."
            ContentPlatform.GENERAL -> "ویدیوی عمومی شبکه‌های اجتماعی."
        }
    } else {
        when (p) {
            ContentPlatform.INSTAGRAM -> "Instagram Reels: the first line must stop the scroll within 2 seconds; fast rhythm, short sentences, warm tone."
            ContentPlatform.YOUTUBE -> "YouTube (long form): strong intro, clear sectioned structure, recap and a subscribe call at the end."
            ContentPlatform.YOUTUBE_SHORTS -> "YouTube Shorts: under 60 seconds, a strong hook up front, one core idea, a quick ending."
            ContentPlatform.TIKTOK -> "TikTok: very fast and energetic, instant hook, everyday language, a single message."
            ContentPlatform.LINKEDIN -> "LinkedIn: professional but human, personal experience plus a practical takeaway, no hype."
            ContentPlatform.TELEGRAM -> "Telegram channel audience: clear, useful and easy to forward."
            ContentPlatform.PODCAST -> "Podcast: conversational storytelling, smooth transitions, nothing that relies on visuals."
            ContentPlatform.GENERAL -> "General social video."
        }
    }

    internal fun toneNote(t: Tone?, persian: Boolean): String? = t?.let {
        if (persian) {
            when (it) {
                Tone.FRIENDLY -> "صمیمی و گرم، مثل صحبت با یک دوست"
                Tone.PROFESSIONAL -> "حرفه‌ای، دقیق و مطمئن"
                Tone.ENERGETIC -> "پرانرژی و هیجان‌انگیز"
                Tone.HUMOROUS -> "شوخ و بامزه، ولی محترمانه"
                Tone.INSPIRATIONAL -> "الهام‌بخش و امیدوارکننده"
                Tone.CALM -> "آرام، شمرده و مطمئن"
                Tone.PERSUASIVE -> "متقاعدکننده، با منطق و مثال"
                Tone.EDUCATIONAL -> "آموزشی، ساده و گام‌به‌گام"
            }
        } else {
            when (it) {
                Tone.FRIENDLY -> "friendly and warm, like talking to a friend"
                Tone.PROFESSIONAL -> "professional, precise and confident"
                Tone.ENERGETIC -> "energetic and exciting"
                Tone.HUMOROUS -> "witty and funny, but respectful"
                Tone.INSPIRATIONAL -> "inspiring and hopeful"
                Tone.CALM -> "calm, measured and reassuring"
                Tone.PERSUASIVE -> "persuasive, with reasoning and examples"
                Tone.EDUCATIONAL -> "educational, simple and step by step"
            }
        }
    }

    /** Colloquial Persian fits casual tones; formal-but-spoken fits the rest. */
    internal fun persianColloquial(t: Tone?): Boolean = when (t) {
        Tone.FRIENDLY, Tone.ENERGETIC, Tone.HUMOROUS, null -> true
        else -> false
    }

    internal fun languageName(tag: String): String = when (tag.substringBefore('-')) {
        "fa" -> "Persian (Farsi)"
        "en" -> "English"
        "ar" -> "Arabic"
        "tr" -> "Turkish"
        "de" -> "German"
        "fr" -> "French"
        "es" -> "Spanish"
        "ru" -> "Russian"
        else -> tag
    }
}
