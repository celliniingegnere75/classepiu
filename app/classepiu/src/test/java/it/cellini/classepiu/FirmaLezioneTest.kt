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
    fun oraDopoUltimaFirmaDellaClasse() {
        val grezzo = """{"lessons":[
            {"evtDate":"2026-11-07","evtHPos":1,"evtDuration":2,"subjectDesc":"ITALIANO","authorId":11,"studentStatus":[]},
            {"evtDate":"2026-11-07","evtHPos":3,"evtDuration":1,"subjectDesc":"MATEMATICA","authorId":12,"studentStatus":[]},
            {"evtDate":"2026-11-06","evtHPos":6,"subjectDesc":"TPSEE","authorId":5753831}
        ]}"""
        assertEquals(4, FirmaLezione.oraDopoUltimaFirma(grezzo, "20261107"))
        // nessuna firma nel giorno: si parte dalla prima ora
        assertEquals(1, FirmaLezione.oraDopoUltimaFirma("""{"lessons":[]}""", "20261107"))
        // classe con tutte le ore firmate: nessuna proposta
        assertNull(FirmaLezione.oraDopoUltimaFirma("""{"lessons":[{"evtDate":"2026-11-07","evtHPos":8,"evtDuration":2,"subjectDesc":"X"}]}""", "20261107"))
        // risposta non leggibile
        assertNull(FirmaLezione.oraDopoUltimaFirma("errore", "20261107"))
    }

    @Test
    fun oreConsecutiveDiventanoUnaLezione() {
        val b = FirmaLezione.blocchi(listOf(
            FirmaLezione.Proposta(2, "TPSEE"), FirmaLezione.Proposta(1, "TPSEE"),
            FirmaLezione.Proposta(4, "TPSEE"), FirmaLezione.Proposta(5, "SISTEMI"),
        ))
        assertEquals(listOf(FirmaLezione.Blocco(1, 2, "TPSEE"), FirmaLezione.Blocco(4, 1, "TPSEE"), FirmaLezione.Blocco(5, 1, "SISTEMI")), b)
        assertEquals(2, b[0].ultima)
    }

    @Test
    fun oreGiaFirmateInUnIntervallo() {
        val grezzo = """{"lessons":[{"evtDate":"2026-11-02","evtHPos":3,"evtDuration":1,"subjectDesc":"TPSEE","authorId":5753831}]}"""
        assertEquals(mapOf(3 to "TPSEE"), FirmaLezione.oreGiaFirmate(grezzo, "5753831", "20261102", 2, 3))
        assertTrue(FirmaLezione.oreGiaFirmate(grezzo, "5753831", "20261102", 1, 2).isEmpty())
        assertTrue(FirmaLezione.oreGiaFirmate(null, "5753831", "20261102", 1, 2).isEmpty())
    }

    @Test
    fun opzioniDellaPaginaDelRegistro() {
        val html = """
            <select id="tipo_attivita" class="styled-select open_sans" name="tipo_attivita">
              <option value="Lezione">Lezione</option> <option value="PCTO - Orientamento">PCTO - Orientamento</option>
              <option value="FSL - Sicurezza">FSL - Sicurezza</option> </select>
            <tr id="tr_progetti_set" style="display: none;"><td><input type="hidden" id="set_enable" value="1"> Progetto </td>
            <td><select id="progetto_set" name="id_progetto_set" class="styled-select"><option value=""></option><option value="1622644">2025/26&nbsp;&bull;&nbsp;- FSL - BIOTECNOLOGIE SANITARIE: ASSISTENZA</option><option value="1562019">2024/25&nbsp;&bull;&nbsp;4ENA - PCTO &quot;TECNICO DELLA PROGETTAZIONE&quot;</option></select></td></tr>
        """
        val o = FirmaLezione.leggiOpzioniFirma(html)
        assertEquals(listOf("Lezione", "PCTO - Orientamento", "FSL - Sicurezza"), o.tipiAttivita)
        assertEquals(listOf(
            FirmaLezione.Progetto("1622644", "2025/26 • - FSL - BIOTECNOLOGIE SANITARIE: ASSISTENZA"),
            FirmaLezione.Progetto("1562019", "2024/25 • 4ENA - PCTO \"TECNICO DELLA PROGETTAZIONE\""),
        ), o.progetti)
        assertTrue(o.progettiAttivi)
        assertFalse(FirmaLezione.leggiOpzioniFirma("<html></html>").progettiAttivi)
    }

    @Test
    fun tipiCheChiedonoIlProgetto() {
        assertTrue(FirmaLezione.richiedeProgetto("PCTO - Orientamento"))
        assertTrue(FirmaLezione.richiedeProgetto("FSL - Attività in aula"))
        assertTrue(FirmaLezione.richiedeProgetto("Alternanza scuola lavoro"))
        assertFalse(FirmaLezione.richiedeProgetto("Lezione"))
        assertFalse(FirmaLezione.richiedeProgetto("Ore personalizzazione - attività di PCTO"))
    }

    @Test
    fun ricercaDeiProgetti() {
        val elenco = listOf(
            FirmaLezione.Progetto("1", "2025/26 • CHIMICA e MATERIALI"),
            FirmaLezione.Progetto("2", "2025/26 • 4ETA - PCTO orientamento"),
            FirmaLezione.Progetto("3", "2025/26 • 4ETAB laboratorio"),
        )
        assertEquals(listOf("2", "1", "3"), FirmaLezione.cercaProgetti(elenco, "", "4ETA").map { it.id })
        assertEquals(listOf("2"), FirmaLezione.cercaProgetti(elenco, "orientamento 4eta", "4ETA").map { it.id })
        assertEquals(listOf("1"), FirmaLezione.cercaProgetti(elenco, "chimica", "").map { it.id })
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
