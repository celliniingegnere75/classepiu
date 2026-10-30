package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PianoNotificheTest {
    private fun hm(h: Int, m: Int) = h * 60 + m
    private fun s(h: Int, m: Int) = hm(h, m) * 60

    /** Mercoledì dell'esempio di Cristian: 1ª e 2ª si sovrappongono di 5 minuti, durate diverse tra loro. */
    private val fasceMercoledi = listOf(
        Fascia(hm(8, 0), hm(8, 55)),
        Fascia(hm(8, 50), hm(9, 55)),
        Fascia(hm(9, 55), hm(10, 45)),
        Fascia(hm(10, 45), hm(11, 45)),
        Fascia(hm(11, 45), hm(12, 35)),
        Fascia(hm(12, 35), hm(13, 30)),
    )
    private val oreMercoledi = listOf(2, 4, 5)

    private fun piano(h: Int, m: Int, fasce: List<Fascia> = fasceMercoledi, ore: List<Int> = oreMercoledi) =
        PianoNotifiche.calcola(fasce, ore, s(h, m)).map { it.ora to it.stato }

    private val E = StatoNotifica.ESTESA
    private val R = StatoNotifica.RIDOTTA

    @Test fun caso1_rientroAlle0830_nessunaNotifica() = assertEquals(emptyList<Pair<Int, StatoNotifica>>(), piano(8, 30))
    @Test fun caso2_rientroAlle0840_secondaEstesa() = assertEquals(listOf(2 to E), piano(8, 40))
    @Test fun caso3_rientroAlle0945_secondaRidotta() = assertEquals(listOf(2 to R), piano(9, 45))
    @Test fun caso4_rientroAlle1035_secondaScadutaQuartaEstesa() = assertEquals(listOf(4 to E), piano(10, 35))
    @Test fun caso5_rientroAlle1135_quartaRidottaQuintaEstesa() = assertEquals(listOf(4 to R, 5 to E), piano(11, 35))
    @Test fun caso6_rientroAlle1225_quartaScadutaQuintaRidotta() = assertEquals(listOf(5 to R), piano(12, 25))
    @Test fun caso7_rientroAlle1320_quintaScaduta() = assertEquals(emptyList<Pair<Int, StatoNotifica>>(), piano(13, 20))

    @Test
    fun sequenzaCompletaAiConfini() {
        assertEquals(listOf(2 to E), piano(8, 35)) // compare
        assertEquals(listOf(2 to R), piano(9, 40)) // si riduce
        assertEquals(listOf(4 to E), piano(10, 30)) // la 2ª scompare, compare la 4ª
        assertEquals(listOf(4 to R, 5 to E), piano(11, 30)) // 4ª ridotta in alto, 5ª estesa in basso
        assertEquals(listOf(5 to R), piano(12, 20)) // la 4ª scompare, la 5ª si riduce
        assertEquals(emptyList<Pair<Int, StatoNotifica>>(), piano(13, 15)) // la 5ª scompare
        // un secondo prima di ogni confine vale ancora lo stato precedente
        assertEquals(emptyList<Pair<Int, StatoNotifica>>(), PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(8, 35) - 1).map { it.ora })
        assertEquals(listOf(2 to E), PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(9, 40) - 1).map { it.ora to it.stato })
        assertEquals(listOf(2 to R), PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(10, 30) - 1).map { it.ora to it.stato })
    }

    @Test
    fun scadenzaDueOreScolasticheEffettive() {
        val p = PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(11, 35)).associateBy { it.ora }
        // 4ª ora: fine della 5ª fascia (12:35) - 15 minuti, anche se la 5ª è un'altra lezione
        assertEquals(s(12, 20), p.getValue(4).scadenza)
        // 5ª ora: fine della 6ª fascia (13:30, senza lezione) - 15 minuti
        assertEquals(s(13, 15), p.getValue(5).scadenza)
        val seconda = PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(8, 40)).single()
        // 2ª ora: conta la 3ª fascia anche se il docente non ha lezione (10:45 - 15), non la 4ª
        assertEquals(s(10, 30), seconda.scadenza)
        assertEquals(s(8, 35), seconda.comparsa)
        assertEquals(s(9, 40), seconda.riduzione)
    }

    @Test
    fun ultimaFasciaSenzaSuccessiva() {
        // lezione nell'ultima fascia: scade alla fine della sua fascia, dopo 15 minuti ridotta
        val p = piano(13, 20, ore = listOf(6))
        assertEquals(listOf(6 to R), p)
        assertEquals(s(13, 30), PianoNotifiche.calcola(fasceMercoledi, listOf(6), s(13, 20)).single().scadenza)
        assertEquals(emptyList<Pair<Int, StatoNotifica>>(), piano(13, 30, ore = listOf(6)))
    }

    @Test
    fun caso8_quattroNotifiche_restanoLeTrePiuRecenti() {
        // fasce lunghe e sovrapposte: alle 8:40 quattro lezioni sarebbero tutte attive
        val fasce = listOf(
            Fascia(hm(8, 0), hm(10, 0)),
            Fascia(hm(8, 15), hm(10, 0)),
            Fascia(hm(8, 30), hm(10, 0)),
            Fascia(hm(8, 45), hm(10, 0)),
            Fascia(hm(10, 0), hm(11, 0)),
        )
        val p = PianoNotifiche.calcola(fasce, listOf(1, 2, 3, 4), s(8, 40))
        assertEquals(listOf(2, 3, 4), p.map { it.ora }) // la più vecchia (1ª) è eliminata
        assertTrue(p.zipWithNext().all { (a, b) -> a.comparsa <= b.comparsa }) // più vecchia in alto, più nuova in basso
        assertEquals(PianoNotifiche.MASSIMO, p.size)
    }

    @Test
    fun maiPiuDiUnaEstesa() {
        // 1ª e 2ª ora del mercoledì: dalle 8:35 alle 8:40 l'orario le vorrebbe estese entrambe
        assertEquals(listOf(1 to R, 2 to E), piano(8, 37, ore = listOf(1, 2)))
        // con quattro lezioni sovrapposte resta estesa solo la più recente
        val fasce = listOf(Fascia(hm(8, 0), hm(10, 0)), Fascia(hm(8, 15), hm(10, 0)), Fascia(hm(8, 30), hm(10, 0)), Fascia(hm(8, 45), hm(10, 0)))
        assertEquals(listOf(2 to R, 3 to R, 4 to E), piano(8, 40, fasce, listOf(1, 2, 3, 4)))
    }

    @Test
    fun ordineCronologicoAncheConOreInDisordine() {
        assertEquals(listOf(4 to R, 5 to E), piano(11, 35, ore = listOf(5, 4, 4)))
    }

    @Test
    fun orariDiversiDallEsempio() {
        // fasce da 60 minuti con intervallo: 3ª 10:00-11:00, 4ª 11:10-12:10, 5ª 12:10-13:10
        val fasce = listOf(
            Fascia(hm(8, 0), hm(9, 0)), Fascia(hm(9, 0), hm(10, 0)), Fascia(hm(10, 0), hm(11, 0)),
            Fascia(hm(11, 10), hm(12, 10)), Fascia(hm(12, 10), hm(13, 10)),
        )
        assertEquals(listOf(3 to E), piano(9, 50, fasce, listOf(3)))
        assertEquals(listOf(3 to R), piano(10, 50, fasce, listOf(3)))
        assertEquals(listOf(3 to R), piano(11, 50, fasce, listOf(3))) // scade alle 11:55
        assertEquals(emptyList<Pair<Int, StatoNotifica>>(), piano(11, 55, fasce, listOf(3)))
    }

    @Test
    fun ilPianoNonTocaLeFasceSalvate() {
        val copia = fasceMercoledi.toList()
        PianoNotifiche.calcola(fasceMercoledi, oreMercoledi, s(11, 35))
        assertEquals(copia, fasceMercoledi)
    }

    @Test
    fun giornoDellaSettimana() {
        fun g(anno: Int, mese: Int, giorno: Int) = Orario.giornoDi(Calendar.getInstance().apply { clear(); set(anno, mese, giorno) })
        assertEquals(1, g(2026, Calendar.OCTOBER, 26)) // lunedì
        assertEquals(3, g(2026, Calendar.OCTOBER, 28)) // mercoledì
        assertEquals(6, g(2026, Calendar.OCTOBER, 31)) // sabato
        assertEquals(7, g(2026, Calendar.NOVEMBER, 1)) // domenica: nessuna lezione
    }
}

