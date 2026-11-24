package com.sene.admin

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Lector del formato E2E v2: AES-256-GCM y clave de contenido envuelta por RSA del patrón. */
object SeneReportV2Reader {
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)

    fun read(message: String): SeneReport {
        val lines = message.trim().lines()
        require(lines.size >= 11 && lines[0] == "SENE-ADMIN-REPORT|2") { "Formato de reporte cifrado no reconocido" }
        val header = lines.take(8)
        fun field(name: String): String = header.firstOrNull { it.startsWith(name + "=") }
            ?.substringAfter("=") ?: throw IllegalArgumentException("Falta el campo " + name)
        require(field("ALG") == "RSA-OAEP-SHA256+A256GCM") { "Algoritmo de cifrado no compatible" }
        val wrappedKey = decode(lines.first { it.startsWith("WRAPPED_KEY=") }.substringAfter("="))
        val iv = decode(lines.first { it.startsWith("IV=") }.substringAfter("="))
        val encrypted = decode(lines.first { it.startsWith("DATA=") }.substringAfter("="))
        val rawKey = ReportKeyPairStore.decrypt(wrappedKey)
        require(rawKey.size == 32) { "Clave de contenido inválida" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(header.joinToString("\n").toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        val worker = String(decode(field("WORKER")), Charsets.UTF_8)
        return SeneReport(field("ID"), worker, field("DATE"), field("TIME"),
            field("NUMBER").toLong(), field("TYPE"), body)
    }
}
