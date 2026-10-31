package it.cellini.classepiu

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import java.util.Calendar

/**
 * Notifiche dell'orario mostrate solo al rientro in primo piano: fino a tre schede sovrapposte, la più vecchia in alto e la
 * più nuova in basso, ognuna col suo stato esteso/ridotto, i suoi timer e la sua dissolvenza dopo circa 10 secondi.
 * A ogni rientro si ricrea il piano dall'orario del giorno ([PianoNotifiche]); se l'utente aveva già scelto la modalità
 * compatta, tutte le schede vengono ripristinate ridotte e il gruppo resta sopra la barra delle pagine.
 * Le schede stanno in un'unica finestra (Dialog), tolta quando sparisce l'ultima scheda o quando l'Activity va in background:
 * così non restano finestre agganciate a un'Activity non più valida.
 */
object FinestraRientro {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null

    // MODIFICA: stato compatto persistente tra sospensioni e successivi risvegli.
    private const val PREFERENZE_COMPATTE = "classepiu_notifiche"
    private const val CHIAVE_MODALITA_COMPATTA = "modalita_compatta"
    private const val PREFERENZE_STATI_NOTIFICHE = "classepiu_stati_notifiche"

    private fun chiaveStato(notifica: NotificaPianificata): String =
        "stato_${notifica.ora}:${notifica.comparsa}:${notifica.scadenza}"

    private fun leggiStatiNotifiche(activity: Activity): Map<String, Boolean> {
        val tutti = activity.getSharedPreferences(PREFERENZE_STATI_NOTIFICHE, Activity.MODE_PRIVATE).all
        return tutti.mapNotNull { (chiave, valore) ->
            if (chiave.startsWith("stato_") && valore is Boolean) chiave.removePrefix("stato_") to valore else null
        }.toMap()
    }

    private fun salvaStatoNotifica(activity: Activity, notifica: NotificaPianificata, ridotta: Boolean) {
        activity.getSharedPreferences(PREFERENZE_STATI_NOTIFICHE, Activity.MODE_PRIVATE)
            .edit().putBoolean(chiaveStato(notifica), ridotta).apply()
    }
    private const val DURATA_SPOSTAMENTO_COMPATTO_MS = 1000L
    private const val DURATA_ANIMAZIONE_RIDUZIONE_MS = 420L
    private const val PREFERENZE_SWIPATE = "classepiu_notifiche_swippate"
    private const val PREFERENZE_CANCELLATE = "classepiu_notifiche_cancellate"
    private val formatoGiorno = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
    private var scadenzaSwippateProgrammata: Runnable? = null
    private var superficieAttiva: FrameLayout? = null
    private var scorrimentoAttivo: ScrollView? = null
    private var campanellaDialog: CampanellaSwipateView? = null // MODIFICA
    /** Activity che ha aperto le notifiche: alla chiusura del Dialog la sua campanella torna visibile. */
    private var attivitaNotifiche: java.lang.ref.WeakReference<Activity>? = null
    /** Scorrimento in corso delle schede rimaste dopo uno swipe (una per scheda). */
    private val scorrimentiSchede = java.util.WeakHashMap<View, android.animation.Animator>()
    /** Scorrimento in corso dell'intera colonna dopo che una scheda è stata tolta. */
    private var animazioneColonna: android.animation.Animator? = null
    /**
     * Notifiche sparite da sole a fine tempo ("ora:comparsa:scadenza"): accendono la campanella ma, a differenza dello
     * swipe, al risveglio successivo ricompaiono da sole. Solo in memoria: valgono fino al prossimo rientro.
     */
    private val dissolte = mutableSetOf<String>()
    private var preferenzeCompatte: android.content.SharedPreferences? = null
    private var posizioneCompatta = false
    private val aggiornaDopoRiduzione = Runnable { applicaDisposizioneCompatta(animate = true) }
    /** Cresce a ogni rientro e a ogni chiusura: le risposte di ClasseViva di un rientro vecchio vengono ignorate. */
    private var generazione = 0
    private val gestore = GestoreNotifiche<Avviso> { ms, azione ->
        val r = Runnable { azione() }
        handler.postDelayed(r, ms)
        val annulla: () -> Unit = { handler.removeCallbacks(r) }
        annulla
    }.apply { allaFine = { togliContenitore() } }

    /** Una notifica (scheda bianca estesa + striscia ridotta) con il suo controller. */
    private class Avviso(val lezione: Lezione, val notifica: NotificaPianificata, val scheda: FrameLayout) : GestoreNotifiche.Finestra {
        lateinit var controllo: ControlloNotificaRiducibile
        var applicaVerifica: (Result<EsitoFirma?>) -> Unit = {}

        override fun riduci() = controllo.riduci()

        override fun dissolvi(fatto: () -> Unit) {
            scheda.animate().cancel()
            scheda.animate().alpha(0f).scaleX(.96f).scaleY(.96f)
                .setDuration(GestoreNotifiche.DISSOLVENZA_MS)
                .withEndAction {
                    // sparita da sola: recuperabile con la campanella e di nuovo visibile al prossimo risveglio
                    attivitaNotifiche?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { registraDissolta(it, notifica) }
                    fatto()
                }
                .start()
        }

        // MODIFICA: se l'utente tocca durante i 400 ms finali, la scheda torna opaca e il conteggio riparte.
        override fun annullaDissolvenza() {
            scheda.animate().cancel()
            scheda.alpha = 1f
            scheda.scaleX = 1f
            scheda.scaleY = 1f
        }

        override fun rimuovi() {
            controllo.ferma()
            scheda.animate().cancel()
            val colonna = scheda.parent as? ViewGroup
            // Le schede rimaste scivolano al loro posto invece di saltare.
            val posizioni = if (colonna != null && superficieAttiva != null) posizioniSchede(colonna, scheda) else null
            colonna?.removeView(scheda)
            if (colonna != null && posizioni != null) ricomponiSenzaScatti(colonna, posizioni)
            aggiornaOmbra()
            programmaAggiornamentoDisposizione() // MODIFICA: se restano solo schede ridotte, compatta il gruppo.
            programmaRiverifica() // ogni movimento delle notifiche: si ricontrollano le firme delle rimaste
        }
    }

    private val riverifica = Runnable {
        val activity = attivitaNotifiche?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@Runnable
        if (gestore.finestre.isNotEmpty()) verificaFirme(activity, generazione, gestore.finestre)
    }

    private fun programmaRiverifica(ritardo: Long = 600) {
        handler.removeCallbacks(riverifica)
        handler.postDelayed(riverifica, ritardo)
    }

    /** Chiude subito tutte le notifiche e i loro timer (rientro nuovo, Activity in background o distrutta). */
    fun chiudiTutto() {
        handler.removeCallbacks(aggiornaDopoRiduzione) // MODIFICA
        handler.removeCallbacks(riverifica)
        generazione++
        gestore.chiudiTutto()
        togliContenitore()
    }

    private fun togliContenitore() {
        animazioneColonna?.cancel()
        animazioneColonna = null
        handler.removeCallbacks(aggiornaDopoRiduzione) // MODIFICA
        val d = dialog
        dialog = null
        superficieAttiva = null
        scorrimentoAttivo = null
        campanellaDialog = null
        preferenzeCompatte = null
        posizioneCompatta = false
        runCatching { d?.dismiss() }
        // senza Dialog torna visibile la campanella della fascia azzurra (se ci sono notifiche nascoste)
        val attivita = attivitaNotifiche?.get()
        attivitaNotifiche = null
        if (attivita != null && !attivita.isDestroyed) (attivita as? MainActivity)?.aggiornaCampanella()
    }

    /** Posizione sullo schermo delle schede che restano dopo aver tolto [tolta]. */
    private fun posizioniSchede(colonna: ViewGroup, tolta: View): Map<View, Int> {
        val punto = IntArray(2)
        return (0 until colonna.childCount).map { colonna.getChildAt(it) }.filter { it !== tolta }.associateWith {
            it.getLocationOnScreen(punto)
            punto[1]
        }
    }

