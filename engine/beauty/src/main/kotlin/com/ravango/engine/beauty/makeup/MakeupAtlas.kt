package com.ravango.engine.beauty.makeup

import com.ravango.engine.beauty.makeup.UvCanvas.Companion.smoothstep
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex as L
import com.ravango.engine.beauty.mesh.FaceMeshModel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Makeup textures authored in the canonical face-mesh UV space — the Lens Studio "Face Mask" technique: each
 * texture is rendered on the tracked mesh, so the makeup sits on the skin and follows every expression.
 *
 * Every shape is placed relative to landmark UVs and drawn with analytic signed distances (soft, anti-aliased
 * edges). Paired features are drawn on the subject's right side and mirrored (the UV layout is symmetric), so both
 * sides match exactly. Channel layout (the shader relies on it):
 *
 * - **A** ([SIZE_A]², detail): R lips · G brows (fill + hair strokes) · B eyeliner with wing · A lashes
 * - **B** ([SIZE_B]², soft): R eyeshadow (lash line → crease gradient) · G blush · B contour · A highlight
 * - **C** ([SIZE_C]², regions): R skin (face minus eyes/brows/lips, feathered) · G under-eye · B lip gloss zone
 *
 * Pure Kotlin and deterministic (seeded strokes), so it is unit-tested on the JVM and generated once per process.
 */
object MakeupAtlas {

    const val SIZE_A = 1024
    const val SIZE_B = 512
    const val SIZE_C = 512

    class Textures(val a: ByteArray, val b: ByteArray, val c: ByteArray) {
        val sizeA = SIZE_A
        val sizeB = SIZE_B
        val sizeC = SIZE_C
    }

    fun generate(model: FaceMeshModel, sizeA: Int = SIZE_A, sizeB: Int = SIZE_B, sizeC: Int = SIZE_C): Textures {
        val p = Painter(model)
        val a = ByteArray(sizeA * sizeA * 4)
        val b = ByteArray(sizeB * sizeB * 4)
        val c = ByteArray(sizeC * sizeC * 4)
        UvCanvas(sizeA).let { cv ->
            p.lips(cv); cv.writeChannel(a, 0); cv.clear()
            p.brows(cv); cv.writeChannel(a, 1); cv.clear()
            p.eyeliner(cv); cv.writeChannel(a, 2); cv.clear()
            p.lashes(cv); cv.writeChannel(a, 3)
        }
        UvCanvas(sizeB).let { cv ->
            p.eyeshadow(cv); cv.writeChannel(b, 0); cv.clear()
            p.blush(cv); cv.writeChannel(b, 1); cv.clear()
            p.contour(cv); cv.writeChannel(b, 2); cv.clear()
            p.highlight(cv); cv.writeChannel(b, 3)
        }
        UvCanvas(sizeC).let { cv ->
            p.skin(cv); cv.writeChannel(c, 0); cv.clear()
            p.underEye(cv); cv.writeChannel(c, 1); cv.clear()
            p.gloss(cv); cv.writeChannel(c, 2)
            // Alpha stays 0 (reserved).
        }
        return Textures(a, b, c)
    }

    /** Channel readback helper for tests and diagnostics: value 0..1 of [channel] at (u, v). */
    fun sample(rgba: ByteArray, size: Int, channel: Int, u: Float, v: Float): Float {
        val i = (u * size).toInt().coerceIn(0, size - 1)
        val j = (v * size).toInt().coerceIn(0, size - 1)
        return (rgba[(j * size + i) * 4 + channel].toInt() and 0xFF) / 255f
    }

    internal class Painter(private val model: FaceMeshModel) {

        /** (u, v) pairs of [indices], optionally mirrored to the other side (u → 1 − u). */
        fun pts(indices: IntArray, mirror: Boolean = false): FloatArray {
            val out = FloatArray(indices.size * 2)
            for (k in indices.indices) {
                val u = model.u(indices[k])
                out[k * 2] = if (mirror) 1f - u else u
                out[k * 2 + 1] = model.v(indices[k])
            }
            return out
        }

