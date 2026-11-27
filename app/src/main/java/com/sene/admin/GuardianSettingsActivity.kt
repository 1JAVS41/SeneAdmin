package com.sene.admin

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Configuración local y envío manual con consentimiento explícito del propietario. */
class GuardianSettingsActivity : Activity() {
    private lateinit var installation: EditText
    private lateinit var token: EditText
    private lateinit var consent: CheckBox
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "SENE GUARDIÁN"
            textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Conexión con SENE VENTAS: copia aquí el IDENTIFICADOR DE INSTALACIÓN y el TOKEN PARA SENEADMIN que aparecen en la pantalla CONECTA TU MUSE. La captura agregada se envía únicamente cuando el propietario lo solicita."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })
        installation = EditText(this).apply {
            hint = "Identificador de instalación (installationId)"
            setSingleLine(true)
        }
        root.addView(installation)
        token = EditText(this).apply {
            hint = "Token de ingestión (ingestToken)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(token)
        consent = CheckBox(this).apply {
            text = "Autorizo a SENE GUARDIÁN a enviar el indicadores agregados de inventario, personal, nómina, asistencia, reportes y productos dañados de este dispositivo."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(consent)
        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(12)) }
        root.addView(status)

        val save = Button(this).apply { text = "GUARDAR CONFIGURACIÓN" }
        root.addView(save)
        save.setOnClickListener {
            try {
                val enteredToken = token.text.toString().trim()
                if (enteredToken.isNotEmpty()) {
                    GuardianConfigStore.save(this, installation.text.toString().trim(), enteredToken, consent.isChecked)
                    token.text?.clear()
                } else {
                    GuardianConfigStore.updateConsent(this, consent.isChecked)
                }
                status.text = if (consent.isChecked) "Configuración protegida. El envío manual está autorizado." else
                    "Configuración protegida. El envío está desactivado porque no hay consentimiento."
                Toast.makeText(this, "Configuración guardada de forma cifrada.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                status.text = e.message ?: "No se pudo guardar la configuración."
            }
        }

        val send = Button(this).apply { text = "ENVIAR CAPTURA AHORA" }
        root.addView(send)
        send.setOnClickListener {
            val config = GuardianConfigStore.read(this)
            if (config == null) {
                status.text = "Primero guarda el identificador y el token de esta instalación."
                return@setOnClickListener
            }
            if (!config.consent || !consent.isChecked) {
                status.text = "El envío está desactivado. Marca el consentimiento y guarda la configuración."
                return@setOnClickListener
            }
            send.isEnabled = false
            status.text = "Preparando captura administrativa agregada..."
            Thread {
                val result = try {
                    GuardianSnapshotExporter.export(applicationContext, config.installationId, config.ingestToken)
                } catch (_: Exception) {
                    GuardianSnapshotExporter.ExportResult(false, 0, "No se pudo preparar o enviar la captura.")
                }
                runOnUiThread {
                    send.isEnabled = true
                    status.text = result.message
                }
            }.start()
        }

        val disable = Button(this).apply { text = "DESACTIVAR Y BORRAR CREDENCIALES" }
        root.addView(disable)
        disable.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("DESACTIVAR SENE GUARDIÁN")
                .setMessage("Se borrarán del dispositivo el identificador y el token guardados. Esto no revoca la instalación del servidor.")
                .setNegativeButton("CANCELAR", null)
                .setPositiveButton("DESACTIVAR") { _, _ ->
                    GuardianConfigStore.clear(this)
                    installation.text?.clear()
                    token.text?.clear()
                    consent.isChecked = false
                    status.text = "GUARDIÁN desactivado en este dispositivo."
                }.show()
        }

        GuardianConfigStore.read(this)?.let {
            installation.setText(it.installationId)
            consent.isChecked = it.consent
            status.text = if (it.consent) "Configuración protegida y consentimiento activo." else "Configuración protegida; consentimiento desactivado."
        }
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(-1, -2))
        })
    }
}
