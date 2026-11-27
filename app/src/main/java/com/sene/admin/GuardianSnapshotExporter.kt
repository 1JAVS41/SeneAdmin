package com.sene.admin

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exportación EXPLÍCITA de un resumen agregado para SENE GUARDIÁN.
 *
 * No programa tareas, no guarda credenciales y no modifica los almacenes locales.
 * La persona que integre esta clase debe invocarla desde un hilo de fondo y
 * suministrar el token de ingestión de esta instalación desde almacenamiento seguro.
 */
object GuardianSnapshotExporter {
    private const val SNAPSHOT_URL = "https://sene-guardian-api.nagavs28.workers.dev/v1/snapshot"
    private val ALLOWED_CATEGORIES = setOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")

    data class ExportResult(val accepted: Boolean, val httpStatus: Int, val message: String)

    /**
     * Envía una captura agregada. Nunca llamar desde el hilo principal.
     * ingestToken no se persiste, registra ni devuelve.
     */
    fun export(
        context: Context,
        installationId: String,
        ingestToken: String
    ): ExportResult {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "La exportación GUARDIÁN debe ejecutarse fuera del hilo principal."
        }
        require(installationId.matches(Regex("^[a-fA-F0-9-]{16,64}$"))) { "Instalación no válida." }
        require(ingestToken.length >= 40) { "Credencial de ingestión no válida." }

        val capturedAt = utcNow()
        val categories = JSONArray()
        for (category in listOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")) {
            val local = InventoryLocalStore.snapshot(context.applicationContext, category) ?: continue
            if (local.category !in ALLOWED_CATEGORIES) continue

            // Solo se conservan conteos. Se descartan código, nombre, precio,
            // pasillo y estante de cada artículo antes de construir el payload.
            val itemCount = local.items.size
            val unitsInStock = local.items.sumOf { it.stock.coerceAtLeast(0).toLong() }
            val outOfStockCount = local.items.count { it.stock <= 0 }

            categories.put(JSONObject()
                .put("category", category)
                .put("itemCount", itemCount)
                .put("unitsInStock", unitsInStock)
                // No existe todavía una regla de bajo stock verificada.
                // null significa desconocido; no inventamos un umbral.
                .put("lowStockCount", JSONObject.NULL)
                .put("outOfStockCount", outOfStockCount)
                .put("updatedAt", capturedAt))
        }

        val payload = JSONObject()
            .put("schema", 1)
            .put("installationId", installationId)
            .put("capturedAt", capturedAt)
            .put("business", JSONObject().put("status", "unknown"))
            .put("inventory", JSONObject().put("categories", categories))

        val connection = (URL(SNAPSHOT_URL).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $ingestToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                ExportResult(true, status, "Captura agregada aceptada por SENE GUARDIÁN.")
            } else {
                // No incluir URL, encabezados ni credenciales en mensajes de error.
                ExportResult(false, status, "El servidor rechazó la captura (HTTP $status).")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
