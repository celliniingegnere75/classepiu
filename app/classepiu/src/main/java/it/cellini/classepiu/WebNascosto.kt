package it.cellini.classepiu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ClasseViva web in un browser vero ma invisibile (WebView). Le richieste fatte a mano con HttpURLConnection
 * vengono respinte dalla protezione antibot (Akamai, HTTP 403 "Access Denied"): qui la pagina di accesso gira
 * nel motore di Chrome del telefono, che ottiene i cookie antibot, e ogni chiamata si fa con fetch() dalla pagina,
 * quindi con le intestazioni e i cookie del browser.
 * Da usare fuori dal thread principale (le chiamate aspettano la risposta).
 */
class WebNascosto(private val ctx: Context) : Closeable {
    private val principale = Handler(Looper.getMainLooper())
    private val risposte = ConcurrentHashMap<Int, LinkedBlockingQueue<Pair<Int, String>>>()
    private val contatore = AtomicInteger()
    private var web: WebView? = null

    private inner class Ponte {
        @JavascriptInterface
        fun fine(id: Int, codice: Int, testo: String) { risposte[id]?.offer(codice to testo) }
    }

    /** Apre la pagina di accesso e aspetta che sia caricata (con il tempo per lo script antibot). */
    @SuppressLint("SetJavaScriptEnabled")
    fun apri(indirizzo: String = "https://web.spaggiari.eu/home/app/default/login.php") {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebNascosto va usato fuori dal thread principale" }
        val caricata = CountDownLatch(1)
        principale.post {
            CookieManager.getInstance().setAcceptCookie(true)
            web = WebView(ctx.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(Ponte(), "cpPonte")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) { caricata.countDown() }
                }
                loadUrl(indirizzo)
            }
        }
        if (!caricata.await(40, TimeUnit.SECONDS)) throw ClasseVivaException("classeviva web non risponde (pagina di accesso)")
        // lo script antibot della pagina manda i suoi dati subito dopo il caricamento
        Thread.sleep(2500)
    }

    /** fetch() dalla pagina: restituisce (codice HTTP, testo). [corpo] != null = POST form. */
    fun chiama(indirizzo: String, corpo: String? = null, referer: String? = null, attesaSec: Long = 60): Pair<Int, String> {
        val w = web ?: throw ClasseVivaException("classeviva web non aperto")
        val id = contatore.incrementAndGet()
        val coda = LinkedBlockingQueue<Pair<Int, String>>()
        risposte[id] = coda
        val opzioni = JSONObject().apply {
            put("method", if (corpo != null) "POST" else "GET")
            put("credentials", "include")
            if (referer != null) put("referrer", referer)
            if (corpo != null) {
                put("headers", JSONObject()
                    .put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .put("X-Requested-With", "XMLHttpRequest"))
                put("body", corpo)
            }
        }
        val js = """
            (function(){
              fetch(${JSONObject.quote(indirizzo)}, $opzioni)
                .then(function(r){ return r.text().then(function(t){ cpPonte.fine($id, r.status, t); }); })
                .catch(function(e){ cpPonte.fine($id, 0, 'errore di rete: ' + e); });
            })()
        """.trimIndent()
        principale.post { w.evaluateJavascript(js, null) }
        try {
            return coda.poll(attesaSec, TimeUnit.SECONDS) ?: (0 to "classeviva web non ha risposto in $attesaSec secondi")
        } finally {
            risposte.remove(id)
        }
    }

    override fun close() {
        val w = web ?: return
        web = null
        principale.post {
            CookieManager.getInstance().flush()
            w.stopLoading()
            w.destroy()
        }
    }
}
