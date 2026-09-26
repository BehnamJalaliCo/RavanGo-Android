package com.ravango.engine.camera.session

import com.ravango.engine.camera.capability.SensorArea
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle in sensor active-array coordinates (right/bottom exclusive). */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Maps points from the camera buffer to sensor coordinates for 3A regions, and computes zoom crop regions.
 * Pure logic, unit tested.
 */
object MeteringMath {

    /** Centered SCALER_CROP_REGION for a digital zoom factor (pre-API 30 zoom). */
    fun cropRegionForZoom(active: SensorArea, zoom: Float): SensorRect {
        val z = max(1f, zoom)
        val w = (active.width / z).roundToInt()
        val h = (active.height / z).roundToInt()
        val left = active.left + (active.width - w) / 2
        val top = active.top + (active.height - h) / 2
        return SensorRect(left, top, left + w, top + h)
    }

    /**
     * The part of the sensor that the stream actually sees: the zoom field of view (the full active array when
     * zoom uses CONTROL_ZOOM_RATIO, whose 3A coordinates are post-zoom; else the crop region), then center-cropped
     * by the HAL to the stream's aspect ratio.
     */
    fun streamFieldOfView(active: SensorArea, cropRegion: SensorRect?, streamAspect: Float): SensorRect {
        val base = cropRegion ?: SensorRect(active.left, active.top, active.left + active.width, active.top + active.height)
        val baseAspect = base.width.toFloat() / base.height
        return if (baseAspect > streamAspect) {
            val w = (base.height * streamAspect).roundToInt()
            val left = base.left + (base.width - w) / 2
            SensorRect(left, base.top, left + w, base.bottom)
        } else {
            val h = (base.width / streamAspect).roundToInt()
            val top = base.top + (base.height - h) / 2
            SensorRect(base.left, top, base.right, top + h)
        }
    }

    /**
     * A metering rectangle centered on the normalized buffer point ([bufferX], [bufferY]) inside [fov],
     * sized [sizeFraction] of the smaller FOV edge and clamped to the active array.
     */
    fun meteringRect(bufferX: Float, bufferY: Float, fov: SensorRect, active: SensorArea, sizeFraction: Float = 0.12f): SensorRect {
        val cx = fov.left + bufferX.coerceIn(0f, 1f) * fov.width
        val cy = fov.top + bufferY.coerceIn(0f, 1f) * fov.height
        val half = max(8f, min(fov.width, fov.height) * sizeFraction / 2f)
        val minX = active.left
        val minY = active.top
        val maxX = active.left + active.width - 1
        val maxY = active.top + active.height - 1
        var left = (cx - half).roundToInt()
        var top = (cy - half).roundToInt()
        var right = (cx + half).roundToInt()
        var bottom = (cy + half).roundToInt()
        // Shift inside the array instead of shrinking, so the region keeps its size near the edges.
        if (left < minX) { right += minX - left; left = minX }
        if (top < minY) { bottom += minY - top; top = minY }
        if (right > maxX) { left -= right - maxX; right = maxX }
        if (bottom > maxY) { top -= bottom - maxY; bottom = maxY }
        return SensorRect(max(minX, left), max(minY, top), min(maxX, right), min(maxY, bottom))
    }
}
