package it.cellini.classepiu

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Finestra temporanea ClassePiù mostrata solo al rientro in primo piano. */
object FinestraRientro {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null

    fun mostra(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ora = Calendar.getInstance()
        val lezione = runCatching { Orario(activity).lezioneAlle(ora) }.getOrNull() ?: return
        val fascia = lezione.fascia
        val minutiAdesso = ora.get(Calendar.HOUR_OF_DAY) * 60 + ora.get(Calendar.MINUTE)
        val secondiAdesso = ora.get(Calendar.SECOND)
        val residui = ((fascia.fine - minutiAdesso) * 60 - secondiAdesso).coerceAtLeast(0)
        if (residui <= 0) return

        dialog?.dismiss()
        val azzurro = Color.rgb(32, 157, 218)
        val radice = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(24), activity.dp(22), activity.dp(24), activity.dp(22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(28).toFloat()
                setStroke(activity.dp(2), azzurro)
            }
        }
        radice.addView(TextView(activity).apply {
            text = "CLASSEPIÙ"
            textSize = 13f
            setTextColor(azzurro)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = .12f
        })
        radice.addView(OrologioLezione(activity, fascia.fine, residui), LinearLayout.LayoutParams(activity.dp(174), activity.dp(174)).apply {
            topMargin = activity.dp(8)
        })
        radice.addView(TextView(activity).apply {
            text = lezione.classe
            textSize = 25f
            setTextColor(Color.rgb(30, 43, 55))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
        radice.addView(TextView(activity).apply {
            text = lezione.materia
            textSize = 17f
            setTextColor(Color.rgb(65, 77, 88))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(4) })
        if (lezione.aula.isNotBlank()) radice.addView(TextView(activity).apply {
            text = "Aula ${lezione.aula}"
            textSize = 15f
            setTextColor(azzurro)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
        radice.addView(TextView(activity).apply {
            text = "Fine lezione: ${Fascia.hhmm(fascia.fine)}"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(radice)
        d.setCancelable(false)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.28f)
            setLayout((activity.resources.displayMetrics.widthPixels * .88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog = d
        d.show()
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * .88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        radice.alpha = 0f
        radice.scaleX = .92f
        radice.scaleY = .92f
        radice.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
        handler.postDelayed({
            if (dialog === d) {
                radice.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(220).withEndAction {
                    runCatching { d.dismiss() }
                    if (dialog === d) dialog = null
                }.start()
            }
        }, 4780)
    }

    private class OrologioLezione(ctx: android.content.Context, private val fine: Int, private val residui: Int) : View(ctx) {
        private val blu = Color.rgb(32, 157, 218)
        private val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 43, 55); textAlign = Paint.Align.CENTER }
        private val lancetta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blu; strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val quadrante = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); color = blu }
        private val tacche = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 185, 200); strokeWidth = ctx.dp(2).toFloat() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - context.dp(8)
            canvas.drawCircle(cx, cy, r, quadrante)
            for (i in 0 until 60) {
                val a = Math.PI * i / 30.0
                val esterno = r - context.dp(5)
                val interno = r - context.dp(if (i % 5 == 0) 13 else 8)
                canvas.drawLine(cx + cos(a).toFloat() * interno, cy + sin(a).toFloat() * interno,
                    cx + cos(a).toFloat() * esterno, cy + sin(a).toFloat() * esterno, tacche)
            }
            val adesso = Calendar.getInstance()
            val secondi = adesso.get(Calendar.SECOND)
            val minuti = adesso.get(Calendar.MINUTE)
            val ore = adesso.get(Calendar.HOUR)
            disegnaLancetta(canvas, cx, cy, r * .48f, (ore % 12 + minuti / 60f) / 12f * 360f - 90f, lancetta)
            disegnaLancetta(canvas, cx, cy, r * .72f, (minuti + secondi / 60f) / 60f * 360f - 90f, lancetta)
            val tot = ((fine - (adesso.get(Calendar.HOUR_OF_DAY) * 60 + minuti)) * 60 - secondi).coerceAtLeast(0)
            val mm = tot / 60
            val ss = tot % 60
            testo.textSize = context.dp(17).toFloat()
            testo.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(String.format(java.util.Locale.ITALY, "%02d:%02d", mm, ss), cx, cy + r * .36f, testo)
            postInvalidateDelayed(1000)
        }

        private fun disegnaLancetta(c: Canvas, x: Float, y: Float, lunghezza: Float, gradi: Float, p: Paint) {
            val a = Math.toRadians(gradi.toDouble())
            c.drawLine(x, y, x + cos(a).toFloat() * lunghezza, y + sin(a).toFloat() * lunghezza, p)
        }
    }
}
