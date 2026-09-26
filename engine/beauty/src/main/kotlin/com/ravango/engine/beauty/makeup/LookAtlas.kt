package com.ravango.engine.beauty.makeup

import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.makeup.UvCanvas.Companion.smoothstep
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex as L
import com.ravango.engine.beauty.mesh.FaceMeshModel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ADDED — the look atlas: UV-space masks for the style variants of [MakeupStyle], painted once (like
 * [MakeupAtlas]) so every look is a pure uniform change on the GPU. Channel layout (the look shader relies on it):
 *
 * - **D** ([SIZE_D]², detail): R classic wing (minus the lid line — subtracting it leaves a tight line) ·
 *   G dramatic cat-eye (thick outer lid line, long lifted wing, inner-corner tick) · B volume lashes (multi-strand
 *   clusters, upper and lower) · A lower lash-line kohl
 * - **E** ([SIZE_E]², soft): R outer "V" + crease (second shadow tone) · G lid centre (shimmer) ·
 *   B lip centre (ombré) · A freckles
 *
 * Generated lazily on a background thread the first time a non-default style is used; the GL thread polls
 * [textures] and never blocks.
 */
object LookAtlas {

    const val SIZE_D = 1024
    const val SIZE_E = 512

    class Textures(val d: ByteArray, val e: ByteArray) {
        val sizeD = SIZE_D
        val sizeE = SIZE_E
    }

    @Volatile var textures: Textures? = null; private set
    @Volatile private var started = false

