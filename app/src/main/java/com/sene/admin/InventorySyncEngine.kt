package com.sene.admin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Motor local cifrado para inventario recibido del servidor y consumos autorizados. */
object InventorySyncEngine {
    private fun latestReportInventory(c: Context): SharedInventoryManager.Snapshot? =
        SeneAdminStore.reports(c)
            .sortedWith(compareByDescending<SeneReport> { it.date }.thenByDescending { it.time }.thenByDescending { it.number })
            .firstNotNullOfOrNull { SharedInventoryManager.inventoryFromReport(it) }

    @Synchronized fun applyEvent(c: Context, event: JSONObject): Boolean {
        val id = event.optString("e")
        val scope = event.optString("g")
        val stamp = event.optLong("i", 0L)
        val rows = event.optJSONArray("p") ?: JSONArray()
        if (id.isBlank() || event.optString("t") != SeneWireProtocol.INVENTORY_SYNC ||
            scope !in setOf("M", "S") || stamp <= 0L || rows.length() == 0) return false
        val prefs = c.getSharedPreferences("sene_admin_shared_inventory", Context.MODE_PRIVATE)
        val done = prefs.getStringSet("wire_inventory_events", emptySet()).orEmpty().toMutableSet()
        if (id in done) return true
        val last = prefs.getLong("wire_inventory_stamp_$scope", 0L)
        if (stamp < last) return true
        val category = if (scope == "M") "INVENTARIO_MAESTRO" else "INVENTARIO_SENE"
        // Los deltas solo se aplican sobre una base local ya recibida.
        // No se solicita ni se lee una clave manual o una carpeta compartida.
        val fullSnapshot = event.optBoolean("f", false)
        val base = if (fullSnapshot) null else InventoryLocalStore.snapshot(c, category)
            ?: (if (scope == "S") latestReportInventory(c) else null)
        // El primer evento de cada categoría lleva una fotografía completa; los siguientes son deltas compactos.
        if (!fullSnapshot && base == null) return false
        val items = if (fullSnapshot) mutableListOf() else base!!.items.toMutableList()
        var acceptedRows = 0
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("c")
            val stock = row.optInt("s", -1)
            if (code.isBlank() || stock < 0) continue
            acceptedRows++
            val index = items.indexOfFirst { it.code.equals(code, true) }
            if (index >= 0) {
                val old = items[index]
                items[index] = old.copy(stock = stock,
                    name = row.optString("n").ifBlank { old.name },
                    price = if (row.has("a")) row.optLong("a", 0L).toDouble() / 100.0 else old.price,
                    aisle = row.optString("l").ifBlank { old.aisle },
                    shelf = row.optString("h").ifBlank { old.shelf })
            } else items.add(SharedInventoryManager.Item(code, row.optString("n"),
                row.optLong("a", 0L).toDouble() / 100.0, stock, row.optString("l"), row.optString("h")))
        }
        if (acceptedRows == 0 || (items.isEmpty() && !fullSnapshot)) return false
        val updated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(stamp))
        val snapshot = SharedInventoryManager.Snapshot(category, "SERVER-SENE-" + id, updated, items, false)
        if (!InventoryLocalStore.save(c, snapshot)) return false
        done.add(id)
        return prefs.edit().putStringSet("wire_inventory_events", done.toList().takeLast(500).toSet())
            .putLong("wire_inventory_stamp_$scope", maxOf(last, stamp)).commit()
    }

    @Synchronized fun applyConsumption(c: Context, request: OwnerPayrollStore.Request): Boolean {
        if (request.type != "CONSUMO") return true
        if (request.productCode.isBlank() || request.quantity <= 0) return false
        val prefs = c.getSharedPreferences("sene_admin_approved_inventory_moves", Context.MODE_PRIVATE)
        if (prefs.getBoolean(request.id, false)) return true
        val current = InventoryLocalStore.snapshot(c, "INVENTARIO_MAESTRO")
            ?: InventoryLocalStore.snapshot(c, "INVENTARIO_SENE")
            ?: latestReportInventory(c) ?: return false
        val index = current.items.indexOfFirst { it.code.equals(request.productCode, true) }
        if (index < 0) return false
        val product = current.items[index]
        if (product.stock < request.quantity) return false
        val items = current.items.toMutableList()
        items[index] = product.copy(stock = product.stock - request.quantity)
        val next = current.copy(category = "INVENTARIO_MAESTRO", fileName = "CONSUMO-" + request.id,
            updated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), items = items)
        if (!InventoryLocalStore.save(c, next)) return false
        return prefs.edit().putBoolean(request.id, true).commit()
    }
}
