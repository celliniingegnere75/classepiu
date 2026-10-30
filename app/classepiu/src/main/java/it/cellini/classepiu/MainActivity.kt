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
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Schermata principale come la bozza: fascia azzurra con la scritta classePiù, contenitore grigio
 * con angoli arrotondati e pannello bianco, barra delle pagine appoggiata alla fascia azzurra in basso (home, registri, bacheca);
 * l'ingranaggio in alto a destra apre lezioni e funzioni.
 */
class MainActivity : AppCompatActivity() {

    private val contenitoreId = View.generateViewId()
    private val schedeId = View.generateViewId()
    private var schedaAttiva = TAB_REGISTRO
    private var barra: BarraPagine? = null
    private var sospesa = false
    private var campanella: CampanellaSwipateView? = null // MODIFICA

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
            val logo = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_bianco)
                contentDescription = "ClassePiù"
                adjustViewBounds = true
                setOnClickListener { FinestraRientro.ripristinaSwippate(this@MainActivity) }
            }
            addView(logo, FrameLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // MODIFICA: campanella sovrapposta di pochi pixel alla "ù" del logo.
            val indicatore = CampanellaSwipateView(this@MainActivity).apply {
                visibility = View.GONE
                setOnClickListener { FinestraRientro.ripristinaSwippate(this@MainActivity) }
            }
            campanella = indicatore
            addView(indicatore, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER).apply {
                leftMargin = dp(90)
            })
            setOnClickListener { FinestraRientro.ripristinaSwippate(this@MainActivity) }
            // a sinistra del logo: omino per uscire dall'account (con conferma)
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
                contentDescription = "esci o cambia account"
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_profilo) },
                    FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                setOnClickListener { esciDallAccount() }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(14) })
            // a destra del logo: ingranaggio bianco che apre lezioni e funzioni (stesso cerchio dell'omino)
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
                contentDescription = "lezioni e funzioni"
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_nav_impostazioni)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                setOnClickListener { vaiA(TAB_IMPOSTAZIONI) }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14) })
        }

        // --- pannello bianco con angoli superiori arrotondati, da bordo a bordo (come la bozza);
        //     sopra le pagine si aprono le schede (alunni, voti, note, lezioni, circolare) e, in sovraimpressione su tutto,
        //     la barra delle pagine con la riga azzurra, sempre ferma
        val pannello = FrameLayout(this).apply {
            id = contenitoreId; setBackgroundColor(Color.WHITE)
            // le liste delle pagine scorrono fin sotto la barra, coperte solo da lei
            viewTreeObserver.addOnGlobalLayoutListener { listeSottoLaBarra() }
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
        val pagine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(azzurro)
            addView(testa, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(contenitore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = -dp(12); leftMargin = dp(1); rightMargin = dp(1) })
        }
        val barraPagine = BarraPagine(this) { vaiA(it) }
        barra = barraPagine
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(azzurro)
            addView(pagine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(FrameLayout(this@MainActivity).apply { id = schedeId }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(sovraimpressione(barraPagine), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ALTEZZA_BARRA + ALZO_BARRA + SOMBRA_BARRA), Gravity.BOTTOM))
        })

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
                // prima si chiude la scheda aperta (torna a registro o bacheca)
                if (supportFragmentManager.backStackEntryCount > 0) { supportFragmentManager.popBackStack(); return }
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
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SENZA_ANIMAZIONE, false)) Splash.mostra(this)
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
        val rientroDaSospensione = sospesa
        sospesa = false
        super.onStart()
        if (Impostazioni(this).pronta) Controllo.recupera(this)
        aggiornaCampanella() // MODIFICA
        // La finestra compare solo quando l'Activity torna dal background, non nei cambi pagina.
        if (rientroDaSospensione && !isFinishing) FinestraRientro.mostra(this)
    }

    override fun onStop() {
        sospesa = true
        // le notifiche del rientro non sopravvivono alla sospensione: al prossimo rientro si ricalcolano da zero
        FinestraRientro.chiudiTutto()
        super.onStop()
    }

    override fun onDestroy() {
        FinestraRientro.chiudiTutto()
        super.onDestroy()
    }

    /** MODIFICA: aggiorna l'indicatore in base alle notifiche swippate ancora recuperabili. */
    fun aggiornaCampanella() {
        campanella?.visibility = if (FinestraRientro.haNotificheSwippate(this)) View.VISIBLE else View.GONE
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
        if (i?.getBooleanExtra(EXTRA_REGISTRO, false) == true) vaiA(TAB_REGISTRO)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", schedaAttiva)
    }

    private fun frammento(tab: Int): Fragment? = supportFragmentManager.findFragmentByTag("t$tab")

    /** Dalla barra: chiude le schede aperte e va alla pagina scelta. */
    private fun vaiA(tab: Int) {
        if (!supportFragmentManager.isStateSaved) supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        mostra(tab)
    }

    /** Apre una scheda (alunni, voti, note, lezioni, circolare) che entra da destra sopra la pagina; "indietro" o ✕ la chiudono. */
    fun apri(scheda: Scheda, vararg extra: Pair<String, Any?>) {
        if (supportFragmentManager.isStateSaved) return
        scheda.arguments = bundleOf(*extra)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.entra_da_destra, 0, 0, R.anim.esce_a_destra)
            .add(schedeId, scheda)
            .addToBackStack(null)
            .commit()
    }

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
    }
}
