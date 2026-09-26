package com.ravango.engine.beauty.tracking

import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import kotlin.math.sqrt

/**
 * One face-landmarker result in frame space (isotropic, normalized by frame height, origin top-left, y down:
 * `x ∈ [0, aspect]`, `y ∈ [0, 1]`; z uses the same unit as x, smaller = closer to the camera).
 * Preallocated for [MAX_FACES] and reused (the GL thread never allocates).
 */
class DetectionFrame {
    var faceCount = 0
    var timestampNs = 0L
    val landmarks = Array(MAX_FACES) { FloatArray(FaceLandmarkIndex.LANDMARK_COUNT * 3) }
    val blendshapes = Array(MAX_FACES) { FloatArray(BLENDSHAPE_COUNT) }
    val hasBlendshapes = BooleanArray(MAX_FACES)
    /** Column-major 4×4 facial transformation matrices (canonical model → metric camera space). */
    val matrices = Array(MAX_FACES) { FloatArray(16) }
    val hasMatrix = BooleanArray(MAX_FACES)

    fun copyFrom(o: DetectionFrame) {
        faceCount = o.faceCount
        timestampNs = o.timestampNs
        for (f in 0 until MAX_FACES) {
            System.arraycopy(o.landmarks[f], 0, landmarks[f], 0, landmarks[f].size)
            System.arraycopy(o.blendshapes[f], 0, blendshapes[f], 0, BLENDSHAPE_COUNT)
            System.arraycopy(o.matrices[f], 0, matrices[f], 0, 16)
            hasBlendshapes[f] = o.hasBlendshapes[f]
            hasMatrix[f] = o.hasMatrix[f]
        }
    }

    companion object {
        const val MAX_FACES = 2
        const val BLENDSHAPE_COUNT = 52
    }
}

/** ARKit-style blendshape indices of the MediaPipe blendshape model that the engine uses. */
object Blendshape {
    const val EYE_BLINK_LEFT = 9
    const val EYE_BLINK_RIGHT = 10
    const val JAW_OPEN = 25
}

/** A tracked face slot: stabilized mesh, presence fade, smoothed blendshapes and pose. */
class TrackedFace {
    val stabilizer = MeshStabilizer(FaceLandmarkIndex.LANDMARK_COUNT)
    val fader = PresenceFader(FADE_SEC)
    /** Stabilized, latency-compensated landmarks for the current render frame. */
    val landmarks = FloatArray(FaceLandmarkIndex.LANDMARK_COUNT * 3)
    val blendshapes = FloatArray(DetectionFrame.BLENDSHAPE_COUNT)
    val matrix = FloatArray(16)
    var hasMatrix = false
    var lastSeenSec = Double.NEGATIVE_INFINITY
    /** Inter-ocular distance in frame units at the last measurement. */
    var scale = 0f
    var active = false

    /** Current presence weight, 0..1. */
    val presence: Float get() = fader.value

    fun reset() {
        stabilizer.reset()
        fader.reset()
        active = false
        hasMatrix = false
        blendshapes.fill(0f)
        lastSeenSec = Double.NEGATIVE_INFINITY
    }

    companion object {
        const val FADE_SEC = 0.15f
    }
}

/**
 * Multi-face tracking state (face index 0/1): assigns each detection to a slot by proximity, stabilizes it,
 * holds the last good mesh through short dropouts and fades faces in/out over ~150 ms.
 */
class FaceTracks(private val holdSec: Double = HOLD_SEC) {

    val faces = Array(DetectionFrame.MAX_FACES) { TrackedFace() }
    private val assigned = BooleanArray(DetectionFrame.MAX_FACES)
    private val slotOf = IntArray(DetectionFrame.MAX_FACES)

    /** Number of faces currently visible (presence > ½). */
    val visibleCount: Int
        get() {
            var c = 0
            for (f in faces) if (f.active && f.presence > 0.5f) c++
            return c
        }

    /** True when any face has a non-zero presence (effects must be rendered). */
    val anyPresent: Boolean
        get() {
            for (f in faces) if (f.active && f.presence > 0f) return true
            return false
        }

    fun reset() { for (f in faces) f.reset() }

