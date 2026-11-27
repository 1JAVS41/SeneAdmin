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
 * Exportación explícita de indicadores agregados de SeneAdmin.
 * No envía nombres, teléfonos, códigos, precios, importes individuales ni textos de reportes.
 */
object GuardianSnapshotExporter {
    private const val SNAPSHOT_URL = "https://sene-guardian-api.nagavs28.workers.dev/v1/snapshot"
    private val ALLOWED_CATEGORIES = setOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")

    data class ExportResult(val accepted: Boolean, val httpStatus: Int, val message: String)

    fun export(context: Context, installationId: String, ingestToken: String): ExportResult {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "La exportación GUARDIÁN debe ejecutarse fuera del hilo principal."
        }
        require(installationId.matches(Regex("^[a-fA-F0-9-]{16,64}$"))) { "Instalación no válida." }
        require(ingestToken.length >= 40) { "Credencial de ingestión no válida." }

        val app = context.applicationContext
        val capturedAt = utcNow()
        val categories = JSONArray()
        for (category in listOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")) {
            val local = InventoryLocalStore.snapshot(app, category) ?: continue
            if (local.category !in ALLOWED_CATEGORIES) continue
            val itemCount = local.items.size
            categories.put(JSONObject()
                .put("category", category)
                .put("itemCount", itemCount)
                .put("unitsInStock", local.items.sumOf { it.stock.coerceAtLeast(0).toLong() })
                .put("lowStockCount", JSONObject.NULL)
                .put("outOfStockCount", local.items.count { it.stock <= 0 })
                .put("updatedAt", capturedAt))
        }

        // Catálogo consultable por producto: solo campos del inventario, sin datos personales.
        val products = JSONArray()
        for (category in listOf("INVENTARIO_MAESTRO", "INVENTARIO_SENE")) {
            val local = InventoryLocalStore.snapshot(app, category) ?: continue
            if (local.category !in ALLOWED_CATEGORIES) continue
            local.items.take(2500).forEach { item ->
                products.put(JSONObject().put("code", item.code).put("name", item.name)
                    .put("price", item.price).put("stock", item.stock)
                    .put("aisle", item.aisle).put("shelf", item.shelf).put("category", category))
            }
        }

        // Solo movimientos aprobados. Se omiten trabajador, descripción e importes individuales.
        val approvedMovements = JSONArray()
        OwnerPayrollStore.approvedMovements(app).takeLast(1000).forEach { movement ->
            approvedMovements.put(JSONObject().put("id", movement.id).put("type", movement.type)
                .put("date", movement.date).put("productCode", movement.productCode)
                .put("quantity", movement.quantity))
        }

        // Solo contadores de negocio: nunca se exportan identidades ni contenido privado.
        val profiles = OwnerPayrollStore.profiles(app)
        var payrollRecords = 0
        var paidPayrolls = 0
        var pendingPayrolls = 0
        var workersTracked = 0
        var trackedAttendanceDays = 0
        var absences = 0
        var earlyExits = 0
        profiles.forEach { profile ->
            val payrolls = OwnerPayrollStore.payrolls(app, profile.id)
            payrollRecords += payrolls.size
            paidPayrolls += payrolls.count { it.status == "PAGADA" }
            pendingPayrolls += payrolls.count { it.status != "PAGADA" }

            val week = OwnerPayrollStore.weekAttendance(app, profile)
            var profileHasAttendance = false
            week.forEach { day ->
                val hasRecord = day.shifts.isNotEmpty() ||
                    day.status in setOf("WORKED", "WORKING", "EARLY_EXIT", "ADMIN_CLOSED")
                if (hasRecord) {
                    trackedAttendanceDays++
                    profileHasAttendance = true
                }
                // weekAttendance marks scheduled days without a shift as NOT_WORKED.
                if (day.status == "NOT_WORKED") absences++
                if (day.status == "EARLY_EXIT" || day.shifts.any { it.status == "EARLY_EXIT" }) earlyExits++
            }
            if (profileHasAttendance) workersTracked++
        }

        val reports = SeneAdminStore.reports(app)
        val orderReports = reports.count { it.body.startsWith("SENE-ORDER|1") }
        val damaged = linkedSetOf<String>()
        reports.forEach { report ->
            val lines = report.body.replace("\r", "").lines()
            val marker = lines.indexOfLast { it.trim().equals("PRODUCTOS_DANADOS_SENE_V1", true) }
            if (marker < 0) return@forEach
            for (raw in lines.drop(marker + 1)) {
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("ETIQUETA:", true) || line.startsWith("Guarde este reporte", true)) break
                if (line.startsWith("CODIGO", true) || line.startsWith("CÓDIGO", true)) continue
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 6) damaged.add(listOf(parts[0], parts[3], parts[4], parts[5]).joinToString("|"))
            }
        }

        val operations = JSONObject()
            .put("employees", JSONObject().put("profileCount", profiles.size))
            .put("payroll", JSONObject().put("recordCount", payrollRecords)
                .put("paidCount", paidPayrolls).put("pendingCount", pendingPayrolls))
            .put("attendance", JSONObject().put("workersTracked", workersTracked)
                .put("trackedDaysThisWeek", trackedAttendanceDays).put("absenceDaysThisWeek", absences)
                .put("earlyExitsThisWeek", earlyExits))
            .put("reports", JSONObject().put("totalCount", reports.size).put("orderCount", orderReports))
            .put("damagedProducts", JSONObject().put("recordCount", damaged.size))

        val payload = JSONObject()
            .put("schema", 2)
            .put("installationId", installationId)
            .put("capturedAt", capturedAt)
            .put("business", JSONObject().put("status", "unknown"))
            .put("inventory", JSONObject().put("categories", categories))
            .put("products", products)
            .put("approvedMovements", approvedMovements)
            .put("operations", operations)

        val connection = URL(SNAPSHOT_URL).openConnection() as HttpURLConnection
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
            if (status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_CREATED)
                ExportResult(true, status, "Captura agregada, catálogo de productos y movimientos aprobados aceptados.")
            else ExportResult(false, status, "El servidor rechazó la captura (HTTP $status).")
        } finally { connection.disconnect() }
    }

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
