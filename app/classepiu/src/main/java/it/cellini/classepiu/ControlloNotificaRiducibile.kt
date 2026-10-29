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

    init {
        stilePulsante(frecciaRiduci, true)
        stilePulsante(frecciaEspandi, false)
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
        contenutoCompleto.alpha = 1f
        contenutoCompleto.translationY = 0f
        contenutoCompatto.visibility = View.VISIBLE
        contenutoCompatto.alpha = 1f
        contenutoCompatto.translationY = 0f
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 92 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun impostaCompleta() {
        contenutoCompatto.visibility = View.GONE
        contenutoCompatto.alpha = 1f
        contenutoCompatto.translationY = 0f
        contenutoCompleto.visibility = View.VISIBLE
        contenutoCompleto.alpha = 1f
        contenutoCompleto.translationY = 0f
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 88 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Anima esplicitamente la sostituzione dei due contenuti con una transizione Android.
     * L'animazione riguarda la vista, non dipende dal fatto che il Dialog accetti di animare
     * la propria altezza: così il passaggio resta visibile anche se la finestra si ridimensiona subito.
     */
    private fun animaCambioStato(compatta: Boolean) {
        val window = dialog.window ?: return
        val contenitore = (contenutoCompleto.parent as? ViewGroup)
        val larghezza = dialog.context.resources.displayMetrics.widthPixels * (if (compatta) 92 else 88) / 100
        val uscente = if (compatta) contenutoCompleto else contenutoCompatto
        val entrante = if (compatta) contenutoCompatto else contenutoCompleto

        if (!dialog.isShowing || contenitore == null) {
            if (compatta) impostaCompatta() else impostaCompleta()
            return
        }

        // AutoTransition anima fade e cambiamento dei limiti durante la variazione di visibilità.
        val transizione = android.transition.AutoTransition().apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
        }
        android.transition.TransitionManager.beginDelayedTransition(contenitore, transizione)
        uscente.visibility = View.GONE
        entrante.visibility = View.VISIBLE
        entrante.alpha = 1f
        entrante.translationY = 0f

        // Lascia WRAP_CONTENT al Dialog: l'altezza finale viene ricalcolata dal layout Android.
        window.setLayout(larghezza, WindowManager.LayoutParams.WRAP_CONTENT)
        contenitore.requestLayout()
        contenitore.post {
            if (dialog.isShowing) {
                window.setLayout(larghezza, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }
}
