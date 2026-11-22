package com.sene.admin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Registro local persistente: conserva reportes recibidos y evita importar dos veces el mismo ID. */
object SeneAdminStore {
    private const val PREFS = "sene_admin_records_v1"
    private const val REPORTS = "received_reports"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun reports(c: Context): List<SeneReport> {
        val raw = prefs(c).getString(REPORTS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SeneReport(o.getString("id"), o.getString("worker"), o.getString("date"),
                    o.getString("time"), o.getLong("number"), o.getString("type"), o.getString("body"))
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun saveReport(c: Context, report: SeneReport): Boolean =
        saveReports(c, listOf(report))

    /** Guarda un lote completo con una sola lectura y una sola escritura persistente. */
    @Synchronized fun saveReports(c: Context, incoming: List<SeneReport>): Boolean {
        if(incoming.isEmpty())return true
        val current=reports(c)
        val known=current.mapTo(HashSet()){it.id}
        val array=JSONArray()
        current.forEach { item ->
            array.put(JSONObject().put("id",item.id).put("worker",item.workerId)
                .put("date",item.date).put("time",item.time).put("number",item.number)
                .put("type",item.type).put("body",item.body))
        }
        incoming.forEach { item ->
            if(known.add(item.id)) array.put(JSONObject().put("id",item.id).put("worker",item.workerId)
                .put("date",item.date).put("time",item.time).put("number",item.number)
                .put("type",item.type).put("body",item.body))
        }
        return prefs(c).edit().putString(REPORTS,array.toString()).commit()
    }
}
