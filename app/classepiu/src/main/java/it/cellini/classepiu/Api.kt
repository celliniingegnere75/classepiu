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
class ClasseViva(private val utente: String, private val password: String, private val appContext: android.content.Context? = null) {

    @Volatile private var token: String = ""
    @Volatile private var id: String = ""
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

    private fun http(metodo: String, url: String, corpo: JSONObject? = null, riprova: Boolean = true): Risposta {
        // Elimina eventuali file diagnostici delle versioni precedenti.
        appContext?.filesDir?.let { dir ->
            runCatching { java.io.File(dir, "classepiu_log.txt").delete() }
            runCatching { java.io.File(dir, "classepiu_media_log.txt").delete() }
        }
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
        // Token condiviso scaduto: un nuovo accesso e una sola nuova prova.
        if (codice == 401 && riprova && token.isNotEmpty() && !url.endsWith("/auth/login")) {
            synchronized(blocco) { if (condiviso?.token == token) condiviso = null }
            login()
            return http(metodo, url, corpo, riprova = false)
        }
        return Risposta(codice, out.toByteArray())
    }

    /**
     * Accede solo se serve: riusa il token dell'ultimo accesso con le stesse credenziali, così le
     * schermate che aprono più client (es. elenco classe) non rifanno ogni volta il login.
     */
    private fun assicuraLogin() {
        if (token.isNotEmpty()) return
        synchronized(blocco) {
            if (token.isNotEmpty()) return
            val s = condiviso
            if (s != null && s.utente == utente && s.password == password && System.currentTimeMillis() < s.scade) {
                token = s.token; ident = s.ident; id = s.id
                return
            }
            login()
        }
    }

