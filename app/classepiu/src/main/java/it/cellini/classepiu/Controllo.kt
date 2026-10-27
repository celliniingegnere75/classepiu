package it.cellini.classepiu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Esito di un controllo della bacheca: [presenti] circolari in bacheca, [allegatiInviati] allegati mandati in ritardo,
 * [allegatiInAttesa] circolari i cui allegati non sono ancora scaricabili, [inviate] se le mail sono partite davvero.
 */
data class Esito(val nuove: Int, val nonInviate: Int, val primoAvvio: Boolean, val messaggio: String,
                 val presenti: Int = 0, val allegatiInviati: Int = 0, val allegatiInAttesa: Int = 0, val inviate: Boolean = false) {
    /** Cosa è successo, detto in breve per il pulsante "invia email". */
    fun riassunto(): String = when {
        primoAvvio -> messaggio
        presenti == 0 -> "nessuna circolare presente."
        !inviate && nuove > 0 -> "invio per email spento: $nuove circolar${if (nuove == 1) "e nuova" else "i nuove"} non inviat${if (nuove == 1) "a" else "e"}."
        nuove > 0 || allegatiInviati > 0 -> listOfNotNull(
            if (nuove > 0) "inviat${if (nuove == 1) "a 1 circolare" else "e $nuove circolari"} per email" else null,
            if (allegatiInviati > 0) "inviat${if (allegatiInviati == 1) "o 1 allegato arrivato" else "i $allegatiInviati allegati arrivati"} in ritardo" else null,
            if (nonInviate > 0) "$nonInviate non partit${if (nonInviate == 1) "a" else "e"} (si riprova)" else null,
        ).joinToString(", ") + "."
        nonInviate > 0 -> "nessuna email partita: $nonInviate circolar${if (nonInviate == 1) "e" else "i"} non inviat${if (nonInviate == 1) "a" else "e"}, si riprova."
        allegatiInAttesa > 0 -> "nessuna circolare nuova. $allegatiInAttesa circolar${if (allegatiInAttesa == 1) "e aspetta" else "i aspettano"} ancora gli allegati da classeviva."
        else -> "nessuna circolare nuova: tutte le circolari e gli allegati sono già stati inviati."
    }
}

/** Controlla la bacheca e invia per mail le circolari nuove (stessa logica di cv.py run). */
object Controllo {
    private const val ATTESA_ALLEGATI_MS = 48L * 3600 * 1000

    private val inCorso = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Quando l'app si risveglia: se il controllo automatico è in ritardo (Android lo ha bloccato o rimandato),
     * lo esegue subito. Le circolari che non partono ora si riprovano al giro successivo.
     */
    fun recupera(ctx: Context) {
        val imp = Impostazioni(ctx)
        if (!imp.pronta || !imp.controlloAutomatico) return
        val ultimo = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getLong("ultimo_controllo", 0L)
        val scadenza = ultimo + imp.oreIntervallo * 3600_000L + 5 * 60_000L
        if (ultimo != 0L && System.currentTimeMillis() < scadenza) return
        if (!inCorso.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try { runCatching { esegui(app) }.onFailure { Diario.scrivi(app, "controllo al risveglio non riuscito: ${it.message}") } }
            finally { inCorso.set(false) }
        }.start()
    }

    @Synchronized
    fun esegui(ctx: Context): Esito {
        val imp = Impostazioni(ctx)
        val archivio = Viste(ctx)
        if (!imp.pronta) return Esito(0, 0, false, "credenziali classeviva non impostate.")
        // come cv.py ("Manca il ricevitore"): senza invio configurato non si tocca nulla,
        // così nessuna circolare viene segnata come vista senza essere stata mandata
        if (imp.invioMail && !imp.mailPronta) throw IllegalStateException(imp.coseMancanti)
        val caricate = archivio.carica()
        val primo = caricate == null
        val viste = caricate ?: mutableMapOf()
        val cv = imp.client()
        cv.login()
        val items = cv.bacheca()
        var nuove = 0
        var errori = 0
        var allegatiInviati = 0
        var inAttesa = 0

        for (c in items) {
            val stato = viste[c.chiave]
            if (stato == 1L) continue
            if (primo) { viste[c.chiave] = 1L; continue }
            if (stato != null) {
                when (riprovaAllegati(ctx, imp, cv, c, viste, stato)) {
                    true -> allegatiInviati++
                    false -> errori++
                    null -> if (viste[c.chiave] != 1L) inAttesa++
                }
                continue
            }
            try {
                val scaricati = try {
                    cv.tuttiGliAllegati(c, 3, apriSeServe = true)
                } catch (r: ClasseViva.CircolareRitirata) {
                    viste[c.chiave] = 1L
                    Diario.scrivi(ctx, "Ritirata dalla scuola, non inviata: ${c.titolo}")
                    continue
                }
                if (imp.invioMail && imp.mailPronta) {
                    Mailer.inviaCircolare(ctx, imp, c.titolo, c.data, c.categoria, Mailer.testoCircolare(c, scaricati.size), scaricati)
                }
                viste[c.chiave] = if (scaricati.size < c.allegati.size) System.currentTimeMillis() else 1L
                archivio.salva(viste)
                nuove++
                Diario.scrivi(ctx, (if (imp.invioMail && imp.mailPronta) "Inviata: " else "Nuova (mail disattivata): ") + c.titolo +
                        if (c.azioni.isNotEmpty()) " [richiede: ${c.azioni.joinToString(", ")}]" else "")
                // le circolari da firmare restano "da leggere", così si vede che c'è da confermare
                if (imp.segnaLette && c.azioni.isEmpty()) runCatching { cv.segnaLetta(c) }
                Notifiche.circolare(ctx, c)
            } catch (e: Exception) {
                errori++
                Diario.scrivi(ctx, "NON inviata (si riprova): ${c.titolo} -> ${e.message}")
            }
        }
        if (items.isNotEmpty()) {
            val presenti = items.map { it.chiave }.toSet()
            viste.keys.retainAll(presenti)
        }
        archivio.salva(viste)
        val msg = if (primo) "primo avvio: segnate come già viste ${items.size} circolari."
        else "controllo fatto. circolari nuove: $nuove" + if (errori > 0) ", non inviate: $errori" else ""
        Diario.scrivi(ctx, msg)
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong("ultimo_controllo", System.currentTimeMillis()).apply()
        return Esito(nuove, errori, primo, msg, items.size, allegatiInviati, inAttesa, imp.invioMail && imp.mailPronta)
    }

