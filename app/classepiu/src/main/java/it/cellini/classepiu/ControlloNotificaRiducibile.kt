package it.cellini.classepiu

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * Controller riutilizzabile per notifiche riducibili, con stato compatto persistente
 * e chiusura con swipe verso destra.
 */
class ControlloNotificaRiducibile(
    private val dialog: Dialog,
    private val contenutoCompleto: View,
    private val contenutoCompatto: View,
    private val frecciaRiduci: View,
    private val frecciaEspandi: View
) {
    private val prefs = dialog.context.getSharedPreferences("classepiu_notifica", 0)
    private var inizioX = 0f
    private var inizioY = 0f
    private var animazioneAltezza: ValueAnimator? = null

    init {
        stilePulsante(frecciaRiduci, true)
        stilePulsante(frecciaEspandi, false)
        // La X è il terzo elemento dell'intestazione: logo, freccia, X.
        (frecciaRiduci.parent as? ViewGroup)?.let { intestazione ->
            if (intestazione.childCount > 2) stilePulsante(intestazione.getChildAt(2), true, chiusura = true)
        }

        frecciaRiduci.setOnClickListener { mostraCompatta() }
        frecciaEspandi.setOnClickListener { mostraCompleta() }

        val swipe = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inizioX = event.rawX
                    inizioY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - inizioX
                    val dy = event.rawY - inizioY
                    if (dx > dialog.context.dp(72) && dx > kotlin.math.abs(dy) * 1.25f) {
                        prefs.edit().putBoolean("chiusa", true).apply()
                        v.animate()
                            .translationX(v.width.toFloat().coerceAtLeast(dialog.context.dp(120).toFloat()))
                            .alpha(0f)
                            .setDuration(240)
                            .withEndAction { dialog.dismiss() }
                            .start()
                        true
                    } else {
                        v.performClick()
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        // Lo swipe funziona trascinando la superficie della finestra o la striscia compatta.
        contenutoCompleto.setOnTouchListener(swipe)
        contenutoCompatto.setOnTouchListener(swipe)

        // Stato iniziale senza animazione: la finestra non deve animarsi all'apertura.
        if (prefs.getBoolean("ridotta", false)) impostaCompatta() else impostaCompleta()
    }

    private fun stilePulsante(view: View, suAzzurro: Boolean, chiusura: Boolean = false) {
        val ctx = dialog.context
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (suAzzurro) intArrayOf(0xFF8BD0F2.toInt(), 0xFF3298D0.toInt())
            else intArrayOf(0xFFEAF7FD.toInt(), 0xFFB9E2F5.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(ctx.dp(2).coerceAtLeast(1),
                if (suAzzurro) 0xBFFFFFFF.toInt() else 0xFF69BCE8.toInt())
        }
        view.elevation = ctx.dp(3).toFloat()
        view.minimumWidth = ctx.dp(38)
        view.minimumHeight = ctx.dp(38)
        view.contentDescription = when {
            chiusura -> "Chiudi finestra"
            suAzzurro -> "Riduci notifica"
            else -> "Espandi notifica"
        }
        if (view is android.widget.TextView) {
            view.setTextColor(if (suAzzurro) Color.WHITE else 0xFF17658A.toInt())
            view.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            view.textSize = if (chiusura) 28f else 27f
        }
    }

    private fun mostraCompatta() {
        prefs.edit().putBoolean("ridotta", true).putBoolean("chiusa", false).apply()
        animaCambioStato(compatta = true)
        dialog.window?.setDimAmount(.08f)
    }

    private fun mostraCompleta() {
        prefs.edit().putBoolean("ridotta", false).putBoolean("chiusa", false).apply()
        animaCambioStato(compatta = false)
        dialog.window?.setDimAmount(.28f)
    }

    private fun impostaCompatta() {
        contenutoCompleto.visibility = View.GONE
        contenutoCompatto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 92 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun impostaCompleta() {
        contenutoCompatto.visibility = View.GONE
        contenutoCompleto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 88 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Anima l'altezza della finestra, non i contenuti: testi, icone e pulsanti
     * conservano le loro dimensioni. La prima visualizzazione resta istantanea.
     */
    private fun animaCambioStato(compatta: Boolean) {
        val window = dialog.window ?: return
        val decor = window.decorView
        val larghezza = dialog.context.resources.displayMetrics.widthPixels *
            (if (compatta) 92 else 88) / 100
        val altezzaSchermo = dialog.context.resources.displayMetrics.heightPixels

        animazioneAltezza?.cancel()

        val altezzaIniziale = decor.height
        if (altezzaIniziale <= 0 || !dialog.isShowing) {
            if (compatta) impostaCompatta() else impostaCompleta()
            return
        }

        // Blocca subito la finestra all'altezza corrente: se resta WRAP_CONTENT,
        // Android la ridimensiona appena cambia la visibilità e l'animazione non si vede.
        window.setLayout(larghezza, altezzaIniziale)

        val uscente = if (compatta) contenutoCompleto else contenutoCompatto
        val entrante = if (compatta) contenutoCompatto else contenutoCompleto

        // Animazione sui contenuti stessi: resta visibile anche sui dispositivi in cui
        // Android ridimensiona il Dialog in un solo frame ignorando l'animazione d'altezza.
        entrante.animate().cancel()
        uscente.animate().cancel()
        entrante.alpha = 0f
        entrante.translationY = if (compatta) -dialog.context.dp(8).toFloat() else dialog.context.dp(8).toFloat()
        entrante.visibility = View.VISIBLE
        entrante.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        uscente.animate()
            .alpha(0f)
            .translationY(if (compatta) dialog.context.dp(8).toFloat() else -dialog.context.dp(8).toFloat())
            .setDuration(150L)
            .withEndAction {
                uscente.visibility = View.GONE
                uscente.alpha = 1f
                uscente.translationY = 0f
            }
            .start()

        decor.post {
            if (!dialog.isShowing) return@post

            decor.measure(
                View.MeasureSpec.makeMeasureSpec(larghezza, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(altezzaSchermo, View.MeasureSpec.AT_MOST)
            )
            val altezzaFinale = decor.measuredHeight.coerceAtLeast(1)
            if (altezzaFinale == altezzaIniziale) {
                window.setLayout(larghezza, WindowManager.LayoutParams.WRAP_CONTENT)
                return@post
            }

            animazioneAltezza = ValueAnimator.ofInt(altezzaIniziale, altezzaFinale).apply {
                duration = if (compatta) 350L else 250L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    if (dialog.isShowing) {
                        window.setLayout(larghezza, animator.animatedValue as Int)
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (dialog.isShowing) {
                            window.setLayout(larghezza, WindowManager.LayoutParams.WRAP_CONTENT)
                        }
                    }
                })
                start()
            }
        }
    }
}
