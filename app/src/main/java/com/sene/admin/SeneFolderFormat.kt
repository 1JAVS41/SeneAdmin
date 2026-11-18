package com.sene.admin

import java.security.MessageDigest
import java.util.Base64

/**
 * Serialización inicial para INVENTARIO SENE e INVENTARIO MAESTRO.
 * Base64 no es cifrado: oculta la lectura casual, no protege contra extracción técnica.
 * La validación nunca borra ni sobrescribe el archivo fuente.
 */
object SeneFolderFormat {
    private const val MAGIC = "SENE-FOLDER|1"
    data class Document(val category: String, val payload: String)

    private fun ownerCheck(ownerKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(("SENE-OWNER|" + ownerKey.trim()).toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }

    fun encode(category: String, ownerKey: String, payload: String): ByteArray {
        require(category == "INVENTARIO_SENE" || category == "INVENTARIO_MAESTRO")
        val body = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return listOf(MAGIC, "CATEGORY=$category", "OWNER=${ownerCheck(ownerKey)}", "DATA=$body")
            .joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, ownerKey: String): Document {
        val lines = bytes.toString(Charsets.UTF_8).lines()
        require(lines.size == 4 && lines[0] == MAGIC) { "Formato SENE no reconocido" }
        val category = lines[1].substringAfter("CATEGORY=", "")
        val owner = lines[2].substringAfter("OWNER=", "")
        val data = lines[3].substringAfter("DATA=", "")
        require(category == "INVENTARIO_SENE" || category == "INVENTARIO_MAESTRO") { "Categoría inválida" }
        require(MessageDigest.isEqual(owner.toByteArray(), ownerCheck(ownerKey).toByteArray())) {
            "La clave no coincide. Los archivos permanecen intactos."
        }
        return Document(category, String(Base64.getDecoder().decode(data), Charsets.UTF_8))
    }
}
