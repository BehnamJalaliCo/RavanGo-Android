package com.ravango.feature.beauty.looks

import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyFeature.CHEEKBONE
import com.ravango.core.model.BeautyFeature.CHIN
import com.ravango.core.model.BeautyFeature.DARK_CIRCLES
import com.ravango.core.model.BeautyFeature.EYE_SIZE
import com.ravango.core.model.BeautyFeature.FACE_SLIM
import com.ravango.core.model.BeautyFeature.JAW
import com.ravango.core.model.BeautyFeature.NOSE
import com.ravango.core.model.BeautyFeature.SHARPEN
import com.ravango.core.model.BeautyFeature.SKIN_BRIGHTNESS
import com.ravango.core.model.BeautyFeature.SKIN_RETOUCH
import com.ravango.core.model.BeautyFeature.BLEMISH_REMOVAL
import com.ravango.core.model.BeautyFeature.SMOOTH_SKIN
import com.ravango.core.model.BeautyFeature.WHITENING
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupFeature.BLUSH
import com.ravango.core.model.MakeupFeature.CONTOUR
import com.ravango.core.model.MakeupFeature.EYEBROW
import com.ravango.core.model.MakeupFeature.EYELASHES
import com.ravango.core.model.MakeupFeature.EYELINER
import com.ravango.core.model.MakeupFeature.EYESHADOW
import com.ravango.core.model.MakeupFeature.FOUNDATION
import com.ravango.core.model.MakeupFeature.HIGHLIGHT
import com.ravango.core.model.MakeupFeature.LIPSTICK
import com.ravango.core.model.MakeupFeature.LIP_COLOR
import com.ravango.core.model.MakeupLayer
import com.ravango.feature.beauty.R

/**
 * The built-in makeup looks (Snapchat-style one-tap lenses). Pure data: the camera carousel, the Beauty panel and
 * the thumbnails all read this list. 8 looks are free, the rest need [LookGating.FEATURE].
 *
 * Designed at 100 % intensity to be clearly visible but flattering; the look-intensity slider scales every amount.
 * Face shaping stays moderate (slim ≤ 28, eye enlarge ≤ 32) so the face still looks like the user.
 */
object LookCatalog {

    // ----------------------------------------------------------------------------------------- shared palettes
    private const val LASH_BLACK = 0xFF0E0B0BL
    private const val LASH_SOFT = 0xFF2B1B17L
    private const val LINER_BLACK = 0xFF0D0B0CL
    private const val LINER_BROWN = 0xFF3E2723L
    private const val PEARL = 0xFFFFF3EAL
    private const val CHAMPAGNE = 0xFFFFF1D6L
    private const val GOLD = 0xFFFFE0B2L

    private val softBeauty = mapOf(SMOOTH_SKIN to 46, SKIN_BRIGHTNESS to 18, DARK_CIRCLES to 38, SHARPEN to 10)
    private val glamBeauty = mapOf(
        SMOOTH_SKIN to 58, SKIN_RETOUCH to 30, BLEMISH_REMOVAL to 35, SKIN_BRIGHTNESS to 22, DARK_CIRCLES to 50,
        SHARPEN to 12, FACE_SLIM to 22, JAW to 40, CHIN to 55, NOSE to 25, EYE_SIZE to 14, CHEEKBONE to 12,
    )

    private fun m(vararg layers: Pair<MakeupFeature, Pair<Int, Long>>): Map<MakeupFeature, MakeupLayer> =
        layers.associate { (f, v) -> f to MakeupLayer(v.first, v.second) }

    private infix fun Int.of(color: Long) = this to color

    private fun look(
        id: String,
        name: Int,
        pro: Boolean,
        tags: Set<LookTag>,
        avatar: AvatarTraits,
        beauty: Map<BeautyFeature, Int>,
        makeup: Map<MakeupFeature, MakeupLayer>,
        style: LookStyle,
        eye: Pair<Long, Int>? = null,
        filter: Pair<String, Int>? = null,
    ) = LookDef(
        id = id,
        nameRes = name,
        pro = pro,
        tags = tags,
        recipe = LookRecipe(
            beauty = beauty,
            makeup = makeup,
            style = style,
            eyeColor = eye?.first,
            eyeIntensity = eye?.second ?: 0,
            filterId = filter?.first,
            filterIntensity = filter?.second ?: 0,
        ),
        avatar = avatar,
    )

