package com.sene.admin

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SeneReport(
    val id: String, val workerId: String, val date: String, val time: String,
    val number: Long, val type: String, val body: String
)

/** Compatible con SENE-ADMIN-REPORT|1 generado por SENE VENTAS. */
object SeneReportReader {
    private const val ITERATIONS = 210000
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)

    fun read(message: String, ownerKey: String): SeneReport {
        val pastedLines = message.trim().lines()
        // SENE VENTAS añade esta instrucción visible; se retira antes de validar el protocolo.
        val lines = if (pastedLines.firstOrNull() == "*COPIA Y PEGA en SeneAdmin*") pastedLines.drop(1) else pastedLines
        require(lines.size >= 11 && lines[0] == "SENE-ADMIN-REPORT|1") { "Formato de reporte no reconocido" }
        // El AAD original contiene exactamente las 8 líneas desde el identificador del protocolo hasta KEY_ID.
        val fields = lines.take(8)
        fun field(name: String) = fields.firstOrNull { it.startsWith("${name}=") }
            ?.substringAfter("=") ?: throw IllegalArgumentException("Falta el campo ${name}")
        val keyId = field("KEY_ID")
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(ownerKey.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
        require(MessageDigest.isEqual(keyId.toByteArray(), expected.toByteArray())) { "La clave no coincide con este reporte" }
        val salt = decode(lines.first { it.startsWith("SALT=") }.substringAfter("="))
        val iv = decode(lines.first { it.startsWith("IV=") }.substringAfter("="))
        val encrypted = decode(lines.first { it.startsWith("DATA=") }.substringAfter("="))
        val header = fields.joinToString("\n")
        val spec = PBEKeySpec(ownerKey.toCharArray(), salt, ITERATIONS, 256)
        val key = try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(header.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        val worker = String(decode(field("WORKER")), Charsets.UTF_8)
        return SeneReport(field("ID"), worker, field("DATE"), field("TIME"), field("NUMBER").toLong(), field("TYPE"), body)
    }
}