class GestoreNotificheTest {
    /** Orologio finto: i timer scattano solo quando il test fa avanzare il tempo. */
    private class Tempo : GestoreNotifiche.Temporizzatore {
        var adesso = 0L
        private val timer = mutableListOf<Triple<Long, Int, () -> Unit>>()
        private var progressivo = 0
        val inAttesa get() = timer.size

        override fun dopo(ms: Long, azione: () -> Unit): () -> Unit {
            val voce = Triple(adesso + ms, progressivo++, azione)
            timer += voce
            return { timer.remove(voce) }
        }

        fun avanza(ms: Long) {
            val fine = adesso + ms
            while (true) {
                val prossimo = timer.filter { it.first <= fine }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                timer.remove(prossimo)
                adesso = prossimo.first
                prossimo.third()
            }
            adesso = fine
        }
    }

    /** Finestra finta: tiene il suo stato e conta dove si trova. */
    private class Finta(val ora: Int, var ridotta: Boolean, val schermo: MutableList<Finta>) : GestoreNotifiche.Finestra {
        var dissolta = false
        var differita = false // MODIFICA: permette di simulare i 400 ms dell'animazione
        var completaDissolvenza: (() -> Unit)? = null // MODIFICA
        override fun riduci() { ridotta = true }
        override fun dissolvi(fatto: () -> Unit) {
            dissolta = true
            if (differita) completaDissolvenza = fatto else fatto()
        }
        override fun annullaDissolvenza() { // MODIFICA
            dissolta = false
            completaDissolvenza = null
        }
        override fun rimuovi() { schermo.remove(this) }
    }

