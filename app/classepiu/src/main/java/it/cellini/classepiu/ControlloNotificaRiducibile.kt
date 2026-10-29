package it.cellini.classepiu

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private val frecciaEspandi: View,
    private val chiudi: View
) {
    private val prefs = dialog.context.getSharedPreferences("classepiu_notifica", 0)
    private var inizioX = 0f
    private var inizioY = 0f
    private var animatore: ValueAnimator? = null

    init {
        stilePulsante(frecciaRiduci, true)
        stilePulsante(frecciaEspandi, false)
        stilePulsante(chiudi, true, chiusura = true)

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
                        v.animate()
                            .translationX(v.width.toFloat().coerceAtLeast(dialog.context.dp(120).toFloat()))
                            .alpha(0f)
                            .setDuration(240)
                            .withEndAction { dialog.dismiss() }
                            .start()
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        contenutoCompleto.setOnTouchListener(swipe)
        contenutoCompatto.setOnTouchListener(swipe)

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
            setStroke(ctx.dp(2).coerceAtLeast(1), if (suAzzurro) 0xBFFFFFFF.toInt() else 0xFF69BCE8.toInt())
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
        prefs.edit().putBoolean("ridotta", true).apply()
        animaCambioStato(compatta = true)
        dialog.window?.setDimAmount(.08f)
    }

    private fun mostraCompleta() {
        prefs.edit().putBoolean("ridotta", false).apply()
        animaCambioStato(compatta = false)
        dialog.window?.setDimAmount(.28f)
    }

    private fun impostaCompatta() {
        contenutoCompleto.visibility = View.GONE
        contenutoCompatto.visibility = View.VISIBLE
        contenutoCompatto.alpha = 1f
    }

    private fun impostaCompleta() {
        contenutoCompatto.visibility = View.GONE
        contenutoCompleto.visibility = View.VISIBLE
        contenutoCompleto.alpha = 1f
    }

    /**
     * Passaggio animato tra le due viste: l'altezza del contenitore scende (o sale) un fotogramma alla volta mentre
     * una vista sfuma e l'altra compare. La finestra del Dialog è WRAP_CONTENT e segue l'altezza a ogni fotogramma;
     * una Transition sulle viste non bastava, perché la finestra cambiava misura di colpo e tagliava l'animazione.
     */
    private fun animaCambioStato(compatta: Boolean) {
        val contenitore = contenutoCompleto.parent as? ViewGroup
        val uscente = if (compatta) contenutoCompleto else contenutoCompatto
        val entrante = if (compatta) contenutoCompatto else contenutoCompleto
        animatore?.cancel()
        if (!dialog.isShowing || contenitore == null || contenitore.width == 0) {
            if (compatta) impostaCompatta() else impostaCompleta()
            return
        }
        val altezzaIniziale = contenitore.height
        entrante.visibility = View.VISIBLE
        entrante.measure(
            View.MeasureSpec.makeMeasureSpec(contenitore.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val altezzaFinale = entrante.measuredHeight
        val parametri = contenitore.layoutParams
        parametri.height = altezzaIniziale
        contenitore.layoutParams = parametri
        val alphaUscente = uscente.alpha
        entrante.alpha = 0f
        animatore = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                parametri.height = (altezzaIniziale + (altezzaFinale - altezzaIniziale) * f).toInt()
                contenitore.layoutParams = parametri
                // la vista che esce sparisce nella prima metà, quella che entra compare nella seconda
                uscente.alpha = alphaUscente * (1f - f * 2f).coerceIn(0f, 1f)
                entrante.alpha = ((f - .35f) / .65f).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var annullata = false
                override fun onAnimationCancel(animation: android.animation.Animator) { annullata = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (annullata) return
                    uscente.visibility = View.GONE
                    uscente.alpha = 1f
                    entrante.alpha = 1f
                    parametri.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    contenitore.layoutParams = parametri
                }
            })
            start()
        }
    }
}
