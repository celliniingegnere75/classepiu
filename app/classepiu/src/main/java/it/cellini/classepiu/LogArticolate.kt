package it.cellini.classepiu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEBUG TEMPORANEO (da togliere): log delle firme di un giorno per capire le classi articolate
 * (es. 5EA = 5EA_AUA + 5EA_ENA). Per ogni firma scrive i campi di classe/gruppo che manda classeviva,
 * gli alunni coinvolti e se tocca gli alunni dei miei gruppi, più l'esito della regola di filtro proposta.
 */
object LogArticolate {

    private val chiaviClasse = Regex("class|group|grp|sect|sez|cls|course", RegexOption.IGNORE_CASE)

    private fun JSONObject.t(vararg ks: String): String {
        for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
        return ""
    }

    private fun firme(v: Any?, out: MutableList<JSONObject>, livello: Int = 0) {
        if (livello > 6) return
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) firme(v.opt(i), out, livello + 1)
            is JSONObject -> {
                val eFirma = v.t("evtCode", "eventCode").equals("LSF0", true) ||
                    (v.t("authorName", "teacherName").isNotEmpty() && v.t("evtHPos", "hourPos", "evtHour").isNotEmpty())
                if (eFirma) out.add(v) else for (k in v.keys()) firme(v.opt(k), out, livello + 1)
            }
        }
    }

    private fun firmeDi(grezzo: String): List<JSONObject> {
        val radice = runCatching { org.json.JSONTokener(grezzo).nextValue() }.getOrNull() ?: return emptyList()
        return ArrayList<JSONObject>().also { firme(radice, it) }
    }

    private fun idAlunno(o: JSONObject): String {
        val s = o.optJSONObject("student") ?: o
        return s.t("studentId", "stuId", "id", "pupilId").ifEmpty { o.t("studentId", "stuId", "id", "pupilId") }
    }

    private fun alunniFirma(f: JSONObject): Set<String> {
        val a = f.optJSONArray("studentStatus") ?: f.optJSONArray("students") ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> idAlunno(o) }?.takeIf { s -> s.isNotEmpty() } }.toSet()
    }

    private fun chiaveFirma(f: JSONObject) =
        listOf(f.t("evtHPos", "hourPos", "evtHour"), f.t("authorId", "teacherId"), f.t("subjectId", "subjectDesc")).joinToString("/")

    /** Campi "di classe" di un oggetto (solo valori semplici), anche dentro gli oggetti figli di primo livello. */
    private fun campiClasse(f: JSONObject): String {
        val out = ArrayList<String>()
        for (k in f.keys()) {
            val v = f.opt(k)
            if (v is JSONObject) {
                for (k2 in v.keys()) {
                    val v2 = v.opt(k2)
                    if (v2 !is JSONObject && v2 !is JSONArray && (chiaviClasse.containsMatchIn(k) || chiaviClasse.containsMatchIn(k2))) out.add("$k.$k2=$v2")
                }
            } else if (v !is JSONArray && chiaviClasse.containsMatchIn(k)) out.add("$k=$v")
        }
        return out.joinToString(" ").ifEmpty { "(nessun campo classe/gruppo)" }
    }

    fun genera(c: ClasseViva, filesDir: File, tipo: String, classeId: String, nome: String, giorno: String, corpo: String, mioId: String): String {
        val log = StringBuilder()
        val sigla = nome.trim().uppercase().substringBefore('_').substringBefore(' ')
        log.append("=== LOG CLASSI ARTICOLATE (debug) ===\n")
        log.append("registro aperto: tipo=${tipo.ifEmpty { "?" }} id=$classeId nome=$nome giorno=$giorno mioId=$mioId\n")

        // i miei registri (classes/mine, dalla cache dell'elenco): gruppi G della stessa classe
        val mie = runCatching { leggiClassi(File(filesDir, "classi_mie.json").readText()) }.getOrDefault(emptyList())
        fun parti(k: Classe) = k.chiave.split("|", limit = 3)
        val mieStessaClasse = mie.filter { k ->
            val p = parti(k)
            val id = p.getOrElse(1) { "" }.uppercase()
            k.nome.uppercase().substringBefore('_') == sigla || id.substringBefore('_') == sigla
        }
        log.append("miei registri della stessa classe ($sigla): " +
            mieStessaClasse.joinToString { "${parti(it).getOrElse(0) { "" }}|${parti(it).getOrElse(1) { "" }}|${it.nome}" }.ifEmpty { "nessuno" } + "\n")
        val mieiGruppi = mieStessaClasse.filter { parti(it).getOrElse(0) { "" }.equals("G", true) || parti(it).getOrElse(1) { "" }.contains('_') }
        val articolata = tipo.equals("G", true) || classeId.contains('_') || mieiGruppi.isNotEmpty()
        log.append("classe articolata (per me): ${if (articolata) "SI" else "NO"}\n")

        // alunni della classe aperta e dei miei gruppi
        val alunniAperta = runCatching { leggiStudenti(c.studentiGrezzi(tipo, classeId).second).map { it.id }.toSet() }.getOrDefault(emptySet())
        log.append("alunni registro aperto: ${alunniAperta.size}\n")
        data class Gruppo(val tipo: String, val id: String, val alunni: Set<String>, val firme: Set<String>, val nFirme: Int, val http: Int)
        val gruppi = mieiGruppi.map { g ->
            val p = parti(g)
            val gt = p.getOrElse(0) { "" }; val gid = p.getOrElse(1) { "" }
            val al = runCatching { leggiStudenti(c.studentiGrezzi(gt, gid).second).map { it.id }.toSet() }.getOrDefault(emptySet())
            val r = runCatching { c.lezioniClasseGrezze(gt, gid, giorno) }.getOrNull()
            val fg = r?.second?.let { firmeDi(it) }.orEmpty()
            Gruppo(gt, gid, al, fg.map { chiaveFirma(it) }.toSet(), fg.size, r?.first ?: -1)
        }
        for (g in gruppi) {
            log.append("mio gruppo ${g.tipo}|${g.id}: ${g.alunni.size} alunni, " +
                "comuni col registro aperto ${g.alunni.intersect(alunniAperta).size}; lessons2 del gruppo http ${g.http}, ${g.nFirme} firme\n")
        }

        val lista = firmeDi(corpo).sortedBy { it.t("evtHPos", "hourPos", "evtHour").toIntOrNull() ?: 99 }
        log.append("firme nel registro aperto: ${lista.size}\n")
        lista.firstOrNull()?.let { f ->
            log.append("campi della prima firma: ${f.keys().asSequence().toList().joinToString()}\n")
            f.optJSONArray("studentStatus")?.optJSONObject(0)?.let { log.append("esempio studentStatus[0]: ${it.toString().take(300)}\n") }
        }
        log.append("\n")

        for (f in lista) {
            val ora = f.t("evtHPos", "hourPos", "evtHour")
            val materia = f.t("subjectDesc", "subjectName", "subjectCode")
            val autore = f.t("authorName", "teacherName")
            val autoreId = f.t("authorId", "teacherId")
            val al = alunniFirma(f)
            val mia = mioId.isNotEmpty() && autoreId == mioId
            log.append("• ${ora}ª ora · $materia · $autore (id $autoreId)${if (mia) " · MIA" else ""}\n")
            log.append("   classe/gruppo: ${campiClasse(f)}\n")
            log.append("   alunni nella firma: ${al.size}" + (if (alunniAperta.isNotEmpty()) " su ${alunniAperta.size} del registro aperto" else "") + "\n")
            var tocca = false
            for (g in gruppi) {
                val comuni = al.intersect(g.alunni).size
                val presente = chiaveFirma(f) in g.firme
                if (comuni > 0) tocca = true
                log.append("   vs ${g.id}: alunni in comune $comuni/${g.alunni.size} · presente in lessons2 del gruppo: ${if (presente) "SI" else "NO"}\n")
            }
            val intera = alunniAperta.isNotEmpty() && al.size >= alunniAperta.size
            val esito = when {
                !articolata -> "VISIBILE (classe non articolata)"
                mia -> "VISIBILE (firma mia)"
                intera -> "VISIBILE (firma su tutta la classe)"
                al.isEmpty() -> "VISIBILE? (nessun alunno nella firma: da decidere)"
                tocca -> "VISIBILE (ha alunni del mio gruppo)"
                else -> "NASCOSTA (solo alunni dell'altra metà)"
            }
            log.append("   regola proposta: $esito\n\n")
        }
        return log.toString()
    }
}
