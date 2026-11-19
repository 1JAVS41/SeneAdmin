package com.sene.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey() else handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SharedInventoryManager.REQUEST_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                SharedInventoryManager.acceptFolder(this, uri)
                Toast.makeText(this, "Carpeta INVENTARIO SENE vinculada", Toast.LENGTH_LONG).show()
                showHome()
            } catch (e: Exception) {
                info("NO SE PUDO VINCULAR", e.message ?: "Android no concedió acceso a la carpeta.")
            }
        }
    }

    private fun handleIncoming(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            incoming.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { importReports(it) }
        }
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 24)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SENE ADMIN"; textSize = 25f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Control automático de nómina y reportes."
            textSize = 14f; setTextColor(0xFF526579.toInt()); setPadding(0, 6, 0, 22)
        })
        fun mainOption(label: String, subtitle: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(18, 26, 18, 26)
                setBackgroundColor(0xFF1769AA.toInt())
                elevation = 4f
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            card.addView(TextView(this).apply {
                text = label; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER; setTextColor(-1)
            })
            card.addView(TextView(this).apply {
                text = subtitle; textSize = 13f
                gravity = android.view.Gravity.CENTER; setTextColor(0xFFE7F2FF.toInt())
                setPadding(0, 8, 0, 0)
            })
            val lp = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 16
            root.addView(card, lp)
        }
        mainOption("NÓMINA", "Perfiles de trabajadores y pagos") {
            startActivity(Intent(this, PayrollActivity::class.java))
        }
        mainOption("OTROS REPORTES", "Reportes organizados por trabajador") {
            showReports()
        }
        mainOption("INVENTARIO SENE", "Último inventario recibido, organizado por producto") {
            showSeneInventory()
        }
        mainOption("PEGAR REPORTES DE WHATSAPP", "Importar reportes enviados por tus trabajadores") {
            showPasteDialog()
        }
        mainOption("AJUSTES", "PIN de acceso y clave de cifrado") {
            showSettings()
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showSeneInventory() {
        val latest = SeneAdminStore.reports(this)
            .sortedWith(compareByDescending<SeneReport> { it.date }.thenByDescending { it.time }.thenByDescending { it.number })
            .firstNotNullOfOrNull { report ->
                SharedInventoryManager.inventoryFromReport(report)?.let { report to it }
            }

        if (latest == null) {
            info("INVENTARIO SENE", "Todavía no se ha recibido un reporte que contenga inventario.")
            return
        }
        val (report, inventory) = latest
        val detail = buildString {
            append("ÚLTIMO INVENTARIO RECIBIDO")
            append("\nActualizado: ").append(report.date).append(" ").append(report.time)
            append("\nProductos: ").append(inventory.items.size)
            append("\n\nINVENTARIO ORGANIZADO")
            inventory.items.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { item ->
                append("\n\n").append(item.name)
                append("\nCódigo: ").append(item.code)
                append("\nPrecio: ").append(OwnerPayrollStore.money((item.price * 100.0).toLong()))
                append("\nExistencia: ").append(item.stock)
                if (item.aisle.isNotBlank()) append("\nPasillo: ").append(item.aisle)
                if (item.shelf.isNotBlank()) append("\nEstante: ").append(item.shelf)
            }
        }
        AlertDialog.Builder(this).setTitle("INVENTARIO SENE")
            .setMessage(detail).setPositiveButton("CERRAR", null).show()
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "Mantén presionado aquí y selecciona PEGAR"
            minLines = 8; maxLines = 16; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextIsSelectable(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 4)
            addView(TextView(this@MainActivity).apply {
                text = "Pega aquí el texto completo de WhatsApp. Puedes incluir varios reportes uno después de otro."
                textSize = 13f; setTextColor(0xFF526579.toInt()); setPadding(0, 0, 0, 10)
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("IMPORTAR REPORTES")
            .setView(box).setPositiveButton("PROCESAR", null).setNegativeButton("CANCELAR", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                if (raw.isBlank()) { input.error = "Pega al menos un reporte"; return@setOnClickListener }
                dialog.dismiss()
                importReports(raw)
            }
        }
        dialog.show()
    }

    private fun showSettings() {
        val options = arrayOf("Vincular / cambiar carpeta compartida", "Clave del propietario",
            "Ver inventario maestro", "Ver inventario SENE", "Ver todos los reportes")
        AlertDialog.Builder(this).setTitle("AJUSTES").setItems(options) { _, which ->
            when (which) {
                0 -> SharedInventoryManager.chooseFolder(this)
                1 -> askOwnerKey()
                2 -> showInventory("INVENTARIO_MAESTRO")
                3 -> showInventory("INVENTARIO_SENE")
                4 -> showReports()
            }
        }.setNegativeButton("CERRAR", null).show()
    }

    private fun askOwnerKey() {
        val input = EditText(this).apply {
            hint = "Clave del propietario (mínimo 8 caracteres)"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dlg = AlertDialog.Builder(this).setTitle("CLAVE DEL PROPIETARIO")
            .setMessage("Introduce la misma clave que utilizas en SENE VENTAS. Si eres cliente nuevo, crea aquí tu clave y consérvala.")
            .setView(input).setPositiveButton("GUARDAR", null).setNegativeButton("CANCELAR", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.length < 8) { input.error = "Mínimo 8 caracteres"; return@setOnClickListener }
                if (!OwnerKeyStore.save(this, value)) {
                    input.error = if (OwnerKeyStore.hasKey(this)) "La clave no coincide con la ya registrada" else "No se pudo guardar"
                    return@setOnClickListener
                }
                dlg.dismiss()
                Toast.makeText(this, "Clave del propietario guardada", Toast.LENGTH_LONG).show()
                showHome()
                handleIncoming(intent)
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
            if (latest == null) {
                info(title, "No hay archivos de esta categoría. Los TXT anteriores se pueden leer sin modificarlos; los nuevos respaldos se guardarán cifrados.")
                return
            }
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
        } catch (e: Exception) {
            info("NO SE PUDO ABRIR EL INVENTARIO", e.message ?: "Verifica la clave y los permisos. El archivo original permanece intacto.")
        }
    }

    private fun importReports(raw: String) {
        val key = OwnerKeyStore.read(this)
        if (key == null) {
            info("CONFIGURA TU CLAVE", "Primero introduce la clave del propietario para leer estos reportes.")
            askOwnerKey()
            return
        }
        // WhatsApp puede anteponer fecha, remitente o espacios al copiar el mensaje.
        // Se localiza cada cabecera donde aparezca y se entrega al lector desde su primera línea real.
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val chunks = normalized.split(Regex("(?=SENE-ADMIN-REPORT\\|1)"))
            .map { it.trim() }
            .filter { it.startsWith("SENE-ADMIN-REPORT|1") }
        var imported = 0
        var duplicates = 0
        val errors = mutableListOf<String>()
        chunks.forEachIndexed { index, message ->
            try {
                val report = SeneReportReader.read(message, key)
                if (SeneAdminStore.reports(this).any { it.id == report.id }) duplicates++
                else { processReport(report, message, key); imported++ }
            } catch (e: Exception) {
                errors.add("Reporte " + (index + 1) + ": " + (e.message ?: "formato no válido"))
            }
        }
        // Crear o actualizar la nómina solo después de procesar el lote completo.
        OwnerPayrollStore.profiles(this).filter { it.weeklySalaryCents > 0L }.forEach { profile ->
            try { OwnerPayrollStore.createOrGetPayroll(this, profile.id) } catch (_: Exception) { }
        }
        showHome()
        val summary = buildString {
            append("Nuevos reportes: ").append(imported)
            append("\nDuplicados omitidos: ").append(duplicates)
            if (errors.isNotEmpty()) {
                append("\n\nNo se pudieron leer:")
                errors.take(8).forEach { append("\n• ").append(it) }
                if (errors.size > 8) append("\nY ").append(errors.size - 8).append(" más.")
            }
        }
        AlertDialog.Builder(this).setTitle("IMPORTACIÓN TERMINADA").setMessage(summary)
            .setPositiveButton("ACEPTAR", null).show()
    }

    private fun processReport(report: SeneReport, message: String, key: String) {
        if (!SeneAdminStore.saveReport(this, report)) throw IllegalStateException("No se pudo guardar el reporte.")
        if (SharedInventoryManager.savedTree(this) != null) {
            try { SharedInventoryManager.saveReport(this, report, message, key) } catch (_: Exception) { }
        }
        if (report.body.startsWith("SENE-ORDER|1")) OwnerPayrollStore.importOrder(this, report)
        else OwnerPayrollStore.importProfile(this, report)
        if (SharedInventoryManager.savedTree(this) != null) {
            try { SharedInventoryManager.updateMasterFromReport(this, key, report.body, report.date + " " + report.time) }
            catch (_: Exception) { }
        }
    }

    private fun showReports() {
        if (SeneAdminStore.reports(this).isEmpty()) {
            val key = OwnerKeyStore.read(this)
            if (key != null) SharedInventoryManager.archivedReports(this, key).forEach { report ->
                if (SeneAdminStore.saveReport(this, report)) {
                    if (report.body.startsWith("SENE-ORDER|1")) OwnerPayrollStore.importOrder(this, report)
                    else OwnerPayrollStore.importProfile(this, report)
                }
            }
        }
        val reports = SeneAdminStore.reports(this)
        val profiles = (OwnerPayrollStore.profiles(this).map { it.id to it.name } +
            reports.map { it.workerId to (OwnerPayrollStore.profile(this, it.workerId)?.name ?: it.workerId) })
            .distinctBy { it.first }.sortedBy { it.second.lowercase(Locale.ROOT) }
        if (profiles.isEmpty()) {
            info("OTROS REPORTES", "Todavía no hay perfiles ni reportes recibidos.")
            return
        }
        val labels = profiles.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle("REPORTES · TRABAJADORES")
            .setItems(labels) { _, which ->
                val workerId = profiles[which].first
                val workerReports = reports.filter { it.workerId == workerId }
                    .sortedWith(compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number })
                val body = if (workerReports.isEmpty()) "Todavía no hay reportes para este trabajador."
                else workerReports.joinToString("\n\n") {
                    "FECHA: " + it.date + " " + it.time + "\nNÚMERO: " + it.number + "\nTIPO: " + it.type + "\n\n" + it.body
                }
                AlertDialog.Builder(this).setTitle("REPORTES · " + profiles[which].second)
                    .setMessage(body).setPositiveButton("CERRAR", null).show()
            }.setNegativeButton("CERRAR", null).show()
    }

    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ACEPTAR", null).show()
    }
}
