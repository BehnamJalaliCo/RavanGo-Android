package com.ravango.engine.beauty.gl

import com.ravango.engine.beauty.effects.EffectsShaders
import com.ravango.engine.beauty.quality.QualityProfile
import com.ravango.engine.beauty.BeautyQuality

/**
 * Every GL program the beauty/effects pipeline can build, with the name it is reported under. Used by the JVM
 * shader lint test and by the instrumented test that compiles and links each one on a real GL driver.
 */
internal object ShaderCatalog {

    data class Program(val name: String, val vertex: String, val fragment: String)

    /** Bilateral radii the quality levels use (the processor clamps to 1..6). */
    val bilateralRadii: List<Int> = (1..6).toList()

    /** Bokeh tap counts of every quality level. */
    val bokehTaps: List<Int> = BeautyQuality.entries.map { QualityProfile.of(it).bokehTaps }.distinct()

    fun all(): List<Program> = buildList {
        add(Program("beauty.downsample", BeautyShaders.VERTEX, BeautyShaders.DOWNSAMPLE))
        add(Program("beauty.copy", BeautyShaders.VERTEX, BeautyShaders.COPY))
        add(Program("beauty.gaussian", BeautyShaders.VERTEX, BeautyShaders.GAUSSIAN))
        add(Program("beauty.skinMask", BeautyShaders.VERTEX, BeautyShaders.SKIN_MASK))
        add(Program("beauty.composite", BeautyShaders.VERTEX, BeautyShaders.COMPOSITE))
        add(Program("mesh.maskRegions", BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_REGIONS))
        add(Program("mesh.maskSolid", BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_SOLID))
        add(Program("mesh.makeup", BeautyShaders.MESH_VERTEX, BeautyShaders.MAKEUP))
        add(Program("mesh.warp", BeautyShaders.WARP_VERTEX, BeautyShaders.WARP_FRAGMENT))
        add(Program("effects.final", BeautyShaders.VERTEX, EffectsShaders.FINAL))
        add(Program("effects.maskRefine", BeautyShaders.VERTEX, EffectsShaders.MASK_REFINE))
        add(Program("effects.bokehPrep", BeautyShaders.VERTEX, EffectsShaders.BOKEH_PREP))
        add(Program("effects.paint", BeautyShaders.MESH_VERTEX, EffectsShaders.PAINT))
        add(Program("effects.sprite", EffectsShaders.SPRITE_VERTEX, EffectsShaders.SPRITE_FRAGMENT))
        bilateralRadii.forEach { add(Program("beauty.bilateral$it", BeautyShaders.VERTEX, BeautyShaders.bilateral(it))) }
        bokehTaps.forEach { add(Program("effects.bokeh$it", BeautyShaders.VERTEX, EffectsShaders.bokeh(it))) }
    }
}
