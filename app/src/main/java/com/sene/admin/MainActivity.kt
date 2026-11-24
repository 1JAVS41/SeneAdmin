package com.sene.admin

import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : AppCompatActivity() {
    @Volatile private var reportEnvelopeProcessing = false
    private lateinit var root: LinearLayout
    private var accessGranted = false
    private var biometricFallbackShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No mostrar la pantalla principal hasta crear el PIN o autenticar al dueño.
        // La limpieza de archivos no debe retrasar el acceso inicial a SeneAdmin.
        val cleanupContext = applicationContext
        Thread {
            try { SeneAdminStore.cleanupIfDue(cleanupContext) } catch (_: Exception) { }
            try { InventoryLocalStore.cleanupIfDue(cleanupContext) } catch (_: Exception) { }
        }.start()
        if (!AccessPinStore.hasPin(this)) {
            askCreatePin {
                accessGranted = true
                showHome()
            }
        } else {
            requestMainAccess()
        }
    }

    private fun requestMainAccess() {
        accessGranted = false
        if (AccessPinStore.fingerprintEnabled(this) &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricFallbackShown = false
            var failedAttempts = 0
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        accessGranted = true
                        showHome()
                    }
                    override fun onAuthenticationFailed() {
                        failedAttempts++
                        if (failedAttempts >= 5 && !biometricFallbackShown) {
                            biometricFallbackShown = true
                            Toast.makeText(this@MainActivity, "Huella no reconocida. Intenta con tu PIN", Toast.LENGTH_LONG).show()
                            // Cancelar el diálogo biométrico antes de presentar el teclado del PIN.
                            window.decorView.post {
                                promptRef?.cancelAuthentication()
                                showPinAccess()
                            }
                        }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!biometricFallbackShown) {
                            biometricFallbackShown = true
                            showPinAccess()
                        }
                    }
                })
            promptRef = prompt
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("SENE ADMIN")
                .setSubtitle("Desbloquea para entrar a la pantalla principal")
                .setNegativeButtonText("USAR PIN")
                .build())
        } else {
            showPinAccess()
        }
    }

    private var promptRef: BiometricPrompt? = null

    private fun showPinAccess() {
        if (isFinishing || isDestroyed || accessGranted) return
        val pin = EditText(this).apply {
            hint = "Tu PIN de SeneAdmin"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("DESBLOQUEAR SENE ADMIN")
            .setMessage("Introduce el PIN que estableciste al configurar la aplicación.")
            .setView(pin)
            .setCancelable(false)
            .setPositiveButton("ENTRAR", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AccessPinStore.matches(this, pin.text.toString())) {
                    accessGranted = true
                    dialog.dismiss()
                    showHome()
                } else {
                    pin.text?.clear()
                    pin.error = "PIN incorrecto"
                }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // Recuperar datos con el límite interno de 15 minutos; no consultar al servidor
        // cada vez que el dueño cambia de pantalla. El buzón se procesa tras la descarga.
        if (ServerPairing.isLinked(this)) {
            AdminPayrollRequestSync.sync(this, false, onComplete = {
                if (!isFinishing && !isDestroyed) processServerReportEnvelopes()
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SharedInventoryManager.REQUEST_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                SharedInventoryManager.acceptFolder(this, uri)
                Toast.makeText(this, "Carpeta INVENTARIO SENE vinculada", Toast.LENGTH_LONG).show()
                showHome()
                checkForWorkerRecovery()
            } catch (e: Exception) {
                info("NO SE PUDO VINCULAR", e.message ?: "Android no concedió acceso a la carpeta.")
            }
        }
    }

    /**
     * En un teléfono nuevo, ofrece recuperar los trabajadores y su historial desde
     * los reportes cifrados que ya existen en la carpeta compartida. Nunca modifica
     * ni elimina los archivos de origen.
     */
    private fun checkForWorkerRecovery(after: () -> Unit = {}) {
        val migrationPrefs = getSharedPreferences("sene_admin_worker_recovery", MODE_PRIVATE)
        if (migrationPrefs.getBoolean("completed", false)) {
            after()
            return
        }
        val key = OwnerKeyStore.read(this)
        if (key.isNullOrBlank() || SharedInventoryManager.savedTree(this) == null) {
            after()
            return
        }

        val (progress, updateProgress) = createProcessingDialog(3)
        updateProgress(0, "Buscando respaldos de trabajadores...")
        progress.window?.decorView?.post {
            Thread {
                var reports: List<SeneReport> = emptyList()
                var failure: String? = null
                try {
                    reports = SharedInventoryManager.archivedReports(this@MainActivity, key)
                        .distinctBy { it.id }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) updateProgress(2, "Identificando los perfiles disponibles...")
                    }
                } catch (e: Exception) {
                    failure = e.message ?: "No se pudieron revisar los respaldos."
                }

                val workerReports = reports.filter { report ->
                    !report.body.startsWith("SENE-ORDER|1") &&
                        report.body.replace("\r", "").lines().any {
                            it.trim().startsWith("TRABAJADOR:", true)
                        }
                }
                val candidates = workerReports.groupBy { it.workerId }.mapNotNull { (workerId, items) ->
                    val latest = items.maxWithOrNull(
                        compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number }
                    ) ?: return@mapNotNull null
                    val name = latest.body.replace("\r", "").lines()
                        .firstOrNull { it.trim().startsWith("TRABAJADOR:", true) }
                        ?.substringAfter(":")?.trim().orEmpty().ifBlank { workerId }
                    workerId to name
                }.sortedBy { it.second.lowercase(Locale.ROOT) }

                runOnUiThread {
                    progress.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failure != null) {
                        info("NO SE PUDIERON REVISAR LOS RESPALDOS", failure!!)
                        after()
                        return@runOnUiThread
                    }
                    if (candidates.isEmpty()) {
                        after()
                        return@runOnUiThread
                    }

                    val names = candidates.joinToString("\n") { "• " + it.second }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("RECUPERAR TRABAJADORES")
                        .setMessage(
                            "Encontramos estos perfiles en los reportes guardados de INVENTARIO SENE:\n\n" +
                                names +
                                "\n\n¿Reconoces a estos trabajadores? Si respondes SÍ, SENE ADMIN recuperará sus perfiles, " +
                                "asistencias, solicitudes y movimientos de nómina que estén disponibles en los reportes, " +
                                "además del historial de reportes. Los archivos originales no se modificarán."
                        )
                        .setPositiveButton("SÍ, LOS RECONOZCO") { _, _ ->
                            restoreArchivedWorkerHistory(reports, after)
                        }
                        .setNegativeButton("NO LOS RECONOZCO") { _, _ ->
                            migrationPrefs.edit().putBoolean("completed", true).commit()
                            after()
                        }
                        .setCancelable(false)
                        .show()
                }
            }.start()
        }
    }

    /** Reproduce localmente los reportes en orden cronológico para reconstruir los datos disponibles. */
    private fun restoreArchivedWorkerHistory(reports: List<SeneReport>, after: () -> Unit = {}) {
        val ordered = reports.sortedWith(
            compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number }
        )
        val total = (ordered.size + 1).coerceAtLeast(1)
        val (progress, updateProgress) = createProcessingDialog(total)
        updateProgress(0, "Preparando la recuperación...")
        progress.window?.decorView?.post {
            Thread {
                var restoredProfiles = 0
                var restoredReports = 0
                val restoredReportBatch = mutableListOf<SeneReport>()
                val errors = mutableListOf<String>()
                ordered.forEachIndexed { index, report ->
                    try {
                        val ok = if (report.body.startsWith("SENE-ORDER|1")) {
                            OwnerPayrollStore.importOrder(this@MainActivity, report)
                        } else {
                            OwnerPayrollStore.importProfile(this@MainActivity, report)
                        }
                        if (!ok) {
                            errors.add("No se pudieron recuperar los datos de ${report.workerId}.")
                        } else {
                            if (!report.body.startsWith("SENE-ORDER|1")) restoredProfiles++
                            restoredReportBatch.add(report)
                            if(restoredReportBatch.size>=10) {
                                if(SeneAdminStore.saveReports(this@MainActivity,restoredReportBatch)) {
                                    restoredReports+=restoredReportBatch.distinctBy{it.id}.size
                                    restoredReportBatch.clear()
                                } else errors.add("No se pudo guardar un bloque del historial local.")
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Reporte ${report.number}: " + (e.message ?: "error de recuperación"))
                    }
                    val completed = index + 1
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            updateProgress(completed, "Recuperando historial ${completed} de ${ordered.size}...")
                        }
                    }
                }
                try {
                    if(!SeneAdminStore.saveReports(this@MainActivity,restoredReportBatch))
                        errors.add("No se pudo guardar el historial local de reportes.")
                    else restoredReports+=restoredReportBatch.distinctBy{it.id}.size
                } catch(e:Exception) {
                    errors.add(e.message ?: "No se pudo guardar el historial local de reportes.")
                }
                try {
                    OwnerPayrollStore.profiles(this@MainActivity)
                        .filter { it.weeklySalaryCents > 0L }
                        .forEach { profile ->
                            OwnerPayrollStore.createOrGetPayroll(this@MainActivity, profile.id)
                        }
                } catch (e: Exception) {
                    errors.add(e.message ?: "No se pudo preparar la nómina.")
                }
                restoredProfiles = OwnerPayrollStore.profiles(this@MainActivity).size
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) updateProgress(total, "Finalizando la recuperación...")
                    progress.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (errors.isEmpty()) {
                        getSharedPreferences("sene_admin_worker_recovery", MODE_PRIVATE)
                            .edit().putBoolean("completed", true).commit()
                    }
                    showHome()
                    val summary = buildString {
                        append("Perfiles y datos de trabajadores procesados: ").append(restoredProfiles)
                        append("\nReportes recuperados: ").append(restoredReports)
                        if (errors.isNotEmpty()) {
                            append("\n\nAlgunos datos no se pudieron recuperar:")
                            errors.take(6).forEach { append("\n• ").append(it) }
                            if (errors.size > 6) append("\nY ").append(errors.size - 6).append(" más.")
                            append("\n\nPuedes volver a vincular la carpeta para reintentar. Los reportes ya recuperados no se duplicarán.")
                        } else {
                            append("\n\nLa recuperación terminó. Los respaldos originales permanecen intactos.")
                        }
                    }
                    AlertDialog.Builder(this@MainActivity).setTitle("RECUPERACIÓN TERMINADA")
                        .setMessage(summary).setPositiveButton("ACEPTAR") { _, _ -> after() }.show()
                }
            }.start()
        }
    }

    private fun showPairingDialog() {
        if(!AccessPinStore.hasPin(this)) { askCreatePin(); return }
        val pin=EditText(this).apply {
            hint="PIN de SeneAdmin"
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("VERIFICAR AL PATRÓN")
            .setMessage("Confirma tu PIN de SeneAdmin para administrar la vinculación de este negocio.")
            .setView(pin).setPositiveButton("CONTINUAR",null).setNegativeButton("CANCELAR",null).create().also{dialog->
                dialog.setOnShowListener{
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                        if(!AccessPinStore.matches(this,pin.text.toString())) {
                            pin.error="PIN incorrecto"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        showPairingCodeDialog()
                    }
                }
                dialog.show()
            }
    }

    private fun showPairingCodeDialog() {
        if(ServerPairing.isLinked(this)) {
            AlertDialog.Builder(this).setTitle(ServerPairing.businessName(this))
                .setMessage("Conectado a SENE SERVER.\n\nEste negocio está vinculado a SeneAdmin en este dispositivo.")
                .setPositiveButton("CERRAR",null).setNeutralButton("ACTUALIZAR"){_,_->updateFromServer()}.show()
            return
        }
        val code=EditText(this).apply {
            hint="Código temporal de SENE VENTAS"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("VINCULAR SENE ADMIN")
            .setMessage("En SENE VENTAS entra a SOY PATRÓN → VINCULAR SENE ADMIN y genera un código temporal. Escríbelo aquí. Es de un solo uso y tiene una vigencia de 10 minutos.")
            .setView(code).setPositiveButton("VINCULAR",null).setNegativeButton("CANCELAR",null).create().also{dialog->
                dialog.setOnShowListener{
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                        val value=code.text.toString().trim().uppercase(Locale.ROOT)
                        if(value.length<5){code.error="Ingresa el código completo";return@setOnClickListener}
                        dialog.dismiss()
                        val progress=AlertDialog.Builder(this).setTitle("Vinculando negocio")
                            .setMessage("Validando el código temporal con SERVER-SENE...").setCancelable(false).create()
                        progress.show()
                        Thread{
                            val response=try{ServerPairing.claim(this,value)}
                                catch(e:Exception){org.json.JSONObject().put("_error",e.message?:"No fue posible conectar")}
                            val saved=response.optInt("_http_status",0) in 200..299 && ServerPairing.saveClaim(this,response)
                            runOnUiThread{
                                progress.dismiss()
                                if(isFinishing||isDestroyed)return@runOnUiThread
                                if(saved){
                                    Toast.makeText(this,"SeneAdmin quedó vinculado con "+ServerPairing.businessName(this),Toast.LENGTH_LONG).show()
                                    AdminPayrollRequestSync.sync(this,true,onComplete={if(!isFinishing&&!isDestroyed)processServerReportEnvelopes()})
                                    showHome()
                                }else{
                                    AlertDialog.Builder(this).setTitle("NO SE PUDO VINCULAR")
                                        .setMessage(response.optString("message",response.optString("_error",
                                            "El código no es válido, caducó o SERVER-SENE aún no tiene habilitada esta función.")))
                                        .setPositiveButton("ACEPTAR",null).show()
                                }
                            }
                        }.start()
                    }
                }
                dialog.show()
            }
    }


    private fun showHome() {
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        val navy = 0xFF145A43.toInt()
        val pale = 0xFFF4F7F4.toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(pale)
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(navy, 0xFF237A55.toInt(), 0xFF27875B.toInt())
            ).apply { cornerRadius = dp(25).toFloat() }
            elevation = dp(5).toFloat()
        }
        val linked = ServerPairing.isLinked(this)
        hero.addView(TextView(this).apply {
            text = if(linked) ServerPairing.businessName(this@MainActivity) else "SENE ADMIN"
            textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(-1)
        })
        hero.addView(TextView(this).apply {
            text = if(linked) "Conectado a SENE SERVER" else "Sin conexión con SENE SERVER"
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if(linked) 0xFFDDF4E5.toInt() else 0xFFFFE5D8.toInt())
            setPadding(0, dp(7), 0, 0)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        fun mainOption(icon: String, label: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(15), dp(16), dp(15))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1); cornerRadius = dp(19).toFloat()
                    setStroke(dp(1), 0xFFE4EAE5.toInt())
                }
                elevation = dp(2).toFloat()
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            card.addView(TextView(this).apply {
                text = icon; textSize = 23f; gravity = android.view.Gravity.CENTER
                setTextColor(navy)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE6F3EA.toInt()); cornerRadius = dp(15).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(6), 0)
            }
            labels.addView(TextView(this).apply {
                text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF194A39.toInt())
            })
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = "›"; textSize = 28f; setTextColor(0xFF237A55.toInt())
            })
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        mainOption("♙", "MIS TRABAJADORES") {
            startActivity(Intent(this, PayrollActivity::class.java))
        }
        mainOption("↔", "VINCULAR") {
            showPairingDialog()
        }
        mainOption("↻", "ACTUALIZAR") {
            updateFromServer()
        }
        mainOption("▦", "INVENTARIO") {
            showSeneInventory()
        }
        mainOption("!", "PENDIENTES") {
            showPendingReviews()
        }
        mainOption("⚙", "AJUSTES") {
            showSettings()
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false; addView(root)
        })
        // Procesar una sola vez lo ya guardado localmente al mostrar Inicio.
        // La consulta al servidor queda en onResume con límite de 15 minutos.
        processServerReportEnvelopes()
    }

    private fun updateFromServer() {
        if(!ServerPairing.isLinked(this)) {
            Toast.makeText(this, "Primero vincula SeneAdmin.", Toast.LENGTH_LONG).show()
            return
        }
        AdminPayrollRequestSync.sync(this, true, onComplete = {
            if (!isFinishing && !isDestroyed) processServerReportEnvelopes()
        })
        Toast.makeText(this, "Actualizando información...", Toast.LENGTH_SHORT).show()
    }

    private fun showServerReportInbox() {
        // El buzón muestra exclusivamente lo que ya está guardado en el teléfono.
        val events = ReportInboxStore.events(this)
        if (events.length() == 0) {
            AlertDialog.Builder(this).setTitle("REPORTES DEL SERVIDOR")
                .setMessage("No hay reportes pendientes en el buzón local. Cuando SERVER-SENE entregue reportes, se guardarán aquí de forma cifrada.")
                .setPositiveButton("ACEPTAR", null).show()
            return
        }
        val lines = (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val worker = event.optString("w").ifBlank { "Trabajador" }
            val kindCode = event.optString("k")
            val kind = when (kindCode) {
                "[EC]" -> "Inicio de turno"
                "[CO]" -> "Cierre de jornada"
                "[CA]" -> "Cierre anticipado"
                "[AD]" -> "Cierre administrativo"
                "[SYNC]" -> "Reporte sincronizado"
                else -> kindCode.ifBlank { "Reporte" }
            }
            val date = event.optLong("i").takeIf { it > 0L }?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(it))
            }.orEmpty()
            "• $worker · $kind" + if (date.isBlank()) "" else " · $date"
        }
        val message = "Reportes recibidos y protegidos en este dispositivo:\n\n" +
            lines.joinToString("\n") +
            "\n\nLos reportes nuevos se descifran con la clave privada protegida de este dispositivo. Los reportes históricos conservan su compatibilidad y pueden requerir la clave anterior."
        val builder = AlertDialog.Builder(this).setTitle("BUZÓN DE REPORTES")
            .setMessage(message).setPositiveButton("CERRAR", null)
        builder.show()
    }

    /** Descifra e importa reportes en segundo plano; el ACK de recepción ya es durable. */
    private fun processServerReportEnvelopes() {
        if (reportEnvelopeProcessing) return
        reportEnvelopeProcessing = true
        val appContext = applicationContext
        Thread {
            var dialogTitle: String? = null
            var dialogMessage: String? = null
            try {
                val legacyKey = OwnerKeyStore.read(appContext)
                // La retención también queda fuera del hilo principal y nunca borra
                // respaldos sin disponer de la clave necesaria para verificarlos.
                if (!legacyKey.isNullOrBlank() && SharedInventoryManager.savedTree(appContext) != null) {
                    Thread {
                        try { SharedInventoryManager.cleanupAllIfDue(appContext, legacyKey) }
                        catch (_: Exception) { }
                    }.start()
                }
                val events = ReportInboxStore.events(appContext)
                val imported = mutableListOf<SeneReport>()
                val removeIds = mutableSetOf<String>()
                val failures = mutableListOf<String>()
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val id = event.optString("e")
                    try {
                        val encryptedPayload = event.optString("b")
                        val report = if (encryptedPayload.trimStart().startsWith("SENE-ADMIN-REPORT|2")) {
                            SeneReportV2Reader.read(encryptedPayload)
                        } else {
                            val key = legacyKey?.takeIf { it.isNotBlank() }
                                ?: throw IllegalStateException("Este reporte histórico requiere la clave de cifrado anterior.")
                            SeneReportReader.read(encryptedPayload, key)
                        }
                        val envelopeWorker = event.optString("w")
                        require(envelopeWorker.isBlank() || envelopeWorker == report.workerId) {
                            "El trabajador del sobre no coincide con el reporte cifrado."
                        }
                        val ok = when {
                            report.body.startsWith("SENE-ORDER|1") -> OwnerPayrollStore.importOrder(appContext, report)
                            report.type == SeneWireProtocol.SYNC && report.body.startsWith("SENE VENTAS  •  REPORTE DE MOVIMIENTOS") -> true
                            else -> OwnerPayrollStore.importProfile(appContext, report)
                        }
                        if (!ok) {
                            failures.add("No se pudieron aplicar los datos del reporte ${report.number}.")
                            continue
                        }
                        imported.add(report)
                        removeIds.add(id)
                    } catch (e: Exception) {
                        failures.add(e.message ?: "No se pudo leer un reporte.")
                    }
                }
                if (imported.isNotEmpty() && !SeneAdminStore.saveReports(appContext, imported)) {
                    dialogTitle = "NO SE PUDO GUARDAR"
                    dialogMessage = "Los reportes siguen en el buzón para volver a intentar."
                    return@Thread
                }
                if (!ReportInboxStore.removeImported(appContext, removeIds)) {
                    dialogTitle = "IMPORTACIÓN PARCIAL"
                    dialogMessage = "Los reportes se importaron, pero no se pudo actualizar el buzón. No se borraron los datos recibidos."
                    return@Thread
                }
                if (imported.isNotEmpty() || failures.isNotEmpty()) {
                    dialogTitle = "REPORTES PROCESADOS AUTOMÁTICAMENTE"
                    dialogMessage = "Reportes importados: ${imported.size}" +
                        if (failures.isEmpty()) "\nTodos los reportes válidos quedaron registrados."
                        else "\n\nPendientes por revisar:\n" + failures.take(5).joinToString("\n")
                }
            } catch (e: Exception) {
                dialogTitle = "NO SE PUDO PROCESAR"
                dialogMessage = e.message ?: "Los reportes permanecen guardados para volver a intentar."
            } finally {
                runOnUiThread {
                    reportEnvelopeProcessing = false
                    if (!isFinishing && !isDestroyed && dialogTitle != null && dialogMessage != null) {
                        AlertDialog.Builder(this@MainActivity).setTitle(dialogTitle)
                            .setMessage(dialogMessage).setPositiveButton("ACEPTAR", null).show()
                    }
                }
            }
        }.start()
    }

    private fun showPendingReviews() {
        AlertDialog.Builder(this).setTitle("PENDIENTES")
            .setItems(arrayOf("PRODUCTOS SIN STOCK", "PRODUCTOS DAÑADOS", "SOLICITUDES DE TRABAJADORES")) { _, which ->
                when(which) {
                    0 -> showOutOfStockProducts()
                    1 -> showDamagedProducts()
                    2 -> startActivity(Intent(this, PayrollActivity::class.java).putExtra("show_requests", true))
                }
            }.setNegativeButton("CERRAR", null).show()
    }

    private fun showOutOfStockProducts() {
        val local = SharedInventoryManager.localSnapshot(this, "INVENTARIO_SENE")
            ?: SharedInventoryManager.localSnapshot(this, "INVENTARIO_MAESTRO")
        val latest = SeneAdminStore.reports(this)
            .sortedWith(compareByDescending<SeneReport> { it.date }.thenByDescending { it.time }.thenByDescending { it.number })
            .firstNotNullOfOrNull { report ->
                SharedInventoryManager.inventoryFromReport(report)?.let { report to it }
            }
        val snapshot = local ?: latest?.second
        if (snapshot == null) {
            info("PRODUCTOS SIN STOCK", "Todavía no hay un inventario válido recibido.")
            return
        }
        val updatedLabel = local?.updated ?: latest?.first?.let { it.date + " " + it.time }.orEmpty()
        val empty = snapshot.items.filter { it.stock <= 0 }.distinctBy { it.code.ifBlank { it.name } }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        addLabel(content, "Inventario actualizado", updatedLabel, 0xFFDCEBFA.toInt())
        if (empty.isEmpty()) addLabel(content, "ESTADO", "No hay productos agotados en el inventario más reciente.", 0xFFDDF4E5.toInt())
        empty.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { item ->
            addLabel(content, item.name, "Código: " + item.code + " · Existencia: " + item.stock, 0xFFFFE8E5.toInt())
        }
        AlertDialog.Builder(this).setTitle("PRODUCTOS SIN STOCK")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("CERRAR", null).show()
    }

    private fun showDamagedProducts() {
        val reports = SeneAdminStore.reports(this)
            .sortedWith(compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number })
        val records = linkedMapOf<String, List<String>>()
        reports.forEach { report ->
            val lines = report.body.replace("\r", "").lines()
            val marker = lines.indexOfLast { it.trim().equals("PRODUCTOS_DANADOS_SENE_V1", true) }
            if (marker < 0) return@forEach
            for (raw in lines.drop(marker + 1)) {
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("ETIQUETA:", true) || line.startsWith("Guarde este reporte", true)) break
                if (line.startsWith("CODIGO", true) || line.startsWith("CÓDIGO", true)) continue
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 6) continue
                val identity = listOf(parts[0], parts[3], parts[4], parts[5]).joinToString("|")
                records.putIfAbsent(identity, parts)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        if (records.isEmpty()) {
            addLabel(content, "ESTADO", "No hay productos dañados registrados.", 0xFFDDF4E5.toInt())
        } else {
            records.values.forEach { parts ->
                addLabel(content, parts[1], "Código: " + parts[0] + " · Cantidad: " + parts[2] +
                    "\nFecha: " + parts[3] + "\nMotivo: " + parts[4] + "\nTrabajador: " + parts[5], 0xFFFFE8E5.toInt())
            }
        }
        AlertDialog.Builder(this).setTitle("PRODUCTOS DAÑADOS")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("CERRAR", null).show()
    }

    private fun addLabel(parent:LinearLayout,label:String,value:String,accent:Int=0xFFE8F1FB.toInt()) {
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(14,10,14,10)
            background=android.graphics.drawable.GradientDrawable().apply{setColor(accent);cornerRadius=18f} }
        card.addView(TextView(this).apply{text=label.uppercase(Locale.ROOT);textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF54708D.toInt())})
        card.addView(TextView(this).apply{text=value;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF173C65.toInt());setPadding(0,3,0,0)})
        val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=8;parent.addView(card,lp)
    }

    private fun showSeneInventory() {
        // Inventario por trabajador: se construye únicamente con reportes que ya fueron
        // recibidos, descifrados e importados en la caché local de SeneAdmin.
        val candidates = SeneAdminStore.reports(this)
            .sortedWith(compareByDescending<SeneReport> { it.date }.thenByDescending { it.time }.thenByDescending { it.number })
            .mapNotNull { report ->
                SharedInventoryManager.inventoryFromReport(report)?.let { report to it }
            }
        val latestByWorker = candidates.groupBy { it.first.workerId }.mapNotNull { (workerId, items) ->
            val latest = items.firstOrNull() ?: return@mapNotNull null
            val reportName = latest.first.body.replace("\r", "").lineSequence()
                .firstOrNull { it.trim().startsWith("TRABAJADOR:", true) }
                ?.substringAfter(":")?.trim().orEmpty()
            val name = OwnerPayrollStore.profile(this, workerId)?.name
                ?: reportName.ifBlank { workerId }
            Triple(name, latest.second, latest.first.date + " " + latest.first.time)
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        if (latestByWorker.isEmpty()) {
            // Si todavía no llegó un reporte individual, muestra la copia compacta
            // del inventario general recibida del servidor, sin hacer otra consulta.
            showInventory("INVENTARIO_SENE")
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        latestByWorker.forEach { (workerName, snapshot, reportDate) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1)
                    cornerRadius = 18f
                    setStroke(1, 0xFFDCE6F3.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { showWorkerInventory(workerName, snapshot) }
            }
            addLabel(card, "TRABAJADOR", workerName, 0xFFDCEBFA.toInt())
            addLabel(card, "ÚLTIMA ACTUALIZACIÓN", reportDate)
            addLabel(card, "PRODUCTOS", snapshot.items.size.toString(), 0xFFDDF4E5.toInt())
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
        AlertDialog.Builder(this).setTitle("INVENTARIO SENE · POR TRABAJADOR")
            .setMessage("Selecciona un trabajador para consultar su inventario más reciente guardado en este teléfono.")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("CERRAR", null).show()
    }

    private fun showWorkerInventory(workerName: String, snapshot: SharedInventoryManager.Snapshot) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        addLabel(content, "TRABAJADOR", workerName, 0xFFDCEBFA.toInt())
        addLabel(content, "ÚLTIMA ACTUALIZACIÓN", snapshot.updated, 0xFFDCEBFA.toInt())
        addLabel(content, "PRODUCTOS REGISTRADOS", snapshot.items.size.toString(), 0xFFDDF4E5.toInt())
        snapshot.items.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1)
                    cornerRadius = 18f
                }
            }
            addLabel(card, "PRODUCTO", item.name)
            addLabel(card, "CÓDIGO", item.code)
            addLabel(card, "PRECIO", OwnerPayrollStore.money((item.price * 100.0).toLong()))
            addLabel(card, "EXISTENCIA", item.stock.toString(),
                if (item.stock > 0) 0xFFDDF4E5.toInt() else 0xFFFFE8E5.toInt())
            if (item.aisle.isNotBlank()) addLabel(card, "PASILLO", item.aisle)
            if (item.shelf.isNotBlank()) addLabel(card, "ESTANTE", item.shelf)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
        AlertDialog.Builder(this).setTitle("INVENTARIO · " + workerName)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("CERRAR", null).show()
    }

    private fun showSettings() {
        val pin=EditText(this).apply { hint="PIN de acceso"; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val fingerprint=AccessPinStore.fingerprintEnabled(this)
        val builder=AlertDialog.Builder(this).setTitle("ACCESO A AJUSTES").setMessage("Introduce tu PIN para continuar.")
            .setView(pin).setPositiveButton("ENTRAR",null).setNegativeButton("CANCELAR",null)
        if(fingerprint) builder.setNeutralButton("HUELLA",null)
        val d=builder.create()
        d.setOnShowListener {
            d.getButton(-1).setOnClickListener {
                if(!AccessPinStore.matches(this,pin.text.toString())) { pin.error="PIN incorrecto"; return@setOnClickListener }
                d.dismiss(); showSettingsMenu()
            }
            if(fingerprint) d.getButton(-3).setOnClickListener { authenticateFingerprint { d.dismiss(); showSettingsMenu() } }
        }
        d.show()
    }

    private fun showSettingsMenu() {
        val options=arrayOf("Cambiar PIN de acceso","Activar / desactivar huella","Vincular / cambiar carpeta compartida")
        AlertDialog.Builder(this).setTitle("AJUSTES").setItems(options) { _,which ->
            when(which) { 0->askChangePin(); 1->toggleFingerprint(); 2->explainSharedFolder() }
        }.setNegativeButton("CERRAR",null).show()
    }

    private fun explainSharedFolder() {
        AlertDialog.Builder(this)
            .setTitle("VINCULAR / CAMBIAR CARPETA COMPARTIDA")
            .setMessage(
                "Antes de continuar, el dueño debe preparar una carpeta compartida o sincronizada " +
                "a la que tengan acceso los dos teléfonos. Si está en la nube, compártela también " +
                "con la cuenta que se utiliza en el otro teléfono.\n\n" +
                "1. Pulsa CONTINUAR y busca la carpeta compartida en el selector de archivos de Android.\n" +
                "2. Puedes seleccionar la carpeta principal compartida; si ya existe una carpeta llamada INVENTARIO-SENE, también puedes seleccionarla directamente.\n" +
                "3. Pulsa «Usar esta carpeta» y después «Permitir», si Android lo solicita.\n" +
                "4. Repite la vinculación en SENE VENTAS y SENE ADMIN, en sus respectivos teléfonos, seleccionando exactamente la misma carpeta.\n" +
                "5. No necesitas introducir ninguna clave de cifrado: ambas aplicaciones protegen sus datos automáticamente.\n\n" +
                "SENE organizará INVENTARIO-SENE, INVENTARIO-MAESTRO y REPORTES. Si seleccionas directamente INVENTARIO-SENE, la aplicación utilizará esa carpeta como destino y no creará otra carpeta igual dentro. " +
                "No borres ni renombres esas carpetas. Si cambias la carpeta vinculada, los archivos que estén en la carpeta anterior no se trasladan automáticamente."
            )
            .setPositiveButton("CONTINUAR") { _, _ -> SharedInventoryManager.chooseFolder(this) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun askCreatePin(onSaved: () -> Unit = {}) {
        val a=EditText(this).apply { hint="Crea un PIN de 4 a 8 dígitos"; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val b=EditText(this).apply { hint="Confirma tu PIN"; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,4); addView(a); addView(b) }
        val d=AlertDialog.Builder(this).setTitle("CREA TU PIN DE ACCESO").setMessage("Debes establecer este PIN para continuar. Protege la entrada a AJUSTES.")
            .setView(box).setPositiveButton("GUARDAR PIN",null).create()
        d.setCancelable(false)
        d.setOnShowListener { d.getButton(-1).setOnClickListener {
            val x=a.text.toString()
            if(x.length !in 4..8 || x.any { !it.isDigit() }) { a.error="Usa de 4 a 8 números"; return@setOnClickListener }
            if(x!=b.text.toString()) { b.error="Los PIN no coinciden"; return@setOnClickListener }
            if(!AccessPinStore.save(this,x)) { a.error="No se pudo guardar"; return@setOnClickListener }
            d.dismiss()
            onSaved()
        } }
        d.show()
    }

    private fun askChangePin() {
        val a=EditText(this).apply { hint="Nuevo PIN (4 a 8 dígitos)"; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val b=EditText(this).apply { hint="Confirmar PIN"; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,4); addView(a); addView(b) }
        val d=AlertDialog.Builder(this).setTitle("CAMBIAR PIN").setView(box).setPositiveButton("GUARDAR",null).setNegativeButton("CANCELAR",null).create()
        d.setOnShowListener { d.getButton(-1).setOnClickListener {
            val x=a.text.toString()
            if(x.length !in 4..8 || x.any { !it.isDigit() }) { a.error="Usa de 4 a 8 números"; return@setOnClickListener }
            if(x!=b.text.toString()) { b.error="Los PIN no coinciden"; return@setOnClickListener }
            if(!AccessPinStore.save(this,x)) { a.error="No se pudo guardar"; return@setOnClickListener }
            d.dismiss(); Toast.makeText(this,"PIN actualizado",Toast.LENGTH_LONG).show()
        } }
        d.show()
    }

    private fun toggleFingerprint() {
        if(AccessPinStore.fingerprintEnabled(this)) { AccessPinStore.setFingerprintEnabled(this,false); info("HUELLA DESACTIVADA","El PIN seguirá protegiendo Ajustes."); return }
        if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS) {
            info("HUELLA NO DISPONIBLE","Registra una huella en los ajustes de seguridad de Android y vuelve a intentarlo."); return
        }
        authenticateFingerprint { AccessPinStore.setFingerprintEnabled(this,true); info("HUELLA ACTIVADA","Ahora puedes usar tu huella o tu PIN para entrar a Ajustes.") }
    }

    private fun authenticateFingerprint(onSuccess:()->Unit) {
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { super.onAuthenticationSucceeded(result); onSuccess() }
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence) { super.onAuthenticationError(errorCode,errString); Toast.makeText(this@MainActivity,errString,Toast.LENGTH_LONG).show() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("SENE ADMIN").setSubtitle("Confirma tu identidad para entrar a Ajustes").setNegativeButtonText("CANCELAR").build())
    }

    private fun askOwnerKey() {
        info("CLAVE NO NECESARIA", "La vinculación y los datos nuevos se protegen automáticamente. Los reportes históricos cifrados con el formato anterior no se importan desde este flujo.")
    }

    private fun showInventory(category: String) {
        val title = if (category == "INVENTARIO_MAESTRO") "INVENTARIO MAESTRO" else "INVENTARIO SENE"
        // Esta pantalla consulta exclusivamente la caché local que SeneAdmin recibió
        // mediante sincronización. No lee carpetas compartidas ni consulta SERVER-SENE.
        val latest = InventoryLocalStore.snapshot(this, category)
            ?: if (category == "INVENTARIO_SENE") InventoryLocalStore.snapshot(this, "INVENTARIO_MAESTRO") else null
        if (latest == null) {
            info(title, "Todavía no hay una copia local recibida. Cuando llegue la próxima sincronización, el inventario quedará guardado aquí para consultarlo sin conexión.")
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        addLabel(content, "Última actualización", latest.updated, 0xFFDCEBFA.toInt())
        if (latest.category != category) addLabel(content, "Origen de la copia", "Inventario maestro", 0xFFDCEBFA.toInt())
        addLabel(content, "Productos registrados", latest.items.size.toString(), 0xFFDDF4E5.toInt())
        latest.items.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1)
                    cornerRadius = 18f
                }
            }
            card.addView(TextView(this).apply {
                text = item.name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF123B68.toInt())
                setPadding(0, 0, 0, 7)
            })
            addLabel(card, "Código", item.code)
            addLabel(card, "Precio", OwnerPayrollStore.money((item.price * 100.0).toLong()))
            addLabel(card, "Existencia", item.stock.toString(),
                if (item.stock > 0) 0xFFDDF4E5.toInt() else 0xFFFFE8E5.toInt())
            if (item.aisle.isNotBlank()) addLabel(card, "Pasillo", item.aisle)
            if (item.shelf.isNotBlank()) addLabel(card, "Estante", item.shelf)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = 10
            content.addView(card, lp)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("CERRAR", null).show()
    }

    private fun createProcessingDialog(totalSteps: Int): Pair<AlertDialog, (Int, String) -> Unit> {
        val total = totalSteps.coerceAtLeast(1)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 24f
                setStroke(1, 0xFFDCE7F3.toInt())
            }
        }
        panel.addView(TextView(this).apply {
            text = "SENE ADMIN"
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF2374C6.toInt())
        })
        panel.addView(TextView(this).apply {
            text = "Organizando tu información"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF173C65.toInt())
            setPadding(0, 5, 0, 4)
        })
        panel.addView(TextView(this).apply {
            text = "Estamos trabajando. No cierres la aplicación."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, 18)
        })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF2383D1.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6EDF5.toInt())
        }
        panel.addView(bar, LinearLayout.LayoutParams(-1, (10 * resources.displayMetrics.density).toInt()))
        val status = TextView(this).apply {
            text = "Preparando..."
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF31516F.toInt())
            setPadding(0, 14, 0, 3)
        }
        panel.addView(status)
        val counter = TextView(this).apply {
            text = "0 de $total pasos"
            textSize = 12f
            setTextColor(0xFF7A8A9B.toInt())
        }
        panel.addView(counter)
        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), -2)
        val update: (Int, String) -> Unit = { completed, message ->
            val safe = completed.coerceIn(0, total)
            bar.progress = (safe * 1000) / total
            status.text = message
            counter.text = "$safe de $total pasos"
        }
        return dialog to update
    }

    private fun showReports() {
        val reports = SeneAdminStore.reports(this)
        val profiles = (OwnerPayrollStore.profiles(this).map { it.id to it.name } +
            reports.map { it.workerId to (OwnerPayrollStore.profile(this, it.workerId)?.name ?: it.workerId) })
            .distinctBy { it.first }.sortedBy { it.second.lowercase(Locale.ROOT) }
        if (profiles.isEmpty()) { info("REPORTES", "Todavía no hay reportes recibidos."); return }
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12); setBackgroundColor(0xFFF4F7FB.toInt()) }
        profiles.forEach { (workerId,name) ->
            val card = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(16,14,16,14); elevation=2f
                background=android.graphics.drawable.GradientDrawable().apply { setColor(-1); cornerRadius=18f }
            }
            card.addView(TextView(this).apply { text=name; textSize=16f; typeface=Typeface.DEFAULT_BOLD; setTextColor(0xFF173C65.toInt()) })
            card.addView(TextView(this).apply { text="Ventas, caja y actividad importante"; textSize=12f; setTextColor(0xFF64748B.toInt()); setPadding(0,4,0,0) })
            val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=9; list.addView(card,lp)
            card.setOnClickListener { showWorkerReports(workerId,name) }
        }
        AlertDialog.Builder(this).setTitle("REPORTES").setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("CERRAR",null).show()
    }

    private fun showWorkerReports(workerId:String,name:String) {
        val reports=SeneAdminStore.reports(this).filter { it.workerId==workerId }
            .sortedWith(compareBy<SeneReport>{it.date}.thenBy{it.time}.thenBy{it.number}).asReversed()
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12); setBackgroundColor(0xFFF4F7FB.toInt()) }
        val important=setOf("FECHA Y HORA","TRABAJADOR","NÚMERO DEL TRABAJADOR","TIPO","HORA DE ENTRADA","HORA DE SALIDA","HORA PROGRAMADA DE SALIDA","CAJA INICIAL","CAJA ACTUAL","CAJA FINAL","VENTAS","TOTAL DE VENTAS","VENTA TOTAL","DEVOLUCIONES","GASTOS","MOVIMIENTOS","EFECTIVO","TRANSFERENCIAS","TARJETA","TOTAL","TOTAL EN CAJA","CORTE","TIPO DE CIERRE","MOTIVO DE CIERRE","SUELDO SEMANAL","DÍA DE PAGO","DÍAS LABORALES","HORAS DE JORNADA")
        if(reports.isEmpty()) addLabel(content,"REPORTES","Todavía no hay reportes para este trabajador.")
        reports.forEach { report ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12); background=android.graphics.drawable.GradientDrawable().apply { setColor(-1); cornerRadius=18f } }
            addLabel(card,"FECHA",report.date+" "+report.time,0xFFDCEBFA.toInt())
            addLabel(card,"TIPO",if(report.type.equals("EARLY_EXIT",true)) "Cierre anticipado" else report.type,0xFFDDF4E5.toInt())
            report.body.replace("\r","").lines().forEach { raw ->
                val line=raw.trim()
                if(line.startsWith("ETIQUETA:",true)) {
                    val heading=line.substringAfter(":").trim()
                    if(heading.contains("VENTA",true)||heading.contains("CAJA",true)||heading.contains("TURNO",true)||heading.contains("CIERRE",true))
                        card.addView(TextView(this).apply { text=heading; textSize=13f; typeface=Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()); setPadding(2,9,2,5) })
                } else if(line.contains(":")) {
                    val key=line.substringBefore(":").trim()
                    val normalized=key.uppercase(Locale.ROOT)
                    val relevant=important.any { it.equals(key,true) } ||
                        listOf("VENTA","CAJA","DEVOLU","GASTO","EFECTIVO","TRANSFERENCIA","CORTE","HORA DE ENTRADA","HORA DE SALIDA").any { normalized.contains(it) }
                    if(relevant && !normalized.contains("DETALLE") && !normalized.contains("INVENTARIO") && !normalized.contains("ARCHIVO")) {
                        val rawValue=line.substringAfter(":").trim()
                        val displayValue=if(normalized=="TIPO DE CIERRE" && rawValue.equals("EARLY_EXIT",true)) "Cierre anticipado" else if(normalized=="HORA DE SALIDA" && (rawValue.isBlank() || rawValue.equals("Por definir",true)) && (report.type.equals("EARLY_EXIT",true) || report.type.contains("CIERRE",true) || report.type.contains("FIN_TURNO",true) || report.type.contains("CLOSE",true))) report.date+" "+report.time else rawValue
                        val displayKey=when(normalized) {
                            "HORA DE ENTRADA" -> "Hora de entrada"
                            "HORA DE SALIDA" -> "Hora de salida"
                            else -> key
                        }
                        if(normalized!="TIPO DE CIERRE" || !rawValue.equals("EARLY_EXIT",true))
                            addLabel(card,displayKey,displayValue)
                        else addLabel(card,"Tipo de cierre",displayValue,0xFFFFE8D6.toInt())
                    }
                }
            }
            if(card.childCount>2) { val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=10; content.addView(card,lp) }
        }
        AlertDialog.Builder(this).setTitle("REPORTES · "+name)
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("CERRAR",null).show()
    }

    private fun showWorkerAdministration() {
        val profiles=OwnerPayrollStore.profiles(this).sortedBy{it.name.lowercase(Locale.ROOT)}
        if(profiles.isEmpty()){
            info("ADMINISTRAR TRABAJADORES","No hay perfiles de trabajadores registrados.")
            return
        }
        AlertDialog.Builder(this).setTitle("ADMINISTRAR TRABAJADORES")
            .setItems(profiles.map{it.name}.toTypedArray()){_,which->
                val selected=profiles[which]
                AlertDialog.Builder(this).setTitle("ELIMINAR PERFIL")
                    .setMessage("¿Deseas eliminar el perfil de "+selected.name+"? También se eliminarán sus datos de nómina, movimientos y asistencia guardados en SENE ADMIN. Los reportes importados se conservarán.")
                    .setPositiveButton("ELIMINAR"){_,_->
                        if(OwnerPayrollStore.deleteProfile(this,selected.id)){
                            Toast.makeText(this,"Perfil eliminado",Toast.LENGTH_LONG).show()
                        }else{
                            info("NO SE PUDO ELIMINAR","No se pudo completar la eliminación del perfil.")
                        }
                    }
                    .setNegativeButton("CANCELAR",null).show()
            }.setNegativeButton("CERRAR",null).show()
    }

    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ACEPTAR", null).show()
    }
}
