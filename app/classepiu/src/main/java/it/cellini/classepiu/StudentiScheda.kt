package it.cellini.classepiu

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Elenco studenti con scheda divisa in anagrafica e indicatori delle nove ore. */
// Build verificata: lettura dello stato giornaliero e della risposta lessons2/students.
class StudentiScheda : Scheda() {
    private val azzurro = 0xFF69BCE8.toInt()
    private val colorePannello = 0xFFF0F0F0.toInt()
    private lateinit var outfitRegular: Typeface
    private lateinit var outfitBold: Typeface
    private lateinit var lista: LinearLayout
    private lateinit var striscia: StrisciaGiorni
    private var studenti: List<Studente> = emptyList()
    private val selezionato: Calendar get() = striscia.selezionato
    private var lavoro: Job? = null
    private val cambiStatoInCorso = mutableSetOf<String>()
    private var tipo = ""
    private var classeId = ""
    private var nome = ""
    /** Da "tutti i registri": un gruppo di classe articolata (es. 5EA_ENA) aperto sulla classe intera. */
    private var gruppo = ""
    private var soloMieLezioni = false
    /** Aperta dal pulsante «libro del docente» (linguetta «appello»); dalla tessera della classe è la scheda classe (linguetta «alunni»). */
    private var libroDocente = false
    private val formatoApi = SimpleDateFormat("yyyyMMdd", Locale.ITALIAN)

    // Colori della scheda e degli indicatori.
    private val verdePresente = 0xFFE3F3E5.toInt()
    private val rosaAssente = 0xFFFBE3E7.toInt()
    private val marroneStage = 0xFFE9D8C9.toInt()
    private val ambraGita = 0xFFFFE7B8.toInt()
    private val verdeOra = 0xFF2EB82E.toInt()
    private val rosaOra = 0xFFE53935.toInt()
    private val gialloOra = 0xFFF2C200.toInt()
    private val grigioOra = 0xFFB8B8B8.toInt()