    /** true = allegati mandati, false = invio non riuscito, null = nulla da mandare (ancora in attesa o rinuncia). */
    private fun riprovaAllegati(ctx: Context, imp: Impostazioni, cv: ClasseViva, c: Circolare, viste: MutableMap<String, Long>, dal: Long): Boolean? {
        val scaricati = try {
            cv.tuttiGliAllegati(c, 1, apriSeServe = true) // come cv.py: se manca, apre la circolare e riprova
        } catch (r: ClasseViva.CircolareRitirata) {
            viste[c.chiave] = 1L
            Diario.scrivi(ctx, "Ritirata dalla scuola, allegato non più disponibile: ${c.titolo}")
            return null
        }
        if (scaricati.size < c.allegati.size) {
            if (System.currentTimeMillis() - dal > ATTESA_ALLEGATI_MS) {
                viste[c.chiave] = 1L
                Diario.scrivi(ctx, "Allegati ancora non disponibili dopo 48 ore, rinuncio: ${c.titolo}")
            }
            return null
        }
        try {
            if (imp.invioMail && imp.mailPronta) {
                Mailer.inviaCircolare(ctx, imp, "Allegato - ${c.titolo}", c.data, c.categoria, "Allegato della circolare inviata in precedenza senza documento.", scaricati)
            }
            viste[c.chiave] = 1L
            Diario.scrivi(ctx, "Inviato allegato: ${c.titolo}")
            return if (imp.invioMail && imp.mailPronta) true else null
        } catch (e: Exception) {
            Diario.scrivi(ctx, "Allegato NON inviato (si riprova): ${c.titolo} -> ${e.message}")
            return false
        }
    }

    /** Invia subito una circolare scelta (dal dettaglio). */
    fun inviaOra(ctx: Context, c: Circolare): Int {
        val imp = Impostazioni(ctx)
        val cv = imp.client()
        cv.login()
        cv.bacheca()
        val scaricati = cv.tuttiGliAllegati(c, 3, apriSeServe = true)
        Mailer.inviaCircolare(ctx, imp, c.titolo, c.data, c.categoria, Mailer.testoCircolare(c, scaricati.size), scaricati)
        Diario.scrivi(ctx, "Inviata a mano: ${c.titolo} (allegati ${scaricati.size}/${c.allegati.size})")
        return scaricati.size
    }

    fun pianifica(ctx: Context) {
        val imp = Impostazioni(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!imp.controlloAutomatico) {
            wm.cancelUniqueWork(LAVORO)
            return
        }
        val richiesta = PeriodicWorkRequestBuilder<LavoroControllo>(imp.oreIntervallo.toLong().coerceIn(1, 24), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(LAVORO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
    }

    private const val LAVORO = "controllo_bacheca"
}

/** Lavoro in background che Android esegue periodicamente, anche ad app chiusa e dopo il riavvio. */
class LavoroControllo(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Controllo.esegui(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Diario.scrivi(applicationContext, "Controllo non riuscito: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object Notifiche {
    private const val CANALE = "circolari"

    fun creaCanale(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANALE, "nuove circolari", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun circolare(ctx: Context, c: Circolare) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        creaCanale(ctx)
        val apri = PendingIntent.getActivity(
            ctx, c.chiave.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CANALE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (c.azioni.isNotEmpty()) "circolare da confermare" else "nuova circolare")
            .setContentText(c.titolo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.titolo))
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(c.chiave.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
