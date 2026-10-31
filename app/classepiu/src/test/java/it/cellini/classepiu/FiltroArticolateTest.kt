package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dati del log reale di 5EA del 22/10/2026: 5EA_AUA (alunni a1..a11) e 5EA_ENA (alunni b1..b12). */
class FiltroArticolateTest {
    private val aua = (1..11).map { "a$it" }.toSet()
    private val ena = (1..12).map { "b$it" }.toSet()
    private val io = "5753831"

    private fun f(ora: Int, materia: String, autore: String, alunni: Set<String>) = FiltroArticolate.Firma("$ora", materia, autore, alunni)

    private val giorno = listOf(
        f(1, "TPSEE", io, aua) to true,
        f(1, "MATEMATICA", "turchi", ena) to false,
        f(2, "TPSEE", io, aua) to true,
        f(2, "MATEMATICA", "turchi", ena) to false,
        f(3, "MATEMATICA", "turchi", aua) to true,
        f(3, "LINGUA INGLESE", "zallocco", ena) to false,
        f(4, "MECCANICA", "digirolamo", emptySet()) to false,
        f(4, "MATEMATICA", "turchi", aua) to true,
        f(4, "MECCANICA", "perticari", ena) to false,
        f(5, "MECCANICA", "digirolamo", emptySet()) to false,
        f(5, "PROGETTI", "scendoni", aua) to true,
        f(6, "STORIA", "pierantozzi", aua + ena) to true,
        f(7, "IMPIANTI", "defiore", ena) to false,
        f(8, "SISTEMI", "camilli", emptySet()) to false,
        f(8, "SISTEMI", "vesprini", ena) to false,
        f(9, "SISTEMI", "vesprini", ena) to false,
        f(9, "SISTEMI", "camilli", emptySet()) to false,
    )

    @Test
    fun soloLaMiaMeta() {
        assertEquals(giorno.map { it.second }, FiltroArticolate.visibili(giorno.map { it.first }, io, aua))
    }

    /** Log del 20/10/2026: Facello è in compresenza con me (si vede), Camilli con De Fiore (no). */
    @Test
    fun compresenzeDel20Ottobre() {
        val firme = listOf(
            f(1, "IMPIANTI", "camilli", emptySet()) to false,
            f(1, "SISTEMI AUTOMATICI", "faro", aua) to true,
            f(1, "IMPIANTI", "defiore", ena) to false,
            f(2, "IMPIANTI", "camilli", emptySet()) to false,
            f(2, "SISTEMI AUTOMATICI", "faro", aua) to true,
            f(2, "IMPIANTI", "defiore", ena) to false,
            f(3, "TPSEE", "facello", emptySet()) to true,
            f(3, "LINGUA INGLESE", "zallocco", ena) to false,
            f(3, "TPSEE", io, aua) to true,
            f(4, "TPSEE", "facello", emptySet()) to true,
            f(4, "IMPIANTI", "defiore", ena) to false,
            f(4, "TPSEE", io, aua) to true,
            f(5, "STORIA", "pierantozzi", aua + ena) to true,
            f(6, "STORIA", "pierantozzi", aua + ena) to true,
        )
        assertEquals(firme.map { it.second }, FiltroArticolate.visibili(firme.map { it.first }, io, aua))
    }

    /** Log del 23/10/2026: Stortini (LSC0, studentStatus null) è in compresenza con Sanseverinati, che ha la mia metà. */
    @Test
    fun compresenzeDel23Ottobre() {
        val firme = listOf(
            f(1, "SCIENZE MOTORIE", "vallasciani", aua + ena) to true,
            f(3, "STORIA", "pierantozzi", aua + ena) to true,
            f(4, "ELETTROTECNICA ED ELETTRONICA", "stortini", emptySet()) to true,
            f(4, "MECCANICA", "perticari", ena) to false,
            f(4, "ELETTROTECNICA ED ELETTRONICA", "sanseverinati", aua) to true,
            f(5, "ELETTROTECNICA ED ELETTRONICA", "stortini", emptySet()) to true,
            f(5, "TECNOLOGIE MECCANICHE", "allevi", ena) to false,
            f(5, "ELETTROTECNICA ED ELETTRONICA", "sanseverinati", aua) to true,
        )
        assertEquals(firme.map { it.second }, FiltroArticolate.visibili(firme.map { it.first }, io, aua))
    }

    @Test
    fun compresenzaDellaMiaMeta() {
        val firme = listOf(f(3, "SISTEMI", "titolare", aua), f(3, "SISTEMI", "itp", emptySet()), f(3, "SISTEMI", "altro", ena))
        assertEquals(listOf(true, true, false), FiltroArticolate.visibili(firme, io, aua))
    }

    /** "Le mie lezioni" del 20/10: io e Facello (mia compresenza), non Camilli né gli altri. */
    @Test
    fun leMieLezioniConCompresenza() {
        val firme = listOf(
            f(1, "IMPIANTI", "camilli", emptySet()), f(1, "SISTEMI AUTOMATICI", "faro", aua),
            f(3, "TPSEE", "facello", emptySet()), f(3, "TPSEE", io, aua),
            f(4, "TPSEE", "facello", emptySet()), f(4, "TPSEE", io, aua),
            f(4, "ELETTROTECNICA ED ELETTRONICA", "stortini", emptySet()), f(5, "STORIA", "pierantozzi", aua + ena),
        )
        assertEquals(listOf(false, false, true, true, true, true, false, false), FiltroArticolate.mieECompresenze(firme, io))
    }

    @Test
    fun classeNonArticolataMostraTutto() {
        val firme = giorno.map { it.first }
        assertEquals(firme.map { true }, FiltroArticolate.visibili(firme, io, emptySet()))
    }
}
