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

        // --- ricevitore e token
        val ricevitore = campoSottolineato(ctx, "ricevitore", password = false).apply { setText(imp.ricevitoreUrlPersonale) }
        val token = campoSottolineato(ctx, "token", password = true).apply { setText(imp.ricevitoreCodicePersonale) }
        col.addView(ricevitore, piena().apply { topMargin = ctx.dp(2) })
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

        // --- due schede in alto: "orario" e "funzioni" (tutto quello che c'era)
        val funzioni = ScrollView(ctx).apply { setBackgroundColor(Color.WHITE); addView(col) }
        val orario = vistaOrario(ctx)
        val schede = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(15), ctx.dp(6), ctx.dp(15), ctx.dp(2))
        }
        val schedaFunzioni = schedaPillola(ctx, "funzioni")
        val schedaOrario = schedaPillola(ctx, "orario")
        fun mostraScheda(primaScheda: Boolean, daClasseviva: Boolean = true) {
            stilePillola(ctx, schedaFunzioni, primaScheda)
            stilePillola(ctx, schedaOrario, !primaScheda)
            funzioni.visibility = if (primaScheda) View.VISIBLE else View.GONE
            orario.visibility = if (primaScheda) View.GONE else View.VISIBLE
            // all'apertura dell'app basta l'ultima risposta salvata: classeviva si chiede solo quando serve
            if (!primaScheda && insegnamenti.isEmpty() && !caricamento) {
                if (daClasseviva) caricaInsegnamenti(ctx, avvisa = false)
                else runCatching { insegnamenti = leggiInsegnamenti(fileClassi(ctx).readText()) }
            }
        }
        schedaFunzioni.setOnClickListener { mostraScheda(true) }
        schedaOrario.setOnClickListener { mostraScheda(false) }
        schede.addView(schedaOrario, LinearLayout.LayoutParams(0, ctx.dp(32), 1f))
        schede.addView(schedaFunzioni, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply { leftMargin = ctx.dp(6) })
        mostraScheda(false, daClasseviva = false)
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
        // ogni ora divisa in due metà: sopra la classe, sotto la materia, ognuna con la sua tendina
        fun meta(ora: Int, primaMeta: Boolean): TextView = TextView(ctx).apply {
            textSize = 14f; maxLines = 2; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(6), ctx.dp(4))
            minHeight = ctx.dp(30)
            setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(ctx, R.drawable.ic_tendina), null)
            setOnClickListener { if (primaMeta) scegliClasse(ctx, orario, ora) else scegliMateria(ctx, orario, ora) }
        }
        val fasceView = ArrayList<TextView>() // inizio-fine sotto il numero dell'ora
        val caselle = (1..Orario.ORE).map { ora ->
            val classe = meta(ora, true)
            val materia = meta(ora, false).apply { textSize = 12f } // materia un po' più piccola della classe
            val aulaView = TextView(ctx).apply {
                textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(azzurro)
                gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine(true)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { scegliAula(ctx, orario, ora) }
            }
            val destra = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(classe, piena())
                addView(View(ctx).apply { setBackgroundColor(0xFFE6E6E6.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))
                addView(materia, piena())
            }
            ore.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt()); cornerRadius = ctx.dp(14).toFloat(); setStroke(ctx.dp(1), 0xFFE6E6E6.toInt())
                }
                // colonna sinistra: numero dell'ora, "aula" piccolo e sotto la sigla dell'aula (segno da toccare)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, ctx.dp(6), 0) // numero, orario, "aula" e sigla un po' più a sinistra, lontani dalla linea
                    // "1° ora" e sotto inizio-fine: toccandoli si cambia l'orario di quell'ora
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(TextView(ctx).apply { text = "$ora° ora"; textSize = 15f; setTextColor(nero); includeFontPadding = false; gravity = Gravity.CENTER; setSingleLine(true) })
                        addView(TextView(ctx).apply {
                            textSize = 10f; setTextColor(azzurro); includeFontPadding = false; gravity = Gravity.CENTER; setSingleLine(true)
                        }.also { fasceView.add(it) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(1) })
                        setOnClickListener { scegliFascia(ctx, orario, ora) }
                    })
                    addView(TextView(ctx).apply { text = "aula"; textSize = 10f; setTextColor(0xFF777777.toInt()); includeFontPadding = false; gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
                    addView(aulaView, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(24)))
                }, LinearLayout.LayoutParams(ctx.dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(ctx).apply { setBackgroundColor(0xFFE6E6E6.toInt()) }, LinearLayout.LayoutParams(ctx.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(destra, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, piena().apply { topMargin = ctx.dp(6) })
            Triple(classe, materia, aulaView)
        }
        ore.addView(pillola(ctx, "pulisci", 15f, Color.WHITE, azzurro, ctx.dp(32)).apply {
            setPadding(ctx.dp(28), ctx.dp(4), ctx.dp(28), ctx.dp(4))
            setOnClickListener { pulisci(ctx, orario) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(14); bottomMargin = ctx.dp(6)
        })

        aggiornaOrario = {
            pillole.forEachIndexed { i, p ->
                p.background = GradientDrawable().apply { cornerRadius = ctx.dp(16).toFloat(); setColor(if (i == giornoScelto) azzurro else 0xFFF0F0F0.toInt()) }
                p.setTextColor(if (i == giornoScelto) Color.WHITE else 0xFF333333.toInt())
                p.setTypeface(null, if (i == giornoScelto) Typeface.BOLD else Typeface.NORMAL)
            }
            fasceView.forEachIndexed { i, v -> v.text = orario.fascia(i + 1).toString() }
            caselle.forEachIndexed { i, (classe, materia, aulaView) ->
                val c = orario.casella(giornoScelto + 1, i + 1)
                classe.text = c?.classe ?: "classe"
                classe.setTypeface(null, if (c == null) Typeface.NORMAL else Typeface.BOLD)
                classe.setTextColor(if (c == null) 0xFFA8A8A8.toInt() else azzurro)
                materia.text = c?.materia?.ifEmpty { null } ?: "materia"
                materia.setTextColor(if (c?.materia.isNullOrEmpty()) 0xFFA8A8A8.toInt() else nero)
                aulaView.text = orario.aula(giornoScelto + 1, i + 1).ifEmpty { "   " } // vuota: resta il solo segno sottolineato
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

    /** Prima carica classi e materie (se mancano), poi apre la tendina. */
    private fun conInsegnamenti(ctx: android.content.Context, poi: () -> Unit) {
        if (insegnamenti.isNotEmpty()) { poi(); return }
        if (caricamento) { messaggio("carico classi e materie…"); return }
        caricaInsegnamenti(ctx, avvisa = true) {
            if (insegnamenti.isEmpty()) messaggio("nessuna classe trovata: usa \"copia la risposta di classeviva\" in fondo.")
            else poi()
        }
    }

    /** Classi e materie senza educazione civica, che non è una materia ordinaria. */
    private fun ordinari() = insegnamenti.filterNot { it.materia.lowercase().contains("civica") }

    /** Voci delle tendine con carattere più piccolo e righe più spaziate (stessa larghezza della finestra). */
    private fun voci(ctx: android.content.Context, elenco: List<String>) =
        object : android.widget.ArrayAdapter<String>(ctx, com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice, android.R.id.text1, elenco) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { v ->
                    (v as? TextView)?.textSize = 14f
                    (v as? TextView)?.minHeight = ctx.dp(52)
                }
        }

    /** Svuota classe, materia e aula del giorno scelto o di tutta la settimana (gli orari di inizio e fine restano). */
    private fun pulisci(ctx: android.content.Context, orario: Orario) {
        val giorno = GIORNI_INTERI[giornoScelto]
        MaterialAlertDialogBuilder(ctx)
            .setMessage("cosa vuoi pulire?")
            .setPositiveButton("solo $giorno") { _, _ -> orario.pulisci(giornoScelto + 1); aggiornaOrario() }
            .setNeutralButton("tutta la settimana") { _, _ -> orario.pulisci(null); aggiornaOrario() }
            .setNegativeButton("annulla", null)
            .show()
    }

    /** Inizio e fine di un'ora (uguali per tutti i giorni): prima si sceglie l'inizio, poi la fine. */
    private fun scegliFascia(ctx: android.content.Context, orario: Orario, ora: Int) {
        val attuale = orario.fascia(ora)
        android.app.TimePickerDialog(ctx, { _, hI, mI ->
            val inizio = hI * 60 + mI
            val durata = attuale.fine - attuale.inizio
            android.app.TimePickerDialog(ctx, { _, hF, mF ->
                val fine = hF * 60 + mF
                if (fine <= inizio) messaggio("la fine deve venire dopo l'inizio.")
                else { orario.impostaFascia(ora, Fascia(inizio, fine)); aggiornaOrario() }
            }, (inizio + durata) / 60 % 24, (inizio + durata) % 60, true).apply { setTitle("fine della $ora° ora") }.show()
        }, attuale.inizio / 60, attuale.inizio % 60, true).apply { setTitle("inizio della $ora° ora") }.show()
    }

    /** Inserimento della sigla dell'aula (massimo 5 caratteri). */
    private fun scegliAula(ctx: android.content.Context, orario: Orario, ora: Int) {
        val giorno = giornoScelto + 1
        val campo = EditText(ctx).apply {
            setText(orario.aula(giorno, ora))
            hint = "aula"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(Orario.AULA_MAX), android.text.InputFilter.AllCaps())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("aula, ${titolo(ora)}")
            .setView(FrameLayout(ctx).apply { setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0); addView(campo) })
            .setPositiveButton("ok") { _, _ -> orario.impostaAula(giorno, ora, campo.text.toString()); aggiornaOrario() }
            .setNegativeButton("annulla", null)
            .show()
    }

    private fun titolo(ora: Int) = "${GIORNI_INTERI[giornoScelto]}, $ora° ora"

    /** Tendina della classe: cambiando classe la materia resta solo se la si insegna anche lì (o se è l'unica). */
    private fun scegliClasse(ctx: android.content.Context, orario: Orario, ora: Int) = conInsegnamenti(ctx) {
        val giorno = giornoScelto + 1
        val classi = ordinari().map { it.classe }.distinct()
        val attuale = orario.casella(giorno, ora)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(titolo(ora))
            .setSingleChoiceItems(voci(ctx, classi), attuale?.let { classi.indexOf(it.classe) } ?: -1) { d, i ->
                val materie = ordinari().filter { it.classe == classi[i] && it.materia.isNotEmpty() }.map { it.materia }
                val materia = attuale?.materia?.takeIf { it in materie } ?: materie.singleOrNull().orEmpty()
                orario.imposta(giorno, ora, Insegnamento(classi[i], materia))
                aggiornaOrario()
                d.dismiss()
            }
            .setNeutralButton("svuota") { _, _ -> orario.imposta(giorno, ora, null); aggiornaOrario() }
            .setNegativeButton("annulla", null)
            .show()
    }

    /** Tendina della materia: solo le materie che il docente insegna nella classe scelta. */
    private fun scegliMateria(ctx: android.content.Context, orario: Orario, ora: Int) = conInsegnamenti(ctx) {
        val giorno = giornoScelto + 1
        val attuale = orario.casella(giorno, ora)
        if (attuale == null) { messaggio("scegli prima la classe."); return@conInsegnamenti }
        val materie = ordinari().filter { it.classe == attuale.classe && it.materia.isNotEmpty() }.map { it.materia }.distinct()
        if (materie.isEmpty()) { messaggio("nessuna materia trovata per ${attuale.classe}."); return@conInsegnamenti }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(titolo(ora))
            .setSingleChoiceItems(voci(ctx, materie), materie.indexOf(attuale.materia)) { d, i ->
                orario.imposta(giorno, ora, Insegnamento(attuale.classe, materie[i]))
                aggiornaOrario()
                d.dismiss()
            }
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
        val GIORNI_INTERI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì")
    }
}
