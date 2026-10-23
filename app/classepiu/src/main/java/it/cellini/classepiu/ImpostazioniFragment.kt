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
    private var aggiornaOre: (Boolean) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val imp = Impostazioni(ctx)
        val azzurro = ContextCompat.getColor(ctx, R.color.logo_sfondo)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(ctx.dp(15), ctx.dp(12), ctx.dp(15), ctx.dp(24))
        }

        // --- Profilo: cerchio con sagoma di persona, nome e account; sotto la pillola per uscire
        val avatar = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(azzurro) }
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_profilo) },
                FrameLayout.LayoutParams(ctx.dp(34), ctx.dp(34), Gravity.CENTER))
        }
        val nomeProfilo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = "profilo"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(nero) })
            addView(TextView(ctx).apply {
                text = imp.utente; textSize = 13f; setTextColor(0xFF666666.toInt()); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
            addView(nomeProfilo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(14) })
        }, piena())
        val esci = pillola(ctx, "esci o cambia account", 17f, Color.WHITE, azzurro, ctx.dp(44)).apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage("uscire dall'account?")
                    .setPositiveButton("conferma") { _, _ ->
                        imp.password = ""
                        imp.controlloAutomatico = false
                        Controllo.pianifica(ctx)
                        startActivity(Intent(ctx, AccessoActivity::class.java))
                        requireActivity().finish()
                    }
                    .setNegativeButton("annulla", null)
                    .show()
            }
        }
        col.addView(esci, piena().apply { height = ViewGroup.LayoutParams.WRAP_CONTENT; topMargin = ctx.dp(16) })

        // --- ricevitore e token
        val ricevitore = campoSottolineato(ctx, "ricevitore", password = false).apply { setText(imp.ricevitoreUrlPersonale) }
        val token = campoSottolineato(ctx, "token", password = true).apply { setText(imp.ricevitoreCodicePersonale) }
        col.addView(ricevitore, piena().apply { topMargin = ctx.dp(20) })
        col.addView(token, piena().apply { topMargin = ctx.dp(4) })
        val salva = pillola(ctx, "salva", 16f, Color.WHITE, azzurro, ctx.dp(38)).apply {
            setPadding(ctx.dp(28), ctx.dp(6), ctx.dp(28), ctx.dp(6))
            setOnClickListener {
                imp.ricevitoreUrl = ricevitore.text.toString()
                imp.ricevitoreCodice = token.text.toString()
                imp.usaRicevitore = true
                messaggio(if (ricevitore.text.isBlank() && token.text.isBlank()) "salvato: si usa il ricevitore già inserito nell'app." else "credenziali del ricevitore salvate.")
            }
        }
        col.addView(salva, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(16)
        })

        // --- interruttori (si salvano da soli)
        riga(ctx, col, "invia per mail le circolari nuove", imp.invioMail, ctx.dp(26)) { on ->
            imp.invioMail = on
            messaggio(if (on) "invio mail attivato." else "invio mail disattivato.")
        }
        riga(ctx, col, "segna come lette quelle inviate", imp.segnaLette, ctx.dp(20)) { on ->
            imp.segnaLette = on
            messaggio(if (on) "le circolari inviate verranno segnate come lette." else "le circolari inviate resteranno da leggere.")
        }
        riga(ctx, col, "controllo periodico della bacheca ogni:", imp.controlloAutomatico, ctx.dp(20)) { on ->
            imp.controlloAutomatico = on
            Controllo.pianifica(ctx)
            aggiornaOre(on)
            messaggio(if (on) "controllo periodico attivato." else "controllo periodico disattivato.")
        }

        // --- intervallo: 2, 4, 8, 24 ore (grigio e non selezionabile se il controllo periodico è spento)
        val gruppo = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL; setPadding(0, 0, 0, 0) }
        aggiornaOre = { acceso ->
            for (i in 0 until gruppo.childCount) (gruppo.getChildAt(i) as RadioButton).apply {
                isEnabled = acceso
                setTextColor(if (acceso) nero else 0xFFA8A8A8.toInt())
                buttonTintList = ColorStateList.valueOf(if (acceso) azzurro else 0xFFC4C4C4.toInt())
            }
        }
        val scelta = if (imp.oreIntervallo in ORE) imp.oreIntervallo else 2
        ORE.forEach { h ->
            gruppo.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); text = "$h ore"; tag = h; textSize = 14f; setTextColor(nero); setSingleLine(true); gravity = Gravity.CENTER_VERTICAL
                buttonTintList = ColorStateList.valueOf(azzurro)
                isChecked = h == scelta
                setPadding(ctx.dp(2), 0, 0, 0)
                // le quattro scelte si dividono la riga: nessuna resta tagliata con il solo cerchietto
                layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        gruppo.setOnCheckedChangeListener { g, id ->
            imp.oreIntervallo = (g.findViewById<RadioButton>(id)?.tag as? Int) ?: 2
            Controllo.pianifica(ctx)
        }
        // la riga parte più a sinistra (cerchietto allineato alle scritte) e guadagna spazio a destra: "24 ore" non si taglia
        col.addView(gruppo, piena().apply { topMargin = ctx.dp(10); leftMargin = -ctx.dp(12) })
        aggiornaOre(imp.controlloAutomatico)

        return ScrollView(ctx).apply { setBackgroundColor(Color.WHITE); addView(col) }
    }

    /** Pulsante a pillola azzurro. */
    private fun pillola(ctx: android.content.Context, s: String, size: Float, coloreTesto: Int, sfondo: Int, altezza: Int) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(coloreTesto)
        gravity = Gravity.CENTER
        minHeight = altezza
        background = GradientDrawable().apply { setColor(sfondo); cornerRadius = altezza / 2f }
        isClickable = true
        isFocusable = true
    }

    /** Campo con scritta grande in grassetto e linea sotto, come "ricevitore" e "token" nella bozza. */
    private fun campoSottolineato(ctx: android.content.Context, etichetta: String, password: Boolean) = EditText(ctx).apply {
        hint = etichetta
        setHintTextColor(0xFF797979.toInt()) // scritte ricevitore/token schiarite del 30%
        setTextColor(nero)
        textSize = 15f
        setSingleLine(true)
        minHeight = ctx.dp(56)
        gravity = Gravity.CENTER_VERTICAL
        backgroundTintList = ColorStateList.valueOf(nero)
        setPadding(ctx.dp(0), ctx.dp(8), ctx.dp(0), ctx.dp(8))
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        // scritta del campo vuoto grande (senza grassetto), testo inserito più piccolo
        val aggiorna = { textSize = if (text.isNullOrEmpty()) 20f else 14f; setTypeface(null, Typeface.NORMAL) }
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
            // pallino sempre della stessa dimensione, sia acceso sia spento
            thumbDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(ctx.dp(24), ctx.dp(24)) }
            thumbIconDrawable = null
            trackDecorationTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(azzurro, 0xFFE3E3E3.toInt())) // stesso colore della barra: nessun bordo scuro
            setOnCheckedChangeListener { _, on -> cambia(on) }
        }
        val t = TextView(ctx).apply {
            text = testo; textSize = 16f; setTextColor(nero) // va a capo se lo spazio non basta
        }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
                .setMessage(r.getOrElse { "non riuscito:\n${it.message ?: it.javaClass.simpleName}" })
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object {
        val ORE = listOf(2, 4, 8, 24)
    }
}
