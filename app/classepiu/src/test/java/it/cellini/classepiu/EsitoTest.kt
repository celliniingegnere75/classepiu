package it.cellini.classepiu

import org.junit.Assert.assertEquals
import org.junit.Test

class EsitoTest {
    private fun e(nuove: Int = 0, errori: Int = 0, presenti: Int = 10, allegati: Int = 0, attesa: Int = 0, inviate: Boolean = true) =
        Esito(nuove, errori, false, "", presenti, allegati, attesa, inviate).riassunto()

    @Test
    fun messaggi() {
        assertEquals("nessuna circolare presente.", e(presenti = 0))
        assertEquals("nessuna circolare nuova: tutte le circolari e gli allegati sono già stati inviati.", e())
        assertEquals("inviate 3 circolari per email.", e(nuove = 3))
        assertEquals("inviata 1 circolare per email.", e(nuove = 1))
        assertEquals("inviate 2 circolari per email, 1 non partita (si riprova).", e(nuove = 2, errori = 1))
        assertEquals("nessuna email partita: 2 circolari non inviate, si riprova.", e(errori = 2))
        assertEquals("nessuna circolare nuova. 1 circolare aspetta ancora gli allegati da classeviva.", e(attesa = 1))
        assertEquals("invio per email spento: 2 circolari nuove non inviate.", e(nuove = 2, inviate = false))
    }
}
