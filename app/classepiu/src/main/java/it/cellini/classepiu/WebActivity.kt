package it.cellini.classepiu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Apre il sito ClasseViva dentro l'app e compila da solo utente e password. */
class WebActivity : AppCompatActivity() {
    private var compilato = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imp = Impostazioni(this)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                if (compilato) return
                val js = """
                    (function(){
                      var p=document.querySelector('input[type=password]'); if(!p) return 'no';
                      var u=document.querySelector('input[name=uid],input#login,input[name=login],input[type=email],input[type=text]');
                      if(!u) return 'no';
                      function set(e,x){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(e,x);
                        e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
                      set(u,${JSONObject.quote(imp.utente)}); set(p,${JSONObject.quote(imp.password)});
                      var f=p.form; var b=f&&f.querySelector('button[type=submit],input[type=submit],button');
                      if(b) b.click(); else if(f) f.submit();
                      return 'ok';
                    })()
                """.trimIndent()
                v.evaluateJavascript(js) { r -> if (r?.contains("ok") == true) compilato = true }
            }
        }
        setContentView(web)
        web.loadUrl("https://web.spaggiari.eu/home/app/default/login.php")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }
}
