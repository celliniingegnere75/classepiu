package it.cellini.classepiu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Registro: elenco delle classi letto direttamente da ClasseViva (senza aprire il sito).
 * Due schede: "I miei registri" e "Tutti i registri".
 */
class RegistroFragment : Fragment() {

    private var mie = true
    private var cerca = ""
    private var datiMie: List<Classe> = emptyList()
    private var datiTutte: List<Classe> = emptyList()
    private var caricateTutte = false
    private var ultimaRisposta = ""
    private lateinit var elenco: RecyclerView
    private lateinit var aggiorna: SwipeRefreshLayout
    private lateinit var vuoto: TextView
    private lateinit var dettagli: TextView
    private lateinit var schedaMie: TextView
    private lateinit var schedaTutte: TextView
    private lateinit var lente: ImageView
    private lateinit var rigaCerca: LinearLayout
    private lateinit var campoCerca: EditText
    private val adattatore = Adattatore()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val celeste = ContextCompat.getColor(ctx, R.color.logo_sfondo)

        campoCerca = EditText(ctx).apply {
            hint = "cerca una classe"
            setSingleLine(true)
            textSize = 17f
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { cerca = s?.toString().orEmpty(); mostra() }
            })
        }
        lente = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF3F3F3F.toInt())
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            contentDescription = "cerca"
            setOnClickListener {
                campoCerca.visibility = if (campoCerca.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (campoCerca.visibility == View.GONE) campoCerca.setText("") else campoCerca.requestFocus()
            }
        }
        rigaCerca = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(campoCerca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(lente, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        fun scheda(testo: String, prima: Boolean) = TextView(ctx).apply {
            text = testo; textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false
            setSingleLine(true); ellipsize = null
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            setOnClickListener { cambiaScheda(prima) }
        }
        schedaMie = scheda("i miei registri", true)
        schedaTutte = scheda("tutti i registri", false)
        val schede = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(schedaMie, LinearLayout.LayoutParams(0, ctx.dp(44), 1f))
            addView(schedaTutte, LinearLayout.LayoutParams(0, ctx.dp(44), 1f).apply { leftMargin = ctx.dp(10) })
        }

        elenco = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = adattatore
            clipToPadding = false
            setPadding(0, ctx.dp(4), 0, ctx.dp(16))
        }
        aggiorna = SwipeRefreshLayout(ctx).apply {
            addView(elenco)
            setColorSchemeColors(celeste)
            setOnRefreshListener { carica(mie, forza = true) }
        }
        vuoto = TextView(ctx).apply {
            textSize = 15f; alpha = .75f
            setPadding(ctx.dp(8), ctx.dp(32), ctx.dp(8), 0)
            visibility = View.GONE
        }
        dettagli = TextView(ctx).apply {
            text = "mostra la risposta di classeviva"
            textSize = 14f; setTextColor(celeste)
            setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
            visibility = View.GONE
            setOnClickListener { mostraRisposta() }
        }
        val corpo = FrameLayout(ctx).apply {
            addView(aggiorna)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(vuoto); addView(dettagli)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), 0)
            addView(schede, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
            addView(rigaCerca, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(corpo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        datiMie = leggiCache(true)
        datiTutte = leggiCache(false)
        aggiornaSchede()
        mostra()
        carica(true, forza = false)
        return col
    }

    private fun cambiaScheda(prima: Boolean) {
        mie = prima
        aggiornaSchede()
        mostra()
        if (!prima && !caricateTutte) carica(false, forza = false)
    }

    private fun aggiornaSchede() {
        val ctx = requireContext()
        val celeste = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        fun stile(v: TextView, attiva: Boolean) {
            v.background = GradientDrawable().apply { cornerRadius = ctx.dp(22).toFloat(); setColor(if (attiva) celeste else 0xFFF0F0F0.toInt()) }
            v.setTextColor(if (attiva) Color.WHITE else 0xFF333333.toInt())
        }
        stile(schedaMie, mie)
        stile(schedaTutte, !mie)
        rigaCerca.visibility = if (mie) View.GONE else View.VISIBLE
        if (mie) { campoCerca.visibility = View.GONE; campoCerca.setText("") }
    }

    private fun fileCache(mie: Boolean) = File(requireContext().filesDir, if (mie) "classi_mie.json" else "classi_tutte.json")

    private fun leggiCache(mie: Boolean): List<Classe> = try { leggiClassi(fileCache(mie).readText()) } catch (e: Exception) { emptyList() }

    fun carica(mieClassi: Boolean, forza: Boolean) {
        val ctx = context ?: return
        val imp = Impostazioni(ctx)
        if (!imp.pronta) { aggiorna.isRefreshing = false; return }
        aggiorna.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val esito = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = imp.client()
                    cv.login()
                    cv.classiGrezze(!mieClassi)
                }
            }
            aggiorna.isRefreshing = false
            esito.onSuccess { (codice, testo) ->
                ultimaRisposta = "HTTP $codice\n$testo"
                if (codice == 200) {
                    val classi = leggiClassi(testo)
                    if (classi.isNotEmpty()) runCatching { fileCache(mieClassi).writeText(testo) }
                    if (mieClassi) datiMie = classi else { datiTutte = classi; caricateTutte = true }
                } else if (!mieClassi) caricateTutte = true
                mostra(errore = if (codice == 200) null else "classeviva ha risposto con un errore (http $codice).")
            }.onFailure { e ->
                ultimaRisposta = "Errore: ${e.message}"
                mostra(errore = "non riesco a leggere le classi: ${e.message}")
            }
        }
    }

    private fun mostra(errore: String? = null) {
        val q = cerca.trim().lowercase()
        val base = if (mie) datiMie else datiTutte
        // In "le mie classi", prima le classi previste oggi nell'orario personale,
        // nell'ordine della prima ora; tutte le altre restano in ordine alfabetico.
        val ordinate = if (!mie) base else {
            val giornoOggi = when (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 1
                java.util.Calendar.TUESDAY -> 2
                java.util.Calendar.WEDNESDAY -> 3
                java.util.Calendar.THURSDAY -> 4
                java.util.Calendar.FRIDAY -> 5
                java.util.Calendar.SATURDAY -> 6
                else -> 0
            }
            val prioritaOggi = if (giornoOggi in 1..6) {
                Orario(requireContext()).lezioni()
                    .filter { it.giorno == giornoOggi }
                    .sortedBy { it.ora }
                    .map { it.classe.trim().uppercase(java.util.Locale.ROOT) }
                    .distinct()
                    .withIndex()
                    .associate { it.value to it.index }
            } else emptyMap()
            base.sortedWith(
                compareBy<Classe> { prioritaOggi[it.nome.trim().uppercase(java.util.Locale.ROOT)] ?: Int.MAX_VALUE }
                    .thenBy { it.nome.lowercase(java.util.Locale.ITALY) }
                    .thenBy { it.codice.lowercase(java.util.Locale.ITALY) }
            )
        }
        val vis = ordinate.filter { q.isEmpty() || it.nome.lowercase().contains(q) || it.descrizione.lowercase().contains(q) || it.codice.lowercase().contains(q) }
        adattatore.dati = vis
        adattatore.notifyDataSetChanged()
        val testo = when {
            errore != null && base.isEmpty() -> errore
            base.isEmpty() && ultimaRisposta.isNotEmpty() -> "ho ricevuto la risposta di classeviva ma non vi ho riconosciuto nessuna classe."
            base.isEmpty() -> "carico le classi…"
            vis.isEmpty() -> "nessuna classe corrisponde alla ricerca."
            else -> ""
        }
        vuoto.text = testo
        vuoto.visibility = if (testo.isEmpty()) View.GONE else View.VISIBLE
        dettagli.visibility = if (base.isEmpty() && ultimaRisposta.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun mostraRisposta() {
        val ctx = requireContext()
        val t = TextView(ctx).apply {
            text = ultimaRisposta.take(6000)
            textSize = 11f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (mie) "risposta: i miei registri" else "risposta: tutti i registri")
            .setView(ScrollView(ctx).apply { addView(t) })
            .setPositiveButton("copia") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("risposta", ultimaRisposta))
                Toast.makeText(ctx, "copiato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("chiudi", null)
            .show()
    }

    fun indietro(): Boolean = false

    private inner class Adattatore : RecyclerView.Adapter<Adattatore.VH>() {
        var dati: List<Classe> = emptyList()

        inner class VH(
            val box: View, val tessera: View, val sopra: View, val nome: TextView, val descrizioneClasse: TextView,
            val appello: TextView, val codice: TextView, val voti: TextView, val lezioni: TextView, val note: TextView
        ) : RecyclerView.ViewHolder(box)

        override fun getItemCount() = dati.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val celeste = ContextCompat.getColor(ctx, R.color.logo_sfondo)
            val outfitRegular = Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf")
            val outfitBold = Typeface.createFromAsset(ctx.assets, "outfit_bold.ttf")
            val celesteTesto = 0xFF4AA7CF.toInt()

            val nome = TextView(ctx).apply {
                textSize = 28f; typeface = Typeface.create(outfitBold, Typeface.BOLD); setTextColor(celeste)
                gravity = Gravity.CENTER; maxLines = 2; includeFontPadding = false
            }
            val descrizioneClasse = TextView(ctx).apply {
                textSize = 10f; typeface = outfitRegular; setTextColor(0xFF3B97CC.toInt())
                gravity = Gravity.CENTER; setSingleLine(true); includeFontPadding = false
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, ctx.dp(3), 0, 0)
            }
            val tessera = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                background = GradientDrawable().apply { setColor(0xFFF0F6FA.toInt()); cornerRadius = ctx.dp(14).toFloat() }
                addView(nome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(descrizioneClasse, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            val appello = TextView(ctx).apply {
                text = "appello veloce"
                textSize = 12f
                typeface = outfitBold
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; setSingleLine(true); includeFontPadding = false
                setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
                background = GradientDrawable().apply { setColor(0xFF81C784.toInt()); cornerRadius = ctx.dp(10).toFloat() }
            }
            val codice = TextView(ctx).apply {
                textSize = 10.5f; typeface = outfitRegular; setTextColor(celeste); setPadding(ctx.dp(2), ctx.dp(3), ctx.dp(2), 0)
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false
            }
            val sopra = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(appello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(codice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            fun tasto(testo: String, colore: Int) = TextView(ctx).apply {
                text = testo; textSize = 11f
                typeface = outfitBold
                setTextColor(celesteTesto)
                gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 2
                background = GradientDrawable().apply { setColor(colore); cornerRadius = ctx.dp(8).toFloat() }
            }
            val voti = tasto("i miei\nvoti", 0xFFB3DDF5.toInt())
            val lezioni = tasto("le mie\nlezioni", 0xFFD4EEFB.toInt())
            val note = tasto("note\nalunni", 0xFFC5D5F2.toInt())
            val sotto = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(lezioni, voti, note).forEachIndexed { i, t ->
                    addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ctx.dp(6) })
                }
            }
            val testi = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(sopra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(sotto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(6) })
            }
            val riga = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt()); cornerRadius = ctx.dp(18).toFloat(); setStroke(ctx.dp(1), 0xFFE6E6E6.toInt())
                }
                addView(tessera, LinearLayout.LayoutParams(ctx.dp(110), ctx.dp(100)))
                addView(testi, LinearLayout.LayoutParams(0, ctx.dp(100), 1f).apply { leftMargin = ctx.dp(14) })
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, ctx.dp(6), 0, ctx.dp(6))
                }
            }
            return VH(riga, tessera, sopra, nome, descrizioneClasse, appello, codice, voti, lezioni, note)
        }

        private fun senzaPrefisso(testo: String, vararg prefissi: String): String {
            val t = testo.trim()
            val p = prefissi.map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { t.startsWith(it, ignoreCase = true) } ?: return t
            return t.drop(p.length).trimStart(' ', '-', ':', '–', '—')
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = dati[pos]
            h.nome.text = c.nome
            val descrizione = senzaPrefisso(c.descrizione, c.codice, c.nome)
            h.descrizioneClasse.text = if (descrizione.length > 12) descrizione.take(11) + "…" else descrizione
            h.descrizioneClasse.visibility = if (descrizione.isEmpty()) View.GONE else View.VISIBLE
            val codice = senzaPrefisso(c.codice, c.nome)
            h.codice.text = codice
            h.codice.visibility = if (codice.isEmpty()) View.GONE else View.VISIBLE
            val apriStudenti = View.OnClickListener {
                val p = c.chiave.split("|", limit = 3)
                (requireActivity() as MainActivity).apri(StudentiScheda(),
                    "tipo" to p.getOrElse(0) { "" }, "id" to p.getOrElse(1) { "" }, "nome" to c.nome, "descrizione" to c.descrizione)
            }
            h.tessera.setOnClickListener(apriStudenti)
            h.sopra.setOnClickListener(apriStudenti)
            val idClasse = c.chiave.split("|", limit = 3).getOrElse(1) { "" }
            val mia = mie || datiMie.any { it.chiave.split("|", limit = 3).getOrElse(1) { "" } == idClasse }
            h.voti.visibility = if (mia) View.VISIBLE else View.GONE
            h.voti.setOnClickListener {
                val p = c.chiave.split("|", limit = 3)
                (requireActivity() as MainActivity).apri(VotiScheda(),
                    "tipo" to p.getOrElse(0) { "" }, "id" to p.getOrElse(1) { "" }, "nome" to c.nome)
            }
            for ((t, cosa) in listOf(h.lezioni to "lezioni", h.note to "note")) {
                t.setOnClickListener {
                    val p = c.chiave.split("|", limit = 3)
                    (requireActivity() as MainActivity).apri(ListaScheda(),
                        "cosa" to cosa, "tipo" to p.getOrElse(0) { "" }, "id" to p.getOrElse(1) { "" }, "nome" to c.nome)
                }
            }
        }
    }
}