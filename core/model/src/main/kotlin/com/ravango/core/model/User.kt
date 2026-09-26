package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppLanguage(val tag: String?) { PERSIAN("fa"), ENGLISH("en"), SYSTEM(null) }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AuthProviderType { EMAIL_OTP, EMAIL_PASSWORD, PHONE_OTP, GOOGLE }

@Serializable
data class UserAccount(
    val id: String,
    val email: String? = null,
    val phone: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val provider: AuthProviderType,
    val createdAt: Long = 0,
)

/** App-wide preferences (stored locally, synced when the user enables cloud sync). */
@Serializable
data class UserPreferences(
    val language: AppLanguage = AppLanguage.PERSIAN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
    val onboardingCompleted: Boolean = false,
    /** Opt-in only. Nothing is sent before the user explicitly agrees. */
    val analyticsConsent: Boolean = false,
    val crashReportsConsent: Boolean = false,
    val cloudSyncEnabled: Boolean = false,
    val syncOverWifiOnly: Boolean = true,
    val backupMedia: Boolean = false,
    val saveToGallery: Boolean = true,
    val keepScreenOnWhilePrompting: Boolean = true,
    val defaultExport: ExportSettings = ExportSettings(),
    val ai: AiPreferences = AiPreferences(),
    val updatedAt: Long = 0,
)

@Serializable
enum class AiProviderId { RAVANGO_GATEWAY, ANTHROPIC_DIRECT, OPENAI_COMPATIBLE }

@Serializable
enum class SpeechProviderId { RAVANGO_GATEWAY, OPENAI_WHISPER, ANDROID_ON_DEVICE }

@Serializable
data class AiPreferences(
    val textProvider: AiProviderId = AiProviderId.RAVANGO_GATEWAY,
    val speechProvider: SpeechProviderId = SpeechProviderId.RAVANGO_GATEWAY,
    /** User consent to send script text / audio to the configured AI provider. */
    val cloudProcessingConsent: Boolean = false,
    val defaultTone: String = "friendly",
    val customBaseUrl: String? = null,
    val customModel: String? = null,
)
