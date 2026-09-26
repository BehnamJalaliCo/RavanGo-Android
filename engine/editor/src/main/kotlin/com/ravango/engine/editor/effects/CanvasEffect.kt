package com.ravango.engine.editor.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ravango.core.model.CanvasBackground
import com.ravango.core.model.ContentFit
import com.ravango.core.model.CropRect
import com.ravango.core.model.Transform2D
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Places one clip on the output canvas.
 *
 * The output frame is always [outputWidth]×[outputHeight] (derived from the canvas aspect ratio and the preview/export
 * resolution), so every item of the main sequence produces identically sized frames. The clip is cropped
 * ([CropRect], normalized to the upright source), turned by quarter turns, flipped, fitted (FIT/FILL), then scaled,
 * translated and finely rotated by [Transform2D]. Uncovered canvas shows a solid color, a gradient or a blurred,
 * canvas-filling copy of the clip.
 */
data class CanvasEffect(
    val outputWidth: Int,
    val outputHeight: Int,
    val crop: CropRect,
    val quarterTurns: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val transform: Transform2D,
    val fit: ContentFit,
    val background: CanvasBackground,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = CanvasShaderProgram(this)
}

/** Pure placement math (pixels, y down), shared with the UI for handle positioning. */
data class CanvasPlacement(val centerX: Float, val centerY: Float, val width: Float, val height: Float, val rotationDegrees: Float) {
    companion object {
        /**
         * @param sourceWidth/sourceHeight upright source frame size.
         */
        fun compute(
            sourceWidth: Int,
            sourceHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
            crop: CropRect,
            quarterTurns: Int,
            fit: ContentFit,
            transform: Transform2D,
        ): CanvasPlacement {
            val cw = sourceWidth * (crop.right - crop.left).coerceAtLeast(0.01f)
            val ch = sourceHeight * (crop.bottom - crop.top).coerceAtLeast(0.01f)
            val odd = quarterTurns.mod(2) == 1
            val dw = if (odd) ch else cw
            val dh = if (odd) cw else ch
            val s = if (fit == ContentFit.FIT) min(canvasWidth / dw, canvasHeight / dh) else max(canvasWidth / dw, canvasHeight / dh)
            val k = s * transform.scale.coerceIn(0.05f, 10f)
            return CanvasPlacement(transform.centerX * canvasWidth, transform.centerY * canvasHeight, dw * k, dh * k, transform.rotationDegrees)
        }
    }
}

