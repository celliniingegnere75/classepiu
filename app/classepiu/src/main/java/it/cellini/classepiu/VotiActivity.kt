package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voti della classe: per ogni alunno una casella con le righe orale, scritto/grafico, test (solo quelle che hanno voti).
 * I voti scorrono in orizzontale; toccandone uno si apre data e annotazioni per la famiglia, modificabili.
 */
class VotiActivity : AppCompatActivity() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private var tipo = ""
    private var classeId = ""
    private var primoGiorno: java.util.Calendar = java.util.Calendar.getInstance().apply { // se le lezioni non si leggono: 1 settembre
        val o = java.util.Calendar.getInstance(); set(if (o.get(java.util.Calendar.MONTH) >= java.util.Calendar.SEPTEMBER) o.get(java.util.Calendar.YEAR) else o.get(java.util.Calendar.YEAR) - 1, java.util.Calendar.SEPTEMBER, 1)
    }
    private var elenco: List<Studente> = emptyList()
    private var tutti: Map<String, List<Voto>> = emptyMap()
    private val memoria by lazy { getSharedPreferences("voti_modificati", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = azzurro
        val nome = intent.getStringExtra("nome") ?: ""
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = this

        val chiudi = TextView(ctx).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val titolo = TextView(ctx).apply {
            text = "voti $nome"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
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
        testo("carico i voti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Impostazioni(this@VotiActivity).client()
                    c.studentiGrezzi(tipo, classeId) to c.votiGrezzi(tipo, classeId)
                }
            }
            // primo giorno di scuola: la prima lezione registrata dall'inizio di settembre
            launch(Dispatchers.IO) {
                runCatching {
                    val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ITALIAN)
                    val oggi = java.util.Calendar.getInstance()
                    val (c, t) = Impostazioni(this@VotiActivity).client().lezioniGrezze(f.format(primoGiorno.time), f.format(oggi.time))
                    if (c == 200) Regex("\"evtDate\"\\s*:\\s*\"(\\d{4})-(\\d{2})-(\\d{2})").findAll(t)
                        .map { it.groupValues.drop(1).joinToString("") }.minOrNull()?.let { primoGiorno.time = f.parse(it)!! }
                }
            }
            val (st, vt) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            val studenti = if (st.first == 200) leggiStudenti(st.second) else emptyList()
            if (vt.first != 200) { testo("classeviva non ha dato i voti (http ${vt.first}).", vt.second); return@launch }
            val voti = leggiVoti(vt.second)
            if (studenti.isEmpty()) { testo("nessuno studente riconosciuto.", st.second); return@launch }
            if (voti.isEmpty()) { testo("nessun voto riconosciuto nella risposta.", vt.second); return@launch }
            elenco = studenti; tutti = voti
            mostra(studenti, voti)
        }
    }

    private fun mostra(studenti: List<Studente>, voti: Map<String, List<Voto>>) {
        lista.removeAllViews()
        lista.addView(TextView(this).apply {
            text = "copia i voti ammessi"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(4), dp(8), dp(12))
            setOnClickListener {
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(this@VotiActivity).client().componentiGrezzi(tipo, classeId) } }
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("risposta", r.getOrElse { "non riuscito: ${it.message}" }))
                    android.widget.Toast.makeText(this@VotiActivity, if (r.isSuccess) "copiata, incollala nella chat" else "non riuscito", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
        val ctx = this
        // righe sempre in questo ordine; altri tipi di prova (es. pratico) seguono con il proprio nome
        fun gruppo(t: String) = when {
            t.lowercase().contains("oral") -> "orale"
            t.lowercase().contains("scritt") || t.lowercase().contains("grafic") -> "scritto"
            t.lowercase().contains("pratic") -> "pratico"
            t.lowercase().contains("test") -> "test"
            else -> t.lowercase()
        }
        val ordine = listOf("orale", "scritto", "pratico", "test")
        studenti.forEach { s ->
            val suoi = (voti[s.id] ?: emptyList()).groupBy { gruppo(it.tipo) }
            val scatola = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                addView(TextView(ctx).apply { text = s.nome; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            val righe = ordine.filter { suoi.containsKey(it) } + suoi.keys.filter { it !in ordine }
            if (righe.isEmpty()) scatola.addView(TextView(ctx).apply { text = "nessun voto"; textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, dp(4), 0, 0) })
            righe.forEach { g ->
                // intestazione fissa a sinistra, voti che scorrono a destra sulla stessa riga
                val chip = LinearLayout(ctx)
                suoi[g].orEmpty().sortedBy { it.data }.forEach { v ->
                    chip.addView(chipVoto(modificato(v)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
                }
                scatola.addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply { text = g; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colorePerRiga(g)) },
                        LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chip) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            lista.addView(scatola, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    /** 5½ = 5,5; 5+ = 5,25; 6- = 5,75; i numeri semplici restano tali. */
    private fun numeroDa(v: String): Double? {
        val t = v.trim().replace(',', '.')
        t.toDoubleOrNull()?.let { return it }
        val base = t.trimEnd('+', '-', '½').toDoubleOrNull() ?: return null
        return base + when (t.last()) { '½' -> 0.5; '+' -> 0.25; '-' -> -0.25; else -> 0.0 }
    }

    private fun colorePerRiga(g: String) = when (g) { "orale" -> 0xFF4CAF7D.toInt(); "scritto" -> azzurro; "pratico" -> 0xFF9B7FD4.toInt(); "test" -> 0xFFF0A040.toInt(); else -> 0xFF8E8E8E.toInt() }

    private fun coloreVoto(n: Double?) = when {
        n == null -> 0xFF8E8E8E.toInt()
        n < 5 -> 0xFFE53935.toInt()
        n < 6 -> 0xFFF0A040.toInt()
        else -> 0xFF2EB82E.toInt()
    }

    private fun chipVoto(v: Voto) = TextView(this).apply {
        text = v.valore; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0); minWidth = dp(46)
        background = GradientDrawable().apply { setColor(coloreVoto(v.numero)); cornerRadius = dp(12).toFloat() }
        setOnClickListener { apriVoto(v) }
    }

    /** Data e annotazioni modificate a mano sono tenute sul telefono (non vengono scritte su classeviva). */
    private fun modificato(v: Voto) = v.copy(
        valore = memoria.getString("${v.chiave}|valore", null) ?: v.valore,
        numero = numeroDa(memoria.getString("${v.chiave}|valore", null) ?: v.valore) ?: v.numero,
        data = memoria.getString("${v.chiave}|data", null) ?: v.data,
        nota = memoria.getString("${v.chiave}|nota", null) ?: v.nota
    )

    private fun apriVoto(v: Voto) {
        val ctx = this
        fun campo(valore: String, suggerimento: String, righe: Int) = EditText(ctx).apply {
            setText(valore); hint = suggerimento; textSize = 15f
            if (righe > 1) { minLines = righe; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        }
        fun etichetta(t: String) = TextView(ctx).apply { text = t; textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(10), 0, dp(2)) }
        // voto scelto da elenco, come sul registro: 5, 5+, 5½, 6- (il mezzo punto si scrive ½, il 5,75 è 6-)
        var valoreScelto = v.valore
        val valore = TextView(ctx).apply {
            text = valoreScelto; textSize = 15f; setTextColor(Color.BLACK); setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                val scelte = (1..10).flatMap { n -> listOf("$n-", "$n", "$n+", "$n½").filter { n < 10 || it == "10" || it == "10-" } }.drop(1)
                val voci = if (valoreScelto in scelte || valoreScelto.isEmpty()) scelte else listOf(valoreScelto) + scelte
                MaterialAlertDialogBuilder(ctx).setTitle("voto")
                    .setItems(voci.toTypedArray()) { _, i -> valoreScelto = voci[i]; text = valoreScelto }
                    .show()
            }
        }
        val fIt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ITALIAN)
        val fApi = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALIAN)
        var scelta: java.util.Date? = runCatching { fApi.parse(v.data.take(10)) }.getOrNull()
        val data = TextView(ctx).apply {
            text = scelta?.let { fIt.format(it) } ?: "scegli la data"; textSize = 15f; setTextColor(Color.BLACK)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                val c = java.util.Calendar.getInstance().apply { time = scelta ?: java.util.Date() }
                val d = android.app.DatePickerDialog(ctx, { _, a, m, g ->
                    scelta = java.util.Calendar.getInstance().apply { set(a, m, g) }.time
                    text = fIt.format(scelta!!)
                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH))
                d.datePicker.minDate = primoGiorno.timeInMillis // non prima del primo giorno di scuola
                d.show()
            }
        }
        val nota = campo(v.nota, "annotazioni per la famiglia", 3)
        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0)
            addView(TextView(ctx).apply { text = "${v.valore}  ·  ${v.tipo.lowercase()}"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(coloreVoto(v.numero)) })
            addView(etichetta("voto")); addView(valore)
            addView(etichetta("data")); addView(data)
            addView(etichetta("annotazioni per la famiglia")); addView(nota)
        }
        MaterialAlertDialogBuilder(ctx)
            .setView(corpo)
            .setNegativeButton("annulla", null)
            .setPositiveButton("salva") { _, _ ->
                val dataApi = scelta?.let { fApi.format(it) } ?: v.data
                salvaSuClasseviva(v, valoreScelto, dataApi, nota.text.toString())
                memoria.edit().putString("${v.chiave}|valore", valoreScelto).putString("${v.chiave}|data", dataApi).putString("${v.chiave}|nota", nota.text.toString()).apply()
                mostra(elenco, tutti)
            }
            .show()
    }

    /** Prova a scrivere la modifica su classeviva; se non riesce resta salvata sul telefono e lo si dice. */
    private fun salvaSuClasseviva(v: Voto, valore: String, data: String, nota: String) {
        if (v.grezzo.isEmpty()) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(this@VotiActivity).client().salvaVoto(v.grezzo, valore, data, nota) } }
            val msg = r.getOrNull()?.let { (c, t) -> if (c in 200..299) "salvato su classeviva" else "classeviva non ha accettato (http $c): ${t.take(80)}; resta sul telefono" }
                ?: "non riuscito: ${r.exceptionOrNull()?.message}; resta sul telefono"
            android.widget.Toast.makeText(this@VotiActivity, msg, android.widget.Toast.LENGTH_LONG).show()
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
