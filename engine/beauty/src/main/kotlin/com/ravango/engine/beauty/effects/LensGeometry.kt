package com.ravango.engine.beauty.effects

import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import com.ravango.engine.beauty.mesh.FaceMeshModel
import com.ravango.engine.beauty.mesh.ReshapeField.Companion.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Warp lenses as displacement fields over the canonical face model (centimetres, x → subject's left, y up), exactly
 * like the beauty reshape field: authored once in a head-fixed frame, projected onto the tracked face by the pose
 * fit and protected against folds by `MeshWarp`. Amplitudes are chosen so the fields stay fold-free on a frontal
 * face at full strength.
 */
class LensWarpField(private val model: FaceMeshModel) {

    /** True when the field of [lens] changes over time (must be recomputed every frame). */
    fun isAnimated(lens: Lens): Boolean = lens == Lens.FACE_SWIRL

    /**
     * Writes a canonical (dx, dy) per mesh vertex for [lens] into [out] (size ≥ 2 × vertexCount).
     * Returns false (and writes zeros) for lenses without a warp.
     */
    fun compute(lens: Lens, timeSec: Float, out: FloatArray): Boolean {
        if (lens.kind != Lens.Kind.WARP) {
            out.fill(0f, 0, model.vertexCount * 2)
            return false
        }
        for (i in 0 until model.vertexCount) {
            val x = model.x(i)
            val y = model.y(i)
            var dx = 0f
            var dy = 0f
            when (lens) {
                Lens.BIG_EYES -> {
                    // Both eyes contribute (continuous across the nose bridge, which neither reaches).
                    for (side in SIDES) {
                        val ex = x - side * EYE_X
                        val ey = y - EYE_Y
                        val w = 1f - smoothstep(0.9f, 2.9f, sqrt(ex * ex + ey * ey * 1.5f))
                        dx += ex * 0.42f * w
                        dy += ey * 0.42f * w
                    }
                }
                Lens.PUFFY_CHEEKS -> {
                    for (side in SIDES) {
                        val ox = x - side * CHEEK_X
                        val oy = y - CHEEK_Y
                        val w = 1f - smoothstep(0.4f, 5.2f, sqrt(ox * ox + oy * oy))
                        // Inflate around the cheek centre and push outwards.
                        dx += ox * 0.22f * w + side * 1.05f * w
                        dy += oy * 0.22f * w - 0.25f * w
                    }
                }
                Lens.TINY_FACE -> {
                    // Uniform shrink towards the face centre; the padded ring keeps the head around it.
                    dx = (0f - x) * 0.38f
                    dy = (TINY_CENTER_Y - y) * 0.38f
                }
                Lens.ALIEN -> {
                    val ax = abs(x)
                    // Tall, wide cranium.
                    val top = smoothstep(2.5f, 8.5f, y)
                    dy += (y - 2.5f).coerceAtLeast(0f) * 0.42f * top
                    dx += x * 0.2f * top
                    // Big, slanted eyes.
                    for (side in SIDES) {
                        val ex = x - side * EYE_X
                        val ey = y - EYE_Y
                        val we = 1f - smoothstep(0.9f, 2.9f, sqrt(ex * ex + ey * ey * 1.5f))
                        dx += ex * 0.3f * we
                        dy += ey * 0.3f * we + 0.3f * we * smoothstep(-0.5f, 1.8f, ex * side)
                    }
                    // Narrow, pointed chin and a small mouth.
                    val low = smoothstep(-1.5f, -8.5f, y)
                    dx -= x * 0.34f * low * smoothstep(0.5f, 6f, ax)
                    val mx = x; val my = y - MOUTH_Y
                    val wm = 1f - smoothstep(0.8f, 3.4f, sqrt(mx * mx + my * my * 2f))
                    dx -= mx * 0.28f * wm
                    dy -= my * 0.28f * wm
                }
                Lens.FACE_SWIRL -> {
                    val ox = x
                    val oy = y - SWIRL_CENTER_Y
                    val r = sqrt(ox * ox + oy * oy)
                    val falloff = 1f - smoothstep(0f, SWIRL_RADIUS, r)
                    val amount = 0.62f + 0.28f * sin(timeSec * 1.3f)
                    val a = amount * falloff * falloff
                    val c = cos(a); val s = sin(a)
                    dx = c * ox - s * oy - ox
                    dy = s * ox + c * oy - oy
                }
                else -> Unit
            }
            out[i * 2] = dx
            out[i * 2 + 1] = dy
        }
        return true
    }

    companion object {
        const val EYE_X = 3.15f
        const val EYE_Y = 2.65f
        const val CHEEK_X = 4.9f
        const val CHEEK_Y = -2.2f
        const val MOUTH_Y = -4.3f
        const val TINY_CENTER_Y = 0.2f
        const val SWIRL_CENTER_Y = 0.2f
        const val SWIRL_RADIUS = 9.5f
        private val SIDES = floatArrayOf(-1f, 1f)
    }
}

/** Pixel rectangles of the procedurally drawn sprite atlas (see `SpriteAtlas`); shared by generator and renderer. */
object SpriteAtlasLayout {
    const val SIZE = 1024

    class Region(val x: Int, val y: Int, val w: Int, val h: Int) {
        val u0: Float get() = x / SIZE.toFloat()
        val v0: Float get() = y / SIZE.toFloat()
        val u1: Float get() = (x + w) / SIZE.toFloat()
        val v1: Float get() = (y + h) / SIZE.toFloat()
    }

    val SUNGLASSES = Region(0, 0, 1024, 352)
    val CROWN = Region(0, 352, 512, 288)
    val CAT_EAR = Region(512, 352, 224, 256)
    val CAT_FACE = Region(0, 640, 512, 176)
    val STAR = Region(768, 352, 128, 128)
    val SPARKLE = Region(896, 352, 128, 128)
    val GLOW = Region(768, 480, 128, 128)
    val HEART = Region(896, 480, 128, 128)
    val RAINBOW = Region(512, 640, 512, 96)
}

/**
 * Client-side vertex data for textured quads: 6 vertices per quad, 8 floats per vertex (NDC x, y · u, v ·
 * premultiplied r, g, b, a). A colour alpha of 0 with premultiplied blending makes a quad additive (sparkles).
 * Preallocated; [clear] + adds per frame never allocate.
 */
class SpriteBatch(val capacity: Int) {
    val data = FloatArray(capacity * 6 * FLOATS_PER_VERTEX)
    var quads = 0; private set

    fun clear() { quads = 0 }

    val vertexCount: Int get() = quads * 6

    /**
     * Adds a quad from four NDC corners (top-left, top-right, bottom-right, bottom-left) with the atlas [region]
     * (optionally mirrored horizontally) and a premultiplied colour multiplier.
     */
    fun add(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        region: SpriteAtlasLayout.Region, r: Float, g: Float, b: Float, a: Float, flipU: Boolean = false,
    ) {
        if (quads >= capacity) return
        val u0 = if (flipU) region.u1 else region.u0
        val u1 = if (flipU) region.u0 else region.u1
        var o = quads * 6 * FLOATS_PER_VERTEX
        o = put(o, x0, y0, u0, region.v0, r, g, b, a)
        o = put(o, x1, y1, u1, region.v0, r, g, b, a)
        o = put(o, x2, y2, u1, region.v1, r, g, b, a)
        o = put(o, x0, y0, u0, region.v0, r, g, b, a)
        o = put(o, x2, y2, u1, region.v1, r, g, b, a)
        put(o, x3, y3, u0, region.v1, r, g, b, a)
        quads++
    }

    /** Adds a quad with arbitrary per-corner texture coordinates (used for ribbons). */
    fun addUv(
        x0: Float, y0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, u3: Float, v3: Float,
        r: Float, g: Float, b: Float, a0: Float, a1: Float,
    ) {
        if (quads >= capacity) return
        var o = quads * 6 * FLOATS_PER_VERTEX
        o = put(o, x0, y0, u0, v0, r * a0, g * a0, b * a0, a0)
        o = put(o, x1, y1, u1, v1, r * a0, g * a0, b * a0, a0)
        o = put(o, x2, y2, u2, v2, r * a1, g * a1, b * a1, a1)
        o = put(o, x0, y0, u0, v0, r * a0, g * a0, b * a0, a0)
        o = put(o, x2, y2, u2, v2, r * a1, g * a1, b * a1, a1)
        put(o, x3, y3, u3, v3, r * a1, g * a1, b * a1, a1)
        quads++
    }

    private fun put(o: Int, x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float): Int {
        data[o] = x; data[o + 1] = y; data[o + 2] = u; data[o + 3] = v
        data[o + 4] = r; data[o + 5] = g; data[o + 6] = b; data[o + 7] = a
        return o + FLOATS_PER_VERTEX
    }

    companion object {
        const val FLOATS_PER_VERTEX = 8
    }
}

/**
 * Builds the sprite/particle geometry of the current lens for every visible face, from the weak-perspective head
 * pose (`PoseFit.affine`: canonical cm → frame space). Canonical anchors make the art follow yaw, pitch, roll and
 * scale, and flip naturally for the mirrored selfie view. Particles live in frame space (they fly off the face).
 */
class LensScene(maxQuads: Int = 320) {

    val batch = SpriteBatch(maxQuads)
    private val particles = Particles(PARTICLE_CAPACITY)
    private val rainbowLength = FloatArray(MAX_FACES)
    private val emitCarry = FloatArray(MAX_FACES)
    private val mouthOpen = BooleanArray(MAX_FACES)
    private var rng = 0x2F6E2B1

    /** Whether any rainbow is currently open (for status/hints). */
    val triggered: Boolean get() = mouthOpen.any { it }

    fun reset() {
        batch.clear()
        particles.clear()
        rainbowLength.fill(0f)
        emitCarry.fill(0f)
        mouthOpen.fill(false)
    }

    fun begin() = batch.clear()

    /**
     * Adds [lens] geometry for one face. [affine] is the row-major 2×4 pose map, [landmarks] the stabilized frame-space
     * mesh, [jawOpen] the smoothed blendshape, [presence] the fade weight, [maxParticles] the quality budget.
     */
    fun addFace(
        lens: Lens, slot: Int, affine: FloatArray, aspect: Float, landmarks: FloatArray, jawOpen: Float,
        presence: Float, timeSec: Float, dtSec: Float, maxParticles: Int,
    ) {
        if (presence <= 0f) return
        when (lens) {
            Lens.SUNGLASSES -> {
                // Lens plane just in front of the eyes, 16 × 5.5 cm.
                quad(affine, aspect, -8.0f, 5.5f, 5.3f, 8.0f, 5.5f, 5.3f, 8.0f, -0.1f, 5.3f, -8.0f, -0.1f, 5.3f,
                    SpriteAtlasLayout.SUNGLASSES, presence, flipU = false)
            }
            Lens.CROWN -> {
                // Sits on the head, leaning back.
                quad(affine, aspect, -6.3f, 16.4f, 0.4f, 6.3f, 16.4f, 0.4f, 6.3f, 9.4f, 2.9f, -6.3f, 9.4f, 2.9f,
                    SpriteAtlasLayout.CROWN, presence, flipU = false)
                halo(affine, aspect, timeSec, presence, count = 6, centerY = 14.5f, radius = 7.5f, size = 1.1f)
            }
            Lens.CAT -> {
                ear(affine, aspect, side = 1f, presence = presence)
                ear(affine, aspect, side = -1f, presence = presence)
                quad(affine, aspect, -6.6f, 1.0f, 7.9f, 6.6f, 1.0f, 7.9f, 6.6f, -3.5f, 7.4f, -6.6f, -3.5f, 7.4f,
                    SpriteAtlasLayout.CAT_FACE, presence, flipU = false)
            }
            Lens.SPARKLES -> {
                halo(affine, aspect, timeSec, presence, count = 16, centerY = 11.2f, radius = 9.2f, size = 1.5f)
                twinkles(affine, aspect, timeSec, presence)
            }
            Lens.RAINBOW -> rainbow(slot, affine, aspect, landmarks, jawOpen, presence, timeSec, dtSec, maxParticles)
            else -> Unit
        }
    }

    /** Advances and draws free particles (call once per frame after the faces). */
    fun finish(dtSec: Float) {
        particles.step(dtSec)
        particles.draw(batch)
    }

    val hasParticles: Boolean get() = particles.count > 0

    // ------------------------------------------------------------------------------------------------ elements

    private fun ear(affine: FloatArray, aspect: Float, side: Float, presence: Float) {
        // Base centre on the head, tilted outwards by ~18°; 5.4 × 6.4 cm.
        val bx = side * 4.7f; val by = 9.2f; val bz = 1.6f
        val tilt = 0.32f
        val ux = side * sin(tilt); val uy = cos(tilt)
        val rx = cos(tilt) * side; val ry = -sin(tilt) * side
        val hw = 2.7f; val h = 6.4f
        // Corners: top-left, top-right, bottom-right, bottom-left in the ear's own frame (mirrored for the right ear).
        quad(
            affine, aspect,
            bx - rx * hw + ux * h, by - ry * hw + uy * h, bz - 0.6f,
            bx + rx * hw + ux * h, by + ry * hw + uy * h, bz - 0.6f,
            bx + rx * hw, by + ry * hw, bz,
            bx - rx * hw, by - ry * hw, bz,
            SpriteAtlasLayout.CAT_EAR, presence, flipU = side < 0f,
        )
    }

    /** Stars orbiting the head on a tilted ring; the far side is dimmer and smaller (depth cue). */
    private fun halo(affine: FloatArray, aspect: Float, t: Float, presence: Float, count: Int, centerY: Float, radius: Float, size: Float) {
        for (k in 0 until count) {
            val theta = t * 0.85f + k * (2f * PI.toFloat() / count)
            val cx = radius * cos(theta)
            val cz = -1.0f + radius * 0.55f * sin(theta)
            val cy = centerY + 0.9f * sin(theta * 2f + k)
            val front = smoothstep(-0.7f, 0.4f, sin(theta))
            val twinkle = 0.6f + 0.4f * sin(t * 4.1f + k * 1.7f)
            val s = size * (0.75f + 0.35f * front) * (0.85f + 0.15f * twinkle)
            val a = presence * (0.25f + 0.75f * front) * twinkle
            val region = if (k % 3 == 0) SpriteAtlasLayout.SPARKLE else SpriteAtlasLayout.STAR
            val tint = HALO_TINTS[k % HALO_TINTS.size]
            billboardCanonical(affine, aspect, cx, cy, cz, s, t * 0.8f + k, region, tint, a, additive = k % 3 == 0)
        }
    }

    private fun twinkles(affine: FloatArray, aspect: Float, t: Float, presence: Float) {
        for (k in TWINKLE_POINTS.indices step 3) {
            val phase = (t * 1.6f + k * 0.37f) % 2f
            val a = presence * smoothBump(phase)
            if (a <= 0.01f) continue
            billboardCanonical(
                affine, aspect, TWINKLE_POINTS[k], TWINKLE_POINTS[k + 1], TWINKLE_POINTS[k + 2], 1.2f * (0.6f + 0.4f * a), t + k,
                SpriteAtlasLayout.SPARKLE, WHITE, a, additive = true,
            )
        }
    }

    private fun rainbow(
        slot: Int, affine: FloatArray, aspect: Float, lm: FloatArray, jawOpen: Float, presence: Float,
        t: Float, dt: Float, maxParticles: Int,
    ) {
        val s = slot.coerceIn(0, MAX_FACES - 1)
        // Hysteresis so the trigger does not flicker around the threshold.
        mouthOpen[s] = if (mouthOpen[s]) jawOpen > JAW_OFF else jawOpen > JAW_ON
        val target = if (mouthOpen[s]) ((jawOpen - JAW_OFF) / (1f - JAW_OFF)).coerceIn(0.35f, 1f) else 0f
        val rate = if (target > rainbowLength[s]) 3.2f else 2.2f
        rainbowLength[s] += (target - rainbowLength[s]) * (1f - kotlin.math.exp(-rate * dt))
        val len = rainbowLength[s]
        if (len < 0.02f) return

        // Mouth centre and width from the tracked landmarks (frame space).
        val ux = lm[FaceLandmarkIndex.UPPER_LIP_INNER * 3]; val uy = lm[FaceLandmarkIndex.UPPER_LIP_INNER * 3 + 1]
        val lx = lm[FaceLandmarkIndex.LOWER_LIP_INNER * 3]; val ly = lm[FaceLandmarkIndex.LOWER_LIP_INNER * 3 + 1]
        val mx = (ux + lx) * 0.5f; val my = (uy + ly) * 0.5f
        val ax = lm[FaceLandmarkIndex.MOUTH_LEFT * 3] - lm[FaceLandmarkIndex.MOUTH_RIGHT * 3]
        val ay = lm[FaceLandmarkIndex.MOUTH_LEFT * 3 + 1] - lm[FaceLandmarkIndex.MOUTH_RIGHT * 3 + 1]
        val mouthW = sqrt(ax * ax + ay * ay)
        // Flow direction: canonical "down and forward", projected (linear part of the pose).
        var fx = affine[1] * -1f + affine[2] * 0.55f
        var fy = affine[5] * -1f + affine[6] * 0.55f
        val fl = sqrt(fx * fx + fy * fy).coerceAtLeast(1e-6f)
        fx /= fl; fy /= fl
        val nx = -fy; val ny = fx
        val scale = sqrt(affine[0] * affine[0] + affine[1] * affine[1] + affine[2] * affine[2])
        val total = scale * 26f * len
        val segments = 10
        val wave = 0.06f
        var px0 = 0f; var py0 = 0f; var hw0 = 0f
        for (k in 0..segments) {
            val f = k / segments.toFloat()
            val along = total * f
            val sway = sin(t * 5f - f * 6f) * wave * along
            val cx = mx + fx * along + nx * sway
            val cy = my + fy * along + ny * sway
            val hw = mouthW * (0.42f + 0.9f * f)
            if (k > 0) {
                val a0 = presence * (1f - (f - 1f / segments) * 0.85f)
                val a1 = presence * (1f - f * 0.85f)
                val v0 = SpriteAtlasLayout.RAINBOW.v0 + 0.1f * (SpriteAtlasLayout.RAINBOW.v1 - SpriteAtlasLayout.RAINBOW.v0)
                val v1 = SpriteAtlasLayout.RAINBOW.v1 - 0.1f * (SpriteAtlasLayout.RAINBOW.v1 - SpriteAtlasLayout.RAINBOW.v0)
                val u0 = SpriteAtlasLayout.RAINBOW.u0; val u1 = SpriteAtlasLayout.RAINBOW.u1
                batch.addUv(
                    ndcX(px0 - nx * hw0, aspect), ndcY(py0 - ny * hw0), u0, v0,
                    ndcX(px0 + nx * hw0, aspect), ndcY(py0 + ny * hw0), u1, v0,
                    ndcX(cx + nx * hw, aspect), ndcY(cy + ny * hw), u1, v1,
                    ndcX(cx - nx * hw, aspect), ndcY(cy - ny * hw), u0, v1,
                    1f, 1f, 1f, a0, a1,
                )
            }
            px0 = cx; py0 = cy; hw0 = hw
        }
        // Sparkles streaming out of the mouth while it is open.
        if (mouthOpen[s] && maxParticles > 0) {
            emitCarry[s] += dt * 46f * len
            while (emitCarry[s] >= 1f) {
                emitCarry[s] -= 1f
                if (particles.count >= maxParticles) break
                val spread = (rand() - 0.5f) * 0.9f
                val speed = scale * (14f + 16f * rand())
                val vx = (fx + nx * spread) * speed
                val vy = (fy + ny * spread) * speed
                val hue = rand()
                particles.spawn(
                    mx + nx * (rand() - 0.5f) * mouthW, my + ny * (rand() - 0.5f) * mouthW, vx, vy,
                    life = 0.9f + 0.7f * rand(), size = scale * (0.7f + 0.9f * rand()), spin = (rand() - 0.5f) * 8f,
                    hue = hue, kind = if (rand() < 0.35f) 1 else 0, aspect = aspect, gravity = scale * 18f,
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ projection

    private fun quad(
        a: FloatArray, aspect: Float,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
        region: SpriteAtlasLayout.Region, presence: Float, flipU: Boolean,
    ) {
        batch.add(
            ndcX(px(a, x0, y0, z0), aspect), ndcY(py(a, x0, y0, z0)),
            ndcX(px(a, x1, y1, z1), aspect), ndcY(py(a, x1, y1, z1)),
            ndcX(px(a, x2, y2, z2), aspect), ndcY(py(a, x2, y2, z2)),
            ndcX(px(a, x3, y3, z3), aspect), ndcY(py(a, x3, y3, z3)),
            region, presence, presence, presence, presence, flipU,
        )
    }

    private fun billboardCanonical(
        a: FloatArray, aspect: Float, x: Float, y: Float, z: Float, sizeCm: Float, rot: Float,
        region: SpriteAtlasLayout.Region, tint: FloatArray, alpha: Float, additive: Boolean,
    ) {
        val scale = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        billboard(batch, px(a, x, y, z), py(a, x, y, z), sizeCm * scale * 0.5f, rot, region, tint, alpha, additive, aspect)
    }

    private fun rand(): Float {
        rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5)
        return (rng ushr 8) / 16777216f
    }

    companion object {
        const val MAX_FACES = 2
        const val PARTICLE_CAPACITY = 160
        const val JAW_ON = 0.32f
        const val JAW_OFF = 0.2f

        private val WHITE = floatArrayOf(1f, 1f, 1f)
        private val HALO_TINTS = arrayOf(
            floatArrayOf(1f, 0.95f, 0.75f), floatArrayOf(1f, 0.8f, 0.95f), floatArrayOf(0.8f, 0.92f, 1f), floatArrayOf(1f, 1f, 1f),
        )

        /** Canonical points around the face where sparkles twinkle (x, y, z triplets). */
        private val TWINKLE_POINTS = floatArrayOf(
            -8.8f, 6.5f, 2f, 9.2f, 5.0f, 2f, -9.8f, -1.5f, 0f, 10.0f, 0.5f, 0f, -7.5f, -7.5f, 2f, 7.8f, -8.0f, 2f,
            -3.5f, 12.5f, 3f, 4.2f, 13.0f, 3f, 0f, 14.5f, 2f, -11f, 3f, -1f, 11f, -3f, -1f,
        )

        fun px(a: FloatArray, x: Float, y: Float, z: Float) = a[0] * x + a[1] * y + a[2] * z + a[3]
        fun py(a: FloatArray, x: Float, y: Float, z: Float) = a[4] * x + a[5] * y + a[6] * z + a[7]

        /** Frame space (x ∈ [0, aspect], y down) → NDC. */
        fun ndcX(x: Float, aspect: Float) = x / aspect * 2f - 1f
        fun ndcY(y: Float) = 1f - 2f * y

        /** 0 → 1 → 0 over phase ∈ [0, 2) with a quick rise. */
        private fun smoothBump(phase: Float): Float = if (phase > 1f) 0f else sin(phase * PI.toFloat()).coerceAtLeast(0f)

        internal fun billboard(
            batch: SpriteBatch, cx: Float, cy: Float, half: Float, rot: Float, region: SpriteAtlasLayout.Region,
            tint: FloatArray, alpha: Float, additive: Boolean, aspect: Float,
        ) {
            val c = cos(rot) * half; val s = sin(rot) * half
            // Corners (frame space, y down): TL, TR, BR, BL rotated by rot.
            val x0 = cx - c + s; val y0 = cy - s - c
            val x1 = cx + c + s; val y1 = cy + s - c
            val x2 = cx + c - s; val y2 = cy + s + c
            val x3 = cx - c - s; val y3 = cy - s + c
            batch.add(
                ndcX(x0, aspect), ndcY(y0), ndcX(x1, aspect), ndcY(y1), ndcX(x2, aspect), ndcY(y2), ndcX(x3, aspect), ndcY(y3),
                region, tint[0] * alpha, tint[1] * alpha, tint[2] * alpha, if (additive) 0f else alpha,
            )
        }
    }
}

/** A small preallocated particle pool in frame space (struct-of-arrays, swap-remove). */
internal class Particles(private val capacity: Int) {
    private val x = FloatArray(capacity); private val y = FloatArray(capacity)
    private val vx = FloatArray(capacity); private val vy = FloatArray(capacity)
    private val age = FloatArray(capacity); private val life = FloatArray(capacity)
    private val size = FloatArray(capacity); private val rot = FloatArray(capacity); private val spin = FloatArray(capacity)
    private val r = FloatArray(capacity); private val g = FloatArray(capacity); private val b = FloatArray(capacity)
    private val kind = IntArray(capacity); private val gravity = FloatArray(capacity)
    private var aspect = 1f
    var count = 0; private set

    fun clear() { count = 0 }

    fun spawn(px: Float, py: Float, pvx: Float, pvy: Float, life: Float, size: Float, spin: Float, hue: Float, kind: Int, aspect: Float, gravity: Float) {
        if (count >= capacity) return
        val i = count++
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy
        age[i] = 0f; this.life[i] = life; this.size[i] = size; rot[i] = 0f; this.spin[i] = spin
        hueToRgb(hue, i)
        this.kind[i] = kind
        this.gravity[i] = gravity
        this.aspect = aspect
    }

    fun step(dt: Float) {
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                val last = --count
                if (i != last) copy(last, i)
                continue
            }
            vy[i] += gravity[i] * dt
            vx[i] *= 1f - 0.6f * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            rot[i] += spin[i] * dt
            i++
        }
    }

    fun draw(batch: SpriteBatch) {
        for (i in 0 until count) {
            val t = age[i] / life[i]
            val fade = (1f - t) * smoothstep(0f, 0.08f, t)
            val region = if (kind[i] == 1) SpriteAtlasLayout.HEART else SpriteAtlasLayout.STAR
            tint[0] = r[i]; tint[1] = g[i]; tint[2] = b[i]
            LensScene.billboard(batch, x[i], y[i], size[i] * 0.5f * (0.7f + 0.3f * (1f - t)), rot[i], region, tint, fade, additive = false, aspect = aspect)
        }
    }

    private val tint = FloatArray(3)

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; vx[to] = vx[from]; vy[to] = vy[from]
        age[to] = age[from]; life[to] = life[from]; size[to] = size[from]; rot[to] = rot[from]; spin[to] = spin[from]
        r[to] = r[from]; g[to] = g[from]; b[to] = b[from]; kind[to] = kind[from]; gravity[to] = gravity[from]
    }

    private fun hueToRgb(h: Float, i: Int) {
        val k = (h % 1f) * 6f
        fun ch(n: Float): Float {
            val t = (n + k) % 6f
            val saturated = 1f - max(0f, minOf(t, 4f - t, 1f))
            return 0.35f + 0.65f * saturated
        }
        // Pastel rainbow: soft, bright hues.
        r[i] = ch(5f); g[i] = ch(3f); b[i] = ch(1f)
    }
}
