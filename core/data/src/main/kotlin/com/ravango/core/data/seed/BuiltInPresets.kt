package com.ravango.core.data.seed

import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.PrompterFont
import com.ravango.core.model.PrompterPlacement
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.model.SubtitleAnimation
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings

/**
 * Built-in content. Names are resource keys resolved by the UI ("preset_*", "template_*") so they are localized;
 * ids are stable so user references survive app updates.
 */
object BuiltInPresets {

    val prompter: List<TeleprompterPreset> = listOf(
        TeleprompterPreset("builtin-prompter-standard", "preset_prompter_standard", TeleprompterSettings(), builtIn = true),
        TeleprompterPreset(
            "builtin-prompter-large", "preset_prompter_large",
            TeleprompterSettings(wordsPerMinute = 110, fontSizeSp = 48f, fontWeight = 600, lineSpacing = 1.6f), builtIn = true,
        ),
        TeleprompterPreset(
            "builtin-prompter-fast", "preset_prompter_fast",
            TeleprompterSettings(wordsPerMinute = 190, fontSizeSp = 28f, lineSpacing = 1.35f), builtIn = true,
        ),
        TeleprompterPreset(
            "builtin-prompter-camera", "preset_prompter_camera",
            TeleprompterSettings(fontSizeSp = 30f, backgroundOpacity = 0.35f, areaHeightFraction = 0.34f, placement = PrompterPlacement.TOP, eyeLinePosition = 0.2f), builtIn = true,
        ),
        TeleprompterPreset(
            "builtin-prompter-mirror", "preset_prompter_mirror",
            TeleprompterSettings(mirrorHorizontal = true, fontSizeSp = 44f, backgroundOpacity = 1f, textColor = 0xFFFFFFFF), builtIn = true,
        ),
        TeleprompterPreset(
            "builtin-prompter-night", "preset_prompter_night",
            TeleprompterSettings(textColor = 0xFFFFD9A0, backgroundOpacity = 0.9f, font = PrompterFont.SAHEL, fontSizeSp = 36f), builtIn = true,
        ),
    )

