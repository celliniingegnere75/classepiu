package it.cellini.classepiu

/** Come appare una notifica dell'orario in un certo istante. */
enum class StatoNotifica { ESTESA, RIDOTTA }

/**
 * Una notifica da mostrare al rientro: l'ora di lezione (1…ORE), lo stato previsto adesso e gli istanti dei tre passaggi,
 * in secondi dalla mezzanotte (comparsa, riduzione, scadenza).
 */
data class NotificaPianificata(val ora: Int, val stato: StatoNotifica, val comparsa: Int, val riduzione: Int, val scadenza: Int)

/**
 * Regole delle notifiche dell'orario, senza Android (si provano con i test):
 * - compare estesa 15 minuti prima dell'inizio della sua fascia;
 * - diventa ridotta 15 minuti prima della fine della sua fascia;
 * - scade 15 minuti prima della fine della fascia successiva della tabella (due ore scolastiche effettive, conta anche
 *   l'ora in cui il docente non ha lezione); se la fascia successiva non c'è, scade alla fine della sua fascia;
 * - al massimo 3 insieme, le più recenti, dalla più vecchia (in alto) alla più nuova (in basso);
 * - al massimo una estesa: se l'orario ne prevede più di una resta estesa solo la più recente, le altre sono ridotte.
 * Tutto si ricalcola da zero dall'ora attuale: nessuno stato dei risvegli precedenti.
 */
object PianoNotifiche {
    const val ANTICIPO_MINUTI = 15
    const val MASSIMO = 3

    /**
     * @param fasce tutte le fasce della tabella "lezioni" in ordine di ora (indice 0 = 1ª ora), così come le ha salvate l'utente
     * @param oreConLezione le ore del giorno in cui il docente ha lezione
     * @param secondiAdesso l'istante attuale in secondi dalla mezzanotte
     */
    fun calcola(fasce: List<Fascia>, oreConLezione: Collection<Int>, secondiAdesso: Int): List<NotificaPianificata> {
        val anticipo = ANTICIPO_MINUTI * 60
        return oreConLezione.distinct().filter { it in 1..fasce.size }.map { ora ->
            val fascia = fasce[ora - 1]
            val successiva = fasce.getOrNull(ora)
            val riduzione = fascia.fine * 60 - anticipo
            val scadenza = if (successiva != null) successiva.fine * 60 - anticipo else fascia.fine * 60
            NotificaPianificata(ora, StatoNotifica.ESTESA, fascia.inizio * 60 - anticipo, riduzione, scadenza.coerceAtLeast(riduzione))
        }
            .filter { secondiAdesso >= it.comparsa && secondiAdesso < it.scadenza }
            .sortedWith(compareBy({ it.comparsa }, { it.ora }))
            .takeLast(MASSIMO)
            .map { it.copy(stato = if (secondiAdesso >= it.riduzione) StatoNotifica.RIDOTTA else StatoNotifica.ESTESA) }
            .let { piano ->
                val estesa = piano.indexOfLast { it.stato == StatoNotifica.ESTESA }
                piano.mapIndexed { k, n -> if (k != estesa && n.stato == StatoNotifica.ESTESA) n.copy(stato = StatoNotifica.RIDOTTA) else n }
            }
    }
}

/**
 * Vita delle finestre di un solo risveglio, senza Android: ogni rientro chiude tutto quello del rientro precedente
 * (finestre e timer) e apre solo le notifiche del piano. Ogni finestra ha i suoi timer: dissolvenza dopo ~10 secondi e,
 * se cadono in quei secondi, riduzione e scadenza.
 */
class GestoreNotifiche<F : GestoreNotifiche.Finestra>(private val tempo: Temporizzatore) {

    interface Finestra {
        /** Passa alla modalità ridotta con la sua animazione (solo questa finestra). */
        fun riduci()
        /** Dissolvenza animata; a fine animazione chiama [fatto]. */
        fun dissolvi(fatto: () -> Unit)
        /** Toglie subito la finestra dallo schermo. */
        fun rimuovi()
        /** MODIFICA: interrompe una dissolvenza già partita dopo un'interazione. */
        fun annullaDissolvenza() {}
    }

    fun interface Temporizzatore {
        /** Esegue [azione] dopo [ms] millisecondi e restituisce la funzione che la annulla. */
        fun dopo(ms: Long, azione: () -> Unit): () -> Unit
    }

    private class Voce<F>(val notifica: NotificaPianificata, val finestra: F) {
        val annulla = mutableListOf<() -> Unit>()
        var annullaDissolvenza: (() -> Unit)? = null // MODIFICA: timer di dissolvenza separato dai timer scolastici
        var dissolvenzaInCorso = false // MODIFICA: consente di annullare anche l'animazione già avviata
        var trattenuta = false // MODIFICA: la firma rapida mantiene aperta solo questa notifica
        var chiusa = false
    }

    private val aperte = mutableListOf<Voce<F>>()

    /** Chiamata quando non resta nessuna finestra (per togliere il contenitore). */
    var allaFine: () -> Unit = {}

