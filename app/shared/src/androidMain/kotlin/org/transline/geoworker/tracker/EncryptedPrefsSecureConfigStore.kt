package org.transline.geoworker.tracker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android [SecureConfigStore] backed by EncryptedSharedPreferences (MasterKey / Android Keystore).
 * Soft-fails crypto/read errors to null to avoid crash after backup restore without key.
 */
class EncryptedPrefsSecureConfigStore(context: Context) : SecureConfigStore {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    override fun load(): SecureConfig? {
        val raw = try {
            prefs?.getString(KEY_SECURE_CONFIG, null)
        } catch (_: Exception) {
            null
        } ?: return null
        return SecureConfigJson.decode(raw)
    }

    override fun save(config: SecureConfig) {
        val p = prefs ?: return
        try {
            p.edit().putString(KEY_SECURE_CONFIG, SecureConfigJson.encode(config)).apply()
        } catch (_: Exception) {
            // Soft-fail: do not crash host on crypto errors
        }
    }

    override fun clear() {
        val p = prefs ?: return
        try {
            p.edit().remove(KEY_SECURE_CONFIG).apply()
        } catch (_: Exception) {
            // Soft-fail
        }
    }

    companion object {
        const val PREFS_FILE = "geoworker_secure_prefs"
        const val KEY_SECURE_CONFIG = "secure_config"
    }
}
