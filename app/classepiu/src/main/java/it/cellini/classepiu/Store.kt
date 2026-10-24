package it.cellini.classepiu

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Impostazioni dell'app. Credenziali e password restano solo sul telefono, cifrate. */
class Impostazioni(ctx: Context) {
    private val p: SharedPreferences = try {
        val chiave = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "impostazioni", chiave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("impostazioni_semplici", Context.MODE_PRIVATE)
    }

    var utente: String
        get() = p.getString("utente", "") ?: ""
        set(v) = p.edit().putString("utente", v.trim()).apply()
    var password: String
        get() = p.getString("password", "") ?: ""
        set(v) = p.edit().putString("password", v).apply()

    var gmail: String
        get() = p.getString("gmail", "") ?: ""
        set(v) = p.edit().putString("gmail", v.trim()).apply()
    var passwordApp: String
        get() = p.getString("password_app", "") ?: ""
        set(v) = p.edit().putString("password_app", v.replace(" ", "")).apply()

    /** Destinatari separati da virgola. Se vuoto si usa la mail del profilo (l'utente ClasseViva, se è una mail). */
    var destinatari: String
        get() = p.getString("destinatari", "") ?: ""
        set(v) = p.edit().putString("destinatari", v.trim()).apply()

    val destinatariEffettivi: List<String>
        get() {
            val elenco = destinatari.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }
            if (elenco.isNotEmpty()) return elenco
            return if (utente.contains('@')) listOf(utente) else emptyList()
        }

    /** true = invio tramite il ricevitore Apps Script (nessuna password per le app); false = Gmail diretto. */
    var usaRicevitore: Boolean
        get() = p.getBoolean("usa_ricevitore", true)
        set(v) = p.edit().putBoolean("usa_ricevitore", v).apply()
    // Ricevitore Apps Script "ClasseViva circolari" (account della scuola), già inserito nell'app:
    // dopo l'accesso con mail e password non serve impostare altro. Si può cambiare dalle Impostazioni.
    var ricevitoreUrl: String
        get() = (p.getString("ricevitore_url", "") ?: "").ifBlank { RICEVITORE_URL }
        set(v) = p.edit().putString("ricevitore_url", v.trim()).apply()
    var ricevitoreCodice: String
        get() = (p.getString("ricevitore_codice", "") ?: "").ifBlank { RICEVITORE_CODICE }
        set(v) = p.edit().putString("ricevitore_codice", v.trim()).apply()

    /** Valori scritti a mano nelle Impostazioni (vuoti = si usa il ricevitore già inserito nell'app). */
    val ricevitoreUrlPersonale get() = p.getString("ricevitore_url", "") ?: ""
    val ricevitoreCodicePersonale get() = p.getString("ricevitore_codice", "") ?: ""

    companion object {
        const val RICEVITORE_URL =
            "https://script.google.com/macros/s/AKfycbwh2KRJ_ENLOXhQ9wYg9RwbdQYQdLsSa3q2J-rOwiXwK-UbEb1PqJp1CZ_iUpyjvI6C/exec"
        const val RICEVITORE_CODICE = "AG02tT3ANujKXTo69vbC-XB9oup3YJY4"
    }

    var invioMail: Boolean
        get() = p.getBoolean("invio_mail", true)
        set(v) = p.edit().putBoolean("invio_mail", v).apply()
    var controlloAutomatico: Boolean
        get() = p.getBoolean("automatico", true)
        set(v) = p.edit().putBoolean("automatico", v).apply()
    var oreIntervallo: Int
        get() = p.getInt("ore", 2)
        set(v) = p.edit().putInt("ore", v).apply()
    /** Segna come lette le circolari inviate che non richiedono azioni (come fa cv.py). */
    var segnaLette: Boolean
        get() = p.getBoolean("segna_lette", true)
        set(v) = p.edit().putBoolean("segna_lette", v).apply()

    val pronta get() = utente.isNotBlank() && password.isNotBlank()
    val mailPronta
        get() = if (usaRicevitore) ricevitoreUrl.startsWith("https://") && ricevitoreCodice.isNotBlank()
        else gmail.contains('@') && passwordApp.isNotBlank() && destinatariEffettivi.isNotEmpty()

    /** Descrizione di dove vanno le mail, per i messaggi all'utente. */
    val doveVanno get() = if (usaRicevitore) "ricevitore Apps Script" else destinatariEffettivi.joinToString(", ")

    fun client() = ClasseViva(utente, password)
}

/**
 * Circolari già trattate: valore 1 = inviata completa,
 * altro numero = inviata senza qualche allegato (istante dell'invio, in ms), si riprova per 48 ore.
 */
class Viste(ctx: Context) {
    private val file = File(ctx.filesDir, "viste.json")

    fun carica(): MutableMap<String, Long>? {
        if (!file.exists()) return null
        return try {
            val j = JSONObject(file.readText())
            j.keys().asSequence().associateWith { j.getLong(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun cancella() { file.delete() }

    fun salva(m: Map<String, Long>) {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k, v) }
        file.writeText(j.toString())
    }
}

/** Registro delle attività (come log.txt di Termux), ultime 300 righe. */
object Diario {
    private const val RIGHE = 300

    private fun file(ctx: Context) = File(ctx.filesDir, "diario.txt")

    @Synchronized
    fun scrivi(ctx: Context, msg: String) {
        val ora = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date())
        val f = file(ctx)
        val righe = (if (f.exists()) f.readLines() else emptyList()) + "$ora  $msg"
        f.writeText(righe.takeLast(RIGHE).joinToString("\n") + "\n")
    }

    fun leggi(ctx: Context): String {
        val f = file(ctx)
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else "Nessuna attività registrata."
    }
}

/** Orario settimanale del docente, solo sul telefono: per ogni giorno (1 = lunedì … 5 = venerdì) e ora (1…8) una classe e una materia. */
class Orario(ctx: Context) {
    private val file = File(ctx.filesDir, "orario.json")

    private fun leggi(): JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }

    fun casella(giorno: Int, ora: Int): Insegnamento? {
        val o = leggi().optJSONObject("$giorno-$ora") ?: return null
        val classe = o.optString("classe")
        return if (classe.isEmpty()) null else Insegnamento(classe, o.optString("materia"))
    }

    /** Sigla dell'aula dell'ora (fino a 5 caratteri), indipendente da classe e materia. */
    fun aula(giorno: Int, ora: Int): String = leggi().optString("$giorno-$ora-aula")

    fun impostaAula(giorno: Int, ora: Int, sigla: String) {
        val j = leggi()
        val s = sigla.trim().uppercase().take(AULA_MAX)
        if (s.isEmpty()) j.remove("$giorno-$ora-aula") else j.put("$giorno-$ora-aula", s)
        file.writeText(j.toString())
    }

    fun imposta(giorno: Int, ora: Int, valore: Insegnamento?) {
        val j = leggi()
        if (valore == null) j.remove("$giorno-$ora")
        else j.put("$giorno-$ora", JSONObject().put("classe", valore.classe).put("materia", valore.materia))
        file.writeText(j.toString())
    }

    companion object {
        val GIORNI = listOf("lun", "mar", "mer", "gio", "ven")
        const val ORE = 9
        const val AULA_MAX = 5
    }
}
