package com.ravango.feature.home.common

import com.ravango.core.model.AspectRatioSpec
import kotlin.math.abs
import kotlin.math.ln

/**
 * Picks the canvas preset closest to a media item's displayed size (after rotation).
 * Distance is measured in log-ratio space so 9:16 vs 16:9 are symmetric and small crops snap sensibly.
 * Unknown sizes (0 or negative) fall back to vertical 9:16, the most common creator format.
 */
fun inferAspectRatio(
    displayWidth: Int,
    displayHeight: Int,
    candidates: List<AspectRatioSpec> = AspectRatioSpec.Presets,
    fallback: AspectRatioSpec = AspectRatioSpec.Portrait9x16,
): AspectRatioSpec {
    if (displayWidth <= 0 || displayHeight <= 0 || candidates.isEmpty()) return fallback
    val target = ln(displayWidth.toDouble() / displayHeight.toDouble())
    return candidates.minBy { abs(ln(it.ratio.toDouble()) - target) }
}