    override fun crea(savedInstanceState: Bundle?) {
        nome = intent.getStringExtra("nome") ?: ""
        gruppo = intent.getStringExtra("gruppo") ?: ""
        soloMieLezioni = intent.getBooleanExtra("solomie", false)
        libroDocente = intent.getBooleanExtra("librodocente", false)
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = requireContext()
        outfitRegular = Typeface.createFromAsset(ctx.assets, "outfit_regular.ttf")
        outfitBold = Typeface.createFromAsset(ctx.assets, "outfit_bold.ttf")

        val chiudi = TextView(ctx).apply {
            text = "✕"; textSize = 22f; typeface = outfitRegular; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        val titolo = TextView(ctx).apply {
            text = nome; textSize = 22f; typeface = outfitBold; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        // Linguette della scheda classe, sempre visibili sotto la data: cambia solo il pannello sotto di loro.
        val linguette = LinguetteView(ctx, outfitRegular, nomiLinguette(), 0, colorePannello) { pos ->
            val i = idLinguette()[pos]
            linguettaAttiva = i
            mostraLinguetta()
            // tornando agli alunni il giorno può essere cambiato: si rileggono i dati
            if (i == 0) { caricaStato(); ridisegna() }
        }

        val giorni = StrisciaGiorni(ctx) {
            if (linguettaAttiva == 0) {
                caricaStato()
                ridisegna()
            } else (childFragmentManager.findFragmentById(idLezioni) as? ListaScheda)?.ricarica()
        }
        striscia = giorni

        lista = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // in fondo anche lo spazio del pulsante "firma la lezione", così l'ultimo alunno non resta sotto
            setPadding(dp(14), dp(16), dp(14), dp(16 + SPAZIO_BARRA + 64))
        }
        val pannello = ScrollView(ctx).apply {
            addView(lista)
            background = GradientDrawable().apply {
                setColor(colorePannello)
            }
            clipToOutline = true
        }
        pannelloAlunni = pannello
        contenitoreLezioni = android.widget.FrameLayout(ctx).apply { id = idLezioni; visibility = View.GONE }
        val colonna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(azzurro)
            addView(barra)
            giorni.aggiungiA(this)
            addView(linguette, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(android.widget.FrameLayout(ctx).apply {
                addView(pannello, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(contenitoreLezioni, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // "firma la lezione": lo stesso pulsante di "invia per email" della bacheca (colore, icona bianca, posto e margini),
        // uno solo per scheda, fuori dal pannello delle linguette: resta fermo cambiando giorno o linguetta
        val celeste = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val fab = ExtendedFloatingActionButton(ctx).apply {
            text = "firma la lezione"
            setIconResource(R.drawable.ic_matita)
            backgroundTintList = ColorStateList.valueOf(celeste)
            setTextColor(0xFFFFFFFF.toInt())
            iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener { preparaFirma() }
        }
        fabFirma = fab
        setContentView(FrameLayout(ctx).apply {
            addView(colonna)
            addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(ALTEZZA_BARRA + ALZO_BARRA + SOMBRA_BARRA + 12)) }) // sopra la barra delle pagine
        })
        aggiornaPulsanteFirma()
        // una scheda ricostruita mentre una firma è ancora in corso tiene il pulsante bloccato fino all'esito
        bloccaFirma(lavoroFirma?.isActive == true)
        linguetteView = linguette
        mostraLinguetta()
        // "le mie lezioni" solo per le mie classi: si parte dalla copia salvata, poi si controlla su classeviva
        verificaMiaClasse()
        carica()
    }

    private val idLezioni = View.generateViewId()
    private var linguetteView: LinguetteView? = null
    /** Vero se la classe è tra le mie (classes/mine); finché non si sa si mostra "le mie lezioni". */
    private var miaClasse = true

    /** Id delle linguette mostrate: 0 alunni, 1 le mie lezioni (solo per le mie classi), 2 tutte le lezioni. */
    private fun idLinguette() = if (miaClasse) listOf(0, 1, 2) else listOf(0, 2)
    private fun nomiLinguette() = idLinguette().map { listOf(if (libroDocente) "appello" else "alunni", "le mie lezioni", "tutte le lezioni")[it] }

    private fun verificaMiaClasse() {
        val files = requireContext().filesDir
        miaClasse = FiltroArticolate.eMiaClasseDaCopia(files, tipo, classeId, nome, gruppo) ?: true
        linguetteView?.impostaVoci(nomiLinguette(), idLinguette().indexOf(linguettaAttiva).coerceAtLeast(0))
        aggiornaPulsanteFirma()
        lifecycleScope.launch {
            val mia = withContext(Dispatchers.IO) { runCatching { FiltroArticolate.eMiaClasse(client, files, tipo, classeId, nome, gruppo) }.getOrNull() } ?: return@launch
            if (mia == miaClasse) return@launch
            miaClasse = mia
            if (!mia && linguettaAttiva == 1) { linguettaAttiva = 0; mostraLinguetta(); caricaStato(); ridisegna() }
            linguetteView?.impostaVoci(nomiLinguette(), idLinguette().indexOf(linguettaAttiva).coerceAtLeast(0))
            aggiornaPulsanteFirma()
        }
    }
    private var linguettaAttiva = 0
    private var pannelloAlunni: View? = null
    private var contenitoreLezioni: View? = null

    /** Il giorno scelto nella striscia, usato anche dalle lezioni incorporate. */
    fun giornoSelezionato(): Calendar = striscia.selezionato

    /** Mostra sotto le linguette gli alunni oppure le lezioni (mie o tutte); data e linguette restano ferme. */
    private fun mostraLinguetta() {
        val fm = childFragmentManager
        if (fm.isStateSaved) return
        val vecchio = fm.findFragmentById(idLezioni)
        aggiornaPulsanteFirma()
        if (linguettaAttiva == 0) {
            pannelloAlunni?.visibility = View.VISIBLE
            contenitoreLezioni?.visibility = View.GONE
            if (vecchio != null) fm.beginTransaction().remove(vecchio).commitNowAllowingStateLoss()
            return
        }
        pannelloAlunni?.visibility = View.GONE
        contenitoreLezioni?.visibility = View.VISIBLE
        val nuovo = ListaScheda().apply {
            arguments = androidx.core.os.bundleOf(
                "cosa" to "lezioni", "tipo" to tipo, "id" to classeId, "nome" to nome, "gruppo" to gruppo, "incorporata" to true,
                if (linguettaAttiva == 1) "solomie" to soloMieLezioni else "tutte" to true)
        }
        fm.beginTransaction().replace(idLezioni, nuovo).commitNowAllowingStateLoss()
    }

    /** Ultimi dati del giorno selezionato, ridisegnati quando arriva l'elenco studenti o gli orari del popup. */
    private data class DatiGiorno(
        val stato: Map<String, StatoGiornaliero>,
        val statiOre: Map<String, List<Int>>,
        val numeroOre: Int,
        val grezzo: Pair<Int, String>?
    )
    private var datiGiorno: DatiGiorno? = null

    // Un solo client per tutta la scheda: l'archivio cifrato si apre una volta e il token si riusa.
    private val client by lazy { Impostazioni(requireContext().applicationContext).client() }

    private fun fileStudenti() = java.io.File(
        requireContext().filesDir, "studenti_${tipo.ifEmpty { "ordinary" }}_${classeId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json"
    )

    private fun ridisegna() {
        if (studenti.isEmpty()) return
        val d = datiGiorno
        if (libroDocente) {
            if (d == null) mostraAppello(emptyMap(), caricamento = true)
            else mostraAppello(d.stato, statiOre = d.statiOre, numeroOre = d.numeroOre, caricamento = false, grezzo = d.grezzo)
        } else {
            if (d == null) mostraAlunni(emptyMap(), caricamento = true)
            else mostraAlunni(d.stato, statiOre = d.statiOre, numeroOre = d.numeroOre, caricamento = false, grezzo = d.grezzo)
        }
    }

    private fun soloMiei(lista: List<Studente>, miei: Set<String>) = if (miei.isEmpty()) lista else lista.filter { it.id in miei }

    /**
     * Mostra subito l'elenco salvato l'ultima volta (se c'è), poi scarica elenco e presenze del giorno
     * in parallelo: prima si aspettava l'elenco e solo dopo partivano le presenze.
     */
    private fun carica() {
        val mieiSalvati = if (gruppo.isEmpty()) emptySet()
            else runCatching { FiltroArticolate.fileGruppo(requireContext().filesDir, gruppo).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        // un gruppo senza alunni salvati non mostra la classe intera nell'attesa
        studenti = if (gruppo.isNotEmpty() && mieiSalvati.isEmpty()) emptyList()
            else soloMiei(runCatching { leggiStudenti(fileStudenti().readText()) }.getOrDefault(emptyList()), mieiSalvati)
        if (studenti.isEmpty()) testo("carico gli studenti…") else ridisegna()
        caricaStato()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (miei, errore) = if (gruppo.isEmpty()) emptySet<String>() to ""
                        else FiltroArticolate.alunniGruppo(client, requireContext().filesDir, tipo, classeId, gruppo)
                    if (gruppo.isNotEmpty() && miei.isEmpty()) throw ClasseVivaException("non riesco a leggere gli alunni del gruppo $gruppo.\n$errore")
                    client.studentiGrezzi(tipo, classeId) to miei
                }
            }
            val (risposta, miei) = r.getOrElse {
                val m = it.message.orEmpty()
                if (studenti.isEmpty()) testo("non riuscito: ${m.substringBefore('\n')}", 0, m.substringAfter('\n', ""))
                return@launch
            }
            val (codice, corpo) = risposta
            val nuovi = if (codice == 200) soloMiei(leggiStudenti(corpo), miei) else emptyList()
            if (nuovi.isEmpty()) {
                if (studenti.isNotEmpty()) return@launch
                testo(
                    if (codice == 200) "risposta ricevuta ma nessuno studente riconosciuto."
                    else "classeviva ha risposto con un errore (http $codice).",
                    codice,
                    corpo
                )
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { fileStudenti().writeText(corpo) } }
            if (nuovi != studenti) {
                studenti = nuovi
                ridisegna()
            }
        }
    }

    private fun caricaStato() {
        lavoro?.cancel()
        datiGiorno = null
        val giorno = formatoApi.format(selezionato.time)
        lavoro = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // Le due letture sono indipendenti: eseguirle in parallelo evita di sommare
                    // i tempi di attesa delle due richieste HTTP.
                    coroutineScope {
                        val giornaliero = async { client.statoGrezzo(tipo, classeId, giorno) }
                        val lezioni = async { client.lezioniClasseGrezze(tipo, classeId, giorno) }
                        giornaliero.await() to lezioni.await()
                    }
                }
            }
            val coppia = r.getOrNull()
            var stato = coppia?.first?.let { (c, t) ->
                if (c == 200) leggiStato(t, valoreSenzaCodice = if (libroDocente) -1 else 0) else emptyMap()
            } ?: emptyMap<String, StatoGiornaliero>()
            // Lo stato giornaliero (status2) sembra tenere una sola annotazione per studente per giorno:
            // se ritardo breve e uscita anticipata capitano lo stesso giorno, la seconda registrata può
            // sovrascrivere la prima lì. Le presenze per ora (lessons2/students) sono invece indipendenti
            // ora per ora, quindi le usiamo come seconda fonte e uniamo i due segnali con un OR, senza
            // mai togliere un'etichetta già vista nello stato giornaliero.
            val eventiOra = coppia?.second?.let { (c, t) -> if (c == 200) leggiEventiOraIndipendenti(t) else emptyMap() } ?: emptyMap()
            if (eventiOra.isNotEmpty()) {
                stato = stato.toMutableMap().apply {
                    for ((id, evento) in eventiOra) {
                        val (haRitardo, haUscita) = evento
                        if (!haRitardo && !haUscita) continue
                        val precedente = this[id] ?: StatoGiornaliero(1)
                        this[id] = precedente.copy(
                            isRitardoBreve = precedente.isRitardoBreve || haRitardo,
                            isUscita = precedente.isUscita || haUscita
                        )
                    }
                }
            }
            val statiOre = coppia?.second?.let { (c, t) -> if (c == 200) leggiStatiOre(t) else emptyMap() } ?: emptyMap()
            // Se lo stato giornaliero non ha alcun codice (nessuna annotazione registrata per la giornata, come
            // succede quando il docente firma solo le lezioni), ma le presenze per ora ci sono, lo studente
            // risulta presente: altrimenti il nome resta grigio anche se i pallini orari sono corretti.
            if (!libroDocente) {
                stato = stato.mapValues { (id, valore) ->
                    if (valore.valore == -1 && statiOre.containsKey(id)) valore.copy(valore = 0) else valore
                }
            }
            val numeroOre = coppia?.second?.let { (c, t) -> if (c == 200) leggiNumeroOre(t) else 9 } ?: 9
            fun diagnostica(popup: String) = coppia?.let { (g, l) ->
                (g.first to "STATO GIORNALIERO HTTP ${g.first}\n${g.second}\n\nLEZIONI E PRESENZE PER ORA HTTP ${l.first}\n${l.second}$popup")
            }
            // Presenze e pallini si vedono subito; gli orari di Rb/U dal registro web arrivano dopo.
            datiGiorno = DatiGiorno(stato, statiOre, numeroOre, diagnostica(""))
            ridisegna()

