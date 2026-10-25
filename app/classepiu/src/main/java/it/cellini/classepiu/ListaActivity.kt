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
import androidx.appcompat.app.AppCompatActivity
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
class ListaActivity : AppCompatActivity() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private var cosa = ""
    private var tipo = ""
    private var classeId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = azzurro
        cosa = intent.getStringExtra("cosa") ?: "note"
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val nome = intent.getStringExtra("nome") ?: ""
        val ctx = this
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
        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16)) }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra)
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        })
        onBackPressedDispatcher.addCallback(this, indietro)
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
                    val c = Impostazioni(this@ListaActivity).client()
                    // grandezza della classe: serve a riconoscere le note di classe (uguali per quasi tutti gli alunni)
                    if (cosa == "note") dimClasse = runCatching { leggiStudenti(c.studentiGrezzi(tipo, classeId).second).size }.getOrDefault(0)
                    if (cosa == "note") c.noteGrezze(tipo, classeId, f.format(inizio.time), f.format(oggi.time))
                    else c.lezioniGrezze(f.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }.time), f.format(oggi.time))
                }
            }
            val (codice, corpo) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            if (codice != 200) { testo("classeviva ha risposto con un errore (http $codice).", corpo); return@launch }
            grezzo = corpo
            val voci = leggiVoci(corpo)
            if (voci.isEmpty()) { testo(if (cosa == "note") "nessuna nota." else "nessuna lezione negli ultimi 30 giorni.", corpo); return@launch }
            if (cosa == "note") { tutte = voci; mostraNote() } else mostra(voci)
        }
    }

    private class Voce(val data: String, val titolo: String, val testo: String, val alunno: String = "", val tipo: String = "")
    private var grezzo = ""
    private var dimClasse = 0
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
                    if (data.isNotEmpty() && corpo.isNotEmpty()) {
                        // la classe: nei dati del docente le lezioni di tutte le classi sono insieme
                        val daClasse = v.t("classId", "classroomId")
                        if (cosa == "lezioni" && daClasse.isNotEmpty() && daClasse != classeId) return
                        val chi = v.t("studentName", "authorName", "teacherName", "subjectDesc", "lessonType", "classDesc", "evtCode")
                        val alunno = v.t("studentName", "fullName", "completeName").ifEmpty {
                            listOf(v.t("studentLastName", "lastName", "surname"), v.t("studentFirstName", "firstName", "name")).filter { it.isNotEmpty() }.joinToString(" ")
                        }
                        val tipoNota = v.t("noteTypeDesc", "noteType", "typeDesc", "evtDesc", "eventDesc", "kind", "evtCode")
                        out.add(Voce(data, listOf(v.t("subjectDesc"), chi).filter { it.isNotEmpty() }.distinct().joinToString(" · "), corpo, alunno, tipoNota))
                    } else for (k in v.keys()) cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice, 0)
        return out.sortedByDescending { it.data }
    }

    private fun formatta(d: String): String {
        val n = d.take(10).replace("-", "")
        return runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(SimpleDateFormat("yyyyMMdd", Locale.ITALIAN).parse(n)!!) }.getOrDefault(d)
    }

    private fun voceView(v: Voce, conAlunno: Boolean, colore: Int) = LinearLayout(this).apply {
        val ctx = context
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        if (v.tipo.isNotEmpty() && cosa == "note") addView(TextView(ctx).apply { text = v.tipo; textSize = 13f; setTextColor(0xFF777777.toInt()) })
        addView(TextView(ctx).apply { text = formatta(v.data); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore) })
        if (!conAlunno && v.titolo.isNotEmpty()) addView(TextView(ctx).apply { text = v.titolo; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setPadding(0, dp(2), 0, 0) })
        addView(TextView(ctx).apply { text = v.testo; textSize = 14f; setTextColor(0xFF222222.toInt()); setPadding(0, dp(4), 0, 0) })
    }

    private fun scatola() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
    }

    private fun parametri() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }

    private fun copiaRisposta() = TextView(this).apply {
        text = "copia la risposta di classeviva"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(8), dp(8), dp(12))
        setOnClickListener {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", grezzo))
            android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostra(voci: List<Voce>) {
        lista.removeAllViews()
        voci.forEach { v -> lista.addView(scatola().apply { addView(voceView(v, false, azzurro)) }, parametri()) }
        lista.addView(copiaRisposta())
    }

    private val arancio = 0xFFF0A040.toInt()

    /** Note: linguette "di classe" (note uguali per quasi tutti gli alunni, una volta sola) e "per alunno" (chi ha almeno una nota, anche di classe). */
    private fun mostraNote() {
        lista.removeAllViews()
        val ctx = this
        val soglia = maxOf(2, Math.ceil(dimClasse * 0.8).toInt())
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

    private fun testo(s: String, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(this).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
        if (corpo.isNotEmpty()) lista.addView(TextView(this).apply {
            text = "mostra la risposta di classeviva (tocca per copiare)"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", corpo))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
