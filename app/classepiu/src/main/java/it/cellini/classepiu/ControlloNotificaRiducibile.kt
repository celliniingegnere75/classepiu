package it.cellini.classepiu

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

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
        // La X è il terzo elemento dell'intestazione: logo, freccia, X.
        (frecciaRiduci.parent as? android.view.ViewGroup)?.let { intestazione ->
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
                        dialog.dismiss()
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

        if (prefs.getBoolean("ridotta", false)) mostraCompatta() else mostraCompleta()
    }

    private fun stilePulsante(view: View, suAzzurro: Boolean, chiusura: Boolean = false) {
        val ctx = dialog.context
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (suAzzurro) intArrayOf(0xFF8BD0F2.toInt(), 0xFF3298D0.toInt())
            else intArrayOf(0xFFEAF7FD.toInt(), 0xFFB9E2F5.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(ctx.dp(1.5f).toInt().coerceAtLeast(1),
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
        contenutoCompleto.visibility = View.GONE
        contenutoCompatto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 92 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(.08f)
    }

    private fun mostraCompleta() {
        prefs.edit().putBoolean("ridotta", false).putBoolean("chiusa", false).apply()
        contenutoCompatto.visibility = View.GONE
        contenutoCompleto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 88 / 100,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(.28f)
    }
}
