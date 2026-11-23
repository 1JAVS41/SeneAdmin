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
        AdminPayrollRequestSync.sync(this,true)
        val profileId = intent.getStringExtra("profile_id")
        if (profileId.isNullOrBlank()) render() else if (OwnerPayrollStore.profile(this, profileId) != null) {
            setContentView(TextView(this).apply { text = "Perfil del trabajador"; setPadding(24, 24, 24, 24) })
            showProfile(profileId)
        } else render()
    }
    private fun render() {
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF1F5FB.toInt())
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(21), dp(21), dp(21))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF173A78.toInt(), 0xFF3478D4.toInt(), 0xFF69A6F2.toInt())
            ).apply { cornerRadius = dp(24).toFloat() }
            elevation = dp(4).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = "SENE / ADMIN"; textSize = 11f; letterSpacing = .14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFFDCEBFF.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "PERSONAL Y NÓMINA"; textSize = 25f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(-1); setPadding(0, dp(7), 0, dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "Asistencia, sueldo, descuentos y pagos de cada trabajador."
            textSize = 12f; setTextColor(0xFFE5EEFF.toInt())
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(17) })
        root.addView(Button(this).apply {
            text = "SOLICITUDES DE PRÉSTAMO Y CONSUMO"
            isAllCaps = false
            setOnClickListener { showRequests() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        root.addView(TextView(this).apply {
            text = "TRABAJADORES REGISTRADOS"; textSize = 12f; letterSpacing = .1f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF60738F.toInt())
            setPadding(dp(2), 0, 0, dp(10))
        })
        val profiles = OwnerPayrollStore.profiles(this).sortedBy { it.name.lowercase(Locale.ROOT) }
        if (profiles.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1); cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), 0xFFDCE6F3.toInt())
                }
            }
            empty.addView(TextView(this).apply {
                text = "Aún no hay trabajadores"; textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF193B68.toInt())
            })
            empty.addView(TextView(this).apply {
                text = "Los perfiles aparecerán al recibir reportes de inicio de turno desde SENE VENTAS."
                textSize = 13f; setTextColor(0xFF687B96.toInt()); setPadding(0, dp(5), 0, 0)
            })
            root.addView(empty)
        }
        profiles.forEach { p ->
            val subtitle = "Sueldo semanal: ${OwnerPayrollStore.money(p.weeklySalaryCents)} · Pago: ${p.payDay.ifBlank { "Sin configurar" }}" +
                if (OwnerPayrollStore.isPayDay(p)) " · HOY: Revisa SENE VENTAS antes de pagar" else ""
            addCard(p.name, subtitle) { showWorkerMenu(p.id) }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false
            addView(root)
        })
    }
    private fun showWorkerMenu(id:String) {
        val profile=OwnerPayrollStore.profile(this,id)?:return
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(arrayOf("Consultar nómina y asistencia","Ver reportes")) { _,which ->
                if(which==0)showProfile(id) else showReportsForWorker(id)
            }.show()
    }

    private fun requireOwnerPin(onAuthorized:()->Unit) {
        if(!AccessPinStore.hasPin(this)) {
            AlertDialog.Builder(this).setTitle("PIN NO CONFIGURADO")
                .setMessage("Primero configura el PIN privado de SeneAdmin.")
                .setPositiveButton("ACEPTAR",null).show()
            return
        }
        val pin=EditText(this).apply {
            hint="PIN de SeneAdmin"
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val dialog=AlertDialog.Builder(this).setTitle("CONFIRMAR AL PATRÓN")
            .setMessage("Ingresa tu PIN para confirmar esta decisión.")
            .setView(pin).setPositiveButton("AUTORIZAR",null).setNegativeButton("CANCELAR",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(!AccessPinStore.matches(this,pin.text.toString())) {
                    pin.error="PIN incorrecto"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onAuthorized()
            }
        }
        dialog.show()
    }

    private fun showRequests() {
        AdminPayrollRequestSync.sync(this,true)
        fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
        val all=OwnerPayrollStore.requests(this)
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(18))
            setBackgroundColor(0xFFF1F5FB.toInt())
        }
        if(all.isEmpty()) {
            content.addView(TextView(this).apply {
                text="No hay solicitudes recibidas todavía. Cuando SENE VENTAS sincronice una solicitud, aparecerá aquí."
                textSize=14f;setTextColor(0xFF53657D.toInt());setPadding(dp(12),dp(16),dp(12),dp(16))
            })
        }
        all.forEach { request ->
            val worker=OwnerPayrollStore.profile(this,request.profileId)?.name ?: request.profileId
            val kind=if(request.type=="PRESTAMO")"PRÉSTAMO" else "CONSUMO"
            val state=when {
                request.status=="APPROVED" -> "AUTORIZADA"
                request.status=="REJECTED" -> "RECHAZADA"
                request.ownerDecision=="APPROVE" -> "APROBACIÓN PENDIENTE DE SINCRONIZAR"
                request.ownerDecision=="REJECT" -> "RECHAZO PENDIENTE DE SINCRONIZAR"
                else -> "PENDIENTE DE AUTORIZACIÓN"
            }
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(13))
                background=android.graphics.drawable.GradientDrawable().apply {
                    setColor(-1);cornerRadius=dp(17).toFloat();setStroke(dp(1),0xFFD9E3F0.toInt())
                }
            }
            card.addView(TextView(this).apply {
                text="$kind · $worker";textSize=16f;typeface=android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF173A68.toInt())
            })
            val details=buildString {
                append("Fecha: ").append(request.date).append("\n")
                append("Importe: ").append(OwnerPayrollStore.money(request.amountCents)).append("\n")
                if(request.description.isNotBlank())append("Detalle: ").append(request.description).append("\n")
                if(request.productCode.isNotBlank())append("Código: ").append(request.productCode).append(" · Cantidad: ").append(request.quantity).append("\n")
                append("Estado: ").append(state)
                if(request.decidedBy.isNotBlank())append("\nAutorizó: ").append(request.decidedBy)
                if(request.rejectionReason.isNotBlank())append("\nMotivo de rechazo: ").append(request.rejectionReason)
            }
            card.addView(TextView(this).apply {
                text=details;textSize=13f;setTextColor(0xFF53657D.toInt());setPadding(0,dp(7),0,dp(7))
            })
            if(request.status=="PENDING"&&request.ownerDecision.isBlank()) {
                val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                actions.addView(Button(this@PayrollActivity).apply {
                    text="AUTORIZAR";isAllCaps=false
                    setOnClickListener {
                        AlertDialog.Builder(this@PayrollActivity)
                            .setTitle("AUTORIZAR $kind")
                            .setMessage("La autorización se enviará al servidor. El descuento de nómina y, en consumos, el movimiento de inventario solo se aplicarán cuando el servidor confirme la decisión.")
                            .setPositiveButton("CONFIRMAR") { _, _ ->
                                requireOwnerPin {
                                    if(OwnerPayrollStore.queueRequestDecision(this@PayrollActivity,request.id,true)) {
                                        Toast.makeText(this@PayrollActivity,"Autorización guardada. Enviando decisión al servidor...",Toast.LENGTH_LONG).show()
                                        AdminPayrollRequestSync.sync(this@PayrollActivity,true)
                                    }
                                    showRequests()
                                }
                            }.setNegativeButton("CANCELAR",null).show()
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                actions.addView(Button(this@PayrollActivity).apply {
                    text="RECHAZAR";isAllCaps=false
                    setOnClickListener {
                        val reason=EditText(this@PayrollActivity).apply{hint="Motivo (opcional)"}
                        AlertDialog.Builder(this@PayrollActivity).setTitle("RECHAZAR $kind").setView(reason)
                            .setPositiveButton("CONFIRMAR") { _, _ ->
                                requireOwnerPin {
                                    if(OwnerPayrollStore.queueRequestDecision(this@PayrollActivity,request.id,false,reason.text.toString())) {
                                        Toast.makeText(this@PayrollActivity,"Rechazo guardado. Enviando decisión al servidor...",Toast.LENGTH_LONG).show()
                                        AdminPayrollRequestSync.sync(this@PayrollActivity,true)
                                    }
                                    showRequests()
                                }
                            }.setNegativeButton("CANCELAR",null).show()
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                card.addView(actions)
            }
            content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        }
        AlertDialog.Builder(this).setTitle("SOLICITUDES · AUTORIZACIÓN")
            .setView(ScrollView(this).apply{addView(content)})
            .setPositiveButton("CERRAR",null).show()
    }

    private fun addCard(title: String, subtitle: String, action: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(15, 14, 15, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(-1); cornerRadius = 19f; setStroke(1, 0xFFDCE6F3.toInt())
            }
            elevation = 2f; isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
        val icon = TextView(this).apply {
            text = "♙"; textSize = 22f; gravity = android.view.Gravity.CENTER
            setTextColor(0xFF285A9A.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEAF2FD.toInt()); cornerRadius = 15f
            }
        }
        box.addView(icon, LinearLayout.LayoutParams(48, 48))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 0, 5, 0)
        }
        labels.addView(TextView(this).apply {
            text = title; textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF173C65.toInt())
        })
        labels.addView(TextView(this).apply {
            text = subtitle; textSize = 12f; setTextColor(0xFF64748B.toInt())
            setPadding(0, 4, 0, 0)
        })
        box.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(TextView(this).apply {
            text = "›"; textSize = 27f; setTextColor(0xFF4777B9.toInt())
        })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = 10
        root.addView(box, lp)
    }
    private fun field(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint; setSingleLine(true); textSize = 15f
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun addProfile() {
        val name=field("Nombre del trabajador"); val id=field("ID único del trabajador"); val phone=field("Teléfono")
        val salary=field("Sueldo semanal",true); val day=field("Día de pago (ej. Viernes)")
        val days=field("Días laborales (ej. Lunes, Martes, Miércoles)")
        val hours=field("Horas por jornada",true)
        val box=LinearLayout(this).apply { orientation=1;setPadding(20,8,20,4);listOf(name,id,phone,salary,day,days,hours).forEach{addView(it)} }
        AlertDialog.Builder(this).setTitle("NUEVO PERFIL").setMessage("Completa todos los datos obligatorios. Las horas de jornada no se asignan automáticamente.")
            .setView(ScrollView(this).apply{addView(box)}).setPositiveButton("GUARDAR",null).setNegativeButton("CANCELAR",null).create().also{d->
                d.setOnShowListener{d.getButton(-1).setOnClickListener{
                    val workerId=id.text.toString().trim();val workerName=name.text.toString().trim()
                    val cleanPhone=phone.text.toString().filter{it.isDigit()}
                    val amount=moneyToCents(salary.text.toString())
                    val workHours=hours.text.toString().trim().toIntOrNull()
                    val payDay=day.text.toString().trim()
                    val workDays=days.text.toString().trim()
                    var valid=true
                    if(workerId.isBlank()){id.error="ID requerido";valid=false}
                    if(workerName.isBlank()){name.error="Nombre requerido";valid=false}
                    if(cleanPhone.length !in 10..15){phone.error="Teléfono obligatorio, con 10 a 15 dígitos";valid=false}
                    if(amount==null||amount<=0L){salary.error="Sueldo semanal inválido";valid=false}
                    if(payDay.isBlank()){day.error="Día de pago obligatorio";valid=false}
                    if(workDays.isBlank()){days.error="Días laborales obligatorios";valid=false}
                    if(workHours==null||workHours !in 1..24){hours.error="Indica entre 1 y 24 horas";valid=false}
                    if(!valid)return@setOnClickListener
                    val ok=OwnerPayrollStore.upsertProfile(this,OwnerPayrollStore.Profile(workerId,workerName,cleanPhone,amount!!,payDay,workDays,workHours!!))
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
        tag("HORAS DE JORNADA",if(p.hours in 1..24)p.hours.toString()+" horas" else "Por definir")
        OwnerPayrollStore.attendanceSummary(this,id)?.let { summary ->
            tag("ÚLTIMO PERÍODO",summary.period)
            tag("DÍAS TRABAJADOS / FALTAS",summary.workedDays.toString()+" trabajados · "+summary.absences+" faltas")
            tag("TURNO",summary.started.ifBlank{"—"}+" · Finalizó: "+summary.closed.ifBlank{"Por definir"})
            if(summary.closingType.isNotBlank())tag("TIPO DE CIERRE",when(summary.closingType){"EARLY_EXIT"->"CIERRE DE TURNO ANTICIPADO";"ADMIN_FORCED","ADMINISTRATIVE"->"CIERRE ADMINISTRATIVO";else->summary.closingType})
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
                "NOT_WORKED" -> "Sin registro de asistencia"
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
        option("VER REPORTES") { showReportsForWorker(p.id) }
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
