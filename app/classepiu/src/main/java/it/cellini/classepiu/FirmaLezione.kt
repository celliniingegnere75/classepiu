package it.cellini.classepiu

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Logica del pulsante «firma la lezione» del libro del docente, senza dipendenze da Android (si prova con i test):
 * registro su cui firmare, ore proposte dall'orario, controllo dell'ora già firmata e testo dell'esito.
 * La firma vera passa sempre da [ClasseViva.firmaRapida].
 */
object FirmaLezione {

    /** Una casella dell'orario del giorno scelto: [ora] 1…9, classe e materia come nelle tendine dell'orario. */
    data class OraOrario(val ora: Int, val classe: String, val materia: String)

    /** Registro di classes/mine su cui firmare: [tipo] C classe o G gruppo, [id], [nome] sigla (per i gruppi è il nomeClasse di firmaRapida). */
    data class Registro(val tipo: String, val id: String, val nome: String)

    /** Una firma possibile: ora e materia scritta come in subjects/list; [firmata] = materia della mia firma già presente in quell'ora. */
    data class Proposta(val ora: Int, val materia: String, val firmata: String? = null)

    /** Ore dell'orario per il registro: [proposte] con una mia materia verificata, [oreSenzaMateria] della classe ma con materia vuota o non mia. */
    data class Analisi(val proposte: List<Proposta>, val oreSenzaMateria: List<Int>)

    /** Esito da mostrare: [ok] solo con una risposta 2xx; [giaFirmata] se il server dice che l'ora è già firmata. */
    data class Esito(val ok: Boolean, val titolo: String, val dettaglio: String, val giaFirmata: Boolean = false)

    fun normalizza(s: String) = s.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    private fun sigla(s: String) = normalizza(s).substringBefore(' ')

    /**
     * Il registro delle mie classi (chiavi "tipo|id|nome" di leggiClassi) su cui firmare la scheda aperta.
     * Un gruppo aperto da "tutti i registri" ([gruppo], es. 5EA_AUA) firma sul gruppo se è mio; altrimenti vale la classe
     * con lo stesso id. null se la classe aperta non è tra le mie: allora non si firma.
     */
    fun registroDaFirmare(chiaviMie: List<String>, tipo: String, classeId: String, gruppo: String): Registro? {
        val mie = chiaviMie.map { it.split("|", limit = 3) }
            .filter { it.size == 3 && it[1].isNotBlank() }
            .map { Registro(it[0], it[1], it[2].trim()) }
        if (gruppo.isNotBlank()) mie.firstOrNull { sigla(it.nome) == sigla(gruppo) }?.let { return it }
        if (classeId.isBlank()) return null
        return mie.firstOrNull { it.id == classeId && it.tipo.equals(tipo, ignoreCase = true) }
            ?: mie.firstOrNull { it.id == classeId }
    }

    /**
     * Le ore dell'orario del giorno che sono del [registro] (stessa sigla) e la cui materia è tra le mie in quella classe
     * ([materieMie], da subjects/list): mai una materia presa a caso. Le ore della classe con materia vuota o non mia
     * finiscono in [Analisi.oreSenzaMateria], per proporle nella scelta a mano.
     */
    fun analizzaOrario(ore: List<OraOrario>, registro: Registro, materieMie: List<String>): Analisi {
        val mie = materieMie.filter { it.isNotBlank() }.associateBy { normalizza(it) }
        val dellaClasse = ore.filter { it.ora in 1..Orario.ORE && sigla(it.classe) == sigla(registro.nome) }.sortedBy { it.ora }
        val proposte = dellaClasse.mapNotNull { o -> mie[normalizza(o.materia)]?.let { Proposta(o.ora, it) } }.distinct()
        val senza = dellaClasse.filter { o -> proposte.none { it.ora == o.ora } }.map { it.ora }.distinct()
        return Analisi(proposte, senza)
    }