            // Per ABR1 (e per ABU0 se manca l'ora) l'API REST non restituisce l'ora: la leggiamo dal popup web
            // in sola lettura. Il popup ha un campo orario per il ritardo e uno per l'uscita (distinti
            // dall'aria-label), quindi li recuperiamo entrambi anche quando capitano lo stesso giorno.
            val ritardiSenzaOra = stato.filterValues { it.isRitardoBreve && it.ritardoBreve == null && it.oraEntrata == null }.keys
            val usciteSenzaOra = stato.filterValues { it.isUscita && it.uscita == null }.keys
            if (ritardiSenzaOra.isEmpty() && usciteSenzaOra.isEmpty()) return@launch
            // Tenuto qui (non dentro il blocco IO) così arriva comunque nella diagnostica anche se
            // la lettura fallisce o non trova niente: serve per vedere l'HTML grezzo del popup web.
            val debugOrari = StringBuilder()
            val orari = withContext(Dispatchers.IO) {
                runCatching {
                    client.orariEventoWeb(classeId, giorno, ritardiSenzaOra, usciteSenzaOra, debugOrari)
                }.getOrDefault(emptyMap())
            }
            if (orari.isNotEmpty()) {
                stato = stato.mapValues { (id, valore) ->
                    val trovato = orari[id] ?: return@mapValues valore
                    valore.copy(
                        ritardoBreve = valore.ritardoBreve ?: trovato.ritardo,
                        uscita = valore.uscita ?: trovato.uscita,
                        isRitardoLungo = valore.isRitardoLungo || trovato.oraEntrata != null,
                        oraEntrata = valore.oraEntrata ?: trovato.oraEntrata
                    )
                }
            }
            val popup = if (debugOrari.isEmpty()) "" else "\n\nPOPUP WEB ORARIO RITARDO/USCITA\n$debugOrari"
            datiGiorno = DatiGiorno(stato, statiOre, numeroOre, diagnostica(popup))
            ridisegna()
        }
    }

    /** Elenco «alunni» della scheda classe (tessera della classe). Separato da [mostraAppello]: le due liste si modificano per conto loro. */
    private fun mostraAlunni(stato: Map<String, StatoGiornaliero>, statiOre: Map<String, List<Int>> = emptyMap(), numeroOre: Int = 9, caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = requireContext()

        studenti.forEach { s ->
            val statoStudente = stato[s.id] ?: StatoGiornaliero(-1)
            val valore = statoStudente.valore
            val coloreAnagrafica = when {
                caricamento -> 0xFFFCFCFC.toInt()
                valore == 2 -> rosaAssente
                valore == 0 || valore == 1 -> verdePresente
                else -> 0xFFEEEEEE.toInt()
            }

            // Scheda unica con due celle sovrapposte: anagrafica sopra, ore effettive del giorno sotto.
            val scheda = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                clipToOutline = true
            }

            val cellaNome = TextView(ctx).apply {
                text = s.nome
                textSize = 17f
                typeface = outfitBold
                setTextColor(0xFF111111.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    setColor(coloreAnagrafica)
                }
                minimumHeight = dp(46)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            scheda.addView(cellaNome, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val cellaOre = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            // Mostra solo le ore effettivamente presenti nel registro per la giornata selezionata.
            val filaPallini = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            repeat(numeroOre.coerceIn(1, 9)) { ora ->
                val pallino = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(coloreOraPerStudente(statiOre, s.id, ora))
                    }
                    contentDescription = "Ora ${ora + 1}: ${descrizioneOra(statiOre[s.id]?.getOrNull(ora) ?: -1)}"
                }
                filaPallini.addView(pallino, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    if (ora > 0) leftMargin = dp(3)
                })
            }
            cellaOre.addView(filaPallini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16)))
            if (statoStudente.isRitardoBreve && statoStudente.isRitardoLungo) {
                // Ritardo normale: se il popup web non ha dato l'ora di entrata, la deduciamo dalla
                // prima ora in cui lo studente risulta presente nelle presenze per ora.
                val entrata = statoStudente.oraEntrata ?: statiOre[s.id]
                    ?.indexOfFirst { it == 0 || it == 1 }?.takeIf { it > 0 }?.let { (it + 1).toString() }
                cellaOre.addView(TextView(ctx).apply {
                    text = "R" + (entrata?.let { " ${it}ª ora" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Ritardo" + (entrata?.let { ", entrato alla ${it}ª ora" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else if (statoStudente.isRitardoBreve) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "Rb" + (statoStudente.ritardoBreve?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Ritardo breve" + (statoStudente.ritardoBreve?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (statoStudente.isUscita) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "U" + (statoStudente.uscita?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Uscita anticipata" + (statoStudente.uscita?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            cellaOre.addView(View(ctx), LinearLayout.LayoutParams(0, dp(1), 1f))
            scheda.addView(cellaOre, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            lista.addView(scheda, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        if (!caricamento && grezzo != null) {
            copiaRisposta(
                "copia la risposta di classeviva di questo giorno",
                grezzo.first,
                "giorno ${formatoApi.format(selezionato.time)}\n${grezzo.second}"
            )
        }
    }


    /** Sigla mostrata a destra del nome nell'Appello. -1 significa nessuno stato. */
    private fun etichettaStatoAppello(valore: Int): String = when (valore) {
        0, 1 -> "P"
        2 -> "A"
        3 -> "PS"
        4 -> "PV"
        else -> ""
    }

    private fun coloreRigaAppello(valore: Int): Int = when (valore) {
        0, 1 -> verdePresente
        2 -> rosaAssente
        3 -> marroneStage
        4 -> ambraGita
        else -> 0xFFEEEEEE.toInt()
    }

    private fun coloreTestoBadgeAppello(valore: Int): Int = when (valore) {
        2 -> 0xFF8A2638.toInt()
        3 -> 0xFF68452D.toInt()
        4 -> 0xFF795000.toInt()
        else -> 0xFF24552D.toInt()
    }

    private fun sfondoBadgeAppello(valore: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(when (valore) {
                0, 1 -> 0xFFC7E8CE.toInt()
                2 -> 0xFFF4C4CC.toInt()
                3 -> 0xFFDCC4AF.toInt()
                4 -> 0xFFFFD783.toInt()
                else -> Color.TRANSPARENT
            })
        }

    /** Il ciclo destro e quello sinistro sono inversi; da nessuno stato si parte da A a destra e da E a sinistra. */
    private fun prossimoStatoAppello(attuale: Int, direzione: Int): Int {
        val sequenza = if (direzione > 0) listOf(2, 4, 3, 0) else listOf(0, 3, 4, 2)
        val normalizzato = if (attuale == 1) 0 else attuale
        val posizione = sequenza.indexOf(normalizzato)
        return if (posizione < 0) sequenza.first() else sequenza[(posizione + 1) % sequenza.size]
    }

    private fun codiceEventoAppello(valore: Int): String? = when (valore) {
        0, 1 -> "E"
        2 -> "A"
        3 -> "PS"
        4 -> "PV"
        else -> null
    }

    /**
     * Effetto "passaggio": la riga schizza fuori dal lato dello swipe e rientra dal lato opposto
     * già aggiornata con la nuova sigla e il nuovo colore.
     */
    private fun animaCambioStatoAppello(riga: View, badge: TextView, da: Int, a: Int, direzione: Int) {
        val coloreA = coloreRigaAppello(a)
        val sigla = etichettaStatoAppello(a)
        val distanza = riga.width.toFloat().coerceAtLeast(riga.resources.displayMetrics.widthPixels.toFloat()) + dp(24)

        riga.animate().cancel()
        riga.animate()
            .translationX(direzione * distanza)
            .alpha(0f)
            .setDuration(115)
            .withEndAction {
                // La nuova presenza compare mentre la riga è fuori schermo.
                riga.background = GradientDrawable().apply { setColor(coloreA) }
                if (sigla.isEmpty()) {
                    badge.text = ""
                    badge.visibility = View.GONE
                    badge.contentDescription = null
                } else {
                    badge.text = sigla
                    badge.visibility = View.VISIBLE
                    badge.setTextColor(coloreTestoBadgeAppello(a))
                    badge.background = sfondoBadgeAppello(a)
                    badge.contentDescription = "Stato: $sigla"
                }

                // Swipe a destra: esce a destra e rientra da sinistra. Viceversa per lo swipe a sinistra.
                riga.translationX = -direzione * distanza
                riga.alpha = 0f
                riga.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .start()
            }
            .start()
    }

    /** Elenco «appello» del libro del docente (pulsante «libro del docente»). Parte come copia di [mostraAlunni], poi diverge. */
    private fun mostraAppello(stato: Map<String, StatoGiornaliero>, statiOre: Map<String, List<Int>> = emptyMap(), numeroOre: Int = 9, caricamento: Boolean, grezzo: Pair<Int, String>? = null) {
        lista.removeAllViews()
        val ctx = requireContext()

        studenti.forEach { s ->
            val statoStudente = stato[s.id] ?: StatoGiornaliero(-1)
            val valore = statoStudente.valore
            val coloreAnagrafica = when {
                caricamento -> 0xFFFCFCFC.toInt()
                valore == 2 -> rosaAssente
                valore == 3 -> marroneStage
                valore == 4 -> ambraGita
                valore == 0 || valore == 1 -> verdePresente
                else -> 0xFFEEEEEE.toInt()
            }

            // Scheda unica con due celle sovrapposte: anagrafica sopra, ore effettive del giorno sotto.
            val scheda = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFFFCFCFC.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                clipToOutline = true
            }

            val etichettaStato = etichettaStatoAppello(valore)
            val rigaNome = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply { setColor(coloreAnagrafica) }
                minimumHeight = dp(46)
            }
            val cellaNome = TextView(ctx).apply {
                text = s.nome
                textSize = 17f
                typeface = outfitBold
                setTextColor(0xFF111111.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(8), 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            rigaNome.addView(cellaNome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val badgeStato = TextView(ctx).apply {
                text = etichettaStato
                textSize = 13f
                typeface = outfitBold
                gravity = Gravity.CENTER
                setTextColor(coloreTestoBadgeAppello(valore))
                setPadding(dp(9), 0, dp(9), 0)
                minWidth = dp(if (etichettaStato.length > 1) 40 else 30)
                minHeight = dp(28)
                visibility = if (etichettaStato.isEmpty()) View.GONE else View.VISIBLE
                background = sfondoBadgeAppello(valore)
                contentDescription = etichettaStato.takeIf { it.isNotEmpty() }?.let { "Stato: $it" }
            }
            rigaNome.addView(badgeStato, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
            scheda.addView(rigaNome, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            // Lo swipe agisce esclusivamente sull'Appello del Libro del docente.
            if (!caricamento && libroDocente) {
                var valoreVisivo = valore
                var inizioX = 0f
                var inizioY = 0f
                val sogliaMovimento = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
                rigaNome.setOnTouchListener { v, evento ->
                    when (evento.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            inizioX = evento.x
                            inizioY = evento.y
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = evento.x - inizioX
                            val dy = evento.y - inizioY
                            if (kotlin.math.abs(dx) > sogliaMovimento && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                v.translationX = (dx * 0.12f).coerceIn(-dp(24).toFloat(), dp(24).toFloat())
                            } else {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val dx = evento.x - inizioX
                            val dy = evento.y - inizioY
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            if (kotlin.math.abs(dx) >= dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                                val direzione = if (dx > 0) 1 else -1
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                val precedente = datiGiorno?.stato?.get(s.id) ?: StatoGiornaliero(-1)
                                val nuovoValore = prossimoStatoAppello(valoreVisivo, direzione)
                                val codiceEvento = codiceEventoAppello(nuovoValore)
                                if (codiceEvento != null && cambiStatoInCorso.add(s.id)) {
                                    val giornoCambio = formatoApi.format(selezionato.time)
                                    val nuovoStato = StatoGiornaliero(nuovoValore)
                                    valoreVisivo = nuovoValore
                                    datiGiorno = datiGiorno?.let { d -> d.copy(stato = d.stato + (s.id to nuovoStato)) }
                                    animaCambioStatoAppello(rigaNome, badgeStato, precedente.valore, nuovoValore, direzione)
                                    lifecycleScope.launch {
                                        val esito = withContext(Dispatchers.IO) {
                                            runCatching { client.cambiaStatoAppello(classeId, gruppo, s.id, giornoCambio, codiceEvento) }
                                        }
                                        cambiStatoInCorso.remove(s.id)
                                        val risposta = esito.getOrNull()
                                        if (risposta != null && risposta.first == 200 && risposta.second.trim().equals("OK", true)) {
                                            // Lo stato è già stato aggiornato in modo ottimistico: evitiamo di rileggere tutta la classe.
                                        } else {
                                            if (formatoApi.format(selezionato.time) == giornoCambio) {
                                                valoreVisivo = precedente.valore
                                                datiGiorno = datiGiorno?.let { d -> d.copy(stato = d.stato + (s.id to precedente)) }
                                                animaCambioStatoAppello(rigaNome, badgeStato, nuovoValore, precedente.valore, -direzione)
                                            }
                                            val messaggio = risposta?.let { "ClasseViva non ha accettato il cambio stato (HTTP ${it.first}). ${it.second.take(120)}" }
                                                ?: "Cambio stato non riuscito: ${esito.exceptionOrNull()?.message ?: "errore di rete"}"
                                            view?.let { Snackbar.make(it, messaggio, Snackbar.LENGTH_LONG).show() }
                                        }
                                    }
                                } else {
                                    v.animate().translationX(0f).setDuration(120).start()
                                }
                            } else {
                                v.animate().translationX(0f).setDuration(120).start()
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            v.animate().translationX(0f).setDuration(120).start()
                            true
                        }
                        else -> true
                    }
                }
            }

            val cellaOre = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            // Mostra solo le ore effettivamente presenti nel registro per la giornata selezionata.
            val filaPallini = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            repeat(numeroOre.coerceIn(1, 9)) { ora ->
                val pallino = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(coloreOraPerStudente(statiOre, s.id, ora))
                    }
                    contentDescription = "Ora ${ora + 1}: ${descrizioneOra(statiOre[s.id]?.getOrNull(ora) ?: -1)}"
                }
                filaPallini.addView(pallino, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    if (ora > 0) leftMargin = dp(3)
                })
            }
            cellaOre.addView(filaPallini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16)))
            if (statoStudente.isRitardoBreve && statoStudente.isRitardoLungo) {
                // Ritardo normale: se il popup web non ha dato l'ora di entrata, la deduciamo dalla
                // prima ora in cui lo studente risulta presente nelle presenze per ora.
                val entrata = statoStudente.oraEntrata ?: statiOre[s.id]
                    ?.indexOfFirst { it == 0 || it == 1 }?.takeIf { it > 0 }?.let { (it + 1).toString() }
                cellaOre.addView(TextView(ctx).apply {
                    text = "R" + (entrata?.let { " ${it}ª ora" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Ritardo" + (entrata?.let { ", entrato alla ${it}ª ora" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else if (statoStudente.isRitardoBreve) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "Rb" + (statoStudente.ritardoBreve?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Ritardo breve" + (statoStudente.ritardoBreve?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (statoStudente.isUscita) {
                cellaOre.addView(TextView(ctx).apply {
                    text = "U" + (statoStudente.uscita?.let { " $it" } ?: "")
                    textSize = 12f
                    typeface = outfitBold
                    setTextColor(0xFF8A6D00.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(4), 0)
                    translationY = -dp(4).toFloat()
                    contentDescription = "Uscita anticipata" + (statoStudente.uscita?.let { " alle $it" } ?: "")
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            cellaOre.addView(View(ctx), LinearLayout.LayoutParams(0, dp(1), 1f))
            scheda.addView(cellaOre, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            lista.addView(scheda, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        if (!caricamento && grezzo != null) {
            copiaRisposta(
                "copia la risposta di classeviva di questo giorno",
                grezzo.first,
                "giorno ${formatoApi.format(selezionato.time)}\n${grezzo.second}"
            )
        }
    }

    /**
     * Legge le presenze per ora dalla risposta lessons2/students.
     * Ogni lezione contiene studentStatus: lo stato può arrivare come codice ClasseViva
     * (es. HAB = assente) oppure come descrizione testuale.
     * Valori normalizzati: 0 presente, 1 ritardo/uscita, 2 assente, -1 non disponibile.
     */
    private fun leggiStatiOre(grezzo: String): Map<String, List<Int>> {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return emptyMap()
        val risultati = mutableMapOf<String, MutableList<Int>>()

        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (chiave in chiavi) {
                val v = o.opt(chiave)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }

        fun classifica(v: String): Int {
            val s = v.trim().uppercase()
            return when {
                s.isEmpty() -> -1
                s.contains("NOLESSON") || s.contains("NO_LESSON") -> -1
                s.contains("ABSENT") || s.contains("ASSENT") || s == "AL" || s.startsWith("HAB") || s.startsWith("ABA") -> 2
                s.contains("LATE") || s.contains("LEAV") || s.contains("RITARD") || s.contains("USCIT") ||
                    s.startsWith("ABR") || s.startsWith("ABU") || s.startsWith("AB") -> 1
                s.contains("PRESENT") || s.contains("PRESEN") || s == "PL" ||
                    s.startsWith("HAT") || s.startsWith("HPR") || s.startsWith("AT") -> 0
                else -> -1
            }
        }

        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val ore = v.optJSONArray("studentStatus")
                    if (ore != null) {
                        val posizione = testo(v, "evtHPos", "hourPos", "evtHour", "hour", "lessonHour").toIntOrNull()
                        if (posizione != null && posizione in 1..9) {
                            for (i in 0 until ore.length()) {
                                val record = ore.optJSONObject(i) ?: continue
                                val studente = record.optJSONObject("student") ?: record
                                val id = testo(record, "studentId", "student_id", "pupilId", "id")
                                    .ifEmpty { testo(studente, "studentId", "student_id", "pupilId", "id") }
                                if (id.isEmpty()) continue
                                val statoRaw = testo(record, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc")
                                    .ifEmpty { testo(studente, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc") }
                                val stato = classifica(statoRaw)
                                val listaOre = risultati.getOrPut(id) { MutableList(9) { -1 } }
                                // Se la risposta contiene più firme nella stessa ora, l'assenza prevale.
                                listaOre[posizione - 1] = when {
                                    stato == 2 -> 2
                                    stato == 1 && listaOre[posizione - 1] != 2 -> 1
                                    stato == 0 && listaOre[posizione - 1] == -1 -> 0
                                    else -> listaOre[posizione - 1]
                                }
                            }
                        }
                    }
                    for (k in v.keys()) {
                        if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                    }
                }
            }
        }
        cammina(radice, 0)
        return risultati
    }

    /**
     * Ritardo breve e uscita anticipata letti ora per ora dalla stessa risposta di [leggiStatiOre]
     * (lessons2/students), invece che dal codice unico dello stato giornaliero: ogni ora ha la sua
     * firma, quindi un ritardo alla prima ora e un'uscita all'ultima restano due segnali distinti
     * anche se lo stato del giorno ne mostrasse solo uno. Ritorna per studente (ha ritardo, ha uscita).
     */
    private fun leggiEventiOraIndipendenti(grezzo: String): Map<String, Pair<Boolean, Boolean>> {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return emptyMap()
        val risultati = mutableMapOf<String, MutableList<Boolean>>() // [haRitardo, haUscita]

        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (chiave in chiavi) {
                val v = o.opt(chiave)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }

        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val ore = v.optJSONArray("studentStatus")
                    if (ore != null) {
                        for (i in 0 until ore.length()) {
                            val record = ore.optJSONObject(i) ?: continue
                            val studente = record.optJSONObject("student") ?: record
                            val id = testo(record, "studentId", "student_id", "pupilId", "id")
                                .ifEmpty { testo(studente, "studentId", "student_id", "pupilId", "id") }
                            if (id.isEmpty()) continue
                            val statoRaw = testo(record, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc")
                                .ifEmpty { testo(studente, "status", "statusDesc", "statusCode", "evtCode", "eventCode", "description", "desc") }
                                .uppercase()
                            val haRitardo = statoRaw.contains("RITARD") || statoRaw.startsWith("ABR")
                            val haUscita = statoRaw.contains("USCIT") || statoRaw.contains("LEAV") || statoRaw.startsWith("ABU")
                            if (!haRitardo && !haUscita) continue
                            val voci = risultati.getOrPut(id) { mutableListOf(false, false) }
                            if (haRitardo) voci[0] = true
                            if (haUscita) voci[1] = true
                        }
                    }
                    for (k in v.keys()) {
                        if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                    }
                }
            }
        }
        cammina(radice, 0)
        return risultati.mapValues { (_, voci) -> voci[0] to voci[1] }
    }

    /** Numero di ore effettive: usa evtHPos + evtDuration, anche se studentStatus è null. */
    private fun leggiNumeroOre(grezzo: String): Int {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return 9
        var massimo = 0
        fun cammina(v: Any?, livello: Int = 0) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val codice = v.opt("evtHPos")?.toString()?.toIntOrNull()
                    val durata = v.opt("evtDuration")?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    if (v.has("studentStatus") && codice != null && codice in 1..9) massimo = maxOf(massimo, (codice + durata - 1).coerceAtMost(9))
                    for (k in v.keys()) cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice)
        return if (massimo in 1..9) massimo else 9
    }

    private fun coloreOraPerStudente(statiOre: Map<String, List<Int>>, studentId: String, ora: Int): Int {
        return when (statiOre[studentId]?.getOrNull(ora) ?: -1) {
            0 -> verdeOra
            1 -> gialloOra
            2 -> rosaOra
            else -> grigioOra
        }
    }

    private fun descrizioneOra(stato: Int): String = when (stato) {
        0 -> "presente"
        1 -> "ritardo o uscita anticipata"
        2 -> "assente"
        else -> "stato non disponibile"
    }

    // ---- «firma la lezione»: classe e data dalla scheda aperta, materia e ora dall'orario o scelte a mano, firma con firmaRapida

    private var fabFirma: ExtendedFloatingActionButton? = null
    /** Vero dal tocco del pulsante fino alla fine delle finestre o della firma: niente seconde richieste. */
    private var firmaInCorso = false
    private var lavoroFirma: Job? = null

    /**
     * Il pulsante c'è solo nel libro del docente (scheda aperta dal pulsante "libro del docente", non dalla tessera della
     * classe), nella sua prima linguetta, e solo se la classe aperta è tra le
     * mie (classes/mine): sulle altre non si può firmare. Nelle linguette delle lezioni le firme hanno «modifica firma» e la X.
     */
    private fun aggiornaPulsanteFirma() {
        fabFirma?.visibility = if (libroDocente && miaClasse && linguettaAttiva == 0) View.VISIBLE else View.GONE
    }

    private fun bloccaFirma(si: Boolean) {
        firmaInCorso = si
        fabFirma?.isEnabled = !si
        fabFirma?.alpha = if (si) .6f else 1f
    }

    /** Dati letti da classeviva prima di proporre la firma. */
    private class PreparazioneFirma(
        val registro: FirmaLezione.Registro,
        val materie: List<String>,
        val analisi: FirmaLezione.Analisi,
        /** lessons/mine del giorno, null se non letto: serve solo a segnalare in anticipo le ore già firmate */
        val lezioniGiorno: String?,
        val mioId: String,
        /** ora dopo l'ultima firma della classe nel giorno (null se non letta o se la classe ha già tutte le ore) */
        val oraDopoUltima: Int?,
    )

    private fun etichettaClasse() = gruppo.ifBlank { nome }

    private fun descriviData(data: java.util.Date) = SimpleDateFormat("EEEE d/M/yyyy", Locale.ITALIAN).format(data)

    private fun preparaFirma() {
        if (firmaInCorso) return
        // classe e data sono quelle della scheda aperta e del giorno scelto nella striscia, fissate al tocco
        val giorno = selezionato.clone() as Calendar
        val data = formatoApi.format(giorno.time)
        val giornoOrario = Orario.giornoDi(giorno)
        val ctx = requireContext().applicationContext
        bloccaFirma(true)
        lavoroFirma = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (codiceClassi, grezzoClassi) = client.classiGrezze(false)
                    if (codiceClassi != 200) throw ClasseVivaException("le tue classi non sono disponibili (http $codiceClassi): ${FirmaLezione.messaggioServer(grezzoClassi)}")
                    val registro = FirmaLezione.registroDaFirmare(leggiClassi(grezzoClassi).map { it.chiave }, tipo, classeId, gruppo)
                        ?: throw ClasseVivaException("${etichettaClasse()} non è tra le tue classi: da qui non si può firmare.")
                    val materie = client.materieMie(registro.tipo, registro.id)
                    if (materie.isEmpty()) throw ClasseVivaException("non trovo tue materie in ${registro.nome} (subjects/list): firma non possibile.")
                    // con "settimana fino a sabato" spenta il sabato non ha orario (le sue caselle restano solo salvate)
                    val sabatoSpento = giornoOrario == 6 && !Impostazioni(ctx).sabato
                    val ore = if (sabatoSpento) emptyList()
                        else Orario(ctx).lezioniDel(giornoOrario).map { FirmaLezione.OraOrario(it.ora, it.classe, it.materia) }
                    val analisi = FirmaLezione.analizzaOrario(ore, registro, materie)
                    val (codiceLezioni, lezioni) = client.lezioniGrezze(data, data)
                    // senza l'orario si propone l'ora dopo l'ultima firma del registro della classe
                    val oraDopo = if (analisi.proposte.isNotEmpty()) null else runCatching {
                        val (codiceClasse, firmeClasse) = client.lezioniClasseGrezze(registro.tipo, registro.id, data)
                        if (codiceClasse == 200) FirmaLezione.oraDopoUltimaFirma(firmeClasse, data) else null
                    }.getOrNull()
                    PreparazioneFirma(registro, materie, analisi, lezioni.takeIf { codiceLezioni == 200 }, client.idDocente(), oraDopo)
                }
            }
            if (view == null) { firmaInCorso = false; return@launch }
            r.fold(
                onSuccess = { p -> scegliLezione(p, giorno, data) },
                onFailure = { e ->
                    bloccaFirma(false)
                    mostraErroreFirma("firma non possibile", FirmaLezione.descriviErrore(e), null)
                }
            )
        }
    }

    /** "3ª ora" o, per più ore, "3ª–4ª ora". */
    private fun etichettaOre(ora: Int, ore: Int) = if (ore <= 1) "${ora}ª ora" else "${ora}ª–${ora + ore - 1}ª ora"

    /**
     * Caso A: una lezione nell'orario (anche di più ore consecutive) → finestra di firma già compilata; caso B: più lezioni →
     * prima la scelta; caso C: orario vuoto o incompleto → finestra con l'ora dopo l'ultima firma della classe o da scegliere.
     */
    private fun scegliLezione(p: PreparazioneFirma, giorno: Calendar, data: String) {
        val blocchi = FirmaLezione.blocchi(p.analisi.proposte)
        when {
            blocchi.size == 1 -> finestraFirma(p, blocchi[0], giorno, data, "dall'orario delle lezioni.")
            blocchi.size > 1 -> {
                val voci = blocchi.map { b ->
                    val firmate = FirmaLezione.oreGiaFirmate(p.lezioniGiorno, p.mioId, data, b.ora, b.ore)
                    "${etichettaOre(b.ora, b.ore)} · ${b.materia.lowercase()}" + if (firmate.isNotEmpty()) " · già firmata" else ""
                }.toTypedArray()
                var scelta = blocchi.indexOfFirst { FirmaLezione.oreGiaFirmate(p.lezioniGiorno, p.mioId, data, it.ora, it.ore).isEmpty() }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("quale lezione firmi? · ${p.registro.nome}")
                    .setSingleChoiceItems(voci, scelta) { _, i -> scelta = i }
                    .setPositiveButton("avanti") { _, _ -> finestraFirma(p, blocchi[scelta], giorno, data, "dall'orario delle lezioni.") }
                    .setNeutralButton("altra ora") { _, _ -> finestraFirma(p, null, giorno, data, "") }
                    .setNegativeButton("annulla") { _, _ -> bloccaFirma(false) }
                    .setOnCancelListener { bloccaFirma(false) }
                    .show()
            }
            p.oraDopoUltima != null && p.analisi.oreSenzaMateria.isEmpty() ->
                // l'orario non indica la classe: ora subito dopo l'ultima firma del giorno; con una sola materia è già scelta
                finestraFirma(p, FirmaLezione.Blocco(p.oraDopoUltima, 1, p.materie.singleOrNull().orEmpty()), giorno, data,
                    "ora proposta: la prima dopo l'ultima firma di ${p.registro.nome} in questo giorno.")
            else -> finestraFirma(p, p.analisi.oreSenzaMateria.firstOrNull()?.let { FirmaLezione.Blocco(it, 1, "") }, giorno, data,
                if (p.analisi.oreSenzaMateria.isEmpty()) "nell'orario non c'è ${p.registro.nome}: scegli materia e ora."
                else "nell'orario manca una tua materia per ${p.registro.nome}: controlla materia e ora.")
        }
    }

    /** Valori scelti nella finestra di firma. */
    private data class SceltaFirma(
        val ora: Int, val ore: Int, val materia: String, val tipoAttivita: String,
        val progetto: FirmaLezione.Progetto?, val argomento: String,
        /** valore dell'ora (peso_ora), UDA e fase: gli altri campi della firma del sito */
        val altri: FirmaLezione.CampiFirma = FirmaLezione.CampiFirma(),
    )

    /** Tipi attività e progetti letti dal registro web, per registro ("tipo|id"): si leggono una volta per scheda. */
    private val opzioniWeb = HashMap<String, FirmaLezione.OpzioniFirma>()

    /**
     * La finestra di firma, come "nuova lezione" del registro web: classe e data della scheda, materia, ora e numero di ore
     * (una sola firma per più ore consecutive), tipo attività, progetto di scuola e territorio per PCTO/FSL (con ricerca)
     * e argomento. Le ore già firmate da me si segnalano subito e non si possono inviare. Il pulsante "firma" invia.
     */
    private fun finestraFirma(p: PreparazioneFirma, iniziale: FirmaLezione.Blocco?, giorno: Calendar, data: String, nota: String) {
        val ctx = requireContext()
        val rosso = 0xFFD32F2F.toInt()
        fun etichetta(s: String) = TextView(ctx).apply { text = s; setTextColor(azzurro); textSize = 14f; typeface = outfitBold }
        fun spinner(voci: List<String>) = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, voci)
        }
        fun alCambio(sp: android.widget.Spinner, azione: () -> Unit) {
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = azione()
                override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
            }
        }

        // materia: solo le mie della classe (subjects/list)
        val materia = spinner(p.materie.map { it.lowercase() }).apply {
            setSelection(p.materie.indexOfFirst { FirmaLezione.normalizza(it) == FirmaLezione.normalizza(iniziale?.materia.orEmpty()) }.coerceAtLeast(0))
        }
        // ora d'inizio e numero di ore (fino all'ultima ora del giorno)
        val oreGiorno = (1..Orario.ORE).toList()
        val ora = spinner(oreGiorno.map { "${it}ª ora" }).apply { setSelection(oreGiorno.indexOf(iniziale?.ora ?: 1).coerceAtLeast(0)) }
        val numeroOre = android.widget.Spinner(ctx)
        fun oraScelta() = oreGiorno.getOrElse(ora.selectedItemPosition) { 1 }
        fun numeroScelto() = numeroOre.selectedItemPosition.coerceAtLeast(0) + 1
        fun aggiornaNumeroOre(desiderate: Int) {
            val massimo = (Orario.ORE - oraScelta() + 1).coerceIn(1, 6)
            numeroOre.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                (1..massimo).map { if (it == 1) "1 ora" else "$it ore" })
            numeroOre.setSelection((desiderate.coerceIn(1, massimo)) - 1)
        }
        aggiornaNumeroOre(iniziale?.ore ?: 1)
        val avvisoOre = TextView(ctx).apply { setTextColor(rosso); textSize = 13f; visibility = View.GONE }
        fun controllaOre() {
            val firmate = FirmaLezione.oreGiaFirmate(p.lezioniGiorno, p.mioId, data, oraScelta(), numeroScelto())
            avvisoOre.text = "già firmata da te: " + firmate.entries.joinToString { "${it.key}ª ora (${it.value.lowercase()})" }
            avvisoOre.visibility = if (firmate.isEmpty()) View.GONE else View.VISIBLE
        }
        alCambio(ora) { aggiornaNumeroOre(numeroScelto()); controllaOre() }
        alCambio(numeroOre) { controllaOre() }

        // tipo attività e progetti: dal registro web (come il sito), finché non arrivano solo "Lezione"
        val chiave = "${p.registro.tipo}|${p.registro.id}"
        val web = p.registro.tipo.equals("C", true) || p.registro.tipo.equals("G", true)
        var opzioni = opzioniWeb[chiave]
        var tipi = opzioni?.tipiAttivita?.ifEmpty { null } ?: listOf("Lezione")
        val tipo = spinner(tipi)
        val caricamento = TextView(ctx).apply {
            text = "carico tipi attività e progetti dal registro…"; textSize = 12f; alpha = .7f
            visibility = if (opzioni == null && web) View.VISIBLE else View.GONE
        }
        var progetto: FirmaLezione.Progetto? = null
        val scegliProgetto = TextView(ctx).apply {
            text = "tocca per scegliere il progetto"; textSize = 15f; setTextColor(0xFF111111.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); cornerRadius = dp(10).toFloat() }
        }
        val sezioneProgetto = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(etichetta("progetto (scuola e territorio)"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(scegliProgetto, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        fun tipoScelto() = tipi.getOrElse(tipo.selectedItemPosition) { "Lezione" }
        fun aggiornaProgetto() {
            val serve = FirmaLezione.richiedeProgetto(tipoScelto()) && (opzioni?.progettiAttivi ?: true)
            sezioneProgetto.visibility = if (serve) View.VISIBLE else View.GONE
            if (!serve) { progetto = null; scegliProgetto.text = "tocca per scegliere il progetto" }
        }
        alCambio(tipo) { aggiornaProgetto() }
        scegliProgetto.setOnClickListener {
            val elenco = opzioni?.progetti.orEmpty()
            if (elenco.isEmpty()) {
                android.widget.Toast.makeText(ctx, if (opzioni == null) "progetti in caricamento…" else "nessun progetto nel registro.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sceltaProgetto(elenco, p.registro.nome) { scelto -> progetto = scelto; scegliProgetto.text = scelto.nome }
        }

        val argomento = android.widget.EditText(ctx).apply {
            hint = "argomento della lezione"
            minLines = 2; maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        // valore dell'ora, come i pulsanti "mezz'ora / un'ora / un'ora e mezza" del sito (predefinito un'ora)
        val valore = spinner(FirmaLezione.PESI_ORA.map { it.second }).apply {
            setSelection(FirmaLezione.PESI_ORA.indexOfFirst { it.first == "1" }.coerceAtLeast(0))
        }
        // uda: le unità di apprendimento che il registro propone per materia e classe; la riga compare solo se ce ne sono
        var udaElenco = emptyList<FirmaLezione.Uda>()
        val uda = android.widget.Spinner(ctx)
        val sezioneUda = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(etichetta("uda"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(uda, LinearLayout.LayoutParams(-1, -2))
        }
        fun udaScelta() = (uda.selectedItemPosition - 1).takeIf { it >= 0 }?.let { udaElenco.getOrNull(it) }
        var udaLavoro: kotlinx.coroutines.Job? = null
        fun caricaUda() {
            if (!web) return
            val nomeMateria = p.materie.getOrNull(materia.selectedItemPosition) ?: return
            udaLavoro?.cancel()
            udaLavoro = lifecycleScope.launch {
                val lette = withContext(Dispatchers.IO) {
                    runCatching { client.udaFirma(p.registro.tipo, p.registro.id, p.registro.nome, nomeMateria) }.getOrDefault(emptyList())
                }
                udaElenco = lette
                uda.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf("nessuna uda") + lette.map { it.nome })
                uda.setSelection(lette.indexOfFirst { it.corrente } + 1)
                sezioneUda.visibility = if (lette.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        alCambio(materia) { caricaUda() }
        val errore = TextView(ctx).apply { setTextColor(rosso); textSize = 13f; visibility = View.GONE }

        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(8))
            addView(TextView(ctx).apply { text = "${p.registro.nome} · ${descriviData(giorno.time)}"; textSize = 16f; typeface = outfitBold; setTextColor(0xFF111111.toInt()) })
            if (nota.isNotEmpty()) addView(TextView(ctx).apply { text = nota; textSize = 13f; alpha = .7f; setPadding(0, dp(2), 0, 0) })
            addView(etichetta("materia"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(materia, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(etichetta("dalla"))
                    addView(ora, LinearLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(etichetta("per"))
                    addView(numeroOre, LinearLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(avvisoOre)
            addView(etichetta("tipo attività"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(tipo, LinearLayout.LayoutParams(-1, -2))
            addView(caricamento)
            addView(sezioneProgetto)
            addView(sezioneUda)
            addView(etichetta("valore"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(valore, LinearLayout.LayoutParams(-1, -2))
            addView(etichetta("argomento"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(argomento, LinearLayout.LayoutParams(-1, -2))
            addView(errore, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val dialogo = MaterialAlertDialogBuilder(ctx)
            .setTitle("firma la lezione")
            .setView(ScrollView(ctx).apply { addView(corpo) })
            .setPositiveButton("firma", null)
            .setNegativeButton("annulla") { _, _ -> bloccaFirma(false) }
            .setOnCancelListener { bloccaFirma(false) }
            .show()
        controllaOre()
        // "firma" controlla i dati prima di chiudere la finestra: con un errore la finestra resta aperta
        dialogo.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val m = p.materie.getOrNull(materia.selectedItemPosition)
            val firmate = FirmaLezione.oreGiaFirmate(p.lezioniGiorno, p.mioId, data, oraScelta(), numeroScelto())
            val serveProgetto = sezioneProgetto.visibility == View.VISIBLE
            val messaggio = when {
                m == null -> "scegli la materia."
                firmate.isNotEmpty() -> "una delle ore scelte è già firmata da te: cambia ora o numero di ore."
                serveProgetto && opzioni == null && web -> "attendi il caricamento dei progetti."
                serveProgetto && progetto == null && opzioni?.progetti.orEmpty().isNotEmpty() -> "per ${tipoScelto()} scegli il progetto di scuola e territorio."
                else -> null
            }
            if (messaggio != null) { errore.text = messaggio; errore.visibility = View.VISIBLE; return@setOnClickListener }
            dialogo.dismiss()
            val u = udaScelta()
            val altri = FirmaLezione.CampiFirma(
                pesoOra = FirmaLezione.PESI_ORA.getOrNull(valore.selectedItemPosition)?.first ?: "1",
                udaId = u?.id.orEmpty(), faseId = u?.fasi?.firstOrNull()?.first.orEmpty()
            )
            inviaFirma(p.registro, SceltaFirma(oraScelta(), numeroScelto(), m!!, tipoScelto(), progetto, argomento.text.toString(), altri), data)
        }

        if (opzioni == null && web) lifecycleScope.launch {
            val lette = withContext(Dispatchers.IO) {
                runCatching {
                    val (codice, html) = client.paginaRegistroWeb(if (p.registro.tipo.equals("G", true)) "" else p.registro.id,
                        if (p.registro.tipo.equals("G", true)) p.registro.nome else "", data)
                    if (codice in 200..299) FirmaLezione.leggiOpzioniFirma(html).takeIf { it.tipiAttivita.isNotEmpty() } else null
                }.getOrNull()
            }
            if (lette != null) opzioniWeb[chiave] = lette
            if (!dialogo.isShowing) return@launch
            if (lette == null) { caricamento.text = "tipi attività e progetti non letti dal registro: si firma come «lezione»."; return@launch }
            opzioni = lette
            caricamento.visibility = View.GONE
            val prima = tipoScelto()
            tipi = lette.tipiAttivita
            tipo.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tipi)
            tipo.setSelection(tipi.indexOf(prima).coerceAtLeast(0))
            aggiornaProgetto()
        }
    }

    /** Elenco dei progetti di scuola e territorio con ricerca; quelli che nominano la classe vengono prima. */
    private fun sceltaProgetto(progetti: List<FirmaLezione.Progetto>, sigla: String, alla: (FirmaLezione.Progetto) -> Unit) {
        val ctx = requireContext()
        var mostrati = FirmaLezione.cercaProgetti(progetti, "", sigla)
        val adattatore = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mostrati.map { it.nome }.toMutableList())
        val conteggio = TextView(ctx).apply { textSize = 12f; alpha = .7f; text = "${mostrati.size} progetti" }
        val cerca = android.widget.EditText(ctx).apply { hint = "cerca (es. 4eta, orientamento)"; setSingleLine(true) }
        val elenco = android.widget.ListView(ctx).apply { adapter = adattatore }
        cerca.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                mostrati = FirmaLezione.cercaProgetti(progetti, s?.toString().orEmpty(), sigla)
                adattatore.clear(); adattatore.addAll(mostrati.map { it.nome })
                conteggio.text = if (mostrati.size == 1) "1 progetto" else "${mostrati.size} progetti"
            }
        })
        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(cerca, LinearLayout.LayoutParams(-1, -2))
            addView(conteggio)
            addView(elenco, LinearLayout.LayoutParams(-1, dp(360)))
        }
        val dialogo = MaterialAlertDialogBuilder(ctx)
            .setTitle("progetto di scuola e territorio")
            .setView(corpo)
            .setNegativeButton("annulla", null)
            .show()
        elenco.setOnItemClickListener { _, _, pos, _ -> mostrati.getOrNull(pos)?.let(alla); dialogo.dismiss() }
    }

    /** Esito di una firma inviata: risposta del server (con la verifica su lessons/mine) oppure ore trovate già firmate. */
    private sealed class RisultatoFirma {
        class GiaFirmata(val descrizione: String) : RisultatoFirma()
        class Risposta(val codice: Int, val testo: String, val verificata: Boolean?) : RisultatoFirma()
    }

    private fun inviaFirma(registro: FirmaLezione.Registro, s: SceltaFirma, data: String) {
        bloccaFirma(true)
        val ctx = requireContext().applicationContext
        val quando = etichettaOre(s.ora, s.ore)
        view?.let { Snackbar.make(it, "firma in corso…", Snackbar.LENGTH_SHORT).setAnchorView(fabFirma).show() }
        val log = StringBuilder("firma dal libro del docente classePiù ${BuildConfig.VERSION_NAME}\n")
            .append("data $data, $quando (${s.ore} ore), registro ${registro.tipo}|${registro.id}|${registro.nome}, materia ${s.materia}, ")
            .append("attività ${s.tipoAttivita}, progetto ${s.progetto?.let { "${it.id} ${it.nome}" } ?: "nessuno"}, argomento \"${s.argomento.trim()}\", ")
            .append("valore ${s.altri.pesoOra}, uda ${s.altri.udaId.ifEmpty { "nessuna" }}\n\n")
        lavoroFirma = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val esito = runCatching {
                    val mioId = client.idDocente()
                    // controllo appena prima di inviare: se nel frattempo una delle ore è stata firmata non si manda niente
                    val (codicePrima, prima) = client.lezioniGrezze(data, data)
                    log.append("GET lessons/mine/$data/$data -> HTTP $codicePrima\n\n")
                    if (codicePrima != 200) throw ClasseVivaException("non riesco a controllare le firme del giorno (http $codicePrima): firma non inviata.\n${FirmaLezione.messaggioServer(prima)}")
                    val firmate = FirmaLezione.oreGiaFirmate(prima, mioId, data, s.ora, s.ore)
                    if (firmate.isNotEmpty()) return@runCatching RisultatoFirma.GiaFirmata(firmate.entries.joinToString { "${it.key}ª ora (${it.value.lowercase()})" })
                    val (codice, testo) = client.firmaRapida(registro.tipo, registro.id, s.materia, data, s.ora, log,
                        nomeClasse = registro.nome, argomento = s.argomento, tipoAttivita = s.tipoAttivita,
                        numeroOre = s.ore, idProgetto = s.progetto?.id.orEmpty(), altri = s.altri)
                    var verificata: Boolean? = null
                    if (codice in 200..299) {
                        val (codiceDopo, dopo) = runCatching { client.lezioniGrezze(data, data) }.getOrDefault(0 to "")
                        log.append("verifica lessons/mine -> HTTP $codiceDopo\n")
                        if (codiceDopo == 200) verificata = FirmaLezione.oreGiaFirmate(dopo, mioId, data, s.ora, s.ore).size == s.ore
                    }
                    RisultatoFirma.Risposta(codice, testo, verificata)
                }
                log.append(esito.fold({ x -> when (x) {
                    is RisultatoFirma.GiaFirmata -> "esito: già firmata ${x.descrizione}, non inviata"
                    is RisultatoFirma.Risposta -> "esito: HTTP ${x.codice} ${x.testo.take(300)}" + (x.verificata?.let { if (it) " · verificata" else " · non trovata in lessons/mine" } ?: "")
                } }, { "esito: errore ${it.javaClass.simpleName} ${it.message}" }))
                runCatching { Diario.scrivi(ctx, "firma libro del docente ${registro.nome} $data $quando: ${log.lines().last()}") }
                esito
            }
            bloccaFirma(false)
            if (view == null) return@launch
            r.fold(
                onSuccess = { risultato ->
                    when (risultato) {
                        is RisultatoFirma.GiaFirmata -> mostraErroreFirma("ora già firmata",
                            "risulta già firmata da te: ${risultato.descrizione}. firma non inviata.", log.toString())
                        is RisultatoFirma.Risposta -> {
                            val e = FirmaLezione.esito(risultato.codice, risultato.testo)
                            when {
                                !e.ok -> mostraErroreFirma(e.titolo, e.dettaglio + if (e.giaFirmata) "\n\nl'ora risulta già firmata sul registro." else "", log.toString())
                                risultato.verificata == false -> mostraErroreFirma(e.titolo,
                                    "classeviva ha accettato la firma, ma non la vedo ancora tra le tue lezioni: controlla sul registro.", log.toString())
                                else -> view?.let {
                                    Snackbar.make(it, "${e.titolo}: ${registro.nome}, $quando" + if (risultato.verificata == true) ", verificata" else "",
                                        Snackbar.LENGTH_LONG).setAnchorView(fabFirma).show()
                                }
                            }
                            if (e.ok) aggiornaDopoFirma()
                        }
                    }
                },
                onFailure = { mostraErroreFirma("firma non riuscita", FirmaLezione.descriviErrore(it), log.toString()) }
            )
        }
    }

    /** Dopo una firma si rileggono i dati della linguetta aperta (presenze per ora o elenco lezioni). */
    private fun aggiornaDopoFirma() {
        if (linguettaAttiva == 0) { caricaStato(); ridisegna() }
        else (childFragmentManager.findFragmentById(idLezioni) as? ListaScheda)?.ricarica()
    }

    /** Errore con il suo messaggio; con [log] si può copiare il registro tecnico della richiesta. */
    private fun mostraErroreFirma(titolo: String, messaggio: String, log: String?) {
        val ctx = context ?: return
        val dialogo = MaterialAlertDialogBuilder(ctx).setTitle(titolo).setMessage(messaggio).setPositiveButton("chiudi", null)
        if (log != null) dialogo.setNeutralButton("copia log") { _, _ ->
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("firma", log))
            android.widget.Toast.makeText(ctx, "log copiato.", android.widget.Toast.LENGTH_SHORT).show()
        }
        dialogo.show()
    }

    private fun testo(s: String, codice: Int = 0, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(requireContext()).apply {
            text = s; textSize = 15f; typeface = outfitRegular; setTextColor(0xFF555555.toInt())
            setPadding(dp(8), dp(24), dp(8), 0)
        })
        if (corpo.isNotEmpty()) copiaRisposta("mostra la risposta di classeviva (tocca per copiare)", codice, corpo)
    }

    private fun copiaRisposta(etichetta: String, codice: Int, corpo: String) {
        lista.addView(TextView(requireContext()).apply {
            text = etichetta; textSize = 14f; typeface = outfitRegular; setTextColor(azzurro)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}

/**
 * Striscia di linguette a cartella: sfondo grigio con gli angoli alti arrotondati, quarti di cerchio tra le linguette,
 * linguetta attiva dello stesso colore del pannello sotto (così le due si fondono).
 */
class LinguetteView(
    ctx: android.content.Context,
    private val font: Typeface,
    private var voci: List<String>,
    attivaIniziale: Int,
    private val colorePannello: Int,
    private val alTocco: (Int) -> Unit,
) : View(ctx) {
    private var attiva = attivaIniziale
    private val d = ctx.resources.displayMetrics.density
    private val pennaFondo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = androidx.core.graphics.ColorUtils.blendARGB(0xFFC4C4C4.toInt(), 0xFF69BCE8.toInt(), 0.2f) }
    private val pennaAttiva = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = androidx.core.graphics.ColorUtils.blendARGB(colorePannello, 0xFF69BCE8.toInt(), 0.2f) }
    private val pennaBordo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA9A9A9.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val pennaTesto = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt(); typeface = font; textSize = 15f * d; textAlign = android.graphics.Paint.Align.CENTER
    }
    private val r = 18f * d

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (40 * d).toInt())

    /** Cambia le voci mostrate (es. togliere "le mie lezioni"); [attivaNuova] è la posizione della linguetta attiva. */
    fun impostaVoci(nuove: List<String>, attivaNuova: Int) { voci = nuove; attiva = attivaNuova.coerceIn(0, nuove.size - 1); invalidate() }

    private fun larghezza() = width.toFloat() / voci.size

    /** Quarto di cerchio tra due linguette in [xb]: dall'alto (xb, 0) al basso (xb - h, h), centro in (xb - h, 0), convesso verso il basso a destra. */
    private fun android.graphics.Path.arcoGiu(xb: Float, h: Float) {
        arcTo(android.graphics.RectF(xb - 2 * h, -h, xb, h), 0f, 90f)
    }

    /** Lo stesso quarto di cerchio percorso dal basso verso l'alto. */
    private fun android.graphics.Path.arcoSu(xb: Float, h: Float) {
        arcTo(android.graphics.RectF(xb - 2 * h, -h, xb, h), 90f, -90f)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val lw = larghezza()
        // fondo grigio con i due angoli alti arrotondati
        val fondo = android.graphics.Path().apply {
            addRoundRect(0f, 0f, w, h + r, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
        }
        c.save(); c.clipRect(0f, 0f, w, h); c.drawPath(fondo, pennaFondo); c.restore()
        // quarti di cerchio tra le linguette
        for (k in 1 until voci.size) {
            val xb = lw * k
            c.drawPath(android.graphics.Path().apply { moveTo(xb, 0f); arcoGiu(xb, h) }, pennaBordo)
        }
        // linguetta attiva
        val xl = lw * attiva; val xr = xl + lw
        val a = android.graphics.Path().apply {
            if (attiva == 0) { moveTo(0f, h); lineTo(0f, r); quadTo(0f, 0f, r, 0f) }
            else { moveTo(xl - h, h); arcoSu(xl, h) }
            if (attiva == voci.size - 1) { lineTo(w - r, 0f); quadTo(w, 0f, w, r); lineTo(w, h) }
            else { lineTo(xr, 0f); arcoGiu(xr, h) }
            close()
        }
        c.drawPath(a, pennaAttiva)
        // stesso carattere per tutte le voci: il più grande (fino a 19) in cui la voce più lunga sta nella sua linguetta
        var dimensione = 19f
        pennaTesto.textSize = dimensione * d
        while (dimensione > 11f && voci.maxOf { pennaTesto.measureText(it) } > lw - 20f * d) { dimensione -= 0.5f; pennaTesto.textSize = dimensione * d }
        val y = h / 2f - (pennaTesto.ascent() + pennaTesto.descent()) / 2f
        // le linguette di mezzo hanno entrambi i bordi curvi verso sinistra: l'etichetta va spostata di 8 dp a sinistra per restare al centro
        voci.forEachIndexed { i, t -> c.drawText(t, lw * i + lw / 2f - (if (i in 1 until voci.size - 1) 8f * d else 0f), y, pennaTesto) }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            val i = (e.x / larghezza()).toInt().coerceIn(0, voci.size - 1)
            if (i != attiva) { attiva = i; invalidate(); alTocco(i) }
        }
        return true
    }
}