private class CanvasShaderProgram(private val effect: CanvasEffect) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {
    private val main: GlProgram = GlSl.program(FRAGMENT)
    private val down: GlProgram? = if (effect.background is CanvasBackground.Blur) GlSl.program(DOWNSAMPLE) else null
    private val blur: GlProgram? = if (effect.background is CanvasBackground.Blur) GlSl.program(BLUR) else null
    private var targets: Pair<RenderTarget, RenderTarget>? = null
    private var inputWidth = 0
    private var inputHeight = 0
    private val fbState = FramebufferState()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        if (effect.background is CanvasBackground.Blur && targets == null) {
            val w = (effect.outputWidth / 8).coerceAtLeast(32)
            val h = (effect.outputHeight / 8).coerceAtLeast(32)
            targets = RenderTarget(w, h) to RenderTarget(w, h)
        }
        return Size(effect.outputWidth, effect.outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val bgMode = when (effect.background) {
                is CanvasBackground.Solid -> 0
                is CanvasBackground.Gradient -> 1
                is CanvasBackground.Blur -> 2
            }
            if (bgMode == 2) renderBlurredBackground(inputTexId)

            val placement = CanvasPlacement.compute(inputWidth, inputHeight, effect.outputWidth, effect.outputHeight, effect.crop, effect.quarterTurns, effect.fit, effect.transform)
            val rad = Math.toRadians(placement.rotationDegrees.toDouble())
            main.use()
            main.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            main.setSamplerTexIdUniform("uBgSampler", targets?.first?.texId ?: inputTexId, 1)
            main.setFloatsUniform("uOutSize", floatArrayOf(effect.outputWidth.toFloat(), effect.outputHeight.toFloat()))
            main.setFloatsUniform("uCenter", floatArrayOf(placement.centerX, placement.centerY))
            main.setFloatsUniform("uHalfSize", floatArrayOf(placement.width / 2f, placement.height / 2f))
            main.setFloatsUniform("uRot", floatArrayOf(cos(rad).toFloat(), sin(rad).toFloat()))
            setContentUniforms(main)
            main.setFloatUniform("uBgMode", bgMode.toFloat())
            when (val bg = effect.background) {
                is CanvasBackground.Solid -> {
                    main.setFloatsUniform("uColorA", bg.color.argbToFloats())
                    main.setFloatsUniform("uColorB", bg.color.argbToFloats())
                    main.setFloatsUniform("uGradDir", floatArrayOf(0f, 1f))
                }
                is CanvasBackground.Gradient -> {
                    val a = Math.toRadians(bg.angleDegrees.toDouble())
                    main.setFloatsUniform("uColorA", bg.start.argbToFloats())
                    main.setFloatsUniform("uColorB", bg.end.argbToFloats())
                    main.setFloatsUniform("uGradDir", floatArrayOf(sin(a).toFloat(), -cos(a).toFloat()))
                }
                is CanvasBackground.Blur -> {
                    main.setFloatsUniform("uColorA", floatArrayOf(0f, 0f, 0f, 1f))
                    main.setFloatsUniform("uColorB", floatArrayOf(0f, 0f, 0f, 1f))
                    main.setFloatsUniform("uGradDir", floatArrayOf(0f, 1f))
                }
            }
            GlSl.drawQuad(main)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun setContentUniforms(p: GlProgram) {
        val c = effect.crop
        p.setFloatsUniform("uCrop", floatArrayOf(c.left, c.top, c.right, c.bottom))
        p.setFloatUniform("uTurns", effect.quarterTurns.mod(4).toFloat())
        p.setFloatsUniform("uFlip", floatArrayOf(if (effect.flipHorizontal) 1f else 0f, if (effect.flipVertical) 1f else 0f))
    }

    /** Downsamples a canvas-filling copy of the clip into a small target, then blurs it with separable passes. */
    private fun renderBlurredBackground(inputTexId: Int) {
        val (a, b) = targets ?: return
        val down = down ?: return
        val blur = blur ?: return
        fbState.save()
        // Aspect of the displayed (cropped + turned) content.
        val cw = inputWidth * (effect.crop.right - effect.crop.left)
        val ch = inputHeight * (effect.crop.bottom - effect.crop.top)
        val odd = effect.quarterTurns.mod(2) == 1
        val contentAspect = if (odd) ch / cw else cw / ch
        val outAspect = effect.outputWidth.toFloat() / effect.outputHeight
        // FILL: scale the sampling window so the content covers the canvas.
        val scale = if (contentAspect > outAspect) floatArrayOf(outAspect / contentAspect, 1f) else floatArrayOf(1f, contentAspect / outAspect)

        a.focus()
        down.use()
        down.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        down.setFloatsUniform("uFillScale", scale)
        setContentUniforms(down)
        GlSl.drawQuad(down)

        val radius = (effect.background as CanvasBackground.Blur).radius.coerceIn(0.05f, 1f)
        val iterations = 1 + (radius * 3).toInt()
        repeat(iterations) {
            b.focus()
            blur.use()
            blur.setSamplerTexIdUniform("uTexSampler", a.texId, 0)
            blur.setFloatsUniform("uStep", floatArrayOf((1f + radius * 2f) / a.width, 0f))
            GlSl.drawQuad(blur)
            a.focus()
            blur.setSamplerTexIdUniform("uTexSampler", b.texId, 0)
            blur.setFloatsUniform("uStep", floatArrayOf(0f, (1f + radius * 2f) / a.height))
            GlSl.drawQuad(blur)
        }
        fbState.restore()
    }

    override fun release() {
        super.release()
        try {
            main.delete()
            down?.delete()
            blur?.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        targets?.let { it.first.release(); it.second.release() }
        targets = null
    }

    companion object {
        private const val CONTENT_UV = """
uniform vec4 uCrop;
uniform float uTurns;
uniform vec2 uFlip;
// local: [0,1]^2 in the displayed clip, y down. Returns GL texture coordinates in the upright source.
vec2 contentUv(vec2 local) {
  vec2 d = local;
  if (uFlip.x > 0.5) d.x = 1.0 - d.x;
  if (uFlip.y > 0.5) d.y = 1.0 - d.y;
  vec2 c;
  if (uTurns < 0.5) c = d;
  else if (uTurns < 1.5) c = vec2(d.y, 1.0 - d.x);
  else if (uTurns < 2.5) c = vec2(1.0 - d.x, 1.0 - d.y);
  else c = vec2(1.0 - d.y, d.x);
  vec2 s = vec2(mix(uCrop.x, uCrop.z, c.x), mix(uCrop.y, uCrop.w, c.y));
  return vec2(s.x, 1.0 - s.y);
}
"""

        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uBgSampler;
uniform vec2 uOutSize;
uniform vec2 uCenter;
uniform vec2 uHalfSize;
uniform vec2 uRot;
uniform float uBgMode;
uniform vec4 uColorA;
uniform vec4 uColorB;
uniform vec2 uGradDir;
varying vec2 vTexCoord;
$CONTENT_UV
void main() {
  vec2 uvDown = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
  vec2 px = uvDown * uOutSize;
  vec4 bg;
  if (uBgMode < 0.5) {
    bg = vec4(uColorA.rgb, 1.0);
  } else if (uBgMode < 1.5) {
    float t = clamp(dot(uvDown - 0.5, uGradDir) + 0.5, 0.0, 1.0);
    bg = vec4(mix(uColorA.rgb, uColorB.rgb, t), 1.0);
  } else {
    bg = vec4(texture2D(uBgSampler, vTexCoord).rgb * 0.82, 1.0);
  }
  vec2 q = px - uCenter;
  vec2 r = vec2(q.x * uRot.x + q.y * uRot.y, -q.x * uRot.y + q.y * uRot.x);
  vec2 dist = uHalfSize - abs(r);
  float inside = clamp(min(dist.x, dist.y) + 0.5, 0.0, 1.0);
  vec4 fg = vec4(0.0);
  if (inside > 0.0) {
    vec2 local = clamp(r / (2.0 * uHalfSize) + 0.5, 0.0, 1.0);
    fg = texture2D(uTexSampler, contentUv(local));
  }
  gl_FragColor = vec4(mix(bg.rgb, fg.rgb, inside * fg.a), 1.0);
}
"""

        const val DOWNSAMPLE = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uFillScale;
varying vec2 vTexCoord;
$CONTENT_UV
void main() {
  vec2 uvDown = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
  vec2 local = (uvDown - 0.5) * uFillScale + 0.5;
  // 4 taps smooth the heavy downscale.
  vec2 o = vec2(0.25, 0.25) * uFillScale * 0.08;
  vec4 c = texture2D(uTexSampler, contentUv(clamp(local + vec2(o.x, o.y), 0.0, 1.0)));
  c += texture2D(uTexSampler, contentUv(clamp(local + vec2(-o.x, o.y), 0.0, 1.0)));
  c += texture2D(uTexSampler, contentUv(clamp(local + vec2(o.x, -o.y), 0.0, 1.0)));
  c += texture2D(uTexSampler, contentUv(clamp(local + vec2(-o.x, -o.y), 0.0, 1.0)));
  gl_FragColor = c * 0.25;
}
"""

        /** 9-tap gaussian using linear sampling (5 fetches). */
        const val BLUR = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uStep;
varying vec2 vTexCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexCoord) * 0.2270270270;
  c += texture2D(uTexSampler, vTexCoord + uStep * 1.3846153846) * 0.3162162162;
  c += texture2D(uTexSampler, vTexCoord - uStep * 1.3846153846) * 0.3162162162;
  c += texture2D(uTexSampler, vTexCoord + uStep * 3.2307692308) * 0.0702702703;
  c += texture2D(uTexSampler, vTexCoord - uStep * 3.2307692308) * 0.0702702703;
  gl_FragColor = c;
}
"""
    }
}
