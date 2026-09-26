package com.ravango.engine.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import com.ravango.core.designsystem.theme.fontResIds
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.PrompterFont
import com.ravango.core.model.PrompterTextAlign
import com.ravango.core.model.SubtitleAnimation
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.TextStyleSpec
import com.ravango.engine.editor.composition.SubtitleFrameState
import com.ravango.engine.editor.composition.SubtitleTiming
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders text overlays, stickers and subtitle cues to bitmaps with [StaticLayout], using the app's bundled Persian
 * fonts. RTL/LTR is resolved per text ([ContentDirection.AUTO] uses the first strong character), alignment START/END
 * follows the paragraph direction, and outline, shadow and rounded backgrounds are drawn around the glyphs.
 *
 * Sizes are expressed in "canvas sp": the canvas is treated as a 360 dp wide phone screen, so a style looks the same in
 * the 720p preview and in a 4K export.
 */
class TextRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun pxPerSp(canvasWidth: Int, canvasHeight: Int): Float = min(canvasWidth, canvasHeight) / 360f

    fun typeface(font: PrompterFont, weight: Int): Typeface = typefaces.getOrPut("$font/$weight") {
        val bold = weight >= 600
        val (regular, boldRes) = font.fontResIds()
        val res = if (bold) boldRes ?: regular else regular
        val loaded = res?.let { runCatching { ResourcesCompat.getFont(appContext, it) }.getOrNull() }
        when {
            loaded != null -> loaded
            else -> {
                val base = when (font) {
                    PrompterFont.SYSTEM_SERIF -> Typeface.SERIF
                    PrompterFont.SYSTEM_MONO -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
                if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight.coerceIn(100, 900), false)
                else Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /**
     * Renders a text overlay. [scale] multiplies the font size (item transform scale), [reveal] (0..1) hides trailing
     * characters for the typewriter animation while keeping the layout stable.
     */
    fun renderText(text: String, style: TextStyleSpec, canvasWidth: Int, canvasHeight: Int, scale: Float, reveal: Float = 1f): Bitmap {
        val px = pxPerSp(canvasWidth, canvasHeight)
        val textSize = (style.sizeSp * px * scale).coerceIn(4f, 2000f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = textSize
            typeface = typeface(style.font, style.weight)
            color = style.color.toColorInt()
        }
        val content = text.ifEmpty { " " }
        val spannable = SpannableString(content)
        val revealed = if (reveal >= 1f) content.length else (content.length * reveal).toInt().coerceIn(0, content.length)
        if (revealed < content.length) spannable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), revealed, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val maxWidth = (canvasWidth * 0.92f).toInt().coerceAtLeast(1)
        val desired = ceil(maxLineWidth(content, paint)).toInt().coerceIn(1, maxWidth)
        val layout = buildLayout(spannable, paint, desired, style.align.toLayoutAlignment(), direction(style.direction), justify = style.align == PrompterTextAlign.JUSTIFY)

        val hasBg = style.backgroundColor != null
        val outline = if (style.outlineColor != null && style.outlineWidth > 0f) style.outlineWidth.coerceIn(0f, 1f) * textSize * 0.22f else 0f
        val pad = if (hasBg) textSize * 0.38f else outline + textSize * 0.12f
        val w = layout.width + (pad * 2).toInt()
        val h = layout.height + (pad * 2).toInt()
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        style.backgroundColor?.let { bg ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.toColorInt() }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), textSize * 0.32f, textSize * 0.32f, p)
        }
        canvas.translate(pad, pad)
        if (outline > 0f) {
            val strokePaint = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = outline
                strokeJoin = Paint.Join.ROUND
                color = style.outlineColor!!.toColorInt()
            }
            val strokeText = SpannableString(content)
            if (revealed < content.length) strokeText.setSpan(ForegroundColorSpan(Color.TRANSPARENT), revealed, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            buildLayout(strokeText, strokePaint, desired, style.align.toLayoutAlignment(), direction(style.direction), style.align == PrompterTextAlign.JUSTIFY).draw(canvas)
        }
        if (style.shadow && !hasBg && reveal >= 1f) paint.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.04f, 0x99000000.toInt())
        layout.draw(canvas)
        return bitmap
    }

    /** Emoji stickers: rendered with the system emoji font at [sizePx]. */
    fun renderEmoji(emoji: String, sizePx: Float): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx.coerceIn(8f, 2000f) }
        val width = ceil(paint.measureText(emoji)).toInt().coerceAtLeast(1)
        val fm = paint.fontMetrics
        val height = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        val pad = (sizePx * 0.1f).toInt()
        val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(emoji, pad.toFloat(), pad - fm.ascent, paint)
        return bitmap
    }

    /**
     * Renders one subtitle cue in its current animation state (karaoke highlight / word-by-word visibility).
     * Each line gets its own rounded background box.
     */
    fun renderSubtitle(state: SubtitleFrameState, style: SubtitleStyle, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val px = pxPerSp(canvasWidth, canvasHeight)
        val textSize = (style.sizeSp * px).coerceIn(4f, 2000f)
        val raw = if (style.uppercase) state.cue.text.uppercase() else state.cue.text
        val content = raw.trim().ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = textSize
            typeface = typeface(style.font, style.weight)
            color = style.color.toColorInt()
        }
        val spannable = SpannableString(content)
        val tokenRanges = tokenRanges(content)
        val words = SubtitleTiming.words(state.cue)
        if (words.size == tokenRanges.size) {
            when (style.animation) {
                SubtitleAnimation.KARAOKE -> state.activeWord.takeIf { it in tokenRanges.indices }?.let { i ->
                    spannable.setSpan(ForegroundColorSpan(style.activeWordColor.toColorInt()), tokenRanges[i].first, tokenRanges[i].last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                SubtitleAnimation.WORD_BY_WORD -> if (state.visibleWords < tokenRanges.size) {
                    val from = tokenRanges[state.visibleWords.coerceIn(0, tokenRanges.lastIndex)].first
                    spannable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), from, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // Highlight the newest word.
                    state.activeWord.takeIf { it in tokenRanges.indices && it < state.visibleWords }?.let { i ->
                        spannable.setSpan(ForegroundColorSpan(style.activeWordColor.toColorInt()), tokenRanges[i].first, tokenRanges[i].last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    state.activeWord.takeIf { it in tokenRanges.indices }?.let { i ->
                        spannable.setSpan(ForegroundColorSpan(style.activeWordColor.toColorInt()), tokenRanges[i].first, tokenRanges[i].last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else -> Unit
            }
        }
        val maxWidth = (canvasWidth * style.maxWidthFraction.coerceIn(0.3f, 1f)).toInt().coerceAtLeast(1)
        val desired = ceil(maxLineWidth(content, paint)).toInt().coerceIn(1, maxWidth)
        val layout = buildLayout(spannable, paint, desired, Layout.Alignment.ALIGN_CENTER, TextDirectionHeuristics.FIRSTSTRONG_LTR, justify = false)
        val padX = textSize * 0.4f
        val padY = textSize * 0.18f
        val outline = if (style.outlineColor != null) textSize * 0.1f else 0f
        val w = layout.width + (padX * 2).toInt()
        val h = layout.height + (padY * 2).toInt()
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        style.backgroundColor?.let { bg ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.toColorInt() }
            val visibleLines = if (style.animation == SubtitleAnimation.WORD_BY_WORD && state.visibleWords < tokenRanges.size && tokenRanges.isNotEmpty()) {
                layout.getLineForOffset(tokenRanges[(state.visibleWords - 1).coerceIn(0, tokenRanges.lastIndex)].last) + 1
            } else layout.lineCount
            for (line in 0 until visibleLines) {
                val left = layout.getLineLeft(line) + padX - textSize * 0.28f
                val right = layout.getLineRight(line) + padX + textSize * 0.28f
                val top = layout.getLineTop(line) + padY - textSize * 0.1f
                val bottom = layout.getLineBottom(line) + padY + textSize * 0.1f
                canvas.drawRoundRect(RectF(left, top, right, bottom), textSize * 0.25f, textSize * 0.25f, p)
            }
        }
        canvas.translate(padX, padY)
        if (outline > 0f) {
            val strokePaint = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = outline
                strokeJoin = Paint.Join.ROUND
                color = style.outlineColor!!.toColorInt()
            }
            val strokeText = SpannableString(content)
            if (style.animation == SubtitleAnimation.WORD_BY_WORD && state.visibleWords < tokenRanges.size && tokenRanges.isNotEmpty()) {
                strokeText.setSpan(ForegroundColorSpan(Color.TRANSPARENT), tokenRanges[state.visibleWords.coerceIn(0, tokenRanges.lastIndex)].first, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            buildLayout(strokeText, strokePaint, desired, Layout.Alignment.ALIGN_CENTER, TextDirectionHeuristics.FIRSTSTRONG_LTR, false).draw(canvas)
        } else if (style.backgroundColor == null) {
            paint.setShadowLayer(textSize * 0.1f, 0f, textSize * 0.04f, 0xAA000000.toInt())
        }
        layout.draw(canvas)
        return bitmap
    }

    private fun maxLineWidth(text: CharSequence, paint: TextPaint): Float =
        text.split('\n').maxOf { Layout.getDesiredWidth(it, paint) } + 1f

    private fun buildLayout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment, dir: TextDirectionHeuristic, justify: Boolean): StaticLayout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(align)
            .setTextDirection(dir)
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
        if (justify) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private fun direction(d: ContentDirection): TextDirectionHeuristic = when (d) {
        ContentDirection.AUTO -> TextDirectionHeuristics.FIRSTSTRONG_LTR
        ContentDirection.RTL -> TextDirectionHeuristics.RTL
        ContentDirection.LTR -> TextDirectionHeuristics.LTR
    }

    private fun PrompterTextAlign.toLayoutAlignment(): Layout.Alignment = when (this) {
        PrompterTextAlign.START, PrompterTextAlign.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
        PrompterTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        PrompterTextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
    }

    companion object {
        /** Character ranges (inclusive) of whitespace-separated tokens. */
        fun tokenRanges(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var start = -1
            for (i in text.indices) {
                val ws = text[i].isWhitespace()
                if (!ws && start < 0) start = i
                if (ws && start >= 0) { out += start until i; start = -1 }
            }
            if (start >= 0) out += start until text.length
            return out
        }
    }
}

internal fun Long.toColorInt(): Int = (this and 0xFFFFFFFFL).toInt()
