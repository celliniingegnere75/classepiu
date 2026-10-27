package it.cellini.classepiu

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Wizard dell'orario: classe e materia di ogni ora ricavate dalle firme del docente nei 7 giorni prima di oggi
 * (lessons/mine). Così ogni giorno della settimana compare una volta sola: se oggi è lunedì si guarda
 * da lunedì scorso a venerdì (o sabato), se è mercoledì da mercoledì scorso a ieri.
 * I nomi dei campi sono dedotti come nelle altre letture di classeviva: lettura tollerante.
 */
object OrarioDalleFirme {

    /** Primo e ultimo giorno da cercare (yyyyMMdd): da oggi meno 7 giorni a ieri. */
    fun intervallo(oggi: Calendar = Calendar.getInstance()): Pair<String, String> {
        fun fmt(c: Calendar) = String.format(java.util.Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val da = (oggi.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -7) }
        val a = (oggi.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        return fmt(da) to fmt(a)
    }

    /**
     * Ore trovate nelle firme: chiave (giorno 1 = lunedì … 6 = sabato, ora 1…ORE).
     * Una firma di più ore (evtDuration) riempie le ore consecutive. Si scartano le firme di altri docenti, le supplenze
     * e le ore in cui classe e materia non sono tra le proprie ([insegnamenti], "le mie classi"); classe e materia
     * si scrivono come nelle tendine.
     */
    fun leggi(grezzo: String, mioId: String, insegnamenti: List<Insegnamento>, giorni: Int): Map<Pair<Int, Int>, Insegnamento> {
        val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
        val out = LinkedHashMap<Pair<Int, Int>, Insegnamento>()
        fun JSONObject.t(vararg ks: String): String {
            for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
            return ""
        }
        fun cammina(v: Any?, livello: Int) {
            if (livello > 6) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), livello + 1)
                is JSONObject -> {
                    val data = v.t("evtDate", "lessonDate", "date", "day")
                    val ora = v.t("evtHPos", "hourPos", "evtHour", "hour").toDoubleOrNull()?.toInt()
                    val materia = v.t("subjectDesc", "subjectName", "subject").ifEmpty { v.optJSONObject("subject")?.t("subjectDesc", "description", "name").orEmpty() }
                    val c = v.optJSONObject("classroom") ?: v.optJSONObject("class")
                    val classe = siglaClasse(v.t("classDesc", "className", "classroomName", "classCode").ifEmpty { c?.t("classDesc", "className", "name", "classCode").orEmpty() }).uppercase()
                    if (data.isEmpty() || ora == null || classe.isEmpty()) { for (k in v.keys()) cammina(v.opt(k), livello + 1); return }
                    // educazione civica non sta nelle tendine dell'orario: si salta (se nella stessa ora c'è un'altra firma, vale quella)
                    if (materia.lowercase().contains("civica")) return
                    if (v.t("lessonType", "lessonTypeDesc", "lessonTypeCode").lowercase().contains("supplen")) return
                    val autore = v.t("authorId", "teacherId")
                    if (mioId.isNotEmpty() && autore.isNotEmpty() && autore != mioId) return
                    val giorno = giornoDellaSettimana(data) ?: return
                    if (giorno !in 1..giorni) return
                    val durata = (v.t("evtDuration", "evtNumH", "duration", "hours").toDoubleOrNull()?.toInt() ?: 1).coerceIn(1, Orario.ORE)
                    val voce = comeNelleTendine(classe, materia, insegnamenti) ?: return
                    for (o in ora until ora + durata) if (o in 1..Orario.ORE) out.putIfAbsent(giorno to o, voce)
                }
            }
        }
        cammina(radice, 0)
        return out
    }

    /** 1 = lunedì … 7 = domenica, da "2026-10-20", "20261020" o con l'ora in coda. */
    fun giornoDellaSettimana(data: String): Int? {
        val n = data.take(10).replace("-", "").take(8)
        if (n.length != 8 || n.any { !it.isDigit() }) return null
        val c = Calendar.getInstance().apply { clear(); set(n.take(4).toInt(), n.substring(4, 6).toInt() - 1, n.substring(6, 8).toInt()) }
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    /** La coppia di "le mie classi" con la stessa classe e materia, o null se in quell'ora non c'è una materia del docente. */
    private fun comeNelleTendine(classe: String, materia: String, insegnamenti: List<Insegnamento>): Insegnamento? {
        if (materia.isEmpty()) return null
        val stessa = insegnamenti.filter { it.classe.equals(classe, ignoreCase = true) && it.materia.isNotEmpty() }
        return stessa.firstOrNull { it.materia.equals(materia, ignoreCase = true) }
            ?: stessa.firstOrNull { it.materia.contains(materia, true) || materia.contains(it.materia, true) }
    }
}
