package com.ravango.engine.editor.effects

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ravango.core.model.TransitionType
import com.ravango.engine.editor.composition.TransitionTiming
import com.ravango.engine.editor.composition.TransitionWindow

/**
 * Composition-level effect that renders clip-edge transitions and clip fades on the output timeline.
 *
 * The main storyline is a single sequence (clips never overlap), so transitions are "through" transitions: the
 * outgoing clip animates into the cut (amount 0 → 1) and the incoming clip animates out of it (1 → 0) with the same
 * treatment mirrored — fade through black/white, zoom punches, whip-pan slides with motion blur, blur and spin.
 * Presentation times received here are output-timeline times.
 */
data class TransitionEffect(val windows: List<TransitionWindow>) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = TransitionShaderProgram(windows)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = windows.isEmpty()
}

private class TransitionShaderProgram(private val windows: List<TransitionWindow>) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = GlSl.program(FRAGMENT)
    private var aspect = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val state = TransitionTiming.stateAt(windows, presentationTimeUs)
        val mode = when (state?.type) {
            null, TransitionType.NONE -> 0f
            TransitionType.FADE_BLACK -> 1f
            TransitionType.FADE_WHITE -> 2f
            TransitionType.CROSS_ZOOM -> 3f
            TransitionType.ZOOM_IN -> 4f
            TransitionType.ZOOM_OUT -> 5f
            TransitionType.SLIDE_LEFT -> 6f
            TransitionType.SLIDE_RIGHT -> 7f
            TransitionType.SLIDE_UP -> 8f
            TransitionType.BLUR -> 9f
            TransitionType.SPIN -> 10f
        }
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uParams", floatArrayOf(mode, state?.amount ?: 0f, if (state?.incoming == true) 1f else 0f, aspect))
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
uniform vec4 uParams; // mode, amount, incoming, aspect
varying vec2 vTexCoord;
vec2 mirrorUv(vec2 uv) { vec2 m = mod(uv, 2.0); return 1.0 - abs(m - 1.0); }
vec4 tex(vec2 uv) { return texture2D(uTexSampler, mirrorUv(uv)); }
vec2 scaleAround(vec2 uv, float s) { return (uv - 0.5) / s + 0.5; }
vec2 rotateAround(vec2 uv, float a, float aspect) {
  vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
  float c = cos(a); float s = sin(a);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
  return p / vec2(aspect, 1.0) + 0.5;
}
void main() {
  float mode = uParams.x;
  float amt = uParams.y;
  float incoming = uParams.z;
  float aspect = uParams.w;
  vec2 uv = vTexCoord;
  vec4 c;
  if (mode < 0.5 || amt <= 0.0001) {
    c = texture2D(uTexSampler, uv);
  } else if (mode < 1.5) {
    c = mix(texture2D(uTexSampler, uv), vec4(0.0, 0.0, 0.0, 1.0), amt);
  } else if (mode < 2.5) {
    c = mix(texture2D(uTexSampler, uv), vec4(1.0), amt);
  } else if (mode < 3.5) {
    // Cross zoom: zoom punch with radial blur and a brightness flash at the cut.
    float s = 1.0 + amt * 0.8;
    vec2 base = scaleAround(uv, s);
    vec2 dir = (base - 0.5) * amt * 0.12;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 10; i++) { acc += tex(base - dir * (float(i) / 9.0)); }
    c = acc / 10.0;
    c.rgb = mix(c.rgb, vec3(1.0), amt * amt * 0.35);
  } else if (mode < 4.5) {
    float s = 1.0 + amt * 0.45;
    vec2 base = scaleAround(uv, s);
    vec2 dir = (base - 0.5) * amt * 0.05;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 6; i++) { acc += tex(base - dir * (float(i) / 5.0)); }
    c = acc / 6.0;
  } else if (mode < 5.5) {
    float s = 1.0 - amt * 0.4;
    c = tex(scaleAround(uv, s));
    c.rgb *= 1.0 - amt * 0.25;
  } else if (mode < 8.5) {
    vec2 dir = mode < 6.5 ? vec2(-1.0, 0.0) : (mode < 7.5 ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    // Outgoing content leaves along dir; incoming arrives from the opposite side moving the same way.
    float shift = incoming > 0.5 ? -amt : amt;
    vec2 base = uv - dir * shift * 0.5;
    vec2 blurDir = dir * amt * 0.08;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 12; i++) { acc += tex(base + blurDir * (float(i) / 11.0 - 0.5)); }
    c = acc / 12.0;
  } else if (mode < 9.5) {
    float r = amt * 0.035;
    vec4 acc = texture2D(uTexSampler, uv);
    for (int i = 0; i < 16; i++) {
      float a = float(i) * 2.39996;
      float d = sqrt(float(i + 1) / 16.0) * r;
      acc += tex(uv + vec2(cos(a) / aspect, sin(a)) * d);
    }
    c = acc / 17.0;
  } else {
    float ang = (incoming > 0.5 ? -1.0 : 1.0) * amt * 1.5708;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 8; i++) {
      float a = ang - (incoming > 0.5 ? -1.0 : 1.0) * amt * 0.25 * (float(i) / 7.0);
      acc += tex(scaleAround(rotateAround(uv, a, aspect), 1.0 + amt * 0.3));
    }
    c = acc / 8.0;
  }
  gl_FragColor = vec4(c.rgb, 1.0);
}
"""
    }
}
