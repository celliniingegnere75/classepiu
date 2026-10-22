package it.cellini.classepiu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImpostazioniFragment : Fragment() {

    private lateinit var diario: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val imp = Impostazioni(ctx)
        val col = ctx.colonna()

        // --- ClasseViva
        col.addView(ctx.titoloSezione("Account ClasseViva"))
        col.addView(ctx.testo("Le credenziali restano solo su questo telefono, cifrate.", 13f, alpha = .7f))
        val utente = Campo(ctx, "Utente (codice o mail della scuola)", email = true).apply { valore = imp.utente }
        val password = Campo(ctx, "Password ClasseViva", password = true).apply { valore = imp.password }
        col.aggiungi(utente.layout, password.layout)

        // --- Mail
        col.addView(ctx.titoloSezione("Invio delle circolari per mail (Gmail)"))
        col.addView(ctx.testo(
            "Le mail partono direttamente da un account Gmail. Serve una \"password per le app\" di Google " +
                "(myaccount.google.com → Sicurezza → Verifica in due passaggi → Password per le app). " +
                "Se lasci vuoti i destinatari, le circolari vanno alla mail del profilo ClasseViva.", 13f, alpha = .7f))
        val gmail = Campo(ctx, "Account Gmail mittente", email = true).apply { valore = imp.gmail }
        val passApp = Campo(ctx, "Password per le app (16 lettere)", password = true).apply { valore = imp.passwordApp }
        val dest = Campo(ctx, "Destinatari (separati da virgola)", email = true).apply {
            valore = imp.destinatari.ifBlank { if (imp.utente.contains('@')) imp.utente else "" }
        }
        col.aggiungi(gmail.layout, passApp.layout, dest.layout)
        col.addView(ctx.bottone("Come creare la password per le app", pieno = false) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
        }.apply { layoutParams = piena() })

        val invioMail = MaterialSwitch(ctx).apply { text = "Invia per mail le circolari nuove"; isChecked = imp.invioMail }
        val segna = MaterialSwitch(ctx).apply { text = "Segna come lette quelle inviate (non quelle da firmare)"; isChecked = imp.segnaLette }
        col.aggiungi(invioMail, segna)

        // --- Controllo automatico
        col.addView(ctx.titoloSezione("Controllo automatico"))
        val auto = MaterialSwitch(ctx).apply { text = "Controlla la bacheca in background"; isChecked = imp.controlloAutomatico }
        col.addView(auto)
        col.addView(ctx.testo("Ogni:", 14f).apply { setPadding(0, ctx.dp(8), 0, 0) })
        val ore = listOf(1, 2, 4, 8)
        val gruppo = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        ore.forEach { h ->
            gruppo.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = "$h ${if (h == 1) "ora" else "ore"}"; tag = h
                isChecked = h == imp.oreIntervallo
            })
        }
        col.addView(gruppo)
        col.addView(ctx.bottone("Escludi dall'ottimizzazione batteria", pieno = false) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }.apply { layoutParams = piena() })

        fun salva() {
            imp.utente = utente.valore
            imp.password = password.valore
            imp.gmail = gmail.valore
            imp.passwordApp = passApp.valore
            imp.destinatari = dest.valore
            imp.invioMail = invioMail.isChecked
            imp.segnaLette = segna.isChecked
            imp.controlloAutomatico = auto.isChecked
            imp.oreIntervallo = (gruppo.findViewById<RadioButton>(gruppo.checkedRadioButtonId)?.tag as? Int) ?: 2
            Controllo.pianifica(ctx)
        }

        col.addView(ctx.titoloSezione("Salva e prova"))
        col.addView(ctx.bottone("Salva impostazioni") { salva(); messaggio("Salvate.") }.apply { layoutParams = piena() })
        col.addView(ctx.bottone("Prova accesso ClasseViva", pieno = false) {
            salva()
            esegui("Prova accesso") {
                val cv = Impostazioni(ctx).client()
                cv.login()
                val b = cv.bacheca()
                "Accesso riuscito (profilo ${cv.ident}).\nCircolari in bacheca: ${b.size}.\nUltima: ${b.firstOrNull()?.titolo ?: "-"}"
            }
        }.apply { layoutParams = piena() })
        col.addView(ctx.bottone("Manda una mail di prova", pieno = false) {
            salva()
            esegui("Mail di prova") {
                val i = Impostazioni(ctx)
                Mailer.invia(i, "[CLASSEVIVA] Prova ClassePiù", "Mail di prova inviata dall'app ClassePiù.\nSe la leggi, l'invio delle circolari funziona.")
                "Mail inviata a ${i.destinatariEffettivi.joinToString(", ")}."
            }
        }.apply { layoutParams = piena() })
        col.addView(ctx.bottone("Invia subito l'ultima circolare", pieno = false) {
            salva()
            esegui("Ultima circolare") {
                val cv = Impostazioni(ctx).client()
                cv.login()
                val c = cv.bacheca().firstOrNull() ?: throw IllegalStateException("Bacheca vuota.")
                val n = Controllo.inviaOra(ctx.applicationContext, c)
                "Inviata: ${c.titolo}\nAllegati: $n su ${c.allegati.size}."
            }
        }.apply { layoutParams = piena() })

        // --- Diario
        col.addView(ctx.titoloSezione("Registro attività"))
        diario = ctx.testo(Diario.leggi(ctx), 12.5f).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(ctx.bottone("Aggiorna", pieno = false) { diario.text = Diario.leggi(ctx) })
        col.addView(diario)
        col.addView(ctx.testo("\nClassePiù ${BuildConfig.VERSION_NAME}", 12f, alpha = .6f))

        return ScrollView(ctx).apply { addView(col) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::diario.isInitialized) diario.text = Diario.leggi(requireContext())
    }

    private fun messaggio(s: String) {
        view?.let { com.google.android.material.snackbar.Snackbar.make(it, s, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() }
    }

    private fun esegui(titolo: String, lavoro: () -> String) {
        val ctx = requireContext()
        messaggio("$titolo in corso…")
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(lavoro) }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(ctx)
                .setTitle(titolo)
                .setMessage(r.getOrElse { "Non riuscito:\n${it.message ?: it.javaClass.simpleName}" })
                .setPositiveButton("OK", null)
                .show()
            diario.text = Diario.leggi(ctx)
        }
    }
}
