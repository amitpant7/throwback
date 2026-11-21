package app.throwback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.throwback.billing.Tips
import app.throwback.connect.relay.RelayLinks
import app.throwback.core.OpenThrowback
import app.throwback.core.RateAsk
import app.throwback.core.StartTrace
import app.throwback.core.Supporter
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.web.PageAssets
import app.throwback.web.UrlParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owned by integration. */
class MainActivity : ComponentActivity() {

    /** instagram.com link this activity was opened with (from another app or a notification). */
    private val deepLink = mutableStateOf<String?>(null)

    /** Bumped each time the "Throwback settings" shortcut opens the app. */
    private val settingsRequests = mutableIntStateOf(0)

    /** Bumped each time a relayed message notification opens the app (messages-only mode if locked). */
    private val messagesRequests = mutableIntStateOf(0)

    /** Bumped each time a link or a notification asks the running app for a page: Settings closes to show it. */
    private val pageRequests = mutableIntStateOf(0)

    /** Set by AppRoot: keep the splash up until the first screen is ready (no flash of unfiltered Instagram). */
    @Volatile
    private var holdSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        StartTrace.mark("activity")
        installSplashScreen().setKeepOnScreenCondition { holdSplash }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deepLink.value = intent.instagramUrl()
            if (intent.action == OpenThrowback.ACTION_OPEN_SETTINGS) settingsRequests.intValue++
            if (intent.action == OpenThrowback.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
        } else {
            // Recreated (font size, language, process death): the page and the screens restore
            // themselves, and the intent that opened the activity must not open again. The link is
            // the one last shown, as RelayLinks.repeatable made it (the intent's may lack its '#').
            deepLink.value = savedInstanceState.getString(STATE_DEEP_LINK)
        }
        warmUp()
        // Test builds only: `adb shell am start -n app.throwback/.MainActivity --es debug_open settings`
        // (or messages) opens Settings directly, so screens can be checked without an Instagram login.
        val debugOpen = if (BuildConfig.DEBUG) intent.getStringExtra("debug_open") else null
        // Test builds only: `--ez debug_supporter true` acts as if a tip went through (unlocks Make It Yours).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_supporter", false)) Supporter.markTipped(this)
        // Tips Google Play remembers unlock Make It Yours again after a reinstall; paid ones get acknowledged.
        if (savedInstanceState == null) Tips.refresh(this)
        val app = application as ThrowbackApp
        StartTrace.mark("setContent")
        setContent {
            StartTrace.mark("compose")
            ThrowbackTheme {
                AppRoot(
                    app = app,
                    deepLink = deepLink.value,
                    onCloseApp = { finish() },
                    onSplashHold = { holdSplash = it },
                    debugOpen = debugOpen,
                    settingsRequests = settingsRequests.intValue,
                    messagesRequests = messagesRequests.intValue,
                    pageRequests = pageRequests.intValue,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DEEP_LINK, deepLink.value)
    }

    /**
     * Off the main thread during the cold start: the page script (the first page starts sooner when
     * it is already read).
     */
    private fun warmUp() {
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { runCatching { PageAssets.load(context) } }
    }

    override fun onStart() {
        super.onStart()
        // A day of use for the rating ask, each time Throwback comes to the front: the activity can
        // live on for days (singleTask), so its creation alone missed days. Off the main thread.
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { runCatching { RateAsk.noteUse(context) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A repeat of the same link (two message notifications for the inbox) must open it again.
        val url = intent.instagramUrl()
        url?.let { deepLink.value = RelayLinks.repeatable(it, deepLink.value) }
        if (intent.action == OpenThrowback.ACTION_OPEN_SETTINGS) settingsRequests.intValue++
        if (intent.action == OpenThrowback.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
        if (url != null || intent.action == OpenThrowback.ACTION_OPEN_MESSAGES) pageRequests.intValue++
    }

    /**
     * The intent's https instagram.com link, by its parsed host (any app can start this activity).
     * Backslashes and control characters are refused: Chromium reads such a URL's host differently.
     */
    private fun Intent.instagramUrl(): String? = dataString?.takeIf { url ->
        !UrlParts.hasAmbiguousChars(url) &&
            url.toUri().let { it.scheme.equals("https", ignoreCase = true) && it.host?.lowercase() in DEEP_LINK_HOSTS }
    }

    companion object {
        /** The hosts of the manifest's VIEW intent filter. */
        private val DEEP_LINK_HOSTS = setOf("www.instagram.com", "instagram.com")

        private const val STATE_DEEP_LINK = "deepLink"
    }
}
