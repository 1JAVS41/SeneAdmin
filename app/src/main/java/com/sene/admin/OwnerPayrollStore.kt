package com.sene.admin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object OwnerPayrollStore {
    data class Profile(val id:String,val name:String,val phone:String,val weeklySalaryCents:Long,val payDay:String,val workDays:String,val hours:Int)
    data class Entry(val id:String,val profileId:String,val type:String,val date:String,val description:String,val amountCents:Long,val payrollId:String)
    data class Payroll(val id:String,val profileId:String,val period:String,val grossCents:Long,val absenceCents:Long,val loanCents:Long,val consumptionCents:Long,val netCents:Long,val status:String,val createdAt:String,val paidAt:String,val deliveredCents:Long)
    private const val PREFS="sene_admin_payroll_v1"
    private const val PROFILES="profiles"
    private const val ENTRIES="entries"
    private const val PAYROLLS="payrolls"
    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun array(c:Context,key:String)=try{JSONArray(prefs(c).getString(key,"[]")?:"[]")}catch(_:Exception){JSONArray()}
    private fun saveArray(c:Context,key:String,a:JSONArray)=prefs(c).edit().putString(key,a.toString()).commit()

    fun profiles(c:Context):List<Profile>{
        val a=array(c,PROFILES);return (0 until a.length()).mapNotNull{i->try{val o=a.getJSONObject(i);Profile(o.getString("id"),o.getString("name"),o.optString("phone"),o.optLong("salary"),o.optString("payDay"),o.optString("workDays"),o.optInt("hours",8))}catch(_:Exception){null}}
    }
    fun profile(c:Context,id:String)=profiles(c).firstOrNull{it.id==id}
    fun upsertProfile(c:Context,p:Profile):Boolean{
        val a=JSONArray();var replaced=false
        profiles(c).forEach{old->if(old.id==p.id){a.put(profileJson(p));replaced=true}else a.put(profileJson(old))}
        if(!replaced)a.put(profileJson(p))
        return saveArray(c,PROFILES,a)
    }
    private fun profileJson(p:Profile)=JSONObject().put("id",p.id).put("name",p.name).put("phone",p.phone).put("salary",p.weeklySalaryCents).put("payDay",p.payDay).put("workDays",p.workDays).put("hours",p.hours)

    private fun reportValue(lines:List<String>,vararg labels:String):String {
        val line=lines.firstOrNull { raw ->
            val value=raw.trim()
            labels.any { label -> value.startsWith("$label:",true) }
        } ?: return ""
        return line.substringAfter(":").trim()
    }

    private fun parseMoneyCents(raw:String):Long? {
        var value=raw.trim().replace("$","").replace(" ","").replace("\u00A0","")
        if(value.isBlank())return null
        val comma=value.lastIndexOf(',')
        val dot=value.lastIndexOf('.')
        value=when {
            comma>=0 && dot>=0 && comma>dot -> value.replace(".","").replace(',','.')
            comma>=0 && dot>=0 -> value.replace(",","")
            comma>=0 -> if(value.length-comma-1 in 1..2)value.replace(',','.') else value.replace(",","")
            else -> value
        }
        return value.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
    }

    private fun isoDate(raw:String):String {
        val value=raw.trim()
        val patterns=listOf("yyyy-MM-dd","yyyy-MM-dd HH:mm:ss","dd/MM/yyyy","dd/MM/yyyy HH:mm","dd/MM/yyyy HH:mm:ss")
        for(pattern in patterns)try{
            val parsed=SimpleDateFormat(pattern,Locale("es","MX")).apply{isLenient=false}.parse(value)?:continue
            return SimpleDateFormat("yyyy-MM-dd",Locale.US).format(parsed)
        }catch(_:Exception){}
        return value.take(10)
    }

    private fun payDayNumber(value:String):Int=when(value.trim().lowercase(Locale("es","MX"))){
        "domingo"->Calendar.SUNDAY
        "lunes"->Calendar.MONDAY
        "martes"->Calendar.TUESDAY
        "miércoles","miercoles"->Calendar.WEDNESDAY
        "jueves"->Calendar.THURSDAY
        "viernes"->Calendar.FRIDAY
        "sábado","sabado"->Calendar.SATURDAY
        else->Calendar.MONDAY
    }

    /** Importa el perfil y, en los reportes de cierre, la asistencia y las solicitudes de nómina. */
    fun importProfile(c:Context,r:SeneReport):Boolean {
        val lines=r.body.replace("\r","").lines()
        val stamp=r.date+" "+r.time
        val stampKey="profile_import_"+r.workerId
        val last=prefs(c).getString(stampKey,null)
        if(last==null||stamp>last){
            val old=profile(c,r.workerId)
            val name=reportValue(lines,"TRABAJADOR","Trabajador").ifBlank{old?.name?:r.workerId}
            val phone=reportValue(lines,"TELÉFONO","TELEFONO","Número del trabajador").ifBlank{old?.phone.orEmpty()}
            val salary=parseMoneyCents(reportValue(lines,"SUELDO SEMANAL"))?:old?.weeklySalaryCents?:0L
            val payDay=reportValue(lines,"DÍA DE PAGO","DIA DE PAGO").ifBlank{old?.payDay.orEmpty()}
            val workDays=reportValue(lines,"DÍAS LABORALES","DIAS LABORALES").ifBlank{old?.workDays.orEmpty()}
            val hours=Regex("\\d+").find(reportValue(lines,"HORAS DE JORNADA"))?.value?.toIntOrNull()?.coerceIn(1,24)?:old?.hours?:8
            if(!upsertProfile(c,Profile(r.workerId,name,phone,salary,payDay,workDays,hours)))return false
            if(!prefs(c).edit().putString(stampKey,stamp).commit())return false
        }
        return importAttendance(c,r,lines,stamp) && importPayrollRequests(c,r,lines)
    }

    private fun importAttendance(c:Context,r:SeneReport,lines:List<String>,stamp:String):Boolean {
        val worked=reportValue(lines,"DÍAS TRABAJADOS REGISTRADOS","DIAS TRABAJADOS REGISTRADOS").toIntOrNull()
        val absences=reportValue(lines,"FALTAS REGISTRADAS HASTA EL CIERRE").toIntOrNull()
        val period=isoDate(reportValue(lines,"PERÍODO DESDE","PERIODO DESDE"))
        if((worked==null&&absences==null)||period.isBlank())return true
        val stampKey="attendance_import_"+r.workerId
        val last=prefs(c).getString(stampKey,null)
        if(last!=null&&stamp<=last)return true
        val summary=try{JSONObject(prefs(c).getString("attendance_summary","{}")?:"{}")}catch(_:Exception){JSONObject()}
        summary.put(r.workerId,JSONObject().put("period",period).put("workedDays",worked?:0).put("absences",absences?:0)
            .put("closingType",reportValue(lines,"TIPO DE CIERRE")).put("closingReason",reportValue(lines,"MOTIVO DE CIERRE"))
            .put("started",reportValue(lines,"HORA DE ENTRADA")).put("closed",reportValue(lines,"HORA DE SALIDA")))
        if(!prefs(c).edit().putString("attendance_summary",summary.toString()).commit())return false

        val count=absences?:return prefs(c).edit().putString(stampKey,stamp).commit()
        val p=profile(c,r.workerId)?:return prefs(c).edit().putString(stampKey,stamp).commit()
        val daysPerWeek=reportValue(lines,"DÍAS LABORABLES POR SEMANA","DIAS LABORABLES POR SEMANA").toIntOrNull()
            ?:p.workDays.split(",").map{it.trim()}.filter{it.isNotBlank()}.size
        if(daysPerWeek<=0||p.weeklySalaryCents<=0L)return prefs(c).edit().putString(stampKey,stamp).commit()
        val payDay=payDayNumber(p.payDay)
        val payrollPeriod=periodForDate(period,payDay)
        if(payrolls(c,r.workerId).any{it.period==payrollPeriod&&it.status=="PAGADA"})
            return prefs(c).edit().putString(stampKey,stamp).commit()
        val amount=(p.weeklySalaryCents/daysPerWeek*count).coerceAtMost(p.weeklySalaryCents)
        val id="attendance-absence-"+r.workerId+"-"+payrollPeriod
        val a=array(c,ENTRIES)
        val index=(0 until a.length()).firstOrNull{a.optJSONObject(it)?.optString("id")==id}
        val oldEntry=index?.let{a.optJSONObject(it)}
        val entry=JSONObject().put("id",id).put("profileId",r.workerId).put("type","FALTA")
            .put("date",payrollPeriod).put("description","Faltas reportadas por SENE VENTAS: $count")
            .put("amount",amount).put("payrollId",oldEntry?.optString("payrollId").orEmpty())
        if(index==null)a.put(entry)else a.put(index,entry)
        if(!saveArray(c,ENTRIES,a))return false
        return prefs(c).edit().putString(stampKey,stamp).commit()
    }

    private fun importPayrollRequests(c:Context,r:SeneReport,lines:List<String>):Boolean {
        val marker=lines.indexOfFirst{it.trim().equals("ETIQUETA: SOLICITUDES DE NÓMINA PENDIENTES",true)}
        if(marker<0)return true
        val profileId=r.workerId
        val existing=entries(c,profileId)
        val pending=mutableListOf<Entry>()
        var kind=""
        var amount=0L
        var date=""
        var reason=""
        var description=""
        var status=""
        fun flush(){
            if(kind.isBlank()||amount<=0L||status.equals("LIQUIDADO",true))return
            val type=if(kind.contains("PRÉSTAMO",true)||kind.contains("PRESTAMO",true))"PRESTAMO" else "CONSUMO"
            val day=isoDate(date)
            if(day.isBlank())return
            val details=listOf(reason.takeIf{it.isNotBlank()}?.let{"Motivo: $it"},description.takeIf{it.isNotBlank()}?.let{"Descripción: $it"}).filterNotNull().joinToString(" · ")
            val duplicate=existing.any{old->
                old.type==type&&old.amountCents==amount&&isoDate(old.date)==day&&
                    (old.description==details||old.description==description||description.isNotBlank()&&old.description.contains(description,true))
            }||pending.any{old->old.type==type&&old.amountCents==amount&&old.date==day&&old.description==details}
            if(!duplicate){
                val identity=listOf(profileId,type,date,amount.toString(),reason,description).joinToString("|")
                val id="report-request-"+UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
                val payrollPeriod=periodForDate(day,payDayNumber(profile(c,profileId)?.payDay.orEmpty()))
                if(!payrolls(c,profileId).any{it.period==payrollPeriod&&it.status=="PAGADA"})
                    pending.add(Entry(id,profileId,type,day,details,amount,""))
            }
        }
        for(line in lines.drop(marker+1)){
            val value=line.trim()
            if(value.startsWith("ETIQUETA:",true))break
            val upper=value.uppercase(Locale("es","MX"))
            if(upper.startsWith("PRÉSTAMO |")||upper.startsWith("PRESTAMO |")||upper.startsWith("CONSUMO |")){
                flush()
                kind=value.substringBefore("|").trim()
                amount=parseMoneyCents(value.substringAfter("|"))?:0L
                date="";reason="";description="";status=""
            }else if(value.startsWith("FECHA:",true))date=value.substringAfter(":").trim()
            else if(value.startsWith("MOTIVO:",true))reason=value.substringAfter(":").trim()
            else if(value.startsWith("DESCRIPCIÓN:",true)||value.startsWith("DESCRIPCION:",true))description=value.substringAfter(":").trim()
            else if(value.startsWith("ESTADO:",true))status=value.substringAfter(":").trim()
        }
        flush()
        if(pending.isEmpty())return true
        val a=array(c,ENTRIES)
        pending.forEach{entry->a.put(JSONObject().put("id",entry.id).put("profileId",entry.profileId).put("type",entry.type)
            .put("date",entry.date).put("description",entry.description).put("amount",entry.amountCents).put("payrollId",entry.payrollId))}
        return saveArray(c,ENTRIES,a)
    }

    data class AttendanceSummary(val period:String,val workedDays:Int,val absences:Int,val closingType:String,val closingReason:String,val started:String,val closed:String)
    fun attendanceSummary(c:Context,profileId:String):AttendanceSummary? {
        return try{
            val all=JSONObject(prefs(c).getString("attendance_summary","{}")?:"{}")
            val o=all.optJSONObject(profileId) ?: return null
            AttendanceSummary(o.optString("period"),o.optInt("workedDays"),o.optInt("absences"),
                o.optString("closingType"),o.optString("closingReason"),o.optString("started"),o.optString("closed"))
        }catch(_:Exception){null}
    }

    fun addEntry(c:Context,profileId:String,type:String,amountCents:Long,description:String,date:String=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())):Boolean{
        require(type in setOf("FALTA","PRESTAMO","CONSUMO") && amountCents>=0)
        val a=array(c,ENTRIES)
        a.put(JSONObject().put("id",UUID.randomUUID().toString()).put("profileId",profileId).put("type",type)
            .put("date",date).put("description",description).put("amount",amountCents).put("payrollId",""))
        return saveArray(c,ENTRIES,a)
    }
    fun entries(c:Context,profileId:String):List<Entry>{
        val a=array(c,ENTRIES);return (0 until a.length()).mapNotNull{i->try{val o=a.getJSONObject(i);if(o.getString("profileId")!=profileId)null else Entry(o.getString("id"),profileId,o.getString("type"),o.getString("date"),o.optString("description"),o.optLong("amount"),o.optString("payrollId"))}catch(_:Exception){null}}
    }
    fun payrolls(c:Context,profileId:String):List<Payroll>{
        val a=array(c,PAYROLLS);return (0 until a.length()).mapNotNull{i->try{val o=a.getJSONObject(i);if(o.getString("profileId")!=profileId)null else Payroll(o.getString("id"),profileId,o.getString("period"),o.optLong("gross"),o.optLong("absence"),o.optLong("loan"),o.optLong("consumption"),o.optLong("net"),o.optString("status"),o.optString("createdAt"),o.optString("paidAt"),o.optLong("delivered"))}catch(_:Exception){null}}
    }
    fun createOrGetPayroll(c:Context,profileId:String):Payroll{
        val p=profile(c,profileId)?:throw IllegalStateException("Perfil no encontrado")
        require(p.weeklySalaryCents>0L) { "El perfil todavía no tiene sueldo semanal configurado" }
        val payDay = when (p.payDay.trim().lowercase(Locale("es","MX"))) {
            "domingo" -> Calendar.SUNDAY
            "lunes" -> Calendar.MONDAY
            "martes" -> Calendar.TUESDAY
            "miércoles", "miercoles" -> Calendar.WEDNESDAY
            "jueves" -> Calendar.THURSDAY
            "viernes" -> Calendar.FRIDAY
            "sábado", "sabado" -> Calendar.SATURDAY
            else -> Calendar.MONDAY
        }
        val week=Calendar.getInstance().apply {
            val delta=(get(Calendar.DAY_OF_WEEK)-payDay+7)%7
            add(Calendar.DAY_OF_YEAR,-delta)
        }.let{SimpleDateFormat("yyyy-MM-dd",Locale.US).format(it.time)}
        val payrollArray=array(c,PAYROLLS)
        val existingIndex=(0 until payrollArray.length()).firstOrNull {
            val item=payrollArray.optJSONObject(it)
            item != null && item.optString("profileId")==profileId && item.optString("period")==week
        }
        val existing=existingIndex?.let { payrollArray.optJSONObject(it) }
        if(existing?.optString("status")=="PAGADA") {
            return payrolls(c,profileId).first { it.period==week }
        }
        val payrollId=existing?.optString("id")?.takeIf{it.isNotBlank()}?:UUID.randomUUID().toString()
        val relevant=entries(c,profileId).filter {
            (it.payrollId==payrollId) || (it.payrollId.isBlank() && periodForDate(it.date,payDay)==week)
        }
        val absence=relevant.filter{it.type=="FALTA"}.sumOf{it.amountCents}
        val loan=relevant.filter{it.type=="PRESTAMO"}.sumOf{it.amountCents}
        val consumption=relevant.filter{it.type=="CONSUMO"}.sumOf{it.amountCents}
        val net=(p.weeklySalaryCents-absence-loan-consumption).coerceAtLeast(0L)
        val payroll=Payroll(payrollId,profileId,week,p.weeklySalaryCents,absence,loan,consumption,net,"PENDIENTE",
            existing?.optString("createdAt")?.takeIf{it.isNotBlank()}?:SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date()),"",0L)
        if(existingIndex==null) payrollArray.put(payrollJson(payroll)) else payrollArray.put(existingIndex,payrollJson(payroll))
        check(saveArray(c,PAYROLLS,payrollArray)){"No se pudo guardar la nómina"}
        val entryArray=array(c,ENTRIES)
        val relevantIds=relevant.map{it.id}.toSet()
        for(i in 0 until entryArray.length()){
            val item=entryArray.optJSONObject(i)?:continue
            if(item.optString("profileId")==profileId && item.optString("id") in relevantIds && item.optString("payrollId").isBlank())
                item.put("payrollId",payrollId)
        }
        check(saveArray(c,ENTRIES,entryArray)){"No se pudieron asociar los movimientos a la nómina"}
        return payroll
    }
    private fun periodForDate(value:String,payDay:Int):String {
        val date = try { SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { isLenient=false }.parse(value) } catch (_:Exception) { null } ?: return ""
        val cal = Calendar.getInstance().apply { time = date }
        val delta=(cal.get(Calendar.DAY_OF_WEEK)-payDay+7)%7
        cal.add(Calendar.DAY_OF_YEAR,-delta)
        return SimpleDateFormat("yyyy-MM-dd",Locale.US).format(cal.time)
    }
    private fun payrollJson(p:Payroll)=JSONObject().put("id",p.id).put("profileId",p.profileId).put("period",p.period)
        .put("gross",p.grossCents).put("absence",p.absenceCents).put("loan",p.loanCents).put("consumption",p.consumptionCents)
        .put("net",p.netCents).put("status",p.status).put("createdAt",p.createdAt).put("paidAt",p.paidAt).put("delivered",p.deliveredCents)
    fun markPaid(c:Context,payrollId:String,deliveredCents:Long):Boolean{
        if(deliveredCents<0)return false
        val a=array(c,PAYROLLS);var found=false
        for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;if(o.optString("id")==payrollId){
            if(o.optString("status")=="PAGADA")return false
            o.put("status","PAGADA").put("paidAt",SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date())).put("delivered",deliveredCents);found=true;break
        }}
        return found&&saveArray(c,PAYROLLS,a)
    }
    /** Importa órdenes cifradas recibidas desde la pantalla SOLICITA de SENE VENTAS. */
    fun importOrder(c:Context, report:SeneReport):Boolean {
        if (!report.body.startsWith("SENE-ORDER|1")) return false
        val fields = report.body.lines().drop(1).mapNotNull { line ->
            val i = line.indexOf("="); if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        val orderId = fields["ORDER_ID"].orEmpty()
        val profileId = fields["WORKER_ID"].orEmpty().ifBlank { report.workerId }
        val type = when (fields["ORDER_TYPE"]) { "CONSUMO" -> "CONSUMO"; "PRESTAMO" -> "PRESTAMO"; else -> return false }
        val cents = fields["AMOUNT_CENTS"]?.toLongOrNull()?.takeIf { it > 0 } ?: return false
        if (orderId.isBlank() || entries(c, profileId).any { it.id == orderId }) return true
        val name = fields["WORKER_NAME"].orEmpty().ifBlank { profileId }
        val old = profile(c, profileId)
        if (old == null) upsertProfile(c, Profile(profileId, name, "", 0L, "", "", 8))
        val entry = Entry(orderId, profileId, type, fields["DATE"].orEmpty(), fields["DESCRIPTION"].orEmpty(),
            cents, "")
        val a = array(c, ENTRIES)
        a.put(JSONObject().put("id", entry.id).put("profileId", entry.profileId).put("type", entry.type)
            .put("date", entry.date).put("description", entry.description).put("amount", entry.amountCents).put("payrollId", ""))
        return saveArray(c, ENTRIES, a)
    }

    fun isPayDay(p:Profile):Boolean{
        val today=SimpleDateFormat("EEEE",Locale("es","MX")).format(Date())
        return p.payDay.isNotBlank()&&today.equals(p.payDay,true)
    }
    fun money(cents:Long)="$" + String.format(Locale.US,"%,.2f",cents/100.0)
}
