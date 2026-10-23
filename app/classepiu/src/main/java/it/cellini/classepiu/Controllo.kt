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

/** Esito di un controllo della bacheca. */
data class Esito(val nuove: Int, val nonInviate: Int, val primoAvvio: Boolean, val messaggio: String)

/** Controlla la bacheca e invia per mail le circolari nuove (stessa logica di cv.py run). */
object Controllo {
    private const val ATTESA_ALLEGATI_MS = 48L * 3600 * 1000

    @Synchronized
    fun esegui(ctx: Context): Esito {
        val imp = Impostazioni(ctx)
        val archivio = Viste(ctx)
        if (!imp.pronta) return Esito(0, 0, false, "credenziali classeviva non impostate.")
        // come cv.py ("Manca il ricevitore"): senza invio configurato non si tocca nulla,
        // così nessuna circolare viene segnata come vista senza essere stata mandata
        if (imp.invioMail && !imp.mailPronta) throw IllegalStateException("invio mail non configurato: completa le impostazioni.")
        val caricate = archivio.carica()
        val primo = caricate == null
        val viste = caricate ?: mutableMapOf()
        val cv = imp.client()
        cv.login()
        val items = cv.bacheca()
        var nuove = 0
        var errori = 0

        for (c in items) {
            val stato = viste[c.chiave]
            if (stato == 1L) continue
            if (primo) { viste[c.chiave] = 1L; continue }
            if (stato != null) { riprovaAllegati(ctx, imp, cv, c, viste, stato); continue }
            try {
                val scaricati = try {
                    cv.tuttiGliAllegati(c, 3, apriSeServe = true)
                } catch (r: ClasseViva.CircolareRitirata) {
                    viste[c.chiave] = 1L
                    Diario.scrivi(ctx, "Ritirata dalla scuola, non inviata: ${c.titolo}")
                    continue
                }
                if (imp.invioMail && imp.mailPronta) {
                    Mailer.inviaCircolare(imp, c.titolo, c.data, c.categoria, Mailer.testoCircolare(c, scaricati.size), scaricati)
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
        return Esito(nuove, errori, primo, msg)
    }

    private fun riprovaAllegati(ctx: Context, imp: Impostazioni, cv: ClasseViva, c: Circolare, viste: MutableMap<String, Long>, dal: Long) {
        val scaricati = try {
            cv.tuttiGliAllegati(c, 1, apriSeServe = true) // come cv.py: se manca, apre la circolare e riprova
        } catch (r: ClasseViva.CircolareRitirata) {
            viste[c.chiave] = 1L
            Diario.scrivi(ctx, "Ritirata dalla scuola, allegato non più disponibile: ${c.titolo}")
            return
        }
        if (scaricati.size < c.allegati.size) {
            if (System.currentTimeMillis() - dal > ATTESA_ALLEGATI_MS) {
                viste[c.chiave] = 1L
                Diario.scrivi(ctx, "Allegati ancora non disponibili dopo 48 ore, rinuncio: ${c.titolo}")
            }
            return
        }
        try {
            if (imp.invioMail && imp.mailPronta) {
                Mailer.inviaCircolare(imp, "Allegato - ${c.titolo}", c.data, c.categoria, "Allegato della circolare inviata in precedenza senza documento.", scaricati)
            }
            viste[c.chiave] = 1L
            Diario.scrivi(ctx, "Inviato allegato: ${c.titolo}")
        } catch (e: Exception) {
            Diario.scrivi(ctx, "Allegato NON inviato (si riprova): ${c.titolo} -> ${e.message}")
        }
    }

    /** Invia subito una circolare scelta (dal dettaglio). */
    fun inviaOra(ctx: Context, c: Circolare): Int {
        val imp = Impostazioni(ctx)
        val cv = imp.client()
        cv.login()
        cv.bacheca()
        val scaricati = cv.tuttiGliAllegati(c, 3, apriSeServe = true)
        Mailer.inviaCircolare(imp, c.titolo, c.data, c.categoria, Mailer.testoCircolare(c, scaricati.size), scaricati)
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