    /** Le finestre ancora sullo schermo, dalla più vecchia (in alto) alla più nuova (in basso). */
    val finestre: List<F> get() = aperte.map { it.finestra }
    val notifiche: List<NotificaPianificata> get() = aperte.map { it.notifica }

    fun rientro(piano: List<NotificaPianificata>, secondiAdesso: Int, crea: (NotificaPianificata) -> F) {
        chiudiTutto()
        for (n in piano) {
            val voce = Voce(n, crea(n))
            aperte += voce
            programmaDissolvenza(voce) // MODIFICA: avvia il conteggio autonomo della dissolvenza
            if (n.stato == StatoNotifica.ESTESA) {
                val ms = (n.riduzione - secondiAdesso) * 1000L
                if (ms in 1 until DURATA_MS) voce.annulla += tempo.dopo(ms) { if (!voce.chiusa) voce.finestra.riduci() }
            }
            val ms = (n.scadenza - secondiAdesso) * 1000L
            if (ms in 1 until DURATA_MS) voce.annulla += tempo.dopo(ms) { dissolvi(voce) }
        }
    }

    /**
     * Avvia subito la dissolvenza di tutte le notifiche aperte, comprese quelle trattenute.
     * Usato quando l'utente tocca una zona esterna alle schede.
     */
    fun dissolviTutto() {
        aperte.toList().forEach { voce ->
            if (!voce.chiusa && !voce.dissolvenzaInCorso) dissolvi(voce)
        }
    }

    /** Ferma i timer di una finestra (es. dopo "firma rapida"): resta finché non la si chiude. */
    fun trattieni(finestra: F) {
        aperte.firstOrNull { it.finestra === finestra && !it.chiusa }?.let {
            it.trattenuta = true // MODIFICA: non rientra nel timer generale dopo la firma rapida
            fermaTimer(it)
        }
    }

    /**
     * MODIFICA: il conteggio della dissolvenza è generale. Qualsiasi interazione su una scheda
     * riavvia contemporaneamente il timer di tutte le notifiche aperte, non trattenute.
     * Se una dissolvenza è già partita, viene annullata anche sulle altre schede.
     */
    fun interagita(finestra: F) {
        if (aperte.none { it.finestra === finestra && !it.chiusa }) return
        aperte.filter { !it.chiusa && !it.trattenuta }.forEach { voce ->
            if (voce.dissolvenzaInCorso) {
                voce.dissolvenzaInCorso = false
                voce.finestra.annullaDissolvenza()
            }
            programmaDissolvenza(voce)
        }
    }

    /** L'utente ha espanso [finestra]: tutte le altre si riducono, così resta estesa una sola notifica. */
    fun espansa(finestra: F) {
        aperte.filter { it.finestra !== finestra && !it.chiusa }.forEach { it.finestra.riduci() }
    }

    /** Chiusura a mano (X o trascinamento): solo questa finestra. */
    fun chiudi(finestra: F) {
        aperte.firstOrNull { it.finestra === finestra }?.let { togli(it) }
    }

    fun chiudiTutto() {
        val tutte = aperte.toList()
        aperte.clear()
        for (v in tutte) {
            fermaTimer(v)
            v.chiusa = true
            v.dissolvenzaInCorso = false // MODIFICA
            v.finestra.rimuovi()
        }
        if (tutte.isNotEmpty()) allaFine()
    }

    private fun dissolvi(voce: Voce<F>) {
        if (voce.chiusa) return
        fermaTimer(voce)
        voce.dissolvenzaInCorso = true // MODIFICA
        voce.finestra.dissolvi {
            if (!voce.chiusa && voce.dissolvenzaInCorso) togli(voce) // MODIFICA: ignora il completamento di un'animazione annullata
        }
    }

    private fun togli(voce: Voce<F>) {
        if (voce.chiusa) return
        voce.chiusa = true
        voce.dissolvenzaInCorso = false // MODIFICA
        fermaTimer(voce)
        aperte.remove(voce)
        voce.finestra.rimuovi()
        if (aperte.isEmpty()) allaFine()
    }

    // MODIFICA: annulla e riprogramma esclusivamente il timer della dissolvenza.
    private fun programmaDissolvenza(voce: Voce<F>) {
        if (voce.chiusa) return
        voce.annullaDissolvenza?.invoke()
        voce.annullaDissolvenza = tempo.dopo(DURATA_MS) {
            voce.annullaDissolvenza = null
            dissolvi(voce)
        }
    }

    private fun fermaTimer(voce: Voce<F>) {
        voce.annullaDissolvenza?.invoke() // MODIFICA: arresta anche il timer di dissolvenza
        voce.annullaDissolvenza = null // MODIFICA
        voce.annulla.forEach { it() }
        voce.annulla.clear()
    }

    companion object {
        /** Ogni finestra resta circa 10 secondi: 9,6 visibile + 0,4 di dissolvenza. */
        const val DURATA_MS = 9600L
        const val DISSOLVENZA_MS = 400L
    }
}
