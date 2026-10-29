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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Elenco studenti con scheda divisa in anagrafica e indicatori delle nove ore. */
// Build verificata: lettura dello stato giornaliero e della risposta lessons2/students.
class StudentiScheda : Scheda() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var outfitRegular: Typeface
    private lateinit var outfitBold: Typeface
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
        outfitRegular = Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf")
        outfitBold = Typeface.createFromAsset(ctx.assets, "outfit_bold.ttf")

        val chiudi = TextView(ctx).apply {
            text = "✕"; textSize = 22f; typeface = outfitRegular; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        val titolo = TextView(ctx).apply {
            text = nome; textSize = 22f; typeface = outfitBold; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        val giorni = StrisciaGiorni(ctx) {
            caricaStato()
            ridisegna()
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

    /** Ultimi dati del giorno selezionato, ridisegnati quando arriva l'elenco studenti o gli orari del popup. */
    private data class DatiGiorno(
        val stato: Map<String, StatoGiornaliero>,
        val statiOre: Map<String, List<Int>>,
        val numeroOre: Int,
        val grezzo: Pair<Int, String>?
    )
    private var datiGiorno: DatiGiorno? = null

    // Un solo client per tutta la scheda: l'archivio cifrato si apre una volta e il token si riusa.
    private val client by lazy { Impostazioni(requireContext().applicationContext).client() }

    private fun fileStudenti() = java.io.File(
        requireContext().filesDir, "studenti_${tipo.ifEmpty { "ordinary" }}_${classeId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json"
    )

    private fun ridisegna() {
        if (studenti.isEmpty()) return
        val d = datiGiorno
        if (d == null) mostra(emptyMap(), caricamento = true)
        else mostra(d.stato, statiOre = d.statiOre, numeroOre = d.numeroOre, caricamento = false, grezzo = d.grezzo)
    }

    /**
     * Mostra subito l'elenco salvato l'ultima volta (se c'è), poi scarica elenco e presenze del giorno
     * in parallelo: prima si aspettava l'elenco e solo dopo partivano le presenze.
     */
    private fun carica() {
        studenti = runCatching { leggiStudenti(fileStudenti().readText()) }.getOrDefault(emptyList())
        if (studenti.isEmpty()) testo("carico gli studenti…") else ridisegna()
        caricaStato()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { client.studentiGrezzi(tipo, classeId) }
            }
            val (codice, corpo) = r.getOrElse {
                if (studenti.isEmpty()) testo("non riuscito: ${it.message}")
                return@launch
            }
            val nuovi = if (codice == 200) leggiStudenti(corpo) else emptyList()
            if (nuovi.isEmpty()) {
                if (studenti.isNotEmpty()) return@launch
                testo(
                    if (codice == 200) "risposta ricevuta ma nessuno studente riconosciuto."
                    else "classeviva ha risposto con un errore (http $codice).",
                    codice,
                    corpo
                )
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { fileStudenti().writeText(corpo) } }
            if (nuovi != studenti) {
                studenti = nuovi
                ridisegna()
            }
        }
    }

    private fun caricaStato() {
        lavoro?.cancel()
        datiGiorno = null
        val giorno = formatoApi.format(selezionato.time)
        lavoro = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // Le due letture sono indipendenti: eseguirle in parallelo evita di sommare
                    // i tempi di attesa delle due richieste HTTP.
                    coroutineScope {
                        val giornaliero = async { client.statoGrezzo(tipo, classeId, giorno) }
                        val lezioni = async { client.lezioniClasseGrezze(tipo, classeId, giorno) }
                        giornaliero.await() to lezioni.await()
                    }
                }
            }
            val coppia = r.getOrNull()
            var stato = coppia?.first?.let { (c, t) -> if (c == 200) leggiStato(t) else emptyMap() } ?: emptyMap<String, StatoGiornaliero>()
            // Lo stato giornaliero (status2) sembra tenere una sola annotazione per studente per giorno:
            // se ritardo breve e uscita anticipata capitano lo stesso giorno, la seconda registrata può
            // sovrascrivere la prima lì. Le presenze per ora (lessons2/students) sono invece indipendenti
            // ora per ora, quindi le usiamo come seconda fonte e uniamo i due segnali con un OR, senza
            // mai togliere un'etichetta già vista nello stato giornaliero.
            val eventiOra = coppia?.second?.let { (c, t) -> if (c == 200) leggiEventiOraIndipendenti(t) else emptyMap() } ?: emptyMap()
            if (eventiOra.isNotEmpty()) {
                stato = stato.toMutableMap().apply {
                    for ((id, evento) in eventiOra) {
                        val (haRitardo, haUscita) = evento
                        if (!haRitardo && !haUscita) continue
                        val precedente = this[id] ?: StatoGiornaliero(1)
                        this[id] = precedente.copy(
                            isRitardoBreve = precedente.isRitardoBreve || haRitardo,
                            isUscita = precedente.isUscita || haUscita
                        )
                    }
                }
            }
            val statiOre = coppia?.second?.let { (c, t) -> if (c == 200) leggiStatiOre(t) else emptyMap() } ?: emptyMap()
            val numeroOre = coppia?.second?.let { (c, t) -> if (c == 200) leggiNumeroOre(t) else 9 } ?: 9
            fun diagnostica(popup: String) = coppia?.let { (g, l) ->
                (g.first to "STATO GIORNALIERO HTTP ${g.first}\n${g.second}\n\nLEZIONI E PRESENZE PER ORA HTTP ${l.first}\n${l.second}$popup")
            }
            // Presenze e pallini si vedono subito; gli orari di Rb/U dal registro web arrivano dopo.
            datiGiorno = DatiGiorno(stato, statiOre, numeroOre, diagnostica(""))
            ridisegna()

            // Per ABR1 (e per ABU0 se manca l'ora) l'API REST non restituisce l'ora: la leggiamo dal popup web
            // in sola lettura. Il popup ha un campo orario per il ritardo e uno per l'uscita (distinti
            // dall'aria-label), quindi li recuperiamo entrambi anche quando capitano lo stesso giorno.
            val ritardiSenzaOra = stato.filterValues { it.isRitardoBreve && it.ritardoBreve == null }.keys
            val usciteSenzaOra = stato.filterValues { it.isUscita && it.uscita == null }.keys
            if (ritardiSenzaOra.isEmpty() && usciteSenzaOra.isEmpty()) return@launch
            // Tenuto qui (non dentro il blocco IO) così arriva comunque nella diagnostica anche se
            // la lettura fallisce o non trova niente: serve per vedere l'HTML grezzo del popup web.
            val debugOrari = StringBuilder()
            val orari = withContext(Dispatchers.IO) {
                runCatching {
                    client.orariEventoWeb(classeId, giorno, ritardiSenzaOra, usciteSenzaOra, debugOrari)
                }.getOrDefault(emptyMap())
            }
            if (orari.isNotEmpty()) {
                stato = stato.mapValues { (id, valore) ->
                    val trovato = orari[id] ?: return@mapValues valore
                    valore.copy(
                        ritardoBreve = valore.ritardoBreve ?: trovato.ritardo,
                        uscita = valore.uscita ?: trovato.uscita
                    )
                }
            }
            val popup = if (debugOrari.isEmpty()) "" else "\n\nPOPUP WEB ORARIO RITARDO/USCITA\n$debugOrari"
            datiGiorno = DatiGiorno(stato, statiOre, numeroOre, diagnostica(popup))
            ridisegna()
        }
    }

    private fun mostra(stato: Map<String, StatoGiornaliero>, statiOre: Map<String, List<Int>> = emptyMap(), numeroOre: Int = 9, caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = requireContext()

        studenti.forEach { s ->
            val statoStudente = stato[s.id] ?: StatoGiornaliero(-1)
            val valore = statoStudente.valore
            val coloreAnagrafica = when {
                caricamento -> 0xFFFCFCFC.toInt()
                valore == 2 -> rosaAssente
                valore == 0 || valore == 1 -> verdePresente
                else -> 0xFFEEEEEE.toInt()
            }

            // Scheda unica con due celle sovrapposte: anagrafica sopra, ore effettive del giorno sotto.
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
                typeface = outfitBold
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

            // Mostra solo le ore effettivamente presenti nel registro per la giornata selezionata.
            val filaPallini = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            repeat(numeroOre.coerceIn(1, 9)) { ora ->
                val pallino = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(coloreOraPerStudente(statiOre, s.id, ora))
                    }
                    contentDescription = "Ora ${ora + 1}: ${descrizioneOra(statiOre[s.id]?.getOrNull(ora) ?: -1)}"
                }
                filaPallini.addView(pallino, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    if (ora > 0) leftMargin = dp(3)
                })
            }
            cellaOre.addView(filaPallini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16)))
            if (statoStudente.isRitardoBreve) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "Rb" + (statoStudente.ritardoBreve?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Ritardo breve" + (statoStudente.ritardoBreve?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)))
            }
            if (statoStudente.isUscita) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "U" + (statoStudente.uscita?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Uscita anticipata" + (statoStudente.uscita?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)))
            }
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
                    s.startsWith("HAT") || s.startsWith("HPR") || s.startsWith("AT") -> 0
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

    /**
     * Ritardo breve e uscita anticipata letti ora per ora dalla stessa risposta di [leggiStatiOre]
     * (lessons2/students), invece che dal codice unico dello stato giornaliero: ogni ora ha la sua
     * firma, quindi un ritardo alla prima ora e un'uscita all'ultima restano due segnali distinti
     * anche se lo stato del giorno ne mostrasse solo uno. Ritorna per studente (ha ritardo, ha uscita).
     */
    private fun leggiEventiOraIndipendenti(grezzo: String): Map<String, Pair<Boolean, Boolean>> {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return emptyMap()
        val risultati = mutableMapOf<String, MutableList<Boolean>>() // [haRitardo, haUscita]

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

        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val ore = v.optJSONArray("studentStatus")
                    if (ore != null) {
                        for (i in 0 until ore.length()) {
                            val record = ore.optJSONObject(i) ?: continue
                            val studente = record.optJSONObject("student") ?: record
                            val id = testo(record, "studentId", "student_id", "pupilId", "id")
                                .ifEmpty { testo(studente, "studentId", "student_id", "pupilId", "id") }
                            if (id.isEmpty()) continue
                            val statoRaw = testo(record, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc")
                                .ifEmpty { testo(studente, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc") }
                                .uppercase()
                            val haRitardo = statoRaw.contains("RITARD") || statoRaw.startsWith("ABR")
                            val haUscita = statoRaw.contains("USCIT") || statoRaw.contains("LEAV") || statoRaw.startsWith("ABU")
                            if (!haRitardo && !haUscita) continue
                            val voci = risultati.getOrPut(id) { mutableListOf(false, false) }
                            if (haRitardo) voci[0] = true
                            if (haUscita) voci[1] = true
                        }
                    }
                    for (k in v.keys()) {
                        if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                    }
                }
            }
        }
        cammina(radice, 0)
        return risultati.mapValues { (_, voci) -> voci[0] to voci[1] }
    }

    /** Numero di ore effettive: usa evtHPos + evtDuration, anche se studentStatus è null. */
    private fun leggiNumeroOre(grezzo: String): Int {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return 9
        var massimo = 0
        fun cammina(v: Any?, livello: Int = 0) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val codice = v.opt("evtHPos")?.toString()?.toIntOrNull()
                    val durata = v.opt("evtDuration")?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    if (v.has("studentStatus") && codice != null && codice in 1..9) massimo = maxOf(massimo, (codice + durata - 1).coerceAtMost(9))
                    for (k in v.keys()) cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice)
        return if (massimo in 1..9) massimo else 9
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
            text = s; textSize = 15f; typeface = outfitRegular; setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(24), dp(8), 0)
        })
        if (corpo.isNotEmpty()) copiaRisposta("mostra la risposta di classeviva (tocca per copiare)", codice, corpo)
    }

    private fun copiaRisposta(etichetta: String, codice: Int, corpo: String) {
        lista.addView(TextView(requireContext()).apply {
            text = etichetta; textSize = 14f; typeface = outfitRegular; setTextColor(azzurro)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
