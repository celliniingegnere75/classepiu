package it.cellini.classepiu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.View

/** Campanella bianca inclinata, senza sfondo, per le notifiche archiviate con swipe. */
class CampanellaSwipateView(context: Context) : View(context) {
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val campana = Path()

    init {
        // Nessun cerchio o riempimento: resta visibile solo il tratto bianco.
        background = null
        contentDescription = "Notifiche nascoste: tocca per ripristinarle"
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lato = minOf(width, height) * .82f
        val scala = lato / 48f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        // La leggera inclinazione riprende quella dell'icona fornita.
        canvas.rotate(13f)
        canvas.scale(scala, scala)
        canvas.translate(-24f, -24f)

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

        // Anello superiore e batacchio.
        canvas.drawArc(17f, 0f, 31f, 14f, 205f, 130f, false, pennello)
        canvas.drawArc(17f, 35f, 31f, 49f, 0f, 180f, false, pennello)

        // Due onde sonore per lato, aperte verso l'esterno.
        canvas.drawArc(-1f, 9f, 13f, 39f, 105f, 150f, false, pennello)
        canvas.drawArc(35f, 9f, 49f, 39f, -75f, 150f, false, pennello)
        canvas.drawArc(3f, 16f, 16f, 34f, 105f, 150f, false, pennello)
        canvas.drawArc(32f, 16f, 45f, 34f, -75f, 150f, false, pennello)

        canvas.restore()
    }
}
