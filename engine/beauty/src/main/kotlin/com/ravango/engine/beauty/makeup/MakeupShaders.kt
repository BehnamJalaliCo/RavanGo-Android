package com.ravango.engine.beauty.makeup

/**
 * ADDED — the Face Mask makeup shader with look styles (supersedes `BeautyShaders.MAKEUP`; same inputs plus the
 * look atlas D/E and the [MakeupStyle] uniforms). GLSL ES 1.00, GLES 2 compatible, 8 texture units.
 *
 * Layer order: foundation → skin finish (matte / dewy) → contour → blush → freckles → highlight → eyeshadow →
 * second shadow tone (outer V + crease) → lid shimmer (+ fine glitter) → brows (soft … sculpted) → lip tint →
 * lipstick (ombré centre, matte … gloss finish) → eyeliner (tight … classic … dramatic cat-eye) → lower kohl →
 * lashes (natural … volume clusters).
 *
 * Every style term is multiplied by a uniform that is 0 until the look atlas is uploaded ([uStyleOn]), so the
 * shader renders exactly the classic makeup when no look style is active.
 */
internal object MakeupShaders {

    const val MAKEUP = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        #define TC highp
        #else
        #define TC mediump
        #endif
        precision mediump float;
        varying TC vec2 vUv;
        varying TC vec2 vScreen;
        uniform sampler2D uFrame;
        uniform sampler2D uLow;
        uniform sampler2D uSkin;
        uniform sampler2D uMakeupA;
        uniform sampler2D uMakeupB;
        uniform sampler2D uMakeupC;
        uniform sampler2D uMakeupD;
        uniform sampler2D uMakeupE;
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
        uniform float uStyleOn;
        // x: classic-wing cut (1 = tight line) · y: dramatic cat-eye · z: lash volume · w: lower kohl (× presence)
        uniform vec4 uStyleA;
        // x: brow definition · y: lip finish (−1 matte … +1 gloss) · z: skin finish (−1 matte … +1 dewy) · w: freckles
        uniform vec4 uStyleB;
        uniform vec4 uShadow2;
        uniform vec4 uShimmer;
        uniform vec4 uLipCenter;

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        vec3 softLight(vec3 b, vec3 s) {
            vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
            vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
            return mix(lo, hi, step(0.5, s));
        }

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

        /** Cheap static hash in UV space (fine glitter that sticks to the lid). */
        float glitter(TC vec2 uv) {
            TC vec2 p = fract(floor(uv * 640.0) * vec2(0.1031, 0.1030));
            p += dot(p, p.yx + 33.33);
            return fract((p.x + p.y) * p.x);
        }

