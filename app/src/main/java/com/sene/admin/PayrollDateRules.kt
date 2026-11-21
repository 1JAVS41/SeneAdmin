package com.sene.admin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reglas de calendario compartidas con SENE VENTAS.
 * El periodo se ancla al último día de pago (no al próximo).
 */
internal object PayrollDateRules {
    private fun parse(value:String):Date? = try {
        SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(value)
    } catch(_:Exception) { null }

    private fun format(value:Date):String = SimpleDateFormat("yyyy-MM-dd",Locale.US).format(value)

    fun periodEndForDate(value:String,payDay:Int):String {
        val date=parse(value) ?: return ""
        if(payDay !in Calendar.SUNDAY..Calendar.SATURDAY)return ""
        val calendar=Calendar.getInstance().apply{time=date}
        val delta=(calendar.get(Calendar.DAY_OF_WEEK)-payDay+7)%7
        calendar.add(Calendar.DAY_OF_YEAR,-delta)
        return format(calendar.time)
    }

    fun mondayStartDate(value:String):String {
        val date=parse(value) ?: return ""
        val calendar=Calendar.getInstance().apply{time=date}
        val delta=(calendar.get(Calendar.DAY_OF_WEEK)-Calendar.MONDAY+7)%7
        calendar.add(Calendar.DAY_OF_YEAR,-delta)
        return format(calendar.time)
    }

    fun attendanceStatus(
        stored:String,
        date:String,
        today:String,
        isScheduled:Boolean,
        effectiveStart:String
    ):String = when(stored) {
        "EARLY_EXIT" -> "EARLY_EXIT"
        "WORKED" -> "WORKED"
        "ADMIN_CLOSED" -> "ADMIN_CLOSED"
        "WORKING" -> "WORKING"
        else -> when {
            !isScheduled -> "REST_DAY"
            date>today -> "FUTURE"
            effectiveStart.isNotBlank() && date<effectiveStart -> "NO_RECORD"
            else -> "NOT_WORKED"
        }
    }
}
