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
import org.json.JSONArray
import org.json.JSONObject

/** Elenco studenti con scheda divisa in anagrafica e indicatori delle nove ore. */
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

    // Colori della scheda e degli indicatori.
    private val verdePresente = 0xFFE3F3E5.toInt()
    private val rosaAssente = 0xFFFBE3E7.toInt()
    private val verdeOra = 0xFF2EB82E.toInt()
    private val rosaOra = 0xFFE53935.toInt()
    private val gialloOra = 0xFFF2C200.toInt()
    private val grigioOra = 0xFFB8B8B8.toInt()

    override fun crea(savedInstanceState: Bundle?) {
        val nome = intent.getStringExtra("nome") ?: ""
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = requireContext()

        val chiudi = TextView(ctx).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        val titolo = TextView(ctx).apply {
            text = nome; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        val giorni = StrisciaGiorni(ctx) {
            mostra(emptyMap(), caricamento = true)
            caricaStato()
        }
        striscia = giorni

        lista = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16 + SPAZIO_BARRA))
        }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply {
                setColor(0xFFF0F0F0.toInt())
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
        }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(azzurro)
            addView(barra)
            giorni.aggiungiA(this)
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        carica()
    }

    /** Carica l'elenco studenti una volta, poi le presenze del giorno. */
    private fun carica() {
        testo("carico gli studenti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Impostazioni(requireContext()).client().studentiGrezzi(tipo, classeId) }
            }
            val (codice, corpo) = r.getOrElse {
                testo("non riuscito: ${it.message}")
                return@launch
            }
            studenti = if (codice == 200) leggiStudenti(corpo) else emptyList()
            if (studenti.isEmpty()) {
                testo(
                    if (codice == 200) "risposta ricevuta ma nessuno studente riconosciuto."
                    else "classeviva ha risposto con un errore (http $codice).",
                    codice,
                    corpo
                )
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
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val client = Impostazioni(requireContext()).client()
                    val giornaliero = client.statoGrezzo(tipo, classeId, giorno)
                    val lezioni = client.lezioniClasseGrezze(tipo, classeId, giorno)
                    giornaliero to lezioni
                }
            }
            val coppia = r.getOrNull()
            val stato = coppia?.first?.let { (c, t) -> if (c == 200) leggiStato(t) else emptyMap() } ?: emptyMap()
            val statiOre = coppia?.second?.let { (c, t) -> if (c == 200) leggiStatiOre(t) else emptyMap() } ?: emptyMap()
            val diagnostica = coppia?.let { (g, l) ->
                (g.first to "STATO GIORNALIERO HTTP ${g.first}\\n${g.second}\\n\\nLEZIONI E PRESENZE PER ORA HTTP ${l.first}\\n${l.second}")
            }
            mostra(stato, statiOre = statiOre, caricamento = false, grezzo = diagnostica)
        }
    }

    private fun mostra(stato: Map<String, Int>, statiOre: Map<String, List<Int>> = emptyMap(), caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = requireContext()

        studenti.forEach { s ->
            val valore = stato[s.id] ?: -1
            val coloreAnagrafica = when {
                caricamento -> 0xFFFCFCFC.toInt()
                valore == 2 -> rosaAssente
                valore == 0 || valore == 1 -> verdePresente
                else -> 0xFFEEEEEE.toInt()
            }

            // Scheda unica con due celle sovrapposte: anagrafica sopra, nove ore sotto.
            val scheda = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                clipToOutline = true
            }

            val cellaNome = TextView(ctx).apply {
                text = s.nome
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF111111.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    setColor(coloreAnagrafica)
                }
                minHeight = dp(46)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            scheda.addView(cellaNome, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val cellaOre = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            // La fila dei nove indicatori occupa metà della larghezza disponibile.
            val filaPallini = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            repeat(9) { ora ->
                val pallino = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(coloreOraPerStudente(statiOre, s.id, ora))
                    }
                    contentDescription = "Ora ${ora + 1}: ${descrizioneOra(statiOre[s.id]?.getOrNull(ora) ?: -1)}"
                }
                filaPallini.addView(pallino, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    if (ora > 0) leftMargin = dp(3)
                })
            }
            cellaOre.addView(filaPallini, LinearLayout.LayoutParams(0, dp(12), 1f))
            cellaOre.addView(View(ctx), LinearLayout.LayoutParams(0, dp(1), 1f))
            scheda.addView(cellaOre, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            lista.addView(scheda, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        if (!caricamento && grezzo != null) {
            copiaRisposta(
                "copia la risposta di classeviva di questo giorno",
                grezzo.first,
                "giorno ${formatoApi.format(selezionato.time)}\n${grezzo.second}"
            )
        }
    }

    /**
     * Legge le presenze per ora dalla risposta lessons2/students.
     * Ogni lezione contiene studentStatus: lo stato può arrivare come codice ClasseViva
     * (es. HAB = assente) oppure come descrizione testuale.
     * Valori normalizzati: 0 presente, 1 ritardo/uscita, 2 assente, -1 non disponibile.
     */
    private fun leggiStatiOre(grezzo: String): Map<String, List<Int>> {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return emptyMap()
        val risultati = mutableMapOf<String, MutableList<Int>>()

        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (chiave in chiavi) {
                val v = o.opt(chiave)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }

        fun classifica(v: String): Int {
            val s = v.trim().uppercase()
            return when {
                s.isEmpty() -> -1
                s.contains("NOLESSON") || s.contains("NO_LESSON") -> -1
                s.contains("ABSENT") || s.contains("ASSENT") || s == "AL" || s.startsWith("HAB") || s.startsWith("ABA") -> 2
                s.contains("LATE") || s.contains("LEAV") || s.contains("RITARD") || s.contains("USCIT") ||
                    s.startsWith("ABR") || s.startsWith("ABU") || s.startsWith("AB") -> 1
                s.contains("PRESENT") || s.contains("PRESEN") || s == "PL" ||
                    s.startsWith("HPR") || s.startsWith("AT") -> 0
                else -> -1
            }
        }

        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val ore = v.optJSONArray("studentStatus")
                    if (ore != null) {
                        val posizione = testo(v, "evtHPos", "hourPos", "evtHour", "hour", "lessonHour").toIntOrNull()
                        if (posizione != null && posizione in 1..9) {
                            for (i in 0 until ore.length()) {
                                val record = ore.optJSONObject(i) ?: continue
                                val studente = record.optJSONObject("student") ?: record
                                val id = testo(record, "studentId", "student_id", "pupilId", "id")
                                    .ifEmpty { testo(studente, "studentId", "student_id", "pupilId", "id") }
                                if (id.isEmpty()) continue
                                val statoRaw = testo(record, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc")
                                    .ifEmpty { testo(studente, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc") }
                                val stato = classifica(statoRaw)
                                val listaOre = risultati.getOrPut(id) { MutableList(9) { -1 } }
                                // Se la risposta contiene più firme nella stessa ora, l'assenza prevale.
                                listaOre[posizione - 1] = when {
                                    stato == 2 -> 2
                                    stato == 1 && listaOre[posizione - 1] != 2 -> 1
                                    stato == 0 && listaOre[posizione - 1] == -1 -> 0
                                    else -> listaOre[posizione - 1]
                                }
                            }
                        }
                    }
                    for (k in v.keys()) {
                        if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                    }
                }
            }
        }
        cammina(radice, 0)
        return risultati
    }

    private fun coloreOraPerStudente(statiOre: Map<String, List<Int>>, studentId: String, ora: Int): Int {
        return when (statiOre[studentId]?.getOrNull(ora) ?: -1) {
            0 -> verdeOra
            1 -> gialloOra
            2 -> rosaOra
            else -> grigioOra
        }
    }

    private fun descrizioneOra(stato: Int): String = when (stato) {
        0 -> "presente"
        1 -> "ritardo o uscita anticipata"
        2 -> "assente"
        else -> "stato non disponibile"
    }

    private fun testo(s: String, codice: Int = 0, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(requireContext()).apply {
            text = s; textSize = 15f; setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(24), dp(8), 0)
        })
        if (corpo.isNotEmpty()) copiaRisposta("mostra la risposta di classeviva (tocca per copiare)", codice, corpo)
    }

    private fun copiaRisposta(etichetta: String, codice: Int, corpo: String) {
        lista.addView(TextView(requireContext()).apply {
            text = etichetta; textSize = 14f; setTextColor(azzurro)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
