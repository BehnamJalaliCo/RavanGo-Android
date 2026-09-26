package com.ravango.engine.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.PrompterFont
import com.ravango.core.model.SubtitleTrack
import com.ravango.core.model.TextStyleSpec
import com.ravango.core.model.Transform2D
import com.ravango.engine.editor.composition.OverlayAnimState
import com.ravango.engine.editor.composition.OverlayTiming
import com.ravango.engine.editor.composition.SubtitleFrameState
import com.ravango.engine.editor.composition.SubtitleTiming

/**
 * Produces the bitmaps for overlay items at a given canvas size, with caching. Also used by the UI to know the on-canvas
 * size of an item (for selection handles), so preview handles and rendering always agree.
 */
class OverlayBitmapFactory(private val context: Context) {
    val textRenderer = TextRenderer(context)
    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Bitmap for [item] at scale (transform.scale) baked in. Returns null for PiP video (drawn by [PipEffect]). */
    fun bitmapFor(item: OverlayItem, canvasWidth: Int, canvasHeight: Int, reveal: Float = 1f): Bitmap? = when (item) {
        is OverlayItem.Text -> textRenderer.renderText(item.text, item.style, canvasWidth, canvasHeight, item.transform.scale, reveal)
        is OverlayItem.Sticker -> when {
            item.emoji != null -> textRenderer.renderEmoji(item.emoji!!, STICKER_SP * textRenderer.pxPerSp(canvasWidth, canvasHeight) * item.transform.scale)
            item.assetUri != null -> image(item.assetUri!!)?.let { ImageLoading.scaleToWidth(it, (canvasWidth * STICKER_WIDTH * item.transform.scale).toInt()) }
            else -> null
        }
        is OverlayItem.Image -> image(item.uri)?.let { ImageLoading.scaleToWidth(it, (canvasWidth * IMAGE_WIDTH * item.transform.scale).toInt()) }
        is OverlayItem.Video -> null
    }

    /** On-canvas size of [item] as fractions of the canvas (width, height) at transform scale. */
    fun normalizedSize(item: OverlayItem, canvasWidth: Int, canvasHeight: Int): Pair<Float, Float> {
        if (item is OverlayItem.Video) {
            val w = item.transform.scale
            val aspect = if (item.source.width > 0 && item.source.height > 0) item.source.height.toFloat() / item.source.width else 16f / 9f
            return w to (w * canvasWidth * aspect / canvasHeight)
        }
        val b = bitmapFor(item, canvasWidth, canvasHeight) ?: return 0.2f to 0.1f
        return b.width.toFloat() / canvasWidth to b.height.toFloat() / canvasHeight
    }

    fun image(uri: String): Bitmap? {
        imageCache.get(uri)?.let { return it }
        val decoded = ImageLoading.decode(context, uri, MAX_IMAGE_DIMENSION) ?: return null
        imageCache.put(uri, decoded)
        return decoded
    }

    companion object {
        const val STICKER_SP = 72f
        const val STICKER_WIDTH = 0.3f
        const val IMAGE_WIDTH = 0.4f
        const val MAX_IMAGE_DIMENSION = 2048
    }
}

/** Maps a normalized canvas position (0..1, y down) to Media3's NDC anchor (-1..1, y up). */
internal fun anchorFromCanvas(x: Float, y: Float): Pair<Float, Float> = (x * 2f - 1f) to (1f - y * 2f)

internal fun overlaySettings(transform: Transform2D, anim: OverlayAnimState): OverlaySettings {
    val (ax, ay) = anchorFromCanvas(transform.centerX + anim.offsetX, transform.centerY + anim.offsetY)
    val alpha = if (!anim.visible) 0f else (transform.opacity * anim.alpha).coerceIn(0f, 1f)
    val s = anim.scale.coerceAtLeast(0.001f)
    return StaticOverlaySettings.Builder()
        .setAlphaScale(alpha)
        .setBackgroundFrameAnchor(ax, ay)
        .setOverlayFrameAnchor(0f, 0f)
        .setScale(s, s)
        // Media3 rotates counter-clockwise for positive degrees; the model uses clockwise (screen) degrees.
        .setRotationDegrees(-(transform.rotationDegrees + anim.rotationDegrees))
        .build()
}

private val EMPTY_BITMAP: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

/**
 * A text/sticker/image overlay with its own time range and in/out animation. The bitmap is rendered once (typewriter
 * text re-renders per revealed character count, cached) and only re-uploaded when it changes.
 */
