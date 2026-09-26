package com.ravango.engine.beauty.gl

/**
 * GLSL ES 1.00 sources for the beauty pipeline (GLES 2 compatible, also valid on GLES 3 contexts).
 *
 * Rules followed everywhere: explicit default precision in fragment shaders, float literals only (no implicit
 * int→float), loops with constant bounds, uniform arrays ≤ 32 vec4. Full-resolution passes sample neighbours
 * with 1-texel offsets, so their texture coordinates use highp when the device supports it (mediump cannot
 * resolve 1/1920 steps near 1.0 on fp16 GPUs); colour math stays mediump.
 */
internal object BeautyShaders {

    private const val TC_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        #define TC highp
        #else
        #define TC mediump
        #endif
    """

    /** Full-screen vertex shader; texcoords optionally flipped vertically (for top-down readback). */
    const val VERTEX = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform float uFlipY;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = vec2(aTexCoord.x, mix(aTexCoord.y, 1.0 - aTexCoord.y, uFlipY));
        }
    """

    /** 4-tap box downsample (also used for the detection readback copy). */
    const val DOWNSAMPLE = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uOffset;
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord + vec2(-uOffset.x, -uOffset.y));
            c += texture2D(uTexture, vTexCoord + vec2(uOffset.x, -uOffset.y));
            c += texture2D(uTexture, vTexCoord + vec2(-uOffset.x, uOffset.y));
            c += texture2D(uTexture, vTexCoord + vec2(uOffset.x, uOffset.y));
            gl_FragColor = c * 0.25;
        }
    """

    /**
     * Separable edge-preserving (bilateral) filter. `RADIUS`/`RADIUS_F` are injected as #defines so the loop bound
     * is a compile-time constant (GLES 2 Appendix A).
     */
    fun bilateral(radius: Int): String = """
        #define RADIUS $radius
        #define RADIUS_F ${radius}.0
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uStep;
        uniform float uRangeFactor;
        void main() {
            vec4 center = texture2D(uTexture, vTexCoord);
            vec3 sum = center.rgb;
            float wsum = 1.0;
            float spatial = 1.0 / (2.0 * 0.45 * RADIUS_F * RADIUS_F);
            for (int i = 1; i <= RADIUS; i++) {
                float fi = float(i);
                float ws = exp(-fi * fi * spatial);
                vec3 a = texture2D(uTexture, vTexCoord + uStep * fi).rgb;
                vec3 b = texture2D(uTexture, vTexCoord - uStep * fi).rgb;
                vec3 da = a - center.rgb;
                vec3 db = b - center.rgb;
                float wa = ws * exp(-dot(da, da) * uRangeFactor);
                float wb = ws * exp(-dot(db, db) * uRangeFactor);
                sum += a * wa + b * wb;
                wsum += wa + wb;
            }
            gl_FragColor = vec4(sum / wsum, center.a);
        }
    """

    /** 9-tap Gaussian using 5 bilinear fetches (separable; direction in `uStep`). */
    const val GAUSSIAN = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uStep;
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord) * 0.2270270270;
            c += texture2D(uTexture, vTexCoord + uStep * 1.3846153846) * 0.3162162162;
            c += texture2D(uTexture, vTexCoord - uStep * 1.3846153846) * 0.3162162162;
            c += texture2D(uTexture, vTexCoord + uStep * 3.2307692308) * 0.0702702703;
            c += texture2D(uTexture, vTexCoord - uStep * 3.2307692308) * 0.0702702703;
            gl_FragColor = c;
        }
    """

    /**
     * Skin likelihood (YCbCr ellipse model) combined with the landmark face mask:
     * face mask R = feathered face oval, G = exclusions (eyes, brows, lips).
     */
    const val SKIN_MASK = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uFaceMask;
        uniform float uFaceWeight;
        void main() {
            vec3 c = texture2D(uTexture, vTexCoord).rgb;
            float y = dot(c, vec3(0.299, 0.587, 0.114));
            float cb = 0.5 + dot(c, vec3(-0.168736, -0.331264, 0.5));
            float cr = 0.5 + dot(c, vec3(0.5, -0.418688, -0.081312));
            vec2 d = vec2((cb - 0.405) / 0.075, (cr - 0.585) / 0.065);
            float p = exp(-0.5 * dot(d, d));
            p *= smoothstep(0.05, 0.18, y);
            vec4 fm = texture2D(uFaceMask, vTexCoord);
            float oval = fm.r * uFaceWeight;
            float excl = fm.g * uFaceWeight;
            float inside = clamp(p * 1.25 + 0.15, 0.0, 1.0);
            float outside = p * (1.0 - 0.35 * uFaceWeight);
            p = mix(outside, inside, oval) * (1.0 - excl);
            gl_FragColor = vec4(clamp(p, 0.0, 1.0));
        }
    """

    /**
     * Full-resolution skin composite: smoothing with pore restoration, retouch (mid-band flattening), blemish
     * removal, brightness, whitening, skin tone, foundation, dark circles, teeth whitening and sharpening.
     * Every block is guarded by a uniform so disabled features cost (almost) nothing.
     */
    const val COMPOSITE = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uSmooth;
        uniform sampler2D uLarge;
        uniform sampler2D uSkin;
        uniform sampler2D uFaceMask;
        uniform TC vec2 uTexel;
        uniform float uSmoothK;
        uniform float uPoreKeep;
        uniform float uRetouchK;
        uniform float uBlemishK;
        uniform float uBrightK;
        uniform float uWhitenK;
        uniform float uToneT;
        uniform float uSharpenK;
        uniform float uDarkCircleK;
        uniform float uTeethK;
        uniform float uFoundationK;
        uniform vec3 uFoundationColor;

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec4 src = texture2D(uTexture, vTexCoord);
            vec3 orig = src.rgb;
            vec3 col = orig;
            float skin = texture2D(uSkin, vTexCoord).r;

            vec3 smoothed = orig;
            if (uSmoothK > 0.0 || uRetouchK > 0.0) {
                smoothed = texture2D(uSmooth, vTexCoord).rgb;
            }
            if (uSmoothK > 0.0) {
                col = mix(orig, smoothed, uSmoothK * skin * (1.0 - uPoreKeep));
            }

            vec3 large = orig;
            if (uRetouchK > 0.0 || uBlemishK > 0.0 || uDarkCircleK > 0.0) {
                large = texture2D(uLarge, vTexCoord).rgb;
            }
            if (uRetouchK > 0.0) {
                // Flatten small tonal irregularities (blotches) but not facial structure (large amplitude).
                vec3 band = clamp(smoothed - large, -0.06, 0.06);
                col -= band * (uRetouchK * 0.75 * skin);
            }
            if (uBlemishK > 0.0) {
                float d = luma(large) - luma(col);
                float spot = smoothstep(0.012, 0.05, d) * (1.0 - smoothstep(0.14, 0.24, d));
                float w = uBlemishK * spot * skin;
                vec3 target = mix(col + vec3(d), large, 0.35);
                col = mix(col, target, w);
            }
            if (uBrightK > 0.0) {
                float k = uBrightK * (0.25 + 0.75 * skin);
                col += col * (1.0 - col) * (0.55 * k);
            }
            if (uWhitenK > 0.0) {
                float k = uWhitenK * skin;
                col = mix(col, vec3(luma(col)), 0.22 * k);
                col += (1.0 - col) * (0.2 * k);
            }
            if (uToneT != 0.0) {
                float k = abs(uToneT) * skin;
                vec3 tint = uToneT > 0.0 ? vec3(1.07, 1.02, 0.92) : vec3(1.03, 0.96, 1.05);
                col = mix(col, clamp(col * tint, 0.0, 1.0), k);
            }
            if (uFoundationK > 0.0) {
                float k = uFoundationK * skin;
                float l = luma(col);
                vec3 target = clamp(uFoundationColor * (l / max(luma(uFoundationColor), 0.05)), 0.0, 1.0);
                col = mix(col, target, 0.45 * k);
            }
            if (uDarkCircleK > 0.0 || uTeethK > 0.0) {
                vec4 fm = texture2D(uFaceMask, vTexCoord);
                if (uDarkCircleK > 0.0) {
                    float m = fm.b * uDarkCircleK;
                    float lift = max(luma(large) * 1.03 - luma(col), 0.0) + 0.02;
                    col += vec3(lift) * (0.9 * m);
                    col = mix(col, col * vec3(1.03, 1.01, 0.97), 0.6 * m);
                }
                if (uTeethK > 0.0) {
                    float l = luma(col);
                    float m = fm.a * uTeethK * smoothstep(0.22, 0.45, l);
                    float yellow = max(0.0, (col.r + col.g) * 0.5 - col.b);
                    col.b += yellow * 0.8 * m;
                    col = mix(col, vec3(luma(col)), 0.3 * m);
                    col += (1.0 - col) * (0.18 * m);
                }
            }
            if (uSharpenK > 0.0) {
                vec3 b = texture2D(uTexture, vTexCoord + uTexel).rgb;
                b += texture2D(uTexture, vTexCoord - uTexel).rgb;
                b += texture2D(uTexture, vTexCoord + vec2(uTexel.x, -uTexel.y)).rgb;
                b += texture2D(uTexture, vTexCoord + vec2(-uTexel.x, uTexel.y)).rgb;
                vec3 detail = orig - b * 0.25;
                col += detail * (uSharpenK * mix(1.2, 0.35, skin));
            }
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), src.a);
        }
    """

    /**
     * Makeup blend. Mask A: R lips, G brows, B eyeliner, A lashes. Mask B: R eyeshadow, G blush, B contour,
     * A highlight. Each colour uniform is (rgb, opacity).
     */
    const val MAKEUP = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uMaskA;
        uniform sampler2D uMaskB;
        uniform sampler2D uSkin;
        uniform vec4 uLipstick;
        uniform vec4 uLipColor;
        uniform vec4 uBrow;
        uniform vec4 uLiner;
        uniform vec4 uLash;
        uniform vec4 uShadow;
        uniform vec4 uBlush;
        uniform vec4 uContour;
        uniform vec4 uHighlight;

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        vec3 softLight(vec3 b, vec3 s) {
            vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
            vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
            return mix(lo, hi, step(0.5, s));
        }

        vec3 normalizedTint(vec3 c) {
            float m = max(max(c.r, c.g), max(c.b, 0.2));
            return c / m;
        }

        void main() {
            vec4 src = texture2D(uTexture, vTexCoord);
            vec3 col = src.rgb;
            vec4 ma = texture2D(uMaskA, vTexCoord);
            vec4 mb = texture2D(uMaskB, vTexCoord);
            float skin = texture2D(uSkin, vTexCoord).r;
            float skinGate = 0.35 + 0.65 * skin;

            if (uContour.a > 0.0) {
                float a = mb.b * uContour.a * skinGate;
                col *= mix(vec3(1.0), normalizedTint(uContour.rgb) * 0.8, 0.6 * a);
            }
            if (uBlush.a > 0.0) {
                float a = mb.g * uBlush.a * skinGate;
                vec3 tinted = mix(softLight(col, uBlush.rgb), col * normalizedTint(uBlush.rgb), 0.35);
                col = mix(col, tinted, 0.75 * a);
            }
            if (uHighlight.a > 0.0) {
                float a = mb.a * uHighlight.a * skinGate;
                col = 1.0 - (1.0 - col) * (1.0 - uHighlight.rgb * (0.5 * a));
            }
            if (uShadow.a > 0.0) {
                float a = mb.r * uShadow.a;
                vec3 m = col * mix(vec3(1.0), normalizedTint(uShadow.rgb) * 0.9, 0.85);
                col = mix(col, mix(m, softLight(col, uShadow.rgb), 0.4), 0.8 * a);
            }
            float l = luma(col);
            if (uLipColor.a > 0.0) {
                float a = ma.r * uLipColor.a;
                col = mix(col, softLight(col, uLipColor.rgb), 0.75 * a);
            }
            if (uLipstick.a > 0.0) {
                float a = ma.r * uLipstick.a;
                float shade = clamp(l / 0.42, 0.25, 1.6);
                float gloss = smoothstep(0.62, 0.9, l);
                col = mix(col, clamp(uLipstick.rgb * shade, 0.0, 1.0), 0.85 * a);
                col += vec3(gloss * 0.25 * a);
            }
            if (uBrow.a > 0.0) {
                float a = ma.g * uBrow.a;
                float hair = 1.0 - smoothstep(0.15, 0.6, l);
                vec3 target = min(col, clamp(col * uBrow.rgb * 2.2, 0.0, 1.0));
                target = mix(target, uBrow.rgb, 0.25);
                col = mix(col, target, a * (0.45 + 0.55 * hair));
            }
            if (uLash.a > 0.0) {
                float a = ma.a * uLash.a;
                col = mix(col, uLash.rgb * 0.8, a * (0.55 + 0.45 * (1.0 - l)));
            }
            if (uLiner.a > 0.0) {
                float a = ma.b * uLiner.a;
                col = mix(col, uLiner.rgb, 0.92 * a);
            }
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), src.a);
        }
    """

    /** Local-warp reshaping; mirrors `WarpSet.sourceOf` exactly. Frame space: isotropic, y down. */
    const val WARP = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uAspect;
        uniform int uCount;
        uniform vec4 uWarpA[16];
        uniform vec4 uWarpB[16];
        void main() {
            vec2 p = vec2(vTexCoord.x * uAspect, 1.0 - vTexCoord.y);
            vec2 disp = vec2(0.0);
            for (int i = 0; i < 16; i++) {
                if (i >= uCount) break;
                vec4 a = uWarpA[i];
                vec4 b = uWarpB[i];
                vec2 o = p - a.xy;
                float d2 = dot(o, o) / (a.z * a.z);
                if (d2 < 1.0) {
                    float f = (1.0 - d2) * (1.0 - d2);
                    if (a.w < 0.5) {
                        disp += b.xy * f;
                    } else if (a.w < 1.5) {
                        disp += o * (b.z * f);
                    } else {
                        disp += b.xy * (dot(o, b.xy) * b.z * f);
                    }
                }
            }
            vec2 s = p - disp;
            vec2 uv = vec2(s.x / uAspect, 1.0 - s.y);
            gl_FragColor = texture2D(uTexture, clamp(uv, 0.0, 1.0));
        }
    """

    /** Landmark geometry → mask texture. Vertices are (x, y, alpha) in frame space. */
    const val MASK_VERTEX = """
        attribute vec3 aVertex;
        uniform float uAspect;
        varying float vAlpha;
        void main() {
            vAlpha = aVertex.z;
            gl_Position = vec4(aVertex.x / uAspect * 2.0 - 1.0, (1.0 - aVertex.y) * 2.0 - 1.0, 0.0, 1.0);
        }
    """

    const val MASK_FRAGMENT = """
        precision mediump float;
        varying float vAlpha;
        void main() {
            gl_FragColor = vec4(vAlpha);
        }
    """
}
