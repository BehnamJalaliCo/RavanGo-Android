package com.ravango.platform.cloud

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.AiPreferences
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ThemeMode
import com.ravango.core.model.UserPreferences
import com.ravango.platform.cloud.settings.SettingsPayload
import com.ravango.platform.cloud.settings.contentHash
import com.ravango.platform.cloud.settings.mergeRemote
import com.ravango.platform.cloud.settings.portable
import org.junit.Test

class SettingsPayloadTest {

    private val device = UserPreferences(
        language = AppLanguage.PERSIAN,
        themeMode = ThemeMode.DARK,
        onboardingCompleted = true,
        analyticsConsent = true,
        cloudSyncEnabled = true,
        syncOverWifiOnly = false,
        backupMedia = true,
        ai = AiPreferences(textProvider = AiProviderId.ANTHROPIC_DIRECT, cloudProcessingConsent = true, defaultTone = "calm", customModel = "m"),
        updatedAt = 99,
    )

    @Test
    fun `device-local fields are not part of the synced payload`() {
        val portable = device.portable()
        assertThat(portable.cloudSyncEnabled).isFalse()
        assertThat(portable.analyticsConsent).isFalse()
        assertThat(portable.onboardingCompleted).isFalse()
        assertThat(portable.ai).isEqualTo(AiPreferences(defaultTone = "calm"))
        assertThat(portable.themeMode).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun `local-only changes do not change the hash, synced changes do`() {
        val base = SettingsPayload(preferences = device)
        val consentChanged = SettingsPayload(preferences = device.copy(analyticsConsent = false, backupMedia = false))
        val themeChanged = SettingsPayload(preferences = device.copy(themeMode = ThemeMode.LIGHT))
        assertThat(consentChanged.contentHash()).isEqualTo(base.contentHash())
        assertThat(themeChanged.contentHash()).isNotEqualTo(base.contentHash())
    }

    @Test
    fun `merging remote keeps this device's local fields and round-trips the hash`() {
        val remote = UserPreferences(language = AppLanguage.ENGLISH, themeMode = ThemeMode.LIGHT, ai = AiPreferences(defaultTone = "energetic")).portable()
        val merged = device.mergeRemote(remote)
        assertThat(merged.language).isEqualTo(AppLanguage.ENGLISH)
        assertThat(merged.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(merged.cloudSyncEnabled).isTrue()
        assertThat(merged.backupMedia).isTrue()
        assertThat(merged.ai.textProvider).isEqualTo(AiProviderId.ANTHROPIC_DIRECT)
        assertThat(merged.ai.cloudProcessingConsent).isTrue()
        assertThat(merged.ai.defaultTone).isEqualTo("energetic")
        // After applying, the local snapshot hashes exactly like the remote one (no echo push).
        assertThat(SettingsPayload(preferences = merged).contentHash()).isEqualTo(SettingsPayload(preferences = remote).contentHash())
    }
}
