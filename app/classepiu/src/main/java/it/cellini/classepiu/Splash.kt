package it.cellini.classepiu

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Breve animazione di apertura: il logo bianco classePiù compare sul fondo azzurro, poi tutto sfuma. */
object Splash {
    fun mostra(a: Activity) {
        val radice = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val velo = FrameLayout(a).apply {
            setBackgroundColor(0xFF69BCE8.toInt())
            isClickable = true
            elevation = 100f
            addView(ImageView(a).apply {
                setImageResource(R.drawable.logo_bianco)
                adjustViewBounds = true
                scaleX = .6f; scaleY = .6f; alpha = 0f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start()
            }, FrameLayout.LayoutParams(a.dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
        radice.addView(velo, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        velo.postDelayed({
            velo.animate().alpha(0f).setDuration(350).withEndAction { radice.removeView(velo) }.start()
        }, 900)
    }
}
