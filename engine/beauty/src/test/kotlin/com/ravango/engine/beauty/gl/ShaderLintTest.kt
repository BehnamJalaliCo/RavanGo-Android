package com.ravango.engine.beauty.gl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ravango.engine.beauty.effects.EffectsShaders
import com.ravango.engine.render.Shaders
import org.junit.Test
import java.util.Locale

/**
 * Static GLSL ES 1.00 portability checks for every program the pipeline builds — the classic reasons a shader that
 * works on one GPU fails to compile or link on another. (The instrumented `ShaderProgramsTest` then compiles them
 * on a real driver.)
 */
class ShaderLintTest {

    private val programs = ShaderCatalog.all() + listOf(
        ShaderCatalog.Program("camera.oes", Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_OES),
        ShaderCatalog.Program("camera.copy", Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_2D),
    )

    private data class Decl(val precision: String?, val type: String, val name: String, val arraySize: Int)

    private val precisionTokens = setOf("lowp", "mediump", "highp", "TC")

    private fun code(src: String): List<String> = src.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun decls(src: String, qualifier: String): List<Decl> =
        code(src).filter { it.startsWith("$qualifier ") }.flatMap { line ->
            val parts = line.removePrefix("$qualifier ").removeSuffix(";").trim().split(Regex("\\s+"), limit = 3)
            val precision = parts[0].takeIf { it in precisionTokens }
            val rest = if (precision != null) parts.drop(1) else parts
            val type = rest[0]
            val names = rest.drop(1).joinToString(" ").split(',').map { it.trim() }
            names.map { n ->
                val size = Regex("\\[(\\w+)]").find(n)?.groupValues?.get(1)?.let { s -> s.toIntOrNull() ?: define(src, s) } ?: 1
                Decl(precision, type, n.substringBefore('['), size)
            }
        }

    private fun define(src: String, name: String): Int =
        Regex("#define\\s+$name\\s+(\\d+)").find(src)?.groupValues?.get(1)?.toInt() ?: error("unknown array size $name")

    /** Effective precision of a declaration (vertex default highp; fragment default from `precision X float`). */
    private fun effective(d: Decl, src: String, vertex: Boolean): String {
        val p = d.precision
        if (p == "TC") return "highp" // TC is highp when the fragment stage supports it
        if (p != null) return p
        if (d.type.startsWith("sampler")) return "lowp"
        if (vertex) return "highp"
        val floatDefault = Regex("precision\\s+(lowp|mediump|highp)\\s+float").find(src)?.groupValues?.get(1)
        return if (d.type.startsWith("int") || d.type.startsWith("ivec") || d.type == "bool") "mediump" else floatDefault ?: "none"
    }

    @Test
    fun `fragment shaders declare a default float precision`() {
        for (p in programs) {
            assertWithMessage(p.name).that(p.fragment).containsMatch("precision\\s+(mediump|highp)\\s+float")
        }
    }

    @Test
    fun `extension directives come first and no GLSL ES 3 syntax is used`() {
        for (p in programs) {
            for ((stage, src) in listOf("vertex" to p.vertex, "fragment" to p.fragment)) {
                val lines = code(src)
                val ext = lines.indexOfFirst { it.startsWith("#extension") }
                if (ext >= 0) {
                    assertWithMessage("${p.name} $stage: #extension after code").that(lines.take(ext).all { it.startsWith("#") }).isTrue()
                }
                assertWithMessage("${p.name} $stage").that(src).doesNotContainMatch("\\btexture\\s*\\(")
                assertWithMessage("${p.name} $stage").that(src).doesNotContainMatch("#version\\s+3")
                assertWithMessage("${p.name} $stage").that(src).doesNotContainMatch("\\blayout\\s*\\(")
                assertWithMessage("${p.name} $stage").that(src).doesNotContainMatch("(?m)^\\s*(in|out)\\s+\\w+\\s+\\w+\\s*;")
            }
        }
    }