        private inline fun bothSides(block: (mirror: Boolean) -> Unit) { block(false); block(true) }

        private fun m(u: Float, mirror: Boolean) = if (mirror) 1f - u else u

        // -------------------------------------------------------------------------------------------- A: detail

        fun lips(cv: UvCanvas) {
            val outer = Geometry.smoothClosed(pts(L.LIPS_OUTER), 4)
            val inner = Geometry.smoothOpen(pts(L.LIPS_INNER + L.LIPS_INNER[0]), 2)
            val innerLengths = Geometry.cumulativeLengths(inner)
            val hit = FloatArray(2)
            val b = UvCanvas.bounds(outer, 0.01f)
            cv.forBox(b[0], b[1], b[2], b[3], { u, v ->
                val cover = smoothstep(-0.0035f, 0.0035f, Geometry.signedDistance(outer, u, v))
                if (cover <= 0f) 0f else {
                    // Slightly lighter right at the inner edge so an open mouth never shows a hard painted rim.
                    Geometry.distanceToPolyline(inner, innerLengths, u, v, hit)
                    cover * (0.72f + 0.28f * smoothstep(0f, 0.007f, hit[0]))
                }
            }, UvCanvas.Op.MAX)
        }

        fun brows(cv: UvCanvas) {
            val rnd = Random(0x5EED_B40)
            bothSides { mirror ->
                val upper = pts(L.RIGHT_BROW_UPPER, mirror)
                val lower = pts(L.RIGHT_BROW_LOWER, mirror)
                val poly = Geometry.smoothClosed(upper + reversed(lower), 3)
                // Head (t = 0) → tail (t = 1) axis, for the soft brow head and the hair direction.
                val headU = (upper[0] + lower[0]) * 0.5f; val headV = (upper[1] + lower[1]) * 0.5f
                val tailU = (upper[8] + lower[8]) * 0.5f; val tailV = (upper[9] + lower[9]) * 0.5f
                val axisU = tailU - headU; val axisV = tailV - headV
                val axisLen2 = axisU * axisU + axisV * axisV
                fun along(u: Float, v: Float) = (((u - headU) * axisU + (v - headV) * axisV) / axisLen2).coerceIn(0f, 1f)

                // Soft fill: lighter at the head (makeup-artist gradient), full through the arch and tail.
                val b = UvCanvas.bounds(poly, 0.01f)
                cv.forBox(b[0], b[1], b[2], b[3], { u, v ->
                    val cover = smoothstep(-0.005f, 0.004f, Geometry.signedDistance(poly, u, v))
                    cover * 0.5f * (0.55f + 0.45f * smoothstep(0f, 0.3f, along(u, v)))
                }, UvCanvas.Op.MAX)

                // Hair strokes: rooted inside the brow, pointing up-and-out at the head, along the brow at the tail.
                val axisAngle = atan2(axisV, axisU)
                // The head→tail axis points to −u on the right brow, so turning it towards +v is clockwise there.
                val up = if (mirror) 1f else -1f
                var planted = 0
                var attempts = 0
                while (planted < 150 && attempts < 5000) {
                    attempts++
                    val su = b[0] + rnd.nextFloat() * (b[2] - b[0])
                    val sv = b[1] + rnd.nextFloat() * (b[3] - b[1])
                    if (Geometry.signedDistance(poly, su, sv) < 0.002f) continue
                    val t = along(su, sv)
                    val tilt = (1f - smoothstep(0f, 0.45f, t)) * 1.05f + 0.12f + (rnd.nextFloat() - 0.5f) * 0.25f
                    val angle = axisAngle + up * tilt
                    val len = 0.011f + rnd.nextFloat() * 0.009f
                    val bend = up * (0.10f + rnd.nextFloat() * 0.08f)
                    val mu = su + cos(angle) * len * 0.5f; val mv = sv + sin(angle) * len * 0.5f
                    val eu = mu + cos(angle - bend) * len * 0.5f; val ev = mv + sin(angle - bend) * len * 0.5f
                    val strength = 0.65f + rnd.nextFloat() * 0.35f
                    cv.stroke(
                        floatArrayOf(su, sv, mu, mv, eu, ev), feather = 0.0007f,
                        halfWidth = { k -> 0.00085f * (1f - 0.6f * k) },
                        value = strength * (0.6f + 0.4f * smoothstep(0f, 0.25f, t)),
                    )
                    planted++
                }
                // Clip stray hair tips to a slightly grown brow outline.
                cv.forBox(b[0] - 0.01f, b[1] - 0.01f, b[2] + 0.01f, b[3] + 0.01f, { u, v ->
                    smoothstep(-0.0045f, 0.0005f, Geometry.signedDistance(poly, u, v))
                }, UvCanvas.Op.MULTIPLY)
            }
        }

