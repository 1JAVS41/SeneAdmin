package com.sene.admin

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object AccessPinStore {
    private const val PREFS = "sene_admin_access_v1"
    private const val SALT = "pin_salt"
    private const val HASH = "pin_hash"
    private const val FINGERPRINT = "fingerprint_enabled"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPin(c: Context): Boolean = !prefs(c).getString(HASH, null).isNullOrBlank()

    fun save(c: Context, pin: String): Boolean {
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        return try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val digest = MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))
            prefs(c).edit()
                .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) { false }
    }

    fun matches(c: Context, pin: String): Boolean {
        return try {
            val salt = Base64.decode(prefs(c).getString(SALT, null) ?: return false, Base64.NO_WRAP)
            val expected = Base64.decode(prefs(c).getString(HASH, null) ?: return false, Base64.NO_WRAP)
            val actual = MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) { false }
    }

    fun fingerprintEnabled(c: Context) = prefs(c).getBoolean(FINGERPRINT, false)
    fun setFingerprintEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(FINGERPRINT, enabled).apply()
    }
}