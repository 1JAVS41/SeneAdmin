package com.sene.admin

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Presentación compacta de reportes: etiquetas visuales para ingresos, egresos y movimientos. */
object ReportPresentation {
    fun addCard(context: Context, parent: LinearLayout, report: SeneReport) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFFDCE6F3.toInt())
            }
            elevation = dp(2).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = report.date + " · " + report.time
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF173C65.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(chip(context, displayType(report.type), 0xFFDDF4E5.toInt(), 0xFF176344.toInt()))
        card.addView(header)

        val seen = linkedSetOf<String>()
        fun addTag(kind: String, value: String) {
            val clean = value.trim()
            if (clean.isBlank()) return
            val identity = (kind + "|" + clean).lowercase(Locale.ROOT)
            if (!seen.add(identity)) return
            val expense = kind == "EGRESO"
            val income = kind == "INGRESO"
            val bg = if (expense) 0xFFFFE8E5.toInt() else if (income) 0xFFDDF4E5.toInt() else 0xFFE8F1FB.toInt()
            val fg = if (expense) 0xFF9C2F25.toInt() else if (income) 0xFF176344.toInt() else 0xFF285A91.toInt()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, 0)
            }
            row.addView(chip(context, kind, bg, fg))
            row.addView(TextView(context).apply {
                text = clean
                textSize = 13f
                setTextColor(0xFF344B63.toInt())
                setPadding(dp(8), dp(5), 0, dp(5))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }

        fun classify(key: String): String {
            val k = key.uppercase(Locale.ROOT)
            return when {
                listOf("EGRESO", "PROVEEDOR", "GASTO", "COMPRA", "DEVOLUCIÓN", "DEVOLUCION",
                    "PRÉSTAMO ENTREGADO", "PRESTAMO ENTREGADO", "SALIDA DE CAJA", "PAGO A").any { k.contains(it) } -> "EGRESO"
                listOf("INGRESO", "VENTA", "ENTRADA DE CAJA").any { k.contains(it) } -> "INGRESO"
                else -> "DATO"
            }
        }

        var movementKind = ""
        var movementParts = linkedMapOf<String, String>()
        fun flushMovement() {
            if (movementKind.isNotBlank() && movementParts.isNotEmpty()) {
                val detail = movementParts.entries.joinToString(" · ") { (key, value) -> "$key: $value" }
                addTag(if (classify(movementKind) == "DATO") "MOVIMIENTO" else classify(movementKind),
                    movementKind + if (detail.isBlank()) "" else " · $detail")
            }
            movementKind = ""
            movementParts = linkedMapOf()
        }

        val lines = report.body.replace("\r", "").lines()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("ETIQUETA:", true)) {
                flushMovement()
                val heading = line.substringAfter(":").trim()
                if (heading.contains("INGRES", true)) addTag("INGRESO", heading)
                else if (heading.contains("EGRES", true) || heading.contains("GAST", true)) addTag("EGRESO", heading)
                continue
            }
            if (line.startsWith("[") && line.contains("]")) {
                flushMovement()
                movementKind = line.substringAfter("[").substringBefore("]").trim()
                val tail = line.substringAfter("]", "").trim()
                if (tail.isNotBlank()) movementParts["Hora"] = tail
                continue
            }
            val key = line.substringBefore(":", "").trim()
            val value = if (line.contains(":")) line.substringAfter(":").trim() else ""
            if (key.isNotBlank() && value.isNotBlank()) {
                val normalized = key.uppercase(Locale.ROOT)
                when {
                    normalized in setOf("PRODUCTO", "PROVEEDOR", "CANTIDAD", "IMPORTE", "MONTO", "FECHA", "MOTIVO", "DESCRIPCIÓN", "DESCRIPCION", "HORA") && movementKind.isNotBlank() ->
                        movementParts[key.replaceFirstChar { it.uppercase() }] = value
                    normalized.contains("PAGO") && normalized.contains("PROVEEDOR") ->
                        addTag("EGRESO", "$key: $value")
                    classify(key) != "DATO" ->
                        addTag(classify(key), "$key: $value")
                    normalized in setOf("EFECTIVO", "TRANSFERENCIAS", "TRANSFERENCIA", "TARJETA", "CAJA INICIAL", "CAJA FINAL", "CAJA ACTUAL", "EFECTIVO ESTIMADO") ->
                        addTag("DATO", "$key: $value")
                }
                continue
            }
            if (movementKind.isNotBlank() && line.contains("|")) {
                val parts = line.split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.isNotEmpty()) movementParts["Detalle"] = parts.joinToString(" · ")
            } else if (line.contains("PROVEEDOR", true) || line.contains("EGRESO", true) || line.contains("GASTO", true)) {
                addTag("EGRESO", line)
            } else if (line.contains("INGRESO", true) || line.contains("VENTA NETA", true)) {
                addTag("INGRESO", line)
            }
        }
        flushMovement()

        if (seen.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Sin movimientos resumidos en este reporte."
                textSize = 12f
                setTextColor(0xFF718096.toInt())
                setPadding(0, dp(9), 0, 0)
            })
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun chip(context: Context, text: String, background: Int, foreground: Int): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        return TextView(context).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.035f
            setTextColor(foreground)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            this.background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = dp(20).toFloat()
            }
        }
    }

    private fun displayType(type: String): String = when (type.uppercase(Locale.ROOT)) {
        "EARLY_EXIT" -> "CIERRE ANTICIPADO"
        "FIN_TURNO", "CLOSE", "CLOSING" -> "CIERRE"
        "INICIO_TURNO", "START" -> "INICIO"
        else -> type.replace('_', ' ').take(22).uppercase(Locale.ROOT)
    }
}