    fun login() {
        if (utente.isBlank() || password.isBlank()) throw ClasseVivaException("utente e password classeviva non impostati (vai in opzioni).")
        token = ""
        fun prova(profilo: String?): JSONObject {
            val body = JSONObject().put("ident", profilo ?: JSONObject.NULL).put("pass", password).put("uid", utente)
            val r = http("POST", "$BASE/auth/login", body)
            if (r.codice != 200) throw ClasseVivaException("accesso non riuscito (http ${r.codice}): ${r.testo.take(200)}")
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
        synchronized(blocco) {
            condiviso = Sessione(utente, password, token, ident, id, System.currentTimeMillis() + DURATA_TOKEN)
        }
    }

    fun bacheca(): List<Circolare> {
        assicuraLogin()
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
        throw ClasseVivaException("bacheca non leggibile (" + errori.joinToString(", ") + ")")
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
            titolo = o.optString("cntTitle").ifBlank { "circolare" },
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
        assicuraLogin()
        val percorsoClassi = if (tutte) "classes/all/students1" else "classes/mine"
        val r = http("GET", "$BASE/teachers/$id/$percorsoClassi")
        return r.codice to r.testo
    }

    /** Elenco degli studenti di una classe (risposta grezza, letta da leggiStudenti). */
    fun studentiGrezzi(tipo: String, classeId: String): Pair<Int, String> {
        assicuraLogin()
        val r = http("GET", "$BASE/teachers/$id/students/list/${tipo.ifEmpty { "ordinary" }}/$classeId")
        return r.codice to r.testo
    }

    /** Presenze della classe in un giorno (yyyyMMdd): risposta grezza, letta da leggiStato. */
    fun statoGrezzo(tipo: String, classeId: String, giorno: String): Pair<Int, String> {
        assicuraLogin()
        val r = http("GET", "$BASE/teachers/$id/students/status2/${tipo.ifEmpty { "ordinary" }}/$classeId/$giorno")
        return r.codice to r.testo
    }

    /** Materie assegnate al docente nella classe, anche se non hanno ancora voti. */
    fun materieMie(tipo: String, classeId: String): List<String> {
        assicuraLogin()
        val t = tipo.ifEmpty { "ordinary" }
        val r = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        if (r.codice != 200) return emptyList()
        val radice = runCatching { org.json.JSONTokener(r.testo).nextValue() }.getOrNull() ?: return emptyList()
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return emptyList()
        val voci = (0 until materie.length()).mapNotNull { materie.optJSONObject(it) }
        val ceMia = voci.any { it.optBoolean("isMine", false) }
        return voci
            .filter { !ceMia || it.optBoolean("isMine", false) }
            .mapNotNull { it.optString("subjectDesc").trim().takeIf(String::isNotEmpty) }
            .distinct()
    }

    /**
     * Voti della classe, come l'app ufficiale Classeviva Docenti: elenco materie (subjects/list) e per ciascuna
     * evaluations/grades2/{tipo}/{classe}/{materia}. Le risposte delle materie leggibili vengono riunite in un solo array JSON.
     */
    fun votiGrezzi(tipo: String, classeId: String): Pair<Int, String> {
        assicuraLogin()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        if (m.codice != 200) return m.codice to "subjects/list -> ${m.testo.take(300)}"
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return 500 to "subjects/list non leggibile: ${m.testo.take(300)}" }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return 404 to "nessuna materia: ${m.testo.take(300)}"
        val unite = JSONArray()
        val esiti = StringBuilder()
        val ceMia = (0 until materie.length()).any { materie.optJSONObject(it)?.optBoolean("isMine", false) == true }
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (ceMia && !o.optBoolean("isMine", false)) continue // solo i voti delle materie del docente
            val sid = listOf("subjectId", "id", "subject_id").firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }?.toString() } ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/grades2/$t/$classeId/$sid")
            // ogni risposta porta con sé la materia, così i voti si possono separare (es. educazione civica)
            if (r.codice == 200) unite.put(JSONObject().put("subjectId", sid).put("subjectDesc", o.optString("subjectDesc")).put("data", try { org.json.JSONTokener(r.testo).nextValue() } catch (e: Exception) { continue }))
            else esiti.append("materia $sid -> HTTP ${r.codice} ${r.testo.take(120)}\n")
        }
        return if (unite.length() > 0) 200 to unite.toString() else 404 to esiti.toString().ifEmpty { "nessuna materia con voti" }
    }

    /** Componenti e livelli di voto di ogni materia della classe (evaluations/components), risposte grezze una dopo l'altra. */
    fun componentiGrezzi(tipo: String, classeId: String): String {
        assicuraLogin()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        val sb = StringBuilder("subjects/list HTTP ${m.codice}\n${m.testo.take(3000)}\n")
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return sb.toString() }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return sb.toString()
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (!o.optBoolean("isMine", false)) continue // le componenti esistono solo per le materie del docente (le altre: 404 "wrong subject-id")
            val sid = listOf("subjectId", "id", "subject_id").firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }?.toString() } ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/components/$t/$classeId/$sid")
            sb.append("\ncomponents/$sid HTTP ${r.codice}\n${r.testo.take(12000)}\n")
        }
        return sb.toString()
    }

    /** Note disciplinari della classe nell'intervallo (yyyyMMdd), risposta grezza. */
    fun noteGrezze(tipo: String, classeId: String, da: String, a: String): Pair<Int, String> {
        assicuraLogin()
        val r = http("GET", "$BASE/teachers/$id/notes/range/${tipo.ifEmpty { "ordinary" }}/$classeId/$da/$a")
        return r.codice to r.testo
    }

    /** Lezioni della classe in un giorno (yyyyMMdd), con i docenti che le hanno firmate: risposta grezza. */
    fun lezioniClasseGrezze(tipo: String, classeId: String, giorno: String): Pair<Int, String> {
        assicuraLogin()
        val r = http("GET", "$BASE/teachers/$id/lessons2/students/${tipo.ifEmpty { "ordinary" }}/$classeId/$giorno")
        return r.codice to r.testo
    }

    /**
     * Orario di ritardo breve, ora di lezione di entrata del ritardo ("Ritardo entra", es. "2")
     * e orario di uscita anticipata letti per uno studente dal popup web.
     */
    data class OrariEvento(val ritardo: String? = null, val uscita: String? = null, val oraEntrata: String? = null)

    /**
     * Legge l'ora del ritardo (breve o normale) e/o dell'uscita anticipata dal popup di sola consultazione
     * del registro web. Tutte le richieste sono GET: non viene mai inviato il modulo di cambio stato.
     * [giorno] è yyyyMMdd e gli ID sono quelli degli studenti con stato ABR1/AT.. o ABU0.
     *
     * Il popup "Cambia Evento" ha un campo orario per il ritardo breve (`id="popup_evento_ora"`) e uno
     * per l'uscita (`id="popup_evento_ora_u"`), verificato sull'HTML grezzo del popup.
     * Per il ritardo normale ("Ritardo entra", radio `popup_ritardol` spuntato, codice ABR0) l'ora di
     * lezione di entrata è l'opzione selezionata di `popup_ora_ritardo`; in quel caso `popup_evento_ora`
     * contiene solo l'ora attuale e va ignorato.
     */
    fun orariEventoWeb(
        classeId: String, giorno: String, studentiRitardo: Set<String>, studentiUscita: Set<String>,
        debug: StringBuilder? = null
    ): Map<String, OrariEvento> {
        val studenti = studentiRitardo + studentiUscita
        if (studenti.isEmpty()) return emptyMap()
        val web = sessioneWeb()
        try {
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
            val data = if (giorno.length == 8) "${giorno.substring(0, 4)}-${giorno.substring(4, 6)}-${giorno.substring(6, 8)}" else giorno
            val base = "https://web.spaggiari.eu/cvv/app/default/regclasse_conpresenza.php?classe_id=${enc(classeId)}&gruppo_id="
            val (codicePagina, pagina) = web.chiama(base)
            if (codicePagina !in 200..299) throw ClasseVivaException("registro presenze web non leggibile (HTTP $codicePagina)")
            val ultimaOra = Regex("""id\s*=\s*["']ora_posizione["'][^>]*value\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(pagina)?.groupValues?.get(1)
                ?: Regex("""value\s*=\s*["']([^"']+)["'][^>]*id\s*=\s*["']ora_posizione["']""", RegexOption.IGNORE_CASE)
                    .find(pagina)?.groupValues?.get(1)
                ?: "1"
            val campoOra = Regex("""<input\b[^>]*\bid=["']popup_evento_ora(_u)?["'][^>]*>""", RegexOption.IGNORE_CASE)
            val valoreOra = Regex("""\bvalue\s*=\s*["']((?:[01]\d|2[0-3]):[0-5]\d)["']""", RegexOption.IGNORE_CASE)
            val ritardoLungoSpuntato = Regex("""<input\b(?=[^>]*\bid=["']popup_ritardol["'])(?=[^>]*\bchecked\b)[^>]*>""", RegexOption.IGNORE_CASE)
            val sceltaOraRitardo = Regex("""<select\b[^>]*\bid=["']popup_ora_ritardo["'][^>]*>(.*?)</select>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val opzioneSelezionata = Regex("""<option\b[^>]*\bvalue=["'](\d+)["'][^>]*\bselected\b""", RegexOption.IGNORE_CASE)
            val risultato = linkedMapOf<String, OrariEvento>()
            for (studenteId in studenti) {
                val url = base + "&action=cambiastato&studente_id=${enc(studenteId)}&data=${enc(data)}&ultimaorafirmata=${enc(ultimaOra)}"
                val (codice, html) = web.chiama(url, referer = base)
                debug?.append("studente $studenteId: HTTP $codice\n$html\n\n")
                if (codice !in 200..299) continue
                var oraRitardo: String? = null
                var oraUscita: String? = null
                val ritardoLungo = ritardoLungoSpuntato.containsMatchIn(html)
                for (m in campoOra.findAll(html)) {
                    val ora = valoreOra.find(m.value)?.groupValues?.get(1) ?: continue
                    if (m.groupValues[1].isNotEmpty()) oraUscita = ora else if (!ritardoLungo) oraRitardo = ora
                }
                val oraEntrata = if (!ritardoLungo) null else sceltaOraRitardo.find(html)?.groupValues?.get(1)
                    ?.let { opzioneSelezionata.find(it)?.groupValues?.get(1) }
                if (oraRitardo != null || oraUscita != null || oraEntrata != null) {
                    risultato[studenteId] = OrariEvento(oraRitardo, oraUscita, oraEntrata)
                }
            }
            return risultato
        } finally {
            web.close()
        }
    }

    /** Lezioni del docente nell'intervallo (yyyyMMdd), risposta grezza. */
    fun lezioniGrezze(da: String, a: String): Pair<Int, String> {
        assicuraLogin()
        val r = http("GET", "$BASE/teachers/$id/lessons/mine/$da/$a")
        return r.codice to r.testo
    }

    /** Identificativo del docente collegato (serve a riconoscere i propri voti tra quelli di altri insegnanti). */
    fun idDocente(): String { assicuraLogin(); return id }

    /** Risposta di evaluations/components della prima materia del docente (livelli di voto, periodi, componenti), o null. */
    fun componentiPrimaMia(tipo: String, classeId: String): String? {
        assicuraLogin()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        if (m.codice != 200) return null
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return null }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return null
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (!o.optBoolean("isMine", false)) continue
            val sid = o.opt("subjectId")?.toString() ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/components/$t/$classeId/$sid")
            if (r.codice == 200) return r.testo
        }
        return null
    }

    /**
     * Scrive un voto come l'app ufficiale ClasseViva Docenti (PUT grades2 con GradeCreationConfigurationRequest).
     * Un voto non ha evtId nella richiesta: la "casella" è data + componentPos + displayPos + periodPos + studente.
     * Per spostare un voto (altra data o altro tipo) si passa la casella nuova: classeviva crea un voto nuovo
     * e quello vecchio va poi cancellato con [cancellaVoto]. L'app ufficiale non ha nessun campo per "fa media".
     */
    fun salvaVoto(grezzo: String, valore: String, decimale: Double?, data: String, nota: String,
                  componentPos: Int? = null, displayPos: Int? = null): Pair<Int, String> {
        val o = ClasseVivaPayloadValidation.voto(grezzo)
        assicuraLogin()
        val scritta = valore.trim().ifEmpty { o.optString("displayValue") }
        // decimalValue è obbligatorio e numerico (null → HTTP 412)
        val numero = decimale?.takeIf { it.isFinite() && it >= 0.0 }
            ?: o.opt("decimalValue")?.takeIf { it != JSONObject.NULL }?.toString()?.toDoubleOrNull()
            ?: scritta.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        val voto = JSONObject()
            .put("evtCode", codiceVoto(o, componentPos))
            .put("studentId", o.opt("studentId"))
            .put("decimalValue", numero)
            .put("displayValue", scritta)
        val richiesta = JSONObject()
            .put("evtDate", data.trim().ifEmpty { o.optString("evtDate") })
            .put("classType", o.optString("classType"))
            .put("classId", o.opt("classId") ?: JSONObject.NULL)
            .put("subjectId", o.opt("subjectId") ?: JSONObject.NULL)
            .put("displayPos", displayPos ?: o.optInt("displayPos"))
            .put("componentPos", componentPos ?: o.optInt("componentPos"))
            .put("periodPos", o.optInt("periodPos", 1))
            .put("notesForFamily", nota)
            .put("internalNotes", o.opt("internalNotes")?.takeIf { it != JSONObject.NULL } ?: "")
            .put("skillsOnly", false)
            .put("grades", JSONArray().put(voto))
        val r = http("PUT", "$BASE/teachers/$id/evaluations/grades2", richiesta)
        return r.codice to r.testo
    }

    /**
     * Modifica una mia firma (lezione): rimanda l'oggetto della lezione con il nuovo argomento, come fa l'app ufficiale
     * (PUT su lessons/sigs). Le presenze degli alunni non fanno parte della firma e vengono tolte. Da verificare sui dati veri.
     */
    /** Un tipo di lezione ammesso (Lezione, Supplenza, Compresenza...): [codice] come lo vuole classeviva, [nome] da mostrare. */
    data class TipoLezione(val codice: String, val nome: String)

    /**
     * Tipi di lezione per la firma, come nella tendina dell'app ufficiale (_st/lessontypes e _st/sctelessontypes).
     * Risposta: {"lessontypes":[{"id":1,"usrCode":"Lezione"},...]} e {"sctelessontypes":[...]} (PCTO/FSL sicurezza, orientamento). [grezzo] serve da copiare.
     */
    fun tipiLezione(): Pair<List<TipoLezione>, String> {
        assicuraLogin()
        fun JSONObject.t(vararg ks: String): String {
            for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
            return ""
        }
        val tipi = LinkedHashMap<String, TipoLezione>()
        val grezzo = StringBuilder()
        for (percorso in listOf("lessontypes", "sctelessontypes")) {
            val r = http("GET", "$BASE/teachers/$id/_st/$percorso")
            grezzo.append("$percorso: HTTP ${r.codice}\n${r.testo}\n\n")
            if (r.codice != 200) continue
            val radice = try { org.json.JSONTokener(r.testo).nextValue() } catch (e: Exception) { continue }
            val a = trovaArrayGenerico(radice, listOf("lessonTypes", "lessontypes", "sctelessontypes", "items", "data")) ?: continue
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                val tipo = if (o == null) a.optString(i).takeIf { it.isNotBlank() }?.let { TipoLezione(it, it) } else {
                    // forma vera: {"id": 1, "usrCode": "Lezione"}
                    val nome = o.t("usrCode", "lessonTypeDesc", "desc", "description", "lessonType", "name", "code")
                    val codice = o.t("id", "lessonTypeId", "lessonTypeCode", "code").ifEmpty { nome }
                    nome.takeIf { it.isNotEmpty() }?.let { TipoLezione(codice, it) }
                }
                if (tipo != null && tipo.nome !in tipi) tipi[tipo.nome] = tipo
            }
        }
        return tipi.values.toList() to grezzo.toString()
    }

    fun salvaFirma(grezzo: String, argomento: String, tipo: TipoLezione? = null): Pair<Int, String> {
        val o = ClasseVivaPayloadValidation.firma(grezzo)
        assicuraLogin()
        o.remove("studentStatus")
        o.put("lessonArg", argomento)
        // classeviva risponde 412 "missing evtNumH": è il numero di ore della firma, che nella lezione letta si chiama evtDuration
        if (!o.has("evtNumH")) o.put("evtNumH", o.optInt("evtDuration", 1))
        if (tipo != null) {
            // il nome va in lessonType; il codice nei campi che la lezione ha già (non si sa ancora quale usa classeviva)
            o.put("lessonType", tipo.nome)
            for (k in listOf("lessonTypeCode", "lessonTypeId", "lessonTypeDesc")) if (o.has(k)) o.put(k, if (k == "lessonTypeDesc") tipo.nome else tipo.codice)
        }
        val r = http("PUT", "$BASE/teachers/$id/lessons/sigs", o)
        return r.codice to r.testo
    }

    /**
     * Firma rapida: una nuova firma di un'ora con tipologia "Lezione" e argomento vuoto, costruita da zero.
     * Classe: [tipo] (C classe, G gruppo) e [classeId] da classes/mine. Materia: il subjectId di subjects/list il cui
     * nome è [materia], tra le mie. [data] è yyyyMMdd, [ora] la posizione dell'ora nel giorno.
     * I campi sono quelli delle firme vere lette da lessons/mine, più evtNumH che classeviva chiede (412 "missing evtNumH").
     * In [log] finiscono le risposte e la richiesta mandata (mai password o token), da copiare se qualcosa va storto.
     */
    fun firmaRapida(tipo: String, classeId: String, materia: String, data: String, ora: Int, log: StringBuilder = StringBuilder()): Pair<Int, String> {
        assicuraLogin()
        val m = http("GET", "$BASE/teachers/$id/subjects/list/${tipo.ifEmpty { "ordinary" }}/$classeId")
        log.append("GET subjects/list/$tipo/$classeId -> HTTP ${m.codice}\n${m.testo.take(2000)}\n\n")
        if (m.codice != 200) return m.codice to "materie non disponibili: ${m.testo.take(200)}"
        val radice = runCatching { org.json.JSONTokener(m.testo).nextValue() }.getOrNull() ?: return 500 to "materie non leggibili"
        val elenco = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return 404 to "nessuna materia nella classe"
        val voci = (0 until elenco.length()).mapNotNull { elenco.optJSONObject(it) }
        val ceMia = voci.any { it.optBoolean("isMine", false) }
        val mie = voci.filter { !ceMia || it.optBoolean("isMine", false) }
        fun normalizza(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(java.util.Locale.ROOT)
        val voce = mie.firstOrNull { normalizza(it.optString("subjectDesc")) == normalizza(materia) }
            ?: mie.singleOrNull() ?: return 404 to "materia \"$materia\" non trovata tra le tue"
        val sid = listOf("subjectId", "id", "subject_id").firstNotNullOfOrNull { k -> voce.opt(k)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray } }
            ?: return 404 to "materia senza codice"
        val tipi = runCatching { tipiLezione().first }.getOrDefault(emptyList())
        log.append("tipi di lezione: ${tipi.joinToString { "${it.codice}=${it.nome}" }.ifEmpty { "non letti" }}\n\n")
        val lezione = tipi.firstOrNull { it.nome.equals("Lezione", ignoreCase = true) } ?: TipoLezione("1", "Lezione")
        // come in lessons/mine: id numerici quando lo sono, data 2026-10-21
        fun numero(v: Any) = v.toString().toLongOrNull() ?: v
        val o = JSONObject()
            // evtCode LSF0 = firma di lezione, come in tutte le firme lette (412 "missing evtCode" se manca)
            .put("evtCode", "LSF0")
            .put("evtDate", "${data.take(4)}-${data.substring(4, 6)}-${data.substring(6, 8)}")
            .put("evtHPos", ora)
            .put("evtDuration", 1)
            .put("evtNumH", 1)
            .put("authorId", numero(id))
            .put("classType", tipo)
            .put("classId", numero(classeId))
            .put("subjectId", numero(sid))
            .put("subjectCode", voce.optString("subjectCode"))
            .put("subjectDesc", voce.optString("subjectDesc"))
            .put("lessonType", lezione.nome)
            .put("lessonTypeId", numero(lezione.codice))
            .put("lessonArg", "")
            .put("lessonPrivNote", "")
            // sigStamp = secondi unix della firma seguiti dall'id docente, come nelle firme vere
            // (es. "17931711813236598" = 1793171181, cioè 28/10/2026 07:06:21 UTC, + docente 3236598); 412 "missing sigStamp" se manca
            .put("sigStamp", "${System.currentTimeMillis() / 1000}$id")
        // Prova in ordine le varianti più probabili e si ferma alla prima accettata (2xx). Un rifiuto (4xx) non scrive
        // niente. Ogni tentativo finisce nel log, così si vede quale forma ClasseViva accetta.
        // Dall'APK ufficiale: la POST di una firma nuova vuole anche supplyType. Il valore per una lezione senza supplenza
        // non è noto: si prova in ordine, solo valori "nessuna supplenza". Un 4xx non scrive.
        // Dal log del 29/10: "noSupply", "NOSUPPLY" e "N" danno 404 "invalid supplyType", "0" dà 412 "missing supplyType".
        // Quindi si provano il vuoto e null, e altri codici di "nessuna supplenza".
        val varianti = listOf<Any>("", JSONObject.NULL, "NONE", "NO").map { tipo ->
            "POST supplyType ${if (tipo == JSONObject.NULL) "null" else "\"$tipo\""}" to { body: JSONObject -> http("POST", "$BASE/teachers/$id/lessons/sigs", body.apply { remove("sigStamp"); put("supplyType", tipo) }) }
        }
        var ultima: Risposta? = null
        for ((nome, invia) in varianti) {
            val corpo = JSONObject(o.toString())
            val r = invia(corpo)
            log.append("tentativo $nome\n${corpo.toString(2)}\n")
            log.append("risposta HTTP ${r.codice}\n${r.testo.take(3000)}\n\n")
            if (r.codice in 200..299) return r.codice to r.testo
            ultima = r
        }
        return (ultima?.codice ?: 0) to (ultima?.testo ?: "")
    }

    /** Cancella un voto: la risposta è l'oggetto del voto così come l'ha dato classeviva. */
    /**
     * evtCode da mandare come l'app ufficiale: non quello letto dal voto (un voto "nessun voto" ne ha un altro e
     * la cancellazione dava HTTP 413) ma GRV0 se il voto ha un componentPos, altrimenti GRT1 (voto di recupero).
     */
    private fun codiceVoto(o: JSONObject, componentPos: Int? = null): String {
        val pos = componentPos ?: o.opt("componentPos")?.takeIf { it != JSONObject.NULL }
        return if (pos != null) "GRV0" else "GRT1"
    }

    fun cancellaVoto(grezzo: String): Pair<Int, String> {
        val o = ClasseVivaPayloadValidation.voto(grezzo)
        assicuraLogin()
        // come l'app ufficiale (GradeDeletionConfigurationRequest): la casella del voto e, per ogni studente, evtCode e studentId
        val richiesta = JSONObject()
            .put("evtDate", o.optString("evtDate"))
            .put("classType", o.optString("classType"))
            .put("classId", o.opt("classId") ?: JSONObject.NULL)
            .put("subjectId", o.opt("subjectId") ?: JSONObject.NULL)
            .put("displayPos", o.optInt("displayPos"))
            .put("componentPos", o.optInt("componentPos"))
            .put("periodPos", o.optInt("periodPos", 1))
            .put("notesForFamily", "")
            .put("internalNotes", "")
            .put("skillsOnly", false)
            .put("grades", JSONArray().put(JSONObject().put("evtCode", codiceVoto(o)).put("studentId", o.opt("studentId"))))
        val r = http("DELETE", "$BASE/teachers/$id/evaluations/grades2", richiesta)
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

    /**
     * Email registrata sul profilo ClasseViva. Non c'è nella REST API: si entra nel portale web
     * (sessione a cookie, non documentato) e si legge data.emailAddress da OtpApi.php?a=recStatus.
     * Se Spaggiari cambia qualcosa torna "" e il destinatario si scrive a mano.
     */
    fun emailRegistrata(): String = runCatching {
        sessioneWeb().use { web ->
            val (codice, testo) = web.chiama("https://web.spaggiari.eu/auth/app/default/OtpApi.php?a=recStatus")
            if (codice !in 200..299) return@runCatching ""
            val j = JSONObject(testo)
            (j.optJSONObject("data")?.optString("emailAddress") ?: j.optString("emailAddress")).trim().takeIf { it.contains('@') }.orEmpty()
        }
    }.getOrDefault("")

    /**
     * Entra nel portale web di ClasseViva dentro un browser invisibile (vedi [WebNascosto]): fatto a mano
     * con HttpURLConnection la protezione antibot risponde HTTP 403. Va chiuso dopo l'uso.
     */
    private fun sessioneWeb(): WebNascosto {
        val ctx = appContext ?: throw ClasseVivaException("accesso a classeviva web non disponibile qui")
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val web = WebNascosto(ctx)
        try {
            web.apri()
            val (codice, testo) = web.chiama("https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php?a=aLoginPwd",
                "cid=&uid=${enc(utente)}&pwd=${enc(password)}&pin=&target=")
            val j = runCatching { JSONObject(testo) }.getOrNull()
            val auth = j?.optJSONObject("data")?.optJSONObject("auth")
            val errori = j?.optJSONArray("error")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
            if (codice !in 200..299 || j == null || (auth != null && !auth.optBoolean("loggedIn", false)) || errori.isNotBlank()) {
                val motivo = errori.ifBlank { auth?.optJSONArray("errors")?.toString() ?: testo.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200) }
                throw ClasseVivaException("accesso a classeviva web non riuscito (HTTP $codice): $motivo")
            }
            return web
        } catch (e: Exception) {
            web.close()
            throw e
        }
    }

    /**
     * Trova il voto nella pagina del registro web (regvoti.php): ogni casella è un elemento id="button_<studente><casella>"
     * con attributi studente_id, evento_id, mydata, voto (vedi loadPopup in regvoti.js). Restituisce (evento_id, casella tipo "S1_3_1").
     * Prima per evento_id uguale all'evtId delle API, poi per studente + data + voto mostrato. Se non lo trova non si manda niente.
     */
    /** Gruppo (classType G) oppure classId non numerico come "5EA_AUA": i voti di grades2 non portano classType. */
    private fun eGruppo(o: JSONObject): Boolean {
        val tipo = o.optString("classType")
        return tipo.equals("G", ignoreCase = true) || tipo.startsWith("group", ignoreCase = true) ||
            o.optString("classId").let { it.isNotEmpty() && !it.all { c -> c.isDigit() } }
    }

    private fun cellaWeb(o: JSONObject, web: WebNascosto): Pair<String, String> {
        fun t(k: String) = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val gruppo = eGruppo(o)
        // come il registro web: per un gruppo (es. 5EA_AUA) classe_id e materia_id restano vuoti
        val indirizzo = "https://web.spaggiari.eu/cvv/app/default/regvoti.php?classe_id=${if (gruppo) "" else enc(t("classId"))}" +
            "&gruppo_id=${if (gruppo) enc(t("webGroupCode").ifEmpty { t("classId") }) else ""}&materia_id=${if (gruppo) "" else enc(t("subjectId"))}"
        val (codice, html) = web.chiama(indirizzo)
        if (codice !in 200..299) throw ClasseVivaException("registro web non leggibile (HTTP $codice)")
        data class Cella(val evento: String, val studente: String, val casella: String, val data: String, val voto: String)
        fun attr(tag: String, nome: String) = Regex("""\b$nome\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1).orEmpty()
        val celle = Regex("""<[^>]*\bid\s*=\s*["']button_([^"']+)["'][^>]*>""").findAll(html).map { m ->
            val tag = m.value
            val studente = attr(tag, "studente_id")
            Cella(attr(tag, "evento_id"), studente, m.groupValues[1].removePrefix(studente), attr(tag, "mydata"), attr(tag, "voto"))
        }.filter { it.studente.isNotEmpty() && it.casella.isNotEmpty() }.toList()
        val studente = t("studentId")
        fun cifre(d: String) = Regex("\\d+").findAll(d).map { it.value.trimStart('0') }.sorted().toList()
        val trovata = celle.firstOrNull { it.evento.isNotEmpty() && it.evento == t("evtId") && it.studente == studente }
            ?: celle.filter { it.studente == studente && it.evento.isNotEmpty() && it.evento != "0" &&
                    cifre(it.data) == cifre(t("evtDate").take(10)) && it.voto.trim() == t("displayValue").trim() }
                .singleOrNull()
            ?: throw ClasseVivaException("non trovo il voto nella pagina del registro web (${celle.size} caselle lette): non ho mandato niente\n" +
                "classe=${t("classId")} gruppo=${t("webGroupCode")} materia=${t("subjectId")} tipo=${t("classType")} studente=$studente evento=${t("evtId")}\n" +
                "pagina: ${Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()} (${html.length} caratteri, " +
                "${Regex("button_").findAll(html).count()} button_) " + html.replace(Regex("(?s)<script.*?</script>|<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(200))
        return trovata.evento to trovata.casella
    }

    /**
     * Mette o toglie un voto dalla media. Le API REST (e l'app ufficiale) non lo permettono: si fa come ClasseViva web
     * (regvoti.js, POST cvv/app/default/votiio.php):
     * - fuori media = "da recuperare ed escluso dalla media": ope=AJRECUPERA con evento_id, studente_id, descrizione_1; risponde "OK";
     * - di nuovo in media = si riscrive il voto con ope=AJINS (no_voto=0, che invece vorrebbe dire "senza valutazione").
     * [casella] è "S1_3" (periodo_componente, dal componentId "Q1:S1_3:PRA:0"); descrizione_1 aggiunge la posizione: "S1_3_1".
     */
    fun mediaVotoWeb(grezzo: String, faMedia: Boolean, casella: String?, decimale: Double? = null): Pair<Int, String> {
        val o = ClasseVivaPayloadValidation.voto(grezzo)
        val web = try { sessioneWeb() } catch (e: Exception) { return 0 to (e.message ?: e.toString()) }
        web.use { return mediaVotoWeb(o, web, faMedia, decimale) }
    }

    /** Salva il voto come "senza voto" (chip blu, decimalValue null) tramite AJINS con no_voto=1. */
    fun senzaVotoWeb(grezzo: String, casella: String?, decimale: Double? = null): Pair<Int, String> {
        val o = ClasseVivaPayloadValidation.voto(grezzo)
        val web = try { sessioneWeb() } catch (e: Exception) { return 0 to (e.message ?: e.toString()) }
        web.use { return mediaVotoWeb(o, web, faMedia = true, decimale = decimale, senzaVoto = true) }
    }

    private fun mediaVotoWeb(o: JSONObject, web: WebNascosto, faMedia: Boolean, decimale: Double?, senzaVoto: Boolean = false): Pair<Int, String> {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        fun t(k: String) = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        val display = t("displayValue")
        // i voti fuori media hanno decimalValue vuoto: si usa quello del livello scelto
        val valore = t("decimalValue").ifEmpty { decimale?.toString() ?: display.replace(',', '.').filter { it.isDigit() || it == '.' } }
        val data = t("evtDate").take(10).split("-").let { if (it.size == 3) "${it[2]}-${it[1]}-${it[0]}" else t("evtDate") }
        val gruppo = eGruppo(o)
        // id dell'evento e casella si prendono dalla pagina del registro web: con valori sbagliati
        // AJRECUPERA non tocca il voto e crea un "voto di recupero" vuoto (blu, "-") in un'altra casella
        val cella = try { cellaWeb(o, web) } catch (e: Exception) { return 0 to (e.message ?: e.toString()) }
        val descrizione = cella.second
        val campi = if (!faMedia && !senzaVoto) linkedMapOf(
            "ope" to "AJRECUPERA",
            "evento_id" to cella.first,
            "studente_id" to t("studentId"),
            "descrizione_1" to descrizione
        ) else linkedMapOf(
            "ope" to "AJINS",
            "studente_id" to t("studentId"),
            "classe" to if (gruppo) "" else t("classId"),
            "gruppo" to if (gruppo) t("webGroupCode").ifEmpty { t("classId") } else "",
            "valore" to if (senzaVoto) "" else valore,
            "evento_codice" to "VG",
            "valore_display" to display,
            "descrizione_1" to descrizione,
            "data" to data,
            "materia_id" to t("subjectId"),
            "materia_desc" to t("subjectDesc"),
            "causale_codice" to "VA",
            "causale_desc" to "<ztx>Altro</ztx>",
            "evento_id" to cella.first,
            "nota_1" to t("notesForFamily"),
            "nota_2" to t("internalNotes"),
            "no_voto" to if (senzaVoto) "1" else "0",
            "id_uda" to ""
        )
        val (codice, testo) = web.chiama("https://web.spaggiari.eu/cvv/app/default/votiio.php",
            campi.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }, "https://web.spaggiari.eu/cvv/app/default/regvoti.php")
        // AJRECUPERA risponde "OK" oppure "periodo_bloccato"; AJINS {"status":"OK"}
        val ok = testo.trim() == "OK" || runCatching { JSONObject(testo).optString("status") == "OK" }.getOrDefault(false)
        return (if (codice in 200..299 && !ok) 422 else codice) to testo
    }

    class CircolareRitirata : Exception("circolare ritirata dalla scuola")

    private class Sessione(val utente: String, val password: String, val token: String, val ident: String, val id: String, val scade: Long)

    companion object {
        const val BASE = "https://web.spaggiari.eu/rest/v1"
        const val RITIRATA = "no more active"
        // Il token REST si riusa per 2 minuti (basta per aprire una classe e cambiare giorno), poi o su HTTP 401 nuovo accesso.
        private const val DURATA_TOKEN = 2 * 60 * 1000L
        private val blocco = Any()
        @Volatile private var condiviso: Sessione? = null
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

/**
 * Stato di presenza per id studente: -1 nessuna lezione/nessun dato, 0 presente, 1 ritardo/uscita, 2 assente.
 * L'app ufficiale usa gli stati present, absent, late, shortLate, leaving, noLesson, presentInternship, presentEducationalTrip.
 */
data class StatoGiornaliero(
    val valore: Int,
    val ritardoBreve: String? = null,
    val isRitardoBreve: Boolean = false,
    val uscita: String? = null,
    val isUscita: Boolean = false,
    /** Ritardo normale ("Ritardo entra", ABR0) invece del ritardo breve (ABR1). */
    val isRitardoLungo: Boolean = false,
    /** Ora di lezione di entrata del ritardo normale, es. "2". */
    val oraEntrata: String? = null
)

/**
 * Ora (hh:mm) dell'evento con codice che inizia per [prefisso], cercata solo nei campi
 * dello stesso oggetto JSON che contiene il codice (così ritardo e uscita non si confondono).
 */
private fun oraEvento(v: Any?, prefisso: String, livello: Int = 0): String? {
    if (livello > 8) return null
    val orario = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
    when (v) {
        is JSONObject -> {
            val primitivi = v.keys().asSequence().mapNotNull { k ->
                val x = v.opt(k)
                if (x is String || x is Number) k.lowercase() to x.toString() else null
            }.toList()
            if (primitivi.any { it.second.uppercase().let { c -> c.length == 4 && c.startsWith(prefisso) } }) {
                primitivi.firstNotNullOfOrNull { (_, testo) -> orario.find(testo)?.value }?.let { return it }
            }
            for (k in v.keys()) oraEvento(v.opt(k), prefisso, livello + 1)?.let { return it }
        }
        is JSONArray -> for (i in 0 until v.length()) oraEvento(v.opt(i), prefisso, livello + 1)?.let { return it }
    }
    return null
}

/**
 * Stato di presenza per id studente. Per ABR1 conserva anche l'ora inserita dal docente,
 * se presente nella risposta di status2.
 */
fun leggiStato(grezzo: String): Map<String, StatoGiornaliero> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
    val a = trovaArrayGenerico(radice, listOf("students", "items", "data", "status", "pupils")) ?: return emptyMap()

    fun raccogli(v: Any?, out: MutableList<Pair<String, String>>, livello: Int = 0) {
        if (livello > 8) return
        when (v) {
            is JSONObject -> for (k in v.keys()) {
                val valore = v.opt(k)
                if (valore == null || valore == JSONObject.NULL) continue
                if (valore is String || valore is Number || valore is Boolean) out += k.lowercase() to valore.toString()
                else raccogli(valore, out, livello + 1)
            }
            is JSONArray -> for (i in 0 until v.length()) raccogli(v.opt(i), out, livello + 1)
        }
    }

    val m = HashMap<String, StatoGiornaliero>()
    val senzaCodice = ArrayList<String>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val id = idDi(o)
        if (id.isEmpty()) continue
        val campi = mutableListOf<Pair<String, String>>().also { raccogli(o, it) }
        val codici = campi.map { it.second.uppercase() }.filter { it.length == 4 && it[0].isLetter() }
        if (codici.isEmpty()) { senzaCodice.add(id); continue }
        val valore = when {
            codici.any { it.startsWith("ABA") || it.startsWith("HAB") } -> 2
            codici.any { it.startsWith("ABR") || it.startsWith("ABU") || it.startsWith("AB") } -> 1
            codici.any { it.startsWith("AT") || it.startsWith("HAT") || it.startsWith("HPR") } -> 0
            else -> -1
        }
        val isUscita = codici.any { it.startsWith("ABU") }
        // ABR0 = ritardo normale ("Ritardo entra" alla tale ora), ABR1 = ritardo breve con orario.
        val isRitardoLungo = "ABR0" in codici && "ABR1" !in codici
        val ritardoBreve = when {
            codici.none { it.startsWith("ABR") } -> null
            isRitardoLungo -> null
            oraEvento(o, "ABR") != null -> oraEvento(o, "ABR")
            isUscita -> null // con anche l'uscita, un orario qualsiasi potrebbe essere quello dell'uscita
            else -> {
                val orario = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
                val campiOrario = campi.filter { (chiave, testo) ->
                    chiave.contains("time") || chiave.contains("ora") || chiave.contains("hour") ||
                        chiave.contains("late") || chiave.contains("ritard") || orario.matches(testo.trim())
                }
                campiOrario.firstNotNullOfOrNull { (_, testo) -> orario.find(testo)?.value }
            }
        }
        // Uscita anticipata (ABU0): l'ora si cerca accanto al codice; se manca la legge il registro web.
        val uscita = if (isUscita) oraEvento(o, "ABU") else null
        val nuovo = StatoGiornaliero(valore, ritardoBreve, codici.any { it.startsWith("ABR") }, uscita, isUscita, isRitardoLungo)
        val precedente = m[id]
        m[id] = when {
            precedente == null -> nuovo
            nuovo.valore == 2 -> nuovo
            precedente.valore == 2 -> precedente
            nuovo.ritardoBreve != null || nuovo.uscita != null -> nuovo.copy(
                ritardoBreve = nuovo.ritardoBreve ?: precedente.ritardoBreve,
                isRitardoBreve = nuovo.isRitardoBreve || precedente.isRitardoBreve,
                uscita = nuovo.uscita ?: precedente.uscita,
                isUscita = nuovo.isUscita || precedente.isUscita,
                isRitardoLungo = nuovo.isRitardoLungo || precedente.isRitardoLungo
            )
            else -> precedente.copy(
                isRitardoBreve = precedente.isRitardoBreve || nuovo.isRitardoBreve,
                isUscita = precedente.isUscita || nuovo.isUscita,
                isRitardoLungo = precedente.isRitardoLungo || nuovo.isRitardoLungo
            )
        }
    }
    val giornoDiLezione = m.isNotEmpty()
    for (id in senzaCodice) m[id] = StatoGiornaliero(if (giornoDiLezione) 0 else -1)
    return m
}

/** Componenti dei voti: tipo della pagina -> nome e posizione come li dà classeviva. */

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
        // il nome può arrivare come "4ETA ELETTRONICA": la sigla è la parte prima del primo spazio, il resto è descrizione
        val nomeIntero = nome
        nome = siglaClasse(nome)
        val descrizione = t("classDesc", "classroomDescription", "description", "desc", "classDescription")
            .ifEmpty { if (nomeIntero != nome) nomeIntero else "" }
        var codice = t("classCode", "classroomCode", "code", "classCodeDescription", "schoolClassCode")
        if (codice == nome) codice = ""
        if (nome.isEmpty() && descrizione.isEmpty()) continue
        val id = t("classId", "classroomId", "id").ifEmpty { nome }
        val tipo = t("classType", "classroomType", "type")
        visti.getOrPut("$tipo|$id|$nome") { Classe("$tipo|$id|$nome", nome.uppercase(), descrizione.uppercase(), codice.uppercase()) }
    }
    return visti.values.sortedWith(compareBy({ it.nome.firstOrNull()?.digitToIntOrNull() ?: 9 }, { it.nome }))
}

/** Una classe con una materia che il docente vi insegna (una riga di classes/mine). */
data class Insegnamento(val classe: String, val materia: String) {
    val chiave get() = "$classe|$materia"
}

/**
 * Coppie classe/materia dalla risposta di "le mie classi": ClasseViva ripete la classe una volta per materia.
 * I nomi dei campi della materia non sono documentati: si provano i più probabili, anche dentro "subject".
 */
fun leggiInsegnamenti(grezzo: String): List<Insegnamento> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    val array = trovaArrayGenerico(radice, listOf("classes", "classrooms", "items", "data", "classi")) ?: return emptyList()
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
    val campiMateria = arrayOf("subjectDesc", "subjectDescription", "subjectName", "subjectCode", "materia", "description", "desc", "name")
    fun materieDi(o: JSONObject): List<String> {
        val dirette = o.testo("subjectDesc", "subjectDescription", "subjectName", "subject", "materia", "subjectCode")
        if (dirette.isNotEmpty()) return listOf(dirette)
        o.optJSONObject("subject")?.let { s -> s.testo(*campiMateria).takeIf { it.isNotEmpty() }?.let { return listOf(it) } }
        val elenco = o.optJSONArray("subjects") ?: return emptyList()
        return (0 until elenco.length()).mapNotNull { i ->
            when (val v = elenco.opt(i)) {
                is JSONObject -> v.testo(*campiMateria).ifEmpty { null }
                is String -> v.trim().ifEmpty { null }
                else -> null
            }
        }
    }
    val visti = LinkedHashSet<Insegnamento>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val c = o.optJSONObject("classroom") ?: o.optJSONObject("class") ?: o
        fun t(vararg k: String) = c.testo(*k).ifEmpty { o.testo(*k) }
        var nome = t("className", "classroomName", "name", "classCode")
        if (nome.isEmpty()) nome = t("classLevel", "classRealLevel", "level") + t("classAddr", "classAddress", "address") + t("classSection", "section")
        nome = siglaClasse(nome)
        if (nome.isEmpty()) continue
        val materie = materieDi(o).ifEmpty { if (c !== o) materieDi(c) else emptyList() }
        if (materie.isEmpty()) visti += Insegnamento(nome.uppercase(), "")
        else materie.forEach { visti += Insegnamento(nome.uppercase(), it) }
    }
    return visti.sortedWith(compareBy({ it.classe.firstOrNull()?.digitToIntOrNull() ?: 9 }, { it.classe }, { it.materia }))
}

/** Solo la sigla della classe: il testo prima del primo spazio ("4ETA ELETTRONICA" -> "4ETA"). */
fun siglaClasse(nome: String): String = nome.trim().substringBefore(' ')

/** Un voto di uno studente: tipo di prova (orale, scritto/grafico, test…), valore mostrato, data e annotazioni per la famiglia. */
data class Voto(val chiave: String, val studente: String, val tipo: String, val valore: String, val numero: Double?, val data: String, val nota: String, val grezzo: String = "", val materia: String = "", val autore: String = "", val faMedia: Boolean = true, val docente: String = "")

/** Voti per id studente dalla risposta di ClasseViva (campi dedotti, lettura tollerante, sia voti annidati nello studente sia elenco piatto). */
fun leggiVoti(grezzo: String): Map<String, List<Voto>> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
    val out = LinkedHashMap<String, MutableList<Voto>>()
    fun JSONObject.t(vararg ks: String): String {
        for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
        return ""
    }
    var materiaCorrente = ""
    fun cammina(v: Any?, idStudente: String, livello: Int) {
        if (livello > 6) return
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), idStudente, livello + 1)
            is JSONObject -> {
                // "senza voto" salvato dal registro web può arrivare senza displayValue né decimalValue: si mostra come "-"
                val senzaValore = v.has("evtId") && v.has("studentId")
                val valore = v.t("displayValue", "decimalValue", "grade", "value", "gradeValue").ifEmpty { if (senzaValore) "-" else "" }
                val èVoto = valore.isNotEmpty() && v.opt("grades") == null
                // un voto ha un proprio "id": per lo studente valgono solo i campi esplicitamente suoi
                val propri = if (èVoto) v.t("studentId", "student_id", "pupilId") else idDi(v)
                if (èVoto) {
                    val sid = propri.ifEmpty { idStudente }
                    // voti annullati (canceled) non si mostrano
                    if (sid.isNotEmpty() && v.opt("canceled") != true) {
                        val tipo = v.t("componentDesc", "gradeTypeDesc", "typeDesc", "gradeType", "type", "kind", "component").ifEmpty { "voto" }
                        val data = v.t("evtDate", "date", "gradeDate", "day")
                        val num = v.t("decimalValue").toDoubleOrNull() ?: valore.replace(',', '.').toDoubleOrNull()
                        val id = v.t("evtId", "gradeId", "id").ifEmpty { "${out[sid]?.size ?: 0}" }
                        out.getOrPut(sid) { ArrayList() }.add(Voto("$sid|$id|$data", sid, tipo, valore, num, data, v.t("notesForFamily", "note", "notes", "annotations"), v.toString(), materiaCorrente.ifEmpty { v.t("subjectDesc") }, v.t("authorId", "teacherId", "teacher_id"), // non fa media: peso 0 oppure valore decimale assente (nei dati di classeviva sono i voti "blu", per esempio superati da un recupero)
            (v.t("weightFactor").toDoubleOrNull()?.let { it != 0.0 } ?: true) && v.opt("decimalValue") != JSONObject.NULL, v.t("teacherName", "authorName")))
                    }
                } else {
                    val sid = propri.ifEmpty { idStudente }
                    for (k in v.keys()) cammina(v.opt(k), sid, livello + 1)
                }
            }
        }
    }
    if (radice is JSONArray && (0 until radice.length()).all { radice.optJSONObject(it)?.has("data") == true && radice.optJSONObject(it)?.has("subjectId") == true }) {
        for (i in 0 until radice.length()) {
            val w = radice.getJSONObject(i)
            materiaCorrente = w.optString("subjectDesc")
            cammina(w.opt("data"), "", 0)
        }
    } else cammina(radice, "", 0)
    return out
}