    /**
     * Dopo che una scheda è stata tolta la colonna si accorcia e, centrata nella superficie, saltava di metà altezza; poi
     * [spostaColonna] la portava sopra la barra, così le schede rimaste prima si sollevavano e poi scendevano.
     * Qui invece la colonna va subito nella posizione finale (sopra la barra se restano solo ridotte, al centro altrimenti)
     * e ogni scheda rimasta parte da dove era e scivola direttamente al suo posto.
     */
    private fun ricomponiSenzaScatti(colonna: ViewGroup, posizioni: Map<View, Int>) {
        val superficie = superficieAttiva ?: return
        val scorrimento = scorrimentoAttivo ?: return
        val osservatore = scorrimento.viewTreeObserver
        osservatore.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (scorrimento.viewTreeObserver.isAlive) scorrimento.viewTreeObserver.removeOnPreDrawListener(this)
                if (superficieAttiva !== superficie || scorrimentoAttivo !== scorrimento) return true
                val avvisi = gestore.finestre
                if (avvisi.isEmpty()) return true
                val compatta = avvisi.all { it.controllo.ridotta }
                val discesa = compatta && !posizioneCompatta
                // stessa destinazione di spostaColonna; da qui in poi spostaColonna non deve più muovere la colonna
                posizioneCompatta = compatta
                val limiteInferiore = superficie.height - superficie.context.dp(ALTEZZA_BARRA + ALZO_BARRA + 4)
                scorrimento.animate().cancel()
                animazioneColonna?.cancel()
                val destinazione = if (compatta) (limiteInferiore - scorrimento.bottom).toFloat() else 0f
                scorrimento.translationY = destinazione
                // quanto si è spostata ogni scheda rispetto a prima, con la colonna già nella posizione finale
                val punto = IntArray(2)
                val distanze = posizioni.filterKeys { it.parent === colonna }.mapValues { (scheda, yPrima) ->
                    scorrimentiSchede.remove(scheda)?.cancel()
                    scheda.translationY = 0f
                    scheda.getLocationOnScreen(punto)
                    (yPrima - punto[1]).toFloat()
                }
                if (distanze.isEmpty()) return true
                // Lo spostamento comune lo fa la colonna intera (che non taglia le schede); ogni scheda fa solo la sua
                // differenza. Spostare le schede dentro la colonna le faceva uscire dai suoi bordi: sparivano e riapparivano.
                val comune = distanze.values.first()
                fun prepara(animazione: android.animation.Animator) = animazione.apply {
                    // la discesa verso la barra ha la stessa durata e lo stesso andamento di spostaColonna
                    if (discesa) {
                        duration = DURATA_SPOSTAMENTO_COMPATTO_MS
                        interpolator = DecelerateInterpolator()
                    } else {
                        duration = 320L
                        interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    }
                }
                if (comune != 0f) {
                    scorrimento.translationY = destinazione + comune
                    animazioneColonna = prepara(android.animation.ObjectAnimator.ofFloat(
                        scorrimento, View.TRANSLATION_Y, destinazione + comune, destinazione)).also { it.start() }
                }
                for ((scheda, distanza) in distanze) {
                    val propria = distanza - comune
                    if (propria == 0f) continue
                    scheda.translationY = propria
                    val animazione = prepara(android.animation.ObjectAnimator.ofFloat(scheda, View.TRANSLATION_Y, propria, 0f))
                    scorrimentiSchede[scheda] = animazione
                    animazione.start()
                }
                return true
            }
        })
    }

    /** MODIFICA: dopo l'animazione interna di riduzione, porta l'intero gruppo sopra la barra delle pagine. */
    private fun programmaAggiornamentoDisposizione() {
        val avvisi = gestore.finestre
        if (avvisi.isEmpty()) {
            handler.removeCallbacks(aggiornaDopoRiduzione)
            return
        }
        val tutteRidotte = avvisi.all { it.controllo.ridotta }
        if (!tutteRidotte) {
            handler.removeCallbacks(aggiornaDopoRiduzione)
            preferenzeCompatte?.edit()?.putBoolean(CHIAVE_MODALITA_COMPATTA, false)?.apply()
            applicaDisposizioneCompatta(animate = true)
        } else {
            // Salva subito lo stato: anche una sospensione durante l'animazione non lo perde.
            preferenzeCompatte?.edit()?.putBoolean(CHIAVE_MODALITA_COMPATTA, true)?.apply()
            handler.removeCallbacks(aggiornaDopoRiduzione)
            handler.postDelayed(aggiornaDopoRiduzione, DURATA_ANIMAZIONE_RIDUZIONE_MS + 40L)
        }
    }

    /** MODIFICA: applica o ripristina la posizione compatta; al risveglio il ripristino è immediato. */
    private fun applicaDisposizioneCompatta(animate: Boolean) {
        val avvisi = gestore.finestre
        if (avvisi.isEmpty()) return
        val compatta = avvisi.all { it.controllo.ridotta }
        preferenzeCompatte?.edit()?.putBoolean(CHIAVE_MODALITA_COMPATTA, compatta)?.apply()

        val cambiata = posizioneCompatta != compatta
        posizioneCompatta = compatta
        spostaColonna(compatta, animate && cambiata)
    }

    /** MODIFICA: l'ultima scheda termina appena sopra la barra inferiore, lasciandola libera e visibile. */
    private fun spostaColonna(compatta: Boolean, animate: Boolean) {
        val superficie = superficieAttiva ?: return
        val scorrimento = scorrimentoAttivo ?: return

        val applica = {
            if (superficieAttiva !== superficie || scorrimentoAttivo !== scorrimento) {
                Unit
            } else if (!animate && animazioneColonna?.isRunning == true) {
                // la colonna sta già scivolando nella posizione giusta dopo una scheda tolta: non va interrotta
                Unit
            } else {
                val destinazione = if (compatta) {
                    val limiteInferiore = superficie.height -
                        superficie.context.dp(ALTEZZA_BARRA + ALZO_BARRA + 4)
                    (limiteInferiore - scorrimento.bottom).toFloat()
                } else 0f

                animazioneColonna?.cancel()
                scorrimento.animate().cancel()
                if (animate) {
                    scorrimento.animate()
                        .translationY(destinazione)
                        .setDuration(DURATA_SPOSTAMENTO_COMPATTO_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    scorrimento.translationY = destinazione
                }
            }
        }

        if (superficie.height == 0 || scorrimento.height == 0) superficie.post { applica() }
        else applica()
    }

    /** Ombra dietro le notifiche: piena se almeno una è estesa, leggera se sono tutte ridotte. */
    private fun aggiornaOmbra() {
        val d = dialog ?: return
        d.window?.setDimAmount(if (gestore.finestre.any { !it.controllo.ridotta }) .28f else .08f)
    }

    /** Notifiche nascoste con swipe: restano recuperabili fino alla scadenza scolastica originale. */
    private fun chiaveSwipate(): String = "giorno_${formatoGiorno.format(java.util.Date())}"

    private fun leggiSwippate(activity: Activity, adesso: Int): Set<String> {
        val pref = activity.getSharedPreferences(PREFERENZE_SWIPATE, Activity.MODE_PRIVATE)
        val chiave = chiaveSwipate()
        val valide = pref.getStringSet(chiave, emptySet()).orEmpty().filter { voce ->
            val parti = voce.split(':')
            parti.size == 3 && parti[2].toIntOrNull()?.let { it > adesso } == true
        }.toSet()
        if (valide.size != pref.getStringSet(chiave, emptySet()).orEmpty().size) {
            pref.edit().putStringSet(chiave, valide).apply()
        }
        return valide
    }

    /** La X è una cancellazione definitiva: non deve essere recuperata insieme alle notifiche swippate. */
    private fun registraCancellazione(activity: Activity, notifica: NotificaPianificata) {
        val pref = activity.getSharedPreferences(PREFERENZE_CANCELLATE, Activity.MODE_PRIVATE)
        val chiave = chiaveSwipate()
        val elementi = pref.getStringSet(chiave, emptySet()).orEmpty().toMutableSet()
        elementi.add("${notifica.ora}:${notifica.comparsa}:${notifica.scadenza}")
        pref.edit().putStringSet(chiave, elementi).apply()
    }

    private fun leggiCancellate(activity: Activity, adesso: Int): Set<String> {
        val pref = activity.getSharedPreferences(PREFERENZE_CANCELLATE, Activity.MODE_PRIVATE)
        val chiave = chiaveSwipate()
        val valide = pref.getStringSet(chiave, emptySet()).orEmpty().filter { voce ->
            val parti = voce.split(':')
            parti.size == 3 && parti[2].toIntOrNull()?.let { it > adesso } == true
        }.toSet()
        if (valide.size != pref.getStringSet(chiave, emptySet()).orEmpty().size) {
            pref.edit().putStringSet(chiave, valide).apply()
        }
        return valide
    }

    private fun registraSwipe(activity: Activity, notifica: NotificaPianificata) {
        val pref = activity.getSharedPreferences(PREFERENZE_SWIPATE, Activity.MODE_PRIVATE)
        val chiave = chiaveSwipate()
        val elementi = pref.getStringSet(chiave, emptySet()).orEmpty().toMutableSet()
        elementi.removeAll { it.startsWith("${notifica.ora}:${notifica.comparsa}:") }
        elementi.add("${notifica.ora}:${notifica.comparsa}:${notifica.scadenza}")
        pref.edit().putStringSet(chiave, elementi).apply()
        aggiornaIndicatore(activity)
        programmaScadenzaSwippate(activity)
    }

    private fun registraDissolta(activity: Activity, notifica: NotificaPianificata) {
        dissolte.add("${notifica.ora}:${notifica.comparsa}:${notifica.scadenza}")
        aggiornaIndicatore(activity)
    }

    /** MODIFICA: fa sparire la campanella automaticamente alla scadenza dell'ultima notifica nascosta. */
    private fun programmaScadenzaSwippate(activity: Activity) {
        scadenzaSwippateProgrammata?.let { handler.removeCallbacks(it) }
        scadenzaSwippateProgrammata = null
        val adesso = Calendar.getInstance()
        val secondi = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
        // anche le notifiche sparite a fine tempo tengono accesa la campanella fino alla loro scadenza
        val scadenze = (leggiSwippate(activity, secondi) + dissolte)
            .mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.filter { it > secondi }
        val prossima = scadenze.minOrNull() ?: run {
            // niente da tenere accesa: si aggiorna la campanella una sola volta, senza richiamarsi (prima andava in loop
            // quando c'erano solo notifiche sparite a fine tempo e l'app si chiudeva)
            dissolte.clear()
            (activity as? MainActivity)?.aggiornaCampanella()
            campanellaDialog?.let { (it.parent as? ViewGroup)?.removeView(it) }
            campanellaDialog = null
            return
        }
        val riferimento = java.lang.ref.WeakReference(activity)
        val r = Runnable {
            scadenzaSwippateProgrammata = null
            riferimento.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { attivita ->
                val ora = Calendar.getInstance()
                val sec = (ora.get(Calendar.HOUR_OF_DAY) * 60 + ora.get(Calendar.MINUTE)) * 60 + ora.get(Calendar.SECOND)
                leggiSwippate(attivita, sec)
                aggiornaIndicatore(attivita)
                programmaScadenzaSwippate(attivita)
            }
        }
        scadenzaSwippateProgrammata = r
        handler.postDelayed(r, ((prossima - secondi).coerceAtLeast(1) * 1000L))
    }

    private fun aggiornaIndicatore(activity: Activity) {
        // prima il Dialog: la fascia azzurra nasconde la sua campanella se c'è già quella sopra le notifiche
        aggiornaCampanellaDialog(activity)
        (activity as? MainActivity)?.aggiornaCampanella()
    }

    /** true mentre la campanella è disegnata sopra le notifiche: quella della fascia azzurra resta nascosta. */
    val campanellaSopraNotifiche: Boolean get() = campanellaDialog != null

    /** MODIFICA: mantiene cliccabile e visibile la campanella anche sopra il Dialog a schermo intero. */
    private fun aggiornaCampanellaDialog(activity: Activity) {
        val superficie = superficieAttiva ?: return
        val presente = haNotificheSwippate(activity)
        if (presente && campanellaDialog == null) {
            val indicatore = CampanellaSwipateView(activity).apply {
                visibility = View.INVISIBLE // compare solo quando è allineata a quella della fascia
                setOnClickListener { ripristinaSwippate(activity) }
            }
            campanellaDialog = indicatore
            superficie.addView(indicatore, FrameLayout.LayoutParams(activity.dp(26), activity.dp(26), Gravity.TOP or Gravity.START))
            allineaCampanella(activity, superficie, indicatore, 0)
        } else if (!presente) {
            campanellaDialog?.let { (it.parent as? ViewGroup)?.removeView(it) }
            campanellaDialog = null
        }
    }

    /**
     * Mette la campanella del Dialog esattamente sopra quella della fascia azzurra (stessa misura e stessa posizione sullo
     * schermo): quella della fascia viene nascosta, così se ne vede sempre una sola, non oscurata dall'ombra delle notifiche.
     */
    private fun allineaCampanella(activity: Activity, superficie: FrameLayout, indicatore: CampanellaSwipateView, tentativo: Int) {
        if (campanellaDialog !== indicatore || superficieAttiva !== superficie) return
        val testa = (activity as? MainActivity)?.campanellaTesta()
        if (testa == null || !testa.isLaidOut || testa.width == 0 || !superficie.isLaidOut) {
            if (tentativo < 30) superficie.post { allineaCampanella(activity, superficie, indicatore, tentativo + 1) }
            else indicatore.visibility = View.VISIBLE
            return
        }
        val posTesta = IntArray(2)
        val posSuperficie = IntArray(2)
        testa.getLocationOnScreen(posTesta)
        superficie.getLocationOnScreen(posSuperficie)
        indicatore.layoutParams = (indicatore.layoutParams as FrameLayout.LayoutParams).apply {
            width = testa.width
            height = testa.height
            leftMargin = posTesta[0] - posSuperficie[0]
            topMargin = posTesta[1] - posSuperficie[1]
        }
        indicatore.visibility = View.VISIBLE
    }

    /** Primo avvio dell'app: le notifiche compaiono tutte, quindi si dimenticano quelle nascoste con lo swipe e la campanella è spenta. */
    fun azzeraSwippate(activity: Activity) {
        dissolte.clear()
        activity.getSharedPreferences(PREFERENZE_SWIPATE, Activity.MODE_PRIVATE).edit().remove(chiaveSwipate()).apply()
        scadenzaSwippateProgrammata?.let { handler.removeCallbacks(it) }
        scadenzaSwippateProgrammata = null
        aggiornaIndicatore(activity)
    }

    fun haNotificheSwippate(activity: Activity): Boolean {
        val adesso = Calendar.getInstance()
        val secondi = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
        dissolte.removeAll { voce -> voce.split(':').getOrNull(2)?.toIntOrNull()?.let { it <= secondi } != false }
        val presenti = leggiSwippate(activity, secondi).isNotEmpty() || dissolte.isNotEmpty()
        if (presenti && scadenzaSwippateProgrammata == null) programmaScadenzaSwippate(activity)
        return presenti
    }

    /** Ripristina le notifiche nascoste ancora valide, senza ricalcolare o prolungare la loro scadenza. */
    fun ripristinaSwippate(activity: Activity) {
        if (!haNotificheSwippate(activity)) {
            aggiornaIndicatore(activity)
            return
        }
        mostra(activity, ripristinaSwippate = true)
    }

    fun mostra(activity: Activity, ripristinaSwippate: Boolean = false) {
        chiudiTutto()
        // le notifiche sparite a fine tempo tornano a ogni risveglio (e col tocco sulla campanella)
        dissolte.clear()
        if (activity.isFinishing || activity.isDestroyed) return
        val gen = generazione
        val adesso = Calendar.getInstance()
        val orario = runCatching { Orario(activity) }.getOrNull() ?: return
        val lezioni = orario.lezioniDel(Orario.giornoDi(adesso)).associateBy { it.ora }
        val secondiAdesso = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
        val preferenze = activity.getSharedPreferences(PREFERENZE_COMPATTE, Activity.MODE_PRIVATE)
        val compattaSalvata = preferenze.getBoolean(CHIAVE_MODALITA_COMPATTA, false)
        val swippate = leggiSwippate(activity, secondiAdesso)
        val cancellate = leggiCancellate(activity, secondiAdesso)
        val pianoCompleto = PianoNotifiche.calcola(orario.fasce(), lezioni.keys, secondiAdesso)
        val pianoSenzaCancellate = PianoNotifiche.escludiSwippate(pianoCompleto, cancellate)
        val pianoVisibile = if (ripristinaSwippate) {
            activity.getSharedPreferences(PREFERENZE_SWIPATE, Activity.MODE_PRIVATE)
                .edit().remove(chiaveSwipate()).apply()
            pianoSenzaCancellate
        } else {
            PianoNotifiche.escludiSwippate(pianoSenzaCancellate, swippate)
        }
        // Prima applica la modalità compatta legacy alle nuove schede, poi prevalgono le scelte
        // individuali dell'utente salvate per ciascuna notifica (ridotta oppure estesa).
        val pianoCompatto = PianoNotifiche.applicaModalitaCompatta(pianoVisibile, compattaSalvata)
        val piano = PianoNotifiche.applicaStatiSalvati(pianoCompatto, leggiStatiNotifiche(activity))
        aggiornaIndicatore(activity)
        programmaScadenzaSwippate(activity)
        if (piano.isEmpty()) return

        // colonna delle notifiche, 10 dp tra l'una e l'altra; scorre solo se non entra nello schermo
        val colonna = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = GradientDrawable().apply { setSize(0, activity.dp(10)) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            clipChildren = false // una scheda che scivola al suo posto non viene tagliata
        }
        val scorrimento = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false // una scheda che scivola al suo posto non viene tagliata
            clipToPadding = false
            addView(colonna, FrameLayout.LayoutParams(-1, -2))
        }
        // Superficie a schermo intero: i tocchi fuori dalla colonna delle notifiche
        // vengono intercettati qui e avviano la dissolvenza immediata di tutte le schede.
        val superficie = FrameLayout(activity).apply {
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val centro = width / 2f
                    val zonaTitolo = event.y < activity.dp(100) &&
                        event.x in (centro - activity.dp(95))..(centro + activity.dp(105))
                    if (zonaTitolo && haNotificheSwippate(activity)) {
                        ripristinaSwippate(activity)
                    } else {
                        // chiuse col tocco fuori: restano recuperabili con la campanella, come con lo swipe
                        gestore.finestre.forEach { registraSwipe(activity, it.notifica) }
                        gestore.dissolviTutto()
                    }
                }
                true
            }
            addView(
                scorrimento,
                FrameLayout.LayoutParams(
                    (activity.resources.displayMetrics.widthPixels * .88f).toInt(),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(superficie)
        d.setCancelable(false)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.28f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }
        dialog = d
        superficieAttiva = superficie
        scorrimentoAttivo = scorrimento
        preferenzeCompatte = preferenze
        posizioneCompatta = false
        attivitaNotifiche = java.lang.ref.WeakReference(activity)
        d.show()
        aggiornaIndicatore(activity)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        // dalla più vecchia (in alto) alla più nuova (in basso), ognuna nello stato previsto dall'orario adesso
        gestore.rientro(piano, secondiAdesso) { n ->
            costruisci(activity, lezioni.getValue(n.ora), n.stato == StatoNotifica.RIDOTTA, n).also { avviso ->
                colonna.addView(avviso.scheda, LinearLayout.LayoutParams(-1, -2))
                avviso.scheda.alpha = 0f
                avviso.scheda.scaleX = .92f
                avviso.scheda.scaleY = .92f
                avviso.scheda.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            }
        }
        aggiornaOmbra()
        // MODIFICA: se la modalità compatta era già attiva, si ripristina senza animazione;
        // altrimenti, se il piano nasce già tutto ridotto, il gruppo scende in un secondo.
        superficie.post {
            if (gen == generazione && dialog === d) {
                applicaDisposizioneCompatta(animate = !compattaSalvata)
            }
        }
        verificaFirme(activity, gen, gestore.finestre)
    }

    /** Costruisce una notifica con la grafica di sempre; [ridotta] = stato iniziale deciso dall'orario. */
    private fun costruisci(activity: Activity, lezione: Lezione, ridotta: Boolean, notifica: NotificaPianificata): Avviso {
        val fascia = lezione.fascia
        lateinit var avviso: Avviso
        val azzurro = 0xFF69BCE8.toInt()
        val scuro = 0xFF333333.toInt()
        val celesteClasse = 0xFF5598BD.toInt()
        val outfitRegular = runCatching { Typeface.createFromAsset(activity.assets, "outfit_regular.ttf") }.getOrDefault(Typeface.DEFAULT)
        val outfitBold = runCatching { Typeface.createFromAsset(activity.assets, "outfit_bold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
        // scheda bianca arrotondata; la fascia azzurra in alto col logo bianco è quella della schermata principale
        val radice = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(24).toFloat()
            }
            clipToOutline = true
        }
        fun pulsanteIcona(icona: Int, descrizione: String) = android.widget.ImageView(activity).apply {
            setImageResource(icona)
            contentDescription = descrizione
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
        }
        val frecciaSu = pulsanteIcona(R.drawable.ic_notifica_su, "Riduci notifica")
        val chiudi = pulsanteIcona(R.drawable.ic_notifica_chiudi, "Chiudi finestra")
        val intestazione = FrameLayout(activity).apply {
            setBackgroundColor(azzurro)
            setPadding(0, activity.dp(10), 0, activity.dp(10))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.logo_bianco)
                adjustViewBounds = true
            }, FrameLayout.LayoutParams(activity.dp(120), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Freccia per ridurre la notifica a una striscia (stile fissato da ControlloNotificaRiducibile).
            addView(frecciaSu, FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.START).apply {
                leftMargin = activity.dp(10)
                topMargin = activity.dp(4)
            })
            // X stilizzata in un cerchio semitrasparente, sempre in alto a destra
            addView(chiudi.apply {
                // la X chiude solo questa notifica
                setOnClickListener { gestore.interagita(avviso); registraCancellazione(activity, avviso.notifica); gestore.chiudi(avviso) } // MODIFICA
            }, FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.END).apply {
                rightMargin = activity.dp(10)
                topMargin = activity.dp(4)
            })
        }
        radice.addView(intestazione, LinearLayout.LayoutParams(-1, -2))
        // Riga superiore come il disegno di Cristian: "aula" e il suo codice a sinistra, "classe" e il suo codice a destra;
        // etichette in azzurro chiaro e codici in grassetto più scuri, così si distinguono.
        fun blocco(etichetta: String, valore: String, allineamento: Int) = LinearLayout(activity).apply {
            // ogni blocco ha la sua colonna di larghezza fissa: il codice si rimpicciolisce invece di essere tagliato
            orientation = LinearLayout.VERTICAL
            gravity = allineamento
            addView(TextView(activity).apply {
                text = etichetta
                gravity = allineamento
                textSize = 16f
                typeface = outfitRegular
                setTextColor(azzurro)
                includeFontPadding = false
                setSingleLine(true)
            })
            addView(TextView(activity).apply {
                text = valore
                textSize = 22f
                typeface = outfitBold
                setTextColor(celesteClasse)
                includeFontPadding = false
                setSingleLine(true)
                gravity = allineamento
                addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                    val t = v as TextView
                    val spazio = (r - l - t.paddingLeft - t.paddingRight).toFloat()
                    if (spazio > 0 && t.paint.measureText(t.text.toString()) > spazio && t.textSize > activity.dp(10)) {
                        t.post { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.textSize * .9f) }
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(2) })
        }
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), 0, activity.dp(20), 0)
            // al centro "ora" e sotto il suo numero, come "classe" e il codice. Tre colonne: le due laterali uguali,
            // così l'ora resta esattamente al centro e non toglie spazio alla classe.
            // l'aula c'è sempre; se nell'orario non è stata inserita compare una lineetta
            addView(blocco("aula", lezione.aula.ifBlank { "—" }, Gravity.START), LinearLayout.LayoutParams(0, -2, 2f))
            addView(blocco("ora", "${lezione.ora}°", Gravity.CENTER_HORIZONTAL), LinearLayout.LayoutParams(0, -2, 1f))
            addView(blocco("classe", lezione.classe, Gravity.END), LinearLayout.LayoutParams(0, -2, 2f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(14) })

        // Materia dell'ora subito sotto aula e classe: una sola riga, puntini se troppo lunga.
        radice.addView(TextView(activity).apply {
            text = lezione.materia
            textSize = 11.25f
            typeface = outfitRegular
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(4) })

        // Ghiera sotto la materia, ben staccata.
        radice.addView(GhieraLezione(activity, fascia, outfitBold), LinearLayout.LayoutParams(activity.dp(78), activity.dp(78)).apply {
            topMargin = activity.dp(16)
        })

        // Stato firma: viene verificato realmente su ClasseViva, senza bloccare l'apertura della finestra.
        val statoFirma = TextView(activity).apply {
            text = "verifico la firma…"
            textSize = 21f // 14 + 50%, come chiesto da Cristian
            typeface = outfitBold
            gravity = Gravity.CENTER
            setTextColor(0xFF777777.toInt())
            setPadding(activity.dp(6), activity.dp(4), activity.dp(6), activity.dp(6))
        }
        // materia del registro sotto "ora firmata": 40% più piccola, una sola riga, puntini se troppo lunga
        val materiaFirma = TextView(activity).apply {
            textSize = 12.6f // 8,4 sp + 50%, come chiesto da Cristian
            typeface = outfitRegular
            gravity = Gravity.CENTER
            setTextColor(0xFF16803C.toInt())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            visibility = View.INVISIBLE
        }
        radice.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(statoFirma, LinearLayout.LayoutParams(-1, -2))
            addView(materiaFirma, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -activity.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(8) })
        // compare solo se l'ora non è firmata: firma tipologia "lezione" con argomento vuoto
        // come il tasto "compilazione magica": cappello a sinistra, "firma" sopra e "rapida" sotto; almeno 128×44 dp ma
        // l'altezza cresce col testo (a 44 fissi "rapida" veniva tagliata), cappello a 18 dp dal bordo e 18 dalla scritta, scritta a 20 dal bordo destro (disegno di Cristian)
        val firmaRapida = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(azzurro)
                cornerRadius = activity.dp(22).toFloat()
            }
            setPadding(activity.dp(4), activity.dp(2), activity.dp(4), activity.dp(2))
            minimumWidth = activity.dp(128)
            minimumHeight = activity.dp(44)
            addView(android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_cappello_mago)
                translationX = -activity.dp(10).toFloat()
            }, LinearLayout.LayoutParams(activity.dp(28), activity.dp(26)).apply { rightMargin = activity.dp(6); bottomMargin = activity.dp(1) })
            // "firma" e "rapida" allineate a sinistra, una sopra l'altra e più vicine tra loro
            addView(TextView(activity).apply {
                text = "firma\nrapida"; textSize = 14.3f; typeface = outfitBold; setTextColor(Color.WHITE)
                includeFontPadding = false; gravity = Gravity.START
                setLineSpacing(0f, 0.9f)
                translationX = -activity.dp(10).toFloat()
            })
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        // Secondo pulsante: "firma ordinaria" (la funzione arriverà dopo). Per non firmare si fa lo swipe a sinistra.
        val firmaOrdinaria = TextView(activity).apply {
            text = "firma\nordinaria"
            textSize = 14.3f
            typeface = outfitBold
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setLineSpacing(0f, 0.9f)
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
            // Stesso fondo di "firma rapida", un pelo più scuro, scritta bianca e due righe centrate.
            background = GradientDrawable().apply {
                setColor(0xFF5AAAD0.toInt())
                cornerRadius = activity.dp(22).toFloat()
            }
            isClickable = true
            isFocusable = true
        }
        // pulsanti affiancati e sotto, piccola, l'indicazione dello swipe a sinistra con le freccette
        val rigaPulsanti = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(firmaRapida, LinearLayout.LayoutParams(activity.dp(150), activity.dp(60)))
                addView(firmaOrdinaria, LinearLayout.LayoutParams(activity.dp(150), activity.dp(60)).apply { leftMargin = activity.dp(22) })
            }, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(activity).apply {
                val frecce = "‹‹‹"
                text = android.text.SpannableString("$frecce  swipe a sinistra per non firmare").apply {
                    setSpan(android.text.style.ForegroundColorSpan(azzurro), 0, frecce.length, 0)
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, frecce.length, 0)
                }
                textSize = 12f
                typeface = outfitRegular
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(8) })
        }
        val chiaveNonFirmo = "non_firmo_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())}_${lezione.ora}_${lezione.classe.trim().replace(Regex("\\s+"), "_")}"
        val preferenzeNonFirmo = activity.getSharedPreferences("classepiu_firma_rapida", Activity.MODE_PRIVATE)
        // Durante il controllo della firma entrambi i pulsanti restano nascosti.
        // Saranno mostrati solo se ClasseViva conferma che l'ora non è firmata.
        rigaPulsanti.visibility = View.INVISIBLE
        firmaRapida.visibility = View.GONE
        firmaRapida.isEnabled = false
        firmaRapida.alpha = .5f
        // per ora "firma ordinaria" non fa nulla (conta solo come tocco: il tempo della notifica riparte);
        // la finestra con materia e tipo attività è pronta in apriFirmaOrdinaria, da collegare quando serve
        firmaOrdinaria.setOnClickListener { gestore.interagita(avviso) }
        // I due pulsanti sono affiancati e compaiono solo se l'ora non è ancora firmata.
        radice.addView(rigaPulsanti, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -activity.dp(37); bottomMargin = activity.dp(7) })

        // Riepilogo compatto: ora scolastica, codice classe e stato effettivo della firma.
        // numeri arabi per tutte le ore, come in "lezioni" (1° ora … 9° ora)
        val oraBreve = "${lezione.ora}° ora"
        val statoCompatto = TextView(activity).apply {
            text = "verifica…"
            textSize = 12.5f
            typeface = outfitBold
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTextColor(0xFF777777.toInt())
            setPadding(activity.dp(10), activity.dp(4), activity.dp(10), activity.dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = activity.dp(14).toFloat()
            }
        }
        val frecciaGiu = pulsanteIcona(R.drawable.ic_notifica_giu, "Espandi notifica")
        val sintesi = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(9), activity.dp(8), activity.dp(10), activity.dp(8))
            background = GradientDrawable().apply {
                setColor(azzurro)
                cornerRadius = activity.dp(24).toFloat()
            }
            addView(frecciaGiu, LinearLayout.LayoutParams(activity.dp(34), activity.dp(34)))
            addView(TextView(activity).apply {
                text = oraBreve
                textSize = 12.5f
                typeface = outfitBold
                setTextColor(Color.WHITE)
                setSingleLine(true)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = activity.dp(7) })
            addView(TextView(activity).apply {
                text = lezione.classe
                textSize = 14f
                typeface = outfitBold
                setTextColor(Color.WHITE)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(12); rightMargin = activity.dp(8) })
            addView(statoCompatto, LinearLayout.LayoutParams(-2, -2))
            visibility = View.GONE
        }
        statoFirma.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val testo = s?.toString().orEmpty().lowercase(java.util.Locale.ROOT)
                when {
                    testo.contains("non firmata") || testo.contains("non riuscita") -> {
                        statoCompatto.text = "senza firma"
                        statoCompatto.setTextColor(0xFFD32F2F.toInt())
                    }
                    testo.contains("in attesa") -> {
                        statoCompatto.text = "in attesa"
                        statoCompatto.setTextColor(0xFF8A6D00.toInt())
                    }
                    testo.contains("già firmata") -> {
                        statoCompatto.text = "già firmata"
                        statoCompatto.setTextColor(0xFFB7791F.toInt())
                    }
                    testo.contains("firmata") -> {
                        statoCompatto.text = "firmato"
                        statoCompatto.setTextColor(0xFF16803C.toInt())
                    }
                    else -> {
                        statoCompatto.text = "verifica…"
                        statoCompatto.setTextColor(0xFF777777.toInt())
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val contenitore = FrameLayout(activity).apply {
            addView(radice, FrameLayout.LayoutParams(-1, -2))
            addView(sintesi, FrameLayout.LayoutParams(-1, -2))
        }

        avviso = Avviso(lezione, notifica, contenitore)
        avviso.controllo = ControlloNotificaRiducibile(activity, radice, sintesi, frecciaSu, frecciaGiu, chiudi, ridotta,
            alCambioStato = {
                salvaStatoNotifica(activity, avviso.notifica, avviso.controllo.ridotta)
                aggiornaOmbra()
                programmaAggiornamentoDisposizione() // MODIFICA
            },
            allaChiusura = { gestore.chiudi(avviso) },
            allEspansione = { gestore.espansa(avviso) },
            alInterazione = { gestore.interagita(avviso) },
            allaSwipe = {
                registraSwipe(activity, avviso.notifica)
                gestore.chiudi(avviso)
            }, // MODIFICA
            // swipe a sinistra: non firmo questa ora, la notifica si cancella come con la X e non si firma nulla
            allaSwipeSinistra = {
                registraCancellazione(activity, avviso.notifica)
                gestore.chiudi(avviso)
            })
        avviso.applicaVerifica = { esito ->
            mostraVerifica(activity, avviso, esito, statoFirma, materiaFirma, firmaRapida, rigaPulsanti, chiaveNonFirmo, preferenzeNonFirmo)
        }
        return avviso
    }

    private class FirmeOggi(val grezzo: String, val mioId: String, val nomi: Map<String, String>, val classi: List<Classe>, val client: ClasseViva)

    /**
     * Controlla le firme del docente nell'endpoint lessons/mine per la data odierna, una sola volta per tutte le notifiche.
     * L'ora è considerata firmata solo se coincidono docente, classe, materia e fascia oraria.
     */
    private fun verificaFirme(activity: Activity, gen: Int, avvisi: List<Avviso>) {
        if (avvisi.isEmpty()) return
        val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        Thread {
            val firme = runCatching {
                val imp = Impostazioni(activity)
                check(imp.pronta) { "Credenziali non configurate" }
                val client = imp.client().also { it.login() }
                val (codiceClassi, classiGrezze) = client.classiGrezze(false)
                check(codiceClassi == 200) { "Classi non disponibili (HTTP $codiceClassi)" }
                val classiMie = leggiClassi(classiGrezze)
                val nomi = OrarioDalleFirme.nomiClassi(classiMie)
                val (codice, grezzo) = client.lezioniGrezze(data, data)
                check(codice == 200) { "Firme non disponibili (HTTP $codice)" }
                FirmeOggi(grezzo, client.idDocente(), nomi, classiMie, client)
            }
            // Compresenza spenta = sono il teorico: se un altro docente ha già firmato quell'ora per la stessa classe non
            // si può firmare sopra. Le mie firme (lessons/mine) non bastano: si leggono le firme di tutta la classe
            // (lessons2). Le classi articolate si saltano: lì due docenti firmano la stessa ora per metà classe ciascuno.
            // Compresenza accesa = sono il pratico: conta la mia firma in compresenza (verde); se per quell'ora c'è la
            // compresenza di un altro docente, giallo. Anche qui si legge la classe intera (lessons2).
            val altriDocenti = HashMap<Avviso, EsitoFirma>()
            val miaCompresenzaClasse = HashMap<Avviso, EsitoFirma>()
            val classeLetta = HashSet<Avviso>() // avvisi per cui le firme della classe sono state lette davvero
            val compresenzaAttiva = Impostazioni(activity).firmaCopresenza
            firme.getOrNull()?.let { f ->
                for (a in avvisi) {
                    if (firmaCorrispondente(f.grezzo, f.mioId, data, a.lezione, f.nomi, soloCompresenza = compresenzaAttiva) != null) continue
                    val sigla = siglaClasse(a.lezione.classe)
                    // gruppo (5EA_AUA): si guardano le firme della classe intera 5EA, dove firma anche chi ha entrambi i gruppi
                    // (es. matematica), ma si scartano quelle dell'altro gruppo (5EA_ENA)
                    val eGruppo = sigla.contains('_')
                    val base = if (eGruppo) sigla.substringBefore('_') else sigla
                    val classe = f.classi.firstOrNull { it.nome.equals(base, ignoreCase = true) } ?: continue
                    val parti = classe.chiave.split("|", limit = 3)
                    if (parti.getOrNull(0) != "C") continue
                    // classe intera con gruppi: le firme dei gruppi sono di metà classe, nessun controllo
                    if (!eGruppo && f.classi.any { it.nome.startsWith(sigla + "_", ignoreCase = true) }) continue
                    // alunni del mio gruppo: servono per distinguere le firme dell'altro gruppo (le firme non hanno il campo gruppo)
                    val gruppo: Set<String>? = if (eGruppo) {
                        runCatching { FiltroArticolate.alunniGruppo(f.client, activity.filesDir, "C", parti.getOrElse(1) { "" }, sigla).first }
                            .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                    } else null
                    val grezzoClasse = runCatching { f.client.lezioniClasseGrezze("C", parti.getOrElse(1) { "" }, data) }.getOrNull()
                        ?.takeIf { it.first == 200 }?.second ?: continue
                    classeLetta.add(a)
                    if (compresenzaAttiva) {
                        firmaAltroDocente(grezzoClasse, f.mioId, data, a.lezione, compresenza = true, mie = true, gruppo = gruppo)?.let { miaCompresenzaClasse[a] = it }
                            ?: firmaAltroDocente(grezzoClasse, f.mioId, data, a.lezione, compresenza = true, gruppo = gruppo)?.let { altriDocenti[a] = it }
                    } else {
                        firmaAltroDocente(grezzoClasse, f.mioId, data, a.lezione, gruppo = gruppo)?.let { altriDocenti[a] = it }
                    }
                }
            }
            activity.runOnUiThread {
                if (gen != generazione || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                for (a in avvisi) {
                    if (!a.scheda.isAttachedToWindow) continue
                    // con Compresenza attiva la firma del teorico non conta: i pulsanti restano finché non c'è la mia compresenza
                    val compresenza = Impostazioni(activity).firmaCopresenza
                    a.applicaVerifica(firme.mapCatching {
                        firmaCorrispondente(it.grezzo, it.mioId, data, a.lezione, it.nomi, soloCompresenza = compresenza)
                            ?: miaCompresenzaClasse[a]
                            ?: altriDocenti[a]
                            // compresenza appena inviata ma non ancora nel registro: "in attesa del registro", si riprova tra poco
                            ?: if (compresenza && compresenzaGiaFirmata(activity, data, a.lezione)) EsitoFirma(a.lezione.materia, inAttesa = true) else null
                    })
                }
            }
        }.start()
    }

    private fun mostraVerifica(
        activity: Activity,
        avviso: Avviso,
        esito: Result<EsitoFirma?>,
        etichetta: TextView,
        materia: TextView,
        pulsante: View,
        rigaPulsanti: View,
        chiaveNonFirmo: String,
        preferenzeNonFirmo: android.content.SharedPreferences
    ) {
        if (!etichetta.isAttachedToWindow) return
        val lezione = avviso.lezione
        esito.fold(
            onSuccess = { risultato ->
                if (risultato == null) {
                    etichetta.text = "ora non firmata"
                    etichetta.setTextColor(0xFFD32F2F.toInt())
                    // INVISIBLE e non GONE: la riga della materia fa spazio ai pulsanti che la sormontano di 37 dp
                    materia.visibility = View.INVISIBLE
                    // i pulsanti spariscono solo se per quest'ora c'è davvero una firma: swipe e X non li tolgono
                    pulsante.visibility = View.VISIBLE
                    pulsante.isEnabled = true
                    pulsante.alpha = 1f
                    rigaPulsanti.visibility = View.VISIBLE
                    pulsante.setOnClickListener { firmaRapida(activity, avviso, lezione, etichetta, materia, pulsante) }
                } else if (risultato.altroDocente) {
                    // firma di un altro docente: avviso giallo e niente pulsanti
                    etichetta.text = "ora già firmata"
                    etichetta.setTextColor(0xFFB7791F.toInt())
                    materia.text = risultato.materia
                    materia.setTextColor(0xFFB7791F.toInt())
                    materia.visibility = if (risultato.materia.isNotBlank()) View.VISIBLE else View.GONE
                    rigaPulsanti.visibility = View.INVISIBLE
                } else {
                    mostraFirmata(etichetta, materia, risultato)
                    rigaPulsanti.visibility = View.INVISIBLE
                }
            },
            onFailure = {
                etichetta.text = "firma non verificata"
                etichetta.setTextColor(0xFF8A6D00.toInt())
            }
        )
    }

    private fun mostraFirmata(etichetta: TextView, materia: TextView, risultato: EsitoFirma) {
        // compresenza inviata ma non ancora visibile nel registro: niente verde finché il registro non la conferma
        val colore = if (risultato.inAttesa) 0xFF8A6D00.toInt() else 0xFF16803C.toInt()
        etichetta.text = if (risultato.inAttesa) "in attesa del registro" else "ora firmata"
        etichetta.setTextColor(colore)
        materia.setTextColor(colore)
        if (risultato.inAttesa) programmaRiverifica(5000)
        materia.text = risultato.materia
        materia.visibility = if (risultato.materia.isNotBlank()) View.VISIBLE else View.GONE
    }

    /**
     * Firma rapida dell'ora in corso, costruita da zero come una firma nuova: classe da "le mie classi", materia da
     * subjects/list (o educazione civica), data e ora dall'orario, tipologia "Lezione" e argomento dalla scheda "firma"
     * delle opzioni (registro web, FIRMA_INSERT).
     * Prima e dopo rilegge le firme di oggi: non firma due volte e conferma che la firma è nel registro.
     * La notifica resta aperta finché non si chiude con la X, così l'esito si legge.
     */
    /**
     * Firma ordinaria: come la firma rapida (stesso FIRMA_INSERT del registro web, HAR di Cristian del 30/10/2026) ma
     * tipologia e argomento si scelgono qui per questa ora, invece di venire dalla scheda "firma" delle opzioni.
     * Educazione civica è una materia a sé (sul sito è una voce del menu "Materia"); tipo_attivita resta "Lezione".
     */
    private fun apriFirmaOrdinaria(activity: Activity, avviso: Avviso, lezione: Lezione, etichetta: TextView, materia: TextView, pulsante: View) {
        // la notifica resta aperta mentre si compila, come dopo la firma rapida
        gestore.interagita(avviso)
        gestore.trattieni(avviso)
        val imp = Impostazioni(activity)
        val azzurro = 0xFF69BCE8.toInt()
        // materia: quella dell'orario oppure educazione civica (sul sito è una voce del menu "Materia")
        val ordinaria = android.widget.RadioButton(activity).apply { id = View.generateViewId(); text = lezione.materia.lowercase().ifBlank { "materia dell'orario" } }
        val civica = android.widget.RadioButton(activity).apply { id = View.generateViewId(); text = "educazione civica" }
        val sceltaMateria = android.widget.RadioGroup(activity).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            addView(ordinaria)
            addView(civica)
            check(if (imp.firmaTipo.equals("Educazione civica", ignoreCase = true)) civica.id else ordinaria.id)
        }
        // tipo attività, elenco di riserva: le 43 voci del menu "Nuova lezione" del registro web, lette dalla pagina
        // regclasse.php (HAR del 31/10/2026), usato finché il sito non risponde con le sue (il valore inviato è il testo)
        val attivita = listOf(
            "Lezione", "Spiegazione", "Interrogazione", "Interrogazione e spiegazione", "Prova pratica", "Compito in classe",
            "Verifica scritta", "Verifiche orali", "Attività di laboratorio", "Orientamento", "Didattica a distanza",
            "Video Lezione", "Video Verifica", "Video Interrogazione", "Didattica a Distanza Materiale", "Didattica a Distanza Compito",
            "Didattica a Distanza Verifica", "Video Lezione Sostegno", "Video Compresenza Sostegno", "Video Compresenza ITP",
            "Sorveglianza", "PCTO - Attività in aula", "FSL - Attività in aula", "Assemblea di classe", "Assemblea di istituto",
            "Supplenza", "PCTO - Sicurezza", "PCTO - Orientamento", "FSL - Sicurezza", "FSL - Orientamento",
            "Ore personalizzazione - attività di antidispersione", "Ore personalizzazione - attività di inclusione",
            "Ore personalizzazione - attività di orientamento", "Ore personalizzazione - attività di riorientamento",
            "Ore personalizzazione - attività di PCTO", "Ore personalizzazione - attività di apprendistato",
            "Ore personalizzazione - attività di alfabetizzazione italiano L2", "Ore personalizzazione - attività per crediti IeFP",
            "Ore personalizzazione - attività di recupero e consolidamento", "Ore personalizzazione - attività di potenziamento",
            "Ore personalizzazione - attività su metodo di studio e stili di apprendimento", "Ore personalizzazione - attività di accoglienza",
            "Ore personalizzazione - attività del bilancio personale iniziale"
        )
        // si apre subito con l'elenco di riserva; appena il sito risponde la tendina si aggiorna con le sue voci
        var voci: List<String> = tipiAttivitaSito ?: attivita
        val tipologia = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voci)
            setSelection(0)
        }
        if (tipiAttivitaSito == null) Thread {
            val dalSito = runCatching {
                val client = imp.client().also { it.login() }
                val classe = leggiClassi(client.classiGrezze(false).second)
                    .firstOrNull { it.nome.equals(siglaClasse(lezione.classe), ignoreCase = true) } ?: return@runCatching emptyList<String>()
                val parti = classe.chiave.split("|", limit = 3)
                val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
                client.tipiAttivita(parti.getOrElse(0) { "" }, parti.getOrElse(1) { "" }, data)
            }.getOrDefault(emptyList())
            if (dalSito.isEmpty()) return@Thread
            tipiAttivitaSito = dalSito
            activity.runOnUiThread {
                if (!tipologia.isAttachedToWindow) return@runOnUiThread
                val scelta = voci.getOrNull(tipologia.selectedItemPosition)
                voci = dalSito
                tipologia.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voci)
                tipologia.setSelection(voci.indexOf(scelta).coerceAtLeast(0))
            }
        }.start()
        val argomento = android.widget.EditText(activity).apply {
            hint = "argomento della lezione"
            setText(imp.firmaArgomento)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val corpo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(8), activity.dp(22), 0)
            addView(TextView(activity).apply { text = "materia"; setTextColor(azzurro); textSize = 14f })
            addView(sceltaMateria)
            addView(TextView(activity).apply { text = "tipo attività"; setTextColor(azzurro); textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(12) })
            addView(tipologia, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(activity).apply { text = "argomento"; setTextColor(azzurro); textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(12) })
            addView(argomento, LinearLayout.LayoutParams(-1, -2))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("firma ordinaria · ${lezione.ora}° ora, ${lezione.classe}")
            .setView(corpo)
            .setPositiveButton("firma") { _, _ ->
                firmaRapida(activity, avviso, lezione, etichetta, materia, pulsante,
                    scelta = SceltaFirma(sceltaMateria.checkedRadioButtonId == civica.id, argomento.text.toString(),
                        voci.getOrElse(tipologia.selectedItemPosition) { "Lezione" }))
            }
            .setNegativeButton("annulla", null)
            .show()
    }

    /** Voci del tipo attività lette dal sito, tenute finché l'app è aperta. */
    private var tipiAttivitaSito: List<String>? = null

    /** Scelte della firma ordinaria per una sola ora. */
    private data class SceltaFirma(val civica: Boolean, val argomento: String, val tipoAttivita: String)

    /** [scelta] = valori della firma ordinaria; null = firma rapida coi valori delle opzioni ("Lezione"). */
    private fun firmaRapida(
        activity: Activity, avviso: Avviso, lezione: Lezione, etichetta: TextView, materia: TextView, pulsante: View,
        scelta: SceltaFirma? = null
    ) {
        // questa notifica resta aperta finché non si chiude con la X; le altre seguono i loro tempi
        gestore.interagita(avviso) // MODIFICA: registra anche la pressione di "firma rapida"
        gestore.trattieni(avviso)
        pulsante.isEnabled = false
        pulsante.alpha = .5f
        etichetta.text = "firmo…"
        etichetta.setTextColor(0xFF777777.toInt())
        val data = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        val log = StringBuilder("${if (scelta == null) "firma rapida" else "firma ordinaria"} classePiù ${BuildConfig.VERSION_NAME}\n")
            .append("data $data, ora ${lezione.ora}, classe ${lezione.classe}, materia ${lezione.materia}\n\n")
        Thread {
            val esito = runCatching {
                val imp = Impostazioni(activity)
                // compresenza e sostegno sul sito passano da un'altra finestra (regclasse_conpresenza.php, con le
                // credenziali del secondo docente): finché non c'è una firma vera da copiare non si manda niente
                // il sostegno sul sito è un'altra finestra, di cui manca ancora una firma vera da copiare
                if (scelta == null) check(!imp.firmaSostegno) { "non firmata: sostegno dal registro web" }
                val compresenza = scelta == null && imp.firmaCopresenza
                val civica = scelta?.civica ?: imp.firmaTipo.equals("Educazione civica", ignoreCase = true)
                val testoArgomento = scelta?.argomento ?: imp.firmaArgomento
                val tipoAttivita = scelta?.tipoAttivita ?: "Lezione"
                log.append("materia ${if (civica) "educazione civica" else "dell'orario"}, attività $tipoAttivita, argomento \"${testoArgomento.trim()}\"\n")
                val client = imp.client().also { it.login() }
                val mioId = client.idDocente()
                log.append("docente $mioId\n")
                val (codiceClassi, classiGrezze) = client.classiGrezze(false)
                check(codiceClassi == 200) { "classi non disponibili (HTTP $codiceClassi)" }
                val classi = leggiClassi(classiGrezze)
                log.append("le mie classi: ${classi.joinToString { it.chiave }}\n")
                val nomi = OrarioDalleFirme.nomiClassi(classi)
                // se nel frattempo l'ora è stata firmata non si firma due volte
                val (codicePrima, grezzoPrima) = client.lezioniGrezze(data, data)
                check(codicePrima == 200) { "firme di oggi non disponibili (HTTP $codicePrima)" }
                firmaCorrispondente(grezzoPrima, mioId, data, lezione, nomi, soloCompresenza = compresenza)
                    ?.let { log.append("ora già firmata, non firmo\n"); return@runCatching it }
                val classe = checkNotNull(classi.firstOrNull { it.nome.equals(siglaClasse(lezione.classe), ignoreCase = true) }) {
                    "${lezione.classe} non è tra le tue classi"
                }
                val parti = classe.chiave.split("|", limit = 3)
                val (codice, risposta) = client.firmaRapida(parti.getOrElse(0) { "" }, parti.getOrElse(1) { "" }, lezione.materia, data, lezione.ora, log,
                    nomeClasse = parti.getOrNull(2)?.takeIf { it.isNotBlank() } ?: classe.nome,
                    educazioneCivica = civica, argomento = testoArgomento, tipoAttivita = tipoAttivita, compresenza = compresenza)
                check(codice in 200..299) { "classeviva ha risposto HTTP $codice ${risposta.take(120)}" }
                val (codiceOggi, grezzoOggi) = client.lezioniGrezze(data, data)
                log.append("GET lessons/mine/$data/$data -> HTTP $codiceOggi\n${grezzoOggi.take(3000)}\n\n")
                check(codiceOggi == 200) { "firma inviata, verifica non riuscita (HTTP $codiceOggi)" }
                // una firma in compresenza può non comparire tra le mie firme: se il sito ha risposto OK vale
                if (compresenza) activity.getSharedPreferences("classepiu_firma_rapida", Activity.MODE_PRIVATE)
                    .edit().putLong(chiaveCompresenza(data, lezione), System.currentTimeMillis()).apply()
                val trovata = firmaCorrispondente(grezzoOggi, mioId, data, lezione, nomi, soloCompresenza = compresenza)
                    ?: if (compresenza) EsitoFirma(lezione.materia, inAttesa = true) else error("firma inviata ma non trovata nel registro")
                // compresenza: sotto "ora firmata" si legge anche se l'argomento è stato copiato o è quello delle opzioni
                if (compresenza && risposta.contains("·")) trovata.copy(materia = "${risposta.substringAfter("·").trim()} · ${trovata.materia}") else trovata
            }
            log.append(esito.fold({ "esito: firmata (${it.materia})" }, { "esito: errore ${it.message}" }))
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !etichetta.isAttachedToWindow) return@runOnUiThread
                esito.fold(
                    onSuccess = { risultato ->
                        mostraFirmata(etichetta, materia, risultato)
                        pulsante.visibility = View.GONE
                        // spariscono i due pulsanti e la riga "swipe a sinistra per non firmare" sotto di loro
                        ((pulsante.parent as? View)?.parent as? View)?.visibility = View.INVISIBLE
                    },
                    onFailure = {
                        // mostra soltanto l’esito della firma; per il sostegno anche il perché
                        etichetta.text = if (it.message.orEmpty().contains("sostegno")) it.message else "firma non riuscita"
                        etichetta.setTextColor(0xFFD32F2F.toInt())
                        pulsante.isEnabled = true
                        pulsante.alpha = 1f
                    }
                )
            }
        }.start()
    }

    /** [altroDocente]: l'ora risulta già firmata da un altro docente come teorico; [materia] è allora la riga da mostrare. */
    private data class EsitoFirma(val materia: String, val altroDocente: Boolean = false, val inAttesa: Boolean = false)

    /**
     * Firma di un altro docente (teorica, non in compresenza) per l'ora di [lezione] nelle firme di tutta la classe
     * (lessons2/students). Restituisce la riga da mostrare, "firmata da <docente> · <materia>".
     */
    private fun firmaAltroDocente(
        grezzo: String, mioId: String, data: String, lezione: Lezione,
        compresenza: Boolean = false, mie: Boolean = false, gruppo: Set<String>? = null
    ): EsitoFirma? {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return null
        fun testo(o: org.json.JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != org.json.JSONObject.NULL && v !is org.json.JSONObject && v !is org.json.JSONArray) {
                    val t = v.toString().trim()
                    if (t.isNotEmpty()) return t
                }
            }
            return ""
        }
        fun alunniDi(v: org.json.JSONObject): Set<String> {
            val stati = v.optJSONArray("studentStatus") ?: return emptySet()
            return (0 until stati.length()).mapNotNull { stati.optJSONObject(it)?.optString("studentId")?.takeIf { id -> id.isNotEmpty() } }.toSet()
        }
        // le compresenze non hanno alunni: prendono quelli del titolare della stessa materia nella stessa ora (come FiltroArticolate)
        val titolari = HashMap<String, MutableSet<String>>()
        fun raccogli(v: Any?, livello: Int = 0) {
            if (livello > 7) return
            when (v) {
                is org.json.JSONArray -> for (i in 0 until v.length()) raccogli(v.opt(i), livello + 1)
                is org.json.JSONObject -> {
                    val ora = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val alunni = alunniDi(v)
                    if (ora != null && alunni.isNotEmpty() && testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "") == data) {
                        val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                        val materia = testo(v, "subjectDesc", "subjectName", "subject").uppercase(java.util.Locale.ROOT)
                        for (h in ora until ora + durata) titolari.getOrPut("$h|$materia") { HashSet() }.addAll(alunni)
                    } else for (k in v.keys()) raccogli(v.opt(k), livello + 1)
                }
            }
        }
        if (gruppo != null) raccogli(radice)
        fun cerca(v: Any?, livello: Int = 0): EsitoFirma? {
            if (livello > 7) return null
            when (v) {
                is org.json.JSONArray -> for (i in 0 until v.length()) cerca(v.opt(i), livello + 1)?.let { return it }
                is org.json.JSONObject -> {
                    val dataFirma = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val ora = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                    val autore = testo(v, "authorId", "teacherId", "teacher_id")
                    val soloLezione = !tipoFirma(v, "compresenza") && !tipoFirma(v, "sostegno")
                    val eCompresenza = tipoFirma(v, "compresenza")
                    // compresenza=false: firma teorica di un altro docente; compresenza=true: firma in compresenza (mia se mie, altrui altrimenti)
                    val tipoGiusto = if (compresenza) eCompresenza else soloLezione
                    val autoreGiusto = autore.isNotBlank() && (if (mie) autore == mioId else autore != mioId)
                    // firma di un altro gruppo della stessa classe (5EA_ENA mentre io sono su 5EA_AUA): non è sovrapposizione
                    // le mie firme sono del mio gruppo per forza (una mia compresenza non ha alunni e non va scartata)
                    val altroGruppo = gruppo != null && !mie && alunniDi(v)
                        .ifEmpty { titolari["${lezione.ora}|${testo(v, "subjectDesc", "subjectName", "subject").uppercase(java.util.Locale.ROOT)}"].orEmpty() }
                        .none { it in gruppo }
                    if (dataFirma == data && ora != null && lezione.ora in ora until (ora + durata) && autoreGiusto && tipoGiusto && !altroGruppo) {
                        if (mie) return EsitoFirma(testo(v, "subjectDesc", "subjectName", "subject").ifEmpty { lezione.materia })
                        val docente = testo(v, "authorName", "teacherName", "signedBy")
                            .ifEmpty { (v.optJSONObject("author") ?: v.optJSONObject("teacher"))?.let { testo(it, "name", "fullName", "teacherName") }.orEmpty() }
                        val materia = testo(v, "subjectDesc", "subjectName", "subject")
                        val riga = listOf(if (docente.isNotEmpty()) "firmata da ${docente.lowercase().replaceFirstChar { it.uppercase() }}" else "firmata da un altro docente", materia)
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                        return EsitoFirma(riga, altroDocente = true)
                    }
                    for (k in v.keys()) cerca(v.opt(k), livello + 1)?.let { return it }
                }
            }
            return null
        }
        return cerca(radice)
    }

    /**
     * Tipo della firma (es. lessonType "Compresenza"): si guardano solo i campi del tipo di attività, mai l'argomento,
     * altrimenti una firma teorica con la parola "compresenza" nell'argomento passerebbe per compresenza.
     */
    private fun tipoFirma(v: org.json.JSONObject, parola: String): Boolean =
        v.keys().asSequence().filter { k ->
            val n = k.lowercase(java.util.Locale.ROOT)
            n.contains("type") || n.contains("tipo") || n == "evtcode"
        }.any { k -> (v.opt(k) as? String)?.contains(parola, ignoreCase = true) == true }

    /** Firma in compresenza già inviata per quest'ora (ricordata qui: il registro può non mostrarla tra le mie firme). */
    private fun chiaveCompresenza(data: String, lezione: Lezione) =
        "cp2_${data}_${lezione.ora}_${lezione.classe.trim().replace(Regex("\\s+"), "_")}"

    private fun compresenzaGiaFirmata(activity: Activity, data: String, lezione: Lezione) =
        activity.getSharedPreferences("classepiu_firma_rapida", Activity.MODE_PRIVATE).getLong(chiaveCompresenza(data, lezione), 0L)
            .let { System.currentTimeMillis() - it < 60_000L } // per un minuto dopo la firma: "in attesa del registro", poi conta solo il registro

    /**
     * [soloCompresenza]: con l'interruttore Compresenza attivo la firma del teorico (anche se è la mia) non conta come
     * "ora firmata": vale solo una mia firma in compresenza.
     */
    private fun firmaCorrispondente(
        grezzo: String,
        mioId: String,
        data: String,
        lezione: Lezione,
        nomiClassi: Map<String, String>,
        soloCompresenza: Boolean = false
    ): EsitoFirma? {
        val radice = org.json.JSONTokener(grezzo).nextValue()
        fun testo(o: org.json.JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != org.json.JSONObject.NULL && v !is org.json.JSONObject && v !is org.json.JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty()) return s
                }
            }
            return ""
        }
        fun normalizza(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(java.util.Locale.ROOT)
        fun cerca(v: Any?, livello: Int = 0): EsitoFirma? {
            if (livello > 7) return null
            when (v) {
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) cerca(v.opt(i), livello + 1)?.let { return it }
                }
                is org.json.JSONObject -> {
                    val dataFirma = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val ora = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                    val autore = testo(v, "authorId", "teacherId", "teacher_id")
                    val tipo = testo(v, "classType")
                    val idClasse = testo(v, "classId", "classroomId")
                    val classeFirmata = testo(v, "classDesc", "className", "classroomName", "classCode")
                        .ifEmpty { nomiClassi["$tipo|$idClasse"] ?: nomiClassi[idClasse].orEmpty() }
                    val stessaClasse = classeFirmata.isBlank() || normalizza(classeFirmata) == normalizza(lezione.classe)
                    val firmaQuestaOra = dataFirma == data && ora != null && lezione.ora in ora until (ora + durata) &&
                        (mioId.isBlank() || autore == mioId) && stessaClasse
                    // in modalità compresenza si riconosce la firma dalla parola "compresenza" in uno qualsiasi dei suoi campi
                    val eCompresenza = !soloCompresenza || tipoFirma(v, "compresenza")
                    if (firmaQuestaOra && eCompresenza) {
                        val materia = testo(v, "subjectDesc", "subjectName", "subjectDescription", "subject")
                            .ifEmpty {
                                val oggettoMateria = v.optJSONObject("subject")
                                if (oggettoMateria != null) testo(oggettoMateria, "subjectDesc", "description", "name") else ""
                            }
                        return EsitoFirma(materia)
                    }
                    for (k in v.keys()) cerca(v.opt(k), livello + 1)?.let { return it }
                }
            }
            return null
        }
        return cerca(radice)
    }

    /** Ghiera che si riempie dall'inizio alla fine dell'ora (fascia dalla tabella "lezioni"); al centro i minuti che mancano. */
    private class GhieraLezione(ctx: android.content.Context, private val fascia: Fascia, carattere: Typeface) : View(ctx) {
        private val spessore = ctx.dp(7).toFloat()
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; color = 0xFFE3EEF5.toInt() }
        private val pieno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = spessore; strokeCap = Paint.Cap.ROUND; color = 0xFF69BCE8.toInt() }
        private val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textAlign = Paint.Align.CENTER; typeface = carattere; textSize = ctx.dp(16).toFloat() }
        private val arco = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val adesso = Calendar.getInstance()
            val secondiAdesso = (adesso.get(Calendar.HOUR_OF_DAY) * 60 + adesso.get(Calendar.MINUTE)) * 60 + adesso.get(Calendar.SECOND)
            val durata = ((fascia.fine - fascia.inizio) * 60).coerceAtLeast(1)
            val passati = (secondiAdesso - fascia.inizio * 60).coerceIn(0, durata)
            val residui = durata - passati
            val m = spessore / 2
            arco.set(m, m, width - m, height - m)
            canvas.drawArc(arco, 0f, 360f, false, fondo)
            canvas.drawArc(arco, -90f, 360f * passati / durata, false, pieno)
            val y = height / 2f - (testo.descent() + testo.ascent()) / 2
            canvas.drawText(String.format(java.util.Locale.ITALY, "%d:%02d", residui / 60, residui % 60), width / 2f, y, testo)
            postInvalidateDelayed(1000)
        }
    }
}
