package com.ravango.engine.beauty.effects

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Procedural 3D colour LUTs for [LiveFilter]s, in the common "512 × 512, 64 levels, 8 × 8 tiles" layout:
 * blue level `b` selects tile `(b % 8, b / 8)`, and inside a tile x = red level, y = green level. Row 0 of the
 * returned RGBA buffer is uploaded first (texture t = 0), which is exactly how the shader addresses it.
 *
 * Each filter is a small, deterministic grading function (lift/gain, S-curve, saturation, split toning, channel
 * mixing) evaluated on the 64³ lattice; the GPU interpolates trilinearly between lattice points. Pure Kotlin so it
 * is unit-tested on the JVM and can also colour the filter previews in the UI.
 */
object LutGenerator {

    const val LEVELS = 64
    const val TILES = 8
    const val SIZE = LEVELS * TILES

    /** Generates the RGBA LUT image (SIZE × SIZE × 4 bytes) of [filter]. */
    fun generate(filter: LiveFilter): ByteArray {
        val out = ByteArray(SIZE * SIZE * 4)
        val c = FloatArray(3)
        val inv = 1f / (LEVELS - 1)
        for (b in 0 until LEVELS) {
            val tx = (b % TILES) * LEVELS
            val ty = (b / TILES) * LEVELS
            for (g in 0 until LEVELS) {
                val row = (ty + g) * SIZE
                for (r in 0 until LEVELS) {
                    grade(filter, r * inv, g * inv, b * inv, c)
                    val o = (row + tx + r) * 4
                    out[o] = byte(c[0])
                    out[o + 1] = byte(c[1])
                    out[o + 2] = byte(c[2])
                    out[o + 3] = -1 // 255
                }
            }
        }
        return out
    }

    /** Looks up [r], [g], [b] (0..1) in a generated [lut] with trilinear interpolation (mirrors the shader). */
    fun sample(lut: ByteArray, r: Float, g: Float, b: Float, out: FloatArray) {
        val bl = b.coerceIn(0f, 1f) * (LEVELS - 1)
        val b0 = bl.toInt().coerceAtMost(LEVELS - 1)
        val b1 = min(b0 + 1, LEVELS - 1)
        val fb = bl - b0
        val rl = r.coerceIn(0f, 1f) * (LEVELS - 1)
        val gl = g.coerceIn(0f, 1f) * (LEVELS - 1)
        for (ch in 0 until 3) {
            val v0 = bilinear(lut, b0, rl, gl, ch)
            val v1 = bilinear(lut, b1, rl, gl, ch)
            out[ch] = v0 + (v1 - v0) * fb
        }
    }

    private fun bilinear(lut: ByteArray, blue: Int, rl: Float, gl: Float, ch: Int): Float {
        val r0 = rl.toInt().coerceAtMost(LEVELS - 1); val r1 = min(r0 + 1, LEVELS - 1); val fr = rl - r0
        val g0 = gl.toInt().coerceAtMost(LEVELS - 1); val g1 = min(g0 + 1, LEVELS - 1); val fg = gl - g0
        val tx = (blue % TILES) * LEVELS
        val ty = (blue / TILES) * LEVELS
        fun at(ri: Int, gi: Int) = (lut[((ty + gi) * SIZE + tx + ri) * 4 + ch].toInt() and 0xFF) / 255f
        val a = at(r0, g0) + (at(r1, g0) - at(r0, g0)) * fr
        val c = at(r0, g1) + (at(r1, g1) - at(r0, g1)) * fr
        return a + (c - a) * fg
    }

