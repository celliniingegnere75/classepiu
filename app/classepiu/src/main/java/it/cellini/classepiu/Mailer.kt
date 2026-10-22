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

        val msg = MimeMessage(sessione)
        msg.setFrom(InternetAddress(imp.gmail, "ClassePiù", "UTF-8"))
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
        Transport.send(msg)
    }

    /** Testo della mail di una circolare, con in testa le azioni richieste. */
    fun testoCircolare(c: Circolare, scaricati: Int): String = buildString {
        if (c.azioni.isNotEmpty()) append("AZIONE RICHIESTA su ClasseViva: ").append(c.azioni.joinToString(", ")).append(".\n")
        if (c.adesione) append("Sull'app è possibile aderire (pulsante Aderisci).\n")
        val attesi = c.allegati.size
        if (attesi > scaricati) append("Allegati non ancora disponibili: ${attesi - scaricati} su $attesi. Arriveranno con una mail successiva appena ClasseViva li rende scaricabili.\n")
        if (isNotEmpty()) append("\n")
        append("Data: ").append(c.data).append('\n')
        if (c.categoria.isNotBlank()) append("Categoria: ").append(c.categoria).append('\n')
        append('\n').append(c.testo.ifBlank { "(nessun testo)" })
        append("\n\n— inviata da ClassePiù")
    }
}
