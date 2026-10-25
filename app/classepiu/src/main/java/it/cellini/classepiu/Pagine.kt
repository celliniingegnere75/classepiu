package it.cellini.classepiu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Altezza della barra delle pagine e della fascia azzurra sotto ai pannelli (dp). */
const val ALTEZZA_BARRA = 66
const val FASCIA_BLU = 14
/** Spazio da lasciare in fondo alle liste perché l'ultima voce non resti sotto la barra (dp). */
const val SPAZIO_BARRA = ALTEZZA_BARRA - FASCIA_BLU + 12

/** Barra delle pagine (home, registro, bacheca, setting), la stessa in tutte le schermate; la voce attiva ha l'etichetta più scura. */
class BarraPagine(ctx: Context, scegli: (Int) -> Unit) {
    private val etichette = mutableMapOf<Int, TextView>()
    val vista: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(ctx, R.color.grigio_barra))
            cornerRadius = ctx.dp(33).toFloat()
        }
        elevation = ctx.dp(3).toFloat()
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        // la barra prende i tocchi: non passano alla lista che le scorre sotto
        isClickable = true
    }

    init {
        listOf(
            Triple(MainActivity.TAB_HOME, "home", R.drawable.ic_nav_home),
            Triple(MainActivity.TAB_REGISTRO, "registro", R.drawable.ic_nav_registro),
            Triple(MainActivity.TAB_BACHECA, "bacheca", R.drawable.ic_nav_bacheca),
            Triple(MainActivity.TAB_IMPOSTAZIONI, "setting", R.drawable.ic_nav_impostazioni),
        ).forEach { (tab, nome, icona) ->
            val img = ImageView(ctx).apply { setImageResource(icona); imageTintList = ColorStateList.valueOf(0xFF3F3F3F.toInt()) }
            val riquadro = FrameLayout(ctx).apply { addView(img, FrameLayout.LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER)) }
            val etichetta = TextView(ctx).apply {
                text = nome; textSize = 12f; maxLines = 1; setSingleLine(true); gravity = Gravity.CENTER; includeFontPadding = false
            }
            val voce = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(riquadro, LinearLayout.LayoutParams(ctx.dp(54), ctx.dp(34)))
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
 * Mette la schermata sopra la fascia azzurra in basso e la barra delle pagine appoggiata al fondo, sempre visibile.
 * Toccando una voce si lascia la schermata e si va alla pagina scelta; mentre è aperta una finestra di modifica
 * la finestra copre tutto e la barra non risponde finché non la si chiude.
 */
fun Activity.conBarra(contenuto: View, attiva: Int, scegli: ((Int) -> Unit)? = null): BarraPagine {
    val barra = BarraPagine(this) { tab -> if (scegli != null) scegli(tab) else vaiAllaPagina(tab) }
    barra.attiva(attiva)
    setContentView(FrameLayout(this).apply {
        setBackgroundColor(ContextCompat.getColor(this@conBarra, R.color.logo_sfondo))
        addView(contenuto, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(FASCIA_BLU) })
        addView(barra.vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ALTEZZA_BARRA), Gravity.BOTTOM).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })
    })
    return barra
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

/** Torna alla schermata principale sulla pagina scelta, chiudendo le schede aperte sopra. */
fun Activity.vaiAllaPagina(tab: Int) {
    startActivity(Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_SCHEDA, tab)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
}
