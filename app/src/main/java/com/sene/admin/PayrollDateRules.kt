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


    fun proratedGross(weeklySalaryCents:Long,eligibleDays:Int,configuredDays:Int):Long {
        if(weeklySalaryCents<=0L||eligibleDays<=0||configuredDays<=0)return 0L
        return (weeklySalaryCents*eligibleDays/configuredDays).coerceAtMost(weeklySalaryCents)
    }

    fun absenceDeduction(weeklySalaryCents:Long,daysPerWeek:Int,absences:Int):Long {
        if(weeklySalaryCents<=0L||daysPerWeek<=0||absences<=0)return 0L
        return (weeklySalaryCents/daysPerWeek*absences).coerceAtMost(weeklySalaryCents)
    }

    fun earlyExitDeduction(weeklySalaryCents:Long,daysPerWeek:Int,hoursPerDay:Int,missingMillis:Long):Long {
        if(weeklySalaryCents<=0L||daysPerWeek<=0||hoursPerDay<=0||missingMillis<60_000L)return 0L
        return (weeklySalaryCents.toDouble()/daysPerWeek.toDouble()/hoursPerDay.toDouble()*
            (missingMillis/3_600_000.0)).toLong().coerceAtLeast(0L).coerceAtMost(weeklySalaryCents)
    }

    fun netPay(gross:Long,absence:Long,earlyExit:Long,loans:Long,consumption:Long):Long {
        val allowedAbsence=absence.coerceAtMost(gross.coerceAtLeast(0L))
        val allowedEarly=earlyExit.coerceAtMost((gross-allowedAbsence).coerceAtLeast(0L))
        return (gross-allowedAbsence-allowedEarly-loans.coerceAtLeast(0L)-consumption.coerceAtLeast(0L)).coerceAtLeast(0L)
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
