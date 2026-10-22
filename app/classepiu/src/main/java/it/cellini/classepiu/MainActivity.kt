package it.cellini.classepiu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var barra: MaterialToolbar
    private lateinit var nav: BottomNavigationView
    private val contenitoreId = View.generateViewId()

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

        barra = MaterialToolbar(this).apply { title = getString(R.string.app_name) }
        val contenitore = FrameLayout(this).apply { id = contenitoreId }
        nav = BottomNavigationView(this).apply {
            menu.add(0, TAB_BACHECA, 0, R.string.tab_bacheca).setIcon(android.R.drawable.ic_menu_agenda)
            menu.add(0, TAB_REGISTRO, 1, R.string.tab_registro).setIcon(android.R.drawable.ic_menu_edit)
            menu.add(0, TAB_IMPOSTAZIONI, 2, R.string.tab_impostazioni).setIcon(android.R.drawable.ic_menu_preferences)
        }
        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(contenitore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(radice)

        if (savedInstanceState == null) {
            val imp = Impostazioni(this)
            supportFragmentManager.beginTransaction()
                .add(contenitoreId, BachecaFragment(), "t$TAB_BACHECA")
                .add(contenitoreId, RegistroFragment(), "t$TAB_REGISTRO")
                .add(contenitoreId, ImpostazioniFragment(), "t$TAB_IMPOSTAZIONI")
                .commitNow()
            mostra(if (imp.mailPronta || !imp.invioMail) TAB_BACHECA else TAB_IMPOSTAZIONI)
            Controllo.pianifica(this)
        } else {
            mostra(savedInstanceState.getInt("tab", TAB_BACHECA))
        }
        nav.setOnItemSelectedListener { mostra(it.itemId); true }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val registro = frammento(TAB_REGISTRO) as? RegistroFragment
                if (nav.selectedItemId == TAB_REGISTRO && registro?.indietro() == true) return
                if (nav.selectedItemId != TAB_BACHECA) { nav.selectedItemId = TAB_BACHECA; return }
                finish()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        gestisci(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gestisci(intent)
    }

    private fun gestisci(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REGISTRO, false) == true) nav.selectedItemId = TAB_REGISTRO
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", nav.selectedItemId)
    }

    private fun frammento(tab: Int): Fragment? = supportFragmentManager.findFragmentByTag("t$tab")

    fun mostra(tab: Int) {
        val t = supportFragmentManager.beginTransaction()
        for (k in listOf(TAB_BACHECA, TAB_REGISTRO, TAB_IMPOSTAZIONI)) {
            val f = frammento(k) ?: continue
            if (k == tab) t.show(f) else t.hide(f)
        }
        t.commitNowAllowingStateLoss()
        if (nav.selectedItemId != tab) nav.menu.findItem(tab)?.isChecked = true
        barra.subtitle = when (tab) {
            TAB_REGISTRO -> "Registro web ufficiale"
            TAB_IMPOSTAZIONI -> "Impostazioni"
            else -> "Bacheca circolari"
        }
    }

    companion object {
        const val TAB_BACHECA = 1
        const val TAB_REGISTRO = 2
        const val TAB_IMPOSTAZIONI = 3
        const val EXTRA_REGISTRO = "apri_registro"
    }
}
