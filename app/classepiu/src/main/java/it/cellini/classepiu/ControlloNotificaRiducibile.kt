package it.cellini.classepiu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * Controller di una sola notifica riducibile: ogni finestra ha il suo stato esteso/ridotto, deciso dall'orario
 * ([ridottaIniziale]) e cambiato solo dalle sue frecce o dal suo timer; chiusura con swipe verso destra.
 * Nessuna preferenza globale: ridurre una notifica non tocca le altre.
 */
class ControlloNotificaRiducibile(
    private val ctx: Context,
    private val contenutoCompleto: View,
    private val contenutoCompatto: View,
    private val frecciaRiduci: View,
    private val frecciaEspandi: View,
    private val chiudi: View,
    ridottaIniziale: Boolean,
    /** Avvisa il contenitore che lo stato è cambiato (per l'ombra dietro le finestre). */
    private val alCambioStato: () -> Unit,
    /** Chiamata a fine swipe: la finestra va tolta. */
    private val allaChiusura: () -> Unit,
    /** Chiamata quando l'utente la espande con la freccia: le altre estese si riducono. */
    private val allEspansione: () -> Unit = {}
) {
    private var inizioX = 0f
    private var inizioY = 0f
    private var animatore: ValueAnimator? = null

    /** true = modalità ridotta (striscia). */
    var ridotta = ridottaIniziale
        private set

    init {
        stilePulsante(frecciaRiduci)
        stilePulsante(frecciaEspandi)
        stilePulsante(chiudi)

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
                    if (dx > ctx.dp(72) && dx > kotlin.math.abs(dy) * 1.25f) {
                        v.animate()
                            .translationX(v.width.toFloat().coerceAtLeast(ctx.dp(120).toFloat()))
                            .alpha(0f)
                            .setDuration(240)
                            .withEndAction { allaChiusura() }
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

        if (ridotta) impostaCompatta() else impostaCompleta()
    }

    /** Riduzione chiesta dall'orario (15 minuti prima della fine dell'ora), con la stessa animazione della freccia. */
    fun riduci() { if (!ridotta) mostraCompatta() }

    /** Ferma un'animazione in corso (finestra che viene tolta). */
    fun ferma() { animatore?.cancel(); animatore = null }

    /** Tondo bianco pieno con l'icona azzurra (le icone sono vettori in res/drawable), tutto opaco. */
    private fun stilePulsante(view: View) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        view.elevation = ctx.dp(2).toFloat()
        (view as? android.widget.ImageView)?.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
    }

    private fun mostraCompatta() {
        ridotta = true
        animaCambioStato(compatta = true)
        alCambioStato()
    }

    private fun mostraCompleta() {
        ridotta = false
        animaCambioStato(compatta = false)
        allEspansione()
        alCambioStato()
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
     * Passaggio animato senza trasparenze: la vista che entra scende dall'alto, ben opaca, sopra quella che esce, mentre
     * il contenitore, tagliato ad angoli arrotondati, cambia altezza un fotogramma alla volta (la finestra è WRAP_CONTENT
     * e lo segue). Le due viste hanno altezza fissa durante il movimento, così il contenuto non viene schiacciato.
     */
    private fun animaCambioStato(compatta: Boolean) {
        val contenitore = contenutoCompleto.parent as? ViewGroup
        val uscente = if (compatta) contenutoCompleto else contenutoCompatto
        val entrante = if (compatta) contenutoCompatto else contenutoCompleto
        animatore?.cancel()
        if (!contenutoCompleto.isAttachedToWindow || contenitore == null || contenitore.width == 0) {
            if (compatta) impostaCompatta() else impostaCompleta()
            return
        }
        val larghezza = contenitore.width
        val altezzaIniziale = contenitore.height
        fun misura(v: View): Int {
            val vecchia = v.visibility
            v.visibility = View.VISIBLE
            v.measure(
                View.MeasureSpec.makeMeasureSpec(larghezza, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            v.visibility = vecchia
            return v.measuredHeight
        }
        val altezzaFinale = misura(entrante)
        val altezzaUscente = altezzaIniziale
        // altezze fisse per tutta l'animazione
        uscente.layoutParams = uscente.layoutParams.apply { height = altezzaUscente }
        entrante.layoutParams = entrante.layoutParams.apply { height = altezzaFinale }
        contenitore.clipToOutline = true
        contenitore.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, ctx.dp(24).toFloat())
            }
        }
        val parametri = contenitore.layoutParams
        parametri.height = altezzaIniziale
        contenitore.layoutParams = parametri
        entrante.visibility = View.VISIBLE
        entrante.translationY = -altezzaFinale.toFloat()
        contenitore.bringChildToFront(entrante)
        animatore = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (compatta) 420L else 620L
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                parametri.height = (altezzaIniziale + (altezzaFinale - altezzaIniziale) * f).toInt()
                contenitore.layoutParams = parametri
                entrante.translationY = -altezzaFinale * (1f - f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var annullata = false
                private fun ripristina() {
                    uscente.visibility = View.GONE
                    uscente.translationY = 0f
                    entrante.translationY = 0f
                    uscente.layoutParams = uscente.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    entrante.layoutParams = entrante.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    parametri.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    contenitore.layoutParams = parametri
                }
                override fun onAnimationCancel(animation: android.animation.Animator) { annullata = true; ripristina() }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!annullata) ripristina() }
            })
            start()
        }
    }
}
