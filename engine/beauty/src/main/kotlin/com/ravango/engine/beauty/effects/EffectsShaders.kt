package com.ravango.engine.beauty.effects

/**
 * GLSL ES 1.00 sources for lenses, live filters and background effects (same conventions as `BeautyShaders`:
 * explicit precision, float literals, constant loop bounds; full-resolution texture coordinates use highp).
 */
internal object EffectsShaders {

    private const val TC_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        #define TC highp
        #else
        #define TC mediump
        #endif
    """

    /**
     * Live filter block (declarations + `applyFilter`): trilinear 3D-LUT lookup (512² / 64 levels / 8×8 tiles, see
     * `LutGenerator`) with intensity, plus the swipe split between the current LUT and the incoming one. The split
     * coordinate is the screen x of the pixel: `dot((tc, 1), uSplitAxis)`. Merged into the beauty composite pass
     * when that pass is the last full-frame write (no extra pass for "beauty + filter").
     */
    const val FILTER_BLOCK = """
        uniform sampler2D uLut;
        uniform sampler2D uLutB;
        uniform float uLutK;
        uniform float uLutBK;
        uniform vec3 uSplitAxis;
        uniform float uSplitEdge;
        uniform float uSplitSide;

        vec3 sampleLut(sampler2D t, vec3 c) {
            vec3 k = clamp(c, 0.0, 1.0);
            float b = k.b * 63.0;
            float b0 = floor(b);
            float b1 = min(b0 + 1.0, 63.0);
            vec2 rg = k.rg * (63.0 / 512.0) + (0.5 / 512.0);
            vec2 q0 = vec2(mod(b0, 8.0), floor(b0 / 8.0)) * 0.125 + rg;
            vec2 q1 = vec2(mod(b1, 8.0), floor(b1 / 8.0)) * 0.125 + rg;
            return mix(texture2D(t, q0).rgb, texture2D(t, q1).rgb, b - b0);
        }

        vec3 applyFilter(vec3 c, vec2 tc) {
            float useB = 0.0;
            if (uSplitSide != 0.0) {
                float s = dot(vec3(tc, 1.0), uSplitAxis);
                float e = smoothstep(uSplitEdge - 0.002, uSplitEdge + 0.002, s);
                useB = uSplitSide > 0.0 ? e : 1.0 - e;
            }
            vec3 a = c;
            if (uLutK > 0.0 && useB < 1.0) a = mix(c, sampleLut(uLut, c), uLutK);
            if (useB > 0.0) {
                vec3 b = c;
                if (uLutBK > 0.0) b = mix(c, sampleLut(uLutB, c), uLutBK);
                a = mix(a, b, useB);
                // A thin light seam along the split line, like a sliding glass edge.
                float s2 = dot(vec3(tc, 1.0), uSplitAxis);
                a += vec3(0.18) * (1.0 - smoothstep(0.0, 0.004, abs(s2 - uSplitEdge)));
            }
            return a;
        }
    """

    /**
     * Final full-frame pass: background effect (segmentation mask × blur / colour / gradient / photo) → Smooth Glow
     * (soft focus + bloom) → live filter. Every block is uniform-guarded.
     */
    const val FINAL = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform sampler2D uBgBlur;
        uniform sampler2D uBgImage;
        uniform sampler2D uGlow;
        uniform float uBgMode;
        uniform vec4 uBgColorA;
        uniform vec4 uBgColorB;
        uniform vec4 uBgImageRect;
        uniform float uGlowK;
    """ + FILTER_BLOCK + """
        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec3 col = texture2D(uTexture, vTexCoord).rgb;
            if (uBgMode > 0.5) {
                float m = texture2D(uMask, vTexCoord).r;
                vec3 bg;
                if (uBgMode < 1.5) {
                    vec4 b = texture2D(uBgBlur, vTexCoord);
                    bg = b.a > 0.02 ? b.rgb / b.a : col;
                } else if (uBgMode < 2.5) {
                    bg = uBgColorA.rgb;
                } else if (uBgMode < 3.5) {
                    bg = mix(uBgColorB.rgb, uBgColorA.rgb, vTexCoord.y);
                } else {
                    bg = texture2D(uBgImage, vTexCoord * uBgImageRect.xy + uBgImageRect.zw).rgb;
                }
                // Light wrap: a hint of the new background on the person's edge.
                float edge = m * (1.0 - m) * 4.0;
                col = mix(col, mix(col, bg, 0.35), edge * 0.5);
                col = mix(bg, col, m);
            }
            if (uGlowK > 0.0) {
                vec3 g = texture2D(uGlow, vTexCoord).rgb;
                // Soft focus (lighten towards the blur) + bloom from bright areas + a warm lift.
                col = mix(col, max(col, g), 0.45 * uGlowK);
                col += max(g - vec3(0.55), vec3(0.0)) * (0.9 * uGlowK);
                col = mix(col, col * vec3(1.04, 1.0, 0.97) + vec3(0.02), 0.6 * uGlowK);
            }
            col = applyFilter(col, vTexCoord);
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    /**
     * Guided (joint-bilateral) upsampling of the low-resolution segmentation mask: 3×3 mask taps weighted by how
     * similar the low-resolution guide colour at each tap is to this pixel's colour, so the mask edge snaps to the
     * image edge (hair, shoulders) instead of the blocky mask grid; then a gentle contrast curve.
     */
    const val MASK_REFINE = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uMask;
        uniform sampler2D uGuide;
        uniform sampler2D uFrame;
        uniform TC vec2 uMaskTexel;
        uniform float uRange;
        void main() {
            vec3 center = texture2D(uFrame, vTexCoord).rgb;
            float sum = 0.0;
            float wsum = 0.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    TC vec2 o = vec2(float(i), float(j)) * uMaskTexel;
                    float m = texture2D(uMask, vTexCoord + o).r;
                    vec3 d = texture2D(uGuide, vTexCoord + o).rgb - center;
                    float w = exp(-dot(d, d) * uRange) * (i == 0 && j == 0 ? 1.0 : 0.6);
                    sum += m * w;
                    wsum += w;
                }
            }
            float m = sum / max(wsum, 0.0001);
            gl_FragColor = vec4(smoothstep(0.22, 0.78, m));
        }
    """

