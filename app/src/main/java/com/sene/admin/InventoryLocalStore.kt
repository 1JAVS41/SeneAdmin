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

/** Caché local de inventarios, cifrada con una clave exclusiva de esta instalación. */
object InventoryLocalStore {
    private const val FILE = "sene_admin_inventory_v1.bin"
    private const val ALIAS = "SENE_ADMIN_INVENTORY_LOCAL_V1"
    private const val IV_SIZE = 12
    private const val AAD = "SENE-ADMIN-INVENTORY|1"

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

    @Synchronized fun snapshot(c: Context, category: String): SharedInventoryManager.Snapshot? {
        return try {
            val o = readAll(c).optJSONObject(category) ?: return null
            val rows = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                SharedInventoryManager.Item(row.optString("c"), row.optString("n"), row.optDouble("p"),
                    row.optInt("s"), row.optString("l"), row.optString("h"))
            }
            if (items.isEmpty()) null else SharedInventoryManager.Snapshot(category,
                o.optString("file", "SERVER-SENE"), o.optString("updated"), items, false)
        } catch (_: Exception) { null }
    }

    @Synchronized fun save(c: Context, snapshot: SharedInventoryManager.Snapshot): Boolean {
        if (snapshot.category !in setOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE") || snapshot.items.isEmpty()) return false
        return try {
            val all = readAll(c)
            val rows = JSONArray()
            snapshot.items.forEach { item ->
                rows.put(JSONObject().put("c", item.code).put("n", item.name).put("p", item.price)
                    .put("s", item.stock).put("l", item.aisle).put("h", item.shelf))
            }
            all.put(snapshot.category, JSONObject().put("file", snapshot.fileName)
                .put("updated", snapshot.updated).put("items", rows))
            writeAll(c, all)
        } catch (_: Exception) { false }
    }
}
