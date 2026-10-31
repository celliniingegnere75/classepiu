package it.cellini.classepiu

import java.io.File

/**
 * Classi articolate (es. 5EA = 5EA_AUA + 5EA_ENA): classeviva dà a tutta la classe e a ciascun gruppo le stesse firme,
 * tutte con la classe intera (classType C). Le metà si distinguono solo dagli alunni elencati in ogni firma (studentStatus).
 * Si vedono le mie firme e quelle con almeno un alunno del mio gruppo; le compresenze (firme senza alunni) prendono gli
 * alunni del titolare della stessa materia, prima nella stessa ora e poi nel resto del giorno; senza titolare non si vedono.
 */
object FiltroArticolate {

    data class Firma(val ora: String, val materia: String, val autoreId: String, val alunni: Set<String>)

    /** Per ogni firma, se va mostrata. Con [mieiAlunni] vuoto (classe non articolata) si vedono tutte. */
    fun visibili(firme: List<Firma>, mioId: String, mieiAlunni: Set<String>): List<Boolean> {
        fun alunniDi(f: Firma): Set<String> {
            if (f.alunni.isNotEmpty()) return f.alunni
            val stessaMateria = firme.filter { it.alunni.isNotEmpty() && it.materia.equals(f.materia, ignoreCase = true) }
            val titolari = stessaMateria.filter { it.ora == f.ora }.ifEmpty { stessaMateria }
            return titolari.flatMap { it.alunni }.toSet()
        }
        return firme.map { f ->
            when {
                mieiAlunni.isEmpty() -> true
                mioId.isNotEmpty() && f.autoreId == mioId -> true
                // una compresenza senza titolare riconoscibile non si mostra: solo la mia quota
                else -> alunniDi(f).any { it in mieiAlunni }
            }
        }
    }

    /** "Le mie lezioni": le mie firme e le compresenze (firme senza alunni) nella stessa ora e materia di una mia firma. */
    fun mieECompresenze(firme: List<Firma>, mioId: String): List<Boolean> {
        val mie = firme.filter { mioId.isNotEmpty() && it.autoreId == mioId }
        return firme.map { f ->
            f in mie || (f.alunni.isEmpty() && mie.any { it.ora == f.ora && it.materia.equals(f.materia, ignoreCase = true) })
        }
    }

    /**
     * Gli alunni "miei" del registro aperto: se è un gruppo (es. 5EA_AUA) i suoi alunni; se è la classe intera,
     * quelli dei miei gruppi della stessa classe (dalla cache di "i miei registri"). Vuoto se la classe non è articolata per me.
     */
    fun mieiAlunni(c: ClasseViva, filesDir: File, tipo: String, classeId: String, nome: String): Set<String> {
        fun alunni(t: String, id: String) = runCatching { leggiStudenti(c.studentiGrezzi(t, id).second).map { it.id } }.getOrDefault(emptyList())
        if (tipo.equals("G", true) || classeId.contains('_') || nome.contains('_')) return alunni(tipo, classeId).toSet()
        val sigla = nome.trim().uppercase().substringBefore(' ')
        val mie = runCatching { leggiClassi(File(filesDir, "classi_mie.json").readText()) }.getOrDefault(emptyList())
        return mie.map { it.chiave.split("|", limit = 3) to it.nome.uppercase() }
            .filter { (p, n) -> p.getOrElse(0) { "" }.equals("G", true) && n.contains('_') && n.substringBefore('_') == sigla }
            .flatMap { (p, _) -> alunni(p[0], p.getOrElse(1) { "" }) }
            .toSet()
    }
}
