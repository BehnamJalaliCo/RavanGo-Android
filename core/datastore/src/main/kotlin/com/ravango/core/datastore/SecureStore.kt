package com.ravango.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small secret storage (auth refresh tokens, user-provided API keys) encrypted with an AES-256-GCM key held in
 * the Android Keystore. Values never leave the device and are excluded from backups (see backup rules).
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("ravango_secure", Context.MODE_PRIVATE)

    private val key: SecretKey by lazy { loadOrCreateKey() }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }.onFailure { RgLog.e("SecureStore", "encrypt failed", it) }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_SIZE)
            val data = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.onFailure {
            // Key invalidated (e.g. device restore). Drop the unreadable value.
            RgLog.w("SecureStore", "decrypt failed for $name", it)
            prefs.edit().remove(name).apply()
        }.getOrNull()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ravango_secure_store_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
