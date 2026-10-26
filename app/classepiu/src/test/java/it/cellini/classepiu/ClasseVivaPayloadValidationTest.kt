package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClasseVivaPayloadValidationTest {
    @Test
    fun votoConEvtIdValidoVieneAccettato() {
        val voto = ClasseVivaPayloadValidation.voto("""{"evtId":123,"displayValue":"8"}""")
        assertEquals("123", voto.optString("evtId"))
    }

    @Test
    fun votoSenzaEvtIdVieneRifiutato() {
        assertThrows(IllegalArgumentException::class.java) {
            ClasseVivaPayloadValidation.voto("""{"displayValue":"8"}""")
        }
    }

    @Test
    fun votoVuotoOMalformatoVieneRifiutato() {
        assertThrows(IllegalArgumentException::class.java) {
            ClasseVivaPayloadValidation.voto("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClasseVivaPayloadValidation.voto("{")
        }
    }

    @Test
    fun firmaConIdentificativoVieneAccettata() {
        val firma = ClasseVivaPayloadValidation.firma("""{"evtId":456,"lessonArg":"Lezione"}""")
        assertEquals("456", firma.optString("evtId"))
    }

    @Test
    fun firmaSenzaIdentificativoVieneRifiutata() {
        assertThrows(IllegalArgumentException::class.java) {
            ClasseVivaPayloadValidation.firma("""{"lessonArg":"Lezione"}""")
        }
    }
}
