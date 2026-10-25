package it.cellini.classepiu

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
            title = if (c.numero.isNotEmpty()) "circolare n. ${c.numero}" else "circolare"
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
            avviso.addView(testo("azione richiesta: $cosa", 15f, true).apply { setTextColor(ContextCompat.getColor(context, R.color.azione)) })
            avviso.addView(testo("la conferma si fa sul sito classeviva: premi il pulsante (l'accesso è automatico) e conferma la circolare in bacheca.", 14f))
            avviso.addView(bottone("apri classeviva nel browser") {
                startActivity(Intent(this, WebActivity::class.java))
            })
            col.addView(MaterialCardView(this).apply {
                setCardBackgroundColor(Color.WHITE)
                addView(avviso)
                layoutParams = piena().apply { bottomMargin = dp(12) }
            })
        }

        val corpo = if (c.testo.contains('<')) HtmlCompat.fromHtml(c.testo, HtmlCompat.FROM_HTML_MODE_COMPACT) else c.testo
        col.addView(testo(corpo.ifEmpty { "(nessun testo)" }, 15.5f).apply { setTextIsSelectable(true) })

        if (c.allegati.isNotEmpty()) {
            col.addView(titoloSezione("allegati"))
            c.allegati.forEach { a ->
                col.addView(TextView(this).apply {
                    text = "📎 ${a.nome}"; textSize = 15f
                    setTextColor(ContextCompat.getColor(context, R.color.primario))
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { apriAllegato(a) }
                }, piena())
            }
        }

        col.addView(titoloSezione("azioni"))
        col.addView(bottone("invia per mail", icona = android.R.drawable.ic_dialog_email) { inviaOra() }.apply { layoutParams = piena() })
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

        // in fondo spazio per la barra delle pagine, che resta sopra al testo
        col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, col.paddingBottom + dp(SPAZIO_BARRA))
        val scroll = ScrollView(this).apply { addView(col) }
        conBarra(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            addView(barra)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, MainActivity.TAB_BACHECA)
    }

    private fun avviso(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun apriAllegato(a: Allegato) {
        avviso("scarico ${a.nome}…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cv = Impostazioni(this@DettaglioActivity).client()
                    cv.login()
                    cv.bacheca()
                    var f = cv.allegato(c, a)
                    if (f == null) { cv.segnaLetta(c); f = cv.allegato(c, a) }
                    f ?: throw IllegalStateException("classeviva non rende ancora disponibile l'allegato.")
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
                    avviso("nessuna app per aprire il file.")
                }
            }.onFailure { avviso(it.message ?: "errore") }
        }
    }

    private fun inviaOra() {
        val imp = Impostazioni(this)
        if (!imp.mailPronta) { avviso(imp.coseMancanti); return }
        avviso("invio in corso…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Controllo.inviaOra(applicationContext, c) } }
            r.onSuccess { avviso("inviata (${imp.doveVanno}, allegati $it/${c.allegati.size}).") }
                .onFailure { errore ->
                    val testo = errore.message ?: errore.toString()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@DettaglioActivity)
                        .setTitle("invio non riuscito")
                        .setMessage(testo)
                        .setPositiveButton("copia") { _, _ ->
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("errore", testo))
                            avviso("errore copiato.")
                        }
                        .setNegativeButton("chiudi", null)
                        .show()
                }
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
            avviso(if (r.getOrDefault(false)) "adesione registrata su classeviva." else "adesione non riuscita: ${r.exceptionOrNull()?.message ?: "risposta inattesa del server"}")
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
            avviso(if (r.getOrDefault(false)) "segnata come letta." else "non riuscito: ${r.exceptionOrNull()?.message ?: "risposta inattesa"}")
        }
    }
}
