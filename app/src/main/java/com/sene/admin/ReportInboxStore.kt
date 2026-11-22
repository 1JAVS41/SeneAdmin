package com.sene.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Buzón durable de reportes recibidos del servidor.
 * Persiste el sobre cifrado con una clave exclusiva de esta instalación (Android Keystore).
 * El ACK solo se publica después de guardar el evento de forma atómica.
 */
object ReportInboxStore {
    private const val FILE = "sene_report_inbox_v1.bin"
    private const val ALIAS = "SENE_ADMIN_REPORT_INBOX_V1"
    private const val IV_SIZE = 12
    private const val AAD = "SENE-ADMIN-REPORT-INBOX|1"

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

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    private fun read(c: Context): JSONObject {
        val target = file(c)
        if (!target.exists()) return JSONObject().put("events", JSONArray()).put("acks", JSONArray())
        val packed = target.readBytes()
        require(packed.size > IV_SIZE + 16) { "Buzón de reportes dañado" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return JSONObject(cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8))
    }

    private fun write(c: Context, data: JSONObject): Boolean {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val target = file(c)
        val temp = File(c.applicationContext.filesDir, "$FILE.tmp")
        return try {
            temp.outputStream().use { it.write(packed); it.fd.sync() }
            temp.renameTo(target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Guarda cada evento idempotentemente y devuelve solo IDs ya persistidos correctamente. */
    @Synchronized
    fun accept(c: Context, incoming: JSONArray?): JSONArray {
        val accepted = JSONArray()
        if (incoming == null || incoming.length() == 0) return accepted
        return try {
            val data = read(c)
            val events = data.optJSONArray("events") ?: JSONArray()
            val acks = data.optJSONArray("acks") ?: JSONArray()
            val known = mutableSetOf<String>()
            for (i in 0 until events.length()) events.optJSONObject(i)?.optString("e")
                ?.takeIf { it.isNotBlank() }?.let(known::add)
            val pending = mutableSetOf<String>()
            for (i in 0 until acks.length()) acks.optString(i).takeIf { it.isNotBlank() }?.let(pending::add)
            for (i in 0 until incoming.length()) {
                val event = incoming.optJSONObject(i) ?: continue
                val id = event.optString("e", event.optString("id"))
                val payload = event.optString("b", event.optString("payload"))
                if (id.isBlank() || payload.isBlank()) continue
                if (id !in known) events.put(JSONObject(event.toString()))
                known.add(id)
                pending.add(id)
                accepted.put(id)
            }
            data.put("events", events).put("acks", JSONArray(pending.toList()))
            if (write(c, data)) accepted else JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    @Synchronized
    fun pendingAcks(c: Context): JSONArray = try {
        read(c).optJSONArray("acks") ?: JSONArray()
    } catch (_: Exception) { JSONArray() }

    /** El servidor confirma que recibió los ACK; entonces se limpia solo la cola de ACK. */
    @Synchronized
    fun acknowledgeAcks(c: Context, confirmed: JSONArray?) {
        if (confirmed == null || confirmed.length() == 0) return
        try {
            val data = read(c)
            val remove = mutableSetOf<String>()
            for (i in 0 until confirmed.length()) {
                val item = confirmed.opt(i)
                val id = if (item is JSONObject) item.optString("e", item.optString("id")) else item?.toString().orEmpty()
                if (id.isNotBlank()) remove.add(id)
            }
            val old = data.optJSONArray("acks") ?: JSONArray()
            val keep = JSONArray()
            for (i in 0 until old.length()) old.optString(i).takeIf { it.isNotBlank() && it !in remove }?.let(keep::put)
            data.put("acks", keep)
            write(c, data)
        } catch (_: Exception) { }
    }

    @Synchronized
    fun events(c: Context): JSONArray = try {
        read(c).optJSONArray("events") ?: JSONArray()
    } catch (_: Exception) { JSONArray() }

    @Synchronized
    fun removeImported(c: Context, ids: Set<String>): Boolean {
        if (ids.isEmpty()) return true
        return try {
            val data = read(c)
            val old = data.optJSONArray("events") ?: JSONArray()
            val keep = JSONArray()
            for (i in 0 until old.length()) {
                val event = old.optJSONObject(i) ?: continue
                if (event.optString("e") !in ids) keep.put(event)
            }
            data.put("events", keep)
            write(c, data)
        } catch (_: Exception) { false }
    }
}
