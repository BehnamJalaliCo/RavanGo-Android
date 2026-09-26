package com.ravango.feature.paywall

import androidx.annotation.StringRes
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.model.ProFeature
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Formats store micros in the store's currency for the current locale (used for "per month" equivalents). */
internal fun formatMicros(micros: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    runCatching { format.currency = Currency.getInstance(currencyCode) }
    format.maximumFractionDigits = if (micros % 1_000_000 == 0L) 0 else 2
    return format.format(micros / 1_000_000.0).localizeDigits(locale)
}

@StringRes
internal fun ProFeature.headlineRes(): Int = when (this) {
    ProFeature.RECORD_4K -> R.string.paywall_feature_record_4k
    ProFeature.RECORD_HIGH_FPS -> R.string.paywall_feature_record_high_fps
    ProFeature.RECORD_HEVC -> R.string.paywall_feature_record_hevc
    ProFeature.MANUAL_CAMERA_PRO -> R.string.paywall_feature_manual_camera
    ProFeature.ADVANCED_BEAUTY -> R.string.paywall_feature_advanced_beauty
    ProFeature.FACE_RESHAPE -> R.string.paywall_feature_face_reshape
    ProFeature.MAKEUP -> R.string.paywall_feature_makeup
    ProFeature.FLOATING_PROMPTER -> R.string.paywall_feature_floating_prompter
    ProFeature.EDITOR_MULTI_LAYER -> R.string.paywall_feature_multi_layer
    ProFeature.EDITOR_PIP -> R.string.paywall_feature_pip
    ProFeature.EDITOR_ADVANCED_COLOR -> R.string.paywall_feature_advanced_color
    ProFeature.EDITOR_REVERSE -> R.string.paywall_feature_reverse
    ProFeature.AUDIO_NOISE_REDUCTION -> R.string.paywall_feature_noise_reduction
    ProFeature.EXPORT_4K -> R.string.paywall_feature_export_4k
    ProFeature.EXPORT_60FPS -> R.string.paywall_feature_export_60fps
    ProFeature.EXPORT_NO_WATERMARK -> R.string.paywall_feature_no_watermark
    ProFeature.AUTO_CAPTIONS -> R.string.paywall_feature_auto_captions
    ProFeature.AI_VIDEO_TOOLS -> R.string.paywall_feature_ai_video
    ProFeature.CLOUD_PROJECT_SYNC -> R.string.paywall_feature_project_sync
    ProFeature.CLOUD_MEDIA_BACKUP -> R.string.paywall_feature_media_backup
    ProFeature.UNLIMITED_PRESETS -> R.string.paywall_feature_unlimited_presets
}

/** Which benefit row a feature belongs to (highlighted and moved first on the paywall). */
internal enum class Benefit { CAPTURE, BEAUTY, PROMPTER, EDITOR, AUDIO, EXPORT, AI, CLOUD, PRESETS }

internal fun ProFeature.benefit(): Benefit = when (this) {
    ProFeature.RECORD_4K, ProFeature.RECORD_HIGH_FPS, ProFeature.RECORD_HEVC, ProFeature.MANUAL_CAMERA_PRO -> Benefit.CAPTURE
    ProFeature.ADVANCED_BEAUTY, ProFeature.FACE_RESHAPE, ProFeature.MAKEUP -> Benefit.BEAUTY
    ProFeature.FLOATING_PROMPTER -> Benefit.PROMPTER
    ProFeature.EDITOR_MULTI_LAYER, ProFeature.EDITOR_PIP, ProFeature.EDITOR_ADVANCED_COLOR, ProFeature.EDITOR_REVERSE -> Benefit.EDITOR
    ProFeature.AUDIO_NOISE_REDUCTION -> Benefit.AUDIO
    ProFeature.EXPORT_4K, ProFeature.EXPORT_60FPS, ProFeature.EXPORT_NO_WATERMARK -> Benefit.EXPORT
    ProFeature.AUTO_CAPTIONS, ProFeature.AI_VIDEO_TOOLS -> Benefit.AI
    ProFeature.CLOUD_PROJECT_SYNC, ProFeature.CLOUD_MEDIA_BACKUP -> Benefit.CLOUD
    ProFeature.UNLIMITED_PRESETS -> Benefit.PRESETS
}
