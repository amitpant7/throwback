package app.throwback.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.ThrowbackApp
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.timelimit.TimeLimitOverlay
import app.throwback.timelimit.TimeLimitSettingsScreen
import app.throwback.timelimit.TimeRemainingChip
import app.throwback.ui.MindfulPauseScreen
import app.throwback.ui.OnboardingFlow
import app.throwback.ui.SettingsRoot
import app.throwback.ui.SettingsStart
import app.throwback.web.InstagramScreen
import kotlinx.coroutines.launch

/**
 * Owned by integration: decides what is on screen and wires the three feature areas together.
 *
 * Layering on the main screen (bottom to top): Instagram WebView, Throwback's top-start controls
 * (home feed only), time-limit overlay, settings. The WebView stays alive underneath everything
 * so the user never loses their place.
 */
@Composable
fun AppRoot(app: ThrowbackApp, deepLink: String?, onCloseApp: () -> Unit) {
    val prefs = app.uiPreferences
    val onboardingDone by prefs.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var settingsOpen by rememberSaveable { mutableStateOf<SettingsStart?>(null) }

    Box(Modifier.fillMaxSize().background(ThrowbackTheme.colors.bg)) {
        when (onboardingDone) {
            null -> Unit // DataStore still loading; the splash screen covers this frame.
            false -> OnboardingFlow(
                onFinished = { scope.launch { prefs.setOnboardingDone() } },
                onOpenMessagesSetup = {
                    scope.launch { prefs.setOnboardingDone() }
                    settingsOpen = SettingsStart.MessagesAndCalls
                },
            )
            true -> MainScreen(app, deepLink, onCloseApp, onOpenSettings = { settingsOpen = SettingsStart.Home })
        }

        settingsOpen?.let { start ->
            // SettingsRoot handles system back itself and calls onClose at its root.
            SettingsRoot(
                onClose = { settingsOpen = null },
                startAt = start,
                timeLimitScreen = { onClose -> TimeLimitSettingsScreen(app.timeLimit, onClose) },
            )
        }
    }
}

@Composable
private fun MainScreen(app: ThrowbackApp, deepLink: String?, onCloseApp: () -> Unit, onOpenSettings: () -> Unit) {
    val timeLimit = app.timeLimit
    val lockState by timeLimit.state.collectAsStateWithLifecycle()
    // null while loading, so the feed never flashes for a frame before the pause screen.
    val mindfulPause by app.uiPreferences.mindfulPause.collectAsStateWithLifecycle(initialValue = null)
    var pauseDone by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(WebMode.FULL) }
    var path by remember { mutableStateOf("/") }

    // Leaving the lock (extension granted, or a new day) returns to the full app.
    LaunchedEffect(lockState) {
        if (lockState !is LockState.Locked && mode == WebMode.MESSAGES_ONLY) mode = WebMode.FULL
    }
    LaunchedEffect(mode) { timeLimit.onForeground(mode) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { timeLimit.onForeground(mode) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { timeLimit.onBackground() }

    if (mindfulPause == null) return
    if (mindfulPause == true && !pauseDone && deepLink == null) {
        MindfulPauseScreen(onContinue = { pauseDone = true }, onNotNow = onCloseApp)
        return
    }

    Box(Modifier.fillMaxSize()) {
        InstagramScreen(
            mode = mode,
            startUrl = deepLink,
            onLocationChanged = { path = it },
            onExitRequest = onCloseApp,
        )

        val onHomeFeed = path == "/" || path.isEmpty()
        if (onHomeFeed && mode == WebMode.FULL) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThrowbackMarkButton(onClick = onOpenSettings)
                TimeRemainingChip(timeLimit, Modifier.padding(start = 8.dp))
            }
        }

        if (mode == WebMode.FULL && lockState is LockState.Locked) {
            TimeLimitOverlay(
                controller = timeLimit,
                onOpenMessagesOnly = { mode = WebMode.MESSAGES_ONLY },
                onCloseApp = onCloseApp,
            )
        }
    }
}