    /**
     * Background-weighted prepass for the portrait blur (quarter resolution): rgb × w, w with w = 1 − person, so the
     * blur never drags the person's colours into the background (no halo). Highlights are boosted slightly so the
     * blurred lights read as bokeh.
     */
    const val BOKEH_PREP = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform vec2 uOffset;
        void main() {
            vec3 c = texture2D(uTexture, vTexCoord + vec2(-uOffset.x, -uOffset.y)).rgb;
            c += texture2D(uTexture, vTexCoord + vec2(uOffset.x, -uOffset.y)).rgb;
            c += texture2D(uTexture, vTexCoord + vec2(-uOffset.x, uOffset.y)).rgb;
            c += texture2D(uTexture, vTexCoord + vec2(uOffset.x, uOffset.y)).rgb;
            c *= 0.25;
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            c *= 1.0 + 0.8 * smoothstep(0.72, 0.98, l);
            float w = 1.0 - texture2D(uMask, vTexCoord).r;
            gl_FragColor = vec4(c * w, w);
        }
    """

    /**
     * Disc ("bokeh") blur over a golden-angle spiral of TAPS offsets (precomputed on the CPU, see [bokehTaps]);
     * keeps the premultiplied background weights.
     */
    fun bokeh(taps: Int): String = """
        #define TAPS $taps
        #define TAPS_F ${taps}.0
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uRadius;
        uniform vec2 uTaps[TAPS];
        void main() {
            vec4 sum = vec4(0.0);
            for (int i = 0; i < TAPS; i++) sum += texture2D(uTexture, vTexCoord + uTaps[i] * uRadius);
            gl_FragColor = sum / TAPS_F;
        }
    """

    /** Unit-disc golden-angle spiral offsets (x, y pairs) for [bokeh]. */
    fun bokehTaps(taps: Int): FloatArray {
        val out = FloatArray(taps * 2)
        for (i in 0 until taps) {
            val r = kotlin.math.sqrt((i + 0.5f) / taps)
            val a = i * 2.39996323f
            out[i * 2] = r * kotlin.math.cos(a)
            out[i * 2 + 1] = r * kotlin.math.sin(a)
        }
        return out
    }

    /** Face paint on the tracked mesh (premultiplied output for in-place blending). */
    const val PAINT = """
        precision mediump float;
        varying vec2 vUv;
        varying vec2 vScreen;
        uniform sampler2D uPaint;
        uniform float uK;
        void main() {
            vec3 p = texture2D(uPaint, vUv).rgb;
            float blush = p.g * 0.42 * uK;
            float freckle = p.r * 0.66 * uK;
            float shine = p.b * 0.55 * uK;
            vec3 c = vec3(0.97, 0.42, 0.52) * blush;
            float a = blush;
            c = c * (1.0 - freckle) + vec3(0.42, 0.24, 0.15) * freckle;
            a = a * (1.0 - freckle) + freckle;
            c += vec3(1.0, 0.96, 0.92) * shine;
            gl_FragColor = vec4(c, a);
        }
    """

    /** Textured quads (sprites, particles) with a premultiplied colour multiplier. */
    const val SPRITE_VERTEX = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        attribute vec4 aColor;
        varying vec2 vUv;
        varying vec4 vColor;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vUv = aTexCoord;
            vColor = aColor;
        }
    """

    const val SPRITE_FRAGMENT = """
        precision mediump float;
        varying vec2 vUv;
        varying vec4 vColor;
        uniform sampler2D uAtlas;
        void main() { gl_FragColor = texture2D(uAtlas, vUv) * vColor; }
    """

    /** Screen-space rotation that gives the split axis for a frame shown rotated by [rotationCw] (see FILTER_BLOCK). */
    fun splitAxis(rotationCw: Int, out: FloatArray) {
        when (((rotationCw % 360) + 360) % 360) {
            90 -> { out[0] = 0f; out[1] = 1f; out[2] = 0f }
            180 -> { out[0] = -1f; out[1] = 0f; out[2] = 1f }
            270 -> { out[0] = 0f; out[1] = -1f; out[2] = 1f }
            else -> { out[0] = 1f; out[1] = 0f; out[2] = 0f }
        }
    }
}
