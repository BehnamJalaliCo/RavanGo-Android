package com.ravango.engine.beauty.gl

/**
 * GLSL ES 1.00 sources for the beauty pipeline (GLES 2 compatible, also valid on GLES 3 contexts).
 *
 * Rules followed everywhere: explicit default precision in fragment shaders, float literals only (no implicit
 * int→float), loops with constant bounds, uniform arrays ≤ 32 vec4. Passes that sample the full-resolution frame
 * at per-pixel positions use highp texture coordinates when available (mediump cannot resolve 1/1920 steps near
 * 1.0 on fp16 GPUs); colour math stays mediump.
 *
 * Frame space (used by every mesh): isotropic, normalized by frame height, origin top-left, y down —
 * `x ∈ [0, aspect]`, `y ∈ [0, 1]`; GL texture coordinates are `(x / aspect, 1 − y)`.
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

    /** 4-tap box downsample (also used for the tracking readback copy). */
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

    /** Plain copy (the base layer under mesh passes). */
    const val COPY = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
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
     * Skin likelihood at low resolution: a YCbCr skin-colour model gated by the tracked mesh.
     * Face mask R = ½·(face surface + skin region), i.e. 0 outside faces, ½ on eyes/brows/lips, 1 on skin.
     * Inside a face the mesh region dominates (feathered, still modulated by colour so hair or a hand over the
     * cheek is not smoothed); outside faces only skin-coloured pixels are touched, more gently when a face exists.
     */
    const val SKIN_MASK = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uFaceMask;
        uniform float uOutsideK;
        void main() {
            vec3 c = texture2D(uTexture, vTexCoord).rgb;
            float y = dot(c, vec3(0.299, 0.587, 0.114));
            float cb = 0.5 + dot(c, vec3(-0.168736, -0.331264, 0.5));
            float cr = 0.5 + dot(c, vec3(0.5, -0.418688, -0.081312));
            vec2 d = vec2((cb - 0.405) / 0.075, (cr - 0.585) / 0.065);
            float p = exp(-0.5 * dot(d, d));
            p *= smoothstep(0.05, 0.18, y);
            float r = texture2D(uFaceMask, vTexCoord).r;
            float face = clamp(r * 2.0, 0.0, 1.0);
            float region = clamp(r * 2.0 - 1.0, 0.0, 1.0);
            float inside = clamp(p * 1.4 + 0.3, 0.0, 1.0) * region;
            float outside = p * (1.0 - face) * uOutsideK;
            gl_FragColor = vec4(max(inside, outside));
        }
    """

    /**
     * Face Retouch at full resolution:
     * - Soft Skin by frequency separation: `base (bilateral, low-res) + detail (orig − low-pass) · keep`, blended
     *   by the skin mask, so pores and fine texture survive (natural, not plastic);
     * - retouch (mid-band flattening), blemish removal, brightness, whitening, skin tone;
     * - under-eye dark circles (mesh region under the lower lid);
     * - teeth whitening (mouth interior × bright, desaturated, yellowish pixels only);
     * - eyes (eye interior from the mesh): sclera whitening with the iris excluded, iris recolour keeping pupil and
     *   catch-lights, extra sharpening.
     * Every block is guarded by a uniform so disabled features cost (almost) nothing.
     * Face mask: R ½·(surface + skin) · G under-eye · B mouth interior · A eye interior.
     */
    const val COMPOSITE = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform sampler2D uLow;
        uniform sampler2D uSmooth;
        uniform sampler2D uLarge;
        uniform sampler2D uSkin;
        uniform sampler2D uFaceMask;
        uniform TC vec2 uTexel;
        uniform TC float uAspect;
        uniform float uSmoothK;
        uniform float uDetailKeep;
        uniform float uRetouchK;
        uniform float uBlemishK;
        uniform float uBrightK;
        uniform float uWhitenK;
        uniform float uToneT;
        uniform float uSharpenK;
        uniform float uDarkCircleK;
        uniform float uTeethK;
        uniform float uEyeWhitenK;
        uniform float uEyeSharpenK;
        uniform vec4 uEyeColor;
        uniform float uUseFaceMask;
        uniform int uIrisCount;
        uniform TC vec4 uIris[4];

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec4 src = texture2D(uTexture, vTexCoord);
            vec3 orig = src.rgb;
            vec3 col = orig;
            float skin = texture2D(uSkin, vTexCoord).r;
            vec4 fm = vec4(0.0);
            if (uUseFaceMask > 0.5) fm = texture2D(uFaceMask, vTexCoord);

            vec3 smoothed = orig;
            if (uSmoothK > 0.0 || uRetouchK > 0.0) smoothed = texture2D(uSmooth, vTexCoord).rgb;
            if (uSmoothK > 0.0) {
                vec3 low = texture2D(uLow, vTexCoord).rgb;
                vec3 soft = smoothed + (orig - low) * uDetailKeep;
                col = mix(orig, soft, uSmoothK * skin);
            }

            vec3 large = orig;
            if (uRetouchK > 0.0 || uBlemishK > 0.0 || uDarkCircleK > 0.0) large = texture2D(uLarge, vTexCoord).rgb;
            if (uRetouchK > 0.0) {
                vec3 band = clamp(smoothed - large, -0.06, 0.06);
                col -= band * (uRetouchK * 0.75 * skin);
            }
            if (uBlemishK > 0.0) {
                float d = luma(large) - luma(col);
                float spot = smoothstep(0.012, 0.05, d) * (1.0 - smoothstep(0.14, 0.24, d));
                float w = uBlemishK * spot * skin;
                col = mix(col, mix(col + vec3(d), large, 0.35), w);
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
            if (uDarkCircleK > 0.0 && fm.g > 0.0) {
                float m = fm.g * uDarkCircleK;
                float lift = max(luma(large) * 1.04 - luma(col), 0.0) + 0.015;
                col += vec3(lift) * (0.95 * m);
                col = mix(col, col * vec3(1.03, 1.01, 0.97), 0.55 * m);
            }
            if (uTeethK > 0.0 && fm.b > 0.0) {
                float l = luma(col);
                float mx = max(col.r, max(col.g, col.b));
                float mn = min(col.r, min(col.g, col.b));
                float sat = (mx - mn) / max(mx, 0.001);
                // Teeth: bright, low saturation, not red-dominant (lips, tongue and gums are excluded).
                float isTooth = smoothstep(0.22, 0.42, l) * (1.0 - smoothstep(0.28, 0.5, sat)) * (1.0 - smoothstep(0.06, 0.16, col.r - col.g));
                float m = fm.b * uTeethK * isTooth;
                float yellow = max(0.0, (col.r + col.g) * 0.5 - col.b);
                col.b += yellow * 0.85 * m;
                col = mix(col, vec3(luma(col)), 0.35 * m);
                col += (1.0 - col) * (0.16 * m);
            }
            if (fm.a > 0.0 && (uEyeWhitenK > 0.0 || uEyeColor.a > 0.0)) {
                TC vec2 p = vec2(vTexCoord.x * uAspect, 1.0 - vTexCoord.y);
                float iris = 0.0;
                for (int i = 0; i < 4; i++) {
                    if (i >= uIrisCount) break;
                    TC vec4 ir = uIris[i];
                    float d = length(p - ir.xy);
                    iris = max(iris, ir.w * (1.0 - smoothstep(ir.z * 0.82, ir.z * 1.04, d)));
                }
                float eye = fm.a;
                float l = luma(col);
                if (uEyeWhitenK > 0.0) {
                    // Sclera only: outside the iris, reasonably bright (not lashes / lid shadow).
                    float m = eye * (1.0 - iris) * smoothstep(0.2, 0.45, l) * uEyeWhitenK;
                    float redness = max(0.0, col.r - (col.g + col.b) * 0.5);
                    col.gb += vec2(redness * 0.6 * m);
                    col = mix(col, vec3(luma(col)), 0.45 * m);
                    col += (1.0 - col) * (0.14 * m);
                }
                if (uEyeColor.a > 0.0) {
                    // Keep the pupil (dark core) and catch-lights (brightest pixels); colour the iris texture.
                    float keep = smoothstep(0.05, 0.16, l) * (1.0 - smoothstep(0.72, 0.9, l));
                    float m = eye * iris * keep * uEyeColor.a;
                    vec3 tint = uEyeColor.rgb * (l / max(luma(uEyeColor.rgb), 0.08));
                    vec3 target = mix(tint, col * uEyeColor.rgb * 1.8, 0.35);
                    col = mix(col, clamp(target, 0.0, 1.0), 0.85 * m);
                }
            }
            float sharpen = uSharpenK * mix(1.2, 0.35, skin) + uEyeSharpenK * fm.a;
            if (sharpen > 0.0) {
                vec3 b = texture2D(uTexture, vTexCoord + uTexel).rgb;
                b += texture2D(uTexture, vTexCoord - uTexel).rgb;
                b += texture2D(uTexture, vTexCoord + vec2(uTexel.x, -uTexel.y)).rgb;
                b += texture2D(uTexture, vTexCoord + vec2(-uTexel.x, uTexel.y)).rgb;
                col += (orig - b * 0.25) * sharpen;
            }
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), src.a);
        }
    """

    /** Face mesh → frame space; passes canonical UVs (makeup textures) and the frame texcoord under the vertex. */
    const val MESH_VERTEX = """
        attribute vec3 aPosition;
        attribute vec2 aUv;
        uniform float uAspect;
        uniform float uDepthScale;
        varying vec2 vUv;
        varying vec2 vScreen;
        void main() {
            vUv = aUv;
            vScreen = vec2(aPosition.x / uAspect, 1.0 - aPosition.y);
            gl_Position = vec4(vScreen * 2.0 - 1.0, clamp(aPosition.z * uDepthScale, -0.99, 0.99), 1.0);
        }
    """

    /** Region masks from the UV region texture (R ½·(surface + skin), G under-eye), scaled by face presence. */
    const val MASK_REGIONS = """
        precision mediump float;
        varying vec2 vUv;
        varying vec2 vScreen;
        uniform sampler2D uRegions;
        uniform float uWeight;
        void main() {
            vec4 r = texture2D(uRegions, vUv);
            gl_FragColor = vec4((r.r * 0.5 + 0.5) * uWeight, r.g * uWeight, 0.0, 0.0);
        }
    """

    /** Solid region fill (mouth / eye openings): [uValue] per channel, scaled by face presence. */
    const val MASK_SOLID = """
        precision mediump float;
        varying vec2 vUv;
        varying vec2 vScreen;
        uniform vec4 uValue;
        uniform float uWeight;
        void main() { gl_FragColor = uValue * uWeight; }
    """

    /**
     * Face Mask makeup: the tracked mesh samples UV-space makeup textures and blends each layer with the camera
     * pixel under it (read from [uFrame] at the same screen position) using its own blend mode:
     * foundation (colour + tone evening) → contour (multiply) → blush (soft-light/multiply) → highlight (screen) →
     * eyeshadow (multiply/soft-light) → brows (darken) → lip tint (soft-light, sheer) → lipstick (colour with
     * luminance kept, specular highlights preserved) → eyeliner (normal) → lashes (normal).
     * Eye and mouth openings are not part of the drawn triangles, so nothing is ever painted on teeth or eyeballs.
     * Layer uniforms are (rgb, strength) with strength = intensity × face presence.
     */
    const val MAKEUP = TC_PRECISION + """
        precision mediump float;
        varying vec2 vUv;
        varying TC vec2 vScreen;
        uniform sampler2D uFrame;
        uniform sampler2D uLow;
        uniform sampler2D uSkin;
        uniform sampler2D uMakeupA;
        uniform sampler2D uMakeupB;
        uniform sampler2D uMakeupC;
        uniform vec4 uFoundation;
        uniform vec4 uContour;
        uniform vec4 uBlush;
        uniform vec4 uHighlight;
        uniform vec4 uShadow;
        uniform vec4 uBrow;
        uniform vec4 uLipColor;
        uniform vec4 uLipstick;
        uniform vec4 uLiner;
        uniform vec4 uLash;

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        vec3 softLight(vec3 b, vec3 s) {
            vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
            vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
            return mix(lo, hi, step(0.5, s));
        }

        /** "Color" blend: hue/saturation of c, luminance of the base. */
        vec3 colorBlend(vec3 base, vec3 c) {
            float d = luma(base) - luma(c);
            vec3 r = c + vec3(d);
            float l = luma(r);
            float mn = min(r.r, min(r.g, r.b));
            float mx = max(r.r, max(r.g, r.b));
            if (mn < 0.0) r = l + (r - l) * l / max(l - mn, 0.0001);
            if (mx > 1.0) r = l + (r - l) * (1.0 - l) / max(mx - l, 0.0001);
            return r;
        }

        vec3 normalizedTint(vec3 c) {
            float m = max(max(c.r, c.g), max(c.b, 0.2));
            return c / m;
        }

        void main() {
            vec3 base = texture2D(uFrame, vScreen).rgb;
            vec3 col = base;
            vec4 a = texture2D(uMakeupA, vUv);
            vec4 b = texture2D(uMakeupB, vUv);
            vec4 c = texture2D(uMakeupC, vUv);
            // Occluders (hair, hands) over the cheeks are not skin-coloured: face makeup fades there.
            float gate = 0.35 + 0.65 * texture2D(uSkin, vScreen).r;
            float skinRegion = c.r;

            if (uFoundation.a > 0.0) {
                float k = uFoundation.a * skinRegion * gate;
                vec3 low = texture2D(uLow, vScreen).rgb;
                vec3 even = mix(col, low, 0.3);
                vec3 tinted = colorBlend(even, mix(even, uFoundation.rgb, 0.65));
                tinted = mix(tinted, tinted * (luma(uFoundation.rgb) / max(luma(tinted), 0.05)), 0.25);
                col = mix(col, clamp(tinted, 0.0, 1.0), 0.8 * k);
            }
            if (uContour.a > 0.0) {
                float k = b.b * uContour.a * gate;
                vec3 shade = normalizedTint(mix(uContour.rgb, vec3(luma(uContour.rgb)), 0.3)) * 0.78;
                col *= mix(vec3(1.0), shade, 0.65 * k);
            }
            if (uBlush.a > 0.0) {
                float k = b.g * uBlush.a * gate;
                vec3 tinted = mix(softLight(col, uBlush.rgb), col * normalizedTint(uBlush.rgb), 0.4);
                col = mix(col, tinted, 0.8 * k);
            }
            if (uHighlight.a > 0.0) {
                float k = b.a * uHighlight.a * gate;
                float shine = 0.6 + 0.4 * smoothstep(0.3, 0.8, luma(col));
                col = 1.0 - (1.0 - col) * (1.0 - uHighlight.rgb * (0.45 * k * shine));
            }
            if (uShadow.a > 0.0) {
                float k = b.r * uShadow.a;
                vec3 m = col * mix(vec3(1.0), normalizedTint(uShadow.rgb) * 0.88, 0.85);
                col = mix(col, mix(m, softLight(col, uShadow.rgb), 0.4), 0.85 * k);
            }
            if (uBrow.a > 0.0) {
                float k = a.g * uBrow.a;
                vec3 target = min(col, colorBlend(col * 0.82, uBrow.rgb) * 0.9);
                target = mix(target, uBrow.rgb, 0.3 * a.g);
                col = mix(col, target, k);
            }
            float l = luma(col);
            float spec = 0.0;
            if (uLipColor.a > 0.0 || uLipstick.a > 0.0) {
                // Specular: how much brighter than its surroundings this lip pixel is (kept after recolouring).
                spec = max(0.0, l - luma(texture2D(uLow, vScreen).rgb)) * (0.6 + 1.2 * c.b);
            }
            if (uLipColor.a > 0.0) {
                float k = a.r * uLipColor.a;
                vec3 tint = mix(softLight(col, uLipColor.rgb), colorBlend(col, uLipColor.rgb), 0.45);
                col = mix(col, tint, 0.7 * k);
                col += vec3(spec * 0.9 + c.b * 0.05) * k;
            }
            if (uLipstick.a > 0.0) {
                float k = a.r * uLipstick.a;
                vec3 shaded = colorBlend(col, uLipstick.rgb);
                // Richer pigment: pull luminance towards the shade's own, keep the lip's shading.
                float target = mix(l, luma(uLipstick.rgb) * clamp(l / 0.45, 0.5, 1.4), 0.55);
                shaded = clamp(shaded * (target / max(luma(shaded), 0.02)), 0.0, 1.0);
                col = mix(col, shaded, 0.9 * k);
                col += vec3(spec * 0.8) * k;
            }
            if (uLiner.a > 0.0) {
                float k = a.b * uLiner.a;
                col = mix(col, uLiner.rgb * (0.85 + 0.15 * l), 0.95 * k);
            }
            if (uLash.a > 0.0) {
                float k = a.a * uLash.a;
                col = mix(col, uLash.rgb * 0.75, 0.9 * k);
            }
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    /** Reshape: deformed positions, texture coordinates at the original tracked positions. */
    const val WARP_VERTEX = """
        attribute vec3 aPosition;
        attribute vec3 aSource;
        uniform float uAspect;
        uniform float uDepthScale;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = vec2(aSource.x / uAspect, 1.0 - aSource.y);
            vec2 ndc = vec2(aPosition.x / uAspect, 1.0 - aPosition.y) * 2.0 - 1.0;
            gl_Position = vec4(ndc, clamp(aPosition.z * uDepthScale, -0.99, 0.99), 1.0);
        }
    """

    const val WARP_FRAGMENT = TC_PRECISION + """
        precision mediump float;
        varying TC vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, clamp(vTexCoord, 0.0, 1.0)); }
    """
}