        fun eyeliner(cv: UvCanvas) {
            bothSides { mirror ->
                val upper = pts(L.RIGHT_EYE_UPPER, mirror) // inner → outer (… 161, 246, 33)
                val n = upper.size / 2
                val iu = upper[0]; val iv = upper[1]
                val ou = upper[(n - 1) * 2]; val ov = upper[(n - 1) * 2 + 1]
                // Lid line on the lash line: thin at the inner corner, fuller towards the outer corner. Its lower half
                // lies inside the eye opening, which is never painted, so the visible line sits right above the lashes.
                cv.stroke(Geometry.smoothOpen(upper, 3), feather = 0.0011f,
                    halfWidth = { t -> 0.0016f + 0.0036f * t.pow(1.6f) },
                    profile = { t -> 0.55f + 0.45f * smoothstep(0f, 0.12f, t) })
                // Wing: a tapered triangle whose lower edge continues from the outer corner up to a lifted tip and
                // whose upper edge leaves the lid a little before the corner.
                val axis = atan2(ov - iv, ou - iu)
                val wingAngle = axis + (if (mirror) 0.42f else -0.42f)
                val len = 0.026f
                val tipU = ou + cos(wingAngle) * len; val tipV = ov + sin(wingAngle) * len
                val p1u = upper[(n - 3) * 2]; val p1v = upper[(n - 3) * 2 + 1]
                val p2u = upper[(n - 2) * 2]; val p2v = upper[(n - 2) * 2 + 1]
                val wing = floatArrayOf(
                    p1u, p1v + 0.0042f,
                    p2u, p2v + 0.0056f,
                    tipU, tipV,
                    ou, ov - 0.0008f,
                    p2u, p2v - 0.001f,
                    p1u, p1v - 0.001f,
                )
                cv.polygon(wing, feather = 0.0009f)
            }
        }

        fun lashes(cv: UvCanvas) {
            val rnd = Random(0x1A5E5)
            bothSides { mirror ->
                val upper = Geometry.smoothOpen(pts(L.RIGHT_EYE_UPPER, mirror), 4)
                val lower = Geometry.smoothOpen(pts(L.RIGHT_EYE_LOWER, mirror), 4)
                val outwardSign = if (mirror) 1f else -1f // outer corner direction in u
                // Dense root line.
                cv.stroke(upper, feather = 0.001f, halfWidth = { t -> 0.0016f + 0.0012f * t }, profile = { t -> 0.5f + 0.5f * smoothstep(0f, 0.2f, t) })
                plantLashes(cv, upper, count = 72, upwards = true, outwardSign = outwardSign, rnd = rnd,
                    length = { t -> 0.0055f + 0.0145f * t.pow(1.3f) }, width = 0.0008f, value = 0.95f, from = 0.04f, to = 1f)
                plantLashes(cv, lower, count = 26, upwards = false, outwardSign = outwardSign, rnd = rnd,
                    length = { t -> 0.0028f + 0.0045f * t }, width = 0.0006f, value = 0.55f, from = 0.35f, to = 0.97f)
            }
        }

