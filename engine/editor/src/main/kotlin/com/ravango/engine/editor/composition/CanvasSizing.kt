package com.ravango.engine.editor.composition

import com.ravango.core.model.AspectRatioSpec
import kotlin.math.roundToInt

/** Output frame size for a canvas aspect ratio at a given short side. Dimensions are even (encoder requirement). */
object CanvasSizing {
    /** Resolution used for the interactive preview; exports use the chosen export resolution. */
    const val PREVIEW_SHORT_SIDE = 720

    fun size(aspect: AspectRatioSpec, shortSide: Int): Pair<Int, Int> {
        val ratio = aspect.ratio.takeIf { it.isFinite() && it > 0f } ?: (9f / 16f)
        val s = even(shortSide.coerceAtLeast(16))
        return if (ratio <= 1f) {
            s to even((s / ratio).roundToInt())
        } else {
            even((s * ratio).roundToInt()) to s
        }
    }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
}
