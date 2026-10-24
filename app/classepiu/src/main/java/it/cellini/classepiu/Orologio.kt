package it.cellini.classepiu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar

/**
 * Orologio analogico disegnato a mano, senza numeri né tacche: ghiera e lancette (ore corta, minuti lunga).
 * Tutte le misure sono proporzionali al raggio, quindi vale per qualsiasi dimensione.
 * [sulAzzurro]: ghiera e lancette bianche su fondo trasparente (per la fascia azzurra); altrimenti colori classePiù su grigio pallido.
 * Si aggiorna allo scoccare di ogni minuto; [alTick] avvisa ad ogni aggiornamento (serve alla data).
 */
class Orologio(ctx: Context, sulAzzurro: Boolean = false) : View(ctx) {
    var alTick: () -> Unit = {}
    private val azzurro = androidx.core.content.ContextCompat.getColor(ctx, R.color.logo_sfondo)
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sulAzzurro) Color.TRANSPARENT else 0xFFF5F5F5.toInt(); style = Paint.Style.FILL }
    private val ghiera = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sulAzzurro) Color.WHITE else azzurro; style = Paint.Style.STROKE }
    private val oreP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sulAzzurro) Color.WHITE else 0xFF444444.toInt(); strokeCap = Paint.Cap.ROUND }
    private val minutiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sulAzzurro) Color.WHITE else azzurro; strokeCap = Paint.Cap.ROUND }
    private val aggiorna = object : Runnable {
        override fun run() {
            invalidate()
            alTick()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50) // prossimo scatto: il minuto successivo
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); aggiorna.run() }
    override fun onDetachedFromWindow() { removeCallbacks(aggiorna); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val m0 = minOf(cx, cy)             // mezza larghezza: tutte le proporzioni partono da qui
        ghiera.strokeWidth = m0 * 0.075f
        oreP.strokeWidth = m0 * 0.093f
        minutiP.strokeWidth = m0 * 0.065f
        val r = m0 - ghiera.strokeWidth / 2
        c.drawCircle(cx, cy, r, fondo)
        c.drawCircle(cx, cy, r, ghiera)
        val t = Calendar.getInstance()
        val m = t.get(Calendar.MINUTE)
        val angOre = Math.toRadians(((t.get(Calendar.HOUR) % 12) * 30 + m * 0.5).toDouble())
        val angMin = Math.toRadians((m * 6).toDouble())
        c.drawLine(cx, cy, cx + (Math.sin(angOre) * m0 * 0.5).toFloat(), cy - (Math.cos(angOre) * m0 * 0.5).toFloat(), oreP)
        c.drawLine(cx, cy, cx + (Math.sin(angMin) * m0 * 0.815).toFloat(), cy - (Math.cos(angMin) * m0 * 0.815).toFloat(), minutiP)
    }
}
