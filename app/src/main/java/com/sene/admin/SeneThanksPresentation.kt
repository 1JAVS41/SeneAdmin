package com.sene.admin

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import kotlin.math.sin

private object AdminSeneRobotPainter {
    fun draw(canvas: Canvas, width: Float, height: Float, phase: Float = 0f, mood: Int = 0) {
        val save = canvas.save()
        canvas.scale(width / 200f, height / 240f)
        val bob = sin(phase * Math.PI * 2.0).toFloat()
        val breathing = sin(phase * Math.PI * 4.0).toFloat()
        canvas.translate(0f, bob * 2.8f)
        val moodTilt = when (mood) { 0 -> -2.2f; 1 -> 3.5f; 2 -> -1.0f; 3 -> 2.8f; 4 -> -3.8f; 5 -> 2.0f; else -> 0f }
        canvas.rotate(sin(phase * Math.PI * 2.0).toFloat() * 1.65f + sin(phase * Math.PI * 4.0).toFloat() * .35f + moodTilt, 100f, 120f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun solid(c: Int) { p.shader = null; p.clearShadowLayer(); p.style = Paint.Style.FILL; p.color = c }
        fun grad(r: RectF, colors: IntArray, horizontal: Boolean = false) {
            p.style = Paint.Style.FILL
            p.shader = if (horizontal) LinearGradient(r.left, r.top, r.right, r.top, colors, null, Shader.TileMode.CLAMP)
            else LinearGradient(r.left, r.top, r.left, r.bottom, colors, null, Shader.TileMode.CLAMP)
            p.clearShadowLayer()
        }
        fun rr(r: RectF, radius: Float) = canvas.drawRoundRect(r, radius, radius, p)

        // Sombra y hombros: cuerpo compacto, con piezas independientes.
        solid(0x55030A1C); canvas.drawOval(25f, 211f, 178f, 237f, p)
        p.setShadowLayer(10f, 0f, 6f, 0x99000000.toInt())
        grad(RectF(37f, 150f, 163f, 232f), intArrayOf(0xFFB9D9F4.toInt(), 0xFF527EB2.toInt(), 0xFF172F58.toInt()))
        canvas.drawRoundRect(37f, 150f, 163f, 232f, 36f, 36f, p); p.clearShadowLayer()
        canvas.save(); canvas.rotate(sin(phase * Math.PI * 2.0).toFloat() * 7f, 41f, 165f)
        grad(RectF(20f, 162f, 62f, 206f), intArrayOf(0xFFE9F7FF.toInt(), 0xFF6D9BC9.toInt(), 0xFF203E6C.toInt()))
        canvas.drawRoundRect(20f, 162f, 62f, 206f, 17f, 17f, p); canvas.restore()
        canvas.save(); canvas.rotate(-sin(phase * Math.PI * 2.0).toFloat() * 7f, 159f, 165f)
        grad(RectF(138f, 162f, 180f, 206f), intArrayOf(0xFFE9F7FF.toInt(), 0xFF6D9BC9.toInt(), 0xFF203E6C.toInt()))
        canvas.drawRoundRect(138f, 162f, 180f, 206f, 17f, 17f, p); canvas.restore()

        // Cabeza y laterales de aleación.
        p.setShadowLayer(12f, 0f, 8f, 0xCC000000.toInt())
        grad(RectF(25f, 35f, 175f, 176f), intArrayOf(0xFFF4FBFF.toInt(), 0xFF9EBEDC.toInt(), 0xFF456B9D.toInt(), 0xFF152D54.toInt()))
        canvas.drawRoundRect(25f, 35f, 175f, 176f, 47f, 47f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.shader = LinearGradient(29f, 36f, 169f, 174f, intArrayOf(Color.WHITE, 0xFF7EA8D6.toInt(), 0xFF10264A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(27f, 37f, 173f, 174f, 44f, 44f, p)
        p.shader = null; p.style = Paint.Style.FILL

        // Orejas mecánicas y tornillos.
        grad(RectF(9f, 84f, 35f, 133f), intArrayOf(0xFFFFD98B.toInt(), 0xFFB96E28.toInt(), 0xFF6A3D1B.toInt()))
        canvas.drawRoundRect(9f, 84f, 35f, 133f, 11f, 11f, p)
        grad(RectF(165f, 84f, 191f, 133f), intArrayOf(0xFFFFD98B.toInt(), 0xFFB96E28.toInt(), 0xFF6A3D1B.toInt()))
        canvas.drawRoundRect(165f, 84f, 191f, 133f, 11f, 11f, p)
        solid(0xFFFFE6B0.toInt()); canvas.drawCircle(22f, 108f, 4f, p); canvas.drawCircle(178f, 108f, 4f, p)

        // Antena e indicador.
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 6f
        solid(0xFFB77B37.toInt()); canvas.drawLine(100f, 16f, 100f, 37f, p)
        p.setShadowLayer(8f, 0f, 0f, 0xFFFFB94E.toInt())
        val antennaPulse = 10.5f + (breathing + 1f) * 2.2f
        grad(RectF(100f-antennaPulse, 16f-antennaPulse, 100f+antennaPulse, 16f+antennaPulse), intArrayOf(0xFFFFF0B5.toInt(), 0xFFFFB33D.toInt(), 0xFF9B4E16.toInt()))
        canvas.drawCircle(100f, 16f, antennaPulse, p); p.clearShadowLayer()
        solid(Color.WHITE); canvas.drawCircle(96f, 11f, 3f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x99FFD77A.toInt()
        canvas.drawCircle(100f, 16f, antennaPulse + 4f + (breathing + 1f) * 1.5f, p); p.style = Paint.Style.FILL

        // Visor negro curvo con borde cromado.
        p.setShadowLayer(8f, 0f, 4f, 0xCC000000.toInt())
        grad(RectF(39f, 75f, 161f, 137f), intArrayOf(0xFF203B60.toInt(), 0xFF071629.toInt(), 0xFF020914.toInt()))
        canvas.drawRoundRect(39f, 75f, 161f, 137f, 24f, 24f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFB7D6F1.toInt()
        canvas.drawRoundRect(40f, 76f, 160f, 136f, 23f, 23f, p); p.style = Paint.Style.FILL

        // Reflejos curvos del cristal del visor y microdetalles ópticos.
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x667FEAFF.toInt()
        canvas.drawArc(47f, 79f, 153f, 112f, 198f, 142f, false, p)
        p.color = 0x66FFFFFF.toInt()
        canvas.drawLine(52f, 83f, 67f, 79f, p)
        canvas.drawLine(133f, 79f, 148f, 83f, p)
        p.style = Paint.Style.FILL
        // Ojos con brillo interno y reflejo de cristal.
        val blinking = phase in .18f.. .225f || phase in .70f.. .745f
        val eyeScale = if (blinking) .12f else 1f
        val eyeTop = 105f - 14f * eyeScale
        val eyeBottom = 105f + 14f * eyeScale
        val gaze = when (mood) { 1 -> -3.5f; 2 -> 0f; 3 -> 4.5f; 4 -> sin(phase * Math.PI * 2.0).toFloat() * 5f; else -> sin(phase * Math.PI * 2.0 * 1.5).toFloat() * 3.2f }
        for (x in listOf(70f, 130f)) {
            p.setShadowLayer(14f + (breathing + 1f) * 2f, 0f, 0f, 0xFF36D8FF.toInt())
            grad(RectF(x - 13f, eyeTop, x + 13f, eyeBottom), intArrayOf(0xFFE7FFFF.toInt(), 0xFF48DFFF.toInt(), 0xFF0874C8.toInt()))
            canvas.drawRoundRect(x - 13f, eyeTop, x + 13f, eyeBottom, 7f, 7f, p); p.clearShadowLayer()
            if (!blinking) {
                solid(0xFFFFFFFF.toInt()); canvas.drawCircle(x - 4f + gaze, 97f, 4f, p)
                solid(0xFF073D68.toInt()); canvas.drawCircle(x + gaze, 105f, 3.2f, p)
            }
        }
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 3f; p.color = 0xFF75E8FF.toInt()
        when (mood) {
            1, 5 -> canvas.drawArc(84f, 114f, 116f, 130f, 205f, 130f, false, p)
            3 -> canvas.drawArc(86f, 113f, 114f, 126f, 15f, 145f, false, p)
            4, 6 -> canvas.drawArc(82f, 108f, 118f, 132f, 5f, 170f, false, p)
            else -> canvas.drawArc(84f, 111f, 116f, 128f, 12f, 156f, false, p)
        }

        // Microplacas laterales, biseles y tornillos mecanizados de la carcasa.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f
        p.color = 0xFFDAEEFF.toInt()
        canvas.drawArc(30f, 47f, 72f, 91f, 190f, 105f, false, p)
        canvas.drawArc(128f, 47f, 170f, 91f, 245f, 105f, false, p)
        p.color = 0xFF29496F.toInt(); p.strokeWidth = 2.2f
        canvas.drawLine(31f, 139f, 43f, 151f, p)
        canvas.drawLine(169f, 139f, 157f, 151f, p)
        p.style = Paint.Style.FILL
        for ((x, y) in listOf(35f to 61f, 165f to 61f, 34f to 148f, 166f to 148f)) {
            p.setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
            grad(RectF(x - 3.5f, y - 3.5f, x + 3.5f, y + 3.5f), intArrayOf(0xFFFFFFFF.toInt(), 0xFF7898B8.toInt(), 0xFF233C5E.toInt()))
            canvas.drawCircle(x, y, 3.5f, p); p.clearShadowLayer()
            solid(0xFF304A68.toInt()); canvas.drawLine(x - 1.8f, y, x + 1.8f, y, p)
        }

        // Circuito IC en la frente.
        p.setShadowLayer(5f, 0f, 2f, 0xAAFFB33E.toInt())
        grad(RectF(85f, 43f, 115f, 68f), intArrayOf(0xFFFFF0B8.toInt(), 0xFFFFC65C.toInt(), 0xFF9C5A20.toInt()))
        canvas.drawRoundRect(85f, 43f, 115f, 68f, 5f, 5f, p); p.clearShadowLayer()
        solid(0xFF75471F.toInt())
        for (x in listOf(91f, 100f, 109f)) { canvas.drawLine(x, 37f, x, 43f, p); canvas.drawLine(x, 68f, x, 74f, p) }
        canvas.drawLine(91f, 50f, 109f, 50f, p); canvas.drawLine(91f, 56f, 109f, 56f, p); canvas.drawLine(91f, 62f, 109f, 62f, p)
        solid(0xFFFFF2C8.toInt()); canvas.drawCircle(100f, 56f, 2.5f, p)

        // Uniones mecánicas, ranuras de ventilación y tornillería del torso.
        solid(0xFF142B4B.toInt())
        for (x in listOf(77f, 87f, 113f, 123f)) {
            canvas.drawRoundRect(x, 157f, x + 4f, 169f, 2f, 2f, p)
        }
        solid(0xFFB8D8F2.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        canvas.drawArc(58f, 154f, 142f, 198f, 195f, 150f, false, p)
        p.style = Paint.Style.FILL
        // Cuello articulado con pistones y anillos de acero.
        grad(RectF(77f, 143f, 123f, 165f), intArrayOf(0xFFE8F5FF.toInt(), 0xFF52769C.toInt(), 0xFF172D4C.toInt()))
        canvas.drawRoundRect(77f, 143f, 123f, 165f, 8f, 8f, p)
        solid(0xFF172A45.toInt())
        canvas.drawRoundRect(84f, 147f, 89f, 162f, 2f, 2f, p)
        canvas.drawRoundRect(111f, 147f, 116f, 162f, 2f, 2f, p)
        solid(0xFFFFC56A.toInt()); canvas.drawCircle(86.5f, 145f, 2.5f, p); canvas.drawCircle(113.5f, 145f, 2.5f, p)

        // Placa del pecho con el nombre y remaches.
        p.setShadowLayer(7f, 0f, 4f, 0xAA000000.toInt())
        grad(RectF(48f, 177f, 152f, 218f), intArrayOf(0xFFE7F5FF.toInt(), 0xFF6D9BCF.toInt(), 0xFF1A3C70.toInt()))
        canvas.drawRoundRect(48f, 177f, 152f, 218f, 12f, 12f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFFE4F4FF.toInt()
        canvas.drawRoundRect(50f, 179f, 150f, 216f, 10f, 10f, p); p.style = Paint.Style.FILL
        solid(0xFFFFFFFF.toInt()); p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = 15f
        p.setShadowLayer(3f, 0f, 2f, 0xFF07152E.toInt()); canvas.drawText("SENE VENTAS", 100f, 203f, p); p.clearShadowLayer()
        solid(0xFFFFC45A.toInt()); canvas.drawCircle(57f, 188f, 2f, p); canvas.drawCircle(143f, 188f, 2f, p)

        // Refuerzos de carcasa, rejillas de ventilación y luces de estado para
        // dar profundidad de metal mecanizado al torso sin cambiar la silueta.
        grad(RectF(58f, 207f, 142f, 226f), intArrayOf(0xFF24476F.toInt(), 0xFF0C1F3D.toInt(), 0xFF426B94.toInt()))
        canvas.drawRoundRect(58f, 207f, 142f, 226f, 6f, 6f, p)
        solid(0xFF9EC4E7.toInt())
        for (x in listOf(70f, 79f, 88f, 112f, 121f, 130f)) {
            canvas.drawRoundRect(x, 212f, x + 4f, 221f, 2f, 2f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFFE8F7FF.toInt()
        canvas.drawLine(43f, 174f, 53f, 181f, p)
        canvas.drawLine(157f, 174f, 147f, 181f, p)
        canvas.drawArc(42f, 185f, 158f, 232f, 12f, 156f, false, p)
        p.style = Paint.Style.FILL
        // Luces de estado laterales con halo y pequeñas lentes.
        for ((index, pair) in listOf(39f to 194f, 161f to 194f).withIndex()) {
            val (x, y) = pair
            val ledOn = ((phase * 6f).toInt() + index) % 3 != 0
            p.setShadowLayer(if(ledOn) 7f else 2f, 0f, 0f, 0xFF43D9FF.toInt())
            solid(if(ledOn)0xFF56E7FF.toInt() else 0xFF285A79.toInt()); canvas.drawCircle(x, y, if(ledOn)3.8f else 2.8f, p)
            p.clearShadowLayer()
            if(ledOn){solid(Color.WHITE);canvas.drawCircle(x - 1f, y - 1f, 1.1f, p)}
        }
        // Remaches inferiores y bordes de las placas de los hombros.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
        p.color = 0xFFD9EEFF.toInt()
        canvas.drawArc(23f, 164f, 61f, 207f, 190f, 90f, false, p)
        canvas.drawArc(139f, 164f, 177f, 207f, 260f, 90f, false, p)
        p.style = Paint.Style.FILL
        solid(0xFF263F60.toInt())
        for (x in listOf(45f, 155f)) canvas.drawCircle(x, 224f, 2.4f, p)
        solid(0xFFD7E9F8.toInt())
        for (x in listOf(45f, 155f)) canvas.drawCircle(x - 0.7f, 223.3f, 0.8f, p)

        canvas.restoreToCount(save)
    }
}


private class AdminSeneRobotPortrait(context: Context) : View(context) {
    private var phase = 0f
    var mood: Int = 0
        set(value) { field = value.coerceIn(0, 6); invalidate() }
    private var animator: ValueAnimator? = null
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        AdminSeneRobotPainter.draw(canvas, width.toFloat(), height.toFloat(), phase, mood)
    }
}



private data class SeneThanksMessage(val title: String, val body: String)

fun Activity.showSeneThanksPresentation() {
    val messages = listOf(
        SeneThanksMessage("¡GRACIAS POR CONFIAR EN SENE!", "SENE VENTAS nació para hacer más sencillo el trabajo de cada negocio. Gracias por impulsar este proyecto."),
        SeneThanksMessage("DETRÁS DE CADA NEGOCIO HAY UN SUEÑO", "Sabemos cuánto esfuerzo cuesta levantar un negocio. Por eso trabajamos para ayudarte a llevarlo más lejos."),
        SeneThanksMessage("TU NEGOCIO MERECE ESTAR EN ORDEN", "Ventas, inventarios y reportes: herramientas para que tengas el control de lo que tanto trabajo te ha costado construir."),
        SeneThanksMessage("LA TECNOLOGÍA DEBE TRABAJAR PARA TI", "Creamos soluciones prácticas para que dediques menos tiempo a complicaciones y más tiempo a tu negocio."),
        SeneThanksMessage("CADA VENTA CUENTA", "Cada operación representa esfuerzo, confianza y una oportunidad para seguir creciendo."),
        SeneThanksMessage("HECHO CON ESFUERZO, PENSADO PARA TI", "Detrás de SENE VENTAS hay dedicación, ideas y el compromiso de seguir mejorando."),
        SeneThanksMessage("¡SIGAMOS CRECIENDO JUNTOS!", "SENE VENTAS · Tu negocio, tu esfuerzo, tu futuro. ¡Gracias por ser parte de esta historia!")
    )
    var index = 0
    fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(22), dp(18), dp(22), dp(18))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF07162D.toInt(), 0xFF102C50.toInt(), 0xFF07172E.toInt())
        ).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0xFF527AA8.toInt()) }
    }
    panel.addView(TextView(this).apply {
        text = "UN MENSAJE DE SENE VENTAS"
        textSize = 12f; letterSpacing = .16f
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        setTextColor(0xFFFFD27A.toInt()); gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(-1, dp(28)))
    val robot = AdminSeneRobotPortrait(this)
    panel.addView(robot, LinearLayout.LayoutParams(dp(218), dp(262)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(2)
    })
    val counter = TextView(this).apply {
        textSize = 11f; letterSpacing = .12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF8ECFFF.toInt()); gravity = Gravity.CENTER
    }
    panel.addView(counter, LinearLayout.LayoutParams(-1, dp(22)))
    val heading = TextView(this).apply {
        textSize = 20f; letterSpacing = .025f
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        setTextColor(0xFFFFD27A.toInt()); gravity = Gravity.CENTER
        setPadding(0, dp(5), 0, dp(8))
    }
    panel.addView(heading, LinearLayout.LayoutParams(-1, -2))
    val body = TextView(this).apply {
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        setTextColor(0xFFEAF3FF.toInt()); gravity = Gravity.CENTER
        setLineSpacing(dp(5).toFloat(), 1.04f)
        setPadding(dp(5), dp(2), dp(5), dp(12))
    }
    panel.addView(body, LinearLayout.LayoutParams(-1, -2))
    val next = Button(this).apply {
        isAllCaps = false
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        setTextColor(0xFF10213B.toInt())
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFFFE5A0.toInt(), 0xFFFFB43F.toInt())
        ).apply { cornerRadius = dp(16).toFloat() }
        elevation = dp(5).toFloat()
        setPadding(dp(18), dp(10), dp(18), dp(10))
    }
    panel.addView(next, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(5) })
    lateinit var dialog: AlertDialog
    fun render() {
        val item = messages[index]
        counter.text = "MENSAJE ${index + 1} DE ${messages.size}"
        heading.text = item.title
        body.text = item.body
        next.text = if (index == messages.lastIndex) "FINALIZAR AGRADECIMIENTO" else "SIGUIENTE  →"
        robot.mood = index\n        robot.contentDescription = "SENE IA animada: ${item.title}"
    }
    next.setOnClickListener {
        if (index < messages.lastIndex) {
            index++
            render()
        } else dialog.dismiss()
    }
    render()
    dialog = AlertDialog.Builder(this).setView(panel).create()
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)),
            -2
        )
    }
    dialog.show()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)),
        -2
    )
}
