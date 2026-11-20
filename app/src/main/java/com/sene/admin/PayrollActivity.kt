package com.sene.admin

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class PayrollActivity : Activity() {
    private lateinit var root: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra("profile_id")
        if (profileId.isNullOrBlank()) render() else if (OwnerPayrollStore.profile(this, profileId) != null) {
            setContentView(TextView(this).apply { text = "Perfil del trabajador"; setPadding(24, 24, 24, 24) })
            showProfile(profileId)
        } else render()
    }
    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 24, 22, 24); setBackgroundColor(0xFFF4F7FB.toInt()) }
        root.addView(TextView(this).apply { text = "PERSONAL Y NÓMINA"; textSize = 23f; setTextColor(0xFF123B68.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = "Los datos de sueldo y jornada se pueden completar automáticamente al recibir el reporte de inicio de turno."; textSize = 13f; setTextColor(0xFF64748B.toInt()); setPadding(0, 6, 0, 16) })
        val profiles = OwnerPayrollStore.profiles(this).sortedBy { it.name.lowercase(Locale.ROOT) }
        if (profiles.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Los perfiles aparecerán automáticamente cuando SENE ADMIN reciba los reportes de inicio de turno."
                textSize = 14f; setTextColor(0xFF64748B.toInt()); setPadding(4, 16, 4, 16)
            })
        }
        profiles.forEach { p ->
            addCard(p.name, "Sueldo semanal: ${OwnerPayrollStore.money(p.weeklySalaryCents)} · Pago: ${p.payDay.ifBlank { "Sin configurar" }}" + if (OwnerPayrollStore.isPayDay(p)) " · HOY: Revisa SENE VENTAS antes de pagar" else "") { showProfile(p.id) }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun addCard(title: String, subtitle: String, action: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE8F1FB.toInt()); cornerRadius=20f }; elevation = 2f }
        box.addView(TextView(this).apply { text = title; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF173C7B.toInt()) })
        box.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFF64748B.toInt()); setPadding(0, 4, 0, 0) })
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 10; root.addView(box, lp); box.setOnClickListener { action() }
    }
    private fun field(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setSingleLine(true); textSize = 15f
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun addProfile() {
        val name=field("Nombre del trabajador"); val id=field("ID único del trabajador"); val phone=field("Teléfono")
        val salary=field("Sueldo semanal",true); val day=field("Día de pago (ej. Viernes)"); val days=field("Días laborales")
        val box=LinearLayout(this).apply { orientation=1;setPadding(20,8,20,4);listOf(name,id,phone,salary,day,days).forEach{addView(it)} }
        AlertDialog.Builder(this).setTitle("NUEVO PERFIL").setMessage("Usa un ID estable para relacionar reportes y nóminas.")
            .setView(ScrollView(this).apply{addView(box)}).setPositiveButton("GUARDAR",null).setNegativeButton("CANCELAR",null).create().also{d->
                d.setOnShowListener{d.getButton(-1).setOnClickListener{
                    val workerId=id.text.toString().trim();val workerName=name.text.toString().trim()
                    val amount=moneyToCents(salary.text.toString())
                    if(workerId.isBlank()||workerName.isBlank()||amount==null){id.error="ID requerido";if(workerName.isBlank())name.error="Nombre requerido";if(amount==null)salary.error="Sueldo inválido";return@setOnClickListener}
                    val ok=OwnerPayrollStore.upsertProfile(this,OwnerPayrollStore.Profile(workerId,workerName,phone.text.toString().trim(),amount,day.text.toString().trim(),days.text.toString().trim(),8))
                    if(!ok){Toast.makeText(this,"No se pudo guardar el perfil",Toast.LENGTH_LONG).show();return@setOnClickListener}
                    d.dismiss();render()
                }}
                d.show()
            }
    }
    private fun showProfile(id: String) {
        val p = OwnerPayrollStore.profile(this, id) ?: return
        val pay = try { OwnerPayrollStore.createOrGetPayroll(this, id) } catch (_: Exception) { null }
        val entries = OwnerPayrollStore.entries(this, id)
        val allPayrolls = OwnerPayrollStore.payrolls(this, id).sortedByDescending { it.createdAt }
        val box = LinearLayout(this).apply { orientation = 1; setPadding(16, 12, 16, 12); setBackgroundColor(0xFFF4F7FB.toInt()) }
        fun tag(label:String,value:String,accent:Int=0xFFE8F1FB.toInt()) {
            val chip=LinearLayout(this).apply { orientation=1; setPadding(14,10,14,10); background=android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius=18f } }
            chip.addView(TextView(this).apply { text=label.uppercase(Locale.ROOT); textSize=10f; setTextColor(0xFF54708D.toInt()); typeface=android.graphics.Typeface.DEFAULT_BOLD })
            chip.addView(TextView(this).apply { text=value; textSize=15f; setTextColor(0xFF173C65.toInt()); typeface=android.graphics.Typeface.DEFAULT_BOLD; setPadding(0,3,0,0) })
            val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=8; box.addView(chip,lp)
        }
        tag("TRABAJADOR",p.name,0xFFDCEBFA.toInt())
        if(p.createdAt.isNotBlank())tag("FECHA Y HORA DE CREACIÓN DEL PERFIL",p.createdAt)
        if(p.firstShiftAt.isNotBlank())tag("FECHA Y HORA DEL PRIMER INICIO DE TURNO",p.firstShiftAt)
        tag("TELÉFONO",p.phone.ifBlank{"No indicado"})
        tag("DÍA DE PAGO",p.payDay.ifBlank{"Sin configurar"})
        tag("SUELDO SEMANAL",OwnerPayrollStore.money(p.weeklySalaryCents))
        tag("DÍAS LABORALES",p.workDays.ifBlank{"Sin configurar"})
        tag("HORAS DE JORNADA",p.hours.toString()+" horas")
        OwnerPayrollStore.attendanceSummary(this,id)?.let { summary ->
            tag("ÚLTIMO PERÍODO",summary.period)
            tag("DÍAS TRABAJADOS / FALTAS",summary.workedDays.toString()+" trabajados · "+summary.absences+" faltas")
            tag("TURNO",summary.started.ifBlank{"—"}+" · Finalizó: "+summary.closed.ifBlank{"Por definir"})
            if(summary.closingType.isNotBlank())tag("TIPO DE CIERRE",if(summary.closingType=="EARLY_EXIT")"CIERRE DE TURNO ANTICIPADO" else summary.closingType)
            if(summary.closingReason.isNotBlank())tag("MOTIVO",summary.closingReason)
        }
        if(pay!=null) {
            tag("SUELDO BASE",OwnerPayrollStore.money(pay.grossCents))
            tag("DESCUENTO POR FALTAS",OwnerPayrollStore.money(pay.absenceCents))
            tag("DESCUENTO POR SALIDAS ANTICIPADAS",OwnerPayrollStore.money(pay.earlyExitCents))
            tag("PRÉSTAMOS",OwnerPayrollStore.money(pay.loanCents))
            tag("CONSUMOS",OwnerPayrollStore.money(pay.consumptionCents))
            tag("TOTAL NETO",OwnerPayrollStore.money(pay.netCents),0xFFDDF4E5.toInt())
            tag("ESTADO DE NÓMINA",pay.status,if(pay.status=="PAGADA")0xFFDDF4E5.toInt() else 0xFFFFF1D6.toInt())
        } else tag("NÓMINA","Falta configurar el sueldo semanal")
        val attendanceTitle=TextView(this).apply { text="ASISTENCIA DE LA SEMANA"; textSize=14f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()); setPadding(2,12,2,8) }
        box.addView(attendanceTitle)
        OwnerPayrollStore.weekAttendance(this,p).forEach { day ->
            val symbol=when(day.status){"WORKED"->"🟢";"EARLY_EXIT"->"🟢";"ADMIN_CLOSED"->"🔵";"NOT_WORKED"->"❌️";"WORKING"->"🔁";"NO_RECORD"->"—";else->"—"}
            val bg=when(day.status){"WORKED"->0xFFDDF4E5.toInt();"EARLY_EXIT"->0xFFFFE8D6.toInt();"ADMIN_CLOSED"->0xFFDCEBFA.toInt();"NOT_WORKED"->0xFFFFE4E6.toInt();"WORKING"->0xFFFFF1D6.toInt();else->0xFFE8EDF3.toInt()}
            val statusText=when(day.status) {
                "NO_RECORD" -> "Aún no tenía registro"
                "EARLY_EXIT" -> "Cierre anticipado"
                "WORKING" -> "En curso"
                "WORKED" -> "Jornada completada"
                "ADMIN_CLOSED" -> "Cierre administrativo"
                "NOT_WORKED" -> "Sin asistencia"
                else -> ""
            }
            val times=listOf(day.started.takeIf{it.isNotBlank()}?.let{"Entrada: $it"},day.closed.takeIf{it.isNotBlank()}?.let{"Salida: $it"}).filterNotNull().joinToString(" · ")
            val detail=listOf(statusText,times).filter{it.isNotBlank()}.joinToString(" · ")
            val chip=TextView(this).apply { text=day.day+" - ["+symbol+"]"+if(detail.isBlank()) "" else " · "+detail; textSize=13f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF26364F.toInt()); setPadding(14,9,14,9); background=android.graphics.drawable.GradientDrawable().apply{setColor(bg);cornerRadius=14f} }
            val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=5;box.addView(chip,lp)
        }
        val movementTitle=TextView(this).apply { text="MOVIMIENTOS REGISTRADOS"; textSize=14f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()); setPadding(2,14,2,8) }
        box.addView(movementTitle)
        if(entries.isEmpty())tag("MOVIMIENTOS","Todavía no hay movimientos.")
        entries.sortedByDescending { it.date }.forEach { tag(it.type+" · "+it.date,OwnerPayrollStore.money(it.amountCents)+" · "+it.description) }
        val historyTitle=TextView(this).apply { text="HISTORIAL DE NÓMINA"; textSize=14f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF123B68.toInt()); setPadding(2,14,2,8) }
        box.addView(historyTitle)
        if(allPayrolls.isEmpty())tag("HISTORIAL","Todavía no hay nóminas.")
        allPayrolls.forEach { tag(it.period+" · "+it.status, "Neto "+OwnerPayrollStore.money(it.netCents)) }
        lateinit var dialog: AlertDialog
        fun option(label: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        option("CONSULTAR / PAGAR NÓMINA") { payroll(p) }
        dialog = AlertDialog.Builder(this).setTitle("PAGAR A · " + p.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CERRAR", null).create()
        dialog.show()
    }

    private fun showReportsForWorker(id: String) {
        val lines = SeneAdminStore.reports(this).filter { it.workerId == id }
            .sortedWith(compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number })
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12);setBackgroundColor(0xFFF4F7FB.toInt())}
        if(lines.isEmpty()) {
            val empty=TextView(this).apply{text="Todavía no hay reportes recibidos para este trabajador.";textSize=14f;setTextColor(0xFF526579.toInt());setPadding(8,16,8,16)}
            content.addView(empty)
        }
        lines.asReversed().forEach { report ->
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12)
                background=android.graphics.drawable.GradientDrawable().apply{setColor(-1);cornerRadius=18f}}
            fun label(name:String,value:String) {
                val chip=LinearLayout(this).apply{orientation=1;setPadding(12,9,12,9)
                    background=android.graphics.drawable.GradientDrawable().apply{setColor(0xFFE8F1FB.toInt());cornerRadius=14f}}
                chip.addView(TextView(this).apply{text=name.uppercase(Locale.ROOT);textSize=10f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(0xFF54708D.toInt())})
                chip.addView(TextView(this).apply{text=value;textSize=13f;setTextColor(0xFF173C65.toInt());setPadding(0,2,0,0)})
                val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=6;card.addView(chip,lp)
            }
            label("FECHA",report.date+" "+report.time)
            label("TIPO",report.type)
            label("NÚMERO",report.number.toString())
            report.body.replace("\r","").lines().forEach { raw ->
                val value=raw.trim()
                if(value.isBlank()||value.startsWith("SENE-ORDER|1"))return@forEach
                if(value.startsWith("ETIQUETA:",true)) {
                    card.addView(TextView(this).apply{text=value.substringAfter(":").trim();textSize=13f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(0xFF123B68.toInt());setPadding(2,9,2,5)})
                } else if(value.contains(":")) label(value.substringBefore(":").trim(),value.substringAfter(":").trim())
                else label("DETALLE",value)
            }
            val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=10;content.addView(card,lp)
        }
        AlertDialog.Builder(this).setTitle("REPORTES DEL TRABAJADOR")
            .setView(ScrollView(this).apply{addView(content)}).setPositiveButton("CERRAR",null).show()
    }
    private fun addEntry(p:OwnerPayrollStore.Profile,type:String){
        val amount=field("Importe a descontar",true);val desc=field("Motivo o descripción")
        val box=LinearLayout(this).apply{orientation=1;setPadding(20,8,20,4);addView(amount);addView(desc)}
        AlertDialog.Builder(this).setTitle("REGISTRAR "+type).setView(box).setPositiveButton("GUARDAR",null).setNegativeButton("CANCELAR",null).create().also{d->
            d.setOnShowListener{d.getButton(-1).setOnClickListener{
                val cents=moneyToCents(amount.text.toString())
                if(cents==null||cents<=0){amount.error="Importe inválido";return@setOnClickListener}
                if(!OwnerPayrollStore.addEntry(this,p.id,type,cents,desc.text.toString().trim())){amount.error="No se pudo guardar";return@setOnClickListener}
                d.dismiss();Toast.makeText(this,"Movimiento guardado",Toast.LENGTH_LONG).show();showProfile(p.id)
            }}
            d.show()
        }
    }
    private fun payroll(p:OwnerPayrollStore.Profile){
        try{
            val pay=OwnerPayrollStore.createOrGetPayroll(this,p.id)
            if(pay.status!="PAGADA" && !OwnerPayrollStore.isPayDay(p)) {
                AlertDialog.Builder(this).setTitle("PAGO NO DISPONIBLE")
                    .setMessage("La nómina solo se puede pagar el día establecido: "+p.payDay.ifBlank{"Sin configurar"}+". Hoy no está permitido registrar el pago.")
                    .setPositiveButton("ACEPTAR",null).show()
                return
            }
            val reminder=if(OwnerPayrollStore.isPayDay(p))"\n\nRECUERDA: Revisa SENE VENTAS antes de pagar." else ""
            val message="PERÍODO: ${pay.period}\nSUELDO BASE (PROPORCIONAL DESDE EL INICIO EFECTIVO): ${OwnerPayrollStore.money(pay.grossCents)}\nLos días anteriores al registro o al primer inicio de turno no generan sueldo ni faltas. Solo cuentan los días laborales configurados desde el inicio efectivo.\nDESCUENTO POR FALTAS: ${OwnerPayrollStore.money(pay.absenceCents)}\nDESCUENTO POR SALIDAS ANTICIPADAS: ${OwnerPayrollStore.money(pay.earlyExitCents)}\nPRÉSTAMOS: ${OwnerPayrollStore.money(pay.loanCents)}\nCONSUMOS: ${OwnerPayrollStore.money(pay.consumptionCents)}\nTOTAL NETO: ${OwnerPayrollStore.money(pay.netCents)}\nESTADO: ${pay.status}$reminder"
            if(pay.status=="PAGADA"){AlertDialog.Builder(this).setTitle("NÓMINA PAGADA").setMessage(message+"\nFecha/hora: ${pay.paidAt}\nCantidad entregada: ${OwnerPayrollStore.money(pay.deliveredCents)}").setPositiveButton("CERRAR",null).show();return}
            AlertDialog.Builder(this).setTitle("NÓMINA PENDIENTE").setMessage(message)
                .setPositiveButton("COMPROBAR EN SENE VENTAS"){_,_->confirmSalesPayroll(pay)}
                .setNegativeButton("CERRAR",null).show()
        }catch(e:Exception){AlertDialog.Builder(this).setTitle("NÓMINA").setMessage(e.message?: "No se pudo generar la nómina").setPositiveButton("ACEPTAR",null).show()}
    }
    private fun confirmSalesPayroll(pay: OwnerPayrollStore.Payroll) {
        val profile=OwnerPayrollStore.profile(this,pay.profileId)
        if(profile==null || !OwnerPayrollStore.isPayDay(profile)) {
            Toast.makeText(this,"El pago solo está permitido el día establecido.",Toast.LENGTH_LONG).show()
            return
        }
        val amount = field("Total neto que aparece en SENE VENTAS", true)
        val message = "Abre SENE VENTAS > SOY PATRÓN > NÓMINA.\n\nComprueba que coincidan trabajador, período, sueldo base, faltas, préstamos, consumos y TOTAL NETO.\n\nTotal esperado en SENE ADMIN: " + OwnerPayrollStore.money(pay.netCents) + "\n\nSolo continúa si todos los conceptos coinciden."
        val dialog = AlertDialog.Builder(this).setTitle("COMPROBACIÓN DE NÓMINA")
            .setMessage(message).setView(amount).setPositiveButton("FINALIZAR PAGO", null)
            .setNegativeButton("CANCELAR", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = moneyToCents(amount.text.toString())
                if (entered == null || entered != pay.netCents) {
                    amount.error = "No coincide con el total de SENE ADMIN. No se registró el pago."
                    return@setOnClickListener
                }
                dialog.dismiss()
                markPaid(pay.id)
            }
        }
        dialog.show()
    }

    private fun markPaid(id:String){
        val delivered=field("Cantidad realmente entregada",true)
        AlertDialog.Builder(this).setTitle("CONFIRMAR PAGO").setMessage("La nómina se marcará PAGADA y quedará registrada la fecha, hora y cantidad entregada.")
            .setView(delivered).setPositiveButton("CONFIRMAR",null).setNegativeButton("CANCELAR",null).create().also{d->
                d.setOnShowListener{d.getButton(-1).setOnClickListener{
                    val cents=moneyToCents(delivered.text.toString())
                    if(cents==null){delivered.error="Cantidad inválida";return@setOnClickListener}
                    if(!OwnerPayrollStore.markPaid(this,id,cents)){delivered.error="No se pudo marcar como pagada";return@setOnClickListener}
                    d.dismiss();Toast.makeText(this,"Nómina marcada PAGADA",Toast.LENGTH_LONG).show();render()
                }}
                d.show()
            }
    }
    private fun moneyToCents(raw:String):Long?=raw.trim().replace("$","").replace(",","").toDoubleOrNull()?.takeIf{it>=0}?.let{(it*100.0).toLong()}
}
