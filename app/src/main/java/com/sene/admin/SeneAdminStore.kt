package com.sene.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Historial local cifrado de reportes. La clave es exclusiva de esta instalación
 * y está protegida por Android Keystore; no depende de la clave compartida antigua.
 */
object SeneAdminStore {
    private const val PREFS = "sene_admin_records_v1"
    private const val REPORTS = "received_reports"
    private const val FILE = "sene_admin_reports_v2.bin"
    private const val ALIAS = "SENE_ADMIN_REPORTS_STORE_V2"
    private const val IV_SIZE = 12
    private const val AAD = "SENE-ADMIN-REPORTS|2"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encode(json: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }

    private fun decode(bytes: ByteArray): String {
        require(bytes.size > IV_SIZE + 16) { "Historial cifrado dañado" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun write(c: Context, json: String): Boolean {
        val target = file(c)
        val temp = File(c.filesDir, "$FILE.tmp")
        return try {
            val bytes = encode(json)
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temp.renameTo(target)) return false
            true
        } catch (_: Exception) { false }
        finally { if (temp.exists()) temp.delete() }
    }

    /** Migra el historial anterior en texto solo después de persistir y verificar el archivo cifrado. */
    @Synchronized private fun migrateLegacy(c: Context) {
        val target = file(c)
        if (target.exists()) return
        val old = prefs(c).getString(REPORTS, null) ?: return
        try {
            val parsed = JSONArray(old)
            if (!write(c, parsed.toString())) return
            val verified = JSONArray(decode(target.readBytes()))
            if (verified.length() == parsed.length()) prefs(c).edit().remove(REPORTS).commit()
        } catch (_: Exception) { /* No borrar ni alterar el historial anterior si la migración falla. */ }
    }

    @Synchronized private fun readArray(c: Context): JSONArray {
        migrateLegacy(c)
        val target = file(c)
        if (target.exists()) return try { JSONArray(decode(target.readBytes())) } catch (_: Exception) { JSONArray() }
        return JSONArray()
    }

    @Synchronized fun reports(c: Context): List<SeneReport> {
        val array = readArray(c)
        return (0 until array.length()).mapNotNull { i ->
            try {
                val o = array.getJSONObject(i)
                SeneReport(o.getString("id"), o.getString("worker"), o.getString("date"),
                    o.getString("time"), o.getLong("number"), o.getString("type"), o.getString("body"))
            } catch (_: Exception) { null }
        }
    }

    @Synchronized fun saveReport(c: Context, report: SeneReport): Boolean =
        saveReports(c, listOf(report))

    /** Guarda el lote completo cifrado; los IDs hacen idempotente la importación. */
    @Synchronized fun saveReports(c: Context, incoming: List<SeneReport>): Boolean {
        if (incoming.isEmpty()) return true
        val array = readArray(c)
        val known = HashSet<String>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.optString("id")
            ?.takeIf { it.isNotBlank() }?.let(known::add)
        incoming.forEach { item ->
            if (known.add(item.id)) array.put(JSONObject().put("id", item.id).put("worker", item.workerId)
                .put("date", item.date).put("time", item.time).put("number", item.number)
                .put("type", item.type).put("body", item.body))
        }
        return write(c, array.toString())
    }
}
