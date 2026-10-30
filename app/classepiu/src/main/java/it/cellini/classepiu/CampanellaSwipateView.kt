package it.cellini.classepiu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** MODIFICA: campanella blu contornata su cerchio bianco, indicatore delle notifiche messe da parte con swipe. */
class CampanellaSwipateView(context: Context) : View(context) {
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2479C8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val campana = Path()

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        elevation = context.resources.displayMetrics.density * 3f
        contentDescription = "Notifiche nascoste: tocca per ripristinarle"
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lato = minOf(width, height) * .72f
        val scala = lato / 48f
        canvas.save()
        canvas.translate((width - lato) / 2f, (height - lato) / 2f)
        canvas.scale(scala, scala)

        // Sagoma della campana e piccolo anello superiore.
        campana.reset()
        campana.moveTo(17f, 10f)
        campana.cubicTo(17f, 5f, 20f, 2.5f, 24f, 2.5f)
        campana.cubicTo(28f, 2.5f, 31f, 5f, 31f, 10f)
        campana.cubicTo(37f, 13f, 39f, 19f, 39f, 27f)
        campana.cubicTo(39f, 33f, 42f, 36f, 44f, 39f)
        campana.cubicTo(45f, 41f, 43f, 43f, 40f, 43f)
        campana.lineTo(8f, 43f)
        campana.cubicTo(5f, 43f, 3f, 41f, 4f, 39f)
        campana.cubicTo(7f, 36f, 9f, 33f, 9f, 27f)
        campana.cubicTo(9f, 19f, 11f, 13f, 17f, 10f)
        canvas.drawPath(campana, pennello)

        // Attacco superiore e batacchio.
        canvas.drawArc(17f, 0f, 31f, 14f, 205f, 130f, false, pennello)
        canvas.drawArc(17f, 35f, 31f, 49f, 0f, 180f, false, pennello)

        // Onde sonore laterali.
        canvas.drawArc(-1f, 11f, 13f, 38f, 105f, 150f, false, pennello)
        canvas.drawArc(35f, 11f, 49f, 38f, -75f, 150f, false, pennello)
        canvas.drawArc(4f, 17f, 17f, 34f, 105f, 150f, false, pennello)
        canvas.drawArc(31f, 17f, 44f, 34f, -75f, 150f, false, pennello)

        canvas.restore()
    }
}