    /** The grading function of [filter] applied to one colour (0..1); result clamped to 0..1 in [out]. */
    fun grade(filter: LiveFilter, r: Float, g: Float, b: Float, out: FloatArray) {
        var cr = r; var cg = g; var cb = b
        when (filter) {
            LiveFilter.NONE -> Unit
            LiveFilter.VIVID -> {
                val s = saturate(cr, cg, cb, 1.38f); cr = s[0]; cg = s[1]; cb = s[2]
                cr = curve(cr, 0.28f); cg = curve(cg, 0.28f); cb = curve(cb, 0.28f)
                cr += 0.012f
            }
            LiveFilter.WARM -> {
                cr = cr * 1.07f + 0.015f; cg = cg * 1.015f + 0.005f; cb = cb * 0.86f
                val l = luma(cr, cg, cb)
                cr += 0.05f * l * l; cg += 0.025f * l * l
                val s = saturate(cr, cg, cb, 1.06f); cr = s[0]; cg = s[1]; cb = s[2]
            }
            LiveFilter.COOL -> {
                val l = luma(cr, cg, cb)
                cr = cr * 0.9f; cg = cg * 0.99f + 0.01f; cb = cb * 1.08f + 0.03f
                // Teal shadows.
                val sh = (1f - l) * (1f - l)
                cg += 0.03f * sh; cb += 0.04f * sh
            }
            LiveFilter.FILM -> {
                cr = liftGain(cr, 0.055f, 0.955f); cg = liftGain(cg, 0.05f, 0.96f); cb = liftGain(cb, 0.06f, 0.94f)
                cr = curve(cr, 0.22f); cg = curve(cg, 0.22f); cb = curve(cb, 0.18f)
                splitTone(cr, cg, cb, floatArrayOf(-0.02f, 0.035f, 0.04f), floatArrayOf(0.05f, 0.025f, -0.03f), 1f)
                    .let { cr = it[0]; cg = it[1]; cb = it[2] }
                val s = saturate(cr, cg, cb, 0.86f); cr = s[0]; cg = s[1]; cb = s[2]
            }
            LiveFilter.MONO -> {
                val l = curve(luma(cr, cg, cb), 0.18f)
                cr = l; cg = l; cb = l
            }
            LiveFilter.NOIR -> {
                var l = luma(cr, cg, cb)
                l = ((l - 0.07f) / 0.86f).coerceIn(0f, 1f)
                l = curve(curve(l, 0.7f), 0.3f)
                cr = l; cg = l; cb = l * 1.02f
            }
            LiveFilter.PASTEL -> {
                val s = saturate(cr, cg, cb, 0.68f); cr = s[0]; cg = s[1]; cb = s[2]
                cr = liftGain(cr, 0.13f, 1f) + 0.02f; cg = liftGain(cg, 0.11f, 0.99f); cb = liftGain(cb, 0.14f, 1f) + 0.03f
                cr = curve(cr, -0.12f); cg = curve(cg, -0.12f); cb = curve(cb, -0.12f)
            }
            LiveFilter.FADE -> {
                val s = saturate(cr, cg, cb, 0.8f); cr = s[0]; cg = s[1]; cb = s[2]
                cr = liftGain(cr, 0.13f, 0.91f); cg = liftGain(cg, 0.13f, 0.91f); cb = liftGain(cb, 0.14f, 0.9f)
            }
            LiveFilter.SUNSET -> {
                val l = luma(cr, cg, cb)
                cr = cr * 1.1f + 0.02f; cg = cg * 0.98f; cb = cb * 0.84f
                splitTone(cr, cg, cb, floatArrayOf(0.05f, -0.02f, 0.07f), floatArrayOf(0.09f, 0.03f, -0.06f), 1f)
                    .let { cr = it[0]; cg = it[1]; cb = it[2] }
                cr += 0.04f * l
                val s = saturate(cr, cg, cb, 1.12f); cr = s[0]; cg = s[1]; cb = s[2]
            }
            LiveFilter.TEAL_ORANGE -> {
                splitTone(cr, cg, cb, floatArrayOf(-0.07f, 0.02f, 0.08f), floatArrayOf(0.09f, 0.025f, -0.08f), 1.1f)
                    .let { cr = it[0]; cg = it[1]; cb = it[2] }
                cr = curve(cr, 0.2f); cg = curve(cg, 0.2f); cb = curve(cb, 0.2f)
                val s = saturate(cr, cg, cb, 1.12f); cr = s[0]; cg = s[1]; cb = s[2]
            }
            LiveFilter.VINTAGE -> {
                // Partial sepia, faded blacks, warm and softly desaturated.
                val sr = 0.393f * cr + 0.769f * cg + 0.189f * cb
                val sg = 0.349f * cr + 0.686f * cg + 0.168f * cb
                val sb = 0.272f * cr + 0.534f * cg + 0.131f * cb
                cr = cr + (sr - cr) * 0.38f; cg = cg + (sg - cg) * 0.38f; cb = cb + (sb - cb) * 0.38f
                cr = liftGain(cr, 0.07f, 0.95f); cg = liftGain(cg, 0.075f, 0.93f); cb = liftGain(cb, 0.09f, 0.86f)
                cg += 0.012f * (1f - luma(cr, cg, cb))
                val s = saturate(cr, cg, cb, 0.82f); cr = s[0]; cg = s[1]; cb = s[2]
            }
            LiveFilter.CINEMA -> {
                cr = curve(cr, 0.36f); cg = curve(cg, 0.36f); cb = curve(cb, 0.33f)
                splitTone(cr, cg, cb, floatArrayOf(-0.035f, 0.01f, 0.05f), floatArrayOf(0.045f, 0.02f, -0.035f), 1f)
                    .let { cr = it[0]; cg = it[1]; cb = it[2] }
                cr = liftGain(cr, 0.02f, 0.97f); cg = liftGain(cg, 0.02f, 0.97f); cb = liftGain(cb, 0.025f, 0.96f)
                val s = saturate(cr, cg, cb, 0.9f); cr = s[0]; cg = s[1]; cb = s[2]
            }
        }
        out[0] = cr.coerceIn(0f, 1f)
        out[1] = cg.coerceIn(0f, 1f)
        out[2] = cb.coerceIn(0f, 1f)
    }

