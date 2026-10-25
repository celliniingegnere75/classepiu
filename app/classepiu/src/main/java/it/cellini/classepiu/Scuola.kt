package it.cellini.classepiu

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Primo giorno di scuola dell'anno in corso, letto da classeviva (inizio del primo periodo) e ricordato sul telefono,
 * così le date di lezioni, alunni e voti non vanno mai prima. Se non si è mai letto, vale il 1 settembre.
 */
object Scuola {
    private const val PREF = "scuola"
    private const val CHIAVE = "primo_giorno"
    private val formato = SimpleDateFormat("yyyy-MM-dd", Locale.ITALIAN)

    fun primoGiorno(ctx: Context): Calendar {
        val letto = runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(CHIAVE, null) }.getOrNull()
        return runCatching { Calendar.getInstance().apply { time = formato.parse(letto!!)!! } }.getOrElse {
            val oggi = Calendar.getInstance()
            Calendar.getInstance().apply {
                set(if (oggi.get(Calendar.MONTH) >= Calendar.SEPTEMBER) oggi.get(Calendar.YEAR) else oggi.get(Calendar.YEAR) - 1, Calendar.SEPTEMBER, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }
    }

    fun salva(ctx: Context, primo: Calendar) {
        runCatching { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(CHIAVE, formato.format(primo.time)).apply() }
    }
}
