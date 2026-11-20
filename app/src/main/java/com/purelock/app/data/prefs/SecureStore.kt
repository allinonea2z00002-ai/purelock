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

    /**
     * SHA-256 hash of the 4-digit Lock-mode PIN (SPEC S05 step 2, foundation
     * decision 5). The plain PIN is never stored. Empty = no PIN set yet.
     */
    var lockPinHash: String
        get() = prefs.getString(KEY_LOCK_PIN_HASH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LOCK_PIN_HASH, value).apply() }

    /** Stores [pin] as its SHA-256 hash. */
    fun setLockPin(pin: String) {
        lockPinHash = sha256Hex(pin)
    }

    /** True when a Lock-mode PIN has been set. */
    fun hasLockPin(): Boolean = lockPinHash.isNotBlank()

    /** True when [pin] hashes to the stored Lock-mode PIN. */
    fun lockPinMatches(pin: String): Boolean =
        lockPinHash.isNotBlank() && lockPinHash == sha256Hex(pin)

    fun hasTrustedContact(): Boolean = trustedPhone.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_NAME = "trusted_name"
        const val KEY_PHONE = "trusted_phone"
        const val KEY_LOCK_PIN_HASH = "lock_pin_hash"

        /** Lowercase hex SHA-256 of [input]. */
        fun sha256Hex(input: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
