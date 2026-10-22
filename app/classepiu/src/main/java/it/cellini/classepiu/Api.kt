package it.cellini.classepiu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Una circolare della bacheca ClasseViva. */
data class Circolare(
    val evtCode: String,
    val pubId: String,
    val titolo: String,
    val data: String,
    val categoria: String,
    val testo: String,
    val letta: Boolean,
    val firma: Boolean,
    val risposta: Boolean,
    val file: Boolean,
    val adesione: Boolean,
    val allegati: List<Allegato>,
) {
    val chiave get() = "${evtCode}_$pubId"

    /** Azioni richieste dalla circolare (es. "Conferma e firma" sull'app ufficiale). */
    val azioni: List<String>
        get() = buildList {
            if (firma) add("conferma e firma")
            if (risposta) add("risposta")
            if (file) add("caricamento di un file")
        }

    /** Numero di protocollo all'inizio del titolo ("251 - ..."), se presente. */
    val numero: String
        get() = titolo.trim().substringBefore(' ').substringBefore('-').trim().takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: ""
}

data class Allegato(val numero: Int, val nome: String)

data class FileScaricato(val nome: String, val dati: ByteArray)

class ClasseVivaException(msg: String) : Exception(msg)

/**
 * Client minimo per l'interfaccia REST di ClasseViva (web.spaggiari.eu/rest/v1),
 * la stessa usata dallo script cv.py di Termux.
 */
class ClasseViva(private val utente: String, private val password: String) {

    private var token: String = ""
    private var id: String = ""
    var ident: String = ""
        private set
    private var percorso: String = ""

    private fun intestazioni(): Map<String, String> = buildMap {
        put("User-Agent", "CVVS/std/4.2.3 Android/12")
        put("Z-Dev-Apikey", "Tg1NWEwNGIgIC0K")
        put("Content-Type", "application/json")
        if (token.isNotEmpty()) put("Z-Auth-Token", token)
    }

    private class Risposta(val codice: Int, val corpo: ByteArray) {
        val testo get() = String(corpo, Charsets.UTF_8)
    }

    private fun http(metodo: String, url: String, corpo: JSONObject? = null): Risposta {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        intestazioni().forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (corpo != null) {
            c.doOutput = true
            c.outputStream.use { it.write(corpo.toString().toByteArray()) }
        }
        val codice = c.responseCode
        val flusso = if (codice in 200..299) c.inputStream else c.errorStream
        val out = ByteArrayOutputStream()
        flusso?.use { it.copyTo(out) }
        c.disconnect()
        return Risposta(codice, out.toByteArray())
    }

    fun login() {
        if (utente.isBlank() || password.isBlank()) throw ClasseVivaException("Utente e password ClasseViva non impostati (vai in Impostazioni).")
        token = ""
        fun prova(profilo: String?): JSONObject {
            val body = JSONObject().put("ident", profilo ?: JSONObject.NULL).put("pass", password).put("uid", utente)
            val r = http("POST", "$BASE/auth/login", body)
            if (r.codice != 200) throw ClasseVivaException("Accesso non riuscito (HTTP ${r.codice}): ${r.testo.take(200)}")
            return JSONObject(r.testo)
        }
        var j = prova(null)
        if (j.has("choices") && j.optString("token").isEmpty()) {
            val scelte = j.getJSONArray("choices")
            j = prova(scelte.getJSONObject(0).optString("ident"))
        }
        token = j.getString("token")
        ident = j.getString("ident")
        id = ident.filter(Char::isDigit)
    }

    fun bacheca(): List<Circolare> {
        if (token.isEmpty()) login()
        val errori = mutableListOf<String>()
        for (p in listOf("teachers", "students")) {
            val r = http("GET", "$BASE/$p/$id/noticeboard")
            if (r.codice == 200) {
                percorso = p
                val items = JSONObject(r.testo).optJSONArray("items") ?: JSONArray()
                return (0 until items.length()).map { leggi(items.getJSONObject(it)) }
            }
            errori += "$p: HTTP ${r.codice}"
        }
        throw ClasseVivaException("Bacheca non leggibile (" + errori.joinToString(", ") + ")")
    }

    private fun leggi(o: JSONObject): Circolare {
        val att = o.optJSONArray("attachments") ?: JSONArray()
        val allegati = (0 until att.length()).map { i ->
            val a = att.getJSONObject(i)
            Allegato(a.optInt("attachNum", i + 1).takeIf { it > 0 } ?: (i + 1), a.optString("fileName").ifBlank { "allegato_${i + 1}.pdf" })
        }
        return Circolare(
            evtCode = o.optString("evtCode"),
            pubId = o.optString("pubId"),
            titolo = o.optString("cntTitle").ifBlank { "Circolare" },
            data = o.optString("cntValidFrom"),
            categoria = o.optString("cntCategory"),
            testo = o.optString("cntText"),
            letta = o.optBoolean("readStatus", false),
            firma = o.optBoolean("needSign", false),
            risposta = o.optBoolean("needReply", false),
            file = o.optBoolean("needFile", false),
            adesione = o.optBoolean("needJoin", false),
            allegati = allegati,
        )
    }

    /** Scarica un allegato. Restituisce null se ClasseViva non lo rende (ancora) disponibile. */
    fun allegato(c: Circolare, a: Allegato): FileScaricato? {
        if (percorso.isEmpty()) bacheca()
        val r = http("GET", "$BASE/$percorso/$id/noticeboard/attach/${c.evtCode}/${c.pubId}/${a.numero}")
        if (r.codice == 200 && r.corpo.isNotEmpty() && !r.testo.startsWith("{")) return FileScaricato(a.nome, r.corpo)
        if (r.testo.contains(RITIRATA)) throw CircolareRitirata()
        return null
    }

    /** Segna la circolare come letta (come quando la si apre sull'app). */
    fun segnaLetta(c: Circolare): Boolean {
        if (percorso.isEmpty()) bacheca()
        // ClasseViva vuole {"join": false}: con il corpo vuoto risponde 400
        val r = http("POST", "$BASE/$percorso/$id/noticeboard/read/${c.evtCode}/${c.pubId}/101", JSONObject().put("join", false))
        return r.codice == 200
    }

    /**
     * Scarica tutti gli allegati. Molte volte ClasseViva li rende scaricabili solo dopo
     * che la circolare è stata aperta: se ne manca qualcuno la si apre e si riprova.
     */
    fun tuttiGliAllegati(c: Circolare, tentativi: Int = 3, apriSeServe: Boolean = true): List<FileScaricato> {
        val presi = sortedMapOf<Int, FileScaricato>()
        for (t in 0 until tentativi) {
            if (t > 0) Thread.sleep(2000)
            c.allegati.forEachIndexed { i, a -> if (i !in presi) allegato(c, a)?.let { presi[i] = it } }
            if (presi.size == c.allegati.size) break
            if (t == 0 && apriSeServe) {
                segnaLetta(c)
                c.allegati.forEachIndexed { i, a -> if (i !in presi) allegato(c, a)?.let { presi[i] = it } }
                if (presi.size == c.allegati.size) break
            }
        }
        return presi.values.toList()
    }

    class CircolareRitirata : Exception("Circolare ritirata dalla scuola")

    companion object {
        const val BASE = "https://web.spaggiari.eu/rest/v1"
        const val RITIRATA = "no more active"
    }
}
