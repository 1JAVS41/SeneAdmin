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
        super.onCreate(savedInstanceState)
        showHome()
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey()
        else handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { importReport(it) }
        }
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SENE ADMIN"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF123B68.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Panel del propietario"; textSize = 15f
            setTextColor(0xFF526579.toInt()); setPadding(0, 4, 0, 20)
        })
        addAction("INVENTARIO MAESTRO", "Administrar el inventario general") {
            info("INVENTARIO MAESTRO", "El módulo se implementará separado de INVENTARIO SENE.")
        }
        addAction("INVENTARIO SENE", "Abrir la carpeta compartida con SENE VENTAS") {
            info("INVENTARIO SENE", "La selección SAF y el formato común se conectarán en la siguiente etapa.")
        }
        addAction("REPORTES DE TRABAJADORES", "Importar y consultar reportes recibidos") { showReports() }
        addAction("PERSONAL Y NÓMINA", "Pagos, faltas, préstamos y consumos") {
            info("PERSONAL Y NÓMINA", "El registro por trabajador y el historial de nómina se implementarán en la siguiente etapa.")
        }
        addAction("CLAVE DEL PROPIETARIO", "Usar la misma clave en ambas aplicaciones") { askOwnerKey() }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addAction(title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 16)
            setBackgroundColor(0xFFFFFFFF.toInt()); elevation = 3f
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF123B68.toInt())
        })
        card.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(0xFF64748B.toInt()); setPadding(0, 5, 0, 0)
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = 12
        root.addView(card, lp)
        card.setOnClickListener { action() }
    }

    private fun askOwnerKey() {
        val input = EditText(this).apply {
            hint = "Clave del propietario (mínimo 8 caracteres)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dlg = AlertDialog.Builder(this).setTitle("CLAVE DEL PROPIETARIO")
            .setMessage("Introduce la misma clave que utilizas en SENE VENTAS. Si eres cliente nuevo, crea aquí tu clave y consérvala.")
            .setView(input).setPositiveButton("GUARDAR", null).setNegativeButton("CANCELAR", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.length < 8) { input.error = "Mínimo 8 caracteres"; return@setOnClickListener }
                if (!OwnerKeyStore.save(this, value)) { input.error = "No se pudo guardar la clave"; return@setOnClickListener }
                dlg.dismiss()
                Toast.makeText(this, "Clave del propietario guardada en este teléfono", Toast.LENGTH_LONG).show()
                handleIncoming(intent)
            }
        }
        dlg.show()
    }

    private fun importReport(message: String) {
        val key = OwnerKeyStore.read(this)
        if (key == null) {
            info("CONFIGURA TU CLAVE", "Primero introduce la clave del propietario para leer este reporte.")
            askOwnerKey()
            return
        }
        try {
            val report = SeneReportReader.read(message, key)
            if (!SeneAdminStore.saveReport(this, report)) {
                throw IllegalStateException("No se pudo guardar el reporte en el historial.")
            }
            Toast.makeText(this, "Reporte recibido: ${report.type}", Toast.LENGTH_LONG).show()
            showReports()
        } catch (e: Exception) {
            info("NO SE PUDO IMPORTAR", e.message ?: "Reporte inválido. El mensaje original no se modificó.")
        }
    }

    private fun showReports() {
        val lines = SeneAdminStore.reports(this).sortedWith(compareBy<SeneReport> { it.date }.thenBy { it.time }).asReversed()
        val body = if (lines.isEmpty()) "Todavía no hay reportes importados.\n\nComparte un mensaje de reporte hacia SENE ADMIN." else
            lines.joinToString("\n\n") { "TRABAJADOR: ${it.workerId}\nFECHA: ${it.date} ${it.time}\nTIPO: ${it.type}\nNÚMERO: ${it.number}\n\n${it.body}" }
        AlertDialog.Builder(this).setTitle("REPORTES RECIBIDOS").setMessage(body).setPositiveButton("CERRAR", null).show()
    }

    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ACEPTAR", null).show()
    }
}
