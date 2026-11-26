package com.sene.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credenciales GUARDIÁN cifradas con una clave no exportable de Android Keystore. */
object GuardianConfigStore {
    private const val PREFS = "sene_guardian_secure_v1"
    private const val VALUE = "encrypted_config"
    private const val ALIAS = "SENE_GUARDIAN_CONFIG_AES_GCM_V1"
    private const val AAD = "SENE-GUARDIAN-CONFIG|1"

    data class Config(val installationId: String, val ingestToken: String, val consent: Boolean)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized fun save(context: Context, installationId: String, ingestToken: String, consent: Boolean) {
        require(installationId.matches(Regex("^[a-fA-F0-9-]{16,64}$"))) { "Identificador de instalación no válido." }
        require(ingestToken.length >= 40) { "Token de ingestión no válido." }
        val plain = JSONObject().put("installationId", installationId)
            .put("ingestToken", ingestToken).put("consent", consent).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(plain)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(VALUE, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)).commit()
        plain.fill(0)
        check(saved) { "No se pudo guardar la configuración protegida." }
    }

    @Synchronized fun read(context: Context): Config? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null) ?: return null
        return try {
            val packed = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(packed.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(packed.copyOfRange(12, packed.size))
            try {
                val json = JSONObject(plain.toString(Charsets.UTF_8))
                Config(json.getString("installationId"), json.getString("ingestToken"), json.optBoolean("consent", false))
            } finally { plain.fill(0) }
        } catch (_: Exception) { null }
    }

    @Synchronized fun setConsent(context: Context, consent: Boolean) {
        val current = read(context) ?: return
        save(context, current.installationId, current.ingestToken, consent)
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).commit()
    }
}
