package it.cellini.classepiu

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unica schermata da compilare: mail e password ClasseViva.
 * Dopo l'accesso l'app imposta da sola tutto il resto (come cv.py dopo "credenziali").
 */
class AccessoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imp = Impostazioni(this)
        val col = colonna(24).apply { gravity = Gravity.CENTER_HORIZONTAL }

        col.addView(testo("ClassePiù", 30f, true).apply { setPadding(0, dp(48), 0, dp(4)) })
        col.addView(testo("Accedi con l'account ClasseViva", 15f, alpha = .7f).apply { setPadding(0, 0, 0, dp(24)) })
        val utente = Campo(this, "Mail o codice utente", email = true).apply { valore = imp.utente }
        val password = Campo(this, "Password", password = true)
        val esito = TextView(this).apply { setPadding(0, dp(12), 0, 0); textSize = 14f }
        val attesa = ProgressBar(this).apply { visibility = View.GONE }
        col.aggiungi(utente.layout, password.layout)

        val accedi = bottone("Accedi") {}
        accedi.layoutParams = piena().apply { topMargin = dp(16) }
        accedi.setOnClickListener {
            val u = utente.valore.trim()
            val p = password.valore
            if (u.isEmpty() || p.isEmpty()) { esito.text = "Inserisci mail e password."; return@setOnClickListener }
            accedi.isEnabled = false
            attesa.visibility = View.VISIBLE
            esito.text = "Accesso in corso…"
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val cv = ClasseViva(u, p)
                        cv.login()
                        cv.bacheca().size
                    }
                }
                attesa.visibility = View.GONE
                accedi.isEnabled = true
                r.onSuccess { n ->
                    configura(u, p)
                    esito.text = "Accesso riuscito: $n circolari in bacheca."
                    startActivity(Intent(this@AccessoActivity, MainActivity::class.java))
                    finish()
                }.onFailure { esito.text = "Accesso non riuscito.\n${it.message}" }
            }
        }
        col.aggiungi(accedi, attesa, esito)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /** Imposta tutto il resto in automatico. */
    private fun configura(utente: String, password: String) {
        val imp = Impostazioni(this)
        val cambiato = imp.utente.isNotEmpty() && !imp.utente.equals(utente, ignoreCase = true)
        imp.utente = utente
        imp.password = password
        if (imp.destinatari.isBlank() && utente.contains('@')) imp.destinatari = utente
        imp.invioMail = true
        imp.segnaLette = true
        imp.controlloAutomatico = true
        if (imp.oreIntervallo !in listOf(1, 2, 4, 8)) imp.oreIntervallo = 2
        if (cambiato) Viste(this).cancella() // nuovo profilo: si riparte come al primo avvio
        Controllo.pianifica(this)
        // primo controllo subito: al primo avvio segna come già viste le circolari presenti, senza inviarle
        val app = applicationContext
        Thread { runCatching { Controllo.esegui(app) } }.start()
    }
}