        private fun plantLashes(
            cv: UvCanvas, line: FloatArray, count: Int, upwards: Boolean, outwardSign: Float, rnd: Random,
            length: (Float) -> Float, width: Float, value: Float, from: Float, to: Float,
        ) {
            val lengths = Geometry.cumulativeLengths(line)
            val total = lengths.last()
            val n = line.size / 2
            for (k in 0 until count) {
                val t = from + (to - from) * (k + rnd.nextFloat() * 0.8f) / count
                // Locate t on the polyline.
                val target = t * total
                var seg = 0
                while (seg < n - 2 && lengths[seg + 1] < target) seg++
                val f = ((target - lengths[seg]) / max(lengths[seg + 1] - lengths[seg], 1e-9f)).coerceIn(0f, 1f)
                val su = line[seg * 2] + (line[seg * 2 + 2] - line[seg * 2]) * f
                val sv = line[seg * 2 + 1] + (line[seg * 2 + 3] - line[seg * 2 + 1]) * f
                var tu = line[seg * 2 + 2] - line[seg * 2]; var tv = line[seg * 2 + 3] - line[seg * 2 + 1]
                val tl = sqrt(tu * tu + tv * tv).coerceAtLeast(1e-9f); tu /= tl; tv /= tl
                // Normal pointing away from the eye opening.
                var nu = -tv; var nv = tu
                if ((nv > 0f) != upwards) { nu = -nu; nv = -nv }
                // Sweep towards the outer corner, more so at the outer end (flick).
                val sweep = (0.15f + 0.75f * t * t) * (if (upwards) 1f else 0.6f)
                var du = nu + outwardSign * sweep
                var dv = nv
                val dl = sqrt(du * du + dv * dv); du /= dl; dv /= dl
                val len = length(t) * (0.85f + rnd.nextFloat() * 0.3f)
                // Curl: the tip bends further outwards/upwards.
                val cu = su + du * len * 0.55f; val cvv = sv + dv * len * 0.55f
                val tipU = cu + (du + outwardSign * 0.35f) * len * 0.45f
                val tipV = cvv + (dv + (if (upwards) 0.15f else -0.15f)) * len * 0.45f
                cv.stroke(floatArrayOf(su, sv, cu, cvv, tipU, tipV), feather = 0.0006f,
                    halfWidth = { s -> width * (1f - 0.75f * s) }, value = value * (0.8f + rnd.nextFloat() * 0.2f))
            }
        }

        // -------------------------------------------------------------------------------------------- B: soft

        fun eyeshadow(cv: UvCanvas) {
            bothSides { mirror ->
                val eye = pts(L.RIGHT_EYE_UPPER, mirror) // inner → outer
                val brow = pts(L.RIGHT_BROW_LOWER, mirror) // head → tail
                val n = eye.size / 2
                val iu = eye[0]; val iv = eye[1]
                val ou = eye[(n - 1) * 2]; val ov = eye[(n - 1) * 2 + 1]
                // Extend past the outer corner towards the brow tail (outer "V").
                val extU = ou + (brow[8] - ou) * 0.35f; val extV = ov + (brow[9] - ov) * 0.35f
                val lashLine = eye + floatArrayOf(extU, extV)
                val lashLengths = Geometry.cumulativeLengths(lashLine)
                val browLengths = Geometry.cumulativeLengths(brow)
                val poly = Geometry.smoothClosed(lashLine + reversed(brow), 2)
                val axisU = ou - iu; val axisV = ov - iv
                val axisLen2 = axisU * axisU + axisV * axisV
                val hit = FloatArray(2)
                val b = UvCanvas.bounds(poly, 0.012f)
                cv.forBox(b[0], b[1], b[2], b[3], { u, v ->
                    val cover = smoothstep(-0.009f, 0.006f, Geometry.signedDistance(poly, u, v))
                    if (cover <= 0f) 0f else {
                        Geometry.distanceToPolyline(lashLine, lashLengths, u, v, hit)
                        val dEye = hit[0]
                        Geometry.distanceToPolyline(brow, browLengths, u, v, hit)
                        val dBrow = hit[0]
                        val t = dEye / max(dEye + dBrow, 1e-6f) // 0 at the lash line, 1 at the brow
                        val s = (((u - iu) * axisU + (v - iv) * axisV) / axisLen2).coerceIn(0f, 1.4f)
                        val gradient = 1f - smoothstep(0.04f, 0.58f, t)
                        val outer = 0.72f + 0.28f * smoothstep(0.25f, 1.05f, s)
                        cover * gradient * outer
                    }
                }, UvCanvas.Op.MAX)
                // Smudged outer lower lash line.
                val lower = pts(L.RIGHT_EYE_LOWER, mirror)
                val outerLower = lower.copyOfRange(8, lower.size)
                cv.stroke(outerLower, feather = 0.006f, halfWidth = { 0.004f }, profile = { t -> 0.45f * smoothstep(0f, 0.6f, t) })
            }
            cv.blur(1)
        }

