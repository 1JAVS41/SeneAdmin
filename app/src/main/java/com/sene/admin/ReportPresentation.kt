package com.sene.admin

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Resumen visual: únicamente cifras operativas importantes del cierre. */
object ReportPresentation {
    private data class Item(val label: String, val value: String, val kind: String)

    fun addCard(context: Context, parent: LinearLayout, report: SeneReport) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(14))
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
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF173C65.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(chip(context, displayType(report.type), 0xFFE8F1FB.toInt(), 0xFF285A91.toInt()))
        card.addView(header)

        val items = linkedMapOf<String, Item>()
        var section = ""
        fun normalize(raw: String) = raw.uppercase(Locale.ROOT)
            .replace("Á", "A").replace("É", "E").replace("Í", "I")
            .replace("Ó", "O").replace("Ú", "U").trim()
        fun put(label: String, value: String, kind: String) {
            val clean = value.trim()
            if (clean.isBlank() || clean == "-" || clean.equals("N/A", true)) return
            items[label] = Item(label, clean, kind)
        }
        fun classify(label: String): String {
            val key = normalize(label)
            return when {
                listOf("GASTO", "EGRESO", "PROVEEDOR", "COMPRA", "DEVOLUCION", "PRESTAMO ENTREGADO", "SALIDA DE CAJA").any { key.contains(it) } -> "EGRESO"
                listOf("VENTA", "INGRESO", "COBRO").any { key.contains(it) } -> "INGRESO"
                else -> "DATO"
            }
        }
        fun friendly(key: String): String? {
            val k = normalize(key).replace(Regex("\\s+"), " ")
            return when {
                k in setOf("CAJA INICIAL", "EFECTIVO INICIAL", "MONTO INICIAL") -> "Caja inicial"
                k in setOf("VENTAS DEL DIA", "VENTA DEL DIA", "TOTAL DE VENTAS", "VENTAS TOTALES", "VENTA NETA DEL DIA", "VENTAS NETAS") -> "Ventas del día"
                k.contains("GASTO") && (k.contains("PROVEEDOR") || k.contains("PROVEEDORES")) -> "Gastos proveedores"
                k.contains("PAGO") && k.contains("PROVEEDOR") -> "Gastos proveedores"
                k in setOf("EGRESOS", "TOTAL EGRESOS", "TOTAL DE EGRESOS") -> "Total de egresos"
                k in setOf("INGRESOS", "TOTAL INGRESOS", "TOTAL DE INGRESOS") -> "Total de ingresos"
                k in setOf("CAJA ACTUAL", "EFECTIVO ACTUAL", "CAJA FINAL", "EFECTIVO FINAL", "EFECTIVO EN CAJA") -> "Caja actual"
                k in setOf("VENTAS EN EFECTIVO", "EFECTIVO DE VENTAS") -> "Ventas en efectivo"
                k in setOf("TRANSFERENCIAS", "VENTAS POR TRANSFERENCIA") -> "Transferencias"
                k in setOf("TARJETA", "VENTAS CON TARJETA") -> "Ventas con tarjeta"
                k in setOf("TOTAL", "TOTAL GASTOS", "TOTAL DE GASTOS") && section.contains("EGRES") -> "Total de egresos"
                k in setOf("TOTAL", "TOTAL VENTAS") && section.contains("INGRES") -> "Total de ingresos"
                else -> null
            }
        }

        for (raw in report.body.replace("\r", "").lines()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("ETIQUETA:", true)) {
                section = normalize(line.substringAfter(":"))
                continue
            }
            if (line.startsWith("[") && line.contains("]")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (value.isBlank()) continue
            val label = friendly(key)
            if (label != null) {
                val kind = when (label) {
                    "Ventas del día", "Ventas en efectivo", "Ventas con tarjeta", "Transferencias", "Total de ingresos" -> "INGRESO"
                    "Gastos proveedores", "Total de egresos" -> "EGRESO"
                    else -> "DATO"
                }
                put(label, value, kind)
            } else if (section.contains("EGRES") && normalize(key) in setOf("IMPORTE", "MONTO", "TOTAL")) {
                put(if (section.contains("PROVEEDOR")) "Gastos proveedores" else "Total de egresos", value, "EGRESO")
            } else if (section.contains("INGRES") && normalize(key) in setOf("IMPORTE", "MONTO", "TOTAL")) {
                put("Total de ingresos", value, "INGRESO")
            }
        }

        // Evita que un total de egresos duplique el importe específico de proveedores.
        if (items.containsKey("Gastos proveedores") && items["Total de egresos"]?.value == items["Gastos proveedores"]?.value) {
            items.remove("Total de egresos")
        }
        val metricItems = items.values.toList()
        metricItems.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            pair.forEach { item ->
                val (bg, fg) = when (item.kind) {
                    "EGRESO" -> 0xFFFFE8E5.toInt() to 0xFF9C2F25.toInt()
                    "INGRESO" -> 0xFFDDF4E5.toInt() to 0xFF176344.toInt()
                    else -> 0xFFE8F1FB.toInt() to 0xFF285A91.toInt()
                }
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    background = GradientDrawable().apply {
                        setColor(bg)
                        cornerRadius = dp(14).toFloat()
                    }
                }
                tile.addView(TextView(context).apply {
                    text = item.label.uppercase(Locale.ROOT)
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(fg)
                })
                tile.addView(TextView(context).apply {
                    text = item.value
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(fg)
                    setPadding(0, dp(4), 0, 0)
                })
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (pair.first() !== item) leftMargin = dp(7)
                    if (pair.size == 1) rightMargin = dp(7)
                })
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (items.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Este reporte no contiene cifras de caja resumidas."
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
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.035f
            setTextColor(foreground)
            setPadding(dp(8), dp(6), dp(8), dp(6))
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
