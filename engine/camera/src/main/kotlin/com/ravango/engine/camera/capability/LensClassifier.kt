package com.ravango.engine.camera.capability

import com.ravango.core.model.LensFacing
import kotlin.math.abs
import kotlin.math.sqrt

/** Optical description of one public camera id, used to build lens chips. */
data class CameraOptics(
    val cameraId: String,
    val facing: LensFacing,
    /** 35mm-equivalent focal length; null when the sensor size is unknown. */
    val equivalentFocalMm: Float?,
    val isLogicalMultiCamera: Boolean,
    /** Equivalent focal lengths of the physical sensors behind a logical camera. */
    val physicalEquivalentFocalMm: List<Float>,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val zoomRatioApi: Boolean,
)

/** Builds the lens chips ("0.5×", "1×", "3×") that exist on the device. Pure logic. */
object LensClassifier {

    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    /** 35mm-equivalent focal length from the real focal length and the sensor's physical size. */
    fun equivalentFocal(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float? {
        val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        if (diagonal <= 0f || focalMm <= 0f) return null
        return focalMm * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    fun kindFor(factor: Float): LensKind = when {
        factor < 0.9f -> LensKind.ULTRA_WIDE
        factor > 1.15f -> LensKind.TELE
        else -> LensKind.WIDE
    }

    /**
     * [cameras] must be in CameraManager id order: the first camera of each facing is the main camera
     * (the platform convention that every app relies on).
     */
    fun build(cameras: List<CameraOptics>): Map<LensFacing, List<LensOption>> {
        val result = LinkedHashMap<LensFacing, List<LensOption>>()
        for (facing in cameras.map { it.facing }.distinct()) {
            val sameFacing = cameras.filter { it.facing == facing }
            val main = sameFacing.first()
            val options = ArrayList<LensOption>()
            options += LensOption("${main.cameraId}@1", main.cameraId, facing, LensKind.WIDE, 1f, 1f)

            // Logical multi-camera with CONTROL_ZOOM_RATIO: the HAL switches sensors for us.
            if (main.zoomRatioApi) {
                val minZoom = main.zoomRange.start
                if (minZoom < 0.95f) {
                    options += LensOption("${main.cameraId}@$minZoom", main.cameraId, facing, LensKind.ULTRA_WIDE, minZoom, minZoom)
                }
                val mainEq = main.equivalentFocalMm
                if (mainEq != null && main.isLogicalMultiCamera) {
                    main.physicalEquivalentFocalMm
                        .map { it / mainEq }
                        .filter { it > 1.4f && it <= main.zoomRange.endInclusive + 0.01f }
                        .forEach { factor -> addIfDistinct(options, LensOption("${main.cameraId}@$factor", main.cameraId, facing, LensKind.TELE, factor, factor)) }
                }
            }

            // Other public camera ids of the same facing (older multi-camera devices expose lenses this way).
            val mainEq = main.equivalentFocalMm
            if (mainEq != null) {
                for (other in sameFacing.drop(1)) {
                    val eq = other.equivalentFocalMm ?: continue
                    val factor = eq / mainEq
                    val kind = kindFor(factor)
                    if (kind == LensKind.WIDE) continue
                    addIfDistinct(options, LensOption("${other.cameraId}@1", other.cameraId, facing, kind, factor, 1f))
                }
            }
            result[facing] = options.sortedBy { it.factor }
        }
        return result
    }

    private fun addIfDistinct(options: MutableList<LensOption>, option: LensOption) {
        if (options.none { abs(it.factor - option.factor) < 0.2f }) options += option
    }
}
