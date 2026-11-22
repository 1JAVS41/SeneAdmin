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
    private lateinit var root: LinearLayout
    @Volatile private var reportImportRunning = false
    private var pendingImportRaw: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportRaw = getSharedPreferences("sene_admin_pending_import", MODE_PRIVATE).getString("raw", null)
        showHome()
        OwnerKeyStore.read(this)?.let { SharedInventoryManager.cleanupAllIfDue(this, it) }
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey() else if (!AccessPinStore.hasPin(this)) askCreatePin() else handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey()
        else if (!AccessPinStore.hasPin(this)) askCreatePin()
        else handleIncoming(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SharedInventoryManager.REQUEST_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                SharedInventoryManager.acceptFolder(this, uri)
                Toast.makeText(this, "Carpeta INVENTARIO SENE vinculada", Toast.LENGTH_LONG).show()
                showHome()
                checkForWorkerRecovery {
                    val pending = pendingImportRaw ?: getSharedPreferences("sene_admin_pending_import", MODE_PRIVATE).getString("raw", null)
                    if (!pending.isNullOrBlank()) importReports(pending)
                }
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
        if(ServerPairing.isLinked(this)) {
            AlertDialog.Builder(this).setTitle("NEGOCIO VINCULADO")
                .setMessage("SeneAdmin está conectado con: "+ServerPairing.businessName(this)+
                    "\\n\\nLa vinculación se conserva en este dispositivo y permite sincronizar solicitudes y autorizaciones.")
                .setPositiveButton("ACEPTAR",null).setNeutralButton("ACTUALIZAR"){_,_->AdminPayrollRequestSync.sync(this,true)}.show()
            return
        }
        val code=EditText(this).apply {
            hint="Código temporal de SENE VENTAS"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("VINCULAR SENE ADMIN")
            .setMessage("En SENE VENTAS entra a SOY PATRÓN → VINCULAR SENE ADMIN y genera un código temporal. Escríbelo aquí. El código es de un solo uso y caduca.")
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
                                    AdminPayrollRequestSync.sync(this,true)
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

    private fun handleIncoming(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            incoming.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { importReports(it) }
        }
    }

    private fun showHome() {
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        val navy = 0xFF145A43.toInt()
        val blue = 0xFFC83D3A.toInt()
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
                intArrayOf(navy, blue, 0xFF27875B.toInt())
            ).apply { cornerRadius = dp(25).toFloat() }
            elevation = dp(5).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = "SENE / ADMIN"; textSize = 13f; letterSpacing = .16f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFE5E2.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "Tu negocio,\nbajo control."; textSize = 29f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(-1); setPadding(0, dp(8), 0, dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "Administración, trabajadores e inventario en un solo lugar."
            textSize = 13f; setTextColor(0xFFFFEDEC.toInt())
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "ACCESOS PRINCIPALES"; textSize = 12f; letterSpacing = .12f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF66766E.toInt())
            setPadding(dp(3), 0, 0, dp(10))
        })

        fun mainOption(icon: String, label: String, subtitle: String, green: Boolean = false, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(15), dp(16), dp(15))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (green) 0xFF168A4A.toInt() else -1)
                    cornerRadius = dp(19).toFloat()
                    setStroke(dp(1), if (green) 0xFF168A4A.toInt() else 0xFFE4EAE5.toInt())
                }
                elevation = dp(2).toFloat()
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            val symbol = TextView(this).apply {
                text = icon; textSize = 23f; gravity = android.view.Gravity.CENTER
                setTextColor(if (green) -1 else navy)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (green) 0xFF0F713B.toInt() else 0xFFFCECEB.toInt())
                    cornerRadius = dp(15).toFloat()
                }
            }
            card.addView(symbol, LinearLayout.LayoutParams(dp(52), dp(52)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, dp(6), 0)
            }
            labels.addView(TextView(this).apply {
                text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (green) -1 else 0xFF194A39.toInt())
            })
            labels.addView(TextView(this).apply {
                text = subtitle; textSize = 12f; setTextColor(if (green) 0xFFE2F5E9.toInt() else 0xFF69796F.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = "›"; textSize = 28f; setTextColor(if (green) -1 else 0xFFC64A43.toInt())
            })
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        mainOption("↔", if(ServerPairing.isLinked(this)) "NEGOCIO VINCULADO" else "VINCULAR NEGOCIO",
            if(ServerPairing.isLinked(this)) "Conectado a "+ServerPairing.businessName(this) else "Conecta SeneAdmin con SENE VENTAS", green = !ServerPairing.isLinked(this)) {
            showPairingDialog()
        }
        mainOption("N", "NÓMINA", "Consultar pagos y revisar asistencia") {
            startActivity(Intent(this, PayrollActivity::class.java))
        }
        mainOption("♙", "ADMINISTRAR TRABAJADORES", "Crear, editar y consultar perfiles") {
            showWorkerAdministration()
        }
        mainOption("▤", "OTROS REPORTES", "Reportes de trabajadores, ventas y caja") {
            showReports()
        }
        mainOption("▦", "INVENTARIO SENE", "Consultar inventario recibido y maestro") {
            AlertDialog.Builder(this).setTitle("INVENTARIOS")
                .setItems(arrayOf("INVENTARIO SENE", "INVENTARIO MAESTRO")) { _, which ->
                    showInventory(if (which == 0) "INVENTARIO_SENE" else "INVENTARIO_MAESTRO")
                }.setNegativeButton("CERRAR", null).show()
        }
        mainOption("⌁", "PEGAR REPORTES DE WHATSAPP", "Importar asistencia y movimientos", green = true) {
            showPasteDialog()
        }
        mainOption("!", "REVISAR PENDIENTES", "Productos sin stock y productos dañados") {
            showPendingReviews()
        }
        mainOption("⚙", "AJUSTES", "Clave, huella y carpeta compartida") {
            showSettings()
        }

        root.addView(TextView(this).apply {
            text = "RECUERDA PEGAR LOS REPORTES DE TUS TRABAJADORES"
            textSize = 11f; letterSpacing = .04f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF9B3B37.toInt())
            setPadding(dp(5), dp(12), dp(5), dp(4))
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root)
        })
    }

    private fun showPendingReviews() {
        AlertDialog.Builder(this).setTitle("REVISAR PENDIENTES")
            .setItems(arrayOf("PRODUCTOS SIN STOCK", "PRODUCTOS DAÑADOS")) { _, which ->
                if (which == 0) showOutOfStockProducts() else showDamagedProducts()
            }.setNegativeButton("CERRAR", null).show()
    }

    private fun showOutOfStockProducts() {
        val latest = SeneAdminStore.reports(this)
            .sortedWith(compareByDescending<SeneReport> { it.date }.thenByDescending { it.time }.thenByDescending { it.number })
            .firstNotNullOfOrNull { report ->
                SharedInventoryManager.inventoryFromReport(report)?.let { report to it }
            }
        if (latest == null) {
            info("PRODUCTOS SIN STOCK", "Todavía no hay un inventario válido recibido.")
            return
        }
        val (report, snapshot) = latest
        val empty = snapshot.items.filter { it.stock <= 0 }.distinctBy { it.code.ifBlank { it.name } }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        addLabel(content, "Inventario actualizado", report.date + " " + report.time, 0xFFDCEBFA.toInt())
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
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12);setBackgroundColor(0xFFF4F7FB.toInt())}
        addLabel(content,"Actualizado",report.date+" "+report.time,0xFFDCEBFA.toInt())
        addLabel(content,"Productos",inventory.items.size.toString(),0xFFDDF4E5.toInt())
        inventory.items.sortedBy{it.name.lowercase(Locale.ROOT)}.forEach { item ->
            val group=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12)
                background=android.graphics.drawable.GradientDrawable().apply{setColor(-1);cornerRadius=18f}}
            group.addView(TextView(this).apply{text=item.name;textSize=16f;typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF123B68.toInt());setPadding(0,0,0,7)})
            addLabel(group,"Código",item.code)
            addLabel(group,"Precio",OwnerPayrollStore.money((item.price*100.0).toLong()))
            addLabel(group,"Existencia",item.stock.toString(),0xFFDDF4E5.toInt())
            if(item.aisle.isNotBlank())addLabel(group,"Pasillo",item.aisle)
            if(item.shelf.isNotBlank())addLabel(group,"Estante",item.shelf)
            val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=10;content.addView(group,lp)
        }
        AlertDialog.Builder(this).setTitle("INVENTARIO SENE")
            .setView(ScrollView(this).apply{addView(content)}).setPositiveButton("CERRAR",null).show()
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
        val options=arrayOf("Cambiar PIN de acceso","Activar / desactivar huella","Vincular / cambiar carpeta compartida","Ver inventario maestro","Ver todos los reportes")
        AlertDialog.Builder(this).setTitle("AJUSTES").setItems(options) { _,which ->
            when(which) { 0->askChangePin(); 1->toggleFingerprint(); 2->explainSharedFolder(); 3->showInventory("INVENTARIO_MAESTRO"); 4->showReports() }
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
                "5. Introduce en ambas aplicaciones la misma clave del propietario.\n\n" +
                "SENE organizará INVENTARIO-SENE, INVENTARIO-MAESTRO y REPORTES. Si seleccionas directamente INVENTARIO-SENE, la aplicación utilizará esa carpeta como destino y no creará otra carpeta igual dentro. " +
                "No borres ni renombres esas carpetas. Si cambias la carpeta vinculada, los archivos que estén en la carpeta anterior no se trasladan automáticamente."
            )
            .setPositiveButton("CONTINUAR") { _, _ -> SharedInventoryManager.chooseFolder(this) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun askCreatePin() {
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
            d.dismiss(); handleIncoming(intent)
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
        val input = EditText(this).apply {
            hint = "Clave del propietario (mínimo 8 caracteres)"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dlg = AlertDialog.Builder(this).setTitle("CLAVE DEL PROPIETARIO")
            .setMessage("Introduce obligatoriamente la MISMA CLAVE DE CIFRADO que ya está configurada en SENE VENTAS. SENE ADMIN no genera ni crea una clave nueva. No podrás continuar sin introducirla.")
            .setView(input).setPositiveButton("CONTINUAR", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.length < 8) { input.error = "Mínimo 8 caracteres"; return@setOnClickListener }
                if (!OwnerKeyStore.save(this, value)) {
                    input.error = if (OwnerKeyStore.hasKey(this)) "La clave no coincide con la ya registrada" else "No se pudo guardar"
                    return@setOnClickListener
                }
                dlg.dismiss()
                Toast.makeText(this, "Clave de SENE VENTAS guardada", Toast.LENGTH_LONG).show()
                askCreatePin()
            }
        }
        dlg.setCancelable(false)
        dlg.show()
    }

    private fun showInventory(category: String) {
        val key = OwnerKeyStore.read(this)
        if (key == null) { askOwnerKey(); return }
        if (SharedInventoryManager.savedTree(this) == null) { explainSharedFolder(); return }

        val title = if (category == "INVENTARIO_MAESTRO") "INVENTARIO MAESTRO" else "INVENTARIO SENE"
        val (progress, updateProgress) = createProcessingDialog(3)
        updateProgress(0, "Preparando la revisión del inventario...")

        // Dar oportunidad a Android de dibujar el diálogo antes de iniciar la lectura.
        progress.window?.decorView?.post {
            Thread {
                var snapshots: List<SharedInventoryManager.Snapshot>? = null
                var failure: String? = null
                try {
                    runOnUiThread { if (!isFinishing && !isDestroyed) updateProgress(1, "Buscando los respaldos disponibles...") }
                    snapshots = SharedInventoryManager.list(this@MainActivity, key, category)
                    runOnUiThread { if (!isFinishing && !isDestroyed) updateProgress(2, "Leyendo y preparando los productos...") }
                } catch (e: Exception) {
                    failure = e.message ?: "Verifica la clave y los permisos. El archivo original permanece intacto."
                }
                runOnUiThread {
                    progress.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failure != null) {
                        info("NO SE PUDO ABRIR EL INVENTARIO", failure!!)
                        return@runOnUiThread
                    }
                    val available = snapshots.orEmpty()
                    val latest = available.firstOrNull()
                    if (latest == null) {
                        info(title, "Todavía no hay respaldos disponibles de este inventario. Se actualizará al incorporar reportes válidos de SENE VENTAS.")
                        return@runOnUiThread
                    }
                    val content = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 12, 14, 12)
                        setBackgroundColor(0xFFF4F7FB.toInt())
                    }
                    addLabel(content, "Última actualización", latest.updated, 0xFFDCEBFA.toInt())
                    addLabel(content, "Productos registrados", latest.items.size.toString(), 0xFFDDF4E5.toInt())
                    addLabel(content, "Respaldos disponibles", available.size.toString(), 0xFFE8E5FA.toInt())
                    if (latest.legacy) addLabel(content, "Formato anterior", "Inventario TXT heredado. Se conserva el archivo original.", 0xFFFFF0D9.toInt())
                    latest.items.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { item ->
                        val card = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(14, 12, 14, 12)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(-1)
                                cornerRadius = 18f
                            }
                            elevation = 1f
                        }
                        card.addView(TextView(this@MainActivity).apply {
                            text = item.name
                            textSize = 16f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(0xFF123B68.toInt())
                            setPadding(0, 0, 0, 7)
                        })
                        addLabel(card, "Código", item.code, 0xFFE8F1FB.toInt())
                        addLabel(card, "Precio", OwnerPayrollStore.money((item.price * 100.0).toLong()), 0xFFE8F1FB.toInt())
                        addLabel(card, "Existencia", item.stock.toString(), if (item.stock > 0) 0xFFDDF4E5.toInt() else 0xFFFFE8E5.toInt())
                        if (item.aisle.isNotBlank()) addLabel(card, "Pasillo", item.aisle)
                        if (item.shelf.isNotBlank()) addLabel(card, "Estante", item.shelf)
                        val lp = LinearLayout.LayoutParams(-1, -2)
                        lp.bottomMargin = 10
                        content.addView(card, lp)
                    }
                    updateProgress(3, "Inventario revisado")
                    AlertDialog.Builder(this@MainActivity).setTitle(title)
                        .setView(ScrollView(this@MainActivity).apply { addView(content) })
                        .setPositiveButton("CERRAR", null).show()
                }
            }.start()
        }
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

    private fun importReports(raw: String) {
        if (reportImportRunning) {
            Toast.makeText(this, "Ya se está procesando un reporte. Espera a que termine.", Toast.LENGTH_LONG).show()
            return
        }
        val key = OwnerKeyStore.read(this)
        if (key == null) {
            info("CONFIGURA TU CLAVE", "Primero introduce la clave del propietario para leer estos reportes.")
            askOwnerKey()
            return
        }
        if (SharedInventoryManager.savedTree(this) == null) {
            pendingImportRaw = raw
            val saved = getSharedPreferences("sene_admin_pending_import", MODE_PRIVATE).edit().putString("raw", raw).commit()
            if (!saved) {
                info("NO SE PUDO CONSERVAR EL REPORTE", "No se pudo guardar temporalmente el texto. Vuelve a pegar el reporte antes de vincular la carpeta.")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("VINCULA LA CARPETA COMPARTIDA")
                .setMessage("Para organizar automáticamente el reporte, guardar su respaldo cifrado y actualizar los inventarios, primero selecciona la carpeta compartida que utilizan SENE VENTAS y SENE ADMIN. El reporte quedará pendiente y se procesará al terminar.")
                .setPositiveButton("VINCULAR CARPETA") { _, _ -> explainSharedFolder() }
                .setNegativeButton("CANCELAR", null)
                .show()
            return
        }
        reportImportRunning = true
        val expectedReports = Regex("SENE-ADMIN-REPORT\\|1").findAll(raw).count()
        val progressTotal = (expectedReports + 1).coerceAtLeast(1)
        val (progress, updateProgress) = createProcessingDialog(progressTotal)
        updateProgress(0, "Preparando los reportes...")

        // Esperar a que el diálogo se dibuje; así permanece visible durante el trabajo.
        progress.window?.decorView?.post {
        Thread {
            fun publishProgress(completed: Int, message: String) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) updateProgress(completed, message)
                }
            }
            var imported = 0
            var duplicates = 0
            val errors = mutableListOf<String>()
            val reportBatch = mutableListOf<SeneReport>()
            var fatalError: String? = null
            try {
                // WhatsApp puede anteponer fecha, remitente o espacios al copiar el mensaje.
                // Se localiza cada cabecera y se procesa el lote fuera del hilo de pantalla.
                val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
                // No descartar el primer reporte cuando WhatsApp antepone fecha o remitente.
                // Extraer cada mensaje desde su cabecera real hasta la siguiente cabecera.
                val headers = Regex("SENE-ADMIN-REPORT\\|1").findAll(normalized).toList()
                val chunks = headers.mapIndexed { index, match ->
                    val end = headers.getOrNull(index + 1)?.range?.first ?: normalized.length
                    normalized.substring(match.range.first, end).trim()
                }
                if (chunks.isEmpty()) errors.add("No se encontraron reportes SENE válidos. Pega el mensaje completo de WhatsApp.")
                val knownIds = SeneAdminStore.reports(this@MainActivity).map { it.id }.toMutableSet()
                chunks.forEachIndexed { index, message ->
                    publishProgress(index, "Leyendo y organizando reporte ${index + 1} de ${chunks.size}...")
                    try {
                        val report = SeneReportReader.read(message, key)
                        if (!knownIds.add(report.id)) duplicates++
                        else {
                            processReport(report, message, key)
                            reportBatch.add(report)
                            imported++
                            if(reportBatch.size>=10) {
                                if(!SeneAdminStore.saveReports(this@MainActivity,reportBatch))
                                    throw IllegalStateException("No se pudo guardar un bloque del historial local.")
                                reportBatch.clear()
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Reporte " + (index + 1) + ": " + (e.message ?: "formato no válido"))
                    }
                    publishProgress(index + 1, "Reporte ${index + 1} de ${chunks.size} revisado")
                }
                // Guardar el historial local en un solo lote evita reescribir todo el JSON
                // una vez por reporte cuando se pegan semanas o meses de WhatsApp.
                if(!SeneAdminStore.saveReports(this@MainActivity,reportBatch))
                    throw IllegalStateException("No se pudo guardar el historial local de reportes.")
                // Crear o actualizar la nómina solo después de procesar el lote completo.
                publishProgress(expectedReports, "Actualizando la información de nómina...")
                OwnerPayrollStore.profiles(this@MainActivity).filter { it.weeklySalaryCents > 0L }.forEach { profile ->
                    try { OwnerPayrollStore.createOrGetPayroll(this@MainActivity, profile.id) } catch (_: Exception) { }
                }
                publishProgress(progressTotal, "Preparando el resumen...")
            } catch (e: Exception) {
                fatalError = e.message ?: "No se pudo completar la importación."
            }

            runOnUiThread {
                reportImportRunning = false
                if (isFinishing || isDestroyed) {
                    progress.dismiss()
                    return@runOnUiThread
                }
                progress.dismiss()
                if (fatalError != null) {
                    info("NO SE COMPLETÓ LA IMPORTACIÓN", fatalError!!)
                    return@runOnUiThread
                }
                showHome()
                if (errors.isEmpty() && (imported > 0 || duplicates > 0)) {
                    getSharedPreferences("sene_admin_pending_import", MODE_PRIVATE).edit().remove("raw").commit()
                    pendingImportRaw = null
                }
                val summary = buildString {
                    append("Nuevos reportes: ").append(imported)
                    append("\nDuplicados omitidos: ").append(duplicates)
                    if (errors.isNotEmpty()) {
                        append("\n\nNo se pudieron leer:")
                        errors.take(8).forEach { append("\n• ").append(it) }
                        if (errors.size > 8) append("\nY ").append(errors.size - 8).append(" más.")
                    }
                }
                AlertDialog.Builder(this@MainActivity).setTitle("IMPORTACIÓN TERMINADA").setMessage(summary)
                    .setPositiveButton("ACEPTAR", null).show()
            }
        }.start()
        }
    }

    private fun processReport(report: SeneReport, message: String, key: String) {
        // Primero clasifica y aplica los datos. Solo después registra el ID como importado:
        // si falla el procesamiento, el dueño puede pegar el mismo mensaje otra vez.
        if (report.body.startsWith("SENE-ORDER|1")) {
            check(OwnerPayrollStore.importOrder(this, report)) { "No se pudo organizar la solicitud del trabajador." }
        } else {
            check(OwnerPayrollStore.importProfile(this, report)) {
                "No se pudieron guardar los datos del trabajador, la asistencia o sus movimientos de nómina."
            }
        }

        // El inventario del reporte se consulta desde el historial local y se muestra
        // automáticamente en INVENTARIO SENE; no requiere que el dueño lo separe.
        // La importación solo se confirma cuando el reporte cifrado quedó respaldado
        // en la carpeta compartida y el inventario maestro pudo actualizarse si corresponde.
        check(SharedInventoryManager.savedTree(this) != null) {
            "Vincula la carpeta compartida antes de importar reportes."
        }
        SharedInventoryManager.saveReport(this, report, message, key)
        SharedInventoryManager.updateMasterFromReport(this, key, report.body, report.date + " " + report.time)
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
