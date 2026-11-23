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

/** Caché local cifrada de inventarios; conserva historial de 10 copias por categoría. */
object InventoryLocalStore {
    private const val FILE = "sene_admin_inventory_v1.bin"
    private const val ALIAS = "SENE_ADMIN_INVENTORY_LOCAL_V1"
    private const val IV_SIZE = 12
    private const val AAD = "SENE-ADMIN-INVENTORY|1"
    private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private const val KEEP = 10

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

    private fun readAll(c: Context): JSONObject {
        val f = file(c)
        if (!f.exists()) return JSONObject()
        val packed = f.readBytes()
        require(packed.size > IV_SIZE + 16) { "Caché de inventario dañada" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return JSONObject(cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8))
    }

    private fun writeAll(c: Context, data: JSONObject): Boolean {
        val target = file(c)
        val temp = File(c.filesDir, "$FILE.tmp")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
            temp.outputStream().use { it.write(packed); it.fd.sync() }
            temp.renameTo(target)
        } catch (_: Exception) { false }
        finally { if (temp.exists()) temp.delete() }
    }

    private fun decode(category: String, o: JSONObject): SharedInventoryManager.Snapshot? {
        val rows = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            SharedInventoryManager.Item(row.optString("c"), row.optString("n"), row.optDouble("p"),
                row.optInt("s"), row.optString("l"), row.optString("h"))
        }
        if (items.isEmpty()) return null
        return SharedInventoryManager.Snapshot(category, o.optString("file", "SERVER-SENE"),
            o.optString("updated"), items, false)
    }

    private fun encode(snapshot: SharedInventoryManager.Snapshot, savedAt: Long): JSONObject {
        val rows = JSONArray()
        snapshot.items.forEach { item ->
            rows.put(JSONObject().put("c", item.code).put("n", item.name).put("p", item.price)
                .put("s", item.stock).put("l", item.aisle).put("h", item.shelf))
        }
        return JSONObject().put("file", snapshot.fileName).put("updated", snapshot.updated)
            .put("saved_at", savedAt).put("items", rows)
    }

    @Synchronized fun snapshot(c: Context, category: String): SharedInventoryManager.Snapshot? {
        return try {
            val current = readAll(c).optJSONObject(category) ?: return null
            decode(category, current)
        } catch (_: Exception) { null }
    }

    @Synchronized fun save(c: Context, snapshot: SharedInventoryManager.Snapshot): Boolean {
        if (snapshot.category !in setOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE") || snapshot.items.isEmpty()) return false
        return try {
            val all = readAll(c)
            val old = all.optJSONObject(snapshot.category) ?: JSONObject()
            val history = old.optJSONArray("history") ?: JSONArray()
            val saved = JSONArray()
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                saved.put(item)
            }
            val now = System.currentTimeMillis()
            // Migra la última copia del formato anterior al historial sin descifrarla de nuevo.
            if (old.has("items") && old.optJSONArray("items")?.length() ?: 0 > 0) {
                val oldFile = old.optString("file")
                val oldUpdated = old.optString("updated")
                val alreadyStored = (0 until saved.length()).any { i ->
                    val item = saved.optJSONObject(i)
                    item != null && item.optString("file") == oldFile && item.optString("updated") == oldUpdated
                }
                if (!alreadyStored) saved.put(JSONObject(old.toString()).put("saved_at", old.optLong("saved_at", now)))
            }
            val current = encode(snapshot, now)
            val duplicate = (0 until saved.length()).any { i ->
                val item = saved.optJSONObject(i)
                item != null && item.optString("file") == snapshot.fileName && item.optString("updated") == snapshot.updated
            }
            if (!duplicate) saved.put(current)
            val lastCleanup = old.optLong("last_cleanup", 0L)
            val kept = if (lastCleanup == 0L || now - lastCleanup >= RETENTION_MS) {
                val ordered = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }
                    .sortedByDescending { it.optLong("saved_at", 0L) }
                JSONArray(ordered.filterIndexed { index, item ->
                    index < KEEP || now - item.optLong("saved_at", now) <= RETENTION_MS
                })
            } else saved
            all.put(snapshot.category, encode(snapshot, now)
                .put("history", kept).put("last_cleanup", if (lastCleanup == 0L || now - lastCleanup >= RETENTION_MS) now else lastCleanup))
            writeAll(c, all)
        } catch (_: Exception) { false }
    }

    /** Limpia cada 30 días y conserva siempre las 10 copias más recientes de cada categoría. */
    @Synchronized fun cleanupIfDue(c: Context): Boolean {
        return try {
            val all = readAll(c)
            val now = System.currentTimeMillis()
            var changed = false
            for (category in listOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")) {
                val old = all.optJSONObject(category) ?: continue
                val last = old.optLong("last_cleanup", 0L)
                if (last > 0L && now - last < RETENTION_MS) continue
                val history = old.optJSONArray("history") ?: JSONArray()
                val orderedInput = (0 until history.length()).mapNotNull { history.optJSONObject(it) }.toMutableList()
                if (old.has("items") && (old.optJSONArray("items")?.length() ?: 0) > 0) {
                    val oldFile = old.optString("file")
                    val oldUpdated = old.optString("updated")
                    if (orderedInput.none { it.optString("file") == oldFile && it.optString("updated") == oldUpdated }) {
                        orderedInput.add(JSONObject(old.toString()).put("saved_at", old.optLong("saved_at", now)))
                    }
                }
                val ordered = orderedInput.sortedByDescending { it.optLong("saved_at", 0L) }
                val kept = JSONArray(ordered.filterIndexed { index, item ->
                    index < KEEP || now - item.optLong("saved_at", now) <= RETENTION_MS
                })
                old.put("history", kept).put("last_cleanup", now)
                changed = true
            }
            !changed || writeAll(c, all)
        } catch (_: Exception) { false }
    }
}