    /** Integrates a detection taken at [timeSec] (frame time of the analysed image). */
    fun onDetection(det: DetectionFrame, timeSec: Double) {
        assigned.fill(false)
        val count = det.faceCount.coerceAtMost(DetectionFrame.MAX_FACES)
        // Greedy nearest assignment (2×2 at most): best pair first.
        for (k in 0 until count) slotOf[k] = -1
        repeat(count) {
            var bestD = Float.MAX_VALUE
            var bestDet = -1
            var bestSlot = -1
            for (d in 0 until count) {
                if (slotOf[d] >= 0) continue
                for (s in faces.indices) {
                    if (assigned[s] || !faces[s].active || !faces[s].stabilizer.hasEstimate) continue
                    val dist = centroidDistance(det.landmarks[d], faces[s].stabilizer.value)
                    val limit = faces[s].scale * MATCH_LIMIT_SCALES
                    if (dist < limit && dist < bestD) { bestD = dist; bestDet = d; bestSlot = s }
                }
            }
            if (bestDet >= 0) { slotOf[bestDet] = bestSlot; assigned[bestSlot] = true }
        }
        // New faces take a free slot (inactive first, else one not seen for longest).
        for (d in 0 until count) {
            if (slotOf[d] >= 0) continue
            var free = -1
            for (s in faces.indices) if (!assigned[s] && !faces[s].active) { free = s; break }
            if (free < 0) {
                var oldest = Double.MAX_VALUE
                for (s in faces.indices) if (!assigned[s] && faces[s].lastSeenSec < oldest) { oldest = faces[s].lastSeenSec; free = s }
            }
            if (free < 0) continue
            faces[free].reset()
            faces[free].active = true
            slotOf[d] = free
            assigned[free] = true
        }
        for (d in 0 until count) {
            val s = slotOf[d]
            if (s < 0) continue
            val face = faces[s]
            val lm = det.landmarks[d]
            face.scale = interocular(lm)
            face.stabilizer.update(lm, timeSec, face.scale)
            face.lastSeenSec = maxOf(face.lastSeenSec, timeSec)
            if (det.hasBlendshapes[d]) {
                val src = det.blendshapes[d]
                for (i in 0 until DetectionFrame.BLENDSHAPE_COUNT) face.blendshapes[i] += 0.6f * (src[i] - face.blendshapes[i])
            }
            face.hasMatrix = det.hasMatrix[d]
            if (face.hasMatrix) System.arraycopy(det.matrices[d], 0, face.matrix, 0, 16)
        }
    }

    /** Advances fades and writes each active face's predicted mesh for render time [nowSec]. */
    fun update(nowSec: Double, dtSec: Float) {
        for (f in faces) {
            if (!f.active) continue
            val visible = nowSec - f.lastSeenSec <= holdSec
            f.fader.update(visible, dtSec)
            if (!visible && f.presence <= 0f) {
                f.reset()
                continue
            }
            f.stabilizer.predict(nowSec, f.landmarks)
        }
    }

    companion object {
        /** A face not re-detected for this long starts fading out; its last mesh is held meanwhile. */
        const val HOLD_SEC = 0.25
        /** A detection farther than this many inter-ocular distances from a slot is a different face. */
        const val MATCH_LIMIT_SCALES = 2.5f

        fun interocular(lm: FloatArray): Float {
            val a = FaceLandmarkIndex.RIGHT_EYE_OUTER * 3
            val b = FaceLandmarkIndex.LEFT_EYE_OUTER * 3
            val dx = lm[a] - lm[b]; val dy = lm[a + 1] - lm[b + 1]
            return sqrt(dx * dx + dy * dy)
        }

        private fun centroidDistance(a: FloatArray, b: FloatArray): Float {
            // Nose tip + eye corners: cheap and stable proxy for the face centroid.
            var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
            for (i in CENTROID_POINTS) { ax += a[i * 3]; ay += a[i * 3 + 1]; bx += b[i * 3]; by += b[i * 3 + 1] }
            val dx = (ax - bx) / CENTROID_POINTS.size; val dy = (ay - by) / CENTROID_POINTS.size
            return sqrt(dx * dx + dy * dy)
        }

        private val CENTROID_POINTS = intArrayOf(
            FaceLandmarkIndex.NOSE_TIP, FaceLandmarkIndex.RIGHT_EYE_OUTER, FaceLandmarkIndex.LEFT_EYE_OUTER,
            FaceLandmarkIndex.FOREHEAD_TOP, FaceLandmarkIndex.CHIN_BOTTOM,
        )
    }
}
