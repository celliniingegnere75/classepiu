package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Elenco degli studenti di una classe, nel formato di ClasseViva con i colori di classePiù. */
class StudentiActivity : AppCompatActivity() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = azzurro
        val nome = intent.getStringExtra("nome") ?: ""
        val tipo = intent.getStringExtra("tipo") ?: ""
        val id = intent.getStringExtra("id") ?: ""

        val chiudi = TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val titolo = TextView(this).apply {
            text = nome; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
        }
        val barra = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.view.View(this@StudentiActivity), LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        // striscia dei giorni: oggi al centro
        val giorni = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val cal = Calendar.getInstance()
        val sigle = "DLMMGVS"
        val inizio = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -3) }
        for (i in 0 until 7) {
            val g = (inizio.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val oggi = i == 3
            giorni.addView(LinearLayout(this@StudentiActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(TextView(context).apply { text = sigle[g.get(Calendar.DAY_OF_WEEK) - 1].toString(); textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                addView(TextView(context).apply {
                    text = g.get(Calendar.DAY_OF_MONTH).toString(); textSize = 18f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    setTextColor(if (oggi) azzurro else Color.WHITE)
                    if (oggi) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val data = TextView(this).apply {
            text = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALIAN).format(cal.time).uppercase()
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }

        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16)) }
        val pannello = ScrollView(this).apply {
            setBackgroundColor(0xFFF0F0F0.toInt()); addView(lista)
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra); addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(giorni, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(14) })
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        messaggio("Carico gli studenti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Impostazioni(this@StudentiActivity).client().studentiGrezzi(tipo, id) }
            }
            lista.removeAllViews()
            val (codice, corpo) = r.getOrElse { messaggio("Non riuscito: ${it.message}"); return@launch }
            val nomi = if (codice == 200) leggiStudenti(corpo) else emptyList()
            if (nomi.isEmpty()) {
                messaggio(if (codice == 200) "Risposta ricevuta ma nessuno studente riconosciuto." else "ClasseViva ha risposto con un errore (HTTP $codice).")
                lista.addView(TextView(this@StudentiActivity).apply {
                    text = "Mostra la risposta di ClasseViva (tocca per copiare)"; textSize = 14f; setTextColor(azzurro)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    setOnClickListener {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("risposta", "HTTP $codice\n$corpo"))
                        android.widget.Toast.makeText(context, "Copiata.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                })
                return@launch
            }
            nomi.forEachIndexed { i, n ->
                lista.addView(LinearLayout(this@StudentiActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                    addView(TextView(context).apply { text = "${i + 1}"; textSize = 20f; setTextColor(0xFF222222.toInt()); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(context).apply { text = n; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
        }
    }

    private fun messaggio(s: String) {
        lista.removeAllViews()
        lista.addView(TextView(this).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
    }
}
