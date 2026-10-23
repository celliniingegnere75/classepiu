package it.cellini.classepiu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Schermata di accesso con la disposizione di quella di ClasseViva: logo, Username/Email, Password,
 * pulsante Accedi e "Password dimenticata?". Niente SPID/CIE e niente testi su scuola, privacy o nazionalità.
 * Dopo l'accesso l'app imposta da sola tutto il resto (come cv.py dopo "credenziali").
 */
class AccessoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imp = Impostazioni(this)
        val grigio = 0xFFF0F0F0.toInt()
        val primario = ContextCompat.getColor(this, R.color.primario)
        window.statusBarColor = grigio
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(grigio)
            setPadding(dp(20), dp(64), dp(20), dp(24))
        }

        // logo (scritta classePiù) in alto, come il marchio sulla schermata di ClasseViva
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_scritta)
            contentDescription = "ClassePiù"
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(48) })

        val (boxUtente, campoUtente) = campo("username/email", password = false)
        campoUtente.setText(imp.utente)
        val (boxPass, campoPass) = campo("PASSWORD", password = true)
        col.addView(boxUtente)
        col.addView(boxPass, piena().apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            text = "password dimenticata?"
            setTextColor(0xFF555555.toInt())
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(8), dp(22))
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://web.spaggiari.eu/"))) } }
        }, piena())

        val accedi = MaterialButton(this).apply {
            text = "accedi"
            textSize = 18f
            letterSpacing = 0.02f
            cornerRadius = dp(30)
            minHeight = dp(60)
            insetTop = 0
            insetBottom = 0
        }
        col.addView(accedi, piena().apply { topMargin = dp(8) })
        val attesa = ProgressBar(this).apply { visibility = View.GONE }
        col.addView(attesa, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(16) })
        val esito = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@AccessoActivity, R.color.azione))
            setPadding(dp(8), dp(12), dp(8), 0)
        }
        col.addView(esito, piena())

        // pulsante grigio finché i campi sono vuoti, azzurro quando si può accedere
        fun aggiornaPulsante() {
            val pronto = !campoUtente.text.isNullOrBlank() && !campoPass.text.isNullOrEmpty()
            accedi.isEnabled = pronto
            accedi.backgroundTintList = android.content.res.ColorStateList.valueOf(if (pronto) primario else 0xFFD6D6D6.toInt())
            accedi.setTextColor(if (pronto) 0xFFFFFFFF.toInt() else 0xFF8A8A8A.toInt())
            accedi.strokeWidth = if (pronto) 0 else dp(1)
            accedi.strokeColor = android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
        }
        val osserva = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { aggiornaPulsante() }
        }
        campoUtente.addTextChangedListener(osserva)
        campoPass.addTextChangedListener(osserva)
        aggiornaPulsante()

        fun entra() {
            val u = campoUtente.text?.toString()?.trim().orEmpty()
            val p = campoPass.text?.toString().orEmpty()
            if (u.isEmpty() || p.isEmpty()) return
            accedi.isEnabled = false
            attesa.visibility = View.VISIBLE
            esito.text = ""
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val cv = ClasseViva(u, p)
                        cv.login()
                        cv.bacheca().size
                    }
                }
                attesa.visibility = View.GONE
                aggiornaPulsante()
                r.onSuccess {
                    configura(u, p)
                    startActivity(Intent(this@AccessoActivity, MainActivity::class.java))
                    finish()
                }.onFailure {
                    esito.text = "errore nelle credenziali!"
                }
            }
        }
        accedi.setOnClickListener { entra() }
        campoPass.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { entra(); true } else false }

        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(grigio); addView(col) })
        if (savedInstanceState == null) Splash.mostra(this)
    }

    /** Campo bianco arrotondato, senza bordo, con la scritta in maiuscolo (come su ClasseViva). */
    private fun campo(etichetta: String, password: Boolean): Pair<TextInputLayout, TextInputEditText> {
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputFilledStyle).apply {
            hint = etichetta
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor = 0xFFFFFFFF.toInt()
            boxStrokeWidth = 0
            boxStrokeWidthFocused = 0
            val r = dp(18).toFloat()
            setBoxCornerRadii(r, r, r, r)
            defaultHintTextColor = android.content.res.ColorStateList.valueOf(0xFF777777.toInt()) // schiarito del 20%
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                setEndIconTintList(android.content.res.ColorStateList.valueOf(0xFF444444.toInt()))
            }
        }
        val edit = TextInputEditText(box.context).apply {
            setSingleLine(true)
            textSize = 18f
            setTextColor(0xFF4E4E4E.toInt()) // schiarito del 20%
            minHeight = dp(64)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        }
        box.addView(edit)
        box.layoutParams = piena()
        return box to edit
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
        if (imp.oreIntervallo !in ImpostazioniFragment.ORE) imp.oreIntervallo = 2
        if (cambiato) Viste(this).cancella() // nuovo profilo: si riparte come al primo avvio
        Controllo.pianifica(this)
        // primo controllo subito: al primo avvio segna come già viste le circolari presenti, senza inviarle
        val app = applicationContext
        Thread { runCatching { Controllo.esegui(app) } }.start()
    }
}
