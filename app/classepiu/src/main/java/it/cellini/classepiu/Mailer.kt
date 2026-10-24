package it.cellini.classepiu

import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.util.ByteArrayDataSource

/** Invio diretto con Gmail (smtp.gmail.com) usando una "password per le app" di Google. */
object Mailer {

    fun invia(imp: Impostazioni, oggetto: String, testo: String, allegati: List<FileScaricato> = emptyList()) {
        val destinatari = imp.destinatariEffettivi
        if (!imp.gmail.contains('@') || imp.passwordApp.isBlank()) throw IllegalStateException("Account Gmail o password per le app non impostati.")
        if (destinatari.isEmpty()) throw IllegalStateException("Nessun destinatario impostato.")

        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "30000")
            put("mail.smtp.timeout", "120000")
            put("mail.smtp.writetimeout", "120000")
        }
        val sessione = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(imp.gmail, imp.passwordApp)
        })

        val msg = messaggio(sessione, imp.gmail, destinatari, oggetto, testo, allegati)
        Transport.send(msg)
    }

    /** Mail completa (testo e allegati PDF), usata sia da SMTP sia dalla Gmail API. */
    fun messaggio(sessione: Session, mittente: String, destinatari: List<String>, oggetto: String, testo: String, allegati: List<FileScaricato>): MimeMessage {
        val msg = MimeMessage(sessione)
        if (mittente.isNotBlank()) msg.setFrom(InternetAddress(mittente, "ClassePiù", "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, destinatari.joinToString(",") { it })
        msg.setSubject(oggetto, "UTF-8")
        msg.sentDate = Date()

        val corpo = MimeMultipart()
        corpo.addBodyPart(MimeBodyPart().apply { setText(testo, "UTF-8") })
        for (a in allegati) {
            val parte = MimeBodyPart()
            val tipo = if (a.nome.lowercase().endsWith(".pdf")) "application/pdf" else "application/octet-stream"
            parte.dataHandler = DataHandler(ByteArrayDataSource(a.dati, tipo))
            parte.fileName = MimeUtility.encodeText(a.nome, "UTF-8", null)
            corpo.addBodyPart(parte)
        }
        msg.setContent(corpo)
        return msg
    }

    /**
     * Invia una circolare nel modo scelto: ricevitore Apps Script (lo stesso usato da cv.py, che la manda
     * per mail con prefisso [CLASSEVIVA]), Gmail con l'accesso Google (OAuth) oppure Gmail con password per le app.
     */
    fun inviaCircolare(ctx: android.content.Context, imp: Impostazioni, titolo: String, data: String, categoria: String, testo: String, allegati: List<FileScaricato>) {
        if (imp.invioGoogle) GoogleMail.invia(ctx, imp.destinatariEffettivi, "[CLASSEVIVA] $titolo",
            "Data: $data\n" + (if (categoria.isNotBlank()) "Categoria: $categoria\n" else "") + "\n" + testo, allegati)
        else if (imp.usaRicevitore) ricevitore(imp, titolo, data, categoria, testo, allegati)
        else invia(imp, "[CLASSEVIVA] $titolo",
            "Data: $data\n" + (if (categoria.isNotBlank()) "Categoria: $categoria\n" else "") + "\n" + testo, allegati)
    }

    private fun ricevitore(imp: Impostazioni, titolo: String, data: String, categoria: String, testo: String, allegati: List<FileScaricato>) {
        if (!imp.ricevitoreUrl.startsWith("https://") || imp.ricevitoreCodice.isBlank())
            throw IllegalStateException("Indirizzo o codice del ricevitore Apps Script non impostati.")
        val corpo = org.json.JSONObject()
            .put("titolo", titolo).put("data", data).put("categoria", categoria).put("testo", testo)
            .put("token", imp.ricevitoreCodice)
            .put("allegati", org.json.JSONArray().apply {
                allegati.forEach {
                    put(org.json.JSONObject().put("nome", it.nome)
                        .put("base64", android.util.Base64.encodeToString(it.dati, android.util.Base64.NO_WRAP)))
                }
            })
        val c = java.net.URL(imp.ricevitoreUrl).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.instanceFollowRedirects = true // Apps Script risponde dopo un redirect
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(corpo.toString().toByteArray()) }
        val codice = c.responseCode
        val risposta = (if (codice in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        if (!risposta.replace(" ", "").contains("\"ok\":true"))
            throw RuntimeException("Il ricevitore ha risposto (HTTP $codice): ${risposta.take(200)}")
    }

    /** Testo della mail di una circolare, con in testa le azioni richieste. */
    // Identico a prepara() di cv.py: note in testa, una per riga, poi una riga vuota e il testo.
    fun testoCircolare(c: Circolare, scaricati: Int): String {
        val note = mutableListOf<String>()
        if (c.azioni.isNotEmpty()) note += "AZIONE RICHIESTA su ClasseViva: " + c.azioni.joinToString(", ") + "."
        if (c.adesione) note += "Sull'app è possibile aderire (pulsante Aderisci)."
        val attesi = c.allegati.size
        if (attesi > scaricati) note += "Allegati non ancora disponibili: ${attesi - scaricati} su $attesi. " +
            "Arriveranno con una mail successiva appena ClasseViva li rende scaricabili."
        if (note.isEmpty()) return c.testo
        return note.joinToString("\n") + if (c.testo.isNotEmpty()) "\n\n" + c.testo else ""
    }
}
