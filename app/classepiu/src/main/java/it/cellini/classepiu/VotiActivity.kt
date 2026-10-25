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
    /** Livello di voto della tabella di classeviva: codice, scritta mostrata e valore decimale. */
    private class Livello(val codice: String, val scritta: String, val decimale: Double)
    private var livelli: List<Livello> = emptyList()
    private var rispostaVoti = ""
    private var mioId = ""
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
                    mioId = c.idDocente()
                    c.studentiGrezzi(tipo, classeId) to c.votiGrezzi(tipo, classeId)
                }
            }
            // tabella dei voti ammessi e periodi (primo giorno di scuola = inizio del primo periodo)
            launch(Dispatchers.IO) {
                runCatching {
                    val t = Impostazioni(this@VotiActivity).client().componentiPrimaMia(tipo, classeId) ?: return@runCatching
                    val o = org.json.JSONObject(t)
                    o.optJSONArray("levels")?.let { a ->
                        livelli = (0 until a.length()).mapNotNull { i ->
                            val l = a.optJSONObject(i) ?: return@mapNotNull null
                            Livello(l.optString("levelCode"), l.optString("displayValue"), l.optDouble("decimalValue", Double.NaN))
                        }.filter { it.scritta.isNotEmpty() }
                    }
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALIAN)
                    o.optJSONArray("periods")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("dateStart")?.takeIf { d -> d.length >= 10 } }.minOrNull()
                            ?.let { primoGiorno.time = f.parse(it.take(10))!! }
                    }
                }
            }
            val (st, vt) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            val studenti = if (st.first == 200) leggiStudenti(st.second) else emptyList()
            if (vt.first != 200) { testo("classeviva non ha dato i voti (http ${vt.first}).", vt.second); return@launch }
            rispostaVoti = vt.second
            val voti = leggiVoti(vt.second)
            if (studenti.isEmpty()) { testo("nessuno studente riconosciuto.", st.second); return@launch }
            if (voti.isEmpty()) { testo("nessun voto riconosciuto nella risposta.", vt.second); return@launch }
            elenco = studenti; tutti = voti
            mostra(studenti, voti)
        }
    }

    private fun mostra(studenti: List<Studente>, tuttiVoti: Map<String, List<Voto>>) {
        lista.removeAllViews()
        val voti = tuttiVoti
        lista.addView(TextView(this).apply {
            text = "copia la risposta dei voti"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(4), dp(8), dp(12))
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("risposta", rispostaVoti.take(60000)))
                android.widget.Toast.makeText(this@VotiActivity, "copiata, incollala nella chat", android.widget.Toast.LENGTH_LONG).show()
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
            val dello = voti[s.id] ?: emptyList()
            // ed. civica: riga a parte con i voti di tutti i docenti
            val civica = dello.filter { it.materia.contains("civic", ignoreCase = true) }
            val suoi = (dello - civica.toSet()).groupBy { gruppo(it.tipo) }
            val scatola = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                addView(TextView(ctx).apply { text = s.nome; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            val righe = ordine.filter { suoi.containsKey(it) } + suoi.keys.filter { it !in ordine }
            if (righe.isEmpty() && civica.isEmpty()) scatola.addView(TextView(ctx).apply { text = "nessun voto"; textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, dp(4), 0, 0) })
            fun riga(etichetta: String, colore: Int, elenco: List<Voto>) {
                // intestazione fissa a sinistra, voti che scorrono a destra sulla stessa riga
                val chip = LinearLayout(ctx)
                elenco.sortedBy { it.data }.forEach { v ->
                    chip.addView(chipVoto(modificato(v)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
                }
                scatola.addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply { text = etichetta; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore) },
                        LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chip) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            righe.forEach { g -> riga(g, colorePerRiga(g), suoi[g].orEmpty()) }
            if (civica.isNotEmpty()) riga("civica", 0xFFE57399.toInt(), civica)
            lista.addView(scatola, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    /** 5½ = 5,5; 5+ = 5,25; 6- = 5,75; i numeri semplici restano tali. */
    private fun numeroDa(v: String): Double? {
        livelli.firstOrNull { it.scritta == v.trim() }?.let { return it.decimale.takeIf { d -> !d.isNaN() && d >= 0 } }
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
        text = v.valore; textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0); minWidth = dp(46)
        val colore = coloreVoto(v.numero)
        // i voti che non fanno media (peso 0) hanno il bordo e lo sfondo chiaro
        if (v.faMedia) { setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(colore); cornerRadius = dp(12).toFloat() } }
        else { setTextColor(colore); background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); setStroke(dp(2), colore); cornerRadius = dp(12).toFloat() } }
        setOnClickListener { apriVoto(v, v.autore.isEmpty() || mioId.isEmpty() || v.autore == mioId) }
    }

    /** Valore, tipo, media, data e annotazioni modificati a mano restano sul telefono finché non si riescono a scrivere su classeviva. */
    private fun modificato(v: Voto): Voto {
        val valore = memoria.getString("${v.chiave}|valore", null) ?: v.valore
        return v.copy(
            valore = valore,
            numero = numeroDa(valore) ?: v.numero,
            tipo = memoria.getString("${v.chiave}|tipo", null) ?: v.tipo,
            faMedia = memoria.getString("${v.chiave}|media", null)?.toBooleanStrictOrNull() ?: v.faMedia,
            data = memoria.getString("${v.chiave}|data", null) ?: v.data,
            nota = memoria.getString("${v.chiave}|nota", null) ?: v.nota
        )
    }

    private fun apriVoto(v: Voto, modificabile: Boolean) {
        val ctx = this
        val fIt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ITALIAN)
        val fApi = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALIAN)
        val tipi = listOf("scritto/grafico", "orale", "pratico")
        var valoreScelto = v.valore
        var tipoScelto = v.tipo.lowercase()
        var mediaScelta = v.faMedia
        var scelta: java.util.Date? = runCatching { fApi.parse(v.data.take(10)) }.getOrNull()
        lateinit var finestra: androidx.appcompat.app.AlertDialog
        lateinit var txtValore: TextView
        lateinit var txtTipo: TextView
        lateinit var txtData: TextView

        fun testoModificabile(t: String) = if (modificabile) "$t ▾" else t
        fun aggiornaValore() { txtValore.text = testoModificabile(valoreScelto); txtValore.setTextColor(coloreVoto(numeroDa(valoreScelto))) }
        fun aggiornaTipo() { txtTipo.text = testoModificabile(tipoScelto); txtTipo.setTextColor(colorePerRiga(if (tipoScelto.contains("scritt")) "scritto" else tipoScelto)) }
        fun aggiornaData() { txtData.text = scelta?.let { fIt.format(it) } ?: "data" }
        fun sceltaValore() {
            // voti ammessi: la tabella di classeviva; se non si legge, una scala numerica di ripiego
            val voci = livelli.map { it.scritta }.ifEmpty { (1..10).flatMap { n -> listOf("$n-", "$n", "$n+", "$n½").filter { n < 10 || it == "10" || it == "10-" } }.drop(1) }
            MaterialAlertDialogBuilder(ctx).setTitle("voto")
                .setItems(voci.toTypedArray()) { _, i -> valoreScelto = voci[i]; aggiornaValore() }
                .show()
        }
        fun sceltaTipo() {
            MaterialAlertDialogBuilder(ctx).setTitle("tipo")
                .setItems(tipi.toTypedArray()) { _, i -> tipoScelto = tipi[i]; aggiornaTipo() }
                .show()
        }
        fun sceltaData() {
            val c = java.util.Calendar.getInstance().apply { time = scelta ?: java.util.Date() }
            android.app.DatePickerDialog(ctx, { _, a, m, g ->
                scelta = java.util.Calendar.getInstance().apply { set(a, m, g) }.time
                aggiornaData()
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = primoGiorno.timeInMillis // non prima del primo giorno di scuola
            }.show()
        }

        // riga 1: voto - materia - data
        txtValore = TextView(ctx).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(6), dp(8), dp(6))
            if (modificabile) { setOnClickListener { sceltaValore() } }
        }
        val txtMateria = TextView(ctx).apply {
            text = v.materia.lowercase(); textSize = 14f; setTextColor(0xFF555555.toInt()); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        txtData = TextView(ctx).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF333333.toInt()); setPadding(dp(8), dp(6), dp(2), dp(6))
            if (modificabile) { setOnClickListener { sceltaData() } }
        }
        val riga1 = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(txtValore)
            addView(txtMateria, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(txtData)
        }

        // riga 2: tipo (scritto/grafico, orale, pratico) e spunta "media"
        txtTipo = TextView(ctx).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(6), dp(8), dp(6))
            if (modificabile) { setOnClickListener { sceltaTipo() } }
        }
        val media = android.widget.CheckBox(ctx).apply {
            text = "media"; textSize = 15f; isChecked = mediaScelta; isEnabled = modificabile
            buttonTintList = android.content.res.ColorStateList.valueOf(azzurro)
            setOnCheckedChangeListener { _, c -> mediaScelta = c }
        }
        val riga2 = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(txtTipo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(media)
        }

        // riga 3: cancella (solo sui propri voti)
        val cancella = TextView(ctx).apply {
            text = "cancella"; textSize = 14f; setTextColor(0xFFE53935.toInt()); setPadding(dp(2), dp(8), dp(12), dp(8))
            if (modificabile) {
                setOnClickListener {
                    MaterialAlertDialogBuilder(ctx).setMessage("cancellare questo voto da classeviva?")
                        .setNegativeButton("no", null)
                        .setPositiveButton("cancella") { _, _ -> finestra.dismiss(); cancellaSuClasseviva(v) }
                        .show()
                }
            } else {
                visibility = android.view.View.GONE
            }
        }
        val riga3 = LinearLayout(ctx).apply { addView(cancella) }

        // annotazioni: etichetta quasi attaccata al campo di testo
        val etichetta = TextView(ctx).apply { text = "annotazioni per la famiglia"; textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(12), 0, 0) }
        val nota = EditText(ctx).apply {
            setText(v.nota); textSize = 15f; minLines = 3; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(4), dp(2), dp(4), dp(4)); isEnabled = modificabile
        }

        fun bottone(t: String, azione: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { azione() }
        }
        // annulla all'estremo sinistro, salva all'estremo destro della finestra
        val pulsanti = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bottone(if (modificabile) "annulla" else "chiudi") { finestra.dismiss() })
            addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            if (modificabile) {
                addView(bottone("salva") {
                    val dataApi = scelta?.let { fApi.format(it) } ?: v.data
                    salvaSuClasseviva(v, valoreScelto, tipoScelto, mediaScelta, dataApi, nota.text.toString())
                    memoria.edit().putString("${v.chiave}|valore", valoreScelto).putString("${v.chiave}|tipo", tipoScelto)
                        .putString("${v.chiave}|media", mediaScelta.toString()).putString("${v.chiave}|data", dataApi)
                        .putString("${v.chiave}|nota", nota.text.toString()).apply()
                    mostra(elenco, tutti)
                    finestra.dismiss()
                })
            }
        }

        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(8))
            addView(riga1); addView(riga2); addView(riga3)
            if (!modificabile) {
                addView(TextView(ctx).apply { text = "prof. " + v.docente.ifEmpty { "altro docente" }; textSize = 13f; setTextColor(0xFF777777.toInt()); setPadding(dp(2), dp(8), 0, 0) })
            }
            addView(etichetta); addView(nota)
            addView(pulsanti, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        aggiornaValore(); aggiornaTipo(); aggiornaData()
        finestra = MaterialAlertDialogBuilder(ctx).setView(corpo).create()
        finestra.show()
    }

    /** Prova a scrivere la modifica su classeviva; se non riesce resta salvata sul telefono e lo si dice. */
    private fun salvaSuClasseviva(v: Voto, valore: String, tipo: String, media: Boolean, data: String, nota: String) {
        if (v.grezzo.isEmpty()) return
        lifecycleScope.launch {
            val livello = livelli.firstOrNull { it.scritta == valore }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Impostazioni(this@VotiActivity).client().salvaVoto(v.grezzo, valore, livello?.codice, livello?.decimale?.takeIf { !it.isNaN() }, tipo, media, data, nota)
                }
            }
            val msg = r.getOrNull()?.let { (c, t) -> if (c in 200..299) "salvato su classeviva" else "classeviva non ha accettato (http $c): ${t.take(80)}; resta sul telefono" }
                ?: "non riuscito: ${r.exceptionOrNull()?.message}; resta sul telefono"
            android.widget.Toast.makeText(this@VotiActivity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Cancella il voto su classeviva e ricarica l'elenco se ci riesce. */
    private fun cancellaSuClasseviva(v: Voto) {
        if (v.grezzo.isEmpty()) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(this@VotiActivity).client().cancellaVoto(v.grezzo) } }
            val codice = r.getOrNull()?.first ?: 0
            val msg = if (codice in 200..299) "voto cancellato" else "non cancellato (http $codice): ${r.getOrNull()?.second?.take(80) ?: r.exceptionOrNull()?.message}"
            android.widget.Toast.makeText(this@VotiActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            if (codice in 200..299) carica()
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
