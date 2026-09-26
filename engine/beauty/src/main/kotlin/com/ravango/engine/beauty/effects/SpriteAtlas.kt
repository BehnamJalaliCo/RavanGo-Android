package com.ravango.engine.beauty.effects

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.effects.SpriteAtlasLayout.Region
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lens art, drawn procedurally (vector paths + gradients, anti-aliased) into one 1024² premultiplied bitmap
 * once per process on a background thread — original artwork, nothing copyrighted is bundled. Regions are fixed by
 * [SpriteAtlasLayout]; each drawing keeps a transparent margin so mipmapped sampling never bleeds between regions.
 */
object SpriteAtlas {

    /** The atlas once [prepare] finished (also used by the UI for lens previews). */
    @Volatile var bitmap: Bitmap? = null; private set
    @Volatile var failed: Boolean = false; private set
    @Volatile private var started = false

    fun prepare() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            try {
                val t0 = System.nanoTime()
                obtain()
                RgLog.i(TAG, "Lens sprite atlas ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (t: Throwable) {
                failed = true
                RgLog.e(TAG, "Lens sprite atlas unavailable", t)
            }
        }, "rg-lens-atlas").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Returns the atlas, drawing it on the calling thread if [prepare] has not finished yet (call off the main thread). */
    fun obtain(): Bitmap = bitmap ?: synchronized(this) { bitmap ?: draw().also { bitmap = it } }

    fun draw(): Bitmap {
        val bmp = Bitmap.createBitmap(SpriteAtlasLayout.SIZE, SpriteAtlasLayout.SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        sunglasses(c, SpriteAtlasLayout.SUNGLASSES)
        crown(c, SpriteAtlasLayout.CROWN)
        catEar(c, SpriteAtlasLayout.CAT_EAR)
        catFace(c, SpriteAtlasLayout.CAT_FACE)
        star(c, SpriteAtlasLayout.STAR)
        sparkle(c, SpriteAtlasLayout.SPARKLE)
        glow(c, SpriteAtlasLayout.GLOW)
        heart(c, SpriteAtlasLayout.HEART)
        rainbow(c, SpriteAtlasLayout.RAINBOW)
        return bmp
    }

    private fun paint(color: Int = 0xFFFFFFFF.toInt(), style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.style = style
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private inline fun Canvas.inRegion(r: Region, block: Canvas.() -> Unit) {
        save()
        clipRect(r.x.toFloat(), r.y.toFloat(), (r.x + r.w).toFloat(), (r.y + r.h).toFloat())
        translate(r.x.toFloat(), r.y.toFloat())
        block()
        restore()
    }

    // ------------------------------------------------------------------------------------------------ sunglasses

    private fun sunglasses(c: Canvas, r: Region) = c.inRegion(r) {
        val lensW = 372f; val lensH = 236f; val top = 58f
        val lenses = listOf(RectF(94f, top, 94f + lensW, top + lensH), RectF(1024f - 94f - lensW, top, 1024f - 94f, top + lensH))
        val shadow = paint(0x55000000).apply { maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
        for (l in lenses) drawPath(lensPath(l), shadow)
        for (l in lenses) {
            val path = lensPath(l)
            drawPath(path, paint().apply {
                shader = LinearGradient(0f, l.top, 0f, l.bottom, intArrayOf(0xF0140E22.toInt(), 0xE8331F4D.toInt(), 0xE06B3E7A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            // Glare: a soft diagonal band and a small highlight.
            save()
            clipPath(path)
            val glare = Path().apply {
                moveTo(l.left + l.width() * 0.18f, l.top - 10f)
                lineTo(l.left + l.width() * 0.46f, l.top - 10f)
                lineTo(l.left + l.width() * 0.12f, l.bottom + 10f)
                lineTo(l.left - l.width() * 0.16f, l.bottom + 10f)
                close()
            }
            drawPath(glare, paint(0x2EFFFFFF))
            drawCircle(l.left + l.width() * 0.76f, l.top + l.height() * 0.24f, 16f, paint(0x55FFFFFF))
            restore()
            drawPath(path, paint(0xFF0B0B10.toInt(), Paint.Style.STROKE).apply { strokeWidth = 18f })
            drawPath(path, paint(0x33FFFFFF, Paint.Style.STROKE).apply { strokeWidth = 4f })
        }
        // Bridge and temple stubs.
        val bridge = Path().apply {
            moveTo(lenses[0].right - 6f, top + 40f)
            quadTo(512f, top - 14f, lenses[1].left + 6f, top + 40f)
        }
        drawPath(bridge, paint(0xFF0B0B10.toInt(), Paint.Style.STROKE).apply { strokeWidth = 18f })
        val stub = paint(0xFF0B0B10.toInt(), Paint.Style.STROKE).apply { strokeWidth = 16f }
        drawLine(lenses[0].left + 8f, top + 36f, 22f, top + 24f, stub)
        drawLine(lenses[1].right - 8f, top + 36f, 1002f, top + 24f, stub)
    }

    /** Soft "wayfarer" lens: flat top, rounded bottom that is fuller towards the outside. */
    private fun lensPath(l: RectF): Path = Path().apply {
        val w = l.width(); val h = l.height()
        moveTo(l.left + w * 0.1f, l.top)
        lineTo(l.right - w * 0.1f, l.top)
        cubicTo(l.right + w * 0.02f, l.top, l.right, l.top + h * 0.35f, l.right - w * 0.03f, l.top + h * 0.6f)
        cubicTo(l.right - w * 0.08f, l.bottom, l.left + w * 0.72f, l.bottom, l.left + w * 0.45f, l.bottom)
        cubicTo(l.left + w * 0.12f, l.bottom, l.left, l.top + h * 0.7f, l.left, l.top + h * 0.3f)
        cubicTo(l.left, l.top + h * 0.05f, l.left + w * 0.03f, l.top, l.left + w * 0.1f, l.top)
        close()
    }

    // ------------------------------------------------------------------------------------------------ crown

    private fun crown(c: Canvas, r: Region) = c.inRegion(r) {
        val gold = LinearGradient(0f, 30f, 0f, 270f, intArrayOf(0xFFFFF0A0.toInt(), 0xFFFFC83D.toInt(), 0xFFE39B0B.toInt(), 0xFFB87400.toInt()), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        val body = Path().apply {
            moveTo(40f, 250f)
            lineTo(34f, 110f)
            lineTo(126f, 175f)
            lineTo(186f, 58f)
            lineTo(256f, 150f)
            lineTo(326f, 58f)
            lineTo(386f, 175f)
            lineTo(478f, 110f)
            lineTo(472f, 250f)
            close()
        }
        drawPath(body, paint(0x44000000).apply { maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) })
        drawPath(body, paint().apply { shader = gold })
        // Band.
        val band = RectF(34f, 206f, 478f, 262f)
        drawRoundRect(band, 18f, 18f, paint().apply {
            shader = LinearGradient(0f, band.top, 0f, band.bottom, intArrayOf(0xFFFFD86B.toInt(), 0xFFD18A00.toInt()), null, Shader.TileMode.CLAMP)
        })
        drawPath(body, paint(0xFF8A5200.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f })
        drawRoundRect(band, 18f, 18f, paint(0xFF8A5200.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f })
        // Pearls on the tips.
        for ((x, y) in listOf(34f to 104f, 186f to 50f, 326f to 50f, 478f to 104f)) gem(this, x, y, 17f, 0xFFFFFBF0.toInt(), 0xFFD9CFC0.toInt())
        gem(this, 256f, 138f, 20f, 0xFFFF6FA3.toInt(), 0xFFB5174F.toInt())
        // Jewels on the band.
        gem(this, 256f, 234f, 22f, 0xFFFF4D6D.toInt(), 0xFF9E0B2E.toInt())
        gem(this, 150f, 234f, 16f, 0xFF4F8DFF.toInt(), 0xFF1A3FA8.toInt())
        gem(this, 362f, 234f, 16f, 0xFF3DDC97.toInt(), 0xFF0E7A4E.toInt())
        gem(this, 80f, 234f, 10f, 0xFFFFFFFF.toInt(), 0xFFBFD7FF.toInt())
        gem(this, 432f, 234f, 10f, 0xFFFFFFFF.toInt(), 0xFFBFD7FF.toInt())
        // Specular streak.
        drawLine(70f, 120f, 70f, 196f, paint(0x66FFFFFF, Paint.Style.STROKE).apply { strokeWidth = 8f })
    }

    private fun gem(c: Canvas, x: Float, y: Float, radius: Float, light: Int, dark: Int) {
        c.drawCircle(x, y, radius, paint().apply {
            shader = RadialGradient(x - radius * 0.35f, y - radius * 0.35f, radius * 1.4f, intArrayOf(light, dark), null, Shader.TileMode.CLAMP)
        })
        c.drawCircle(x, y, radius, paint(0x88000000.toInt(), Paint.Style.STROKE).apply { strokeWidth = 3f })
        c.drawCircle(x - radius * 0.35f, y - radius * 0.38f, radius * 0.28f, paint(0xCCFFFFFF.toInt()))
    }

    // ------------------------------------------------------------------------------------------------ cat

    private fun catEar(c: Canvas, r: Region) = c.inRegion(r) {
        val outer = Path().apply {
            moveTo(18f, 246f)
            cubicTo(22f, 160f, 60f, 60f, 104f, 16f)
            quadTo(116f, 6f, 126f, 18f)
            cubicTo(168f, 70f, 202f, 160f, 206f, 246f)
            close()
        }
        drawPath(outer, paint().apply {
            shader = LinearGradient(0f, 10f, 0f, 250f, intArrayOf(0xFF5A4640.toInt(), 0xFF8C6F63.toInt(), 0xFFB99A86.toInt()), null, Shader.TileMode.CLAMP)
        })
        val inner = Path().apply {
            moveTo(56f, 240f)
            cubicTo(62f, 176f, 84f, 100f, 112f, 62f)
            cubicTo(140f, 100f, 162f, 176f, 168f, 240f)
            close()
        }
        drawPath(inner, paint().apply {
            shader = LinearGradient(0f, 60f, 0f, 240f, intArrayOf(0xFFFF9DBA.toInt(), 0xFFFFC2D3.toInt()), null, Shader.TileMode.CLAMP)
        })
        drawPath(outer, paint(0xFF2E2320.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f })
        // Fur tufts at the base.
        val tuft = paint(0xDDF7EFE9.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f }
        for (k in 0 until 5) {
            val x = 78f + k * 17f
            drawLine(x, 238f, x + (k - 2) * 4f, 196f + (k % 2) * 10f, tuft)
        }
    }

    private fun catFace(c: Canvas, r: Region) = c.inRegion(r) {
        // Whiskers (drawn first, under the nose), with a soft dark shadow for contrast on light skin.
        val shadow = paint(0x55000000, Paint.Style.STROKE).apply { strokeWidth = 9f; maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL) }
        val whisker = paint(0xF2FFFFFF.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f }
        for (side in intArrayOf(-1, 1)) {
            for (k in 0 until 3) {
                val y0 = 94f + k * 16f
                val path = Path().apply {
                    moveTo(256f + side * 64f, y0)
                    quadTo(256f + side * 160f, y0 - 18f + k * 10f, 256f + side * 236f, y0 - 40f + k * 36f)
                }
                drawPath(path, shadow)
                drawPath(path, whisker)
            }
        }
        // Nose.
        val nose = Path().apply {
            moveTo(214f, 58f)
            cubicTo(214f, 40f, 298f, 40f, 298f, 58f)
            cubicTo(298f, 74f, 270f, 98f, 256f, 100f)
            cubicTo(242f, 98f, 214f, 74f, 214f, 58f)
            close()
        }
        drawPath(nose, paint().apply {
            shader = LinearGradient(0f, 40f, 0f, 100f, intArrayOf(0xFFFF9EB8.toInt(), 0xFFE0567A.toInt()), null, Shader.TileMode.CLAMP)
        })
        drawPath(nose, paint(0xFF7A1F3A.toInt(), Paint.Style.STROKE).apply { strokeWidth = 4f })
        drawCircle(240f, 54f, 7f, paint(0xAAFFFFFF.toInt()))
        // Little mouth.
        val mouth = Path().apply {
            moveTo(256f, 100f)
            lineTo(256f, 116f)
            moveTo(226f, 116f)
            quadTo(241f, 134f, 256f, 116f)
            quadTo(271f, 134f, 286f, 116f)
        }
        drawPath(mouth, paint(0xFF7A1F3A.toInt(), Paint.Style.STROKE).apply { strokeWidth = 5f })
    }

    // ------------------------------------------------------------------------------------------------ particles

    private fun star(c: Canvas, r: Region) = c.inRegion(r) {
        val cx = 64f; val cy = 66f
        drawCircle(cx, cy, 60f, paint().apply {
            shader = RadialGradient(cx, cy, 60f, intArrayOf(0x88FFF3B0.toInt(), 0x00FFF3B0), null, Shader.TileMode.CLAMP)
        })
        val path = Path()
        for (k in 0 until 10) {
            val a = -PI / 2 + k * PI / 5
            val rad = if (k % 2 == 0) 50.0 else 21.0
            val x = cx + (rad * cos(a)).toFloat(); val y = cy + (rad * sin(a)).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, paint().apply {
            shader = RadialGradient(cx, cy - 6f, 54f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE066.toInt(), 0xFFFFB020.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        drawPath(path, paint(0x66B36B00, Paint.Style.STROKE).apply { strokeWidth = 3f })
    }

    private fun sparkle(c: Canvas, r: Region) = c.inRegion(r) {
        val cx = 64f; val cy = 64f
        drawCircle(cx, cy, 60f, paint().apply {
            shader = RadialGradient(cx, cy, 60f, intArrayOf(0xAAFFFFFF.toInt(), 0x33CFE8FF, 0x00CFE8FF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        })
        val path = Path().apply {
            moveTo(cx, cy - 58f)
            quadTo(cx + 7f, cy - 7f, cx + 58f, cy)
            quadTo(cx + 7f, cy + 7f, cx, cy + 58f)
            quadTo(cx - 7f, cy + 7f, cx - 58f, cy)
            quadTo(cx - 7f, cy - 7f, cx, cy - 58f)
            close()
        }
        drawPath(path, paint().apply {
            shader = RadialGradient(cx, cy, 58f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE3F1FF.toInt(), 0xFFB9DCFF.toInt()), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun glow(c: Canvas, r: Region) = c.inRegion(r) {
        drawCircle(64f, 64f, 62f, paint().apply {
            shader = RadialGradient(64f, 64f, 62f, intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun heart(c: Canvas, r: Region) = c.inRegion(r) {
        val path = Path().apply {
            moveTo(64f, 112f)
            cubicTo(20f, 82f, 8f, 58f, 14f, 40f)
            cubicTo(22f, 14f, 56f, 12f, 64f, 38f)
            cubicTo(72f, 12f, 106f, 14f, 114f, 40f)
            cubicTo(120f, 58f, 108f, 82f, 64f, 112f)
            close()
        }
        drawPath(path, paint().apply {
            shader = LinearGradient(0f, 14f, 0f, 112f, intArrayOf(0xFFFF9EC0.toInt(), 0xFFFF3D7F.toInt()), null, Shader.TileMode.CLAMP)
        })
        drawCircle(40f, 40f, 9f, paint(0xAAFFFFFF.toInt()))
    }

    private fun rainbow(c: Canvas, r: Region) = c.inRegion(r) {
        val colors = intArrayOf(
            0x00FF5A5F, 0xFFFF5A5F.toInt(), 0xFFFF9F43.toInt(), 0xFFFFE066.toInt(), 0xFF5FD38D.toInt(),
            0xFF4FC3F7.toInt(), 0xFF6C7BFF.toInt(), 0xFFB57BFF.toInt(), 0x00B57BFF,
        )
        val stops = floatArrayOf(0f, 0.08f, 0.22f, 0.36f, 0.5f, 0.64f, 0.78f, 0.92f, 1f)
        drawRect(8f, 8f, 504f, 88f, paint().apply { shader = LinearGradient(8f, 0f, 504f, 0f, colors, stops, Shader.TileMode.CLAMP) })
    }

    private const val TAG = "SpriteAtlas"
}