    private val fasce = listOf(
        Fascia(480, 535), Fascia(530, 595), Fascia(595, 645), Fascia(645, 705), Fascia(705, 755), Fascia(755, 810),
    )
    private val ore = listOf(2, 4, 5)
    private val tempo = Tempo()
    private val schermo = mutableListOf<Finta>()
    private val gestore = GestoreNotifiche<Finta>(tempo)
    private var contenitoreTolto = 0

    init { gestore.allaFine = { contenitoreTolto++ } }

    private fun rientro(secondi: Int) {
        val piano = PianoNotifiche.calcola(fasce, ore, secondi)
        gestore.rientro(piano, secondi) { n -> Finta(n.ora, n.stato == StatoNotifica.RIDOTTA, schermo).also { schermo += it } }
    }

    @Test
    fun caso9e10_rientriRipetuti_nessunDuplicatoNessunaFinestraVecchia() {
        rientro(695 * 60) // 11:35: 4ª ridotta e 5ª estesa
        assertEquals(listOf(4, 5), schermo.map { it.ora })
        rientro(695 * 60 + 5) // nuovo risveglio prima della dissolvenza
        assertEquals(listOf(4, 5), schermo.map { it.ora })
        rientro(745 * 60) // 12:25: solo la 5ª
        assertEquals(listOf(5), schermo.map { it.ora })
        assertEquals(listOf(5), gestore.finestre.map { it.ora })
        // i timer dei risvegli precedenti sono stati annullati: resta solo quello della dissolvenza della 5ª
        assertEquals(1, tempo.inAttesa)
        // chiusura dell'app (onStop/onDestroy) e riapertura
        gestore.chiudiTutto()
        assertTrue(schermo.isEmpty())
        assertEquals(0, tempo.inAttesa)
        rientro(745 * 60)
        assertEquals(listOf(5), schermo.map { it.ora })
    }

    @Test
    fun caso11_dissolvenzaDopoDieciSecondi() {
        rientro(695 * 60)
        tempo.avanza(GestoreNotifiche.DURATA_MS - 1)
        assertEquals(2, schermo.size)
        tempo.avanza(1)
        assertTrue(schermo.isEmpty())
        assertTrue(gestore.finestre.isEmpty())
        assertEquals(1, contenitoreTolto) // il Dialog si toglie una volta sola, quando sparisce l'ultima
        assertEquals(0, tempo.inAttesa)
    }

