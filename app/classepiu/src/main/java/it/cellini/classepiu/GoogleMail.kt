package it.cellini.classepiu

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Session

/**
 * Invio con Gmail come fa Thunderbird: niente password, l'utente dà una volta il consenso con il suo account Google
 * (permesso gmail.send, solo invio) e l'app riceve un gettone con cui chiama la Gmail API.
 * L'app è riconosciuta da Google tramite pacchetto e impronta della firma (client OAuth "Android" nella Cloud Console):
 * nel codice non c'è nessun ID o segreto.
 */
object GoogleMail {
    private const val INVIO = "https://www.googleapis.com/auth/gmail.send"
    private const val EMAIL = "email"

    fun richiesta(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(INVIO), Scope(EMAIL))).build()

    /** Gettone valido, anche in background: se il consenso manca o è stato revocato serve ripassare da opzioni. */
    private fun gettone(ctx: Context): String {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(richiesta()), 60, TimeUnit.SECONDS)
        if (r.hasResolution()) throw IllegalStateException("accesso google da rinnovare: apri classePiù, opzioni, funzioni e premi \"accedi con google\".")
        return r.accessToken ?: throw IllegalStateException("google non ha dato il permesso di invio.")
    }

    /** Indirizzo dell'account che ha dato il consenso (dal servizio userinfo di Google). */
    fun indirizzo(gettone: String): String {
        val c = URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $gettone")
        c.connectTimeout = 30_000; c.readTimeout = 30_000
        val testo = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        return runCatching { org.json.JSONObject(testo).optString("email") }.getOrDefault("")
    }

    fun indirizzoDa(r: AuthorizationResult): String = r.accessToken?.let { indirizzo(it) }.orEmpty()

    /** Spedisce la mail completa di allegati con l'endpoint di caricamento della Gmail API (fino a 35 MB). */
    fun invia(ctx: Context, destinatari: List<String>, oggetto: String, testo: String, allegati: List<FileScaricato>) {
        if (destinatari.isEmpty()) throw IllegalStateException("nessun destinatario impostato.")
        val g = gettone(ctx)
        val msg = Mailer.messaggio(Session.getInstance(Properties()), "", destinatari, oggetto, testo, allegati) // il mittente lo mette gmail
        val grezzo = ByteArrayOutputStream().also { msg.writeTo(it) }.toByteArray()
        val c = URL("https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=media").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $g")
        c.setRequestProperty("Content-Type", "message/rfc822")
        c.setFixedLengthStreamingMode(grezzo.size)
        c.outputStream.use { it.write(grezzo) }
        val codice = c.responseCode
        val risposta = (if (codice in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        if (codice !in 200..299) throw RuntimeException("gmail ha risposto (http $codice): ${risposta.take(200)}")
    }
}
