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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Calendar

/** Finestra temporanea classePiù mostrata solo al rientro in primo piano. */
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
        val azzurro = 0xFF69BCE8.toInt()
        val scuro = 0xFF333333.toInt()
        val outfitRegular = runCatching { Typeface.createFromAsset(activity.assets, "outfit_regular.ttf") }.getOrDefault(Typeface.DEFAULT)
        val outfitBold = runCatching { Typeface.createFromAsset(activity.assets, "outfit_bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
        // scheda bianca arrotondata; la fascia azzurra in alto col logo bianco è quella della schermata principale
        val radice = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(24).toFloat()
            }
            clipToOutline = true
        }
        val intestazione = FrameLayout(activity).apply {
            setBackgroundColor(azzurro)
            setPadding(0, activity.dp(12), 0, activity.dp(12))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.logo_bianco)
                adjustViewBounds = true
            }, FrameLayout.LayoutParams(activity.dp(120), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // X stilizzata in un cerchio semitrasparente, sempre in alto a destra
            addView(TextView(activity).apply {
                text = "×"
                textSize = 30f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                contentDescription = "Chiudi finestra"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x35FFFFFF)
                    setStroke(activity.dp(1), 0x70FFFFFF)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    handler.removeCallbacksAndMessages(null)
                    val daChiudere = dialog
                    dialog = null
                    daChiudere?.dismiss()
                }
            }, FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.END).apply {
                rightMargin = activity.dp(10)
                topMargin = activity.dp(4)
            })
        }
        radice.addView(intestazione, LinearLayout.LayoutParams(-1, -2))
        // ghiera: metà del vecchio orologio (174dp -> 87dp)
        radice.addView(GhieraLezione(activity, fascia, outfitBold), LinearLayout.LayoutParams(activity.dp(87), activity.dp(87)).apply {
            topMargin = activity.dp(18)
        })
        // stessa riga: classe e aula
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                text = lezione.classe
                textSize = 22f
                typeface = outfitBold
                setTextColor(scuro)
            })
            if (lezione.aula.isNotBlank()) addView(TextView(activity).apply {
                text = "aula ${lezione.aula}"
                textSize = 18f
                typeface = outfitRegular
                setTextColor(azzurro)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = activity.dp(12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        // riga sotto: materia
        radice.addView(TextView(activity).apply {
            text = lezione.materia
            textSize = 15f
            typeface = outfitRegular
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            setPadding(activity.dp(20), 0, activity.dp(20), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(2); bottomMargin = activity.dp(20) })

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
        }, 7780) // 8 secondi in tutto, compresa la dissolvenza
    }

    /** Ghiera che si riempie dall'inizio alla fine dell'ora (fascia dalla tabella "lezioni"); al centro i minuti che mancano. */
    private class GhieraLezione(ctx: android.content.Context, private val fascia: Fascia, carattere: Typeface) : View(ctx) {
        private val spessore = ctx.dp(7).toFloat()
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; color = 0xFFE3EEF5.toInt() }
        private val pieno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; strokeCap = Paint.Cap.ROUND; color = 0xFF69BCE8.toInt() }
        private val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textAlign = Paint.Align.CENTER; typeface = carattere; textSize = ctx.dp(16).toFloat() }
        private val arco = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val adesso = Calendar.getInstance()
            val secondiAdesso = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
            val durata = ((fascia.fine - fascia.inizio) * 60).coerceAtLeast(1)
            val passati = (secondiAdesso - fascia.inizio * 60).coerceIn(0, durata)
            val residui = durata - passati
            val m = spessore / 2
            arco.set(m, m, width - m, height - m)
            canvas.drawArc(arco, 0f, 360f, false, fondo)
            canvas.drawArc(arco, -90f, 360f * passati / durata, false, pieno)
            val y = height / 2f - (testo.descent() + testo.ascent()) / 2
            canvas.drawText(String.format(java.util.Locale.ITALY, "%d:%02d", residui / 60, residui % 60), width / 2f, y, testo)
            postInvalidateDelayed(1000)
        }
    }
}
