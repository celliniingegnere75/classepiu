package it.cellini.classepiu

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni come la bozza: Profilo, ricevitore/token, tre interruttori, intervallo, invio dell'ultima circolare. */
class ImpostazioniFragment : Fragment() {

    private val nero = 0xFF111111.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val imp = Impostazioni(ctx)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(15), ctx.dp(12), ctx.dp(15), ctx.dp(24))
        }

        // --- Profilo + esci o cambia account
        val profilo = FrameLayout(ctx).apply {
            clipChildren = false
            addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(azzurro) } },
                FrameLayout.LayoutParams(ctx.dp(60), ctx.dp(60), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = ctx.dp(6) })
            addView(TextView(ctx).apply {
                text = "Profilo"; textSize = 23f; setTypeface(typeface, Typeface.BOLD); setTextColor(nero)
                maxLines = 1
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }
        val esci = pillola(ctx, "esci o cambia account", 22f, Color.WHITE, azzurro, ctx.dp(64)).apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage("Accesso come ${imp.utente}.\nUscire dall'account ClasseViva? Il controllo automatico si ferma finché non accedi di nuovo.")
                    .setPositiveButton("Esci") { _, _ ->
                        imp.password = ""
                        imp.controlloAutomatico = false
                        Controllo.pianifica(ctx)
                        startActivity(Intent(ctx, AccessoActivity::class.java))
                        requireActivity().finish()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(profilo, LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(64)))
            addView(esci, LinearLayout.LayoutParams(0, ctx.dp(64), 1f).apply { leftMargin = ctx.dp(10) })
        }, piena())

        // --- ricevitore e token
        val ricevitore = campoSottolineato(ctx, "ricevitore", password = false).apply { setText(imp.ricevitoreUrlPersonale) }
        val token = campoSottolineato(ctx, "token", password = true).apply { setText(imp.ricevitoreCodicePersonale) }
        col.addView(ricevitore, piena().apply { topMargin = ctx.dp(34) })
        col.addView(token, piena().apply { topMargin = ctx.dp(10) })
        val salva = pillola(ctx, "salva credenziali", 19f, 0xFF333333.toInt(), azzurro, ctx.dp(24)).apply {
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
            setOnClickListener {
                imp.ricevitoreUrl = ricevitore.text.toString()
                imp.ricevitoreCodice = token.text.toString()
                imp.usaRicevitore = true
                messaggio(if (ricevitore.text.isBlank() && token.text.isBlank()) "Salvato: si usa il ricevitore già inserito nell'app." else "Credenziali del ricevitore salvate.")
            }
        }
        col.addView(salva, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(24)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(14)
        })

        // --- interruttori (si salvano da soli)
        riga(ctx, col, "invia per mail le circolari nuove", imp.invioMail, ctx.dp(44)) { on ->
            imp.invioMail = on
            messaggio(if (on) "Invio mail attivato." else "Invio mail disattivato.")
        }
        riga(ctx, col, "segna come lette quelle inviate", imp.segnaLette, ctx.dp(18)) { on ->
            imp.segnaLette = on
            messaggio(if (on) "Le circolari inviate verranno segnate come lette." else "Le circolari inviate resteranno da leggere.")
        }
        riga(ctx, col, "controllo periodico della bacheca ogni:", imp.controlloAutomatico, ctx.dp(18)) { on ->
            imp.controlloAutomatico = on
            Controllo.pianifica(ctx)
            messaggio(if (on) "Controllo periodico attivato." else "Controllo periodico disattivato.")
        }

        // --- intervallo: 2, 4, 8, 24 ore
        val gruppo = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL; setPadding(ctx.dp(8), 0, 0, 0) }
        val scelta = if (imp.oreIntervallo in ORE) imp.oreIntervallo else 2
        ORE.forEach { h ->
            gruppo.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = "$h ore"; tag = h; textSize = 19f; setTextColor(nero)
                buttonTintList = ColorStateList.valueOf(azzurro)
                isChecked = h == scelta
                setPadding(ctx.dp(4), 0, ctx.dp(10), 0)
            })
        }
        gruppo.setOnCheckedChangeListener { g, id ->
            imp.oreIntervallo = (g.findViewById<RadioButton>(id)?.tag as? Int) ?: 2
            Controllo.pianifica(ctx)
            messaggio("Controllo ogni ${imp.oreIntervallo} ore.")
        }
        col.addView(gruppo, piena().apply { topMargin = ctx.dp(10) })

        // --- invia subito l'ultima circolare
        val ultima = pillola(ctx, "Invia subito l'ultima circolare!", 20f, 0xFF333333.toInt(), azzurro, ctx.dp(32)).apply {
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            setOnClickListener {
                esegui("Ultima circolare") {
                    val cv = Impostazioni(ctx).client()
                    cv.login()
                    val c = cv.bacheca().firstOrNull() ?: throw IllegalStateException("Bacheca vuota.")
                    val n = Controllo.inviaOra(ctx.applicationContext, c)
                    "Inviata: ${c.titolo}\nAllegati: $n su ${c.allegati.size}."
                }
            }
        }
        col.addView(ultima, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)).apply { topMargin = ctx.dp(28) })

        return ScrollView(ctx).apply { setBackgroundColor(Color.WHITE); addView(col) }
    }

    /** Pulsante a pillola azzurro. */
    private fun pillola(ctx: android.content.Context, s: String, size: Float, coloreTesto: Int, sfondo: Int, altezza: Int) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(coloreTesto)
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        background = GradientDrawable().apply { setColor(sfondo); cornerRadius = altezza / 2f }
        isClickable = true
        isFocusable = true
    }

    /** Campo con scritta grande in grassetto e linea sotto, come "ricevitore" e "token" nella bozza. */
    private fun campoSottolineato(ctx: android.content.Context, etichetta: String, password: Boolean) = EditText(ctx).apply {
        hint = etichetta
        setHintTextColor(nero)
        setTextColor(nero)
        textSize = 15f
        setSingleLine(true)
        minHeight = ctx.dp(72)
        gravity = Gravity.CENTER_VERTICAL
        backgroundTintList = ColorStateList.valueOf(nero)
        setPadding(ctx.dp(0), ctx.dp(8), ctx.dp(0), ctx.dp(8))
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        // scritta del campo vuoto grande e in grassetto, testo inserito più piccolo
        val aggiorna = { textSize = if (text.isNullOrEmpty()) 25f else 15f; setTypeface(null, if (text.isNullOrEmpty()) Typeface.BOLD else Typeface.NORMAL) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { aggiorna() }
        })
        aggiorna()
    }

    /** Riga con testo a sinistra e interruttore azzurro a destra; si salva appena lo tocchi. */
    private fun riga(ctx: android.content.Context, col: LinearLayout, testo: String, acceso: Boolean, sopra: Int, cambia: (Boolean) -> Unit): MaterialSwitch {
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val sw = MaterialSwitch(ctx).apply {
            isChecked = acceso
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(azzurro, 0xFFE3E3E3.toInt()))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackDecorationTintList = ColorStateList.valueOf(0xFF333333.toInt())
            setOnCheckedChangeListener { _, on -> cambia(on) }
        }
        val t = TextView(ctx).apply { text = testo; textSize = 19f; setTextColor(nero) }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { sw.toggle() }
        }, piena().apply { topMargin = sopra })
        return sw
    }

    private fun messaggio(s: String) {
        view?.let { Snackbar.make(it, s, Snackbar.LENGTH_SHORT).show() }
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
        }
    }

    companion object {
        val ORE = listOf(2, 4, 8, 24)
    }
}
