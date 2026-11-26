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
    private const val SNAPSHOT_URL = "https://sene-guardian-test.nagavs28.workers.dev/v1/snapshot"
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

        val damagedProductCount = damagedProductCount(context.applicationContext)
        val payload = JSONObject()
            .put("schema", 1)
            .put("installationId", installationId)
            .put("capturedAt", capturedAt)
            .put("business", JSONObject().put("status", "unknown"))
            .put("inventory", JSONObject().put("categories", categories))
            .put("damagedProducts", JSONObject()
                .put("itemCount", damagedProductCount)
                .put("updatedAt", capturedAt))
            .put("approvedMovements", approvedMovementSummary(context.applicationContext))
            .put("payroll", payrollSummary(context.applicationContext))

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

    /**
     * Solo exporta conteos y unidades por mes; nunca exporta perfiles, descripciones,
     * códigos de producto, importes ni identificadores de movimientos.
     */
    private fun approvedMovementSummary(context: Context): JSONObject {
        val grouped = OwnerPayrollStore.approvedMovements(context).mapNotNull { movement ->
            val month = Regex("^(\d{4}-\d{2})-\d{2}").find(movement.date.trim())?.groupValues?.get(1)
                ?: return@mapNotNull null
            val type = movement.type.uppercase(Locale.ROOT)
            if (type != "CASH_DISBURSEMENT" && type != "INVENTORY_CONSUMPTION") return@mapNotNull null
            Triple(month, type, movement.quantity.coerceAtLeast(0))
        }.groupBy { it.first }.toSortedMap().toList().takeLast(36)
        val periods = JSONArray()
        grouped.forEach { (month, rows) ->
            periods.put(JSONObject()
                .put("period", month)
                .put("cashDisbursementCount", rows.count { it.second == "CASH_DISBURSEMENT" })
                .put("inventoryConsumptionCount", rows.count { it.second == "INVENTORY_CONSUMPTION" })
                .put("inventoryConsumptionUnits", rows.filter { it.second == "INVENTORY_CONSUMPTION" }.sumOf { it.third.toLong() }))
        }
        return JSONObject().put("periods", periods)
    }

    /**
     * Totales de nómina por periodo. Se omiten importes con menos de tres
     * trabajadores distintos para evitar revelar un sueldo individual.
     */
    private fun payrollSummary(context: Context): JSONObject {
        val rows = OwnerPayrollStore.profiles(context).flatMap { profile ->
            OwnerPayrollStore.payrolls(context, profile.id).map { profile.id to it }
        }.filter { it.second.period.matches(Regex("^\d{4}-\d{2}-\d{2}$")) }
            .groupBy { it.second.period }.toSortedMap().toList().takeLast(36)
        val periods = JSONArray()
        rows.forEach { (period, records) ->
            val employeeCount = records.map { it.first }.distinct().size
            if (employeeCount < 3) return@forEach
            periods.put(JSONObject()
                .put("period", period)
                .put("employeeCount", employeeCount)
                .put("payrollCount", records.size)
                .put("grossCents", records.sumOf { it.second.grossCents.coerceAtLeast(0L) })
                .put("absenceCents", records.sumOf { it.second.absenceCents.coerceAtLeast(0L) })
                .put("earlyExitCents", records.sumOf { it.second.earlyExitCents.coerceAtLeast(0L) })
                .put("loanCents", records.sumOf { it.second.loanCents.coerceAtLeast(0L) })
                .put("consumptionCents", records.sumOf { it.second.consumptionCents.coerceAtLeast(0L) })
                .put("netCents", records.sumOf { it.second.netCents.coerceAtLeast(0L) })
                .put("deliveredCents", records.sumOf { it.second.deliveredCents.coerceAtLeast(0L) }))
        }
        return JSONObject().put("periods", periods)
    }

    /** Cuenta registros únicos de productos dañados sin exportar códigos, nombres ni motivos. */
    private fun damagedProductCount(context: Context): Int {
        val identities = linkedSetOf<String>()
        SeneAdminStore.reports(context).forEach { report ->
            val lines = report.body.replace("\r", "").lines()
            val marker = lines.indexOfLast { it.trim().equals("PRODUCTOS_DANADOS_SENE_V1", true) }
            if (marker < 0) return@forEach
            for (raw in lines.drop(marker + 1)) {
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("ETIQUETA:", true) ||
                    line.startsWith("Guarde este reporte", true)) break
                if (line.startsWith("CODIGO", true) || line.startsWith("CÓDIGO", true)) continue
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 6) continue
                identities.add(listOf(parts[0], parts[3], parts[4], parts[5]).joinToString("|"))
            }
        }
        return identities.size
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
