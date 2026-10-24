package it.cellini.classepiu

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni come la bozza, in due schede: "funzioni" (profilo, ricevitore/token, interruttori, intervallo) e "orario". */
class ImpostazioniFragment : Fragment() {

    private val nero = 0xFF111111.toInt()
    private var aggiornaOre: (Boolean) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val imp = Impostazioni(ctx)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(15), ctx.dp(4), ctx.dp(15), ctx.dp(10))
        }

        // --- Profilo: cerchio con sagoma di persona, nome e account; sotto la pillola per uscire
        val avatar = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(azzurro) }
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_profilo) },
                FrameLayout.LayoutParams(ctx.dp(34), ctx.dp(34), Gravity.CENTER))
        }
        val nomeProfilo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = "profilo"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(nero) })
            addView(TextView(ctx).apply {
                text = imp.utente; textSize = 13f; setTextColor(0xFF666666.toInt()); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            addView(nomeProfilo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(14) })
        }, piena())
        val esci = pillola(ctx, "esci o cambia account", 17f, Color.WHITE, azzurro, ctx.dp(44)).apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage("uscire dall'account?")
                    .setPositiveButton("conferma") { _, _ ->
                        imp.password = ""
                        imp.controlloAutomatico = false
                        Controllo.pianifica(ctx)
                        startActivity(Intent(ctx, AccessoActivity::class.java))
                        requireActivity().finish()
                    }
                    .setNegativeButton("annulla", null)
                    .show()
            }
        }
        col.addView(esci, piena().apply { height = ViewGroup.LayoutParams.WRAP_CONTENT; topMargin = ctx.dp(10) })

        // --- ricevitore e token
        val ricevitore = campoSottolineato(ctx, "ricevitore", password = false).apply { setText(imp.ricevitoreUrlPersonale) }
        val token = campoSottolineato(ctx, "token", password = true).apply { setText(imp.ricevitoreCodicePersonale) }
        col.addView(ricevitore, piena().apply { topMargin = ctx.dp(4) })
        col.addView(token, piena().apply { topMargin = ctx.dp(0) })
        val salva = pillola(ctx, "salva", 15f, Color.WHITE, azzurro, ctx.dp(32)).apply {
            setPadding(ctx.dp(28), ctx.dp(4), ctx.dp(28), ctx.dp(4))
            setOnClickListener {
                imp.ricevitoreUrl = ricevitore.text.toString()
                imp.ricevitoreCodice = token.text.toString()
                imp.usaRicevitore = true
                messaggio(if (ricevitore.text.isBlank() && token.text.isBlank()) "salvato: si usa il ricevitore già inserito nell'app." else "credenziali del ricevitore salvate.")
            }
        }
        col.addView(salva, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(6)
        })

        // --- interruttori (si salvano da soli)
        riga(ctx, col, "invia per mail le circolari nuove", imp.invioMail, ctx.dp(10)) { on ->
            imp.invioMail = on
            messaggio(if (on) "invio mail attivato." else "invio mail disattivato.")
        }
        riga(ctx, col, "segna come lette quelle inviate", imp.segnaLette, ctx.dp(10)) { on ->
            imp.segnaLette = on
            messaggio(if (on) "le circolari inviate verranno segnate come lette." else "le circolari inviate resteranno da leggere.")
        }
        riga(ctx, col, "controllo periodico della bacheca ogni:", imp.controlloAutomatico, ctx.dp(10)) { on ->
            imp.controlloAutomatico = on
            Controllo.pianifica(ctx)
            aggiornaOre(on)
            messaggio(if (on) "controllo periodico attivato." else "controllo periodico disattivato.")
        }

        // --- intervallo: 2, 4, 8, 24 ore (grigio e non selezionabile se il controllo periodico è spento)
        // scelte disegnate a mano (cerchio, punto, scritta) con spazi flessibili tra l'una e l'altra:
        // nessuna larghezza fissa, così "24 ore" non viene mai tagliato
        val gruppo = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var oreScelte = if (imp.oreIntervallo in ORE) imp.oreIntervallo else 2
        var oreAttive = imp.controlloAutomatico
        val voci = ArrayList<Triple<Int, View, TextView>>() // ore, punto interno, scritta
        val cerchi = ArrayList<GradientDrawable>()
        fun ridisegna() {
            val colore = if (oreAttive) azzurro else 0xFFC4C4C4.toInt()
            voci.forEachIndexed { i, (h, punto, scritta) ->
                cerchi[i].setStroke(ctx.dp(2), colore)
                (punto.background as GradientDrawable).setColor(colore)
                punto.visibility = if (h == oreScelte) View.VISIBLE else View.INVISIBLE
                scritta.setTextColor(if (oreAttive) nero else 0xFFA8A8A8.toInt())
            }
        }
        ORE.forEachIndexed { i, h ->
            if (i > 0) gruppo.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            val anello = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            cerchi.add(anello)
            val punto = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
            val cerchio = FrameLayout(ctx).apply {
                background = anello
                addView(punto, FrameLayout.LayoutParams(ctx.dp(10), ctx.dp(10), Gravity.CENTER))
            }
            val scritta = TextView(ctx).apply { text = "$h ore"; textSize = 14f; setSingleLine(true) }
            voci.add(Triple(h, punto, scritta))
            gruppo.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                addView(cerchio, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)))
                addView(scritta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(6) })
                setOnClickListener {
                    if (!oreAttive) return@setOnClickListener
                    oreScelte = h
                    imp.oreIntervallo = h
                    Controllo.pianifica(ctx)
                    ridisegna()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        aggiornaOre = { acceso -> oreAttive = acceso; ridisegna() }
        col.addView(gruppo, piena().apply { topMargin = ctx.dp(4) })
        aggiornaOre(imp.controlloAutomatico)

        // --- due schede in alto: "funzioni" (tutto quello che c'era) e "orario"
        val funzioni = ScrollView(ctx).apply { setBackgroundColor(Color.WHITE); addView(col) }
        val orario = vistaOrario(ctx)
        val schede = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(15), ctx.dp(6), ctx.dp(15), ctx.dp(2))
        }
        val schedaFunzioni = schedaPillola(ctx, "funzioni")
        val schedaOrario = schedaPillola(ctx, "orario")
        fun mostraScheda(primaScheda: Boolean) {
            stilePillola(ctx, schedaFunzioni, primaScheda)
            stilePillola(ctx, schedaOrario, !primaScheda)
            funzioni.visibility = if (primaScheda) View.VISIBLE else View.GONE
            orario.visibility = if (primaScheda) View.GONE else View.VISIBLE
            if (!primaScheda && insegnamenti.isEmpty() && !caricamento) caricaInsegnamenti(ctx, avvisa = false)
        }
        schedaFunzioni.setOnClickListener { mostraScheda(true) }
        schedaOrario.setOnClickListener { mostraScheda(false) }
        schede.addView(schedaFunzioni, LinearLayout.LayoutParams(0, ctx.dp(32), 1f))
        schede.addView(schedaOrario, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply { leftMargin = ctx.dp(6) })
        mostraScheda(true)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(schede, piena())
            addView(FrameLayout(ctx).apply {
                addView(funzioni, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(orario, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // ---------------- scheda "orario"

    private var insegnamenti: List<Insegnamento> = emptyList()
    private var caricamento = false
    private var rispostaClassi = ""
    private var giornoScelto = 0
    private var aggiornaOrario: () -> Unit = {}

    /** Pillola delle schede, come "le mie classi" / "tutte le classi" nel registro. */
    private fun schedaPillola(ctx: android.content.Context, s: String) = TextView(ctx).apply {
        text = s; textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine(true)
    }

    private fun stilePillola(ctx: android.content.Context, v: TextView, attiva: Boolean) {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        v.background = GradientDrawable().apply { cornerRadius = ctx.dp(16).toFloat(); setColor(if (attiva) azzurro else 0xFFF0F0F0.toInt()) }
        v.setTextColor(if (attiva) Color.WHITE else 0xFF333333.toInt())
    }

    /** Orario come su classeviva: giorni da lunedì a venerdì in alto, sotto le 8 ore; toccando un'ora si sceglie classe e materia. */
    private fun vistaOrario(ctx: android.content.Context): View {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val orario = Orario(ctx)
        val oggi = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        giornoScelto = if (oggi in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY) oggi - java.util.Calendar.MONDAY else 0

        val giorni = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(15), ctx.dp(6), ctx.dp(15), ctx.dp(2)) }
        val pillole = Orario.GIORNI.mapIndexed { i, g ->
            TextView(ctx).apply {
                text = g; textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine(true)
                setOnClickListener { giornoScelto = i; aggiornaOrario() }
            }.also { giorni.addView(it, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply { if (i > 0) leftMargin = ctx.dp(6) }) }
        }

        val ore = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(15), 0, ctx.dp(15), ctx.dp(12)) }
        val caselle = (1..Orario.ORE).map { ora ->
            val classe = TextView(ctx).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 2 }
            val materia = TextView(ctx).apply { textSize = 13f; setTextColor(0xFF4E4E4E.toInt()); gravity = Gravity.CENTER; maxLines = 2 }
            val centro = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(classe, piena()); addView(materia, piena())
            }
            ore.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(56)
                setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(12), ctx.dp(6))
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt()); cornerRadius = ctx.dp(14).toFloat(); setStroke(ctx.dp(1), 0xFFE6E6E6.toInt())
                }
                addView(TextView(ctx).apply { text = "$ora° ora"; textSize = 16f; setTextColor(nero) }, LinearLayout.LayoutParams(ctx.dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(centro, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_matita); contentDescription = "modifica" },
                    LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { leftMargin = ctx.dp(8) })
                setOnClickListener { scegli(ctx, orario, ora) }
            }, piena().apply { topMargin = ctx.dp(6) })
            classe to materia
        }
        ore.addView(TextView(ctx).apply {
            text = "copia la risposta di classeviva (se mancano classi o materie)"
            textSize = 13f; setTextColor(azzurro); gravity = Gravity.CENTER
            setPadding(0, ctx.dp(14), 0, ctx.dp(6))
            setOnClickListener { copiaRispostaClassi(ctx) }
        }, piena())

        aggiornaOrario = {
            pillole.forEachIndexed { i, p ->
                p.background = GradientDrawable().apply { cornerRadius = ctx.dp(16).toFloat(); setColor(if (i == giornoScelto) azzurro else 0xFFF0F0F0.toInt()) }
                p.setTextColor(if (i == giornoScelto) Color.WHITE else 0xFF333333.toInt())
                p.setTypeface(null, if (i == giornoScelto) Typeface.BOLD else Typeface.NORMAL)
            }
            caselle.forEachIndexed { i, (classe, materia) ->
                val c = orario.casella(giornoScelto + 1, i + 1)
                classe.text = c?.classe ?: "nessuna materia"
                classe.setTextColor(if (c == null) 0xFFA8A8A8.toInt() else azzurro)
                materia.text = c?.materia.orEmpty()
                materia.visibility = if (c?.materia.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        }
        aggiornaOrario()

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(giorni, piena())
            addView(ScrollView(ctx).apply { addView(ore) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun fileClassi(ctx: android.content.Context) = java.io.File(ctx.filesDir, "classi_mie.json")

    /** Classi e materie del docente da "le mie classi" di classeviva; intanto si usa l'ultima risposta salvata. */
    private fun caricaInsegnamenti(ctx: android.content.Context, avvisa: Boolean, poi: () -> Unit = {}) {
        if (insegnamenti.isEmpty()) runCatching { insegnamenti = leggiInsegnamenti(fileClassi(ctx).readText()) }
        val imp = Impostazioni(ctx)
        if (!imp.pronta) { poi(); return }
        caricamento = true
        if (avvisa) messaggio("carico classi e materie…")
        viewLifecycleOwner.lifecycleScope.launch {
            val esito = withContext(Dispatchers.IO) { runCatching { imp.client().also { it.login() }.classiGrezze(false) } }
            caricamento = false
            if (!isAdded) return@launch
            esito.onSuccess { (codice, testo) ->
                rispostaClassi = "HTTP $codice\n$testo"
                if (codice == 200) {
                    val nuovi = leggiInsegnamenti(testo)
                    if (nuovi.isNotEmpty()) {
                        insegnamenti = nuovi
                        runCatching { fileClassi(ctx).writeText(testo) }
                    }
                }
            }.onFailure { rispostaClassi = "Errore: ${it.message}" }
            poi()
        }
    }

    /** Scelta di classe e materia per un'ora del giorno selezionato. */
    private fun scegli(ctx: android.content.Context, orario: Orario, ora: Int) {
        if (insegnamenti.isEmpty()) {
            if (caricamento) { messaggio("carico classi e materie…"); return }
            caricaInsegnamenti(ctx, avvisa = true) {
                if (insegnamenti.isEmpty()) messaggio("nessuna classe trovata: usa \"copia la risposta di classeviva\" in fondo.")
                else scegli(ctx, orario, ora)
            }
            return
        }
        val giorno = giornoScelto + 1
        val voci = insegnamenti.map { if (it.materia.isEmpty()) it.classe else "${it.classe} · ${it.materia}" }.toTypedArray()
        val attuale = orario.casella(giorno, ora)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("${Orario.GIORNI[giornoScelto]}, $ora° ora")
            .setSingleChoiceItems(voci, insegnamenti.indexOfFirst { it == attuale }) { d, i ->
                orario.imposta(giorno, ora, insegnamenti[i])
                aggiornaOrario()
                d.dismiss()
            }
            .setNeutralButton("svuota") { _, _ -> orario.imposta(giorno, ora, null); aggiornaOrario() }
            .setNegativeButton("annulla", null)
            .show()
    }

    /** Testo esatto di "le mie classi", da incollare se classi o materie non compaiono. */
    private fun copiaRispostaClassi(ctx: android.content.Context) {
        fun copia() {
            val testo = rispostaClassi.ifEmpty { runCatching { "HTTP 200 (salvata)\n" + fileClassi(ctx).readText() }.getOrDefault("") }
            if (testo.isEmpty()) { messaggio("nessuna risposta di classeviva disponibile."); return }
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", testo))
            messaggio("risposta di classeviva copiata.")
        }
        if (rispostaClassi.isEmpty() && !caricamento) caricaInsegnamenti(ctx, avvisa = true) { copia() } else copia()
    }

    /** Pulsante a pillola azzurro. */
    private fun pillola(ctx: android.content.Context, s: String, size: Float, coloreTesto: Int, sfondo: Int, altezza: Int) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(coloreTesto)
        gravity = Gravity.CENTER
        minHeight = altezza
        background = GradientDrawable().apply { setColor(sfondo); cornerRadius = altezza / 2f }
        isClickable = true
        isFocusable = true
    }

    /** Campo con scritta grande in grassetto e linea sotto, come "ricevitore" e "token" nella bozza. */
    private fun campoSottolineato(ctx: android.content.Context, etichetta: String, password: Boolean) = EditText(ctx).apply {
        hint = etichetta
        setHintTextColor(0xFF797979.toInt()) // scritte ricevitore/token schiarite del 30%
        setTextColor(nero)
        textSize = 15f
        setSingleLine(true)
        minHeight = ctx.dp(40)
        gravity = Gravity.CENTER_VERTICAL
        backgroundTintList = ColorStateList.valueOf(nero)
        setPadding(ctx.dp(0), ctx.dp(4), ctx.dp(0), ctx.dp(4))
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        // scritta del campo vuoto grande (senza grassetto), testo inserito più piccolo
        val aggiorna = { textSize = if (text.isNullOrEmpty()) 17f else 14f; setTypeface(null, Typeface.NORMAL) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { aggiorna() }
        })
        aggiorna()
    }

    /** Riga con testo a sinistra e interruttore azzurro a destra; si salva appena lo tocchi. */
    private fun riga(ctx: android.content.Context, col: LinearLayout, testo: String, acceso: Boolean, sopra: Int, cambia: (Boolean) -> Unit): androidx.appcompat.widget.SwitchCompat {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        fun pillola(colore: Int) = GradientDrawable().apply {
            setColor(colore); cornerRadius = ctx.dp(15).toFloat(); setSize(ctx.dp(52), ctx.dp(30))
        }
        val sfondo = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pillola(azzurro))
            addState(intArrayOf(), pillola(0xFFE3E3E3.toInt()))
        }
        // pallino bianco di 22 dp con 4 dp di margine: sta sempre dentro lo sfondo, uguale acceso e spento
        val pallino = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(ctx.dp(22), ctx.dp(22)) }, ctx.dp(4))
        val sw = androidx.appcompat.widget.SwitchCompat(ctx, null, 0).apply {
            trackDrawable = sfondo
            thumbDrawable = pallino
            switchMinWidth = ctx.dp(52)
            showText = false
            background = null
            isChecked = acceso
            setOnCheckedChangeListener { _, on -> cambia(on) }
        }
        val t = TextView(ctx).apply {
            text = testo; textSize = 16f; setTextColor(nero) // va a capo se lo spazio non basta
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { sw.toggle() }
        }, piena().apply { topMargin = sopra })
        return sw
    }

    private fun messaggio(s: String) {
        view?.let { Snackbar.make(it, s, Snackbar.LENGTH_SHORT).show() }
    }

    private fun esegui(titolo: String, lavoro: () -> String) {
        val ctx = requireContext()
        messaggio("$titolo in corso…")
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(lavoro) }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(ctx)
                .setTitle(titolo)
                .setMessage(r.getOrElse { "non riuscito:\n${it.message ?: it.javaClass.simpleName}" })
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object {
        val ORE = listOf(2, 4, 8, 24)
    }
}
