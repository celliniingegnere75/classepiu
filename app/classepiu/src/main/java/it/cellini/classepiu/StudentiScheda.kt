package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Elenco degli studenti di una classe nel formato ClasseViva con i colori di classePiù, con presenze per giorno. */
class StudentiScheda : Scheda() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private lateinit var striscia: StrisciaGiorni
    private var studenti: List<Studente> = emptyList()
    private val selezionato: Calendar get() = striscia.selezionato
    private var lavoro: Job? = null
    private var tipo = ""
    private var classeId = ""
    private val formatoApi = SimpleDateFormat("yyyyMMdd", Locale.ITALIAN)

    override fun crea(savedInstanceState: Bundle?) {
        val nome = intent.getStringExtra("nome") ?: ""
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = requireContext()

        val chiudi = TextView(ctx).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val titolo = TextView(ctx).apply {
            text = nome; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        // striscia dei giorni scorrevole: quando si ferma su un altro giorno si caricano le presenze di quel giorno
        val striscia = StrisciaGiorni(ctx) {
            mostra(emptyMap(), caricamento = true)
            caricaStato()
        }
        this.striscia = striscia

        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16 + SPAZIO_BARRA)) }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra)
            striscia.aggiungiA(requireContext())
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        carica()
    }

    /** Carica l'elenco studenti una volta, poi le presenze del giorno. */
    private fun carica() {
        testo("carico gli studenti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(requireContext()).client().studentiGrezzi(tipo, classeId) } }
            val (codice, corpo) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            studenti = if (codice == 200) leggiStudenti(corpo) else emptyList()
            if (studenti.isEmpty()) {
                testo(if (codice == 200) "risposta ricevuta ma nessuno studente riconosciuto." else "classeviva ha risposto con un errore (http $codice).", codice, corpo)
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
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(requireContext()).client().statoGrezzo(tipo, classeId, giorno) } }
            val stato = r.getOrNull()?.let { (c, t) -> if (c == 200) leggiStato(t) else emptyMap() } ?: emptyMap()
            mostra(stato, caricamento = false, grezzo = r.getOrNull())
        }
    }

    private fun mostra(stato: Map<String, Int>, caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = requireContext()
        studenti.forEach { s ->
            val colore = when {
                caricamento -> Color.TRANSPARENT                                  // in attesa dei dati: nessun pallino, niente grigio provvisorio
                stato.isEmpty() -> 0xFFB8B8B8.toInt()                        // nessun dato: grigio
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
        if (!caricamento && grezzo != null) copiaRisposta("copia la risposta di classeviva di questo giorno", grezzo.first, "giorno ${formatoApi.format(selezionato.time)}\n${grezzo.second}")
    }

    private fun testo(s: String, codice: Int = 0, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(requireContext()).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
        if (corpo.isNotEmpty()) copiaRisposta("mostra la risposta di classeviva (tocca per copiare)", codice, corpo)
    }

    private fun copiaRisposta(etichetta: String, codice: Int, corpo: String) {
        lista.addView(TextView(requireContext()).apply {
            text = etichetta; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
