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
        for (p in listOf("students", "teachers")) { // stesso ordine di cv.py
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

    /**
     * Classi del docente: "mine" = le sue classi, "all/students1" = tutte le classi della scuola.
     * Restituisce il codice HTTP e il testo grezzo della risposta (letto da leggiClassi).
     */
    fun classiGrezze(tutte: Boolean): Pair<Int, String> {
        if (token.isEmpty()) login()
        val percorsoClassi = if (tutte) "classes/all/students1" else "classes/mine"
        val r = http("GET", "$BASE/teachers/$id/$percorsoClassi")
        return r.codice to r.testo
    }

    /** Elenco degli studenti di una classe (risposta grezza, letta da leggiStudenti). */
    fun studentiGrezzi(tipo: String, classeId: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/students/list/${tipo.ifEmpty { "ordinary" }}/$classeId")
        return r.codice to r.testo
    }

    /** Presenze della classe in un giorno (yyyyMMdd): risposta grezza, letta da leggiStato. */
    fun statoGrezzo(tipo: String, classeId: String, giorno: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/students/status2/${tipo.ifEmpty { "ordinary" }}/$classeId/$giorno")
        return r.codice to r.testo
    }

    /** Segna la circolare come letta (come quando la si apre sull'app). */
    fun segnaLetta(c: Circolare, aderisci: Boolean = false): Boolean {
        if (percorso.isEmpty()) bacheca()
        // ClasseViva vuole {"join": false}: con il corpo vuoto risponde 400
        val r = http("POST", "$BASE/$percorso/$id/noticeboard/read/${c.evtCode}/${c.pubId}/101", JSONObject().put("join", aderisci))
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

data class Studente(val id: String, val nome: String)

private fun trovaArrayGenerico(v: Any?, chiavi: List<String>, liv: Int = 0): JSONArray? {
    if (v is JSONArray) return v
    if (v is JSONObject && liv < 3) {
        for (k in chiavi) v.optJSONArray(k)?.let { return it }
        for (k in v.keys()) trovaArrayGenerico(v.opt(k), chiavi, liv + 1)?.let { return it }
    }
    return null
}

private fun idDi(o: JSONObject): String {
    val s = o.optJSONObject("student") ?: o
    for (k in listOf("studentId", "id", "student_id", "pupilId")) {
        val v = s.opt(k) ?: o.opt(k)
        if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) return v.toString()
    }
    return ""
}

/** Studenti di una classe dalla risposta di ClasseViva (campi dedotti, lettura tollerante). */
fun leggiStudenti(grezzo: String): List<Studente> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    val a = trovaArrayGenerico(radice, listOf("students", "items", "data", "pupils")) ?: return emptyList()
    fun JSONObject.t(vararg ks: String): String {
        for (k in ks) { val v = opt(k); if (v is String && v.isNotBlank()) return v.trim() }
        return ""
    }
    val lista = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val s = o.optJSONObject("student") ?: o
        val cognome = s.t("lastName", "surname", "cognome", "studentLastName")
        val nome = s.t("firstName", "name", "nome", "studentFirstName")
        val intero = s.t("studentName", "fullName", "completeName")
        val r = listOf(cognome, nome).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { intero }
        if (r.isEmpty()) null else Studente(idDi(o), r.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    }
    return lista.distinctBy { it.nome }.sortedBy { it.nome }
}

/** Stato di presenza per id studente: 0 presente, 1 altra condizione (ritardo, uscita...), 2 assente. Vuota = nessun dato. */
fun leggiStato(grezzo: String): Map<String, Int> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
    val a = trovaArrayGenerico(radice, listOf("students", "items", "data", "status", "pupils")) ?: return emptyMap()
    val m = HashMap<String, Int>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val id = idDi(o).ifEmpty { continue }
        val t = o.toString().lowercase()
        val v = when {
            t.contains("\"aba") || t.contains("assen") || t.contains("absen") -> 2
            t.contains("\"abr") || t.contains("\"abu") || t.contains("ritard") || t.contains("uscit") || t.contains("late") || t.contains("\"abl") -> 1
            else -> 0
        }
        m[id] = maxOf(m[id] ?: 0, v)
    }
    return m
}

/** Una classe, come nell'elenco dell'app ufficiale (senza le materie). */
data class Classe(val chiave: String, val nome: String, val descrizione: String, val codice: String)

/**
 * Legge l'elenco delle classi dalla risposta di ClasseViva. Prova più nomi di campo possibili,
 * perché la risposta non è documentata; le classi uguali (una riga per materia) si contano una volta sola.
 */
fun leggiClassi(grezzo: String): List<Classe> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    fun trovaArray(v: Any?, livello: Int = 0): JSONArray? {
        if (v is JSONArray) return v
        if (v is JSONObject && livello < 3) {
            for (k in listOf("classes", "classrooms", "items", "data", "classi")) v.optJSONArray(k)?.let { return it }
            for (k in v.keys()) trovaArray(v.opt(k), livello + 1)?.let { return it }
        }
        return null
    }
    val array = trovaArray(radice) ?: return emptyList()
    fun JSONObject.testo(vararg chiavi: String): String {
        for (k in chiavi) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                val t = v.toString().trim()
                if (t.isNotEmpty() && t != "null") return t
            }
        }
        return ""
    }
    val visti = LinkedHashMap<String, Classe>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val c = o.optJSONObject("classroom") ?: o.optJSONObject("class") ?: o
        fun t(vararg k: String) = c.testo(*k).ifEmpty { o.testo(*k) }
        val livello = t("classLevel", "classRealLevel", "level")
        val indirizzo = t("classAddr", "classAddress", "address")
        val sezione = t("classSection", "section")
        var nome = t("className", "classroomName", "name", "classCode")
        if (nome.isEmpty()) nome = livello + indirizzo + sezione
        val descrizione = t("classDesc", "classroomDescription", "description", "desc", "classDescription")
        var codice = t("classCode", "classroomCode", "code", "classCodeDescription", "schoolClassCode")
        if (codice == nome) codice = ""
        if (nome.isEmpty() && descrizione.isEmpty()) continue
        val id = t("classId", "classroomId", "id").ifEmpty { nome }
        val tipo = t("classType", "classroomType", "type")
        visti.getOrPut("$tipo|$id|$nome") { Classe("$tipo|$id|$nome", nome.uppercase(), descrizione.uppercase(), codice.uppercase()) }
    }
    return visti.values.sortedWith(compareBy({ it.nome.firstOrNull()?.digitToIntOrNull() ?: 9 }, { it.nome }))
}
