package it.cellini.classepiu

import org.json.JSONObject

/**
 * Validazioni minime prima di inviare richieste che modificano o cancellano dati su ClasseViva.
 * Non sostituiscono una conferma esplicita nell'interfaccia: impediscono payload vuoti o senza identità.
 */
internal object ClasseVivaPayloadValidation {
    fun voto(grezzo: String): JSONObject {
        val oggetto = oggetto(grezzo, "voto")
        require(oggetto.optString("evtId").isNotBlank()) {
            "Operazione annullata: il voto non contiene un evtId valido."
        }
        return oggetto
    }

    fun firma(grezzo: String): JSONObject {
        val oggetto = oggetto(grezzo, "firma della lezione")
        val haIdentita = listOf("evtId", "lessonId", "lessonID", "id", "evtCode")
            .any { chiave ->
                val valore = oggetto.opt(chiave)
                valore != null && valore != JSONObject.NULL && valore.toString().isNotBlank()
            }
        require(haIdentita) {
            "Operazione annullata: la firma della lezione non contiene un identificativo."
        }
        return oggetto
    }

    private fun oggetto(grezzo: String, descrizione: String): JSONObject {
        require(grezzo.isNotBlank()) { "Operazione annullata: $descrizione senza dati." }
        return try {
            JSONObject(grezzo)
        } catch (e: Exception) {
            throw IllegalArgumentException("Operazione annullata: dati di $descrizione non validi.", e)
        }
    }
}