        fun blush(cv: UvCanvas) {
            bothSides { mirror ->
                // Apple of the cheek, long axis rising towards the temple.
                val cu = m(0.252f, mirror)
                val angle = if (mirror) (PI * 0.14).toFloat() else (PI * 0.86).toFloat()
                cv.gaussian(cu, 0.472f, 0.095f, 0.062f, angle, 1f)
            }
        }

        fun contour(cv: UvCanvas) {
            bothSides { mirror ->
                // Cheekbone hollow: from the ear towards the mouth corner, stopping under the apple.
                val hollow = floatArrayOf(m(0.03f, mirror), 0.482f, m(0.13f, mirror), 0.438f, m(0.235f, mirror), 0.392f)
                cv.stroke(Geometry.smoothOpen(hollow, 6), feather = 0.028f, halfWidth = { t -> 0.024f - 0.012f * t },
                    profile = { t -> 0.9f * (1f - smoothstep(0.7f, 1f, t)) + 0.1f })
                // Jaw line (the stroke straddles the oval edge; the outside half is not part of the mesh).
                val jaw = pts(intArrayOf(93, 132, 58, 172, 136, 150, 149, 176), mirror)
                cv.stroke(Geometry.smoothOpen(jaw, 4), feather = 0.03f, halfWidth = { 0.02f },
                    profile = { t -> 0.75f * smoothstep(0f, 0.15f, t) * (1f - smoothstep(0.65f, 1f, t)) })
                // Temples.
                val temple = pts(intArrayOf(127, 162, 21, 54, 103), mirror)
                cv.stroke(Geometry.smoothOpen(temple, 4), feather = 0.03f, halfWidth = { 0.018f },
                    profile = { t -> 0.5f * (1f - smoothstep(0.6f, 1f, t)) })
                // Sides of the nose.
                val nose = floatArrayOf(m(0.468f, mirror), 0.636f, m(0.462f, mirror), 0.545f, m(0.452f, mirror), 0.478f)
                cv.stroke(Geometry.smoothOpen(nose, 4), feather = 0.011f, halfWidth = { 0.005f },
                    profile = { t -> 0.55f * smoothstep(0f, 0.2f, t) * (1f - smoothstep(0.8f, 1f, t)) })
            }
            cv.blur(2)
        }

        fun highlight(cv: UvCanvas) {
            bothSides { mirror ->
                // Top of the cheekbones.
                val cheek = floatArrayOf(m(0.15f, mirror), 0.548f, m(0.22f, mirror), 0.537f, m(0.285f, mirror), 0.517f)
                cv.stroke(Geometry.smoothOpen(cheek, 6), feather = 0.02f, halfWidth = { t -> 0.013f - 0.005f * t },
                    profile = { t -> smoothstep(0f, 0.2f, t) })
                // Brow bone under the arch and tail.
                val browBone = floatArrayOf(m(0.337f, mirror), 0.702f, m(0.296f, mirror), 0.692f, m(0.262f, mirror), 0.672f)
                cv.stroke(Geometry.smoothOpen(browBone, 4), feather = 0.008f, halfWidth = { 0.005f }, value = 0.6f)
                // Inner eye corner.
                cv.gaussian(m(0.432f, mirror), 0.622f, 0.008f, 0.008f, 0f, 0.7f)
            }
            // Nose bridge and tip, cupid's bow, chin, forehead centre.
            cv.stroke(floatArrayOf(0.5f, 0.632f, 0.5f, 0.566f, 0.5f, 0.5f), feather = 0.008f, halfWidth = { t -> 0.007f - 0.002f * t },
                profile = { t -> 0.8f * smoothstep(0f, 0.15f, t) })
            cv.gaussian(0.5f, 0.458f, 0.014f, 0.011f, 0f, 0.6f)
            cv.gaussian(0.5f, 0.361f, 0.024f, 0.009f, 0f, 0.8f)
            cv.gaussian(0.5f, 0.112f, 0.04f, 0.025f, 0f, 0.5f)
            cv.gaussian(0.5f, 0.78f, 0.06f, 0.045f, 0f, 0.35f)
            cv.blur(1)
        }

