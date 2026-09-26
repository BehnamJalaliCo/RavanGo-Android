package com.ravango.platform.cloud.settings

import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AiPreferences
import com.ravango.core.model.AudioSettings
import com.ravango.core.model.BeautyState
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.Clock
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.UserPreferences
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.cloud.remote.OutgoingRow
import com.ravango.platform.cloud.remote.PostgrestClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Everything synced in the per-user `user_settings` row. */
@Serializable
data class SettingsPayload(
    val preferences: UserPreferences = UserPreferences(),
    val prompter: TeleprompterSettings = TeleprompterSettings(),
    val camera: CameraSettings = CameraSettings(),
    val audio: AudioSettings = AudioSettings(),
    val beauty: BeautyState = BeautyState(),
)

/**
 * Device-local preferences never leave the device: consents (asked per device), sync switches, onboarding state
 * and AI provider/keys configuration (keys live only in this device's Keystore). Only the default tone is shared.
 */
fun UserPreferences.portable(): UserPreferences {
    val defaults = UserPreferences()
    return copy(
        onboardingCompleted = defaults.onboardingCompleted,
        analyticsConsent = defaults.analyticsConsent,
        crashReportsConsent = defaults.crashReportsConsent,
        cloudSyncEnabled = defaults.cloudSyncEnabled,
        syncOverWifiOnly = defaults.syncOverWifiOnly,
        backupMedia = defaults.backupMedia,
        ai = AiPreferences(defaultTone = ai.defaultTone),
        updatedAt = 0,
    )
}

/** Applies synced values from [remote] while keeping this device's local-only fields from `this`. */
fun UserPreferences.mergeRemote(remote: UserPreferences): UserPreferences = remote.copy(
    onboardingCompleted = onboardingCompleted,
    analyticsConsent = analyticsConsent,
    crashReportsConsent = crashReportsConsent,
    cloudSyncEnabled = cloudSyncEnabled,
    syncOverWifiOnly = syncOverWifiOnly,
    backupMedia = backupMedia,
    ai = ai.copy(defaultTone = remote.ai.defaultTone),
    updatedAt = updatedAt,
)

fun SettingsPayload.portable(): SettingsPayload = copy(preferences = preferences.portable())

fun SettingsPayload.contentHash(): String {
    val json = SupabaseJson.encodeToString(SettingsPayload.serializer(), portable())
    return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Syncs preferences + prompter defaults + camera/audio settings + beauty state as one row (`user_settings`,
 * id = user id). Local edits are detected by hashing the portable payload against the hash of the last synced
 * version; conflicts are resolved last-writer-wins on the time the local change was first observed.
 */
@Singleton
class SettingsSync @Inject constructor(
    private val preferences: PreferencesDataSource,
    private val postgrest: PostgrestClient,
    private val clock: Clock,
) {

    val snapshots: Flow<SettingsPayload> = combine(
        preferences.userPreferences,
        preferences.prompterDefaults,
        preferences.cameraSettings,
        preferences.audioSettings,
        preferences.beautyState,
    ) { p, t, c, a, b -> SettingsPayload(p, t, c, a, b).portable() }.distinctUntilChanged()

    /** Records when a local change was first seen; returns true when settings have unsynced changes. */
    suspend fun onLocalSnapshot(payload: SettingsPayload): Boolean {
        val pending = payload.contentHash() != preferences.observeString(KEY_SYNCED_HASH).first()
        if (pending && preferences.observeString(KEY_CHANGED_AT).first() == null) {
            preferences.putString(KEY_CHANGED_AT, clock.now().toString())
        }
        return pending
    }

    suspend fun hasPendingChanges(): Boolean = snapshots.first().contentHash() != preferences.observeString(KEY_SYNCED_HASH).first()

    suspend fun sync(userId: String, accessToken: String) {
        val local = snapshots.first()
        val localHash = local.contentHash()
        val syncedHash = preferences.observeString(KEY_SYNCED_HASH).first()
        val pending = localHash != syncedHash
        val changedAt = preferences.observeString(KEY_CHANGED_AT).first()?.toLongOrNull() ?: clock.now()

        val remoteRow = postgrest.getRow(TABLE, userId, accessToken)
        val remote = remoteRow?.let { row -> runCatching { SupabaseJson.decodeFromJsonElement(SettingsPayload.serializer(), row.data).portable() }.getOrNull() }
        if (remoteRow != null && remote != null) {
            val remoteHash = remote.contentHash()
            if (remoteHash == localHash) {
                markSynced(localHash)
                return
            }
            if (!pending || remoteRow.updatedAt > changedAt) {
                // Record the hash first so the observer does not treat the applied values as a local edit.
                markSynced(remoteHash)
                apply(remote)
                return
            }
        }
        if (!pending && remoteRow != null) return
        val data = SupabaseJson.encodeToJsonElement(SettingsPayload.serializer(), local).jsonObject
        postgrest.upsert(TABLE, userId, listOf(OutgoingRow(userId, if (pending) changedAt else clock.now(), null, data)), accessToken)
        markSynced(localHash)
    }

    private suspend fun apply(remote: SettingsPayload) {
        preferences.updateUserPreferences { it.mergeRemote(remote.preferences) }
        preferences.updatePrompterDefaults { remote.prompter }
        preferences.updateCameraSettings { remote.camera }
        preferences.updateAudioSettings { remote.audio }
        preferences.updateBeautyState { remote.beauty }
    }

    private suspend fun markSynced(hash: String) {
        preferences.putString(KEY_SYNCED_HASH, hash)
        preferences.putString(KEY_CHANGED_AT, null)
    }

    /** Forgets sync bookkeeping (sign-out / account switch). */
    suspend fun reset() {
        preferences.putString(KEY_SYNCED_HASH, null)
        preferences.putString(KEY_CHANGED_AT, null)
    }

    companion object {
        const val TABLE = "user_settings"
        private const val KEY_SYNCED_HASH = "cloud.settings.syncedHash"
        private const val KEY_CHANGED_AT = "cloud.settings.changedAt"
    }
}
