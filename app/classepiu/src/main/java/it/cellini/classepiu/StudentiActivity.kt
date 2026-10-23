package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Elenco degli studenti di una classe nel formato ClasseViva con i colori di classePiù, con presenze per giorno. */
class StudentiActivity : AppCompatActivity() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private lateinit var data: TextView
    private val celle = ArrayList<Pair<TextView, Calendar>>()
    private var studenti: List<Studente> = emptyList()
    private var selezionato: Calendar = Calendar.getInstance()
    private var lavoro: Job? = null
    private var tipo = ""
    private var classeId = ""
    private val formatoApi = SimpleDateFormat("yyyyMMdd", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = azzurro
        val nome = intent.getStringExtra("nome") ?: ""
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = this

        val chiudi = TextView(ctx).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val titolo = TextView(ctx).apply {
            text = nome; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        data = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }

        // striscia dei giorni scorrevole: a destra le date precedenti, a sinistra le successive
        val larghezzaCella = resources.displayMetrics.widthPixels / 7
        val sigle = "DLMMGVS"
        val riga = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        val oggi = Calendar.getInstance()
        val inizio = (oggi.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -120) }
        val giorniTotali = 120 + 60
        var cellaOggi: View? = null
        for (i in 0..giorniTotali) {
            val g = (inizio.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val numero = TextView(ctx).apply {
                text = g.get(Calendar.DAY_OF_MONTH).toString(); textSize = 18f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            }
            val cella = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(ctx).apply { text = sigle[g.get(Calendar.DAY_OF_WEEK) - 1].toString(); textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                addView(numero, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(4) })
                setOnClickListener { seleziona(g) }
            }
            celle.add(numero to g)
            riga.addView(cella, LinearLayout.LayoutParams(larghezzaCella, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (i == 120) cellaOggi = cella
        }
        val striscia = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(riga) }

        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16)) }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra)
            addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(striscia, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(14) })
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        // oggi selezionato e al centro della striscia
        striscia.post { striscia.scrollTo((cellaOggi?.left ?: 0) - 3 * larghezzaCella, 0) }
        colora(oggi)
        carica()
    }

    private fun colora(g: Calendar) {
        selezionato = g
        data.text = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALIAN).format(g.time).uppercase()
        celle.forEach { (t, c) ->
            val sel = c.get(Calendar.DAY_OF_YEAR) == g.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == g.get(Calendar.YEAR)
            t.setTextColor(if (sel) azzurro else Color.WHITE)
            t.background = if (sel) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } else null
        }
    }

    private fun seleziona(g: Calendar) {
        colora(g)
        mostra(emptyMap(), caricamento = true)
        caricaStato()
    }

    /** Carica l'elenco studenti una volta, poi le presenze del giorno. */
    private fun carica() {
        testo("Carico gli studenti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(this@StudentiActivity).client().studentiGrezzi(tipo, classeId) } }
            val (codice, corpo) = r.getOrElse { testo("Non riuscito: ${it.message}"); return@launch }
            studenti = if (codice == 200) leggiStudenti(corpo) else emptyList()
            if (studenti.isEmpty()) {
                testo(if (codice == 200) "Risposta ricevuta ma nessuno studente riconosciuto." else "ClasseViva ha risposto con un errore (HTTP $codice).", codice, corpo)
                return@launch
            }
            mostra(emptyMap(), caricamento = true)
            caricaStato()
        }
    }

    private fun caricaStato() {
        lavoro?.cancel()
        val giorno = formatoApi.format(selezionato.time)
        lavoro = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(this@StudentiActivity).client().statoGrezzo(tipo, classeId, giorno) } }
            val stato = r.getOrNull()?.let { (c, t) -> if (c == 200) leggiStato(t) else emptyMap() } ?: emptyMap()
            mostra(stato, caricamento = false, grezzo = r.getOrNull())
        }
    }

    private fun mostra(stato: Map<String, Int>, caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = this
        studenti.forEach { s ->
            val colore = when {
                caricamento || stato.isEmpty() -> 0xFFB8B8B8.toInt()           // nessun dato: grigio
                else -> when (stato[s.id] ?: -1) { 2 -> 0xFFE53935.toInt(); 1 -> 0xFFF2C200.toInt(); 0 -> 0xFF2EB82E.toInt(); else -> 0xFFB8B8B8.toInt() }
            }
            val pallino = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 11f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colore) }
            }
            lista.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                addView(pallino, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(TextView(ctx).apply { text = s.nome; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); gravity = Gravity.TOP
                    setLines(2); ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        if (!caricamento && grezzo != null && stato.isEmpty()) copiaRisposta("Presenze non riconosciute: mostra la risposta di ClasseViva (tocca per copiare)", grezzo.first, grezzo.second)
    }

    private fun testo(s: String, codice: Int = 0, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(this).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
        if (corpo.isNotEmpty()) copiaRisposta("Mostra la risposta di ClasseViva (tocca per copiare)", codice, corpo)
    }

    private fun copiaRisposta(etichetta: String, codice: Int, corpo: String) {
        lista.addView(TextView(this).apply {
            text = etichetta; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                android.widget.Toast.makeText(context, "Copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
