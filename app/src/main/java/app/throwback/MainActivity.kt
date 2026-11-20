package app.throwback

import app.throwback.core.StartTrace
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.throwback.core.AppRoot
import app.throwback.core.RateAsk
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.relay.RelayLinks
import app.throwback.relay.RelayNotifier
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
            if (intent.action == ACTION_OPEN_SETTINGS) settingsRequests.intValue++
            if (intent.action == RelayNotifier.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
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
     * it is already read) and the day count for the rating ask (SharedPreferences).
     */
    private fun warmUp() {
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            launch { runCatching { PageAssets.load(context) } }
            launch { runCatching { RateAsk.noteUse(context) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A repeat of the same link (two message notifications for the inbox) must open it again.
        intent.instagramUrl()?.let { deepLink.value = RelayLinks.repeatable(it, deepLink.value) }
        if (intent.action == ACTION_OPEN_SETTINGS) settingsRequests.intValue++
        if (intent.action == RelayNotifier.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
    }

    /**
     * The intent's https instagram.com link, by its parsed host (any app can start this activity).
     * Backslashes and control characters are refused: Chromium reads such a URL's host differently.
     */
    private fun Intent.instagramUrl(): String? = dataString?.takeIf { url ->
        !UrlParts.hasAmbiguousChars(url) &&
            Uri.parse(url).let { it.scheme.equals("https", ignoreCase = true) && it.host?.lowercase() in DEEP_LINK_HOSTS }
    }

    companion object {
        /** Intent action of the "Throwback settings" app-icon shortcut (res/xml/shortcuts.xml). */
        const val ACTION_OPEN_SETTINGS = "app.throwback.action.OPEN_SETTINGS"

        /** The hosts of the manifest's VIEW intent filter. */
        private val DEEP_LINK_HOSTS = setOf("www.instagram.com", "instagram.com")

        private const val STATE_DEEP_LINK = "deepLink"
    }
}
