package app.throwback.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.throwback.core.WebMode
import app.throwback.web.rules.FilterRules

/** A short message shown over the page. */
internal enum class WebNotice { REELS, SUGGESTED, MESSAGES_ONLY, HIDDEN, NO_APP }

internal data class WebToast(val notice: WebNotice, val id: Long)

/** A main-frame load failed. [retrying] while a retry is in flight. */
internal data class LoadError(val offline: Boolean, val url: String?, val retrying: Boolean = false)

/**
 * Owns the single long-lived Instagram WebView and everything wired to it: settings, the page
 * script, the message bridge, navigation rules, errors, pull-to-refresh and file uploads.
 * Compose reads its state; InstagramScreen drives it. Main thread only.
 */
internal class InstagramWebController(
    private val context: Context,
    private val assets: PageAssets,
    initialMode: WebMode,
    initialHandledStartUrl: String?,
) {
    /** Callbacks into the composable (set on every composition). */
    interface Host {
        fun onLocationChanged(location: String, url: String)
        fun onStartUrlHandled(url: String?)

        /** Throwback's settings icon in the page was tapped. */
        fun onOpenSettings()

        /** The chat's call button: open the chat (with [user], else chat [thread]) in the Instagram app. */
        fun onCallInApp(thread: String?, user: String?) {}

        /** The reel allowance in Instagram's swipe viewer is used up. */
        fun onReelLimit()

        /** The feed reached "You're all caught up". */
        fun onCaughtUp() {}

        /** Opens a picker; must eventually call [deliverFiles]. False if no picker could be opened. */
        fun pickFiles(kind: PickKind, multiple: Boolean, documentIntent: Intent?): Boolean
    }

    var host: Host? = null

    private val handler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)
    private val injector = ScriptInjector(webView)
    private val loopGuard = RedirectLoopGuard(SystemClock::elapsedRealtime)
    private val pull: PullGesture

    // State read by Compose.
    var contentShown by mutableStateOf(false)
        private set
    var error by mutableStateOf<LoadError?>(null)
        private set
    var pageBackground by mutableStateOf<Int?>(null)
        private set
    var pullProgress by mutableFloatStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var toast by mutableStateOf<WebToast?>(null)
    var rendererGone by mutableStateOf(false)
        private set

    var mode: WebMode = initialMode
        private set
    var started = false
        private set

    private var rules: FilterRules? = null
    private var router: UrlRouter? = null
    private var handledStartUrl = initialHandledStartUrl
    private var lastLocation: String? = null
    private var failedUrl: String? = null

    /** The main-frame URL being loaded, to tell main-frame SSL errors from subresource ones. */
    private var navigatingTo: String? = null
    private var revealRequested = false
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var nightMode: Int? = null
    private var destroyed = false
    private var toastCounter = 0L
    private val revealRunnable = Runnable { reveal() }
    private val stopRefreshing = Runnable { refreshing = false }

    init {
        WebViewConfig.apply(webView, context.resources.configuration.fontScale)
        val density = context.resources.displayMetrics.density
        pull = PullGesture(
            thresholdPx = PULL_THRESHOLD_DP * density,
            touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        )
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        installBridge()
        installTouchListener()
        current = java.lang.ref.WeakReference(this)
    }

    /** Today's counts from the page (JSON, see PageDayStats), or null when the page can't say. */
    fun dayStats(onResult: (String?) -> Unit) {
        if (destroyed) return onResult(null)
        webView.evaluateJavascript(
            "(function(){try{return window.__throwback&&__throwback.dayStats?__throwback.dayStats():null}catch(e){return null}})()",
        ) { raw ->
            // The value comes back JSON-encoded: a quoted string, or null.
            val text = try {
                (kotlinx.serialization.json.Json.parseToJsonElement(raw ?: "null") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            } catch (e: IllegalArgumentException) {
                null
            }
            onResult(text)
        }
    }

    // ------------------------------------------------------------------ driven by InstagramScreen

    /** New or updated rules: future pages get them at document start, the open page live. */
    fun applyRules(newRules: FilterRules) {
        if (newRules == rules) return
        val hadRules = rules != null
        rules = newRules
        router = UrlRouter(newRules)
        injector.install(PageScript.build(assets.template, assets.css, newRules, mode))
        if (hadRules && started) injector.configureLive(PageScript.config(rules = newRules)) {}
    }

    /**
     * First call: loads the deep link (if not already handled), else the page to restore, else
     * home. Later calls: opens [startUrl] if it is a new deep link.
     */
    fun openOrStart(startUrl: String?, restoreUrl: String?) {
        val router = router ?: return
        if (!started) {
            started = true
            val freshLink = startUrl?.takeIf { it != handledStartUrl }
            markStartHandled(startUrl)
            // Logged out: go straight to Instagram's login form instead of its "Log in or sign up"
            // landing page, which would need a second tap. A deep link still opens as asked.
            val loggedIn = InstagramSession.refresh()
            load(if (!loggedIn && freshLink == null) InstagramSession.LOGIN_URL else router.startUrl(freshLink ?: restoreUrl, mode))
            handler.postDelayed(revealRunnable, REVEAL_TIMEOUT_MS)
        } else if (startUrl != null && startUrl != handledStartUrl) {
            markStartHandled(startUrl)
            error = null
            load(router.startUrl(startUrl, mode))
        }
    }

    private var covered = false

    /** Tells the page whether a Throwback screen covers it (its feed-time clock pauses). */
    fun setCovered(value: Boolean) {
        covered = value
        pushCovered()
    }

    private fun pushCovered() {
        if (destroyed) return
        webView.evaluateJavascript(
            "window.__throwback && window.__throwback.setCovered && window.__throwback.setCovered($covered)",
            null,
        )
    }

    /** Switches FULL / MESSAGES_ONLY without reloading when the page script is present. */
    fun setMode(newMode: WebMode) {
        if (newMode == mode) return
        mode = newMode
        val rules = rules ?: return
        injector.install(PageScript.build(assets.template, assets.css, rules, newMode))
        if (!started) return
        injector.configureLive(PageScript.config(mode = newMode)) { handled ->
            if (!handled && !destroyed) enforceModeNatively()
        }
    }

    /** Back: through the WebView history (skipping pages this mode doesn't show). False = nothing left. */
    fun goBack(): Boolean {
        val router = router ?: return false
        if (mode == WebMode.MESSAGES_ONLY) {
            val history = webView.copyBackForwardList()
            for (i in history.currentIndex - 1 downTo 0) {
                val url = history.getItemAtIndex(i)?.url ?: continue
                if (router.decide(url, mode, currentUrl = url) == UrlRouter.Decision.Load) {
                    error = null
                    webView.goBackOrForward(i - history.currentIndex)
                    return true
                }
            }
            // Nothing behind us this mode shows: from a chat go to the inbox, from the inbox leave.
            val inbox = router.homeUrl(mode)
            val current = webView.url
            if (current != null && router.locationFor(current) != router.locationFor(inbox)) {
                error = null
                load(inbox)
                return true
            }
            return false
        }
        if (!webView.canGoBack()) return false
        error = null
        webView.goBack()
        return true
    }

    fun retry() {
        val failed = error ?: return
        val url = failed.url ?: router?.homeUrl(mode) ?: return
        failedUrl = null
        error = failed.copy(retrying = true)
        load(url)
    }

    /** Retries an offline error once the network is back. */
    fun retryIfOffline() {
        if (error?.offline == true && error?.retrying == false) retry()
    }

    fun onNightModeChanged(uiModeNight: Int) {
        val previous = nightMode
        nightMode = uiModeNight
        if (previous != null && previous != uiModeNight) WebViewConfig.refreshColorScheme(webView.settings)
    }

    fun onStart() {
        webView.onResume()
        webView.resumeTimers()
    }

    /** In the background: stop timers and animations (saves battery), keep the login. */
    fun onStop() {
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
    }

    fun deliverFiles(uris: Array<Uri>?) {
        pendingFiles?.onReceiveValue(uris)
        pendingFiles = null
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (current?.get() === this) current = null
        handler.removeCallbacksAndMessages(null)
        deliverFiles(null)
        injector.release()
        CookieManager.getInstance().flush()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    // ------------------------------------------------------------------ navigation

    private fun load(url: String) {
        navigatingTo = url
        webView.loadUrl(url)
    }

    private fun markStartHandled(url: String?) {
        handledStartUrl = url
        host?.onStartUrlHandled(url)
    }

    /** @return true when the WebView must not load [url] itself. */
    private fun handleNavigation(url: String, currentUrl: String?): Boolean {
        val router = router ?: return false
        return when (val d = router.decide(url, mode, currentUrl)) {
            UrlRouter.Decision.Load -> {
                navigatingTo = url
                false
            }
            is UrlRouter.Decision.Redirect -> redirect(d)
            is UrlRouter.Decision.External -> {
                openExternally(d.url)
                true
            }
            UrlRouter.Decision.Ignore -> true
        }
    }

    /** @return true if the original navigation was replaced or dropped. */
    private fun redirect(d: UrlRouter.Decision.Redirect): Boolean {
        if (loopGuard.allow(d.url)) {
            load(d.url)
            return true
        }
        Log.w(TAG, "Redirect loop to ${d.url}")
        if (d.kind == null) return false // plain normalisation: give up and load the page as asked
        val home = router?.homeUrl(mode)
        if (home != null && home != d.url && loopGuard.allow(home)) load(home)
        return true // blocked content is never loaded
    }

    /** The page has no Throwback script (error page, Facebook login): apply the mode from here. */
    private fun enforceModeNatively() {
        val router = router ?: return
        val url = webView.url
        if (error != null || url == null) {
            error = null
            failedUrl = null
            load(router.homeUrl(mode))
            return
        }
        val d = router.decide(url, mode, currentUrl = url)
        if (d is UrlRouter.Decision.Redirect) load(d.url)
    }

    private fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (!context.isActivity()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showNotice(WebNotice.NO_APP)
        } catch (e: SecurityException) {
            showNotice(WebNotice.NO_APP)
        }
    }

    private fun Context.isActivity(): Boolean {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return true
            c = c.baseContext
        }
        return false
    }

    private fun reportLocation(url: String?) {
        val router = router ?: return
        if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
        val location = router.locationFor(url)
        val loggedIn = InstagramSession.refresh()
        // Logged out on the landing page (e.g. after logging out): show the login form directly.
        // Capped, so a cookie we can't read can never cause a redirect loop.
        if (!loggedIn && location == "/" && loginRedirects < MAX_LOGIN_REDIRECTS) {
            loginRedirects++
            load(InstagramSession.LOGIN_URL)
            return
        }
        if (location == lastLocation) return
        lastLocation = location
        host?.onLocationChanged(location, url)
    }

    private var loginRedirects = 0

    // ------------------------------------------------------------------ readiness, errors, notices

    /** The page script says the filtered page is rendered: reveal once that frame is drawn. */
    private fun requestReveal() {
        if (contentShown || revealRequested) return
        revealRequested = true
        webView.postVisualStateCallback(VISUAL_STATE_REQUEST, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) = reveal()
        })
        handler.postDelayed(revealRunnable, VISUAL_STATE_TIMEOUT_MS)
    }

    private fun reveal() {
        if (destroyed || contentShown) return
        handler.removeCallbacks(revealRunnable)
        contentShown = true
        WebReadiness.markShown()
    }

    private fun showError(url: String?) {
        failedUrl = url
        refreshing = false
        error = LoadError(offline = !isOnline(context), url = url)
        reveal() // the error screen replaces the splash
    }

    private fun showNotice(notice: WebNotice) {
        toast = WebToast(notice, ++toastCounter)
    }

    private fun noticeFor(kind: String) = when (kind) {
        "reels" -> WebNotice.REELS
        "suggested" -> WebNotice.SUGGESTED
        UrlRouter.KIND_MESSAGES_ONLY -> WebNotice.MESSAGES_ONLY
        else -> WebNotice.HIDDEN
    }

    // ------------------------------------------------------------------ bridge

    private fun installBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only Instagram's own top-level documents can reach the app.
            WebViewCompat.addWebMessageListener(webView, BRIDGE_OBJECT, WebViewConfig.INSTAGRAM_ORIGINS) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onBridgeMessage(message.data)
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(), LEGACY_BRIDGE_OBJECT)
        }
    }

    /** For WebViews without WEB_MESSAGE_LISTENER. Visible to every page, so messages are checked on arrival. */
    private inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(json: String?) {
            handler.post {
                if (!destroyed && router?.isInstagram(webView.url) == true) onBridgeMessage(json)
            }
        }
    }

    private fun onBridgeMessage(json: String?) {
        if (destroyed) return
        when (val m = BridgeMessage.parse(json)) {
            is BridgeMessage.Location -> reportLocation(m.url)
            BridgeMessage.Ready -> {
                requestReveal()
                if (covered) pushCovered() // a new page starts uncovered
            }
            is BridgeMessage.Blocked -> showNotice(noticeFor(m.kind))
            is BridgeMessage.Touch -> {
                pull.scriptReport(m.atTop)
                pullProgress = pull.progress
            }
            is BridgeMessage.Background -> pageBackground = m.argb
            BridgeMessage.OpenSettings -> host?.onOpenSettings()
            is BridgeMessage.CallInApp -> host?.onCallInApp(m.thread, m.user)
            BridgeMessage.ReelLimit -> {
                Log.d(TAG, "reel poster requested by the page")
                host?.onReelLimit()
            }
            BridgeMessage.CaughtUp -> host?.onCaughtUp()
            null -> Unit
        }
    }

    // ------------------------------------------------------------------ pull to refresh

    /** Observes touches for pull-to-refresh; never consumes them, so the page behaves as usual. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchListener() {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pull.down(
                    x = event.x,
                    y = event.y,
                    documentAtTop = webView.scrollY == 0 && !refreshing && error == null,
                    pageAllowsPull = router?.allowsPullToRefresh(webView.url) == true,
                )
                MotionEvent.ACTION_MOVE -> pullProgress = pull.move(event.x, event.y, webView.scrollY == 0)
                MotionEvent.ACTION_UP -> {
                    if (pull.up()) refresh()
                    pullProgress = 0f
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    pull.cancel()
                    pullProgress = 0f
                }
            }
            false
        }
    }

    private fun refresh() {
        refreshing = true
        handler.removeCallbacks(stopRefreshing)
        handler.postDelayed(stopRefreshing, REFRESH_TIMEOUT_MS)
        webView.reload()
    }

    // ------------------------------------------------------------------ clients

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleNavigation(request.url.toString(), view.url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url != failedUrl) failedUrl = null
            injector.onPageEvent(router?.isInstagram(url) == true)
            reportLocation(url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            // Pages without the Throwback script (Facebook login) are ready as soon as they show.
            if (router?.isInstagram(url) != true) requestReveal()
        }

        override fun onPageFinished(view: WebView, url: String) {
            handler.removeCallbacks(stopRefreshing)
            refreshing = false
            // Error pages finish too (and don't start); failedUrl tells them apart.
            if (failedUrl == null) error = null
            val instagram = router?.isInstagram(url) == true
            injector.onPageEvent(instagram)
            if (!contentShown) {
                if (instagram) handler.postDelayed(revealRunnable, SCRIPT_READY_GRACE_MS) else requestReveal()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
            showError(request.url.toString())
        }

        override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
            sslHandler.cancel() // never continue past a certificate error
            if (error.url == navigatingTo || error.url == view.url) showError(error.url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            reportLocation(url) // also called for pushState, a backup for the page script's report
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Without this the app would be killed. InstagramScreen replaces the WebView.
            Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()}); recreating")
            rendererGone = true
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            deliverFiles(null) // a previous chooser that never returned
            pendingFiles = filePathCallback
            val kind = FileChooserKinds.classify(fileChooserParams.acceptTypes)
            val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            val documentIntent = try {
                fileChooserParams.createIntent()
            } catch (e: RuntimeException) {
                null
            }
            val opened = try {
                host?.pickFiles(kind, multiple, documentIntent) == true
            } catch (e: ActivityNotFoundException) {
                false
            }
            if (!opened) deliverFiles(null)
            return true
        }

        private val blankPoster by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

        /** Without this, videos show WebView's grey play-button placeholder before they load. */
        override fun getDefaultVideoPoster(): Bitmap = blankPoster
    }

    companion object {
        /** The live page (one at a time), for screens that read from it, such as "Your day". */
        @Volatile
        var current: java.lang.ref.WeakReference<InstagramWebController>? = null

        private const val MAX_LOGIN_REDIRECTS = 2
        private const val TAG = "ThrowbackWeb"
        private const val BRIDGE_OBJECT = "throwbackNative"
        private const val LEGACY_BRIDGE_OBJECT = "throwbackBridge"
        private const val VISUAL_STATE_REQUEST = 1L
        private const val PULL_THRESHOLD_DP = 72

        /** The splash never waits longer than this, whatever happens. */
        private const val REVEAL_TIMEOUT_MS = 12_000L

        /** After the visual-state request, in case its callback never comes. */
        private const val VISUAL_STATE_TIMEOUT_MS = 600L

        /** After an Instagram page finished loading, wait this long for the script's "ready". */
        private const val SCRIPT_READY_GRACE_MS = 3_000L
        private const val REFRESH_TIMEOUT_MS = 20_000L

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
