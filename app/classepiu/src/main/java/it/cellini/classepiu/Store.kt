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
    private val appContext = ctx.applicationContext
    private val p: SharedPreferences = try {
        val chiave = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "impostazioni", chiave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { cifrate ->
            migraPreferenzeLegacy(ctx, cifrate)
        }
    } catch (e: Exception) {
        // Mai ripiegare su preferenze in chiaro: meglio fermarsi che salvare password non cifrate.
        throw IllegalStateException(
            "Impossibile aprire l'archivio cifrato di ClassePiu. Le credenziali non sono state salvate in chiaro.",
            e
        )
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
            if (invioGoogle && googleAccount.contains('@')) return listOf(googleAccount)
            return if (utente.contains('@')) listOf(utente) else emptyList()
        }

    /** true = invio con Gmail dopo l'accesso con Google (OAuth, permesso gmail.send): ha la precedenza sul ricevitore. */
    var invioGoogle: Boolean
        get() = p.getBoolean("invio_google", false)
        set(v) = p.edit().putBoolean("invio_google", v).apply()
    /** Account Google con cui si è dato il consenso (serve per mostrarlo e come destinatario predefinito). */
    var googleAccount: String
        get() = p.getString("google_account", "") ?: ""
        set(v) = p.edit().putString("google_account", v.trim()).apply()

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
    /** Preferenze predefinite per la firma rapida. */
    var firmaTipo: String
        get() = p.getString("firma_tipo", "Lezione") ?: "Lezione"
        set(v) = p.edit().putString("firma_tipo", v).apply()
    var firmaCopresenza: Boolean
        get() = p.getBoolean("firma_copresenza", false)
        set(v) = p.edit().putBoolean("firma_copresenza", v).apply()
    var firmaSostegno: Boolean
        get() = p.getBoolean("firma_sostegno", false)
        set(v) = p.edit().putBoolean("firma_sostegno", v).apply()
    var firmaMateria: String
        get() = p.getString("firma_materia", "") ?: ""
        set(v) = p.edit().putString("firma_materia", v).apply()

    /** Settimana fino a sabato nell'orario (altrimenti da lunedì a venerdì). */
    var sabato: Boolean
        get() = p.getBoolean("sabato", false)
        set(v) = p.edit().putBoolean("sabato", v).apply()
    var oreIntervallo: Int
        get() = p.getInt("ore", 2)
        set(v) = p.edit().putInt("ore", v).apply()
    /** Segna come lette le circolari inviate che non richiedono azioni (come fa cv.py). */
    var segnaLette: Boolean
        get() = p.getBoolean("segna_lette", true)
        set(v) = p.edit().putBoolean("segna_lette", v).apply()

    val pronta get() = utente.isNotBlank() && password.isNotBlank()
    val mailPronta
        get() = if (invioGoogle) googleAccount.contains('@') && destinatariEffettivi.isNotEmpty()
        else if (usaRicevitore) ricevitoreUrl.startsWith("https://") && ricevitoreCodice.isNotBlank()
        else gmail.contains('@') && passwordApp.isNotBlank() && destinatariEffettivi.isNotEmpty()

    /** Descrizione di dove vanno le mail, per i messaggi all'utente. */
    val doveVanno get() = if (invioGoogle) destinatariEffettivi.joinToString(", ") else if (usaRicevitore) "ricevitore Apps Script" else destinatariEffettivi.joinToString(", ")

    /** Spiega cosa manca per poter inviare, quando mailPronta è falso (manca sempre qualcosa di preciso). */
    val coseMancanti: String
        get() = if (invioGoogle) {
            if (!googleAccount.contains('@')) "manca l'accesso con google: in opzioni, funzioni, premi \"accedi con google\"."
            else "manca il destinatario: in opzioni, funzioni, scrivi un'email nel campo destinatario."
        } else if (usaRicevitore) "manca l'indirizzo o il codice del ricevitore: in opzioni, funzioni."
        else "manca gmail o la password per le app: in opzioni, funzioni."

    // Passa il contesto applicativo: abilita il log HTTP diagnostico persistente in ClasseViva.
    fun client() = ClasseViva(utente, password, appContext)
}

