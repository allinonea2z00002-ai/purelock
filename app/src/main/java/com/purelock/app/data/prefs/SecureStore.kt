package com.purelock.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted storage for the optional trusted contact. Never leaves the device. */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "purelock_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore problems must not crash the app; fall back to private (non-encrypted) storage
        // and keep working. The contact is still only readable by this app.
        context.getSharedPreferences("purelock_secure_fallback", Context.MODE_PRIVATE)
    }

    var trustedName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NAME, value.trim()).apply() }

    var trustedPhone: String
        get() = prefs.getString(KEY_PHONE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PHONE, value.trim()).apply() }

    fun hasTrustedContact(): Boolean = trustedPhone.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_NAME = "trusted_name"
        const val KEY_PHONE = "trusted_phone"
    }
}