        // -------------------------------------------------------------------------------------------- C: regions

        fun skin(cv: UvCanvas) {
            val oval = Geometry.smoothClosed(pts(L.FACE_OVAL), 3)
            cv.polygon(oval, feather = 0.03f, bias = -0.03f)
            val lips = Geometry.smoothClosed(pts(L.LIPS_OUTER), 3)
            cv.polygon(lips, feather = 0.006f, op = UvCanvas.Op.ERASE, bias = 0.002f)
            bothSides { mirror ->
                cv.polygon(Geometry.smoothClosed(pts(L.RIGHT_EYE, mirror), 3), feather = 0.008f, op = UvCanvas.Op.ERASE, bias = 0.006f)
                val brow = pts(L.RIGHT_BROW_UPPER, mirror) + reversed(pts(L.RIGHT_BROW_LOWER, mirror))
                cv.polygon(Geometry.smoothClosed(brow, 3), feather = 0.008f, value = 0.85f, op = UvCanvas.Op.ERASE)
            }
        }

        fun underEye(cv: UvCanvas) {
            bothSides { mirror ->
                val lower = Geometry.smoothOpen(pts(L.RIGHT_EYE_LOWER, mirror), 3) // inner → outer
                val eye = pts(L.RIGHT_EYE, mirror)
                val lengths = Geometry.cumulativeLengths(lower)
                val hit = FloatArray(2)
                val b = UvCanvas.bounds(lower, 0.06f)
                val centerV = (eye.filterIndexed { k, _ -> k % 2 == 1 }.average()).toFloat()
                cv.forBox(b[0], b[1], b[2], b[3], { u, v ->
                    if (v > centerV || Geometry.inside(eye, u, v)) 0f else {
                        Geometry.distanceToPolyline(lower, lengths, u, v, hit)
                        val d = hit[0]
                        val t = hit[1]
                        val band = smoothstep(0.002f, 0.009f, d) * (1f - smoothstep(0.026f, 0.05f, d))
                        // Strongest at the inner half (tear trough), fading at both corners.
                        val along = sin(PI.toFloat() * t.coerceIn(0f, 1f)).coerceAtLeast(0f).pow(0.6f) * (1f - 0.35f * t)
                        band * along
                    }
                }, UvCanvas.Op.MAX)
            }
            cv.blur(2)
        }

        fun gloss(cv: UvCanvas) {
            cv.gaussian(0.5f, 0.283f, 0.048f, 0.012f, 0f, 1f)
            cv.gaussian(0.5f, 0.327f, 0.032f, 0.008f, 0f, 0.6f)
            val lips = Geometry.smoothClosed(pts(L.LIPS_OUTER), 3)
            val b = UvCanvas.bounds(lips, 0.02f)
            cv.forBox(b[0] - 0.05f, b[1] - 0.05f, b[2] + 0.05f, b[3] + 0.05f, { u, v ->
                smoothstep(-0.002f, 0.004f, Geometry.signedDistance(lips, u, v))
            }, UvCanvas.Op.MULTIPLY)
        }

        private fun reversed(pts: FloatArray): FloatArray {
            val n = pts.size / 2
            val out = FloatArray(pts.size)
            for (k in 0 until n) { out[k * 2] = pts[(n - 1 - k) * 2]; out[k * 2 + 1] = pts[(n - 1 - k) * 2 + 1] }
            return out
        }
    }
}
