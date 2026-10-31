package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Test

class GruppiScuolaTest {
    @Test
    fun gruppiDallaPaginaTuttiIGruppi() {
        val html = """
            <tr><td>5EA ENERGIA - AUTOMAZIONE</td><td><a href="regclasse.php?corsoextra=0&gruppo_id=5EA_AUA">5EA_AUA</a></td></tr>
            <tr><td></td><td><a href="regclasse.php?corsoextra=0&gruppo_id=5EA_ENA">5EA_ENA</a></td></tr>
            <tr><td>5AN CONDUZIONE DEL MEZZO AEREO - NAVALE</td><td><a href="regclasse.php?corsoextra=0&gruppo_id=5AN_CAA">5AN_CAA</a></td></tr>
            <tr><td></td><td><a href="regclasse.php?corsoextra=0&gruppo_id=5AN_CNA">5AN_CNA</a></td></tr>
            <tr><td>1IL INFORMATICA</td><td>1IL_1ITD</td></tr><tr><td></td><td>1IL_1TLB</td></tr>
        """
        assertEquals(
            mapOf("5EA" to listOf("5EA_AUA", "5EA_ENA"), "5AN" to listOf("5AN_CAA", "5AN_CNA"), "1IL" to listOf("1IL_1ITD", "1IL_1TLB")),
            leggiGruppiScuola(html)
        )
    }

    @Test
    fun alunniDelGruppoIncrociatiConLaClasse() {
        val html = """<tr studente_id="10527600"><td>Rossi</td></tr><tr data-id="9634176"></tr><span>2026</span><i>38296817</i>"""
        assertEquals(setOf("10527600", "9634176"), alunniInPagina(html, setOf("10527600", "9634176", "8948723")))
    }
}
