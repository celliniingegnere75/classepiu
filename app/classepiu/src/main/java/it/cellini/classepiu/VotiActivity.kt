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
        val ctx = this
        // righe sempre in questo ordine; altri tipi di prova (es. pratico) seguono con il proprio nome
        fun gruppo(t: String) = when {
            t.lowercase().contains("oral") -> "orale"
            t.lowercase().contains("scritt") || t.lowercase().contains("grafic") -> "scritto/grafico"
            t.lowercase().contains("test") -> "test"
            else -> t.lowercase()
        }
        val ordine = listOf("orale", "scritto/grafico", "test")
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
                scatola.addView(TextView(ctx).apply { text = g; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colorePerRiga(g)); setPadding(0, dp(8), 0, dp(4)) })
                val chip = LinearLayout(ctx)
                suoi[g].orEmpty().sortedBy { it.data }.forEach { v ->
                    chip.addView(chipVoto(modificato(v)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
                }
                scatola.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chip) })
            }
            lista.addView(scatola, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun colorePerRiga(g: String) = when (g) { "orale" -> 0xFF4CAF7D.toInt(); "scritto/grafico" -> azzurro; "test" -> 0xFFF0A040.toInt(); else -> 0xFF8E8E8E.toInt() }

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
        numero = (memoria.getString("${v.chiave}|valore", null) ?: v.valore).replace(',', '.').toDoubleOrNull(),
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
        val valore = campo(v.valore, "voto", 1)
        val data = campo(v.data, "data", 1)
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
                salvaSuClasseviva(v, valore.text.toString(), data.text.toString(), nota.text.toString())
                memoria.edit().putString("${v.chiave}|valore", valore.text.toString()).putString("${v.chiave}|data", data.text.toString()).putString("${v.chiave}|nota", nota.text.toString()).apply()
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
