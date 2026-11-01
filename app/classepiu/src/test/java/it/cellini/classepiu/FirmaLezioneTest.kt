package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmaLezioneTest {
    private val mie = listOf("C|101|4ETA", "C|102|3ETB", "G|900|5EA_AUA")

    @Test
    fun registroClasseNormale() {
        assertEquals(FirmaLezione.Registro("C", "101", "4ETA"), FirmaLezione.registroDaFirmare(mie, "C", "101", ""))
    }

    @Test
    fun registroGruppoArticolato() {
        // gruppo aperto dalla classe intera (5EA, id 500): si firma sul gruppo, con il suo nome per nomeClasse
        assertEquals(FirmaLezione.Registro("G", "900", "5EA_AUA"), FirmaLezione.registroDaFirmare(mie, "C", "500", "5EA_AUA"))
        // gruppo aperto da "i miei registri"
        assertEquals(FirmaLezione.Registro("G", "900", "5EA_AUA"), FirmaLezione.registroDaFirmare(mie, "G", "900", ""))
    }

    @Test
    fun classeNonMiaNonSiFirma() {
        assertNull(FirmaLezione.registroDaFirmare(mie, "C", "777", ""))
        assertNull(FirmaLezione.registroDaFirmare(mie, "C", "500", "5EA_ENA"))
        assertNull(FirmaLezione.registroDaFirmare(mie, "C", "", ""))
    }

    @Test
    fun orarioUnaLezione() {
        val ore = listOf(
            FirmaLezione.OraOrario(2, "4ETA", "ELETTROTECNICA ED ELETTRONICA"),
            FirmaLezione.OraOrario(3, "3ETB", "TPSEE"),
        )
        val a = FirmaLezione.analizzaOrario(ore, FirmaLezione.Registro("C", "101", "4ETA"), listOf("Elettrotecnica  ed elettronica"))
        assertEquals(listOf(FirmaLezione.Proposta(2, "Elettrotecnica  ed elettronica")), a.proposte)
        assertTrue(a.oreSenzaMateria.isEmpty())
    }

    @Test
    fun orarioPiuLezioni() {
        val ore = listOf(
            FirmaLezione.OraOrario(5, "4ETA", "TPSEE"),
            FirmaLezione.OraOrario(1, "4ETA ELETTRONICA", "ELETTROTECNICA ED ELETTRONICA"),
        )
        val a = FirmaLezione.analizzaOrario(ore, FirmaLezione.Registro("C", "101", "4ETA"), listOf("ELETTROTECNICA ED ELETTRONICA", "TPSEE"))
        assertEquals(listOf(1, 5), a.proposte.map { it.ora })
    }

    @Test
    fun orarioIncompletoOMateriaNonMia() {
        val ore = listOf(
            FirmaLezione.OraOrario(4, "4ETA", ""),
            FirmaLezione.OraOrario(6, "4ETA", "MATEMATICA"),
        )
        val a = FirmaLezione.analizzaOrario(ore, FirmaLezione.Registro("C", "101", "4ETA"), listOf("TPSEE"))
        assertTrue(a.proposte.isEmpty())
        assertEquals(listOf(4, 6), a.oreSenzaMateria)
        // orario vuoto
        val vuoto = FirmaLezione.analizzaOrario(emptyList(), FirmaLezione.Registro("C", "101", "4ETA"), listOf("TPSEE"))
        assertTrue(vuoto.proposte.isEmpty() && vuoto.oreSenzaMateria.isEmpty())
    }

    @Test
    fun orarioDelGruppoNonDellaClasseIntera() {
        val ore = listOf(FirmaLezione.OraOrario(2, "5EA_AUA", "TPSEE"), FirmaLezione.OraOrario(3, "5EA", "TPSEE"))
        val a = FirmaLezione.analizzaOrario(ore, FirmaLezione.Registro("G", "900", "5EA_AUA"), listOf("TPSEE"))
        assertEquals(listOf(2), a.proposte.map { it.ora })
    }

    @Test
    fun firmaGiaPresente() {
        val grezzo = """{"lessons":[
            {"evtDate":"2026-11-02","evtHPos":2,"evtDuration":2,"classId":101,"subjectDesc":"TPSEE","authorId":5753831},
            {"evtDate":"2026-11-02","evtHPos":5,"classId":102,"subjectDesc":"MATEMATICA","authorId":999},
            {"evtDate":"2026-11-03","evtHPos":1,"classId":101,"subjectDesc":"TPSEE","authorId":5753831}
        ]}"""
        assertEquals("TPSEE", FirmaLezione.firmaEsistente(grezzo, "5753831", "20261102", 3))
        assertEquals("TPSEE", FirmaLezione.firmaEsistente(grezzo, "5753831", "20261102", 2))
        assertNull(FirmaLezione.firmaEsistente(grezzo, "5753831", "20261102", 4))
        assertNull(FirmaLezione.firmaEsistente(grezzo, "5753831", "20261102", 5)) // firma di un altro docente
        assertNull(FirmaLezione.firmaEsistente(grezzo, "5753831", "20261102", 1))
        assertNull(FirmaLezione.firmaEsistente("non json", "5753831", "20261102", 1))
    }

    @Test
    fun esitoRiuscito() {
        val e = FirmaLezione.esito(200, """{"status":"OK","evento_id":123}""")
        assertTrue(e.ok)
        assertEquals("firma registrata (http 200)", e.titolo)
    }

    @Test
    fun esitoErroreConCodiceEMessaggio() {
        val e = FirmaLezione.esito(422, """{"status":"KO","message":"Ora non disponibile"}""")
        assertFalse(e.ok)
        assertEquals("firma non riuscita (http 422)", e.titolo)
        assertEquals("Ora non disponibile", e.dettaglio)
        val n = FirmaLezione.esito(404, "materia \"FISICA\" non trovata tra le tue")
        assertEquals("firma non riuscita (http 404)", n.titolo)
        assertEquals("materia \"FISICA\" non trovata tra le tue", n.dettaglio)
        val h = FirmaLezione.esito(500, "<html><body><h1>Errore</h1> interno</body></html>")
        assertEquals("Errore interno", h.dettaglio)
        assertEquals("nessun messaggio dal server", FirmaLezione.esito(503, "").dettaglio)
    }

    @Test
    fun esitoOraGiaFirmata() {
        val e = FirmaLezione.esito(422, """{"status":"KO","msg":"Attenzione: ora già firmata"}""")
        assertFalse(e.ok)
        assertTrue(e.giaFirmata)
        assertEquals("ora già firmata (http 422)", e.titolo)
    }

    @Test
    fun erroriDiRete() {
        assertTrue(FirmaLezione.descriviErrore(java.net.UnknownHostException("web.spaggiari.eu")).startsWith("nessuna connessione"))
        assertTrue(FirmaLezione.descriviErrore(java.net.SocketTimeoutException("read timed out")).contains("timeout"))
        assertTrue(FirmaLezione.descriviErrore(RuntimeException("x", java.net.ConnectException("refused"))).startsWith("connessione a classeviva non riuscita"))
        assertTrue(FirmaLezione.descriviErrore(org.json.JSONException("bad")).startsWith("risposta di classeviva non valida"))
        assertEquals("classe non tua", FirmaLezione.descriviErrore(IllegalStateException("classe non tua")))
        assertEquals("IllegalStateException", FirmaLezione.descriviErrore(IllegalStateException()))
    }
}
