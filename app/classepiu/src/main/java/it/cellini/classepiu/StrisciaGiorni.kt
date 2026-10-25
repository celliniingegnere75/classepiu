package it.cellini.classepiu

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Striscia dei giorni scorrevole, la stessa dell'elenco degli alunni e delle lezioni: il giorno al centro è quello scelto,
 * la data scritta sopra si aggiorna mentre si scorre e, quando la striscia si ferma su un altro giorno, parte [alCambio].
 */
class StrisciaGiorni(ctx: Context, private val alCambio: (Calendar) -> Unit) {
    private val celle = ArrayList<Pair<TextView, Calendar>>()
    /** Il primo giorno della striscia: mai prima del primo giorno di scuola, e al più 120 giorni fa. */
    private val oggi = Calendar.getInstance()
    private val inizio: Calendar = Scuola.primoGiorno(ctx).let { primo ->
        val daOggi = (oggi.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -MAX_INDIETRO) }
        if (primo.after(daOggi)) primo else daOggi
    }
    /** Quanti giorni la striscia va indietro rispetto a oggi: oggi sta in questa posizione. */
    private val PRIMA: Int = java.time.temporal.ChronoUnit.DAYS.between(giornoDi(inizio), giornoDi(oggi)).toInt().coerceAtLeast(0)
    private var indiceCorrente = PRIMA
    private var indiceCaricato = PRIMA
    var selezionato: Calendar = Calendar.getInstance()
        private set

    /** Data per esteso del giorno scelto ("sabato 25 ottobre 2026"). */
    val data = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
    val vista: HorizontalScrollView

    init {
        val larghezzaCella = ctx.resources.displayMetrics.widthPixels / 7
        val sigle = "DLMMGVS"
        val riga = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(3 * larghezzaCella, 0, 3 * larghezzaCella, 0) }
        val giorniTotali = PRIMA + DOPO
        vista = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(riga) }
        for (i in 0..giorniTotali) {
            val g = (inizio.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val numero = TextView(ctx).apply {
                text = g.get(Calendar.DAY_OF_MONTH).toString(); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f); gravity = Gravity.CENTER
                minHeight = ctx.dp(40)
            }
            val cella = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(ctx).apply { text = sigle[g.get(Calendar.DAY_OF_WEEK) - 1].toString(); textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                addView(numero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
                setOnClickListener { vista.smoothScrollTo(i * larghezzaCella, 0) }
            }
            celle.add(numero to g)
            riga.addView(cella, LinearLayout.LayoutParams(larghezzaCella, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // il giorno al centro della striscia è quello selezionato: la data in alto si aggiorna mentre si scorre
        val assesta = Runnable {
            vista.smoothScrollTo(indiceCorrente * larghezzaCella, 0)
            if (indiceCorrente != indiceCaricato) {
                indiceCaricato = indiceCorrente
                alCambio(selezionato)
            }
        }
        vista.setOnScrollChangeListener { v, x, _, _, _ ->
            val idx = ((x + larghezzaCella / 2) / larghezzaCella).coerceIn(0, giorniTotali)
            if (idx != indiceCorrente) { indiceCorrente = idx; colora(celle[idx].second) }
            v.removeCallbacks(assesta)
            v.postDelayed(assesta, 150)
        }
        // oggi selezionato e al centro della striscia
        vista.post { vista.scrollTo(PRIMA * larghezzaCella, 0) }
        colora(oggi)
    }

    private fun colora(g: Calendar) {
        selezionato = g
        data.text = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALIAN).format(g.time)
        celle.forEach { (t, c) ->
            val sel = c.get(Calendar.DAY_OF_YEAR) == g.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == g.get(Calendar.YEAR)
            t.setTextColor(Color.WHITE)
            // in dp (non in sp): con il carattere di sistema grande Android non scala in modo lineare e il rapporto non sarebbe 1,5
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (sel) 36f else 24f)
            t.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Data e striscia una sotto l'altra, con gli stessi spazi dell'elenco degli alunni. */
    fun aggiungiA(colonna: LinearLayout) {
        colonna.addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = colonna.context.dp(6) })
        colonna.addView(vista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = colonna.context.dp(10); bottomMargin = colonna.context.dp(14)
        })
    }

    private companion object {
        /** Il giorno del calendario, senza ore: così il conto dei giorni non sbaglia con l'ora legale. */
        fun giornoDi(c: Calendar) = java.time.LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

        const val MAX_INDIETRO = 120 // giorni prima di oggi, al massimo
        const val DOPO = 60          // giorni dopo oggi
    }
}
