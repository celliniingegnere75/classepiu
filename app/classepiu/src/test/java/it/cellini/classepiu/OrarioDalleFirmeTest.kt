package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class OrarioDalleFirmeTest {
    @Test
    fun intervalloSettimanaScorsa() {
        val lunedi = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 26) }
        assertEquals("20261019" to "20261023", OrarioDalleFirme.intervallo(lunedi))
        val martedi = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 27) }
        assertEquals("20261019" to "20261023", OrarioDalleFirme.intervallo(martedi))
        assertEquals("20261019" to "20261024", OrarioDalleFirme.intervallo(martedi, 6))
        val domenica = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 1) }
        assertEquals("20261019" to "20261023", OrarioDalleFirme.intervallo(domenica))
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

    @Test
    fun firmeVereSoloConClassId() {
        // come risponde lessons/mine: niente nome della classe, solo classId e classType (C classe, G gruppo)
        val grezzo = """{"lessons":[
            {"evtDate":"2026-10-21","authorId":5753831,"classType":"C","classId":2163613,"subjectDesc":"SCIENZE E TECNOLOGIE APPLICATE","evtHPos":6,"evtDuration":1,"lessonType":"Lezione"},
            {"evtDate":"2026-10-22","authorId":5753831,"classType":"G","classId":38296817,"subjectDesc":"TECNOLOGIE E PROGETTAZIONE DI SISTEMI ELETTRICI ED ELETTRONICI","evtHPos":1,"evtDuration":1,"lessonType":"Lezione"},
            {"evtDate":"2026-10-23","authorId":5753831,"classType":"G","classId":38296124,"subjectDesc":"ELETTROTECNICA, ELETTRONICA E AUTOMAZIONE","evtHPos":1,"evtDuration":1,"lessonType":"Assemblea di classe"}
        ]}"""
        val classi = listOf(Classe("C|2163613|2ETA", "2ETA", "", ""), Classe("G|38296817|4ETB", "4ETB", "", ""))
        val ins = listOf(
            Insegnamento("2ETA", "SCIENZE E TECNOLOGIE APPLICATE"),
            Insegnamento("4ETB", "TECNOLOGIE E PROGETTAZIONE DI SISTEMI ELETTRICI ED ELETTRONICI"),
            Insegnamento("5ETA", "ELETTROTECNICA, ELETTRONICA E AUTOMAZIONE"),
        )
        val r = OrarioDalleFirme.leggi(grezzo, "5753831", ins, 5, OrarioDalleFirme.nomiClassi(classi))
        assertEquals(Insegnamento("2ETA", "SCIENZE E TECNOLOGIE APPLICATE"), r[3 to 6])
        assertEquals(Insegnamento("4ETB", "TECNOLOGIE E PROGETTAZIONE DI SISTEMI ELETTRICI ED ELETTRONICI"), r[4 to 1])
        // id non in "le mie classi": la materia è insegnata in una sola classe, quindi è quella
        assertEquals(Insegnamento("5ETA", "ELETTROTECNICA, ELETTRONICA E AUTOMAZIONE"), r[5 to 1])
    }

    @Test
    fun giornoInPiuScartato() {
        assertEquals("20261019", OrarioDalleFirme.giornoPrima("20261020"))
        assertEquals("20260930", OrarioDalleFirme.giornoPrima("20261001"))
        val grezzo = """{"lessons":[
            {"evtDate":"2026-10-19","authorId":1,"classDesc":"5ETA","subjectDesc":"TPSEE","evtHPos":1},
            {"evtDate":"2026-10-26","authorId":1,"classDesc":"5ETA","subjectDesc":"TPSEE","evtHPos":2}
        ]}"""
        val r = OrarioDalleFirme.leggi(grezzo, "1", listOf(Insegnamento("5ETA", "TPSEE")), 5, emptyMap(), "20261020")
        assertNull(r[1 to 1])
        assertEquals(Insegnamento("5ETA", "TPSEE"), r[1 to 2])
    }
}
