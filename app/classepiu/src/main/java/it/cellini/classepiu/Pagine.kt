package it.cellini.classepiu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** Altezza della barra delle pagine, della riga azzurra in fondo e di quanto la barra sta sopra il fondo (dp). */
const val ALTEZZA_BARRA = 66
const val FASCIA_BLU = 14
const val ALZO_BARRA = 5
/** Spazio sopra la barra per l'alone d'ombra (dp): l'ombra di Android scende sempre in basso, questa sale e segue l'ovale. */
const val SOMBRA_BARRA = 6
/** Spazio da lasciare in fondo alle liste perché l'ultima voce si possa portare sopra la barra (dp). */
const val SPAZIO_BARRA = ALTEZZA_BARRA + ALZO_BARRA + 12

/** Barra delle pagine (home, registri, bacheca), la stessa in tutte le schermate; la voce attiva ha l'etichetta più scura. */
class BarraPagine(ctx: Context, scegli: (Int) -> Unit) {
    private val etichette = mutableMapOf<Int, TextView>()
    val vista: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(ctx, R.color.grigio_barra))
            cornerRadius = ctx.dp(33).toFloat()
        }
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        // la barra prende i tocchi: non passano alla lista che le scorre sotto
        isClickable = true
    }

    init {
        listOf(
            Triple(MainActivity.TAB_HOME, "home", R.drawable.ic_nav_home),
            Triple(MainActivity.TAB_REGISTRO, "registri", R.drawable.ic_nav_registro),
            Triple(MainActivity.TAB_BACHECA, "bacheca", R.drawable.ic_nav_bacheca),
        ).forEach { (tab, nome, icona) ->
            val img = ImageView(ctx).apply { setImageResource(icona); imageTintList = ColorStateList.valueOf(0xFF3F3F3F.toInt()) }
            val riquadro = FrameLayout(ctx).apply { addView(img, FrameLayout.LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER)) }
            val etichetta = TextView(ctx).apply {
                text = nome; textSize = 12f; maxLines = 1; setSingleLine(true); gravity = Gravity.CENTER; includeFontPadding = false
            }
            val voce = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(riquadro, LinearLayout.LayoutParams(ctx.dp(54), ctx.dp(30)))
                addView(etichetta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
                setOnClickListener { scegli(tab) }
            }
            vista.addView(voce, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            etichette[tab] = etichetta
        }
    }

    fun attiva(tab: Int) {
        etichette.forEach { (t, e) -> e.setTextColor(if (t == tab) 0xFF111111.toInt() else 0xFF4A4A4A.toInt()) }
    }
}

/**
 * Barra delle pagine e riga azzurra in un solo oggetto, in sovraimpressione sul fondo della schermata principale:
 * le pagine e le schede scorrono sotto, lei resta ferma. La barra è alzata di [ALZO_BARRA] per lasciar vedere il blu.
 */
fun Context.sovraimpressione(barra: BarraPagine): FrameLayout = FrameLayout(this).apply {
    // alone verso l'alto che segue l'ovale della barra: lo stesso ovale, sfumato e spostato un poco in su; non riceve tocchi
    addView(AloneBarra(context), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ALTEZZA_BARRA + SOMBRA_BARRA), Gravity.BOTTOM).apply {
        bottomMargin = dp(ALZO_BARRA)
    })
    addView(View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.logo_sfondo)) },
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FASCIA_BLU), Gravity.BOTTOM))
    addView(barra.vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ALTEZZA_BARRA), Gravity.BOTTOM).apply {
        leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(ALZO_BARRA)
    })
}

/** Ombra della barra: l'ovale della barra in nero trasparente, sfocato di 4 dp e alzato di 1,5 dp, così sporge solo sopra e ai lati. */
private class AloneBarra(ctx: Context) : View(ctx) {
    private val pennello = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x46000000
        maskFilter = android.graphics.BlurMaskFilter(ctx.dp(4).toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val ovale = android.graphics.RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // la sfocatura si disegna solo così

    override fun onDraw(canvas: android.graphics.Canvas) {
        val su = context.dp(3) / 2f
        ovale.set(context.dp(18).toFloat(), context.dp(SOMBRA_BARRA) - su, width - context.dp(18).toFloat(), height - su)
        val r = context.dp(33).toFloat()
        canvas.drawRoundRect(ovale, r, r, pennello)
    }
}

/**
 * Scheda aperta da registro o bacheca (alunni, voti, note, lezioni, circolare): entra da destra sopra la pagina
 * da cui è aperta, dentro la schermata principale, così la barra e la riga azzurra non si muovono.
 * Si scrive come una schermata: [crea] costruisce la vista e la passa a [setContentView]; [finish] la chiude.
 */
abstract class Scheda : Fragment() {
    private var radice: View? = null

    /** Gli extra con cui è stata aperta. */
    protected val intent: Intent get() = Intent().putExtras(arguments ?: Bundle())

    protected abstract fun crea(savedInstanceState: Bundle?)

    protected fun setContentView(v: View) { radice = v }

    protected fun finish() { if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack() }

    protected fun dp(v: Int): Int = requireContext().dp(v)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        crea(savedInstanceState)
        // la scheda copre la pagina sotto: i tocchi non passano
        return radice!!.apply { isClickable = true }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        radice = null
    }
}

/**
 * Le liste che arrivano in fondo al [contenitore] scorrono fin sotto la barra: spazio in fondo alla lista (non al contenitore)
 * e niente taglio sul bordo, così l'ultima casella resta coperta solo dalla barra. Va richiamata quando le pagine cambiano.
 */
fun ViewGroup.listeSottoLaBarra() {
    if (height == 0) return
    val fondo = IntArray(2).also { getLocationInWindow(it) }[1] + height
    fun cerca(v: View) {
        val scorre = v is android.widget.ScrollView || v is android.widget.AbsListView || v is androidx.core.view.ScrollingView
        if (scorre && v.getTag(R.id.spazio_barra) == null && v.height > v.context.dp(120)) {
            val sotto = IntArray(2).also { v.getLocationInWindow(it) }[1] + v.height
            // solo le liste verticali che arrivano al fondo della pagina
            if (sotto >= fondo - v.context.dp(4)) {
                v.setTag(R.id.spazio_barra, true)
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom + v.context.dp(SPAZIO_BARRA))
                (v as ViewGroup).clipToPadding = false
                return
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) cerca(v.getChildAt(i))
    }
    cerca(this)
}
