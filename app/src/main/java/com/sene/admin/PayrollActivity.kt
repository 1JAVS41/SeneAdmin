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
    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        val profileId = intent.getStringExtra("profile_id")\n        if (profileId.isNullOrBlank()) render() else if (OwnerPayrollStore.profile(this, profileId) != null) showProfile(profileId) else render()\n    }
    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 24, 22, 24); setBackgroundColor(0xFFF4F7FB.toInt()) }
        root.addView(TextView(this).apply { text = "PERSONAL Y NÓMINA"; textSize = 23f; setTextColor(0xFF123B68.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = "Los datos de sueldo y jornada se pueden completar automáticamente al recibir el reporte de inicio de turno."; textSize = 13f; setTextColor(0xFF64748B.toInt()); setPadding(0, 6, 0, 16) })
        addCard("AGREGAR PERFIL", "Registrar trabajador y sueldo semanal") { addProfile() }
        OwnerPayrollStore.profiles(this).sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { p ->
            addCard(p.name, "ID: ${p.id} · Sueldo semanal: ${OwnerPayrollStore.money(p.weeklySalaryCents)} · Pago: ${p.payDay.ifBlank { "Sin configurar" }}" + if (OwnerPayrollStore.isPayDay(p)) " · HOY: Revisa SENE VENTAS antes de pagar" else "") { showProfile(p.id) }
        }
        addCard("VOLVER", "Regresar al panel principal") { finish() }
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
        val p=OwnerPayrollStore.profile(this,id)?:return
        val entries=OwnerPayrollStore.entries(this,id)
        val payrolls=OwnerPayrollStore.payrolls(this,id).sortedByDescending{it.createdAt}
        val pending=entries.filter{it.payrollId.isBlank()}
        val detail=buildString{
            append("TRABAJADOR: ").append(p.name).append("\nID: ").append(p.id)
            append("\nTELÉFONO: ").append(p.phone.ifBlank{"No indicado"})
            append("\nSUELDO SEMANAL: ").append(OwnerPayrollStore.money(p.weeklySalaryCents))
            append("\nDÍA DE PAGO: ").append(p.payDay.ifBlank{"Sin configurar"})
            append("\nDÍAS LABORALES: ").append(p.workDays.ifBlank{"Sin configurar"})
            if(OwnerPayrollStore.isPayDay(p)) append("\n\nRECUERDA: Revisa SENE VENTAS antes de pagar.")
            append("\n\nMOVIMIENTOS PENDIENTES: ").append(pending.size)
            pending.forEach{append("\n").append(it.date).append(" · ").append(it.type).append(" · ").append(OwnerPayrollStore.money(it.amountCents)).append(" · ").append(it.description)}
            append("\n\nHISTORIAL DE NÓMINA:")
            if(payrolls.isEmpty())append("\nTodavía no hay nóminas.")
            payrolls.forEach{append("\n").append(it.period).append(" · ").append(it.status).append(" · Neto ").append(OwnerPayrollStore.money(it.netCents))
                if(it.status=="PAGADA")append(" · Entregado ").append(OwnerPayrollStore.money(it.deliveredCents)).append(" · ").append(it.paidAt)}
        }
        val box=LinearLayout(this).apply{orientation=1;setPadding(18,8,18,8)}
        box.addView(TextView(this).apply{text=detail;textSize=13f;setTextColor(0xFF26364F.toInt())})
        lateinit var dialog: AlertDialog
        fun option(label:String, action:()->Unit) {
            box.addView(Button(this).apply{text=label;isAllCaps=false;setOnClickListener{dialog.dismiss();action()}})
        }
        option("REGISTRAR FALTA"){addEntry(p,"FALTA")}
        option("REGISTRAR PRÉSTAMO"){addEntry(p,"PRESTAMO")}
        option("REGISTRAR CONSUMO"){addEntry(p,"CONSUMO")}
        option("GENERAR / CONSULTAR NÓMINA"){payroll(p)}
        dialog=AlertDialog.Builder(this).setTitle(p.name).setView(ScrollView(this).apply{addView(box)})
            .setPositiveButton("CERRAR",null).create()
        dialog.show()
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
                .setPositiveButton("MARCAR PAGADA"){_,_->markPaid(pay.id)}
                .setNegativeButton("CERRAR",null).show()
        }catch(e:Exception){AlertDialog.Builder(this).setTitle("NÓMINA").setMessage(e.message?: "No se pudo generar la nómina").setPositiveButton("ACEPTAR",null).show()}
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
