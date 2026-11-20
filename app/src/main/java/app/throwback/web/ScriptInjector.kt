package app.throwback.web

import java.io.File
import android.util.Log
import app.throwback.BuildConfig
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** throwback.js and throwback.css from assets/inject/, read once per process. */
internal class PageAssets(val template: String, val css: String) {
    companion object {
        @Volatile
        private var cached: PageAssets? = null

        suspend fun load(context: Context): PageAssets = cached ?: withContext(Dispatchers.IO) {
            val assets = context.applicationContext.assets
            fun read(name: String) = assets.open(name).bufferedReader().use { it.readText() }
            PageAssets(read("inject/throwback.js"), read("inject/throwback.css")).also { cached = it }
        }
    }
}

/**
 * Puts the page script into Instagram documents.
 *
 * Preferred: WebViewCompat.addDocumentStartJavaScript, which runs it before any of Instagram's own
 * scripts in every www.instagram.com document (and nowhere else). WebViews too old for that get it
 * from onPageStarted and again from onPageFinished; the script ignores a second copy and just
 * reconfigures, so double injection is harmless.
 */
internal class ScriptInjector(private val webView: WebView) {

    private var handler: ScriptHandler? = null
    private var script: String? = null

    /**
     * TEST BUILDS ONLY: with a file `files/debug_noscript`, no page script is injected, so a plain
     * Instagram page can be measured in the same WebView and session (Throwback's own cost = the
     * difference). Release builds always inject.
     */
    private val noScript: Boolean = BuildConfig.DEBUG && File(webView.context.filesDir, "debug_noscript").exists()

    /** Uses [script] for every Instagram page loaded from now on. */
    fun install(script: String) {
        if (noScript) {
            Log.w("ThrowbackWeb", "DEBUG: page script disabled (files/debug_noscript)")
            return
        }
        if (script == this.script) return
        this.script = script
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler?.remove()
            handler = WebViewCompat.addDocumentStartJavaScript(webView, script, WebViewConfig.INSTAGRAM_ORIGINS)
        }
    }

    /** Fallback injection; call from onPageStarted and onPageFinished. */
    fun onPageEvent(isInstagram: Boolean) {
        if (!isInstagram || WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        script?.let { webView.evaluateJavascript(it, null) }
    }

    /**
     * Sends a new mode or rules to the page already open (no reload).
     * [onResult] gets false when the page has no Throwback script (not Instagram, or an error page).
     */
    fun configureLive(configJson: String, onResult: (Boolean) -> Unit) {
        val js = "(function(){var t=window.__throwback;return !!(t&&t.configure($configJson));})()"
        webView.evaluateJavascript(js) { result -> onResult(result == "true") }
    }

    fun release() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) handler?.remove()
        handler = null
    }
}
