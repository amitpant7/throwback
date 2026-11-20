package app.throwback

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.throwback.core.AppRoot
import app.throwback.core.theme.ThrowbackTheme

/** Owned by integration. */
class MainActivity : ComponentActivity() {

    /** instagram.com link this activity was opened with (from another app or a notification). */
    private val deepLink = mutableStateOf<String?>(null)

    /** Set by AppRoot: keep the splash up until the first screen is ready (no flash of unfiltered Instagram). */
    @Volatile
    private var holdSplash = true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { holdSplash }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = intent.instagramUrl()
        val app = application as ThrowbackApp
        setContent {
            ThrowbackTheme {
                AppRoot(
                    app = app,
                    deepLink = deepLink.value,
                    onCloseApp = { finish() },
                    onSplashHold = { holdSplash = it },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.instagramUrl()?.let { deepLink.value = it }
    }

    private fun Intent.instagramUrl(): String? =
        dataString?.takeIf { it.startsWith("https://www.instagram.com") || it.startsWith("https://instagram.com") }
}
