package it.cellini.classepiu

import android.app.Dialog
import android.view.View
import android.widget.TextView

/**
 * Collega una vista completa e una vista compatta della stessa notifica.
 * Riutilizzabile nelle future finestre: la vista compatta deve includere
 * il controllo per tornare alla vista completa.
 */
class ControlloNotificaRiducibile(
    private val dialog: Dialog,
    private val contenutoCompleto: View,
    private val contenutoCompatto: View,
    frecciaRiduci: View,
    frecciaEspandi: View
) {
    init {
        frecciaRiduci.setOnClickListener { mostraCompatta() }
        frecciaEspandi.setOnClickListener { mostraCompleta() }
        mostraCompleta()
    }

    private fun mostraCompatta() {
        contenutoCompleto.visibility = View.GONE
        contenutoCompatto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 92 / 100,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(.12f)
    }

    private fun mostraCompleta() {
        contenutoCompatto.visibility = View.GONE
        contenutoCompleto.visibility = View.VISIBLE
        dialog.window?.setLayout(
            dialog.context.resources.displayMetrics.widthPixels * 88 / 100,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(.28f)
    }
}