    /** Starts generating the atlas for [model] once (no-op when already started). */
    fun prepare(model: FaceMeshModel) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            try {
                val t0 = System.nanoTime()
                textures = generate(model)
                RgLog.i(TAG, "Look atlas ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (t: Throwable) {
                RgLog.e(TAG, "Look atlas unavailable", t)
            }
        }, "rg-look-atlas").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    fun generate(model: FaceMeshModel, sizeD: Int = SIZE_D, sizeE: Int = SIZE_E): Textures {
        val base = MakeupAtlas.Painter(model)
        val p = Painter(base)
        val d = ByteArray(sizeD * sizeD * 4)
        val e = ByteArray(sizeE * sizeE * 4)
        UvCanvas(sizeD).let { cv ->
            p.classicWingOnly(cv); cv.writeChannel(d, 0); cv.clear()
            p.dramaticLiner(cv); cv.writeChannel(d, 1); cv.clear()
            p.volumeLashes(cv); cv.writeChannel(d, 2); cv.clear()
            p.kohl(cv); cv.writeChannel(d, 3)
        }
        UvCanvas(sizeE).let { cv ->
            p.shadowAccent(cv); cv.writeChannel(e, 0); cv.clear()
            p.lidShimmer(cv); cv.writeChannel(e, 1); cv.clear()
            p.lipCenter(cv); cv.writeChannel(e, 2); cv.clear()
            p.freckles(cv); cv.writeChannel(e, 3)
        }
        return Textures(d, e)
    }

    private const val TAG = "LookAtlas"

    internal class Painter(private val base: MakeupAtlas.Painter) {

        private fun pts(indices: IntArray, mirror: Boolean) = base.pts(indices, mirror)

        // ------------------------------------------------------------------------------------------ D: detail

        /** Classic flick with the lid line removed: `A.b − D.r` is a tight line without a wing. */
        fun classicWingOnly(cv: UvCanvas) {
            val line = UvCanvas(cv.size)
            base.bothSides { mirror ->
                cv.polygon(base.classicWing(mirror), feather = 0.0012f, bias = 0.0004f)
                base.lidLine(line, mirror)
            }
            for (i in cv.data.indices) cv.data[i] = max(0f, cv.data[i] - line.data[i])
        }

        /** Thick outer lid line, long lifted cat-eye wing and an inner-corner tick (Arabic / siren liner). */
        fun dramaticLiner(cv: UvCanvas) {
            base.bothSides { mirror ->
                val upper = pts(L.RIGHT_EYE_UPPER, mirror)
                val lower = pts(L.RIGHT_EYE_LOWER, mirror)
                val n = upper.size / 2
                cv.stroke(Geometry.smoothOpen(upper, 3), feather = 0.0012f,
                    halfWidth = { t -> 0.002f + 0.0068f * t.pow(1.4f) },
                    profile = { t -> 0.6f + 0.4f * smoothstep(0f, 0.15f, t) })
                val iu = upper[0]; val iv = upper[1]
                val ou = upper[(n - 1) * 2]; val ov = upper[(n - 1) * 2 + 1]
                val axis = atan2(ov - iv, ou - iu)
                val lift = if (mirror) 0.5f else -0.5f
                val wingAngle = axis + lift
                val len = 0.05f
                val tipU = ou + cos(wingAngle) * len; val tipV = ov + sin(wingAngle) * len
                val pa = n - 4; val pb = n - 3; val pc = n - 2
                val upU = upper[pc * 2] + (tipU - upper[pc * 2]) * 0.5f
                val upV = upper[pc * 2 + 1] + 0.0095f + (tipV - upper[pc * 2 + 1] - 0.0095f) * 0.5f + 0.0015f
                val loU = ou + (tipU - ou) * 0.5f
                val loV = ov + (tipV - ov) * 0.5f - 0.0012f
                val wing = floatArrayOf(
                    upper[pa * 2], upper[pa * 2 + 1] + 0.0058f,
                    upper[pb * 2], upper[pb * 2 + 1] + 0.0082f,
                    upper[pc * 2], upper[pc * 2 + 1] + 0.0095f,
                    upU, upV,
                    tipU, tipV,
                    loU, loV,
                    ou, ov - 0.0014f,
                    upper[pc * 2], upper[pc * 2 + 1] - 0.001f,
                    upper[pb * 2], upper[pb * 2 + 1] - 0.001f,
                    upper[pa * 2], upper[pa * 2 + 1] - 0.001f,
                )
                cv.polygon(wing, feather = 0.001f)
                // Inner-corner tick pointing towards the nose and slightly down.
                val inward = axis + PI.toFloat() + (if (mirror) 0.38f else -0.38f)
                val tick = 0.011f
                cv.polygon(floatArrayOf(
                    iu, iv + 0.0024f,
                    iu + cos(inward) * tick, iv + sin(inward) * tick,
                    lower[4], lower[5] - 0.0006f,
                ), feather = 0.0009f)
            }
        }

        /** Longer, curled multi-strand lash clusters (upper) and fuller lower lashes. */
        fun volumeLashes(cv: UvCanvas) {
            val rnd = Random(0xB0_1A5E)
            base.bothSides { mirror ->
                val upper = Geometry.smoothOpen(pts(L.RIGHT_EYE_UPPER, mirror), 4)
                val lower = Geometry.smoothOpen(pts(L.RIGHT_EYE_LOWER, mirror), 4)
                val outwardSign = if (mirror) 1f else -1f
                cv.stroke(upper, feather = 0.001f, halfWidth = { t -> 0.0022f + 0.0016f * t }, profile = { t -> 0.6f + 0.4f * smoothstep(0f, 0.2f, t) })
                base.plantLashes(cv, upper, count = 44, upwards = true, outwardSign = outwardSign, rnd = rnd,
                    length = { t -> 0.0085f + 0.024f * t.pow(1.2f) }, width = 0.001f, value = 1f, from = 0.05f, to = 1.02f,
                    strands = 3, fan = 0.22f)
                base.plantLashes(cv, lower, count = 30, upwards = false, outwardSign = outwardSign, rnd = rnd,
                    length = { t -> 0.0035f + 0.0085f * t }, width = 0.0007f, value = 0.8f, from = 0.25f, to = 0.98f,
                    strands = 2, fan = 0.3f)
            }
        }

        /** A crisp kohl line under the lower lashes, extended a little past the outer corner. */
        fun kohl(cv: UvCanvas) {
            base.bothSides { mirror ->
                val lower = pts(L.RIGHT_EYE_LOWER, mirror) // inner → outer
                val n = lower.size / 2
                cv.stroke(Geometry.smoothOpen(lower, 3), feather = 0.0011f,
                    halfWidth = { t -> 0.0013f + 0.0017f * t },
                    profile = { t -> 0.55f + 0.45f * smoothstep(0f, 0.3f, t) })
                val ou = lower[(n - 1) * 2]; val ov = lower[(n - 1) * 2 + 1]
                val pu = lower[(n - 2) * 2]; val pv = lower[(n - 2) * 2 + 1]
                val du = ou - pu; val dv = ov - pv
                val dl = sqrt(du * du + dv * dv).coerceAtLeast(1e-6f)
                val ext = 0.009f
                cv.stroke(floatArrayOf(ou, ov, ou + du / dl * ext, ov + dv / dl * ext + 0.002f), feather = 0.0011f,
                    halfWidth = { t -> 0.0028f * (1f - 0.8f * t) }, value = 0.9f)
            }
        }

        // ------------------------------------------------------------------------------------------ E: soft

        /** Outer "V" and crease: where a deeper second shadow tone goes. */
        fun shadowAccent(cv: UvCanvas) {
            base.bothSides { mirror ->
                base.lidField(cv, mirror) { cover, t, s ->
                    val outerV = smoothstep(0.5f, 1.1f, s) * (1f - smoothstep(0.45f, 0.85f, t))
                    val crease = exp(-((t - 0.5f) / 0.14f).let { it * it }) * smoothstep(0.2f, 0.9f, s) * 0.75f
                    cover * max(outerV, crease)
                }
                val lower = pts(L.RIGHT_EYE_LOWER, mirror)
                cv.stroke(lower.copyOfRange(10, lower.size), feather = 0.008f, halfWidth = { 0.004f },
                    profile = { t -> 0.7f * smoothstep(0f, 0.5f, t) })
            }
            cv.blur(1)
        }

        /** Centre of the mobile lid (and inner corner): where shimmer catches the light. */
        fun lidShimmer(cv: UvCanvas) {
            base.bothSides { mirror ->
                base.lidField(cv, mirror) { cover, t, s ->
                    cover * (1f - smoothstep(0.02f, 0.42f, t)) * exp(-((s - 0.45f) / 0.3f).let { it * it })
                }
                val eye = pts(L.RIGHT_EYE_UPPER, mirror)
                cv.gaussian(eye[0], eye[1] + 0.002f, 0.008f, 0.007f, 0f, 0.65f)
            }
            cv.blur(1)
        }

        /** Where the lips meet (1) fading towards the outer lip line (0): the centre of an ombré / gradient lip. */
        fun lipCenter(cv: UvCanvas) {
            val outer = Geometry.smoothClosed(pts(L.LIPS_OUTER, false), 4)
            val inner = Geometry.smoothOpen(pts(L.LIPS_INNER + L.LIPS_INNER[0], false), 2)
            val innerLengths = Geometry.cumulativeLengths(inner)
            val hit = FloatArray(2)
            val b = UvCanvas.bounds(outer, 0.01f)
            cv.forBox(b[0], b[1], b[2], b[3], { u, v ->
                val cover = smoothstep(-0.003f, 0.003f, Geometry.signedDistance(outer, u, v))
                if (cover <= 0f) 0f else {
                    Geometry.distanceToPolyline(inner, innerLengths, u, v, hit)
                    cover * (1f - smoothstep(0f, 0.03f, hit[0]))
                }
            }, UvCanvas.Op.MAX)
            cv.blur(1)
        }

        /** Seeded freckles across the nose bridge and the upper cheeks (denser in the middle). */
        fun freckles(cv: UvCanvas) {
            val rnd = Random(0xF2EC)
            fun gauss(): Float {
                val u1 = rnd.nextFloat().coerceAtLeast(1e-6f)
                val u2 = rnd.nextFloat()
                return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
            }
            repeat(190) { k ->
                val onNose = k % 4 == 0
                val u: Float
                val v: Float
                if (onNose) {
                    u = 0.5f + gauss() * 0.032f
                    v = 0.56f + gauss() * 0.03f
                } else {
                    val side = if (rnd.nextBoolean()) 0f else 1f
                    val cu = 0.315f + gauss() * 0.045f
                    u = if (side == 0f) cu else 1f - cu
                    v = 0.535f + gauss() * 0.024f
                }
                val r = 0.0024f + rnd.nextFloat() * 0.003f
                val value = 0.45f + rnd.nextFloat() * 0.55f
                cv.gaussian(u, v, r, r * (0.8f + rnd.nextFloat() * 0.3f), rnd.nextFloat() * PI.toFloat(), value)
            }
        }
    }
}
