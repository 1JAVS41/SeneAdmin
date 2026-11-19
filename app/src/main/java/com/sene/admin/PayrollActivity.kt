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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); setBackgroundColor(-1); elevation = 2f }
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
        val detail = buildString {
            append("TRABAJADOR: ").append(p.name)
            append("\nTELÉFONO: ").append(p.phone.ifBlank { "No indicado" })
            append("\nDÍAS LABORALES: ").append(p.workDays.ifBlank { "Sin configurar" })
            OwnerPayrollStore.attendanceSummary(this@PayrollActivity, id)?.let { summary ->
                append("\n\nASISTENCIA DEL ÚLTIMO CIERRE")
                append("\nPeríodo: ").append(summary.period)
                append("\nDías trabajados: ").append(summary.workedDays)
                append("\nDías de falta: ").append(summary.absences)
                append("\nTipo de cierre: ").append(summary.closingType.ifBlank { "No indicado" })
                append("\nEntrada: ").append(summary.started.ifBlank { "No indicada" })
                append("\nSalida: ").append(summary.closed.ifBlank { "No indicada" })
                if (summary.closingReason.isNotBlank()) append("\nMotivo: ").append(summary.closingReason)
            }
            append("\nDÍA DE PAGO: ").append(p.payDay.ifBlank { "Sin configurar" })
            append("\nSUELDO SEMANAL: ").append(OwnerPayrollStore.money(p.weeklySalaryCents))
            if (pay != null) {
                append("\n\nDESGLOSE DE LA SEMANA")
                append("\nSueldo base: ").append(OwnerPayrollStore.money(pay.grossCents))
                append("\nDescuento por faltas: ").append(OwnerPayrollStore.money(pay.absenceCents))
                append("\nPréstamos: ").append(OwnerPayrollStore.money(pay.loanCents))
                append("\nConsumos: ").append(OwnerPayrollStore.money(pay.consumptionCents))
                append("\nTOTAL NETO: ").append(OwnerPayrollStore.money(pay.netCents))
                append("\nESTADO: ").append(pay.status)
            } else append("\n\nTodavía falta configurar el sueldo semanal para generar nómina.")
            append("\n\nMOVIMIENTOS REGISTRADOS:")
            if (entries.isEmpty()) append("\nTodavía no hay movimientos.")
            entries.sortedByDescending { it.date }.forEach {
                append("\n").append(it.date).append(" · ").append(it.type)
                    .append(" · ").append(OwnerPayrollStore.money(it.amountCents))
                    .append(" · ").append(it.description)
            }
            append("\n\nHISTORIAL DE NÓMINA:")
            if (allPayrolls.isEmpty()) append("\nTodavía no hay nóminas.")
            allPayrolls.forEach {
                append("\n").append(it.period).append(" · ").append(it.status)
                    .append(" · Neto ").append(OwnerPayrollStore.money(it.netCents))
            }
        }
        val box = LinearLayout(this).apply { orientation = 1; setPadding(18, 8, 18, 8) }
        box.addView(TextView(this).apply {
            text = detail; textSize = 13f; setTextColor(0xFF26364F.toInt())
        })
        lateinit var dialog: AlertDialog
        fun option(label: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        option("CONSULTAR / PAGAR NÓMINA") { payroll(p) }
        option("VER OTROS REPORTES DEL TRABAJADOR") { showReportsForWorker(id) }
        dialog = AlertDialog.Builder(this).setTitle("PAGAR A · " + p.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("CERRAR", null).create()
        dialog.show()
    }

    private fun showReportsForWorker(id: String) {
        val lines = SeneAdminStore.reports(this).filter { it.workerId == id }
            .sortedWith(compareBy<SeneReport> { it.date }.thenBy { it.time }.thenBy { it.number })
        val body = if (lines.isEmpty()) "Todavía no hay reportes recibidos para este trabajador."
        else lines.joinToString("\n\n") { it.date + " " + it.time + " · " + it.type + "\n\n" + it.body }
        AlertDialog.Builder(this).setTitle("REPORTES DEL TRABAJADOR")
            .setMessage(body).setPositiveButton("CERRAR", null).show()
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
            val reminder=if(OwnerPayrollStore.isPayDay(p))"\n\nRECUERDA: Revisa SENE VENTAS antes de pagar." else ""
            val message="PERÍODO: ${pay.period}\nSUELDO: ${OwnerPayrollStore.money(pay.grossCents)}\nDESCUENTO POR FALTAS: ${OwnerPayrollStore.money(pay.absenceCents)}\nPRÉSTAMOS: ${OwnerPayrollStore.money(pay.loanCents)}\nCONSUMOS: ${OwnerPayrollStore.money(pay.consumptionCents)}\nTOTAL NETO: ${OwnerPayrollStore.money(pay.netCents)}\nESTADO: ${pay.status}$reminder"
            if(pay.status=="PAGADA"){AlertDialog.Builder(this).setTitle("NÓMINA PAGADA").setMessage(message+"\nFecha/hora: ${pay.paidAt}\nCantidad entregada: ${OwnerPayrollStore.money(pay.deliveredCents)}").setPositiveButton("CERRAR",null).show();return}
            AlertDialog.Builder(this).setTitle("NÓMINA PENDIENTE").setMessage(message)
                .setPositiveButton("COMPROBAR EN SENE VENTAS"){_,_->confirmSalesPayroll(pay)}
                .setNegativeButton("CERRAR",null).show()
        }catch(e:Exception){AlertDialog.Builder(this).setTitle("NÓMINA").setMessage(e.message?: "No se pudo generar la nómina").setPositiveButton("ACEPTAR",null).show()}
    }
    private fun confirmSalesPayroll(pay: OwnerPayrollStore.Payroll) {
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
