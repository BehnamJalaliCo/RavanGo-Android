package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Plan { FREE, PRO, LIFETIME }

/** Capabilities that may be gated by plan. The mapping plan -> features lives in the billing module's config. */
@Serializable
enum class ProFeature {
    RECORD_4K,
    RECORD_HIGH_FPS,
    RECORD_HEVC,
    MANUAL_CAMERA_PRO,
    ADVANCED_BEAUTY,
    FACE_RESHAPE,
    MAKEUP,
    FLOATING_PROMPTER,
    EDITOR_MULTI_LAYER,
    EDITOR_PIP,
    EDITOR_ADVANCED_COLOR,
    EDITOR_REVERSE,
    AUDIO_NOISE_REDUCTION,
    EXPORT_4K,
    EXPORT_60FPS,
    EXPORT_NO_WATERMARK,
    AUTO_CAPTIONS,
    AI_VIDEO_TOOLS,
    CLOUD_PROJECT_SYNC,
    CLOUD_MEDIA_BACKUP,
    UNLIMITED_PRESETS,
}

/** The user's effective rights right now. Computed from purchases + remote config; never trusted from the UI. */
@Serializable
data class Entitlements(
    val plan: Plan = Plan.FREE,
    val features: Set<ProFeature> = emptySet(),
    val aiCreditsPerMonth: Int = 20,
    val aiCreditsRemaining: Int = 20,
    val cloudQuotaBytes: Long = 50L * 1024 * 1024,
    val maxRecordShortSide: Int = 1080,
    val maxRecordFps: Int = 30,
    val maxExportShortSide: Int = 1080,
    val maxExportFps: Int = 30,
    val maxSavedPresets: Int = 3,
    val watermarkOnExport: Boolean = true,
    val isTrial: Boolean = false,
    val expiresAt: Long? = null,
) {
    fun has(feature: ProFeature): Boolean = feature in features
    val isPaid: Boolean get() = plan != Plan.FREE
}

/** AI operations and their credit cost (1 credit ≈ one short text generation). */
@Serializable
enum class AiOperation(val credits: Int) {
    TEXT_SMALL(1),
    TEXT_LARGE(3),
    TRANSCRIBE_PER_MINUTE(2),
    VIDEO_ANALYSIS(5),
}