    // MODIFICA: la dissolvenza riparte dalla singola interazione senza spostare quella delle altre notifiche.
    @Test
    fun interazioneRiavviaSoloLaDissolvenzaDellaNotificaToccata() {
        rientro(695 * 60)
        val (quarta, quinta) = schermo.toList()

        tempo.avanza(5000)
        gestore.interagita(quarta)

        tempo.avanza(4600) // sono trascorsi 9,6 s dall'apertura: scade solo la quinta
        assertEquals(listOf(quarta), schermo)
        assertEquals(false, quarta.dissolta)
        assertEquals(true, quinta.dissolta)

        tempo.avanza(5000) // la quarta scade dopo 9,6 s dalla sua interazione
        assertTrue(schermo.isEmpty())
    }

    // MODIFICA: un tocco durante l'animazione finale la interrompe e fa ripartire il conteggio.
    @Test
    fun interazioneAnnullaAncheLaDissolvenzaGiaIniziata() {
        rientro(695 * 60)
        val (quarta, quinta) = schermo.toList()
        quarta.differita = true

        tempo.avanza(GestoreNotifiche.DURATA_MS)
        assertEquals(listOf(quarta), schermo)
        assertTrue(quarta.dissolta)

        gestore.interagita(quarta)
        assertEquals(false, quarta.dissolta)
        assertEquals(null, quarta.completaDissolvenza)

        tempo.avanza(GestoreNotifiche.DURATA_MS)
        assertTrue(quarta.dissolta)
        assertTrue(quarta.completaDissolvenza != null)
        quarta.completaDissolvenza?.invoke()
        assertTrue(schermo.isEmpty())
    }

    @Test
    fun caso12_statiIndipendenti() {
        // 9:39:55: la 2ª è estesa e si riduce dopo 5 secondi, da sola
        rientro(580 * 60 - 5)
        val seconda = schermo.single()
        assertEquals(false, seconda.ridotta)
        tempo.avanza(5000)
        assertEquals(true, seconda.ridotta)

        // 11:29:58: la 4ª estesa si riduce alle 11:30 senza toccare le altre; la 5ª non c'è ancora (compare al prossimo rientro)
        rientro(690 * 60 - 2)
        assertEquals(listOf(4), schermo.map { it.ora })
        tempo.avanza(2000)
        assertEquals(true, schermo.single().ridotta)
        rientro(690 * 60)
        val (quarta, quinta) = schermo
        assertEquals(true, quarta.ridotta)
        assertEquals(false, quinta.ridotta)
        quinta.ridotta = true // l'utente riduce la 5ª
        assertEquals(true, quarta.ridotta)
        // chiusura a mano della 4ª: la 5ª resta, con i suoi tempi
        gestore.chiudi(quarta)
        assertEquals(listOf(5), schermo.map { it.ora })
        tempo.avanza(GestoreNotifiche.DURATA_MS)
        assertTrue(schermo.isEmpty())
    }

    @Test
    fun espandereUnaRidottaRiduceQuellaEstesa() {
        rientro(695 * 60) // 11:35: 4ª ridotta in alto, 5ª estesa in basso
        val (quarta, quinta) = schermo.toList()
        quarta.ridotta = false // l'utente preme la freccia della 4ª
        gestore.espansa(quarta)
        assertEquals(false, quarta.ridotta)
        assertEquals(true, quinta.ridotta)
        assertEquals(1, schermo.count { !it.ridotta })
        // e viceversa
        quinta.ridotta = false
        gestore.espansa(quinta)
        assertEquals(true, quarta.ridotta)
        assertEquals(1, schermo.count { !it.ridotta })
    }

    @Test
    fun scadenzaDuranteLaVisualizzazione() {
        // 10:29:57: la 2ª scade dopo 3 secondi, prima della dissolvenza generale
        rientro(630 * 60 - 3)
        assertEquals(listOf(2), schermo.map { it.ora })
        tempo.avanza(3000)
        assertTrue(schermo.isEmpty())
    }

    @Test
    fun firmaRapidaTrattieneSoloLaSuaNotifica() {
        rientro(695 * 60)
        val (quarta, quinta) = schermo.toList()
        gestore.trattieni(quarta)
        tempo.avanza(GestoreNotifiche.DURATA_MS)
        assertEquals(listOf(quarta), schermo)
        assertTrue(quinta.dissolta)
        gestore.chiudi(quarta)
        assertTrue(schermo.isEmpty())
    }
}