    val beauty: List<BeautyPreset> = listOf(
        BeautyPreset("builtin-beauty-natural", "preset_beauty_natural", BeautyState(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 30, BeautyFeature.SKIN_BRIGHTNESS to 12, BeautyFeature.SHARPEN to 12)), builtIn = true),
        BeautyPreset(
            "builtin-beauty-glow", "preset_beauty_glow",
            BeautyState(
                beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 50, BeautyFeature.SKIN_BRIGHTNESS to 30, BeautyFeature.WHITENING to 15, BeautyFeature.DARK_CIRCLES to 35, BeautyFeature.SHARPEN to 10),
                makeup = mapOf(MakeupFeature.BLUSH to MakeupLayer(25, 0xFFF8A5B8), MakeupFeature.HIGHLIGHT to MakeupLayer(30, 0xFFFFF3E0)),
            ),
            builtIn = true,
        ),
        BeautyPreset(
            "builtin-beauty-studio", "preset_beauty_studio",
            BeautyState(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 40, BeautyFeature.BLEMISH_REMOVAL to 50, BeautyFeature.SKIN_RETOUCH to 35, BeautyFeature.SHARPEN to 20, BeautyFeature.DARK_CIRCLES to 40, BeautyFeature.TEETH_WHITENING to 30)),
            builtIn = true,
        ),
        BeautyPreset(
            "builtin-beauty-business", "preset_beauty_business",
            BeautyState(beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 25, BeautyFeature.BLEMISH_REMOVAL to 40, BeautyFeature.DARK_CIRCLES to 30, BeautyFeature.SHARPEN to 15, BeautyFeature.SKIN_TONE to 52)),
            builtIn = true,
        ),
        BeautyPreset(
            "builtin-beauty-glam", "preset_beauty_glam",
            BeautyState(
                beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 55, BeautyFeature.SKIN_BRIGHTNESS to 25, BeautyFeature.EYE_SIZE to 20, BeautyFeature.FACE_SLIM to 20, BeautyFeature.CHEEKBONE to 15),
                makeup = mapOf(
                    MakeupFeature.LIPSTICK to MakeupLayer(55, 0xFFB0204E),
                    MakeupFeature.EYELINER to MakeupLayer(45, 0xFF111111),
                    MakeupFeature.EYELASHES to MakeupLayer(50, 0xFF111111),
                    MakeupFeature.EYESHADOW to MakeupLayer(30, 0xFF9C6B5E),
                    MakeupFeature.BLUSH to MakeupLayer(30, 0xFFF48FB1),
                    MakeupFeature.CONTOUR to MakeupLayer(25, 0xFF6D4C41),
                    MakeupFeature.HIGHLIGHT to MakeupLayer(30, 0xFFFFF3E0),
                ),
            ),
            builtIn = true,
        ),
    )

    val templates: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "tpl-reel", nameKey = "template_reel", descriptionKey = "template_reel_desc",
            aspectRatio = AspectRatioSpec.Portrait9x16, suggestedDurationSec = 45,
            scriptOutline = "## Hook\n\n## Value\n\n## Call to action\n",
            subtitleStyle = SubtitleStyle(animation = SubtitleAnimation.WORD_BY_WORD, sizeSp = 30f, positionY = 0.68f), accentArgb = 0xFFFF93AF,
        ),
        ProjectTemplate(
            id = "tpl-youtube", nameKey = "template_youtube", descriptionKey = "template_youtube_desc",
            aspectRatio = AspectRatioSpec.Landscape16x9, suggestedDurationSec = 480,
            scriptOutline = "## Intro\n\n## Main point 1\n\n## Main point 2\n\n## Main point 3\n\n## Outro\n",
            subtitleStyle = SubtitleStyle(sizeSp = 22f, positionY = 0.88f), autoCaptions = false, accentArgb = 0xFFF7718F,
        ),
        ProjectTemplate(
            id = "tpl-short", nameKey = "template_short", descriptionKey = "template_short_desc",
            aspectRatio = AspectRatioSpec.Portrait9x16, suggestedDurationSec = 30, cameraFrameRate = 30,
            scriptOutline = "## Hook (3s)\n\n## Payoff\n\n## Loop back\n",
            subtitleStyle = SubtitleStyle(animation = SubtitleAnimation.KARAOKE, sizeSp = 32f, positionY = 0.62f), accentArgb = 0xFFFFAE7A,
        ),
        ProjectTemplate(
            id = "tpl-linkedin", nameKey = "template_linkedin", descriptionKey = "template_linkedin_desc",
            aspectRatio = AspectRatioSpec.Portrait4x5, suggestedDurationSec = 90,
            scriptOutline = "## Insight\n\n## Story\n\n## Takeaway\n", accentArgb = 0xFF7DB8FF,
        ),
        ProjectTemplate(
            id = "tpl-product", nameKey = "template_product", descriptionKey = "template_product_desc",
            aspectRatio = AspectRatioSpec.Square1x1, suggestedDurationSec = 60,
            scriptOutline = "## Problem\n\n## Product\n\n## Benefits\n\n## Offer\n", accentArgb = 0xFF6FD9C0,
        ),
        ProjectTemplate(
            id = "tpl-tutorial", nameKey = "template_tutorial", descriptionKey = "template_tutorial_desc",
            aspectRatio = AspectRatioSpec.Landscape16x9, suggestedDurationSec = 300,
            scriptOutline = "## What you'll learn\n\n## Step 1\n\n## Step 2\n\n## Step 3\n\n## Recap\n", autoCaptions = true, accentArgb = 0xFFA394FB,
        ),
        ProjectTemplate(
            id = "tpl-podcast", nameKey = "template_podcast", descriptionKey = "template_podcast_desc",
            aspectRatio = AspectRatioSpec.Square1x1, suggestedDurationSec = 1200,
            scriptOutline = "## Opening\n\n## Topic\n\n## Guest questions\n\n## Closing\n", autoCaptions = false, accentArgb = 0xFFFFD166,
        ),
    )
}
