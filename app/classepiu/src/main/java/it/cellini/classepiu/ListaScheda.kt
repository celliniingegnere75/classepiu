package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Note o lezioni di una classe, dal giorno 1 settembre dell'anno scolastico a oggi, dalla più recente. Campi dedotti, lettura tollerante. */
class ListaScheda : Scheda() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private var cosa = ""
    private var tipo = ""
    private var classeId = ""
    /** Lezioni: striscia dei giorni come nell'elenco degli alunni; il giorno scelto è quello al centro. */
    private var striscia: StrisciaGiorni? = null
    private val giorno: Calendar get() = striscia?.selezionato ?: Calendar.getInstance()
    private var mioId = ""
    private var nome = ""
    /** Classe articolata: gli alunni del mio gruppo, per mostrare solo le firme della mia metà (vuoto = tutte). */
    private var mieiAlunni: Set<String> = emptySet()

    override fun crea(savedInstanceState: Bundle?) {
        cosa = intent.getStringExtra("cosa") ?: "note"
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        nome = intent.getStringExtra("nome") ?: ""
        val ctx = requireContext()
        val chiudi = TextView(ctx).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val titolo = TextView(ctx).apply {
            text = "$cosa $nome"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.view.View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16 + SPAZIO_BARRA)) }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        if (cosa == "lezioni") striscia = StrisciaGiorni(ctx) { carica() }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra)
            striscia?.aggiungiA(this)
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (striscia == null) topMargin = dp(10) })
        })
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, indietro)
        carica()
    }

    private fun carica() {
        testo("carico $cosa…")
        val f = SimpleDateFormat("yyyyMMdd", Locale.ITALIAN)
        val oggi = Calendar.getInstance()
        val inizio = Calendar.getInstance().apply { set(if (oggi.get(Calendar.MONTH) >= Calendar.SEPTEMBER) oggi.get(Calendar.YEAR) else oggi.get(Calendar.YEAR) - 1, Calendar.SEPTEMBER, 1) }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Impostazioni(requireContext()).client()
                    mioId = c.idDocente()
                    // grandezza della classe: serve a riconoscere le note di classe (uguali per quasi tutti gli alunni)
                    // le note arrivano con l'id dell'alunno: i nomi vengono dall'elenco studenti
                    if (cosa == "note") runCatching { leggiStudenti(c.studentiGrezzi(tipo, classeId).second) }.getOrDefault(emptyList()).let { st ->
                        dimClasse = st.size; nomi = st.associate { it.id to it.nome }
                    }
                    if (cosa == "note") c.noteGrezze(tipo, classeId, f.format(inizio.time), f.format(oggi.time))
                    else {
                        mieiAlunni = FiltroArticolate.mieiAlunni(c, requireContext().filesDir, tipo, classeId, nome)
                        c.lezioniClasseGrezze(tipo, classeId, f.format(giorno.time))
                    }
                }
            }
            val (codice, corpo) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            if (codice != 200) { testo("classeviva ha risposto con un errore (http $codice).", corpo); return@launch }
            grezzo = corpo
            val voci = leggiVoci(corpo)
            if (voci.isEmpty()) { testo(if (cosa == "note") "nessuna nota." else "nessuna lezione in questo giorno.", corpo); return@launch }
            if (cosa == "note") { tutte = voci; mostraNote() } else mostra(voci)
        }
    }

    /** Una nota o una lezione; per le lezioni [grezzo] è l'oggetto di classeviva e [mia] dice se l'ho firmata io. */
    private class Voce(val data: String, val titolo: String, val testo: String, val alunno: String = "", val tipo: String = "", val ordine: Int = 0,
                       val grezzo: String = "", val mia: Boolean = false, val firma: FiltroArticolate.Firma? = null)
    private var grezzo = ""
    private var dimClasse = 0
    private var nomi: Map<String, String> = emptyMap()
    private var tutte: List<Voce> = emptyList()
    private var inClasse = true
    private var alunnoAperto: String? = null
    private val indietro = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { alunnoAperto = null; isEnabled = false; mostraNote() }
    }

    private fun leggiVoci(grezzo: String): List<Voce> {
        val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Voce>()
        fun JSONObject.t(vararg ks: String): String {
            for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
            return ""
        }
        fun cammina(v: Any?, livello: Int) {
            if (livello > 5) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val data = v.t("evtDate", "date", "noteDate", "lessonDate", "day")
                    val corpo = v.t("evtText", "noteText", "text", "note", "notes", "lessonArg", "lessonDesc", "description")
                    if (cosa == "lezioni") {
                        // lessons2/students restituisce le firme di tutti i docenti della classe.
                        // La materia non è obbligatoria: alcune firme, inclusa Educazione civica,
                        // possono avere i dati della materia in forma diversa o non esporre subjectDesc.
                        val docente = v.optJSONObject("author") ?: v.optJSONObject("teacher") ?: v.optJSONObject("signedBy")
                        val materiaObj = v.optJSONObject("subject")
                        val materia = v.t("subjectDesc", "subjectName", "subjectCode", "subject")
                            .ifEmpty { materiaObj?.t("subjectDesc", "subjectName", "description", "name").orEmpty() }
                        val firma = v.t("authorName", "teacherName", "signedBy", "teacher", "author")
                            .ifEmpty { docente?.t("name", "fullName", "teacherName", "authorName").orEmpty() }
                        val autoreId = v.t("authorId", "teacherId", "signedById", "author_id", "teacher_id")
                            .ifEmpty { docente?.t("id", "teacherId", "authorId").orEmpty() }
                        val ora = v.t("evtHPos", "hourPos", "evtHour", "hour")
                        val codiceEvento = v.t("evtCode", "eventCode")
                        val eFirma = codiceEvento.equals("LSF0", true) ||
                            (firma.isNotEmpty() && (ora.isNotEmpty() || corpo.isNotEmpty() || materia.isNotEmpty()))
                        if (eFirma) {
                            val tipoLezione = v.t("lessonType").takeIf { it.isNotEmpty() && !it.equals("Lezione", ignoreCase = true) }
                            val stati = v.optJSONArray("studentStatus")
                            val assenti = stati?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optString("status", "")?.startsWith("HAB") == true } } ?: 0
                            val riga = listOfNotNull(if (firma.isNotEmpty()) "firmata da $firma" else null, tipoLezione?.lowercase(), if (assenti > 0) "$assenti assent${if (assenti == 1) "e" else "i"}" else null).joinToString(" · ")
                            // L'editabilità dipende esclusivamente dall'ID del docente, mai dalla materia:
                            // quindi sono modificabili anche le proprie firme di Educazione civica.
                            val mia = mioId.isNotEmpty() && autoreId == mioId
                            val titolo = listOf(if (ora.isNotEmpty()) "${ora}ª ora" else "", materia)
                                .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "firma di lezione" }
                            val alunni = stati?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.t("studentId") }.toSet() }.orEmpty()
                            out.add(Voce(data.ifEmpty { "" }, titolo, corpo.ifEmpty { "nessun argomento" }, "", riga, ora.toIntOrNull() ?: 99,
                                v.toString(), mia, FiltroArticolate.Firma(ora, materia, autoreId, alunni)))
                        } else for (k in v.keys()) cammina(v.opt(k), livello + 1)
                        return
                    }
                    if (data.isNotEmpty() && corpo.isNotEmpty()) {
                        // la classe: nei dati del docente le lezioni di tutte le classi sono insieme
                        val daClasse = v.t("classId", "classroomId")
                        if (cosa == "lezioni" && daClasse.isNotEmpty() && daClasse != classeId) return
                        val chi = v.t("studentName", "authorName", "teacherName", "subjectDesc", "lessonType", "classDesc", "evtCode")
                        // gli "appunto" (NTNT) sono le autorizzazioni di entrata/uscita: per ora esclusi
                        if (cosa == "note" && v.t("evtCode") == "NTNT") return
                        val sid = v.t("studentId", "student_id")
                        val alunno = nomi[sid] ?: v.t("studentName", "fullName", "completeName").ifEmpty {
                            listOf(v.t("studentLastName", "lastName", "surname"), v.t("studentFirstName", "firstName", "name")).filter { it.isNotEmpty() }.joinToString(" ")
                        }.ifEmpty { sid }
                        // tipo come lo chiama classeviva (nota insegnante, nota nel registro, richiamo…), con autore, materia e motivo
                        val descrizione = v.t("noteTypeDesc", "noteType", "typeDesc", "evtDesc", "eventDesc", "kind", "evtCode").replaceFirstChar { it.uppercase() }
                        val dettagli = listOf(v.t("warningType"), v.t("subjectDesc")).filter { it.isNotEmpty() }.joinToString(", ")
                        val tipoNota = listOf(descrizione + if (dettagli.isNotEmpty()) " ($dettagli)" else "", v.t("authorName")).filter { it.isNotEmpty() }.joinToString(" · ")
                        out.add(Voce(data, listOf(v.t("subjectDesc"), chi).filter { it.isNotEmpty() }.distinct().joinToString(" · "), corpo, alunno, tipoNota))
                    } else for (k in v.keys()) cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice, 0)
        if (cosa != "lezioni") return out.sortedByDescending { it.data }
        // classe articolata: solo le firme della mia metà (le mie, quelle con i miei alunni e le loro compresenze)
        val firme = out.filter { it.firma != null }
        val vedi = FiltroArticolate.visibili(firme.map { it.firma!! }, mioId, mieiAlunni)
        val nascoste = firme.filterIndexed { i, _ -> !vedi[i] }.toSet()
        return out.filter { it !in nascoste }.sortedBy { it.ordine }
    }

    private fun formatta(d: String): String {
        val n = d.take(10).replace("-", "")
        return runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(SimpleDateFormat("yyyyMMdd", Locale.ITALIAN).parse(n)!!) }.getOrDefault(d)
    }

    private fun voceView(v: Voce, conAlunno: Boolean, colore: Int) = LinearLayout(requireContext()).apply {
        val ctx = context
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        if (cosa == "lezioni") {
            addView(TextView(ctx).apply { text = v.titolo; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()) })
            if (v.tipo.isNotEmpty()) addView(TextView(ctx).apply { text = v.tipo; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore); setPadding(0, dp(2), 0, 0) })
            addView(TextView(ctx).apply { text = v.testo; textSize = 14f; setTextColor(0xFF222222.toInt()); setPadding(0, dp(4), 0, 0) })
            // solo la mia firma si può modificare
            if (v.mia) {
                addView(TextView(ctx).apply { text = "tocca per modificare la firma"; textSize = 12f; setTextColor(azzurro); setPadding(0, dp(6), 0, 0) })
                setOnClickListener { modificaFirma(v) }
            }
            return@apply
        }
        if (v.tipo.isNotEmpty() && cosa == "note") addView(TextView(ctx).apply { text = v.tipo; textSize = 13f; setTextColor(0xFF777777.toInt()) })
        addView(TextView(ctx).apply { text = formatta(v.data); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore) })
        if (!conAlunno && v.titolo.isNotEmpty()) addView(TextView(ctx).apply { text = v.titolo; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setPadding(0, dp(2), 0, 0) })
        addView(TextView(ctx).apply { text = v.testo; textSize = 14f; setTextColor(0xFF222222.toInt()); setPadding(0, dp(4), 0, 0) })
    }

    /** Scatola di una voce; tra le lezioni quelle che posso modificare (le mie) sono più chiare, le altre un po' più grigie. */
    private fun scatola(mia: Boolean? = null) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(when (mia) { true -> 0xFFFFFFFF.toInt(); false -> 0xFFF7F7F7.toInt(); null -> 0xFFFCFCFC.toInt() }); cornerRadius = dp(16).toFloat() }
    }

    private fun parametri() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }

    private fun copiaRisposta() = TextView(requireContext()).apply {
        text = "copia la risposta di classeviva"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(8), dp(8), dp(12))
        setOnClickListener {
            requireContext().getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", grezzo))
            android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostra(voci: List<Voce>) {
        lista.removeAllViews()
        voci.forEach { v -> lista.addView(scatola(v.mia).apply { addView(voceView(v, false, azzurro)) }, parametri()) }
        lista.addView(copiaRisposta())
    }

    private val arancio = 0xFFF0A040.toInt()

    /** Note: linguette "di classe" (note uguali per quasi tutti gli alunni, una volta sola) e "per alunno" (chi ha almeno una nota, anche di classe). */
    private fun mostraNote() {
        lista.removeAllViews()
        val ctx = requireContext()
        val soglia = maxOf(3, Math.ceil(dimClasse * 0.6).toInt())
        // una nota di classe compare uguale (stessa data e testo) a quasi tutta la classe
        val gruppi = tutte.groupBy { it.data.take(10) + "|" + it.testo }
        val diClasse = gruppi.values.filter { g -> g.map { it.alunno }.distinct().size >= soglia }

        val aperto = alunnoAperto
        if (aperto != null) {
            lista.addView(TextView(ctx).apply { text = aperto; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setPadding(dp(4), 0, 0, dp(10)) })
            tutte.filter { it.alunno == aperto }.sortedByDescending { it.data }.forEach { v ->
                lista.addView(scatola().apply { addView(voceView(v, true, arancio)) }, parametri())
            }
            lista.addView(copiaRisposta())
            return
        }

        fun pillola(t: String, attiva: Boolean, azione: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine(true)
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (attiva) azzurro else 0xFFFFFFFF.toInt()) }
            setTextColor(if (attiva) Color.WHITE else 0xFF333333.toInt())
            setOnClickListener { azione() }
        }
        lista.addView(LinearLayout(ctx).apply {
            addView(pillola("di classe", inClasse) { inClasse = true; mostraNote() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
            addView(pillola("per alunno", !inClasse) { inClasse = false; mostraNote() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        if (inClasse) {
            if (diClasse.isEmpty()) lista.addView(TextView(ctx).apply { text = "nessuna nota di classe."; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(12), dp(8), 0) })
            diClasse.map { it.first() }.sortedByDescending { it.data }.forEach { v ->
                lista.addView(scatola().apply { addView(voceView(v, true, arancio)) }, parametri())
            }
        } else {
            val alunni = tutte.map { it.alunno }.filter { it.isNotEmpty() }.distinct().sorted()
            if (alunni.isEmpty()) lista.addView(TextView(ctx).apply { text = "nessun alunno con note."; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(12), dp(8), 0) })
            alunni.forEach { a ->
                val n = tutte.count { it.alunno == a }
                lista.addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                    addView(TextView(ctx).apply { text = a; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(ctx).apply { text = n.toString(); textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(arancio) } }, LinearLayout.LayoutParams(dp(30), dp(30)))
                    setOnClickListener { alunnoAperto = a; indietro.isEnabled = true; mostraNote() }
                }, parametri())
            }
        }
        lista.addView(copiaRisposta())
    }

    /** Finestra per cambiare argomento e tipo della mia lezione; salva subito su classeviva e ricarica il giorno. */
    private fun modificaFirma(v: Voce) {
        val ctx = requireContext()
        val argomento = android.widget.EditText(ctx).apply {
            setText(if (v.testo == "nessun argomento") "" else v.testo); textSize = 15f; minLines = 3; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        // tipo di lezione: si parte da quello della firma; l'elenco dei tipi ammessi si legge da classeviva
        val tipoAttuale = runCatching { org.json.JSONObject(v.grezzo).optString("lessonType") }.getOrDefault("").ifEmpty { "Lezione" }
        var tipoScelto: ClasseViva.TipoLezione? = null
        var tipi: List<ClasseViva.TipoLezione> = emptyList()
        var rispostaTipi = ""
        val tipo = TextView(ctx).apply {
            text = "$tipoAttuale  ▾"; textSize = 15f; setTextColor(0xFF111111.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0xFFF2F2F2.toInt()); cornerRadius = dp(10).toFloat() }
            setOnClickListener {
                if (tipi.isEmpty()) {
                    // elenco non letto: si può copiare la risposta per sistemare la lettura
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setMessage(if (rispostaTipi.isEmpty()) "sto leggendo i tipi di lezione da classeviva..." else "non riesco a leggere i tipi di lezione.")
                        .setNegativeButton("chiudi", null)
                        .apply {
                            if (rispostaTipi.isNotEmpty()) setPositiveButton("copia la risposta") { _, _ ->
                                ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", rispostaTipi))
                            }
                        }.show()
                    return@setOnClickListener
                }
                val nomi = tipi.map { it.nome }.toTypedArray()
                val corrente = nomi.indexOfFirst { it.equals(tipoScelto?.nome ?: tipoAttuale, ignoreCase = true) }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("tipo di lezione")
                    .setSingleChoiceItems(nomi, corrente) { d, i ->
                        tipoScelto = tipi[i]; text = "${tipi[i].nome}  ▾"; d.dismiss()
                    }.show()
            }
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(ctx).client().tipiLezione() } }
            r.onSuccess { (l, g) -> tipi = l; rispostaTipi = g }.onFailure { rispostaTipi = "errore: ${it.message}" }
        }
        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), 0)
            addView(TextView(ctx).apply { text = v.titolo; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()) })
            addView(TextView(ctx).apply { text = "tipo di lezione"; textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(12), 0, dp(4)) })
            addView(tipo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(ctx).apply { text = "argomento della lezione"; textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(12), 0, 0) })
            addView(argomento)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setView(corpo)
            .setNegativeButton("annulla", null)
            .setPositiveButton("salva") { _, _ ->
                val testo = argomento.text.toString().trim()
                val nuovoTipo = tipoScelto?.takeIf { !it.nome.equals(tipoAttuale, ignoreCase = true) }
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(ctx).client().salvaFirma(v.grezzo, testo, nuovoTipo) } }
                    val (codice, risposta) = r.getOrElse { android.widget.Toast.makeText(ctx, "non riuscito: ${it.message}", android.widget.Toast.LENGTH_LONG).show(); return@launch }
                    if (codice in 200..299) {
                        android.widget.Toast.makeText(ctx, "firma salvata su classeviva", android.widget.Toast.LENGTH_SHORT).show()
                        carica()
                    } else {
                        // per sistemare la scrittura serve la risposta di classeviva
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                            .setMessage("classeviva non ha accettato la modifica (http $codice).")
                            .setNegativeButton("chiudi", null)
                            .setPositiveButton("copia la risposta") { _, _ ->
                                requireContext().getSystemService(android.content.ClipboardManager::class.java)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$risposta\n\ninviato:\n${v.grezzo}"))
                            }.show()
                    }
                }
            }.show()
    }

    private fun testo(s: String, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(requireContext()).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
        if (corpo.isNotEmpty()) lista.addView(TextView(requireContext()).apply {
            text = "mostra la risposta di classeviva (tocca per copiare)"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                requireContext().getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", corpo))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
