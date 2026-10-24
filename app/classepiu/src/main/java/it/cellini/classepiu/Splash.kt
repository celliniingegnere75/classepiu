package it.cellini.classepiu

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Animazione di apertura: il logo bianco classePiù compare sul fondo azzurro, poi tutto sfuma.
 * Con [conFrase] (solo alla schermata di accesso, al primo avvio) sotto il logo appare anche
 * la frase scherzosa "è intelligente ma si applica!" e l'animazione dura 3 secondi in più.
 */
object Splash {
    fun mostra(a: Activity, conFrase: Boolean = false) {
        val radice = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val frase = TextView(a).apply {
            text = "è intelligente ma si applica!"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("cursive", Typeface.NORMAL) // corsivo calligrafico, un po' frivolo
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = a.dp(8).toFloat()
        }
        val velo = FrameLayout(a).apply {
            setBackgroundColor(0xFF69BCE8.toInt())
            isClickable = true
            elevation = 100f
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(a).apply {
                    setImageResource(R.drawable.logo_bianco)
                    adjustViewBounds = true
                    scaleX = .6f; scaleY = .6f; alpha = 0f
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450)
                        .withEndAction { if (conFrase) frase.animate().alpha(1f).translationY(0f).setDuration(400).start() }.start()
                }, LinearLayout.LayoutParams(a.dp(190), ViewGroup.LayoutParams.WRAP_CONTENT))
                if (conFrase) addView(frase, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(14) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        radice.addView(velo, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // senza frase: logo per 900 ms; con frase resta 3 secondi dopo la comparsa del logo (450 ms)
        velo.postDelayed({
            velo.animate().alpha(0f).setDuration(350).withEndAction { radice.removeView(velo) }.start()
        }, if (conFrase) 450L + 3000L else 900L)
    }
}
