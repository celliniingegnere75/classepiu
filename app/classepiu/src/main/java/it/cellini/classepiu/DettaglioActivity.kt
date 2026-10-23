package it.cellini.classepiu

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class DettaglioActivity : AppCompatActivity() {

    private lateinit var c: Circolare

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = circolareDa(JSONObject(intent.getStringExtra("circolare") ?: "{}"))

        val barra = MaterialToolbar(this).apply {
            title = if (c.numero.isNotEmpty()) "Circolare n. ${c.numero}" else "Circolare"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        val col = colonna()
        col.addView(testo(c.titolo, 19f, true))
        val info = listOf(dataBreve(c.data), c.categoria).filter { it.isNotBlank() }.joinToString(" · ")
        col.addView(testo(info, 13f, alpha = .7f).apply { setPadding(0, dp(4), 0, dp(8)) })

        if (c.azioni.isNotEmpty()) {
            val avviso = colonna(12)
            val cosa = c.azioni.joinToString(", ")
            avviso.addView(testo("Azione richiesta: $cosa", 15f, true).apply { setTextColor(ContextCompat.getColor(context, R.color.azione)) })
            avviso.addView(testo("La conferma si fa sul sito ClasseViva: premi il pulsante (l'accesso è automatico) e conferma la circolare in Bacheca.", 14f))
            avviso.addView(bottone("Apri ClasseViva nel browser") {
                startActivity(Intent(this, WebActivity::class.java))
            })
            col.addView(MaterialCardView(this).apply {
                addView(avviso)
                layoutParams = piena().apply { bottomMargin = dp(12) }
            })
        }

        val corpo = if (c.testo.contains('<')) HtmlCompat.fromHtml(c.testo, HtmlCompat.FROM_HTML_MODE_COMPACT) else c.testo
        col.addView(testo(corpo.ifEmpty { "(nessun testo)" }, 15.5f).apply { setTextIsSelectable(true) })

        if (c.allegati.isNotEmpty()) {
            col.addView(titoloSezione("Allegati"))
            c.allegati.forEach { a ->
                col.addView(TextView(this).apply {
                    text = "📎 ${a.nome}"; textSize = 15f
                    setTextColor(ContextCompat.getColor(context, R.color.primario))
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { apriAllegato(a) }
                }, piena())
            }
        }

        col.addView(titoloSezione("Azioni"))
        col.addView(bottone("Invia per mail ora") { inviaOra() }.apply { layoutParams = piena() })
        val pref = getSharedPreferences("app", MODE_PRIVATE)
        val chiaveLetta = "letta_app_${c.chiave}"
        val letta = TextView(this).apply {
            text = "Segna come letta"; textSize = 16f; gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            fun colora() = setTextColor(if (pref.getBoolean(chiaveLetta, false)) 0xFF9E9E9E.toInt() else ContextCompat.getColor(context, R.color.primario))
            colora()
            setOnClickListener {
                pref.edit().putBoolean(chiaveLetta, true).apply()
                colora()
                segnaLetta()
            }
        }
        col.addView(letta, piena())
        if (c.adesione) {
            val chiaveAdesione = "adesione_app_${c.chiave}"
            val aderisci = TextView(this).apply {
                text = "Aderisci"; textSize = 16f; gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
                fun colora() = setTextColor(if (pref.getBoolean(chiaveAdesione, false)) 0xFF9E9E9E.toInt() else ContextCompat.getColor(context, R.color.primario))
                colora()
                setOnClickListener {
                    if (pref.getBoolean(chiaveAdesione, false)) return@setOnClickListener
                    pref.edit().putBoolean(chiaveAdesione, true).putBoolean(chiaveLetta, true).apply()
                    colora(); letta.setTextColor(0xFF9E9E9E.toInt())
                    aderisci()
                }
            }
            col.addView(aderisci, piena())
        }

        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barra)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun avviso(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun apriAllegato(a: Allegato) {
        avviso("Scarico ${a.nome}…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = Impostazioni(this@DettaglioActivity).client()
                    cv.login()
                    cv.bacheca()
                    var f = cv.allegato(c, a)
                    if (f == null) { cv.segnaLetta(c); f = cv.allegato(c, a) }
                    f ?: throw IllegalStateException("ClasseViva non rende ancora disponibile l'allegato.")
                    val dir = File(cacheDir, "allegati").apply { mkdirs() }
                    File(dir, f.nome.replace(Regex("[\\\\/:*?\"<>|]"), "_")).apply { writeBytes(f.dati) }
                }
            }
            r.onSuccess { file ->
                val uri = FileProvider.getUriForFile(this@DettaglioActivity, "$packageName.files", file)
                val tipo = if (file.name.lowercase().endsWith(".pdf")) "application/pdf" else "*/*"
                val vedi = Intent(Intent.ACTION_VIEW).setDataAndType(uri, tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(Intent.createChooser(vedi, file.name))
                } catch (e: ActivityNotFoundException) {
                    avviso("Nessuna app per aprire il file.")
                }
            }.onFailure { avviso(it.message ?: "Errore") }
        }
    }

    private fun inviaOra() {
        val imp = Impostazioni(this)
        if (!imp.mailPronta) { avviso("Imposta prima l'invio delle mail in Impostazioni."); return }
        avviso("Invio in corso…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Controllo.inviaOra(applicationContext, c) } }
            r.onSuccess { avviso("Inviata (${imp.doveVanno}, allegati $it/${c.allegati.size}).") }
                .onFailure { avviso("Invio non riuscito: ${it.message}") }
        }
    }

    private fun aderisci() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = Impostazioni(this@DettaglioActivity).client()
                    cv.login(); cv.bacheca(); cv.segnaLetta(c, aderisci = true)
                }
            }
            avviso(if (r.getOrDefault(false)) "Adesione registrata su ClasseViva." else "Adesione non riuscita: ${r.exceptionOrNull()?.message ?: "risposta inattesa del server"}")
        }
    }

    private fun segnaLetta() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = Impostazioni(this@DettaglioActivity).client()
                    cv.login(); cv.bacheca(); cv.segnaLetta(c)
                }
            }
            avviso(if (r.getOrDefault(false)) "Segnata come letta." else "Non riuscito: ${r.exceptionOrNull()?.message ?: "risposta inattesa"}")
        }
    }
}
