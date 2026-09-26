package com.ravango.engine.editor.effects

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Single-pass color grade: exposure, white balance, brightness, contrast, tone (highlights/shadows), fade, split toning,
 * saturation, vibrance, sharpen/blur, vignette and animated film grain.
 */
data class ColorGradeEffect(val params: GradeParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = ColorGradeShaderProgram(params)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = params.isNeutral
}

private class ColorGradeShaderProgram(private val p: GradeParams) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = GlSl.program(FRAGMENT)
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatsUniform("uTone", floatArrayOf(p.exposure, p.brightness, p.contrast, p.fade))
            program.setFloatsUniform("uRange", floatArrayOf(p.highlights, p.shadows, p.temperature, p.tint))
            program.setFloatsUniform("uColor", floatArrayOf(p.saturation, p.vibrance, p.vignette, p.grain))
            program.setFloatsUniform("uDetail", floatArrayOf(p.sharpen, p.blur, (presentationTimeUs % 10_000_000L) / 1_000_000f, width.toFloat() / height))
            program.setFloatsUniform("uShadowTint", floatArrayOf(p.shadowTint.first, p.shadowTint.second, p.shadowTint.third))
            program.setFloatsUniform("uHighlightTint", floatArrayOf(p.highlightTint.first, p.highlightTint.second, p.highlightTint.third))
            GlSl.drawQuad(program)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform vec4 uTone;      // exposure, brightness, contrast, fade
uniform vec4 uRange;     // highlights, shadows, temperature, tint
uniform vec4 uColor;     // saturation, vibrance, vignette, grain
uniform vec4 uDetail;    // sharpen, blur, time (s), aspect
uniform vec3 uShadowTint;
uniform vec3 uHighlightTint;
varying vec2 vTexCoord;
${GlSl.NOISE}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
  vec4 src = texture2D(uTexSampler, vTexCoord);
  vec3 c = src.rgb;
  // Detail: blur (9 taps, radius up to ~6px) or unsharp-mask sharpen.
  if (uDetail.y > 0.001) {
    vec2 r = uTexel * (1.0 + uDetail.y * 5.0);
    vec3 acc = c;
    acc += texture2D(uTexSampler, vTexCoord + vec2(r.x, 0.0)).rgb;
    acc += texture2D(uTexSampler, vTexCoord - vec2(r.x, 0.0)).rgb;
    acc += texture2D(uTexSampler, vTexCoord + vec2(0.0, r.y)).rgb;
    acc += texture2D(uTexSampler, vTexCoord - vec2(0.0, r.y)).rgb;
    acc += texture2D(uTexSampler, vTexCoord + r).rgb;
    acc += texture2D(uTexSampler, vTexCoord - r).rgb;
    acc += texture2D(uTexSampler, vTexCoord + vec2(r.x, -r.y)).rgb;
    acc += texture2D(uTexSampler, vTexCoord + vec2(-r.x, r.y)).rgb;
    c = mix(c, acc / 9.0, clamp(uDetail.y * 1.5, 0.0, 1.0));
  } else if (uDetail.x > 0.001) {
    vec3 n = texture2D(uTexSampler, vTexCoord + vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, vTexCoord - vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexel.y)).rgb
           + texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexel.y)).rgb;
    c = c + (c - n * 0.25) * uDetail.x * 1.6;
  }
  // Exposure (±2 stops) and white balance.
  c *= pow(2.0, uTone.x * 2.0);
  c.r += uRange.z * 0.10 + uRange.w * 0.03;
  c.b -= uRange.z * 0.10 - uRange.w * 0.03;
  c.g -= uRange.w * 0.08;
  // Brightness and contrast around mid grey.
  c += uTone.y * 0.25;
  c = (c - 0.5) * (1.0 + uTone.z) + 0.5;
  // Tone ranges.
  float l = luma(clamp(c, 0.0, 1.0));
  float sMask = 1.0 - smoothstep(0.0, 0.55, l);
  float hMask = smoothstep(0.45, 1.0, l);
  c += uRange.y * 0.25 * sMask;
  c += uRange.x * 0.25 * hMask;
  // Split toning and fade (lifted blacks).
  c += uShadowTint * sMask + uHighlightTint * hMask;
  c = c * (1.0 - uTone.w * 0.22) + uTone.w * 0.14;
  // Saturation and vibrance.
  l = luma(c);
  c = mix(vec3(l), c, 1.0 + uColor.x);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float sat = clamp(mx - mn, 0.0, 1.0);
  c = mix(vec3(luma(c)), c, 1.0 + uColor.y * (1.0 - sat));
  // Vignette (aspect-corrected).
  if (uColor.z > 0.001) {
    vec2 d = (vTexCoord - 0.5) * vec2(uDetail.w, 1.0);
    float v = smoothstep(0.35, 0.95, length(d) * 1.25);
    c *= 1.0 - uColor.z * v;
  }
  // Grain, animated per frame.
  if (uColor.w > 0.001) {
    float g = rgHash(vTexCoord / uTexel + vec2(uDetail.z * 97.0, uDetail.z * 61.0)) - 0.5;
    c += g * uColor.w * 0.16;
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
    }
}