    @Test
    fun `TC precision macro is defined wherever it is used`() {
        for (p in programs) {
            for (src in listOf(p.vertex, p.fragment)) {
                if (Regex("\\bTC\\b").containsMatchIn(src.replace("#define TC", ""))) {
                    assertWithMessage(p.name).that(src).contains("#define TC highp")
                }
            }
        }
    }

    @Test
    fun `every fragment varying is written by the vertex shader with the same type`() {
        for (p in programs) {
            val out = decls(p.vertex, "varying").associateBy { it.name }
            for (v in decls(p.fragment, "varying")) {
                val w = out[v.name]
                assertWithMessage("${p.name}: varying ${v.name} missing in vertex shader").that(w).isNotNull()
                assertWithMessage("${p.name}: varying ${v.name} type").that(w!!.type).isEqualTo(v.type)
            }
        }
    }

    @Test
    fun `uniforms shared by both stages have the same precision (else the link fails)`() {
        for (p in programs) {
            val vertex = decls(p.vertex, "uniform").associateBy { it.name }
            for (f in decls(p.fragment, "uniform")) {
                val v = vertex[f.name] ?: continue
                assertWithMessage("${p.name}: uniform ${f.name} type").that(v.type).isEqualTo(f.type)
                assertWithMessage("${p.name}: uniform ${f.name} precision")
                    .that(effective(f, p.fragment, vertex = false)).isEqualTo(effective(v, p.vertex, vertex = true))
            }
        }
    }

    @Test
    fun `programs fit GLES 2 minimum limits`() {
        for (p in programs) {
            val all = (decls(p.vertex, "uniform") + decls(p.fragment, "uniform")).distinctBy { it.name }
            val samplers = all.filter { it.type.startsWith("sampler") }.sumOf { it.arraySize }
            // GL_MAX_TEXTURE_IMAGE_UNITS is at least 8 on GLES 2.
            assertWithMessage("${p.name}: samplers").that(samplers).isAtMost(8)
            val fragVectors = decls(p.fragment, "uniform").filterNot { it.type.startsWith("sampler") }.sumOf { d ->
                val rows = if (d.type.startsWith("mat")) d.type.last().digitToInt() else 1
                rows * d.arraySize
            }
            // GLES 2 guarantees only 16 fragment uniform vectors; stay well inside what every real GPU offers.
            assertWithMessage("${p.name}: fragment uniform vectors").that(fragVectors).isAtMost(32)
        }
    }

    @Test
    fun `loops have constant bounds`() {
        val loop = Regex("for\\s*\\(\\s*int\\s+(\\w+)\\s*=\\s*([-\\w.]+)\\s*;\\s*\\w+\\s*(<=|<|>=|>)\\s*([-\\w.]+)")
        for (p in programs) {
            for (src in listOf(p.vertex, p.fragment)) {
                for (m in loop.findAll(src)) {
                    val bound = m.groupValues[4]
                    val constant = bound.toIntOrNull() != null || Regex("#define\\s+$bound\\s").containsMatchIn(src)
                    assertWithMessage("${p.name}: loop bound $bound").that(constant).isTrue()
                }
            }
        }
    }

    @Test
    fun `generated shader sources are ASCII even with a Persian default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            for (src in ShaderCatalog.all().flatMap { listOf(it.vertex, it.fragment) } + EffectsShaders.bokeh(24)) {
                assertThat(src.all { it.code < 128 || it == '·' }).isTrue()
            }
            assertThat(EffectsShaders.glsl(0.5f)).isEqualTo("0.500000")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `segmentation mask is sampled flipped to match the top-down readback`() {
        assertThat(EffectsShaders.MASK_REFINE).contains("vec2(vTexCoord.x, 1.0 - vTexCoord.y)")
    }

    @Test
    fun `bokeh has no uniform array`() {
        for (taps in ShaderCatalog.bokehTaps) {
            val src = EffectsShaders.bokeh(taps)
            assertThat(src).doesNotContain("uTaps")
            assertThat(Regex("sum \\+= texture2D").findAll(src).count()).isEqualTo(taps)
        }
    }
}
