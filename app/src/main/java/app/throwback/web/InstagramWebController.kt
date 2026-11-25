package app.throwback.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
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
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.throwback.core.StartTrace
import app.throwback.core.WebMode
import app.throwback.web.rules.FilterRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/** A short message shown over the page. */
internal enum class WebNotice { REELS, SUGGESTED, MESSAGES_ONLY, HIDDEN, NO_APP }

internal data class WebToast(val notice: WebNotice, val id: Long)

/** A main-frame load failed. [retrying] while a retry is in flight. */
internal data class LoadError(val offline: Boolean, val url: String?, val retrying: Boolean = false)

/**
 * The WebView's renderer process went away: it [crashed], or the system killed it (memory).
 * [inFront]: the app was started at the time.
 */
internal data class RendererGone(val crashed: Boolean, val inFront: Boolean)

/**
 * Owns the single long-lived Instagram WebView and everything wired to it: settings, the page
 * script (its configuration kept current by [PageConfigSync]), the message bridge, navigation rules,
 * errors, pull-to-refresh and file uploads.
 * Compose reads its state; InstagramScreen drives it. Main thread only.
 */
internal class InstagramWebController(
    private val context: Context,
    assets: PageAssets,
    initialMode: WebMode,
    initialHandledStartUrl: String?,
    initialFeatures: PageFeatures,
) {
    /** What the page asks of the app: InstagramScreen's callbacks, in one object. */
    class Host(
        /** The location changed: the path on Instagram, the full URL elsewhere; and the URL. */
        val onLocationChanged: (location: String, url: String) -> Unit,
        val onStartUrlHandled: (url: String?) -> Unit,
        /** Throwback's settings were asked for (profile icon, Instagram's settings page, Home long-press). */
        val onOpenSettings: () -> Unit,
        /** The chat's call button: open the chat (with the user, else the chat thread) in the Instagram app. */
        val onCallInApp: (thread: String?, user: String?) -> Unit,
        /** The reel allowance in Instagram's swipe viewer is used up. */
        val onReelLimit: () -> Unit,
        /** The feed reached "You're all caught up". */
        val onCaughtUp: () -> Unit,
        /** "+" / "Your story" with the Instagram app connected. */
        val onCreateInApp: (story: Boolean) -> Unit,
        /** "Open settings in the Instagram app". */
        val onSettingsInApp: () -> Unit,
        /** "Your story" went to the website's upload (the app route is waiting). */
        val onStoryWeb: () -> Unit,
        /** The support line was shown at "You're all caught up". */
        val onSupportAskShown: () -> Unit,
        /** "Say thanks" on the support line. */
        val onSupportThanks: () -> Unit,
    )

    /** The screen's latest callbacks (set on every composition). */
    var host: Host? = null

    /** Opens a picker for a file input; must eventually call [deliverFiles]. False if none could open. */
    var pickFiles: ((kind: PickKind, multiple: Boolean, documentIntent: Intent?) -> Boolean)? = null

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

    /** Set when the renderer process went away (then this controller is destroyed): whether it crashed. */
    var rendererGone by mutableStateOf<RendererGone?>(null)
        private set

    /** The page's configuration: rules, mode, features and the trusted clock. */
    private val config: PageConfigSync
    private val mode: WebMode get() = config.mode
    private var started = false

    private var router: UrlRouter? = null
    private var handledStartUrl = initialHandledStartUrl
    private var lastLocation: String? = null
    private var failedUrl: String? = null

    /** The main-frame URL being loaded, to tell main-frame SSL errors from subresource ones. */
    private var navigatingTo: String? = null

    /** Whether the main-frame navigation under way began with a tap, for its server redirects. */
    private val chainGesture = ChainGesture()
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
        config = PageConfigSync(context, assets, injector, initialMode, initialFeatures, pageOpen = { started })
        current = WeakReference(this)
    }

    /** Today's counts from the page (JSON, see PageDayStats), or null when the page can't say. */
    fun dayStats(onResult: (String?) -> Unit) {
        if (destroyed) return onResult(null)
        webView.evaluateJavascript(
            "(function(){try{return window.__throwback&&__throwback.dayStats?__throwback.dayStats():null}catch(e){return null}})()",
        ) { raw ->
            // The value comes back JSON-encoded: a quoted string, or null.
            val text = try {
                (Json.parseToJsonElement(raw ?: "null") as? JsonPrimitive)
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
        if (destroyed || newRules == config.rules) return
        router = UrlRouter(newRules)
        config.setRules(newRules)
    }

    /**
     * First call: loads the deep link (if not already handled), else the page to restore, else
     * home. Later calls: opens [startUrl] if it is a new deep link.
     */
    fun openOrStart(startUrl: String?, restoreUrl: String?) {
        if (destroyed) return
        val router = router ?: return
        if (!started) {
            started = true
            val freshLink = startUrl?.takeIf { it != handledStartUrl }
            markStartHandled(startUrl)
            // Logged out, home ("/") is Instagram's "Log in or sign up" page: reportLocation sends it
            // on to the login form. Reading the login cookie here would hold up the first frame.
            load(router.startUrl(freshLink ?: restoreUrl, mode))
            handler.postDelayed(revealRunnable, REVEAL_TIMEOUT_MS)
        } else if (startUrl != null && startUrl != handledStartUrl) {
            markStartHandled(startUrl)
            error = null
            load(router.startUrl(startUrl, mode))
        }
    }

    /** A volume key was pressed: the page turns the sound on for the video on screen (#34kk). */
    fun onVolumeKey() {
        if (destroyed) return
        webView.evaluateJavascript("window.__throwback && window.__throwback.volumeKey && window.__throwback.volumeKey()", null)
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
        if (destroyed) return
        config.setMode(newMode) { if (!destroyed) enforceModeNatively() }
    }

    /** Back: through the WebView history (skipping pages this mode doesn't show). False = nothing left. */
    fun goBack(): Boolean {
        if (destroyed) return false
        val router = router ?: return false
        if (mode == WebMode.MESSAGES_ONLY) {
            val current = webView.url
            val history = webView.copyBackForwardList()
            for (i in history.currentIndex - 1 downTo 0) {
                val url = history.getItemAtIndex(i)?.url ?: continue
                // Judged as a step from this page: a Facebook page doesn't vouch for itself.
                if (router.decide(url, mode, currentUrl = current) == UrlRouter.Decision.Load) {
                    error = null
                    webView.goBackOrForward(i - history.currentIndex)
                    return true
                }
            }
            // Nothing behind us this mode shows: from a chat go to the inbox, from the inbox leave.
            val inbox = router.homeUrl(mode)
            if (current != null && router.locationFor(current) != router.locationFor(inbox)) {
                error = null
                load(inbox)
                return true
            }
            return false
        }
        // Instagram's login form is the first screen while logged out: Back leaves Throwback instead of
        // stepping back to the logged-out start page ("from Meta", #34ll). Steps of a login (Facebook,
        // two-factor) still go back one at a time, to this form at most.
        if (!InstagramSession.loggedIn.value && isLoginForm(webView.url)) return false
        if (!webView.canGoBack()) return false
        error = null
        webView.goBack()
        return true
    }

    /** Instagram's login form (or its logged-out start page, which leads to it). */
    private fun isLoginForm(url: String?): Boolean {
        val uri = url?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        if (uri.host?.lowercase() != "www.instagram.com" && uri.host?.lowercase() != "instagram.com") return false
        val path = uri.path.orEmpty()
        return path == "/" || path.isEmpty() || LOGIN_FORM.matches(path)
    }

    fun retry() {
        if (destroyed) return
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
        if (destroyed) return
        val previous = nightMode
        nightMode = uiModeNight
        if (previous != null && previous != uiModeNight) WebViewConfig.refreshColorScheme(webView.settings)
    }

    /** In front: the WebView runs again, and the page's clock is kept fresh (PageConfigSync). */
    fun onStart() {
        if (destroyed) return
        webView.onResume()
        webView.resumeTimers()
        config.onStart()
    }

    /** In the background: stop timers and animations (saves battery), keep the login. */
    fun onStop() {
        if (destroyed) return
        config.onStop()
        webView.onPause()
        webView.pauseTimers()
        flushCookies()
    }

    /** Writes the login to disk. flush() waits for the disk, so it runs off the main thread. */
    private fun flushCookies() {
        val cookies = CookieManager.getInstance()
        thread(name = "ThrowbackCookieFlush") { cookies.flush() }
    }

    fun deliverFiles(uris: Array<Uri>?) {
        pendingFiles?.onReceiveValue(uris)
        pendingFiles = null
    }

    /** The app's features changed (e.g. the Instagram app got connected): tell the page. */
    fun setFeatures(value: PageFeatures) {
        if (destroyed) return
        config.setFeatures(value)
    }

    private fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        if (!startOutside(Intent.createChooser(send, null))) Log.w(TAG, "no app to share with")
    }

    /** Detaches and destroys the WebView. Idempotent: also called when the renderer goes away. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (current?.get() === this) current = null
        handler.removeCallbacksAndMessages(null)
        config.release()
        deliverFiles(null)
        injector.release()
        flushCookies()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    // ------------------------------------------------------------------ navigation

    private fun load(url: String) {
        StartTrace.mark("load")
        chainGesture.reset()
        navigatingTo = url
        webView.loadUrl(url)
    }

    private fun markStartHandled(url: String?) {
        handledStartUrl = url
        host?.onStartUrlHandled(url)
    }

    /**
     * @param userGesture a tap started it (or its redirect chain): only then may it open another app.
     * @return true when the WebView must not load [url] itself.
     */
    private fun handleNavigation(url: String, currentUrl: String?, userGesture: Boolean): Boolean {
        val router = router ?: return false
        return when (val d = router.decide(url, mode, currentUrl, userGesture)) {
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
        d.kind?.let { showNotice(noticeFor(it)) } // blocked content (reels, suggestions, messages only)
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

    /** The main frame's address as last committed or pushed: where the next one comes from. */
    private var shownUrl: String? = null

    /**
     * The backstop for loads shouldOverrideUrlLoading never sees (form posts, back/forward): a page
     * outside Instagram that the router wouldn't load, reached from [from], is left at once (back, else
     * home). Instagram's own pages are the page script's to check. True when it left.
     */
    private fun leaveIfNotAllowed(url: String, from: String?): Boolean {
        val router = router ?: return false
        if (router.isInstagram(url)) return false
        return when (val d = router.decide(url, mode, from, userGesture = false)) {
            UrlRouter.Decision.Load -> false
            is UrlRouter.Decision.Redirect -> redirect(d)
            else -> {
                Log.w(TAG, "Left a page outside Instagram that this mode doesn't load")
                webView.stopLoading()
                if (webView.canGoBack()) webView.goBack() else load(router.homeUrl(mode))
                true
            }
        }
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
        // Not as a step in a login: Accounts Center then goes to the inbox in messages-only mode.
        val d = router.decide(url, mode, currentUrl = null)
        if (d is UrlRouter.Decision.Redirect) load(d.url)
    }

    private fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (!startOutside(intent)) showNotice(WebNotice.NO_APP)
    }

    /**
     * Starts another app's activity from the screen's activity. False when no app may take it: none
     * found, not allowed, or a link Android refuses to hand over (it throws on file: links).
     */
    private fun startOutside(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * One report per location change: every load reports from onPageStarted, doUpdateVisitedHistory
     * and the page's own `loc`, and reading the login cookie waits on another thread.
     */
    private fun reportLocation(url: String?) {
        val router = router ?: return
        if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
        val location = router.locationFor(url)
        if (location == lastLocation) return
        lastLocation = location
        val loggedIn = InstagramSession.refresh()
        if (loggedIn) {
            loginRedirects = 0 // a later logout gets the login form again
        } else if (location == "/" && loginRedirects < MAX_LOGIN_REDIRECTS) {
            // Logged out on the landing page (the first start, or after logging out): show the login
            // form directly. Capped, so a cookie that can't be read can never cause a redirect loop.
            loginRedirects++
            load(InstagramSession.LOGIN_URL)
            return
        }
        host?.onLocationChanged(location, url)
    }

    /** Logged-out landings sent to the login form since the user was last seen logged in. */
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
                if (isMainFrame) onBridgeMessage(message.data, trusted = true)
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(), LEGACY_BRIDGE_OBJECT)
        }
    }

    /**
     * For WebViews without WEB_MESSAGE_LISTENER. Injected into every frame and only the top-level
     * URL can be checked, so a frame could pretend to be the page: messages that open another app or
     * start a pass are dropped here (see [BridgeMessage.opensOtherApp]).
     */
    private inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(json: String?) {
            handler.post {
                if (!destroyed && router?.isInstagram(webView.url) == true) onBridgeMessage(json, trusted = false)
            }
        }
    }

    /** The current document's script has spoken (any message); reset when a new document starts. */
    private var scriptSeen = false

    /** The current document's first `loc` has arrived; reset when a new document starts. */
    private var locSeen = false

    /** @param trusted from Instagram's own top-level document (not the legacy bridge). */
    private fun onBridgeMessage(json: String?, trusted: Boolean) {
        if (destroyed) return
        val m = BridgeMessage.parse(json) ?: return
        if (!trusted && m.opensOtherApp) return
        scriptSeen = true
        when (m) {
            is BridgeMessage.Location -> {
                // A new document starts uncovered: cover it at once, not only at "ready" (seconds
                // later), or its feed and day clocks count time under the pause or Settings.
                if (!locSeen && covered) pushCovered()
                locSeen = true
                reportLocation(m.url)
            }
            BridgeMessage.Ready -> {
                requestReveal()
                if (covered) pushCovered() // a new page starts uncovered
                config.onPageReady()
            }
            is BridgeMessage.ServerTime -> PageClock.serverTimeSink?.invoke(m.millis)
            is BridgeMessage.Blocked -> showNotice(noticeFor(m.kind))
            is BridgeMessage.Touch -> {
                pull.scriptReport(m.atTop)
                pullProgress = pull.progress
            }
            is BridgeMessage.Background -> pageBackground = m.argb
            BridgeMessage.OpenSettings -> host?.onOpenSettings()
            is BridgeMessage.CallInApp -> host?.onCallInApp(m.thread, m.user)
            BridgeMessage.ReelLimit -> host?.onReelLimit()
            BridgeMessage.CaughtUp -> host?.onCaughtUp()
            is BridgeMessage.Share -> share(m.url)
            is BridgeMessage.CreateInApp -> host?.onCreateInApp(m.story)
            BridgeMessage.SettingsInApp -> host?.onSettingsInApp()
            BridgeMessage.StoryWeb -> host?.onStoryWeb()
            BridgeMessage.SupportAskShown -> host?.onSupportAskShown()
            BridgeMessage.SupportThanks -> host?.onSupportThanks()
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
            val tapped = chainGesture.of(request.isRedirect, request.hasGesture())
            return handleNavigation(request.url.toString(), view.url, userGesture = tapped)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            chainGesture.reset()
            if (url != failedUrl) failedUrl = null
            scriptSeen = false
            locSeen = false
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
                when {
                    !instagram -> requestReveal()
                    // A script that has spoken sends "ready" itself, when the page is really ready (it
                    // waits for the first home post on purpose); REVEAL_TIMEOUT_MS still caps the wait.
                    !scriptSeen -> handler.postDelayed(revealRunnable, SCRIPT_READY_GRACE_MS)
                }
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
            val from = shownUrl
            shownUrl = url
            if (url != from && leaveIfNotAllowed(url, from)) return
            reportLocation(url) // also called for pushState, a backup for the page script's report
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning true keeps the app alive, but this WebView can't be used again: it is detached
            // and destroyed now (as the Android docs ask), and InstagramScreen builds a new one.
            Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()}); recreating")
            destroy()
            rendererGone = RendererGone(crashed = detail.didCrash(), inFront = config.inFront)
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
                pickFiles?.invoke(kind, multiple, documentIntent) == true
            } catch (e: ActivityNotFoundException) {
                false
            }
            if (!opened) deliverFiles(null)
            return true
        }

        private val blankPoster by lazy { createBitmap(1, 1) }

        /** Without this, videos show WebView's grey play-button placeholder before they load. */
        override fun getDefaultVideoPoster(): Bitmap = blankPoster
    }

    companion object {
        /** The live page (one at a time), for screens that read from it, such as "Your day". */
        @Volatile
        var current: WeakReference<InstagramWebController>? = null

        private const val MAX_LOGIN_REDIRECTS = 2
        private val LOGIN_FORM = Regex("^/accounts/login/?$")
        private const val TAG = "ThrowbackWeb"
        private const val BRIDGE_OBJECT = "throwbackNative"
        private const val LEGACY_BRIDGE_OBJECT = "throwbackBridge"
        private const val VISUAL_STATE_REQUEST = 1L
        private const val PULL_THRESHOLD_DP = 72

        /** The splash never waits longer than this, whatever happens. */
        private const val REVEAL_TIMEOUT_MS = 12_000L

        /** After the visual-state request, in case its callback never comes. */
        private const val VISUAL_STATE_TIMEOUT_MS = 600L

        /** After an Instagram page finished loading without a word from the script, wait this long for it. */
        private const val SCRIPT_READY_GRACE_MS = 3_000L
        private const val REFRESH_TIMEOUT_MS = 20_000L

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