    /**
     * La mia firma già presente il giorno [data] (yyyyMMdd) nell'ora [ora], dalle lezioni del docente (lessons/mine):
     * restituisce la materia (o "lezione" se manca), null se l'ora è libera. Una firma di più ore copre le ore consecutive.
     * Il docente non può firmare due volte la stessa ora, nemmeno in classi diverse.
     */
    fun firmaEsistente(grezzo: String, mioId: String, data: String, ora: Int): String? {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return null
        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }
        fun cerca(v: Any?, livello: Int): String? {
            if (livello > 7) return null
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cerca(v.opt(i), livello + 1)?.let { return it }
                is JSONObject -> {
                    val dataFirma = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val inizio = testo(v, "evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    if (dataFirma.isNotEmpty() && inizio != null) {
                        val durata = (testo(v, "evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                        val autore = testo(v, "authorId", "teacherId", "teacher_id")
                        val mia = mioId.isBlank() || autore.isEmpty() || autore == mioId
                        if (dataFirma == data && ora in inizio until inizio + durata && mia) {
                            return testo(v, "subjectDesc", "subjectName", "subjectDescription", "subject")
                                .ifEmpty { v.optJSONObject("subject")?.let { testo(it, "subjectDesc", "description", "name") }.orEmpty() }
                                .ifEmpty { "lezione" }
                        }
                        return null
                    }
                    for (k in v.keys()) cerca(v.opt(k), livello + 1)?.let { return it }
                }
            }
            return null
        }
        return cerca(radice, 0)
    }

    /**
     * Firme già presenti da qualunque docente nella classe, limitate alle ore richieste.
     * Ritorna null se la risposta di lessons2/students non è leggibile.
     */
    fun firmeClasseNelleOre(grezzo: String, data: String, ora: Int, ore: Int): Map<Int, String>? {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return null
        if (radice !is JSONObject && radice !is JSONArray) return null

        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }

        val oreRichieste = ora until ora + ore.coerceAtLeast(1)
        val trovate = linkedMapOf<Int, String>()
        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val inizio = testo(v, "evtHPos", "hourPos", "evtHour").toDoubleOrNull()?.toInt()
                    val giorno = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    val firmata = testo(v, "subjectDesc", "subjectId", "authorId", "teacherId", "evtCode", "authorName").isNotEmpty()
                    if (inizio != null && inizio in 1..Orario.ORE && firmata && (giorno.isEmpty() || giorno == data)) {
                        val durata = (testo(v, "evtDuration", "evtNumH", "duration").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                        val docente = testo(v, "authorName", "teacherName", "teacherId", "authorId")
                            .ifEmpty { testo(v, "subjectDesc", "subjectName") }
                            .ifEmpty { "firma già presente" }
                        for (h in inizio until inizio + durata) if (h in oreRichieste) trovate.putIfAbsent(h, docente)
                    }
                    for (k in v.keys()) if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice, 0)
        return trovate
    }
    /**
     * L'ora subito dopo l'ultima firma del giorno [data] (yyyyMMdd) nel registro della classe (lessons2/students, firme di
     * tutti i docenti): 1 se non c'è ancora nessuna firma, null se l'ultima arriva già all'ultima ora o la risposta non si legge.
     * Una firma di più ore (evtDuration) conta fino alla sua ultima ora. Serve quando l'orario non indica la classe.
     */
    fun oraDopoUltimaFirma(grezzo: String, data: String): Int? {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return null
        if (radice !is JSONObject && radice !is JSONArray) return null
        fun testo(o: JSONObject, vararg chiavi: String): String {
            for (k in chiavi) {
                val v = o.opt(k)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "null") return s
                }
            }
            return ""
        }
        var ultima = 0
        fun cammina(v: Any?, livello: Int) {
            if (livello > 7) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val inizio = testo(v, "evtHPos", "hourPos", "evtHour").toDoubleOrNull()?.toInt()
                    val giorno = testo(v, "evtDate", "lessonDate", "date").take(10).replace("-", "")
                    // una firma vera ha almeno materia, docente o codice evento; le caselle vuote non contano
                    val firmata = testo(v, "subjectDesc", "subjectId", "authorId", "teacherId", "evtCode", "authorName").isNotEmpty()
                    if (inizio != null && inizio in 1..Orario.ORE && firmata && (giorno.isEmpty() || giorno == data)) {
                        val durata = (testo(v, "evtDuration", "evtNumH", "duration").toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                        ultima = maxOf(ultima, inizio + durata - 1)
                    }
                    for (k in v.keys()) if (k != "studentStatus") cammina(v.opt(k), livello + 1)
                }
            }
        }
        cammina(radice, 0)
        return (ultima + 1).takeIf { it <= Orario.ORE }
    }

    /** Ore consecutive della stessa materia nell'orario: una lezione di più ore da firmare con una sola firma. */
    data class Blocco(val ora: Int, val ore: Int, val materia: String) {
        val ultima get() = ora + ore - 1
    }

    /** Raggruppa le proposte dell'orario in blocchi di ore consecutive con la stessa materia (es. 1ª e 2ª ora → una lezione di 2 ore). */
    fun blocchi(proposte: List<Proposta>): List<Blocco> {
        val out = mutableListOf<Blocco>()
        for (p in proposte.sortedBy { it.ora }) {
            val ultimo = out.lastOrNull()
            if (ultimo != null && ultimo.ultima + 1 == p.ora && normalizza(ultimo.materia) == normalizza(p.materia)) out[out.size - 1] = ultimo.copy(ore = ultimo.ore + 1)
            else out += Blocco(p.ora, 1, p.materia)
        }
        return out
    }

    /** Le ore da [ora] per [ore] ore già firmate da me il giorno [data]: ora → materia della firma presente. */
    fun oreGiaFirmate(grezzo: String?, mioId: String, data: String, ora: Int, ore: Int): Map<Int, String> {
        if (grezzo == null) return emptyMap()
        return (ora until ora + ore.coerceAtLeast(1)).mapNotNull { o -> firmaEsistente(grezzo, mioId, data, o)?.let { o to it } }.toMap()
    }

    /** Un progetto di scuola e territorio (PCTO/FSL) del registro web: [id] è il valore di id_progetto_set. */
    data class Progetto(val id: String, val nome: String)

    /** Voci "tipo attività", progetti PCTO/FSL e se il registro li chiede ([progettiAttivi], campo set_enable del sito). */
    data class OpzioniFirma(val tipiAttivita: List<String>, val progetti: List<Progetto>, val progettiAttivi: Boolean)

    /** Testo di un'opzione HTML in chiaro: entità comuni e numeriche, spazi ripetuti tolti. */
    fun testoHtml(s: String): String = entita(s.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()

    /** Entità HTML comuni e numeriche in chiaro, senza toccare spazi e a capo (per il contenuto delle textarea). */
    private fun entita(s: String): String {
        val nomi = mapOf("nbsp" to " ", "bull" to "•", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
            "agrave" to "à", "egrave" to "è", "eacute" to "é", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù", "ordf" to "ª", "deg" to "°",
            "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "ndash" to "–", "mdash" to "—", "hellip" to "…")
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
            val k = m.groupValues[1]
            when {
                k.startsWith("#x") || k.startsWith("#X") -> k.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                k.startsWith("#") -> k.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> nomi[k.lowercase(Locale.ROOT)] ?: m.value
            }
        }
    }

    private fun opzioniSelect(html: String, id: String): List<Pair<String, String>> {
        val inizio = html.indexOf("id=\"$id\"").takeIf { it >= 0 } ?: return emptyList()
        val fine = html.indexOf("</select>", inizio).takeIf { it > 0 } ?: html.length
        return Regex("<option\\s+value=\"([^\"]*)\"[^>]*>(.*?)</option>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html.substring(inizio, fine)).map { testoHtml(it.groupValues[1]) to testoHtml(it.groupValues[2]) }.toList()
    }

    /** Dalla pagina del registro web (regclasse.php): voci di <select id="tipo_attivita"> e progetti di <select id="progetto_set">. */
    fun leggiOpzioniFirma(html: String): OpzioniFirma {
        val tipi = opzioniSelect(html, "tipo_attivita").map { it.first }.filter { it.isNotEmpty() }.distinct()
        val progetti = opzioniSelect(html, "progetto_set").filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .map { Progetto(it.first, it.second) }.distinctBy { it.id }
        val attivi = Regex("id=\"set_enable\"\\s+value=\"1\"").containsMatchIn(html) || Regex("value=\"1\"\\s+id=\"set_enable\"").containsMatchIn(html)
        return OpzioniFirma(tipi, progetti, attivi)
    }

    /**
     * Voci di una <select> con l'eventuale voce scelta. Il sito scrive le voci scelte come `selected` o
     * `selected="selected"` e lascia `:selected:` su quelle non scelte (finestra di modifica, HAR del 1/11/2026).
     */
    private fun selectConScelta(html: String, id: String): Pair<List<Pair<String, String>>, String?> {
        val apertura = Regex("<select\\b[^>]*\\bid=[\"']${Regex.escape(id)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?: return emptyList<Pair<String, String>>() to null
        val fine = html.indexOf("</select>", apertura.range.last).takeIf { it > 0 } ?: html.length
        var scelta: String? = null
        val voci = Regex("<option\\b([^>]*)>(.*?)(?=</option>|<option\\b|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html.substring(apertura.range.last + 1, fine)).mapNotNull { m ->
                val attributi = m.groupValues[1]
                val valore = Regex("\\bvalue=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(attributi)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val voce = testoHtml(valore) to testoHtml(m.groupValues[2])
                if (scelta == null && Regex("(?<![:\\w])selected(?![:\\w])", RegexOption.IGNORE_CASE).containsMatchIn(attributi)) scelta = voce.first
                voce
            }.toList()
        return voci to scelta
    }

    /** Contenuto di una <textarea> (con a capo), null se nella pagina non c'è. */
    private fun textarea(html: String, id: String): String? =
        Regex("<textarea\\b[^>]*\\bid=[\"']${Regex.escape(id)}[\"'][^>]*>(.*?)</textarea>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { entita(it).trim() }

    /**
     * La finestra "aggiorna argomento della lezione" del registro web (regclasse_update_firma.php), con i valori attuali
     * della firma: si rimandano tutti nel POST UPD, così quello che non si tocca resta com'è (progetto, integrazione privata, UDA).
     */
    data class FormModifica(
        val attivita: List<String>,
        val attivitaScelta: String?,
        val progetti: List<Progetto>,
        val progettoScelto: String?,
        /** campo set_enable: il registro usa i progetti di scuola e territorio */
        val progettiAttivi: Boolean,
        /** argomento (text_nota), null se la finestra non l'ha */
        val argomento: String?,
        /** integrazione privata (text_nota_2), visibile solo nel registro del docente */
        val notaPrivata: String,
        /** unità di apprendimento (uda_firma_edit): id → nome, senza la voce vuota */
        val uda: List<Pair<String, String>>,
        val udaScelta: String?,
    )

    fun leggiFormModifica(html: String): FormModifica {
        val (attivita, attivitaScelta) = selectConScelta(html, "tipo_attivita_modifica")
        val (progetti, progettoScelto) = selectConScelta(html, "progetto_set_modifica")
        val (uda, udaScelta) = selectConScelta(html, "uda_firma_edit")
        val attivi = Regex("<input\\b(?=[^>]*\\bid=[\"']set_enable[\"'])(?=[^>]*\\bvalue=[\"']1[\"'])[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html)
        return FormModifica(
            attivita = attivita.map { it.first.ifEmpty { it.second } }.filter { it.isNotEmpty() }.distinct(),
            attivitaScelta = attivitaScelta?.takeIf { it.isNotEmpty() },
            progetti = progetti.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.map { Progetto(it.first, it.second) }.distinctBy { it.id },
            progettoScelto = progettoScelto?.takeIf { it.isNotEmpty() },
            progettiAttivi = attivi,
            argomento = textarea(html, "popup_text_nota"),
            notaPrivata = textarea(html, "popup_text_nota_2").orEmpty(),
            uda = uda.filter { it.first.isNotEmpty() },
            udaScelta = udaScelta?.takeIf { it.isNotEmpty() },
        )
    }

    /** Un'unità di apprendimento proposta dal registro per materia e classe (rubrica_io.php?ope=getUdaFirma). */
    data class Uda(val id: String, val nome: String, val corrente: Boolean, val fasi: List<Pair<String, String>>)

    /**
     * La risposta di getUdaFirma, letta come fa regclasse.js (udaLoad): per ogni voce uda_id, "(codice) - shortdesc",
     * current = "1" per quella già proposta, lista_fasi con id e fase. Arriva come elenco o come oggetto; vuota se non ci sono UDA.
     */
    fun leggiUda(testo: String): List<Uda> {
        val radice = runCatching { org.json.JSONTokener(testo.trim()).nextValue() }.getOrNull() ?: return emptyList()
        val voci = when (radice) {
            is JSONArray -> (0 until radice.length()).mapNotNull { radice.optJSONObject(it) }
            is JSONObject -> radice.keys().asSequence().mapNotNull { radice.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        fun JSONObject.testo(k: String) = opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim().orEmpty()
        return voci.mapNotNull { u ->
            val id = u.testo("uda_id").ifEmpty { return@mapNotNull null }
            val codice = u.testo("codice")
            val nome = listOf(codice.takeIf { it.isNotEmpty() }?.let { "($it)" }, u.testo("shortdesc").takeIf { it.isNotEmpty() })
                .filterNotNull().joinToString(" - ").ifEmpty { id }
            val elencoFasi = when (val f = u.opt("lista_fasi")) {
                is JSONArray -> (0 until f.length()).mapNotNull { f.optJSONObject(it) }
                is JSONObject -> f.keys().asSequence().mapNotNull { f.optJSONObject(it) }.toList()
                else -> emptyList()
            }
            val fasi = elencoFasi.mapNotNull { x -> x.testo("id").takeIf { it.isNotEmpty() }?.let { it to x.testo("fase") } }
            Uda(id, testoHtml(nome), u.testo("current") == "1", fasi)
        }.distinctBy { it.id }
    }

    /** Voci "valore" della finestra di firma del sito (peso_ora); la quarta (1.75) sul sito è nascosta. */
    val PESI_ORA = listOf("0.5" to "mezz'ora", "1" to "un'ora", "1.5" to "un'ora e mezza")

    /** Campi della nuova firma oltre a classe, materia, giorno e ora, con i valori predefiniti del sito. */
    data class CampiFirma(
        val tipoAttivita: String = "Lezione",
        val numeroOre: Int = 1,
        val idProgetto: String = "",
        val argomento: String = "",
        val pesoOra: String = "1",
        val udaId: String = "",
        val faseId: String = "",
    )

    /**
     * Corpo del POST regclasse_io.php (FIRMA_INSERT), negli stessi campi e nello stesso ordine del sito (HAR del 1/11/2026).
     * Classe intera: [classeId] e [gruppo] vuoto; gruppo di classe articolata: la sigla [gruppo] va in classe_id con gruppo=1.
     * [giorno] è yyyy-MM-dd.
     */
    fun campiFirmaInsert(classeId: String, gruppo: String, materiaId: String, giorno: String, ora: Int, c: CampiFirma): List<Pair<String, String>> {
        return listOf(
            "action" to "FIRMA_INSERT", "classe_id" to gruppo.ifEmpty { classeId }, "data_start" to giorno,
            "conteggioassenze" to "conteggiaora",
            "materia" to materiaId, "tipo_attivita" to c.tipoAttivita,
            "ora_posizione" to ora.toString(), "numero_ore" to c.numeroOre.coerceAtLeast(1).toString(),
            "uda_firma" to c.udaId, "fase_firma" to if (c.udaId.isEmpty()) "" else c.faseId,
            "id_progetto_set" to c.idProgetto,
            "peso_ora" to (PESI_ORA.firstOrNull { it.first == c.pesoOra }?.first ?: "1"),
            "text_nota" to c.argomento.trim(), "output" to "", "gruppo" to if (gruppo.isEmpty()) "0" else "1"
        )
    }

    /** Il tipo attività chiede il progetto di scuola e territorio? Stessa regola del sito (regclasse.js): ALTERNANZA, ASL, PCTO, FSL. */
    fun richiedeProgetto(tipoAttivita: String): Boolean {
        val t = tipoAttivita.trim().uppercase(Locale.ROOT)
        return t.startsWith("ALTERNANZA") || t.startsWith("ASL") || t.startsWith("PCTO") || t.startsWith("FSL")
    }

    /** Progetti filtrati per [cerca] (tutte le parole, senza maiuscole); quelli che nominano la classe [sigla] vengono prima. */
    fun cercaProgetti(progetti: List<Progetto>, cerca: String, sigla: String): List<Progetto> {
        val parole = normalizza(cerca).split(' ').filter { it.isNotEmpty() }
        val trovati = progetti.filter { p -> val n = normalizza(p.nome); parole.all { it in n } }
        val s = normalizza(sigla).substringBefore('_')
        if (s.isEmpty()) return trovati
        val (classe, altri) = trovati.partition { Regex("(^|[^A-Z0-9])${Regex.escape(s)}([^A-Z0-9]|$)").containsMatchIn(normalizza(it.nome)) }
        return classe + altri
    }

    /** Il messaggio leggibile della risposta del server: il campo di errore se è JSON, altrimenti il testo senza HTML. */
    fun messaggioServer(testo: String): String {
        val t = testo.trim()
        if (t.isEmpty()) return "nessun messaggio dal server"
        val json = runCatching { org.json.JSONTokener(t).nextValue() as? JSONObject }.getOrNull()
        if (json != null) {
            for (k in listOf("message", "msg", "error", "errore", "info", "reason", "detail")) {
                when (val v = json.opt(k)) {
                    is JSONObject -> v.optString("message").ifBlank { v.optString("msg") }.takeIf { it.isNotBlank() }?.let { return it.trim() }
                    is String -> if (v.isNotBlank()) return v.trim()
                }
            }
            json.optString("status").takeIf { it.isNotBlank() }?.let { return "status $it" }
        }
        return t.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(300)
            .ifEmpty { "nessun messaggio dal server" }
    }

    /** Il server dice che l'ora è già firmata? (riconosce le frasi più probabili, in italiano e in inglese) */
    fun parlaDiFirmaGiaPresente(testo: String): Boolean {
        val s = testo.lowercase(Locale.ROOT)
        return listOf("già firmat", "gia' firmat", "gia firmat", "già present", "esiste già", "already", "duplicat").any { it in s }
    }

    /** Esito della risposta di firmaRapida: riuscita solo con un codice 2xx; altrimenti codice http e messaggio del server. */
    fun esito(codice: Int, testo: String): Esito {
        val messaggio = messaggioServer(testo)
        if (codice in 200..299) return Esito(true, "firma registrata (http $codice)", messaggio)
        val gia = parlaDiFirmaGiaPresente(testo)
        val titolo = if (gia) "ora già firmata (http $codice)" else "firma non riuscita (http $codice)"
        return Esito(false, titolo, messaggio, giaFirmata = gia)
    }

    /** Descrizione di un errore di rete o di lettura, senza ridurre tutto a "firma non riuscita". */
    fun descriviErrore(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is java.net.UnknownHostException -> return "nessuna connessione a internet: classeviva non è raggiungibile."
                is java.net.SocketTimeoutException -> return "classeviva non ha risposto in tempo (timeout). controlla sul registro se la firma è arrivata."
                is java.net.ConnectException, is java.net.NoRouteToHostException -> return "connessione a classeviva non riuscita: ${c.message.orEmpty()}".trim()
                is javax.net.ssl.SSLException -> return "connessione sicura con classeviva non riuscita: ${c.message.orEmpty()}".trim()
                is org.json.JSONException -> return "risposta di classeviva non valida: ${c.message.orEmpty()}".trim()
            }
            c = c.cause
        }
        return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}