/** Migra una sola volta le vecchie impostazioni non cifrate, solo dopo aver aperto l'archivio cifrato. */
private fun migraPreferenzeLegacy(ctx: Context, cifrate: SharedPreferences) {
    val legacy = ctx.getSharedPreferences("impostazioni_semplici", Context.MODE_PRIVATE)
    val valori = legacy.all
    if (valori.isEmpty()) return

    val editor = cifrate.edit()
    for ((chiave, valore) in valori) {
        if (cifrate.contains(chiave)) continue
        when (valore) {
            is String -> editor.putString(chiave, valore)
            is Boolean -> editor.putBoolean(chiave, valore)
            is Int -> editor.putInt(chiave, valore)
            is Long -> editor.putLong(chiave, valore)
            is Float -> editor.putFloat(chiave, valore)
            is Set<*> -> editor.putStringSet(chiave, valore.filterIsInstance<String>().toSet())
        }
    }
    check(editor.commit()) { "Migrazione delle impostazioni nell'archivio cifrato non riuscita." }
    check(legacy.edit().clear().commit()) { "Impossibile eliminare le vecchie impostazioni non cifrate." }
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

/** Inizio e fine di un'ora di lezione, in minuti dalla mezzanotte (8:00 = 480). */
data class Fascia(val inizio: Int, val fine: Int) {
    fun contiene(minuto: Int) = minuto in inizio until fine
    override fun toString() = "${hhmm(inizio)}-${hhmm(fine)}"

    companion object {
        fun hhmm(m: Int) = "%d:%02d".format(m / 60, m % 60)
        /** Valore di partenza: la 1ª ora 8:00-9:00, la 2ª 9:00-10:00 e così via. */
        fun predefinita(ora: Int) = Fascia(420 + ora * 60, 480 + ora * 60)
    }
}

/** Una casella dell'orario completa: quando (giorno, ora, fascia), dove (aula), con chi e cosa (classe, materia). */
data class Lezione(val giorno: Int, val ora: Int, val fascia: Fascia, val classe: String, val materia: String, val aula: String)

/**
 * Orario settimanale del docente, solo sul telefono, in un unico file orario.json:
 * {"v":2, "fasce":[[480,540],…], "fissate":[1,3], "celle":{"1-1":{"c":"4ETA","m":"ELETTROTECNICA…","a":"LAB1"},…}}
 * Giorni 1 = lunedì … 6 = sabato (il sabato si vede solo se acceso in "lezioni"), ore 1…ORE. Le fasce orarie valgono per tutti i giorni.
 * Il file si legge una volta sola e in memoria i dati stanno in due array a indice fisso.
 */
class Orario(ctx: Context) {
    private val file = File(ctx.filesDir, "orario.json")
    private val fasce = Array(ORE) { Fascia.predefinita(it + 1) }
    private val fissate = BooleanArray(ORE) // true = orario scelto dall'utente: la cascata non lo tocca
    private val celle = arrayOfNulls<Lezione>(GIORNI.size * ORE) // solo classe/materia/aula; giorno, ora e fascia si ricavano

    private fun i(giorno: Int, ora: Int) = (giorno - 1) * ORE + (ora - 1)

    init {
        val j = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (j.optInt("v") == 2) {
            j.optJSONArray("fasce")?.let { a ->
                for (k in 0 until minOf(a.length(), ORE)) a.optJSONArray(k)?.let { fasce[k] = Fascia(it.optInt(0), it.optInt(1)) }
            }
            j.optJSONArray("fissate")?.let { a -> for (k in 0 until a.length()) a.optInt(k).let { o -> if (o in 1..ORE) fissate[o - 1] = true } }
            j.optJSONObject("celle")?.let { c ->
                for (k in c.keys()) {
                    val (g, o) = k.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: continue
                    if (g !in 1..GIORNI.size || o !in 1..ORE) continue
                    val v = c.getJSONObject(k)
                    celle[i(g, o)] = Lezione(g, o, fasce[o - 1], v.optString("c"), v.optString("m"), v.optString("a"))
                }
            }
        } else {
            // formato precedente: chiavi "g-o" {classe, materia} e "g-o-aula"
            for (g in 1..GIORNI.size) for (o in 1..ORE) {
                val v = j.optJSONObject("$g-$o")
                val aula = j.optString("$g-$o-aula")
                if (v != null || aula.isNotEmpty())
                    celle[i(g, o)] = Lezione(g, o, fasce[o - 1], v?.optString("classe").orEmpty(), v?.optString("materia").orEmpty(), aula)
            }
            if (j.length() > 0) salva()
        }
    }

    private fun salva() {
        val c = JSONObject()
        celle.forEach { l ->
            if (l != null && (l.classe.isNotEmpty() || l.aula.isNotEmpty()))
                c.put("${l.giorno}-${l.ora}", JSONObject().put("c", l.classe).put("m", l.materia).put("a", l.aula))
        }
        val f = org.json.JSONArray()
        fasce.forEach { f.put(org.json.JSONArray().put(it.inizio).put(it.fine)) }
        val fx = org.json.JSONArray()
        fissate.forEachIndexed { k, si -> if (si) fx.put(k + 1) }
        file.writeText(JSONObject().put("v", 2).put("fasce", f).put("fissate", fx).put("celle", c).toString())
    }

    private fun cambia(giorno: Int, ora: Int, nuova: (Lezione) -> Lezione) {
        val vecchia = celle[i(giorno, ora)] ?: Lezione(giorno, ora, fasce[ora - 1], "", "", "")
        celle[i(giorno, ora)] = nuova(vecchia)
        salva()
    }

    fun casella(giorno: Int, ora: Int): Insegnamento? =
        celle[i(giorno, ora)]?.takeIf { it.classe.isNotEmpty() }?.let { Insegnamento(it.classe, it.materia) }

    /** Classe e materia (null = svuota); l'aula resta. */
    fun imposta(giorno: Int, ora: Int, valore: Insegnamento?) =
        cambia(giorno, ora) { it.copy(classe = valore?.classe.orEmpty(), materia = valore?.materia.orEmpty()) }

    /** Sigla dell'aula dell'ora (fino a 5 caratteri), indipendente da classe e materia. */
    fun aula(giorno: Int, ora: Int): String = celle[i(giorno, ora)]?.aula.orEmpty()

    fun impostaAula(giorno: Int, ora: Int, sigla: String) =
        cambia(giorno, ora) { it.copy(aula = sigla.trim().uppercase().take(AULA_MAX)) }

    fun fascia(ora: Int): Fascia = fasce[ora - 1]

    /**
     * Cambia l'ora indicata (che da quel momento resta come l'ha scelta l'utente) e, a cascata, fa iniziare
     * le ore successive quando finisce la precedente (durata invariata); la cascata si ferma alla prima ora già scelta dall'utente.
     */
    fun impostaFascia(ora: Int, f: Fascia) {
        fasce[ora - 1] = f
        fissate[ora - 1] = true
        for (k in ora until ORE) {
            if (fissate[k]) break
            val durata = fasce[k].fine - fasce[k].inizio
            fasce[k] = Fascia(fasce[k - 1].fine, fasce[k - 1].fine + durata)
        }
        for (k in celle.indices) celle[k]?.let { celle[k] = it.copy(fascia = fasce[it.ora - 1]) }
        salva()
    }

    /** Svuota classe, materia e aula di un giorno (null = tutta la settimana); le fasce orarie restano. */
    fun pulisci(giorno: Int?) {
        for (k in celle.indices) if (giorno == null || k / ORE == giorno - 1) celle[k] = null
        salva()
    }

    /** Tutte le lezioni con una classe, in ordine di giorno e ora (per gli usi futuri). */
    fun lezioni(): List<Lezione> = celle.filterNotNull().filter { it.classe.isNotEmpty() }

    /** La lezione in corso in quel momento, se c'è. */
    fun lezioneAlle(quando: java.util.Calendar = java.util.Calendar.getInstance()): Lezione? {
        val g = quando.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 1
        if (g !in 1..GIORNI.size) return null
        val m = quando.get(java.util.Calendar.HOUR_OF_DAY) * 60 + quando.get(java.util.Calendar.MINUTE)
        val o = fasce.indexOfFirst { it.contiene(m) } + 1
        return if (o == 0) null else celle[i(g, o)]?.takeIf { it.classe.isNotEmpty() }
    }

    companion object {
        val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab")
        const val ORE = 9
        const val AULA_MAX = 5
    }
}
