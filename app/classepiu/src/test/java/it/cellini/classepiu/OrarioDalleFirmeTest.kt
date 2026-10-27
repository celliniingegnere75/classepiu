package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class OrarioDalleFirmeTest {
    @Test
    fun intervalloDaOggiMenoSetteAIeri() {
        val lunedi = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 26) }
        assertEquals("20261019" to "20261025", OrarioDalleFirme.intervallo(lunedi))
    }

    @Test
    fun giornoDellaSettimana() {
        assertEquals(1, OrarioDalleFirme.giornoDellaSettimana("2026-10-19"))
        assertEquals(6, OrarioDalleFirme.giornoDellaSettimana("20261024"))
        assertEquals(7, OrarioDalleFirme.giornoDellaSettimana("2026-10-25T00:00:00"))
    }

    @Test
    fun firmeRiempionoLeOreEScartanoGliAltri() {
        val grezzo = """{"lessons":[
            {"evtDate":"2026-10-19","evtHPos":2,"evtDuration":2,"classDesc":"4ETA ELETTRONICA","subjectDesc":"ELETTROTECNICA ED ELETTRONICA","authorId":5753831},
            {"evtDate":"2026-10-20","evtHPos":1,"classDesc":"3ETB","subjectDesc":"TPSEE","authorId":999},
            {"evtDate":"2026-10-21","evtHPos":3,"classDesc":"5ETA","subjectDesc":"EDUCAZIONE CIVICA","authorId":5753831},
            {"evtDate":"2026-10-22","evtHPos":4,"classDesc":"4ETA","subjectDesc":"ELETTROTECNICA ED ELETTRONICA","lessonType":"Supplenza","authorId":5753831},
            {"evtDate":"2026-10-23","evtHPos":5,"classDesc":"2ITA","subjectDesc":"MATEMATICA","authorId":5753831},
            {"evtDate":"2026-10-23","evtHPos":6,"classDesc":"4ETA","subjectDesc":"MATEMATICA","authorId":5753831},
            {"evtDate":"2026-10-24","evtHPos":1,"classDesc":"5ETA","subjectDesc":"TPSEE","authorId":5753831}
        ]}"""
        val ins = listOf(Insegnamento("4ETA", "ELETTROTECNICA ED ELETTRONICA"), Insegnamento("5ETA", "TPSEE"))
        val r = OrarioDalleFirme.leggi(grezzo, "5753831", ins, 5)
        assertEquals(Insegnamento("4ETA", "ELETTROTECNICA ED ELETTRONICA"), r[1 to 2])
        assertEquals(Insegnamento("4ETA", "ELETTROTECNICA ED ELETTRONICA"), r[1 to 3])
        assertNull(r[2 to 1]) // firma di un altro docente
        assertNull(r[3 to 3]) // educazione civica
        assertNull(r[4 to 4]) // supplenza
        assertNull(r[5 to 5]) // classe non mia
        assertNull(r[5 to 6]) // materia non mia in una mia classe
        assertNull(r[6 to 1]) // sabato spento
        assertEquals(Insegnamento("5ETA", "TPSEE"), OrarioDalleFirme.leggi(grezzo, "5753831", ins, 6)[6 to 1])
    }
}