    private val MAKEUP = setOf(LookTag.MAKEUP)
    private val SKIN_FIRST = setOf(LookTag.MAKEUP, LookTag.BEAUTY)

    val looks: List<LookDef> = listOf(
        look(
            "bold_glam", R.string.beauty_look_bold_glam, pro = false, tags = MAKEUP,
            avatar = AvatarTraits(0xFFD9A07E, 0xFF1C1412, HairStyle.LONG, 0xFFC9A0B8, 0xFF6E4A66, Accessory.HOOPS),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (42 of 0xFFE0B090), CONTOUR to (58 of 0xFF6D4C41), BLUSH to (36 of 0xFFE58A96),
                HIGHLIGHT to (55 of CHAMPAGNE), EYESHADOW to (72 of 0xFFA0715A), EYELINER to (85 of LINER_BLACK),
                EYELASHES to (92 of LASH_BLACK), EYEBROW to (70 of 0xFF33241D), LIPSTICK to (70 of 0xFFC27C7E),
            ),
            style = LookStyle(
                wing = 0.8f, lashVolume = 1f, lowerLiner = 0.35f, shadowAccent = 0xFF4A2C22, shadowAccentAmount = 0.7f,
                shimmerColor = 0xFFF3D3B0, shimmer = 0.25f, lipFinish = 0.1f, lipCenter = 0xFFB5646E, lipCenterAmount = 0.35f,
                browDefinition = 0.9f, skinFinish = -0.4f,
            ),
            eye = 0xFFB5853F to 38,
        ),
        look(
            "soft_glam", R.string.beauty_look_soft_glam, pro = false, tags = MAKEUP,
            avatar = AvatarTraits(0xFFE3B08E, 0xFF3A2620, HairStyle.WAVY, 0xFFF6D1D6, 0xFFD69AA8),
            beauty = mapOf(SMOOTH_SKIN to 52, SKIN_RETOUCH to 20, SKIN_BRIGHTNESS to 20, DARK_CIRCLES to 45, SHARPEN to 10,
                FACE_SLIM to 16, JAW to 44, NOSE to 18, EYE_SIZE to 10),
            makeup = m(
                FOUNDATION to (35 of 0xFFE0B99A), CONTOUR to (35 of 0xFF6D4C41), BLUSH to (38 of 0xFFF08FA5),
                HIGHLIGHT to (42 of CHAMPAGNE), EYESHADOW to (50 of 0xFFCF967F), EYELINER to (50 of LINER_BROWN),
                EYELASHES to (62 of LASH_BLACK), EYEBROW to (45 of 0xFF5D4037), LIPSTICK to (58 of 0xFFC7757A),
            ),
            style = LookStyle(
                wing = 0.55f, lashVolume = 0.35f, shadowAccent = 0xFF7E4E3E, shadowAccentAmount = 0.4f, shimmer = 0.35f,
                lipFinish = 0.3f, browDefinition = 0.35f, skinFinish = 0.15f,
            ),
        ),
        look(
            "natural", R.string.beauty_look_natural, pro = false, tags = SKIN_FIRST,
            avatar = AvatarTraits(0xFFEDC3A5, 0xFF5A3A28, HairStyle.LONG, 0xFFD6EADF, 0xFFA9CBB8),
            beauty = mapOf(SMOOTH_SKIN to 42, SKIN_BRIGHTNESS to 16, DARK_CIRCLES to 35, SHARPEN to 10, FACE_SLIM to 8),
            makeup = m(
                FOUNDATION to (22 of 0xFFE8BFA0), EYEBROW to (30 of 0xFF5D4037), LIP_COLOR to (38 of 0xFFD98C8C),
                BLUSH to (20 of 0xFFF7A1B0), EYELASHES to (32 of LASH_SOFT), HIGHLIGHT to (18 of PEARL),
            ),
            style = LookStyle(lipFinish = 0.2f, skinFinish = 0.35f),
        ),
        look(
            "persian_bridal", R.string.beauty_look_persian_bridal, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFF0CDB4, 0xFF2E1E18, HairStyle.BUN, 0xFFFFF4E4, 0xFFE9C99A, Accessory.TIARA),
            beauty = mapOf(SMOOTH_SKIN to 60, SKIN_RETOUCH to 30, BLEMISH_REMOVAL to 35, SKIN_BRIGHTNESS to 26, WHITENING to 10,
                DARK_CIRCLES to 50, FACE_SLIM to 20, JAW to 42, CHIN to 54, NOSE to 22, EYE_SIZE to 16, SHARPEN to 10),
            makeup = m(
                FOUNDATION to (40 of 0xFFEFCDB6), CONTOUR to (30 of 0xFF6D4C41), BLUSH to (40 of 0xFFF09AAE),
                HIGHLIGHT to (60 of PEARL), EYESHADOW to (55 of 0xFFE0B89A), EYELINER to (70 of 0xFF1A1214),
                EYELASHES to (85 of LASH_BLACK), EYEBROW to (55 of 0xFF3E2A22), LIPSTICK to (62 of 0xFFD9737F),
            ),
            style = LookStyle(
                wing = 0.62f, lashVolume = 0.8f, shadowAccent = 0xFFA0725E, shadowAccentAmount = 0.45f,
                shimmerColor = 0xFFFFE9CF, shimmer = 0.7f, lipFinish = 0.65f, browDefinition = 0.55f, skinFinish = 0.4f,
            ),
            filter = "pastel" to 18,
        ),
        look(
            "arabic_glam", R.string.beauty_look_arabic_glam, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFC98E6A, 0xFF120D0C, HairStyle.LONG, 0xFFE9C27A, 0xFF8A5A2B, Accessory.HOOPS),
            beauty = mapOf(SMOOTH_SKIN to 55, SKIN_RETOUCH to 25, SKIN_BRIGHTNESS to 18, DARK_CIRCLES to 50, SHARPEN to 12,
                FACE_SLIM to 26, JAW to 38, CHIN to 55, NOSE to 34, CHEEKBONE to 18, EYE_SIZE to 12),
            makeup = m(
                FOUNDATION to (42 of 0xFFD9A585), CONTOUR to (62 of 0xFF6A4632), BLUSH to (28 of 0xFFD07A5A),
                HIGHLIGHT to (55 of GOLD), EYESHADOW to (78 of 0xFFA86A3D), EYELINER to (95 of LINER_BLACK),
                EYELASHES to (92 of LASH_BLACK), EYEBROW to (82 of 0xFF231A16), LIPSTICK to (62 of 0xFFB07564),
            ),
            style = LookStyle(
                wing = 1f, lashVolume = 1f, lowerLiner = 0.85f, shadowAccent = 0xFF3B2418, shadowAccentAmount = 0.8f,
                shimmerColor = 0xFFFFD58A, shimmer = 0.45f, lipFinish = -0.7f, browDefinition = 1f, skinFinish = -0.3f,
            ),
            eye = 0xFF8FA06A to 30,
        ),
        look(
            "k_beauty", R.string.beauty_look_k_beauty, pro = false, tags = SKIN_FIRST,
            avatar = AvatarTraits(0xFFF3D5C0, 0xFF16110F, HairStyle.BOB, 0xFFF9DDE6, 0xFFD9C3F0),
            beauty = mapOf(SMOOTH_SKIN to 60, SKIN_BRIGHTNESS to 25, WHITENING to 10, DARK_CIRCLES to 40, EYE_SIZE to 22,
                FACE_SLIM to 18, CHIN to 56, NOSE to 16),
            makeup = m(
                FOUNDATION to (28 of 0xFFF3D5C0), BLUSH to (35 of 0xFFFFA3A0), HIGHLIGHT to (45 of 0xFFFCE4EC),
                EYESHADOW to (28 of 0xFFE9A8A0), EYELINER to (30 of LINER_BROWN), EYELASHES to (38 of LASH_SOFT),
                EYEBROW to (32 of 0xFF6D5A4E), LIP_COLOR to (62 of 0xFFE8736F),
            ),
            style = LookStyle(
                wing = 0.2f, lipCenter = 0xFFD0263B, lipCenterAmount = 0.75f, lipFinish = 0.7f, skinFinish = 0.8f,
                shimmerColor = 0xFFFFE4EE, shimmer = 0.3f,
            ),
            filter = "pastel" to 30,
        ),
        look(
            "latte", R.string.beauty_look_latte, pro = false, tags = MAKEUP,
            avatar = AvatarTraits(0xFFD9A585, 0xFF6B3E26, HairStyle.WAVY, 0xFFEBD9C6, 0xFFB08968),
            beauty = mapOf(SMOOTH_SKIN to 50, SKIN_BRIGHTNESS to 16, DARK_CIRCLES to 40, FACE_SLIM to 14, NOSE to 18, SHARPEN to 10),
            makeup = m(
                FOUNDATION to (35 of 0xFFDDA886), CONTOUR to (45 of 0xFF7A4E36), BLUSH to (30 of 0xFFC8805F),
                HIGHLIGHT to (38 of GOLD), EYESHADOW to (62 of 0xFF9A6446), EYELINER to (60 of LINER_BROWN),
                EYELASHES to (62 of LASH_SOFT), EYEBROW to (50 of 0xFF4A3226), LIPSTICK to (58 of 0xFFA86A55),
            ),
            style = LookStyle(
                wing = 0.6f, lashVolume = 0.4f, shadowAccent = 0xFF5A3322, shadowAccentAmount = 0.55f,
                shimmerColor = 0xFFE8B98A, shimmer = 0.3f, lipFinish = 0.35f, lipCenter = 0xFF7A4034, lipCenterAmount = 0.35f,
                browDefinition = 0.5f,
            ),
            filter = "warm" to 30,
        ),
        look(
            "clean_girl", R.string.beauty_look_clean_girl, pro = false, tags = SKIN_FIRST,
            avatar = AvatarTraits(0xFFE8BD9C, 0xFF4A2E20, HairStyle.BUN, 0xFFDDE6D3, 0xFFA8B89A, Accessory.PEARLS),
            beauty = mapOf(SMOOTH_SKIN to 48, SKIN_BRIGHTNESS to 20, DARK_CIRCLES to 42, SHARPEN to 12, FACE_SLIM to 12),
            makeup = m(
                FOUNDATION to (22 of 0xFFE8C2A6), BLUSH to (30 of 0xFFF29AA0), HIGHLIGHT to (35 of PEARL),
                EYEBROW to (42 of 0xFF5D4037), EYELASHES to (40 of LASH_SOFT), LIP_COLOR to (48 of 0xFFD9918C),
            ),
            style = LookStyle(wing = 0.2f, browDefinition = 0.45f, lipFinish = 0.9f, skinFinish = 0.6f),
        ),
        look(
            "rosy_doll", R.string.beauty_look_rosy_doll, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFF3D2C4, 0xFFB88A5A, HairStyle.WAVY, 0xFFFFD6E6, 0xFFF29BBE),
            beauty = mapOf(SMOOTH_SKIN to 60, SKIN_BRIGHTNESS to 28, WHITENING to 15, DARK_CIRCLES to 45, EYE_SIZE to 32,
                FACE_SLIM to 25, CHIN to 56, NOSE to 20),
            makeup = m(
                FOUNDATION to (32 of 0xFFF3D2C4), BLUSH to (55 of 0xFFF48FB1), HIGHLIGHT to (50 of 0xFFFCE4EC),
                EYESHADOW to (45 of 0xFFE7A3B5), EYELINER to (45 of 0xFF3A2A2E), EYELASHES to (80 of LASH_BLACK),
                EYEBROW to (35 of 0xFF7B5E4B), LIP_COLOR to (70 of 0xFFEC7FA2),
            ),
            style = LookStyle(
                wing = 0.35f, lashVolume = 0.75f, shadowAccent = 0xFFC76A8A, shadowAccentAmount = 0.35f,
                shimmerColor = 0xFFFFD6E6, shimmer = 0.55f, lipFinish = 0.85f, lipCenter = 0xFFE0457B, lipCenterAmount = 0.45f,
                skinFinish = 0.5f,
            ),
            eye = 0xFF8FA9C9 to 40,
            filter = "pastel" to 25,
        ),
        look(
            "smokey_night", R.string.beauty_look_smokey_night, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFDDAE8E, 0xFF0F0B0B, HairStyle.LONG, 0xFF3B4466, 0xFF141827),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (38 of 0xFFE0B99A), CONTOUR to (45 of 0xFF6D4C41), HIGHLIGHT to (40 of CHAMPAGNE),
                BLUSH to (20 of 0xFFC98A9A), EYESHADOW to (85 of 0xFF3A3638), EYELINER to (90 of LINER_BLACK),
                EYELASHES to (90 of LASH_BLACK), EYEBROW to (60 of 0xFF2E221D), LIPSTICK to (55 of 0xFFB27C74),
            ),
            style = LookStyle(
                wing = 0.7f, lashVolume = 0.9f, lowerLiner = 0.8f, shadowAccent = 0xFF141214, shadowAccentAmount = 0.8f,
                shimmerColor = 0xFFD8D8E0, shimmer = 0.2f, lipFinish = -0.5f, browDefinition = 0.75f, skinFinish = -0.4f,
            ),
            filter = "cinema" to 25,
        ),
        look(
            "siren_eyes", R.string.beauty_look_siren_eyes, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFCF9674, 0xFF2A1A14, HairStyle.LONG, 0xFFB65A6E, 0xFF4A1A2A),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (40 of 0xFFE3B394), CONTOUR to (50 of 0xFF6D4C41), BLUSH to (25 of 0xFFC98A8A),
                HIGHLIGHT to (45 of CHAMPAGNE), EYESHADOW to (60 of 0xFF8A5A44), EYELINER to (92 of LINER_BLACK),
                EYELASHES to (75 of LASH_BLACK), EYEBROW to (72 of 0xFF2E221D), LIPSTICK to (60 of 0xFFA66A70),
            ),
            style = LookStyle(
                wing = 1f, lashVolume = 0.6f, lowerLiner = 0.5f, shadowAccent = 0xFF4E2E24, shadowAccentAmount = 0.75f,
                lipFinish = 0.2f, browDefinition = 0.9f, skinFinish = -0.2f,
            ),
        ),
        look(
            "bronzed_summer", R.string.beauty_look_bronzed_summer, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFC68B66, 0xFF8A4B26, HairStyle.WAVY, 0xFFFFC98A, 0xFFE0714A, Accessory.HOOPS),
            beauty = mapOf(SMOOTH_SKIN to 48, SKIN_BRIGHTNESS to 18, FACE_SLIM to 16, DARK_CIRCLES to 40, SHARPEN to 12),
            makeup = m(
                FOUNDATION to (35 of 0xFFCF9A74), CONTOUR to (55 of 0xFF8D5A3B), BLUSH to (40 of 0xFFD9785A),
                HIGHLIGHT to (58 of 0xFFFFD9A0), EYESHADOW to (55 of 0xFFB9793F), EYELINER to (45 of LINER_BROWN),
                EYELASHES to (60 of LASH_BLACK), EYEBROW to (45 of 0xFF4A3226), LIPSTICK to (55 of 0xFFC0674F),
            ),
            style = LookStyle(
                wing = 0.55f, lashVolume = 0.4f, shadowAccent = 0xFF6E3F22, shadowAccentAmount = 0.45f,
                shimmerColor = 0xFFFFD08A, shimmer = 0.6f, lipFinish = 0.7f, freckles = 0.25f, skinFinish = 0.5f,
            ),
            filter = "warm" to 40,
        ),
        look(
            "peach_fresh", R.string.beauty_look_peach_fresh, pro = false, tags = SKIN_FIRST,
            avatar = AvatarTraits(0xFFF1CFB8, 0xFFC98B5E, HairStyle.BOB, 0xFFFFE0CC, 0xFFFFB199),
            beauty = mapOf(SMOOTH_SKIN to 48, SKIN_BRIGHTNESS to 22, DARK_CIRCLES to 40, EYE_SIZE to 10, FACE_SLIM to 12),
            makeup = m(
                FOUNDATION to (25 of 0xFFF0CDB5), BLUSH to (45 of 0xFFFFA285), HIGHLIGHT to (40 of 0xFFFFE9D6),
                EYESHADOW to (35 of 0xFFF3A98C), EYELINER to (30 of LINER_BROWN), EYELASHES to (45 of LASH_SOFT),
                EYEBROW to (35 of 0xFF7B5E4B), LIP_COLOR to (60 of 0xFFF4876E),
            ),
            style = LookStyle(wing = 0.4f, shimmerColor = 0xFFFFE0C8, shimmer = 0.4f, lipFinish = 0.75f, skinFinish = 0.6f),
            filter = "warm" to 15,
        ),
        look(
            "berry_lips", R.string.beauty_look_berry_lips, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFE6BA9C, 0xFF24160F, HairStyle.LONG, 0xFFE3B5C8, 0xFF7A2A4E),
            beauty = mapOf(SMOOTH_SKIN to 52, SKIN_RETOUCH to 20, SKIN_BRIGHTNESS to 18, DARK_CIRCLES to 42, SHARPEN to 10,
                FACE_SLIM to 16, JAW to 44, NOSE to 18),
            makeup = m(
                FOUNDATION to (38 of 0xFFE6BA9C), CONTOUR to (38 of 0xFF6D4C41), BLUSH to (30 of 0xFFC9798E),
                HIGHLIGHT to (35 of CHAMPAGNE), EYESHADOW to (40 of 0xFF9C7A74), EYELINER to (55 of LINER_BLACK),
                EYELASHES to (65 of LASH_BLACK), EYEBROW to (55 of 0xFF3B2A22), LIPSTICK to (88 of 0xFF8E1F4A),
            ),
            style = LookStyle(
                wing = 0.55f, lashVolume = 0.4f, lipFinish = -0.6f, lipCenter = 0xFF5A0F2E, lipCenterAmount = 0.3f,
                browDefinition = 0.6f, skinFinish = -0.3f,
            ),
        ),
        look(
            "nude_matte", R.string.beauty_look_nude_matte, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFDDA886, 0xFF3A2418, HairStyle.BUN, 0xFFEADBC8, 0xFFC3A487),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (40 of 0xFFE0B090), CONTOUR to (50 of 0xFF6D4C41), BLUSH to (25 of 0xFFC98A7E),
                HIGHLIGHT to (30 of CHAMPAGNE), EYESHADOW to (50 of 0xFFA88672), EYELINER to (55 of LASH_SOFT),
                EYELASHES to (65 of LASH_BLACK), EYEBROW to (58 of 0xFF3B2A22), LIPSTICK to (70 of 0xFFB87C68),
            ),
            style = LookStyle(
                wing = 0.55f, lashVolume = 0.45f, shadowAccent = 0xFF6B4A3C, shadowAccentAmount = 0.45f,
                lipFinish = -0.85f, browDefinition = 0.7f, skinFinish = -0.6f,
            ),
        ),
        look(
            "cool_silver", R.string.beauty_look_cool_silver, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFF1D3C0, 0xFFD8CFC4, HairStyle.LONG, 0xFFDCE6F5, 0xFF8FA3C8, Accessory.PEARLS),
            beauty = mapOf(SMOOTH_SKIN to 56, SKIN_BRIGHTNESS to 22, WHITENING to 12, DARK_CIRCLES to 45, SHARPEN to 12,
                FACE_SLIM to 20, JAW to 42, NOSE to 22, EYE_SIZE to 14),
            makeup = m(
                FOUNDATION to (32 of 0xFFF1D3C0), CONTOUR to (35 of 0xFF6B5750), BLUSH to (30 of 0xFFE8A3B8),
                HIGHLIGHT to (55 of 0xFFF3F4FF), EYESHADOW to (60 of 0xFFB8BFCC), EYELINER to (70 of 0xFF3A3E4A),
                EYELASHES to (75 of LASH_BLACK), EYEBROW to (45 of 0xFF4E4440), LIPSTICK to (55 of 0xFFD98FA8),
            ),
            style = LookStyle(
                wing = 0.65f, lashVolume = 0.6f, shadowAccent = 0xFF505866, shadowAccentAmount = 0.6f,
                shimmerColor = 0xFFEEF2FF, shimmer = 0.8f, lipFinish = 0.6f, browDefinition = 0.4f, skinFinish = 0.3f,
            ),
            eye = 0xFF8FB7D9 to 45,
            filter = "cool" to 35,
        ),
        look(
            "goth", R.string.beauty_look_goth, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFF2DDD0, 0xFF0B0909, HairStyle.BOB, 0xFF5A3A6E, 0xFF1A1020),
            beauty = mapOf(SMOOTH_SKIN to 55, SKIN_BRIGHTNESS to 20, WHITENING to 20, DARK_CIRCLES to 40, SHARPEN to 12,
                FACE_SLIM to 22, JAW to 42, NOSE to 25),
            makeup = m(
                FOUNDATION to (45 of 0xFFF2DDD0), CONTOUR to (55 of 0xFF5E4C4A), HIGHLIGHT to (30 of 0xFFF3F4FF),
                EYESHADOW to (80 of 0xFF2A1F2A), EYELINER to (95 of LINER_BLACK), EYELASHES to (90 of LASH_BLACK),
                EYEBROW to (78 of 0xFF1A1414), LIPSTICK to (92 of 0xFF3E0E22),
            ),
            style = LookStyle(
                wing = 0.9f, lashVolume = 0.85f, lowerLiner = 0.9f, shadowAccent = 0xFF120C12, shadowAccentAmount = 0.8f,
                lipFinish = -0.7f, browDefinition = 1f, skinFinish = -0.5f,
            ),
            eye = 0xFF9AA0A8 to 35,
            filter = "film" to 25,
        ),
        look(
            "sunset_eyes", R.string.beauty_look_sunset_eyes, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFCF9674, 0xFF2A1810, HairStyle.WAVY, 0xFFFFB86B, 0xFFC2407A),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (35 of 0xFFE0B090), CONTOUR to (40 of 0xFF6D4C41), BLUSH to (35 of 0xFFF08A70),
                HIGHLIGHT to (50 of GOLD), EYESHADOW to (75 of 0xFFF08A3A), EYELINER to (70 of LINER_BLACK),
                EYELASHES to (75 of LASH_BLACK), EYEBROW to (55 of 0xFF3B2A22), LIPSTICK to (50 of 0xFFC98276),
            ),
            style = LookStyle(
                wing = 0.75f, lashVolume = 0.6f, shadowAccent = 0xFFB0306A, shadowAccentAmount = 0.75f,
                shimmerColor = 0xFFFFD27A, shimmer = 0.65f, lipFinish = 0.45f, browDefinition = 0.6f,
            ),
            filter = "sunset" to 25,
        ),
        look(
            "freckled_natural", R.string.beauty_look_freckled_natural, pro = false, tags = SKIN_FIRST,
            avatar = AvatarTraits(0xFFF0CDB0, 0xFFB5582F, HairStyle.WAVY, 0xFFDDEBC8, 0xFF9DBB8A),
            beauty = mapOf(SMOOTH_SKIN to 32, SKIN_BRIGHTNESS to 14, DARK_CIRCLES to 30, SHARPEN to 12),
            makeup = m(
                FOUNDATION to (15 of 0xFFEBC6AA), BLUSH to (35 of 0xFFF09A8E), HIGHLIGHT to (22 of PEARL),
                EYEBROW to (35 of 0xFF7B5E4B), EYELASHES to (35 of LASH_SOFT), LIP_COLOR to (45 of 0xFFD98A7E),
            ),
            style = LookStyle(freckles = 0.75f, skinFinish = 0.45f, lipFinish = 0.4f, browDefinition = 0.3f),
        ),
        look(
            "hollywood_red", R.string.beauty_look_hollywood_red, pro = true, tags = MAKEUP,
            avatar = AvatarTraits(0xFFEEC9AE, 0xFFD9B77A, HairStyle.WAVY, 0xFFF7E3D0, 0xFFB3262E, Accessory.PEARLS),
            beauty = glamBeauty,
            makeup = m(
                FOUNDATION to (42 of 0xFFEBC4A8), CONTOUR to (40 of 0xFF6D4C41), BLUSH to (28 of 0xFFE58A90),
                HIGHLIGHT to (45 of CHAMPAGNE), EYESHADOW to (40 of 0xFFC9A48A), EYELINER to (90 of LINER_BLACK),
                EYELASHES to (88 of LASH_BLACK), EYEBROW to (62 of 0xFF2E221D), LIPSTICK to (92 of 0xFFC4122F),
            ),
            style = LookStyle(
                wing = 0.85f, lashVolume = 0.85f, shimmer = 0.35f, lipFinish = -0.4f, browDefinition = 0.75f, skinFinish = -0.4f,
            ),
            filter = "film" to 20,
        ),
    )

    private val byId: Map<String, LookDef> = looks.associateBy { it.id }

    fun find(id: String?): LookDef? = id?.let { byId[it] }

    /** Curated order for the camera's «برای تو / For you» category (looks only; lenses are mixed in by the camera). */
    val featured: List<String> = listOf(
        "bold_glam", "soft_glam", "persian_bridal", "k_beauty", "latte", "arabic_glam", "clean_girl", "siren_eyes", "rosy_doll",
    )

    fun tagged(tag: LookTag): List<LookDef> = looks.filter { tag in it.tags }
}
