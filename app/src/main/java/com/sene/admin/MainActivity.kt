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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
        if (!OwnerKeyStore.hasKey(this)) askOwnerKey() else if (!AccessPinStore.hasPin(this)) askCreatePin() else handleIncoming(intent)
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
        fun mainOption(label: String, subtitle: String, color: Int = 0xFF1769AA.toInt(), action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(18, 26, 18, 26)
                setBackgroundColor(color)
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
        mainOption("ADMINISTRAR TRABAJADORES", "Seleccionar y eliminar un perfil") {
            showWorkerAdministration()
        }
        mainOption("REPORTES", "Ventas, caja y actividad importante") {
            showReports()
        }
        mainOption("PEGAR REPORTES DE WHATSAPP", "Importar reportes enviados por tus trabajadores", 0xFF218A57.toInt()) {
            showPasteDialog()
        }
        mainOption("AJUSTES", "Configuración protegida", 0xFFE7A0A0.toInt()) {
            showSettings()
        }
        setContentView(ScrollView(this).apply { addView(root) })
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
            when(which) { 0->askChangePin(); 1->toggleFingerprint(); 2->SharedInventoryManager.chooseFolder(this); 3->showInventory("INVENTARIO_MAESTRO"); 4->showReports() }
        }.setNegativeButton("CERRAR",null).show()
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
        if (SharedInventoryManager.savedTree(this) != null) {
            try { SharedInventoryManager.saveReport(this, report, message, key) } catch (_: Exception) { }
            try { SharedInventoryManager.updateMasterFromReport(this, key, report.body, report.date + " " + report.time) }
            catch (_: Exception) { }
        }
        check(SeneAdminStore.saveReport(this, report)) { "No se pudo registrar el reporte importado." }
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
            addLabel(card,"TIPO",report.type,0xFFDDF4E5.toInt())
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
                    if(relevant && !normalized.contains("DETALLE") && !normalized.contains("INVENTARIO") && !normalized.contains("ARCHIVO"))
                        addLabel(card,key,line.substringAfter(":").trim())
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
