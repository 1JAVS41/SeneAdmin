package com.sene.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); showHome()
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey() else handleIncoming(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SharedInventoryManager.REQUEST_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { SharedInventoryManager.acceptFolder(this, uri); Toast.makeText(this, "Carpeta INVENTARIO SENE vinculada", Toast.LENGTH_LONG).show(); showHome() }
            catch (e: Exception) { info("NO SE PUDO VINCULAR", e.message ?: "Android no concedió acceso a la carpeta.") }
        }
    }
    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { importReport(it) }
    }
    private fun showHome() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24); setBackgroundColor(0xFFF4F7FB.toInt()) }
        root.addView(TextView(this).apply { text = "SENE ADMIN"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()) })
        root.addView(TextView(this).apply { text = "Panel del propietario"; textSize = 15f; setTextColor(0xFF526579.toInt()); setPadding(0, 4, 0, 20) })
        addAction("INVENTARIO MAESTRO", "Existencias generales actualizadas por reportes") { showInventory("INVENTARIO_MAESTRO") }
        addAction("INVENTARIO SENE", "Carpeta compartida con SENE VENTAS") {
            if (SharedInventoryManager.savedTree(this) == null) SharedInventoryManager.chooseFolder(this) else showInventory("INVENTARIO_SENE")
        }
        addAction("VINCULAR / CAMBIAR CARPETA", "Seleccionar la carpeta INVENTARIO SENE") { SharedInventoryManager.chooseFolder(this) }
        addAction("REPORTES DE TRABAJADORES", "Historial ordenado por trabajador, fecha y número") { showReports() }
        addAction("PERSONAL Y NÓMINA", "Perfiles, días de pago, faltas, sueldos y movimientos") {
            startActivity(Intent(this, PayrollActivity::class.java))
        }
        addAction("CLAVE DEL PROPIETARIO", "La misma clave que utiliza SENE VENTAS") { askOwnerKey() }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun addAction(title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 16); setBackgroundColor(-1); elevation = 3f }
        card.addView(TextView(this).apply { text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()) })
        card.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(0xFF64748B.toInt()); setPadding(0, 5, 0, 0) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = 12
        root.addView(card, lp); card.setOnClickListener { action() }
    }
    private fun askOwnerKey() {
        val input = EditText(this).apply { hint = "Clave del propietario (mínimo 8 caracteres)"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val dlg = AlertDialog.Builder(this).setTitle("CLAVE DEL PROPIETARIO")
            .setMessage("Introduce la misma clave que utilizas en SENE VENTAS. Si eres cliente nuevo, crea aquí tu clave y consérvala.")
            .setView(input).setPositiveButton("GUARDAR", null).setNegativeButton("CANCELAR", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.length < 8) { input.error = "Mínimo 8 caracteres"; return@setOnClickListener }
                if (!OwnerKeyStore.save(this, value)) { input.error = if (OwnerKeyStore.hasKey(this)) "La clave no coincide con la ya registrada" else "No se pudo guardar"; return@setOnClickListener }
                dlg.dismiss(); Toast.makeText(this, "Clave del propietario guardada", Toast.LENGTH_LONG).show(); handleIncoming(intent)
            }
        }
        dlg.show()
    }
    private fun showInventory(category: String) {
        val key = OwnerKeyStore.read(this)
        if (key == null) { askOwnerKey(); return }
        if (SharedInventoryManager.savedTree(this) == null) { SharedInventoryManager.chooseFolder(this); return }
        try {
            var snapshots = SharedInventoryManager.list(this, key, category)
            if (category == "INVENTARIO_MAESTRO" && snapshots.isEmpty()) {
                val source = SharedInventoryManager.list(this, key, "INVENTARIO_SENE").firstOrNull()
                if (source != null) {
                    SharedInventoryManager.save(this, key, "INVENTARIO_MAESTRO", source.items, source.updated)
                    snapshots = SharedInventoryManager.list(this, key, category)
                }
            }
            val latest = snapshots.firstOrNull()
            val title = if (category == "INVENTARIO_MAESTRO") "INVENTARIO MAESTRO" else "INVENTARIO SENE"
            if (latest == null) { info(title, "No hay archivos de esta categoría. Los TXT anteriores se pueden leer sin modificarlos; los nuevos respaldos se guardarán cifrados."); return }
            val detail = buildString {
                append("Archivo: ").append(latest.fileName).append("\nActualizado: ").append(latest.updated)
                append("\nProductos: ").append(latest.items.size)
                if (latest.legacy) append("\n\nTXT anterior sin cifrado; se conservó intacto.")
                append("\n\n")
                latest.items.take(80).forEach {
                    append(it.code).append(" · ").append(it.name).append("\nExistencia: ").append(it.stock).append(" | Precio: $").append(it.price)
                    if (it.aisle.isNotBlank() || it.shelf.isNotBlank()) append("\nPasillo: ").append(it.aisle).append(" | Estante: ").append(it.shelf)
                    append("\n\n")
                }
                if (latest.items.size > 80) append("Se muestran los primeros 80 productos.")
                append("\nRespaldos encontrados: ").append(snapshots.size)
            }
            AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("CERRAR", null).show()
        } catch (e: Exception) { info("NO SE PUDO ABRIR EL INVENTARIO", e.message ?: "Verifica la clave y los permisos. El archivo original permanece intacto.") }
    }
    private fun importReport(message: String) {
        val key = OwnerKeyStore.read(this)
        if (key == null) { info("CONFIGURA TU CLAVE", "Primero introduce la clave del propietario para leer este reporte."); askOwnerKey(); return }
        try {
            val report = SeneReportReader.read(message, key)
            if (SeneAdminStore.reports(this).any { it.id == report.id }) {
                Toast.makeText(this, "Este reporte ya fue recibido. No se volvió a procesar.", Toast.LENGTH_LONG).show(); return
            }
            if (!SeneAdminStore.saveReport(this, report)) throw IllegalStateException("No se pudo guardar el reporte.")
            var reportBackupNote = ""
            if (SharedInventoryManager.savedTree(this) != null) {
                try {
                    val archive = SharedInventoryManager.saveReport(this, report, message)
                    reportBackupNote = "\\nRespaldo compartido: $archive"
                } catch (e: Exception) {
                    reportBackupNote = "\\nEl reporte quedó guardado en SENE ADMIN, pero no se pudo copiar a la carpeta compartida: " + (e.message ?: "error")
                }
            }
            val isOrder = report.body.startsWith("SENE-ORDER|1")
            if (isOrder) OwnerPayrollStore.importOrder(this, report) else OwnerPayrollStore.importProfile(this, report)
            var note = ""
            if (SharedInventoryManager.savedTree(this) != null) {
                try {
                    val saved = SharedInventoryManager.updateMasterFromReport(this, key, report.body, report.date + " " + report.time)
                    if (saved != null) note = "\nInventario maestro actualizado: $saved"
                } catch (e: Exception) { note = "\nEl reporte se guardó, pero no se actualizó el inventario maestro: " + (e.message ?: "error") }
            } else note = "\nVincula la carpeta INVENTARIO SENE para actualizar el inventario maestro."
            Toast.makeText(this, "Reporte recibido: ${report.type}", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this).setTitle(if (isOrder) "ORDEN RECIBIDA" else "REPORTE RECIBIDO")
                .setMessage("Trabajador: ${report.workerId}\nFecha: ${report.date} ${report.time}\nNúmero: ${report.number}$note")
                .setPositiveButton("VER REPORTES") { _, _ -> showReports() }.setNegativeButton("CERRAR", null).show()
        } catch (e: Exception) { info("NO SE PUDO IMPORTAR", e.message ?: "Reporte inválido. El mensaje original no se modificó.") }
    }
    private fun showReports() {
        val lines = SeneAdminStore.reports(this).sortedWith(compareBy<SeneReport> { it.workerId }.thenBy { it.date }.thenBy { it.time }.thenBy { it.number })
        val body = if (lines.isEmpty()) "Todavía no hay reportes importados. Comparte un mensaje hacia SENE ADMIN." else
            lines.groupBy { it.workerId }.entries.joinToString("\n\n") { (worker, reports) ->
                "TRABAJADOR: $worker\n" + reports.joinToString("\n\n") {
                    "FECHA: ${it.date} ${it.time}\nNÚMERO: ${it.number}\nTIPO: ${it.type}\n\n${it.body}"
                }
            }
        AlertDialog.Builder(this).setTitle("REPORTES RECIBIDOS").setMessage(body).setPositiveButton("CERRAR", null).show()
    }
    private fun info(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ACEPTAR", null).show() }
}
