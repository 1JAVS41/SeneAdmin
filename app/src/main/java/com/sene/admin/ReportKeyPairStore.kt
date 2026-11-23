package com.sene.admin

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Clave privada de reportes no exportable; la pública se registra en SERVER-SENE. */
object ReportKeyPairStore {
    // V3 fuerza una clave nueva con autorización explícita para MGF1-SHA256.
    // Las claves V2 creadas sin esa autorización pueden fallar al descifrar en Android Keystore.
    private const val ALIAS = "SENE_ADMIN_REPORT_RSA_V3"
    private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    @Synchronized private fun ensurePair() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setRandomizedEncryptionRequired(true)
        // Android 15+ permite declarar por separado el digest MGF1 autorizado.
        if (Build.VERSION.SDK_INT >= 35) {
            builder.setMgf1Digests(KeyProperties.DIGEST_SHA256)
        }
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    fun publicKeyBase64(): String {
        ensurePair()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val publicKey = store.getCertificate(ALIAS)?.publicKey
            ?: throw IllegalStateException("No se pudo obtener la clave pública de reportes")
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decrypt(wrappedKey: ByteArray): ByteArray {
        ensurePair()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = store.getKey(ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("No está disponible la clave privada de reportes")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep)
        return cipher.doFinal(wrappedKey)
    }
}
