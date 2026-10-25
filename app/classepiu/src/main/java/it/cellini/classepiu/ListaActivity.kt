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
                    if (cosa == "note") c.noteGrezze(tipo, classeId, f.format(inizio.time), f.format(oggi.time))
                    else c.lezioniGrezze(f.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }.time), f.format(oggi.time))
                }
            }
            val (codice, corpo) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            if (codice != 200) { testo("classeviva ha risposto con un errore (http $codice).", corpo); return@launch }
            val voci = leggiVoci(corpo)
            if (voci.isEmpty()) { testo(if (cosa == "note") "nessuna nota." else "nessuna lezione negli ultimi 30 giorni.", corpo); return@launch }
            mostra(voci)
        }
    }

    private class Voce(val data: String, val titolo: String, val testo: String)

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
                        out.add(Voce(data, listOf(v.t("subjectDesc"), chi).filter { it.isNotEmpty() }.distinct().joinToString(" · "), corpo))
                    } else for (k in v.keys()) cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice, 0)
        return out.sortedByDescending { it.data }
    }

    private fun formatta(d: String): String {
        val n = d.take(10).replace("-", "")
        return runCatching { SimpleDateFormat("EEE d MMM yyyy", Locale.ITALIAN).format(SimpleDateFormat("yyyyMMdd", Locale.ITALIAN).parse(n)!!) }.getOrDefault(d)
    }

    private fun mostra(voci: List<Voce>) {
        lista.removeAllViews()
        val ctx = this
        val colore = if (cosa == "note") 0xFFF0A040.toInt() else azzurro
        voci.forEach { v ->
            lista.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                addView(TextView(ctx).apply { text = formatta(v.data); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore) })
                if (v.titolo.isNotEmpty()) addView(TextView(ctx).apply { text = v.titolo; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setPadding(0, dp(2), 0, 0) })
                addView(TextView(ctx).apply { text = v.testo; textSize = 14f; setTextColor(0xFF333333.toInt()); setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
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
