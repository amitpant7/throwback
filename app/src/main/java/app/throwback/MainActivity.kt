package app.throwback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.throwback.core.AppRoot
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.relay.RelayLinks
import app.throwback.relay.RelayNotifier

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
        installSplashScreen().setKeepOnScreenCondition { holdSplash }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = intent.instagramUrl()
        app.throwback.core.RateAsk.noteUse(this)
        if (intent.action == ACTION_OPEN_SETTINGS) settingsRequests.intValue++
        if (intent.action == RelayNotifier.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
        // Test builds only: `adb shell am start -n app.throwback/.MainActivity --es debug_open settings`
        // (or messages) opens Settings directly, so screens can be checked without an Instagram login.
        val debugOpen = if (BuildConfig.DEBUG) intent.getStringExtra("debug_open") else null
        val app = application as ThrowbackApp
        setContent {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A repeat of the same link (two message notifications for the inbox) must open it again.
        intent.instagramUrl()?.let { deepLink.value = RelayLinks.repeatable(it, deepLink.value) }
        if (intent.action == ACTION_OPEN_SETTINGS) settingsRequests.intValue++
        if (intent.action == RelayNotifier.ACTION_OPEN_MESSAGES) messagesRequests.intValue++
    }

    private fun Intent.instagramUrl(): String? =
        dataString?.takeIf { it.startsWith("https://www.instagram.com") || it.startsWith("https://instagram.com") }

    companion object {
        /** Intent action of the "Throwback settings" app-icon shortcut (res/xml/shortcuts.xml). */
        const val ACTION_OPEN_SETTINGS = "app.throwback.action.OPEN_SETTINGS"
    }
}
