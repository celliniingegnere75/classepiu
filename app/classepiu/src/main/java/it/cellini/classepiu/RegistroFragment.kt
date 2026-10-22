package it.cellini.classepiu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.json.JSONObject

/**
 * Registro web ufficiale (web.spaggiari.eu) dentro l'app: classi, lezioni, firme, voti, competenze,
 * note, agenda, assenze e giustificazioni, didattica, colloqui. L'accesso resta salvato.
 */
class RegistroFragment : Fragment() {

    private var web: WebView? = null
    private lateinit var carica: ProgressBar
    private var sceltaFile: ValueCallback<Array<Uri>>? = null

    private val selettore = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null
        sceltaFile?.onReceiveValue(uris)
        sceltaFile = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        carica = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }

        val w = WebView(ctx)
        web = w
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "http" || u.scheme == "https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { true }
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { carica.visibility = View.VISIBLE }
            override fun onPageFinished(view: WebView?, url: String?) {
                carica.visibility = View.GONE
                CookieManager.getInstance().flush()
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) { carica.progress = p }
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                sceltaFile?.onReceiveValue(null)
                sceltaFile = cb
                return try { selettore.launch(params!!.createIntent()); true } catch (e: Exception) { sceltaFile = null; false }
            }
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ -> scarica(ctx, url, userAgent, contentDisposition, mimeType) }

        val pulsanti = ctx.riga().apply { setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4)) }
        fun p(s: String, f: () -> Unit) = pulsanti.addView(ctx.bottone(s, pieno = false) { f() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) }
        })
        p("Compila accesso") { compilaAccesso() }
        p("Apri nel browser") { w.url?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }

        if (savedInstanceState != null) w.restoreState(savedInstanceState) else w.loadUrl(HOME)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(pulsanti) })
            addView(carica, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(3)))
            addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** Inserisce utente e password salvati nei campi della pagina di accesso. */
    private fun compilaAccesso() {
        val imp = Impostazioni(requireContext())
        if (!imp.pronta) { Toast.makeText(requireContext(), "Imposta prima utente e password.", Toast.LENGTH_LONG).show(); return }
        val u = JSONObject.quote(imp.utente)
        val p = JSONObject.quote(imp.password)
        val js = """
            (function(){
              function set(el,v){ if(!el) return; el.focus(); el.value=v; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
              var pw=document.querySelector('input[type=password]');
              var us=document.querySelector('input[type=email]')||document.querySelector('input[name*=uid],input[name*=login],input[id*=login],input[name*=user]')||document.querySelector('input[type=text]');
              set(us,$u); set(pw,$p);
              return (us?1:0)+(pw?1:0);
            })();
        """.trimIndent()
        web?.evaluateJavascript(js) { r ->
            val msg = if (r == "2") "Campi compilati: premi Accedi." else "Non trovo i campi di accesso in questa pagina."
            context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun scarica(ctx: Context, url: String, ua: String, disp: String?, mime: String?) {
        try {
            val nome = URLUtil.guessFileName(url, disp, mime)
            val r = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .addRequestHeader("User-Agent", ua)
                .setMimeType(mime)
                .setTitle(nome)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nome)
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            Toast.makeText(ctx, "Scarico $nome in Download", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download non riuscito: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Torna alla pagina precedente del registro; false se non c'è. */
    fun indietro(): Boolean {
        val w = web ?: return false
        if (w.canGoBack()) { w.goBack(); return true }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroyView() {
        web?.destroy()
        web = null
        super.onDestroyView()
    }

    companion object {
        const val HOME = "https://web.spaggiari.eu/"
    }
}