    /** Colours used to paint a filter's round preview chip (a warm skin tone, sky blue and foliage green). */
    fun previewSwatch(filter: LiveFilter): LongArray {
        val refs = arrayOf(floatArrayOf(0.86f, 0.64f, 0.52f), floatArrayOf(0.42f, 0.66f, 0.9f), floatArrayOf(0.36f, 0.62f, 0.34f))
        val c = FloatArray(3)
        return LongArray(refs.size) { i ->
            grade(filter, refs[i][0], refs[i][1], refs[i][2], c)
            (0xFFL shl 24) or (channel(c[0]) shl 16) or (channel(c[1]) shl 8) or channel(c[2])
        }
    }

    private fun channel(v: Float): Long = (v.coerceIn(0f, 1f) * 255f).roundToInt().toLong()

    private fun byte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    internal fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    /** Smoothstep-based S-curve; negative [amount] flattens contrast. */
    internal fun curve(x: Float, amount: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = c * c * (3f - 2f * c)
        return c + (s - c) * amount
    }

    internal fun liftGain(x: Float, lift: Float, gain: Float) = lift + x * (gain - lift)

    private val tmp = ThreadLocal.withInitial { FloatArray(3) }

    internal fun saturate(r: Float, g: Float, b: Float, s: Float): FloatArray {
        val l = luma(r, g, b)
        val o = tmp.get()
        o[0] = l + (r - l) * s; o[1] = l + (g - l) * s; o[2] = l + (b - l) * s
        return o
    }

    /** Adds [shadow] tint weighted towards dark tones and [highlight] tint towards bright ones. */
    private fun splitTone(r: Float, g: Float, b: Float, shadow: FloatArray, highlight: FloatArray, k: Float): FloatArray {
        val l = luma(r, g, b).coerceIn(0f, 1f)
        val ws = (1f - l) * (1f - l) * k
        val wh = l * l * k
        val o = tmp.get()
        o[0] = r + shadow[0] * ws + highlight[0] * wh
        o[1] = g + shadow[1] * ws + highlight[1] * wh
        o[2] = b + shadow[2] * ws + highlight[2] * wh
        return o
    }
}
