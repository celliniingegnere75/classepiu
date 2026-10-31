package it.cellini.classepiu

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni in due schede: "funzioni" (accesso google per l'invio, interruttori, intervallo) e "orario". */
class ImpostazioniFragment : Fragment() {

    private val nero = 0xFF111111.toInt()
    private var aggiornaOre: (Boolean) -> Unit = {}
    private var invioMailRow: View? = null
    private var segnaLetteRow: View? = null
    private var controlloPeriodicoRow: View? = null
    private var gruppoIntervallo: View? = null
    private var contenitorePosizioneId: Int = View.NO_ID

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val imp = Impostazioni(ctx)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(15), ctx.dp(24), ctx.dp(15), ctx.dp(10))
        }

        // invio con gmail dopo l'accesso google (unico modo, il ricevitore Apps Script non c'è più)
        imp.invioGoogle = true
        val titoloGoogle = TextView(ctx).apply {
            textSize = 17f; typeface = carattereGoogle(ctx) ?: Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { accediGoogle() }
        }
        titoloGoogleView = titoloGoogle
        col.addView(titoloGoogle, piena().apply { topMargin = ctx.dp(6) })
        val contoGoogle = TextView(ctx).apply {
            textSize = 13f; setTextColor(0xFF797979.toInt()); gravity = Gravity.CENTER
            carattereNormale(ctx)?.let { typeface = it }
            isClickable = true; isFocusable = true
            setOnClickListener { scollegaGoogle(ctx) }
        }
        contoGoogleView = contoGoogle
        col.addView(contoGoogle, piena().apply { topMargin = ctx.dp(2) })
        val titoloDestinatario = TextView(ctx).apply {
            text = "le email saranno inviate a:"; textSize = 14f; setTextColor(0xFF666666.toInt())
            carattereNormale(ctx)?.let { typeface = it }
            visibility = View.GONE
        }
        titoloDestinatarioView = titoloDestinatario
        col.addView(titoloDestinatario, piena().apply { topMargin = ctx.dp(10) })
        val destinatario = campoSottolineato(ctx, "destinatario (vuoto = te stesso)", password = false).apply { setText(imp.destinatari); carattereNormale(ctx)?.let { typeface = it } }
        destinatarioView = destinatario
        col.addView(destinatario, piena().apply { topMargin = ctx.dp(2) })

        // "annulla" e "salva" compaiono insieme solo quando il destinatario è stato cambiato
        val salva = LinearLayout(ctx).apply { visibility = View.GONE } // niente da salvare finché non tocchi il campo
        salva.addView(pillola(ctx, "annulla", 15f, azzurro, Color.WHITE, ctx.dp(32)).apply {
            setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(4))
            (background as? GradientDrawable)?.setStroke(ctx.dp(1), azzurro)
            setOnClickListener {
                destinatario.setText(imp.destinatari) // torna com'era: il controllo del testo nasconde i pulsanti
                destinatario.clearFocus()
            }
        })
        salva.addView(pillola(ctx, "salva", 15f, Color.WHITE, azzurro, ctx.dp(32)).apply {
            setPadding(ctx.dp(28), ctx.dp(4), ctx.dp(28), ctx.dp(4))
            setOnClickListener {
                imp.destinatari = destinatario.text.toString()
                messaggio("le circolari andranno a: " + imp.destinatariEffettivi.joinToString(", ").ifEmpty { "nessuno (accedi con google)" })
                salva.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
        salvaDestinatarioView = salva
        col.addView(salva, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(6)
        })
        destinatario.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                salva.visibility = if (s.toString() != imp.destinatari) View.VISIBLE else View.GONE
            }
        })
        aggiornaAccount()
        // destinatario vuoto: si prova a prendere l'email registrata su classeviva
        if (imp.destinatari.isBlank() && imp.pronta) lifecycleScope.launch {
            val email = withContext(Dispatchers.IO) { imp.client().emailRegistrata() }
            if (!isAdded || email.isEmpty() || imp.destinatari.isNotBlank()) return@launch
            imp.destinatari = email
            destinatario.setText(email)
            salva.visibility = View.GONE
            messaggio("email presa da classeviva: $email")
        }

        // --- interruttori (si salvano da soli)
        invioMailRow = riga(ctx, col, "invia per email le circolari nuove", imp.invioMail, ctx.dp(28), true) { on ->
            imp.invioMail = on
            // il controllo periodico della bacheca segue questo interruttore: acceso con l'invio, spento senza
            imp.controlloAutomatico = on
            Controllo.pianifica(ctx)
            messaggio(if (on) "invio per email attivato." else "invio per email disattivato.")
            aggiornaVisibilitaControlli()
        } as View
        segnaLetteRow = riga(ctx, col, "segna come lette quelle inviate", imp.segnaLette, ctx.dp(12), true) { on ->
            imp.segnaLette = on
            messaggio(if (on) "le circolari inviate verranno segnate come lette." else "le circolari inviate resteranno da leggere.")
        } as View
        // senza interruttore: il controllo periodico c'è finché è acceso "invia per email le circolari nuove"
        controlloPeriodicoRow = TextView(ctx).apply { text = "controllo periodico della bacheca ogni:"; textSize = 16f; setTextColor(nero) }
            .also { col.addView(it, piena().apply { topMargin = ctx.dp(10) }) }
        // --- intervallo: 2, 4, 8, 24 ore (grigio e non selezionabile se il controllo periodico è spento)
        // scelte disegnate a mano (cerchio, punto, scritta) con spazi flessibili tra l'una e l'altra:
        // nessuna larghezza fissa, così "24 ore" non viene mai tagliato
        val gruppo = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var oreScelte = if (imp.oreIntervallo in ORE) imp.oreIntervallo else 2
        var oreAttive = true // l'intervallo si sceglie sempre: 2, 4, 8 o 24 ore
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
        gruppoIntervallo = gruppo
        col.addView(gruppo, piena().apply { topMargin = ctx.dp(4) })
        aggiornaOre(true)
        // il controllo segue l'invio per email (anche per chi aveva i due interruttori discordi)
        if (imp.invioMail && !imp.controlloAutomatico && imp.googleAccount.contains('@')) { imp.controlloAutomatico = true; Controllo.pianifica(ctx) }
        aggiornaVisibilitaControlli()

        // Navigazione a linguette: icona e testo, con indicatore azzurro sulla scheda attiva.
        val funzioni = ScrollView(ctx).apply { setBackgroundColor(Color.WHITE); addView(col) }
        val orario = vistaOrario(ctx)
        val firma = vistaFirma(ctx)
        val posizioneId = View.generateViewId()
        contenitorePosizioneId = posizioneId
        val posizione = FrameLayout(ctx).apply { id = posizioneId; setBackgroundColor(Color.WHITE) }
        val contenuti = listOf(orario, funzioni, firma, posizione)
        val schede = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), 0)
            setBackgroundColor(Color.WHITE)
        }
        val pulsanti = listOf(
            schedaNavigazione(ctx, "▤", "lezioni"),
            schedaNavigazione(ctx, "✉", "email"),
            schedaNavigazione(ctx, "✎", "firma"),
            schedaNavigazione(ctx, "⌖", "posizione")
        )
        fun mostraScheda(indice: Int, daClasseviva: Boolean = true) {
            pulsanti.forEachIndexed { i, v -> stileNavigazione(ctx, v, i == indice) }
            contenuti.forEachIndexed { i, v -> v.visibility = if (i == indice) View.VISIBLE else View.GONE }
            if ((indice == 0 || indice == 2) && insegnamenti.isEmpty() && !caricamento) {
                if (daClasseviva) caricaInsegnamenti(ctx, avvisa = false)
                else runCatching { insegnamenti = leggiInsegnamenti(fileClassi(ctx).readText()) }
            }
        }
        pulsanti.forEachIndexed { i, v ->
            v.isClickable = true
            v.isFocusable = true
            v.setOnClickListener { mostraScheda(i) }
            schede.addView(v, LinearLayout.LayoutParams(0, ctx.dp(68), 1f))
        }
        mostraScheda(0, daClasseviva = false)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(schede, piena())
            addView(View(ctx).apply { setBackgroundColor(0xFFE7EDF2.toInt()) }, LinearLayout.LayoutParams(-1, ctx.dp(1)))
            addView(FrameLayout(ctx).apply {
                contenuti.forEach { addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("scheda_posizione") == null) {
            childFragmentManager.beginTransaction()
                .replace(contenitorePosizioneId, GeolocalizzazioneFragment(), "scheda_posizione")
                .commit()
        }
    }

    // ---------------- accesso google per l'invio con gmail

    private var titoloGoogleView: TextView? = null
    private var contoGoogleView: TextView? = null
    private var titoloDestinatarioView: TextView? = null
    private var destinatarioView: EditText? = null
    private var salvaDestinatarioView: View? = null

    /** Applica un Typeface personalizzato (compatibile da API 21, non solo da 28 come TypefaceSpan(Typeface)). */
    private class CaratterePersonalizzato(private val tf: Typeface) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(ds: android.text.TextPaint) { ds.typeface = tf }
        override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = tf }
    }

    private var tfGoogle: Typeface? = null

    /** Outfit normale (non grassetto), per la riga con l'account. */
    private fun carattereNormale(ctx: android.content.Context): Typeface? =
        runCatching { Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf") }.getOrNull()

    /** Outfit, preso prima dagli assets e in seconda battuta dalle risorse (se uno dei due non va). */
    private fun carattereGoogle(ctx: android.content.Context): Typeface? {
        tfGoogle?.let { return it }
        val tf = runCatching { Typeface.createFromAsset(ctx.assets, "outfit_bold.ttf") }.getOrNull()
            ?: runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.outfit_bold) }.getOrNull()
        tfGoogle = tf
        return tf
    }

    /**
     * Scritta con "Google" a colori come il logo (carattere Outfit bold) e l'icona della catena in fondo.
     * L'icona sta dentro al testo, così va a capo insieme alle parole e non finisce mai fuori dallo schermo.
     */
    private fun scrittaConGoogle(ctx: android.content.Context, prima: String, dopo: String = ""): SpannableString {
        val segnaposto = "  " // lo spazio unificatore tiene l'icona attaccata alla parola che la precede
        val testo = "$prima Google$dopo$segnaposto"
        val s = SpannableString(testo)
        val colori = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF4285F4.toInt(), 0xFF34A853.toInt(), 0xFFEA4335.toInt())
        s.setSpan(ForegroundColorSpan(nero), 0, prima.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val inizio = prima.length
        colori.forEachIndexed { i, c ->
            s.setSpan(ForegroundColorSpan(c), inizio + i, inizio + i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val carattere = carattereGoogle(ctx)
        if (carattere != null) s.setSpan(CaratterePersonalizzato(carattere), inizio, inizio + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        else s.setSpan(StyleSpan(Typeface.BOLD), inizio, inizio + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (dopo.isNotEmpty()) s.setSpan(ForegroundColorSpan(nero), inizio + 6, inizio + 6 + dopo.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val catena = ContextCompat.getDrawable(ctx, R.drawable.ic_catena)
        if (catena != null) {
            val lato = ctx.dp(22)
            catena.setBounds(0, 0, lato, lato)
            s.setSpan(android.text.style.ImageSpan(catena, android.text.style.ImageSpan.ALIGN_BOTTOM),
                testo.length - 1, testo.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** Schermata di consenso di google (solo la prima volta o se il permesso è stato revocato). */
    private val consenso = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { esito ->
        val ctx = context ?: return@registerForActivityResult
        runCatching { com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(esito.data) }
            .onSuccess { accessoRiuscito(it) }
            .onFailure { messaggio("accesso google annullato.") }
    }

    /** Account scelto nel selettore di Android, in attesa che google confermi il permesso. */
    private var accountScelto = ""

    /** Selettore degli account google del telefono, mostrato sempre (anche con un solo account, e con "aggiungi account"). */
    @Suppress("DEPRECATION")
    private val sceltaAccount = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { esito ->
        val nome = esito.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME).orEmpty()
        if (nome.contains('@')) { accountScelto = nome; autorizza(nome) } else messaggio("nessun account scelto.")
    }

    @Suppress("DEPRECATION")
    private fun accediGoogle() {
        sceltaAccount.launch(android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), true, null, null, null, null))
    }

    private fun autorizza(account: String) {
        val ctx = context ?: return
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(ctx).authorize(GoogleMail.richiesta(account))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consenso.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                else accessoRiuscito(r)
            }
            .addOnFailureListener { messaggio("accesso google non riuscito: ${it.message}") }
    }

    private fun accessoRiuscito(r: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val letta = withContext(Dispatchers.IO) { runCatching { GoogleMail.indirizzoDa(r) }.getOrDefault("") }
            val email = letta.ifEmpty { accountScelto } // se google non lo dice, vale l'account scelto nel selettore
            val imp = Impostazioni(ctx)
            if (email.contains('@')) imp.googleAccount = email
            imp.invioGoogle = true
            aggiornaAccount()
            messaggio(if (email.contains('@')) "invio con gmail attivo: $email" else "accesso google fatto, ma non leggo l'indirizzo.")
        }
    }

    private fun aggiornaAccount() {
        val ctx = context ?: return
        val a = Impostazioni(ctx).googleAccount
        val autenticato = a.contains('@')
        titoloGoogleView?.text = scrittaConGoogle(ctx, if (autenticato) "autenticato con" else "autenticati con", " per inviare le email")
        contoGoogleView?.apply {
            text = if (autenticato) "$a · tocca per scollegare" else ""
            visibility = if (autenticato) View.VISIBLE else View.GONE
        }
        titoloDestinatarioView?.visibility = if (autenticato) View.VISIBLE else View.GONE
        destinatarioView?.visibility = if (autenticato) View.VISIBLE else View.GONE
        if (!autenticato) salvaDestinatarioView?.visibility = View.GONE
        aggiornaVisibilitaControlli()
    }

    /** Senza autenticazione non si può inviare nulla: gli interruttori e l'intervallo restano nascosti. */
    private fun aggiornaVisibilitaControlli() {
        val ctx = context ?: return
        val imp = Impostazioni(ctx)
        val autenticato = imp.googleAccount.contains('@')
        invioMailRow?.visibility = if (autenticato) View.VISIBLE else View.GONE
        val visibile = autenticato && imp.invioMail
        segnaLetteRow?.visibility = if (visibile) View.VISIBLE else View.GONE
        controlloPeriodicoRow?.visibility = if (visibile) View.VISIBLE else View.GONE
        gruppoIntervallo?.visibility = if (visibile) View.VISIBLE else View.GONE
    }

    /**
     * Dimentica l'account google in classePiù; al prossimo accesso il selettore di Android fa scegliere
     * l'account. Non si revoca via oauth2/revoke: lascerebbe in Play Services un gettone morto.
     */
    private fun scollegaGoogle(ctx: android.content.Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("scollegare l'account google?")
            .setMessage("classePiù dimenticherà questo account. Al prossimo accesso potrai scegliere quello che preferisci.")
            .setPositiveButton("scollega") { _, _ ->
                Impostazioni(ctx).googleAccount = ""
                aggiornaAccount()
                messaggio("account google scollegato.")
            }
            .setNegativeButton("annulla", null)
            .show()
    }

    // ---------------- scheda "orario"

    private var insegnamenti: List<Insegnamento> = emptyList()
    private var caricamento = false
    private var rispostaClassi = ""
    private var giornoScelto = 0
    private var aggiornaOrario: () -> Unit = {}

    /** Impostazioni predefinite della firma rapida, salvate localmente sul dispositivo. */
    private fun vistaFirma(baseCtx: android.content.Context): View {
        val ctx = android.view.ContextThemeWrapper(baseCtx, R.style.Theme_ClassePiu)
        val imp = Impostazioni(ctx)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val grigio = 0xFF667085.toInt()
        val bordo = 0xFFE1E7EF.toInt()
        val sfondo = 0xFFF6F8FC.toInt()
        val colonna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(22))
            setBackgroundColor(sfondo)
        }
        fun titolo(t: String) {
            colonna.addView(TextView(ctx).apply {
                text = t
                textSize = 13f
                setTextColor(grigio)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .04f
            }, piena().apply { topMargin = ctx.dp(16); bottomMargin = ctx.dp(5) })
        }
        fun scheda(v: View) {
            colonna.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = ctx.dp(14).toFloat()
                    setStroke(ctx.dp(1), bordo)
                }
                addView(v, piena())
            }, piena())
        }
        fun scelta(testo: String, valore: () -> String, opzioni: List<String>, salva: (String) -> Unit) {
            val riga = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                background = GradientDrawable().apply {
                    setColor(0xFFF8FAFD.toInt())
                    cornerRadius = ctx.dp(10).toFloat()
                    setStroke(ctx.dp(1), bordo)
                }
                isClickable = true
                isFocusable = true
            }
            val etichetta = TextView(ctx).apply {
                text = testo
                textSize = 12f
                setTextColor(grigio)
            }
            val valoreView = TextView(ctx).apply {
                textSize = 15f
                setTextColor(nero)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, ctx.dp(4), 0, 0)
            }
            fun aggiorna() { valoreView.text = "${valore().ifBlank { "Seleziona…" }}  ▾" }
            riga.addView(etichetta, piena())
            riga.addView(valoreView, piena())
            aggiorna()
            riga.setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(testo)
                    .setSingleChoiceItems(opzioni.toTypedArray(), opzioni.indexOf(valore()).coerceAtLeast(0)) { d, i ->
                        salva(opzioni[i])
                        aggiorna()
                        d.dismiss()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            scheda(riga)
        }

        colonna.addView(TextView(ctx).apply {
            text = "Imposta i valori predefiniti per la firma rapida."
            textSize = 14f
            setTextColor(grigio)
        }, piena().apply { topMargin = ctx.dp(4); bottomMargin = ctx.dp(8) })

        titolo("TIPOLOGIA")
        scelta("Tipologia della lezione", { imp.firmaTipo }, listOf("Lezione ordinaria", "Educazione civica")) {
            imp.firmaTipo = it
        }

        titolo("MODALITÀ")
        scheda(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Stessi interruttori a scorrimento della scheda Email: pillola azzurra/grigia
            // e cursore bianco, con etichetta a sinistra.
            fun interruttore(t: String, iniziale: Boolean, salva: (Boolean) -> Unit) {
                val larghezza = ctx.dp(52)
                val altezza = ctx.dp(30)
                val cursore = ctx.dp(22)
                val margine = ctx.dp(4)
                fun pillola(colore: Int) = GradientDrawable().apply {
                    setColor(colore)
                    cornerRadius = altezza / 2f
                    setSize(larghezza, altezza)
                }
                val sfondo = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), pillola(azzurro))
                    addState(intArrayOf(), pillola(0xFFE3E3E3.toInt()))
                }
                val pallino = android.graphics.drawable.InsetDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setSize(cursore, cursore)
                    }, margine
                )
                val sw = androidx.appcompat.widget.SwitchCompat(ctx, null, 0).apply {
                    trackDrawable = sfondo
                    thumbDrawable = pallino
                    switchMinWidth = larghezza
                    showText = false
                    background = null
                    isChecked = iniziale
                    setOnCheckedChangeListener { _, checked -> salva(checked) }
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = t
                        textSize = 16f
                        setTextColor(nero)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(sw, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = ctx.dp(12) })
                    setOnClickListener { sw.toggle() }
                }, piena().apply { topMargin = ctx.dp(3); bottomMargin = ctx.dp(3) })
            }
            interruttore("Compresenza", imp.firmaCopresenza) { imp.firmaCopresenza = it }
            addView(View(ctx).apply { setBackgroundColor(bordo) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)
            ).apply { topMargin = ctx.dp(4); bottomMargin = ctx.dp(4) })
            interruttore("Sostegno", imp.firmaSostegno) { imp.firmaSostegno = it }
        })

        titolo("ARGOMENTO")
        val argomento = EditText(ctx).apply {
            setText(imp.firmaArgomento)
            hint = "argomento o nota della lezione"
            textSize = 15f
            setTextColor(nero)
            setHintTextColor(0xFF98A2B3.toInt())
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = ctx.dp(12).toFloat()
                setStroke(ctx.dp(1), bordo)
            }
        }
        scheda(argomento)
        argomento.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { imp.firmaArgomento = s?.toString().orEmpty() }
        })
        colonna.addView(TextView(ctx).apply {
            text = "La firma rapida usa questi valori: educazione civica firma con la materia educazione civica della classe, l'argomento finisce nel registro. Con compresenza attiva la firma rapida firma in compresenza: copia l'argomento del docente teorico e, se non lo trova, usa quello scritto qui. Con sostegno attivo la firma rapida non firma: per ora quelle ore si firmano dal registro web."
            textSize = 12f
            setTextColor(grigio)
        }, piena().apply { topMargin = ctx.dp(12) })
        // Tutti i testi della scheda Firma usano Outfit, mantenendo il peso normale/grassetto.
        val outfit = runCatching { Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf") }.getOrNull()
            ?: runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.outfit_regular) }.getOrNull()
        if (outfit != null) {
            fun applicaOutfit(v: View) {
                if (v is TextView) v.typeface = Typeface.create(outfit, if (v.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL)
                if (v is ViewGroup) for (i in 0 until v.childCount) applicaOutfit(v.getChildAt(i))
            }
            applicaOutfit(colonna)
        }
        return ScrollView(ctx).apply {
            setBackgroundColor(sfondo)
            isFillViewport = true
            addView(colonna)
        }
    }

    /** Linguetta con icona sopra l'etichetta e indicatore inferiore. */
    private fun schedaNavigazione(ctx: android.content.Context, simbolo: String, etichetta: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(12))
        val riga = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            translationY = -ctx.dp(2).toFloat()
            addView(android.widget.ImageView(ctx).apply {
                val risorsa = when (etichetta) {
                    "lezioni" -> R.drawable.ic_scheda_libro
                    "posizione" -> R.drawable.ic_scheda_mondo
                    else -> R.drawable.ic_nav_impostazioni
                }
                setImageResource(risorsa)
                imageTintList = ColorStateList.valueOf(0xFF858D95.toInt())
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                tag = "icona"
            }, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(32)))
            addView(TextView(ctx).apply {
                text = etichetta
                textSize = 12f
                typeface = runCatching { Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf") }.getOrDefault(Typeface.DEFAULT)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                tag = "etichetta"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(20)))
        }
        addView(riga, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(ctx).apply { tag = "indicatore" }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(3)))
    }

    private fun stileNavigazione(ctx: android.content.Context, scheda: LinearLayout, attiva: Boolean) {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val colore = if (attiva) azzurro else 0xFF858D95.toInt()
        val riga = scheda.getChildAt(0) as LinearLayout
        (riga.findViewWithTag<View>("icona") as android.widget.ImageView).imageTintList = ColorStateList.valueOf(colore)
        (riga.findViewWithTag<View>("etichetta") as TextView).apply {
            setTextColor(colore)
            typeface = runCatching { Typeface.createFromAsset(ctx.assets, if (attiva) "outfit_bold.ttf" else "outfit_regular.ttf") }.getOrDefault(if (attiva) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        }
        scheda.findViewWithTag<View>("indicatore")?.setBackgroundColor(if (attiva) azzurro else Color.TRANSPARENT)
    }

    /** Orario come su classeviva: giorni da lunedì a venerdì (o sabato) in alto, sotto le 8 ore; toccando un'ora si sceglie classe e materia. */
    private fun vistaOrario(baseCtx: android.content.Context): View {
        // Questa scheda mantiene intenzionalmente il carattere originale, diverso dal resto dell'app.
        val ctx = android.view.ContextThemeWrapper(baseCtx, R.style.ThemeOverlay_ClassePiu_Lezioni)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val orario = Orario(ctx)
        val oggi = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        val ultimo = if (Impostazioni(ctx).sabato) java.util.Calendar.SATURDAY else java.util.Calendar.FRIDAY
        giornoScelto = if (oggi in java.util.Calendar.MONDAY..ultimo) oggi - java.util.Calendar.MONDAY else 0

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
                    addView(aulaView, LinearLayout.LayoutParams(ctx.dp(65), ctx.dp(24)))
                }, LinearLayout.LayoutParams(ctx.dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(ctx).apply { setBackgroundColor(0xFFE6E6E6.toInt()) }, LinearLayout.LayoutParams(ctx.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(destra, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, piena().apply { topMargin = ctx.dp(6) })
            Triple(classe, materia, aulaView)
        }
        // "compilazione magica" (wizard) compila classe e materia dalle firme della settimana scorsa, "pulisci" svuota.
        // Il wizard è largo quanto il suo contenuto (cappello e scritta), "pulisci" prende la stessa larghezza;
        // i due tasti stanno al centro con 28 dp di stacco e hanno la stessa altezza.
        val altezzaTasto = ctx.dp(52)
        val tasti = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isBaselineAligned = false
        }
        // cappello a sinistra, "compilazione" e sotto "magica" allineate a sinistra, come in firma rapida
        tasti.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = GradientDrawable().apply { setColor(azzurro); cornerRadius = altezzaTasto / 2f }
            isClickable = true; isFocusable = true
            setPadding(ctx.dp(12), 0, ctx.dp(16), 0)
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_cappello_mago) },
                LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(27)).apply { rightMargin = ctx.dp(7) })
            // "compilazione" e "magica" su due righe con la stessa interlinea di "firma rapida"
            addView(TextView(ctx).apply {
                text = "compilazione\nmagica"; textSize = 14.5f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false; gravity = Gravity.START
                setLineSpacing(0f, 0.9f)
            })
            setOnClickListener { wizard(ctx, orario) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, altezzaTasto))
        tasti.addView(pillola(ctx, "pulisci", 13f, Color.WHITE, azzurro, altezzaTasto).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
            setOnClickListener { pulisci(ctx, orario) }
        }, LinearLayout.LayoutParams(ctx.dp(96), altezzaTasto).apply { leftMargin = ctx.dp(28) }) // più stretto del wizard
        ore.addView(tasti, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(14); bottomMargin = ctx.dp(6)
        })
        val rigaSabato = riga(ctx, ore, "settimana fino a sabato", Impostazioni(ctx).sabato, ctx.dp(16), storeRow = true, piccola = true) { on ->
            Impostazioni(ctx).sabato = on
            if (!on && giornoScelto > 4) giornoScelto = 4
            aggiornaOrario()
        } as LinearLayout
        // "settimana fino a sabato" in Outfit, colore 80% nero e 20% azzurro (#69BCE8)
        (rigaSabato.getChildAt(0) as TextView).apply {
            carattereNormale(ctx)?.let { typeface = it }
            setTextColor(0xFF15262E.toInt())
        }

        aggiornaOrario = {
            pillole[5].visibility = if (Impostazioni(ctx).sabato) View.VISIBLE else View.GONE
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

    /**
     * Wizard: classe e materia di ogni ora dalle proprie firme dei 7 giorni prima di oggi (da lunedì a venerdì o sabato).
     * Le ore senza firma restano vuote; l'aula e gli orari di inizio e fine non si toccano.
     */
    private fun wizard(ctx: android.content.Context, orario: Orario) {
        val imp = Impostazioni(ctx)
        if (!imp.pronta) { messaggio("prima accedi a classeviva."); return }
        val giorni = if (imp.sabato) 6 else 5
        val (da, a) = OrarioDalleFirme.intervallo(giorni = giorni)
        fun leggibile(d: String) = "${d.substring(6, 8)}/${d.substring(4, 6)}"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("compilo l'orario dalle firme?")
            .setMessage("cerco le tue firme dal ${leggibile(da)} al ${leggibile(a)} e riempio classe e materia da lunedì a ${GIORNI_INTERI[giorni - 1]}, saltando supplenze e materie non tue. Le ore senza firma restano come sono.")
            .setPositiveButton("compila") { _, _ ->
                messaggio("cerco le firme…")
                viewLifecycleOwner.lifecycleScope.launch {
                    val esito = withContext(Dispatchers.IO) {
                        runCatching {
                            val c = imp.client().also { it.login() }
                            // classi e materie aggiornate: servono per dare il nome alle classi (le firme hanno solo l'id)
                            // e per scartare le ore che non sono del docente; se classeviva non risponde vale l'ultima copia
                            runCatching { c.classiGrezze(false) }.getOrNull()?.let { (cc, tc) ->
                                if (cc == 200) leggiInsegnamenti(tc).takeIf { it.isNotEmpty() }?.let { insegnamenti = it; runCatching { fileClassi(ctx).writeText(tc) } }
                            }
                            if (insegnamenti.isEmpty()) runCatching { insegnamenti = leggiInsegnamenti(fileClassi(ctx).readText()) }
                            if (insegnamenti.isEmpty()) error("non trovo le tue classi e materie.")
                            val (codice, testo) = c.lezioniGrezze(OrarioDalleFirme.giornoPrima(da), a)
                            val nomi = OrarioDalleFirme.nomiClassi(runCatching { leggiClassi(fileClassi(ctx).readText()) }.getOrDefault(emptyList()))
                            Triple(codice, testo, if (codice == 200) OrarioDalleFirme.leggi(testo, c.idDocente(), ordinari(), giorni, nomi, da) else emptyMap())
                        }
                    }
                    if (!isAdded) return@launch
                    esito.onSuccess { (codice, testo, trovate) ->
                        if (trovate.isEmpty()) {
                            // niente da scrivere: l'orario resta com'era
                            MaterialAlertDialogBuilder(ctx)
                                .setMessage(if (codice == 200) "nessuna firma valida trovata dal ${leggibile(da)} al ${leggibile(a)}: l'orario non è cambiato." else "classeviva ha risposto HTTP $codice.")
                                .setPositiveButton("ok", null)
                                .setNeutralButton("copia la risposta di classeviva") { _, _ -> copiaTesto(ctx, "HTTP $codice\n$testo") }
                                .show()
                            return@onSuccess
                        }
                        // solo le ore con una firma: le altre restano come sono
                        for ((go, voce) in trovate) orario.imposta(go.first, go.second, voce)
                        // se il giorno mostrato non ha lezioni si passa al primo che ne ha, così l'orario compilato si vede subito
                        if ((1..Orario.ORE).none { orario.casella(giornoScelto + 1, it) != null })
                            trovate.keys.minOfOrNull { it.first }?.let { giornoScelto = it - 1 }
                        aggiornaOrario()
                        messaggio("orario compilato: ${trovate.size} ore dalle firme.")
                    }.onFailure { messaggio("wizard non riuscito: ${it.message}") }
                }
            }
            .setNegativeButton("annulla", null)
            .show()
    }

    private fun copiaTesto(ctx: android.content.Context, testo: String) {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", testo))
        messaggio("risposta di classeviva copiata.")
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
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(Orario.AULA_MAX))
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
            .setNeutralButton("svuota") { _, _ -> orario.svuota(giorno, ora); aggiornaOrario() }
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

    /** Riga con testo a sinistra e interruttore azzurro a destra; si salva appena lo tocchi. Restituisce il row container (LinearLayout) se storeRow=true, altrimenti il switch. piccola=true riduce testo e interruttore al 70%. */
    private fun riga(ctx: android.content.Context, col: LinearLayout, testo: String, acceso: Boolean, sopra: Int, storeRow: Boolean = false, piccola: Boolean = false, cambia: (Boolean) -> Unit): Any {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val pillW = if (piccola) ctx.dp(36) else ctx.dp(52)
        val pillH = if (piccola) ctx.dp(21) else ctx.dp(30)
        val knobSz = if (piccola) ctx.dp(15) else ctx.dp(22)
        val knobMarg = if (piccola) ctx.dp(3) else ctx.dp(4)
        fun pillola(colore: Int) = GradientDrawable().apply {
            setColor(colore); cornerRadius = pillH / 2f; setSize(pillW, pillH)
        }
        val sfondo = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pillola(azzurro))
            addState(intArrayOf(), pillola(0xFFE3E3E3.toInt()))
        }
        val pallino = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(knobSz, knobSz) }, knobMarg)
        val sw = androidx.appcompat.widget.SwitchCompat(ctx, null, 0).apply {
            trackDrawable = sfondo
            thumbDrawable = pallino
            switchMinWidth = pillW
            showText = false
            background = null
            isChecked = acceso
            setOnCheckedChangeListener { _, on -> cambia(on) }
        }
        val t = TextView(ctx).apply {
            text = testo; textSize = if (piccola) 11.2f else 16f; setTextColor(nero) // va a capo se lo spazio non basta
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { sw.toggle() }
        }
        col.addView(row, piena().apply { topMargin = sopra })
        return if (storeRow) row else sw
    }

    private fun messaggio(s: String) {
        view?.let {
            Snackbar.make(it, s, Snackbar.LENGTH_SHORT).apply {
                view.background = GradientDrawable().apply {
                    setColor(0xFF323232.toInt())
                    cornerRadius = requireContext().dp(12).toFloat()
                }
            }.show()
        }
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
        val GIORNI_INTERI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato")
    }
}