        void main() {
            vec3 base = texture2D(uFrame, vScreen).rgb;
            vec3 col = base;
            vec4 a = texture2D(uMakeupA, vUv);
            vec4 b = texture2D(uMakeupB, vUv);
            vec4 c = texture2D(uMakeupC, vUv);
            vec4 d = vec4(0.0);
            vec4 e = vec4(0.0);
            if (uStyleOn > 0.5) {
                d = texture2D(uMakeupD, vUv);
                e = texture2D(uMakeupE, vUv);
            }
            float gate = 0.35 + 0.65 * texture2D(uSkin, vScreen).r;
            float skinRegion = c.r;
            vec3 low = texture2D(uLow, vScreen).rgb;

            if (uFoundation.a > 0.0) {
                float k = uFoundation.a * skinRegion * gate;
                vec3 even = mix(col, low, 0.3);
                vec3 tinted = colorBlend(even, mix(even, uFoundation.rgb, 0.65));
                tinted = mix(tinted, tinted * (luma(uFoundation.rgb) / max(luma(tinted), 0.05)), 0.25);
                col = mix(col, clamp(tinted, 0.0, 1.0), 0.8 * k);
            }
            if (uStyleB.z > 0.0) {
                // Dewy / glass skin: lift the highlights on the skin, a touch of cool-white sheen.
                float k = uStyleB.z * skinRegion * gate;
                float hl = smoothstep(0.35, 0.85, luma(col));
                col += (vec3(1.0) - col) * (0.3 * hl * k);
                col = mix(col, col * vec3(1.02, 1.01, 1.03), 0.5 * k);
            } else if (uStyleB.z < 0.0) {
                // Soft matte: pull shiny spots down towards their surroundings.
                float k = -uStyleB.z * skinRegion * gate;
                float shine = max(luma(col) - luma(low) - 0.02, 0.0);
                col -= vec3(shine) * (0.8 * k);
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
            if (uStyleB.w > 0.0) {
                float k = e.a * uStyleB.w * gate;
                col = mix(col, col * vec3(0.7, 0.5, 0.38), 0.8 * k);
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
            if (uShadow2.a > 0.0) {
                float k = e.r * uShadow2.a;
                vec3 m = col * normalizedTint(uShadow2.rgb) * 0.8;
                col = mix(col, mix(m, softLight(col, uShadow2.rgb), 0.3), 0.9 * k);
            }
            if (uShimmer.a > 0.0) {
                float k = e.g * uShimmer.a;
                col = 1.0 - (1.0 - col) * (1.0 - uShimmer.rgb * (0.42 * k));
                float g = step(0.965, glitter(vUv)) * k;
                col += uShimmer.rgb * (0.45 * g);
            }
            if (uBrow.a > 0.0) {
                float def = uStyleB.x;
                float g = mix(a.g, smoothstep(0.08, 0.42, a.g), def);
                float k = g * uBrow.a;
                vec3 target = min(col, colorBlend(col * mix(0.82, 0.7, def), uBrow.rgb) * 0.9);
                target = mix(target, uBrow.rgb, (0.3 + 0.25 * def) * g);
                col = mix(col, target, k);
            }
            float l = luma(col);
            float spec = 0.0;
            float finish = uStyleB.y;
            if (uLipColor.a > 0.0 || uLipstick.a > 0.0) {
                spec = max(0.0, l - luma(low)) * (0.6 + 1.2 * c.b);
                spec *= finish < 0.0 ? 1.0 + 0.85 * finish : 1.0 + 1.3 * finish;
            }
            float center = e.b * uLipCenter.a;
            if (uLipColor.a > 0.0) {
                float k = a.r * uLipColor.a;
                vec3 lc = mix(uLipColor.rgb, uLipCenter.rgb, center);
                vec3 tint = mix(softLight(col, lc), colorBlend(col, lc), 0.45);
                col = mix(col, tint, 0.7 * k);
                col += vec3(spec * 0.9 + c.b * 0.05) * k;
            }
            if (uLipstick.a > 0.0) {
                float k = a.r * uLipstick.a;
                vec3 lc = mix(uLipstick.rgb, uLipCenter.rgb, center);
                vec3 shaded = colorBlend(col, lc);
                float target = mix(l, luma(lc) * clamp(l / 0.45, 0.5, 1.4), 0.55);
                shaded = clamp(shaded * (target / max(luma(shaded), 0.02)), 0.0, 1.0);
                col = mix(col, shaded, 0.9 * k);
                col += vec3(spec * 0.8) * k;
            }
            if (finish > 0.0 && (uLipColor.a > 0.0 || uLipstick.a > 0.0)) {
                // Gloss: a wet highlight on the fullest part of the lower lip.
                float k = a.r * max(uLipColor.a, uLipstick.a) * finish;
                col += vec3(0.22 * c.b * k);
            }
            if (uLiner.a > 0.0) {
                float liner = max(a.b - d.r * uStyleA.x, 0.0);
                liner = max(liner, d.g * uStyleA.y);
                float k = liner * uLiner.a;
                col = mix(col, uLiner.rgb * (0.85 + 0.15 * l), 0.95 * k);
            }
            if (uStyleA.w > 0.0) {
                vec3 kc = uLiner.a > 0.0 ? uLiner.rgb : uLash.rgb;
                col = mix(col, kc * (0.85 + 0.15 * l), 0.9 * d.a * uStyleA.w);
            }
            if (uLash.a > 0.0) {
                float lash = max(a.a, d.b * uStyleA.z);
                float k = lash * uLash.a;
                col = mix(col, uLash.rgb * 0.75, 0.92 * k);
            }
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
