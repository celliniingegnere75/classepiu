package it.cellini.classepiu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Calendar

/** Scheda home: per ora solo l'orologio analogico. */
class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        return FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            addView(Orologio(ctx), FrameLayout.LayoutParams(ctx.dp(108), ctx.dp(108), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = ctx.dp(40) })
        }
    }
}

/** Orologio analogico disegnato a mano: ghiera azzurra, lancetta delle ore grigio scuro e dei minuti azzurra; niente numeri né tacche. */
class Orologio(ctx: Context) : View(ctx) {
    private val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F5.toInt(); style = Paint.Style.FILL }
    private val ghiera = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = azzurro; style = Paint.Style.STROKE; strokeWidth = ctx.dp(4).toFloat() }
    private val oreP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); strokeWidth = ctx.dp(5).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val minutiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = azzurro; strokeWidth = 3.5f * ctx.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND }
    private val dp = ctx.resources.displayMetrics.density
    private val aggiorna = object : Runnable {
        override fun run() {
            invalidate()
            // prossimo aggiornamento allo scoccare del minuto
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); aggiorna.run() }
    override fun onDetachedFromWindow() { removeCallbacks(aggiorna); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - ghiera.strokeWidth / 2
        c.drawCircle(cx, cy, r, fondo)
        c.drawCircle(cx, cy, r, ghiera)
        val t = Calendar.getInstance()
        val m = t.get(Calendar.MINUTE)
        val angOre = Math.toRadians(((t.get(Calendar.HOUR) % 12) * 30 + m * 0.5).toDouble())
        val angMin = Math.toRadians((m * 6).toDouble())
        // lancette ferme alla misura precedente: 27 dp le ore, 44 dp i minuti
        c.drawLine(cx, cy, cx + (Math.sin(angOre) * 27 * dp).toFloat(), cy - (Math.cos(angOre) * 27 * dp).toFloat(), oreP)
        c.drawLine(cx, cy, cx + (Math.sin(angMin) * 44 * dp).toFloat(), cy - (Math.cos(angMin) * 44 * dp).toFloat(), minutiP)
    }
}
