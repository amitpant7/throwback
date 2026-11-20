package app.throwback.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.throwback.R
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import app.throwback.web.WebReadiness
import kotlinx.coroutines.launch

/**
 * Owned by integration: decides what is on screen and wires the three feature areas together.
 *
 * Layering on the main screen (bottom to top): Instagram WebView, Throwback's top-start controls
 * (home feed only), time-limit overlay, settings. The WebView stays alive underneath everything
 * so the user never loses their place.
 */
@Composable
fun AppRoot(app: ThrowbackApp, deepLink: String?, onCloseApp: () -> Unit, onSplashHold: (Boolean) -> Unit) {
    val prefs = app.uiPreferences
    val onboardingDone by prefs.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var settingsOpen by rememberSaveable { mutableStateOf<SettingsStart?>(null) }

    Box(Modifier.fillMaxSize().background(ThrowbackTheme.colors.bg)) {
        when (onboardingDone) {
            null -> SideEffect { onSplashHold(true) } // DataStore still loading.
            false -> {
                SideEffect { onSplashHold(false) }
                OnboardingFlow(
                onFinished = { scope.launch { prefs.setOnboardingDone() } },
                onOpenMessagesSetup = {
                    scope.launch { prefs.setOnboardingDone() }
                    settingsOpen = SettingsStart.MessagesAndCalls
                },
                )
            }
            true -> MainScreen(
                app = app,
                deepLink = deepLink,
                settingsOpen = settingsOpen != null,
                onSplashHold = onSplashHold,
                onCloseApp = onCloseApp,
                onOpenSettings = { settingsOpen = SettingsStart.Home },
            )
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
private fun MainScreen(
    app: ThrowbackApp,
    deepLink: String?,
    settingsOpen: Boolean,
    onSplashHold: (Boolean) -> Unit,
    onCloseApp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
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
    // Only time actually spent on Instagram counts: not while Throwback's own settings or the
    // mindful pause cover it, and not while the activity is paused.
    val pauseShowing = mindfulPause == true && !pauseDone && deepLink == null
    val onInstagram = mindfulPause != null && !pauseShowing && !settingsOpen
    var resumed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
    LaunchedEffect(resumed, onInstagram, mode) {
        if (resumed && onInstagram) timeLimit.onForeground(mode) else timeLimit.onBackground()
    }

    // Hold the splash until Instagram's first filtered page is drawn, unless something else
    // (the mindful pause or settings) is what the user will see first. WebReadiness caps the wait.
    val firstPageShown by WebReadiness.firstPageShown.collectAsStateWithLifecycle()
    SideEffect { onSplashHold(mindfulPause == null || (!settingsOpen && !pauseShowing && !firstPageShown)) }

    if (mindfulPause == null) return
    if (pauseShowing) {
        MindfulPauseScreen(onContinue = { pauseDone = true }, onNotNow = onCloseApp)
        return
    }

    val lockShowing = mode == WebMode.FULL && lockState is LockState.Locked

    Box(Modifier.fillMaxSize()) {
        InstagramScreen(
            mode = mode,
            startUrl = deepLink,
            onLocationChanged = { path = it },
            onExitRequest = onCloseApp,
            // While locked, screen readers must not reach the feed under the lock screen.
            modifier = if (lockShowing) Modifier.clearAndSetSemantics {} else Modifier,
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

        if (mode == WebMode.MESSAGES_ONLY) {
            MessagesOnlyBar(
                onAskForMoreTime = { mode = WebMode.FULL },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (lockShowing) {
            TimeLimitOverlay(
                controller = timeLimit,
                onOpenMessagesOnly = { mode = WebMode.MESSAGES_ONLY },
                onCloseApp = onCloseApp,
            )
        }
    }
}

/** Shown at the bottom in messages-only mode, so the user can go back to the lock screen and ask for more time. */
@Composable
private fun MessagesOnlyBar(onAskForMoreTime: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = ThrowbackTheme.colors.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.messages_only_bar),
                style = MaterialTheme.typography.bodySmall,
                color = ThrowbackTheme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAskForMoreTime) {
                Text(stringResource(R.string.messages_only_ask_more), color = ThrowbackTheme.colors.accentText)
            }
        }
    }
}
