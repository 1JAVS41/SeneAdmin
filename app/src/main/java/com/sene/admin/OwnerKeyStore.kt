package com.sene.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La clave se vuelve a introducir al cambiar de teléfono; el secreto local no se exporta. */
object OwnerKeyStore {
    private const val PREFS = "sene_admin_owner_key_v1"
    private const val VALUE = "wrapped_owner_key"
    private const val ALIAS = "SENE_ADMIN_OWNER_KEY_V1"
    private const val IV_SIZE = 12
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }

    fun hasKey(c: Context) = !read(c).isNullOrBlank()
    fun save(c: Context, value: String): Boolean {
        val clean = value.trim()
        if (clean.length < 8 || clean.length > 256) return false
        if (hasKey(c)) return matches(c, clean)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val packed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs(c).edit().putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        } catch (_: Exception) { false }
    }

    fun read(c: Context): String? = try {
        val packed = Base64.decode(prefs(c).getString(VALUE, null) ?: return null, Base64.NO_WRAP)
        require(packed.size > IV_SIZE + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
        cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun matches(c: Context, candidate: String): Boolean {
        val current = read(c) ?: return false
        return java.security.MessageDigest.isEqual(current.toByteArray(Charsets.UTF_8),
            candidate.trim().toByteArray(Charsets.UTF_8))
    }
}