internal class TimedItemOverlay(
    private val item: OverlayItem,
    private val factory: OverlayBitmapFactory,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    prerendered: Bitmap?,
) : BitmapOverlay() {
    private val base: Bitmap = prerendered ?: EMPTY_BITMAP
    private val typewriter = item is OverlayItem.Text && item.animationIn == OverlayAnimation.TYPEWRITER
    private val revealCache = LruCache<Int, Bitmap>(8)
    private var lastTime = Long.MIN_VALUE
    private var lastState: OverlayAnimState = OverlayAnimState.Hidden

    private fun stateAt(t: Long): OverlayAnimState {
        if (t != lastTime) {
            lastTime = t
            lastState = OverlayTiming.state(item, t)
        }
        return lastState
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val s = stateAt(presentationTimeUs)
        if (!typewriter || !s.visible || s.reveal >= 1f) return base
        val text = (item as OverlayItem.Text).text
        val count = (text.length * s.reveal).toInt()
        return revealCache.get(count) ?: (factory.bitmapFor(item, canvasWidth, canvasHeight, count.toFloat() / text.length.coerceAtLeast(1)) ?: base).also { revealCache.put(count, it) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings(item.transform, stateAt(presentationTimeUs))
}

/**
 * Burns subtitles in. Each cue state (cue × active word × visible words) is rendered once and cached; between cues a
 * 1×1 transparent bitmap is used so nothing is uploaded.
 */
internal class SubtitleOverlay(
    private val track: SubtitleTrack,
    private val renderer: TextRenderer,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
) : BitmapOverlay() {
    private val cues = track.cues.sortedBy { it.startUs }
    private val cache = LruCache<String, Bitmap>(24)
    private var lastTime = Long.MIN_VALUE
    private var lastState: SubtitleFrameState? = null

    private fun stateAt(t: Long): SubtitleFrameState? {
        if (t != lastTime) {
            lastTime = t
            lastState = SubtitleTiming.cueAt(cues, t)?.let { SubtitleTiming.state(it, track.style.animation, t) }
        }
        return lastState
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val s = stateAt(presentationTimeUs) ?: return EMPTY_BITMAP
        val key = "${s.cue.id}|${s.activeWord}|${s.visibleWords}|${s.cue.text.hashCode()}"
        return cache.get(key) ?: renderer.renderSubtitle(s, track.style, canvasWidth, canvasHeight).also { cache.put(key, it) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val s = stateAt(presentationTimeUs)
        val (ax, ay) = anchorFromCanvas(0.5f, track.style.positionY.coerceIn(0.05f, 0.95f) + (s?.offsetY ?: 0f))
        return StaticOverlaySettings.Builder()
            .setAlphaScale(s?.alpha ?: 0f)
            .setBackgroundFrameAnchor(ax, ay)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(s?.scale ?: 1f, s?.scale ?: 1f)
            .build()
    }
}

/** The free-plan export watermark: brand word mark, bottom end corner, 70% opacity. */
internal class WatermarkOverlay(bitmap: Bitmap, private val canvasWidth: Int, private val canvasHeight: Int) : BitmapOverlay() {
    private val bmp = bitmap
    private val settings: OverlaySettings = run {
        val marginX = 0.04f
        val marginY = 0.035f
        val w = bmp.width.toFloat() / canvasWidth
        val h = bmp.height.toFloat() / canvasHeight
        val (ax, ay) = anchorFromCanvas(1f - marginX - w / 2f, 1f - marginY - h / 2f)
        StaticOverlaySettings.Builder().setAlphaScale(0.72f).setBackgroundFrameAnchor(ax, ay).setOverlayFrameAnchor(0f, 0f).build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    companion object {
        const val BRAND = "RavanGo"

        fun render(renderer: TextRenderer, canvasWidth: Int, canvasHeight: Int): Bitmap =
            renderer.renderText(
                BRAND,
                TextStyleSpec(font = PrompterFont.VAZIRMATN, sizeSp = 15f, weight = 700, color = 0xFFFFFFFF, outlineColor = null, shadow = true),
                canvasWidth, canvasHeight, 1f,
            )
    }
}

/** Splits overlays into [OverlayEffect]s of at most [MAX_PER_EFFECT] textures (one sampler unit each). */
internal fun overlayEffects(overlays: List<TextureOverlay>): List<OverlayEffect> =
    overlays.chunked(MAX_PER_EFFECT).map { OverlayEffect(it) }

private const val MAX_PER_EFFECT = 6
