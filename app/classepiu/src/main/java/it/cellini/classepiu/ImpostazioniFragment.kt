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
        col.addView(ctx.testo("Accesso come ${imp.utente}\nLe credenziali restano solo su questo telefono, cifrate.", 13f, alpha = .7f))
        col.addView(ctx.bottone("Esci / cambia account", pieno = false) {
            MaterialAlertDialogBuilder(ctx)
                .setMessage("Uscire dall'account ClasseViva? Il controllo automatico si ferma finché non accedi di nuovo.")
                .setPositiveButton("Esci") { _, _ ->
                    imp.password = ""
                    imp.controlloAutomatico = false
                    Controllo.pianifica(ctx)
                    startActivity(Intent(ctx, AccessoActivity::class.java))
                    requireActivity().finish()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }.apply { layoutParams = piena() })

        // --- Mail
        col.addView(ctx.titoloSezione("Invio delle circolari per mail"))
        val modo = RadioGroup(ctx)
        val rbRic = RadioButton(ctx).apply { id = View.generateViewId(); text = "Ricevitore Apps Script (nessuna password, consigliato)" }
        val rbGmail = RadioButton(ctx).apply { id = View.generateViewId(); text = "Gmail diretto (serve la password per le app)" }
        modo.aggiungi(rbRic, rbGmail)
        col.addView(modo)

        val boxRic = ctx.colonna(0)
        boxRic.addView(ctx.testo(
            "Lo stesso ricevitore che usa Termux: manda la circolare con i PDF alla mail della scuola. " +
                "Indirizzo e codice li vedi in Termux con:  cat ~/.classeviva/config.json  (voci endpoint e token).",
            13f, alpha = .7f))
        val ricUrl = Campo(ctx, "Indirizzo del ricevitore (https://script.google.com/…)").apply { valore = imp.ricevitoreUrl }
        val ricCod = Campo(ctx, "Codice segreto (token)", password = true).apply { valore = imp.ricevitoreCodice }
        boxRic.aggiungi(ricUrl.layout, ricCod.layout)

        val boxGmail = ctx.colonna(0)
        boxGmail.addView(ctx.testo(
            "Le mail partono da un account Gmail con una \"password per le app\": Google la offre solo se sull'account " +
                "è attiva la verifica in due passaggi, e sugli account della scuola può essere disattivata dall'amministratore. " +
                "Se lasci vuoti i destinatari, le circolari vanno alla mail del profilo ClasseViva.", 13f, alpha = .7f))
        val gmail = Campo(ctx, "Account Gmail mittente", email = true).apply { valore = imp.gmail }
        val passApp = Campo(ctx, "Password per le app (16 lettere)", password = true).apply { valore = imp.passwordApp }
        val dest = Campo(ctx, "Destinatari (separati da virgola)", email = true).apply {
            valore = imp.destinatari.ifBlank { if (imp.utente.contains('@')) imp.utente else "" }
        }
        boxGmail.aggiungi(gmail.layout, passApp.layout, dest.layout)
        boxGmail.addView(ctx.bottone("Attiva la verifica in due passaggi", pieno = false) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/signinoptions/twosv"))) }
        }.apply { layoutParams = piena() })
        boxGmail.addView(ctx.bottone("Crea la password per le app", pieno = false) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
        }.apply { layoutParams = piena() })
        col.aggiungi(boxRic, boxGmail)

        fun aggiornaModo() {
            boxRic.visibility = if (rbRic.isChecked) View.VISIBLE else View.GONE
            boxGmail.visibility = if (rbGmail.isChecked) View.VISIBLE else View.GONE
        }
        (if (imp.usaRicevitore) rbRic else rbGmail).isChecked = true
        aggiornaModo()
        modo.setOnCheckedChangeListener { _, _ -> aggiornaModo() }

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
            imp.gmail = gmail.valore
            imp.passwordApp = passApp.valore
            imp.destinatari = dest.valore
            imp.usaRicevitore = rbRic.isChecked
            imp.ricevitoreUrl = ricUrl.valore
            imp.ricevitoreCodice = ricCod.valore
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
                val oggi = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALY).format(java.util.Date())
                Mailer.inviaCircolare(i, "Prova ClassePiù", oggi, "Prova", "Mail di prova inviata dall'app ClassePiù.\nSe la leggi, l'invio delle circolari funziona.", emptyList())
                "Mail inviata (${i.doveVanno})."
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
