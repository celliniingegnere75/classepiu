package it.cellini.classepiu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
 * con angoli arrotondati e pannello bianco, barra in basso flottante (Registro, Bacheca, Impostazioni).
 */
class MainActivity : AppCompatActivity() {

    private val contenitoreId = View.generateViewId()
    private var schedaAttiva = TAB_BACHECA
    private val voci = mutableMapOf<Int, Pair<View, TextView>>() // tab -> (riquadro icona, etichetta)
    private val icone = mutableMapOf<Int, ImageView>()

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
            }, FrameLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        // --- contenitore grigio arrotondato con il pannello bianco
        val pannello = FrameLayout(this).apply { id = contenitoreId; setBackgroundColor(Color.WHITE) }
        val barra = barraInBasso()
        val contenitore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.grigio_contenitore))
                val r = dp(26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(7), dp(17), dp(7), dp(10))
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)).apply {
                setMargins(dp(10), dp(12), dp(10), dp(4))
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(azzurro)
            addView(testa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(contenitore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = -dp(12) })
        })

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(contenitoreId, BachecaFragment(), "t$TAB_BACHECA")
                .add(contenitoreId, RegistroFragment(), "t$TAB_REGISTRO")
                .add(contenitoreId, ImpostazioniFragment(), "t$TAB_IMPOSTAZIONI")
                .commitNow()
            mostra(TAB_BACHECA)
            Controllo.pianifica(this)
        } else {
            mostra(savedInstanceState.getInt("tab", TAB_BACHECA))
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
    }

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

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) chiediBatteria()
    }

    /** Barra flottante grigio chiaro con le tre voci; la voce attiva ha il riquadro azzurro. */
    private fun barraInBasso(): LinearLayout {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MainActivity, R.color.grigio_barra))
                cornerRadius = dp(33).toFloat()
            }
            elevation = dp(3).toFloat()
            setPadding(dp(14), 0, dp(14), 0)
        }
        listOf(
            Triple(TAB_REGISTRO, "Registro", R.drawable.ic_nav_registro),
            Triple(TAB_BACHECA, "Bacheca", R.drawable.ic_nav_bacheca),
            Triple(TAB_IMPOSTAZIONI, "Impostazioni", R.drawable.ic_nav_impostazioni),
        ).forEach { (tab, nome, icona) ->
            val img = ImageView(this).apply { setImageResource(icona) }
            val riquadro = FrameLayout(this).apply {
                addView(img, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }
            val etichetta = TextView(this).apply {
                text = nome
                textSize = 14.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val voce = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(riquadro, LinearLayout.LayoutParams(dp(62), dp(34)))
                addView(etichetta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                setOnClickListener { mostra(tab) }
            }
            barra.addView(voce, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            voci[tab] = riquadro to etichetta
            icone[tab] = img
        }
        return barra
    }

    private fun aggiornaBarra() {
        val azzurro = ContextCompat.getColor(this, R.color.logo_sfondo)
        voci.forEach { (tab, v) ->
            val (riquadro, etichetta) = v
            val attiva = tab == schedaAttiva
            riquadro.background = if (attiva) GradientDrawable().apply {
                setColor(azzurro)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(2), 0xFF333333.toInt())
            } else null
            icone[tab]?.imageTintList = ColorStateList.valueOf(if (attiva) Color.WHITE else 0xFF444444.toInt())
            etichetta.setTextColor(if (attiva) 0xFF222222.toInt() else 0xFF555555.toInt())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gestisci(intent)
    }

    private fun gestisci(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REGISTRO, false) == true) mostra(TAB_REGISTRO)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", schedaAttiva)
    }

    private fun frammento(tab: Int): Fragment? = supportFragmentManager.findFragmentByTag("t$tab")

    fun mostra(tab: Int) {
        schedaAttiva = tab
        val t = supportFragmentManager.beginTransaction()
        for (k in listOf(TAB_BACHECA, TAB_REGISTRO, TAB_IMPOSTAZIONI)) {
            val f = frammento(k) ?: continue
            if (k == tab) t.show(f) else t.hide(f)
        }
        t.commitNowAllowingStateLoss()
        aggiornaBarra()
    }

    companion object {
        const val TAB_BACHECA = 1
        const val TAB_REGISTRO = 2
        const val TAB_IMPOSTAZIONI = 3
        const val EXTRA_REGISTRO = "apri_registro"
    }
}
