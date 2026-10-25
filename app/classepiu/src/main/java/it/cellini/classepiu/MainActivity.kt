package it.cellini.classepiu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Schermata principale come la bozza: fascia azzurra con la scritta classePiù, contenitore grigio
 * con angoli arrotondati e pannello bianco, barra delle pagine appoggiata alla fascia azzurra in basso (home, registro, bacheca, setting).
 */
class MainActivity : AppCompatActivity() {

    private val contenitoreId = View.generateViewId()
    private var schedaAttiva = TAB_REGISTRO
    private var barra: BarraPagine? = null

    private val permessoNotifiche = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Impostazioni(this).pronta) {
            // unica cosa da fare al primo avvio: accedere con mail e password
            startActivity(Intent(this, AccessoActivity::class.java))
            finish()
            return
        }
        Notifiche.creaCanale(this)
        val azzurro = ContextCompat.getColor(this, R.color.logo_sfondo)
        window.statusBarColor = azzurro

        // --- fascia azzurra con la scritta
        val testa = FrameLayout(this).apply {
            setBackgroundColor(azzurro)
            setPadding(0, dp(6), 0, dp(26))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_bianco)
                contentDescription = "ClassePiù"
                adjustViewBounds = true
            }, FrameLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { rightMargin = dp(20) }) // un po' a sinistra, per lasciare posto all'orologio
            // a sinistra del logo: omino per uscire dall'account (con conferma)
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
                contentDescription = "esci o cambia account"
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_profilo) },
                    FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                setOnClickListener { esciDallAccount() }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(14) })
            // a destra del logo: orologio funzionante e, sotto, il giorno ("mar, 2 ott")
            val data = TextView(this@MainActivity).apply {
                textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setSingleLine(true); includeFontPadding = false
            }
            val orologio = Orologio(this@MainActivity, sulAzzurro = true).apply { alTick = { data.text = giornoBreve() } }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(orologio, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }, FrameLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(16) })
        }

        // --- pannello bianco con angoli superiori arrotondati, da bordo a bordo (come la bozza);
        //     in fondo la fascia azzurra e, appoggiata sopra, la barra delle pagine (la stessa di tutte le schermate)
        val pannello = FrameLayout(this).apply {
            id = contenitoreId; setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, dp(SPAZIO_BARRA)) // le pagine finiscono sopra la barra
        }
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = dp(30).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            setPadding(0, dp(14), 0, 0)
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        barra = conBarra(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(azzurro)
            addView(testa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(contenitore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = -dp(12); leftMargin = dp(1); rightMargin = dp(1) })
        }, schedaAttiva) { mostra(it) }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(contenitoreId, BachecaFragment(), "t$TAB_BACHECA")
                .add(contenitoreId, RegistroFragment(), "t$TAB_REGISTRO")
                .add(contenitoreId, ImpostazioniFragment(), "t$TAB_IMPOSTAZIONI")
                .add(contenitoreId, HomeFragment(), "t$TAB_HOME")
                .commitNow()
            mostra(TAB_REGISTRO)
            Controllo.pianifica(this)
        } else {
            mostra(savedInstanceState.getInt("tab", TAB_REGISTRO))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val registro = frammento(TAB_REGISTRO) as? RegistroFragment
                if (schedaAttiva == TAB_REGISTRO && registro?.indietro() == true) return
                if (schedaAttiva != TAB_BACHECA) { mostra(TAB_BACHECA); return }
                finish()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else chiediBatteria()
        gestisci(intent)
        // dopo l'ingresso con le credenziali si entra subito, senza animazione
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SENZA_ANIMAZIONE, false) && !intent.hasExtra(EXTRA_SCHEDA)) Splash.mostra(this)
    }

    /** Stessa uscita del pulsante in opzioni: toglie la password, ferma il controllo e torna all'accesso. */
    private fun esciDallAccount() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage("uscire dall'account?")
            .setPositiveButton("conferma") { _, _ ->
                val imp = Impostazioni(this)
                imp.password = ""
                imp.controlloAutomatico = false
                Controllo.pianifica(this)
                startActivity(Intent(this, AccessoActivity::class.java))
                finish()
            }
            .setNegativeButton("annulla", null)
            .show()
    }

    /** Giorno in forma breve, tutto minuscolo: "mar, 2 ott". */
    private fun giornoBreve(): String =
        java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.ITALY).format(java.util.Date()).lowercase().replace(".", "")

    /** Una sola volta: chiede di escludere l'app dall'ottimizzazione batteria, così i controlli partono a orario. */
    @SuppressLint("BatteryLife")
    private fun chiediBatteria() {
        val pref = getSharedPreferences("app", MODE_PRIVATE)
        if (pref.getBoolean("batteria_chiesta", false)) return
        pref.edit().putBoolean("batteria_chiesta", true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onStart() {
        super.onStart()
        if (Impostazioni(this).pronta) Controllo.recupera(this)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) chiediBatteria()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gestisci(intent)
    }

    private fun gestisci(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REGISTRO, false) == true) mostra(TAB_REGISTRO)
        // dalla barra delle pagine di un'altra schermata
        i?.getIntExtra(EXTRA_SCHEDA, 0)?.takeIf { it != 0 }?.let { mostra(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", schedaAttiva)
    }

    private fun frammento(tab: Int): Fragment? = supportFragmentManager.findFragmentByTag("t$tab")

    fun mostra(tab: Int) {
        schedaAttiva = tab
        val t = supportFragmentManager.beginTransaction()
        for (k in listOf(TAB_HOME, TAB_BACHECA, TAB_REGISTRO, TAB_IMPOSTAZIONI)) {
            val f = frammento(k) ?: continue
            if (k == tab) t.show(f) else t.hide(f)
        }
        t.commitNowAllowingStateLoss()
        barra?.attiva(tab)
    }

    companion object {
        const val TAB_BACHECA = 1
        const val TAB_REGISTRO = 2
        const val TAB_IMPOSTAZIONI = 3
        const val TAB_HOME = 4
        const val EXTRA_REGISTRO = "apri_registro"
        const val EXTRA_SENZA_ANIMAZIONE = "senza_animazione"
        const val EXTRA_SCHEDA = "scheda"
    }
}
