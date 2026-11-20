package app.throwback.core

import app.throwback.web.InstagramWebController
import app.throwback.web.PageFeatures
import app.throwback.ui.UiPrefs
import app.throwback.ui.messages.MessagesOffState
import app.throwback.ui.messages.MessagesOffHooks
import app.throwback.timelimit.GuardOffPinScreen
import app.throwback.timelimit.GuardOffApi
import app.throwback.relay.CallInApp
import android.widget.Toast
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
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
import app.throwback.ui.InstagramAppPrompt
import app.throwback.ui.ReelPoster
import app.throwback.ui.SettingsHint
import app.throwback.ui.markSettingsHintShown
import app.throwback.ui.shouldShowSettingsHint
import app.throwback.ui.MindfulPauseScreen
import app.throwback.ui.OnboardingFlow
import app.throwback.ui.SettingsRoot
import app.throwback.ui.SettingsStart
import app.throwback.ui.shouldOfferInstagramPrompt
import app.throwback.web.InstagramScreen
import app.throwback.web.InstagramSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Owned by integration: decides what is on screen and wires the three feature areas together.
 *
 * Layering on the main screen (bottom to top): Instagram WebView, Throwback's top-start controls
 * (home feed only), time-limit overlay, settings. The WebView stays alive underneath everything
 * so the user never loses their place.
 */
@Composable
fun AppRoot(
    app: ThrowbackApp,
    deepLink: String?,
    onCloseApp: () -> Unit,
    onSplashHold: (Boolean) -> Unit,
    debugOpen: String? = null,
    settingsRequests: Int = 0,
    messagesRequests: Int = 0,
) {
    StartTrace.mark("approot")
    val prefs = app.uiPreferences
    val onboardingDone by prefs.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var settingsOpen by rememberSaveable {
        mutableStateOf(
            when (debugOpen) { // test builds only, see MainActivity
                "settings" -> SettingsStart.Home
                "messages" -> SettingsStart.MessagesAndCalls
                else -> null
            },
        )
    }

    // With the Instagram app connected, "+" and "Your story" open its create screen: tell the page.
    val messagesConnected by UiPrefs.from(LocalContext.current).messagesSetupDone.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(messagesConnected) {
        PageFeatures.createInApp = messagesConnected
        InstagramWebController.current?.get()?.pushFeatures()
    }

    // A Messages & calls turn-off scheduled with the PIN is due (a new day): turn it off now.
    val limitView = GuardOffApi.view(app.timeLimit)?.collectAsStateWithLifecycle()?.value
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(limitView?.guardOffDue) {
        if (limitView?.guardOffDue == true) {
            UiPrefs.from(appContext).setMessagesSetupDone(false)
            GuardOffApi.handled(app.timeLimit)
        }
    }

    // "Throwback settings" app-icon shortcut (MainActivity counts the requests).
    LaunchedEffect(settingsRequests) { if (settingsRequests > 0) settingsOpen = SettingsStart.Home }

    Box(Modifier.fillMaxSize().background(ThrowbackTheme.colors.bg)) {
        when (onboardingDone) {
            null -> SideEffect { onSplashHold(true) } // DataStore still loading.
            false -> {
                SideEffect { onSplashHold(false) }
                OnboardingFlow(onFinished = { scope.launch { prefs.setOnboardingDone() } })
            }
            true -> MainScreen(
                app = app,
                deepLink = deepLink,
                settingsOpen = settingsOpen != null,
                onSplashHold = onSplashHold,
                onCloseApp = onCloseApp,
                onOpenSettings = { settingsOpen = SettingsStart.Home },
                onOpenMessagesSetup = { settingsOpen = SettingsStart.MessagesAndCalls },
                messagesRequests = messagesRequests,
            )
        }

        settingsOpen?.let { start ->
            // SettingsRoot handles system back itself and calls onClose at its root.
            SettingsRoot(
                onClose = { settingsOpen = null },
                startAt = start,
                timeLimitScreen = { onClose -> TimeLimitSettingsScreen(app.timeLimit, onClose) },
                todayScreen = { onClose -> TodayRoute(app.timeLimit, onClose) },
                messagesOff = remember {
                    MessagesOffHooks(
                        state = {
                            val v = GuardOffApi.view(app.timeLimit)?.collectAsStateWithLifecycle()?.value
                            MessagesOffState(limitOn = v?.limitMinutes != null, pendingDate = v?.pendingGuardOff)
                        },
                        cancel = { GuardOffApi.cancel(app.timeLimit) },
                        pinScreen = { onScheduled, onBack -> GuardOffPinScreen(app.timeLimit, onScheduled, onBack) },
                    )
                },
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
    onOpenMessagesSetup: () -> Unit,
    messagesRequests: Int = 0,
) {
    val timeLimit = app.timeLimit
    StartTrace.mark("mainscreen")
    val lockState by timeLimit.state.collectAsStateWithLifecycle()
    // null while loading, so the feed never flashes for a frame before the pause screen.
    val mindfulPause by app.uiPreferences.mindfulPause.collectAsStateWithLifecycle(initialValue = null)
    var pauseDone by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(WebMode.FULL) }
    var path by remember { mutableStateOf("/") }
    val loggedIn by InstagramSession.loggedIn.collectAsStateWithLifecycle()

    // After the first login, offer the "Instagram app found" choice once (if the app is installed).
    val context = LocalContext.current
    var offerInstagramPrompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (loggedIn && shouldOfferInstagramPrompt(context)) offerInstagramPrompt = true
    }

    // Reel allowance in Instagram's swipe viewer used up: the poster, then back to the chat.
    var reelPoster by rememberSaveable { mutableStateOf(false) }

    // Once, after setup: where Throwback's settings are (long-press Home). Opening settings any other
    // way also counts as found.
    var settingsHint by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (loggedIn && shouldShowSettingsHint(context)) {
            delay(SETTINGS_HINT_DELAY_MS) // let the feed appear first
            settingsHint = true
        }
    }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            markSettingsHintShown(context)
            settingsHint = false
        }
    }

    // A relayed message notification was tapped (Messages & calls): if the limit is locked, open
    // messages-only mode instead of the lock screen. Waits briefly for the lock state on a cold start.
    LaunchedEffect(messagesRequests) {
        if (messagesRequests > 0) {
            withTimeoutOrNull(LOCK_STATE_WAIT_MS) { timeLimit.state.first { it is LockState.Locked } }
                ?.let { mode = WebMode.MESSAGES_ONLY }
        }
    }

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

    // Hold the system splash only until preferences are loaded. It must not wait for the WebView:
    // the WebView can't draw (and so can't report itself ready) while the splash blocks drawing.
    // Unfiltered content is still never visible: the web layer's own loading cover hides the page
    // until the filtered page is ready.
    SideEffect { onSplashHold(mindfulPause == null) }

    if (mindfulPause == null) return
    StartTrace.mark("mainscreen-ready")

    val lockShowing = mode == WebMode.FULL && lockState is LockState.Locked

    Box(Modifier.fillMaxSize()) {
        InstagramScreen(
            mode = mode,
            startUrl = deepLink,
            onLocationChanged = { path = it },
            onExitRequest = onCloseApp,
            onOpenSettings = onOpenSettings,
            onCallInApp = { thread, user ->
                // No Instagram app: explain, and offer the Messages & calls setup (it installs it).
                when (CallInApp.open(context, thread, user)) {
                    CallInApp.Result.OPENED -> Unit
                    CallInApp.Result.NO_APP -> {
                        Toast.makeText(context, R.string.call_needs_instagram_app, Toast.LENGTH_LONG).show()
                        onOpenMessagesSetup()
                    }
                    CallInApp.Result.LIMITED -> Toast.makeText(context, R.string.call_too_many, Toast.LENGTH_LONG).show()
                }
            },
            backEnabled = !settingsOpen && !pauseShowing && !reelPoster,
            covered = settingsOpen || pauseShowing || lockShowing || reelPoster,
            onReelLimit = {
                Log.d("ThrowbackApp", "reel poster shown (was showing: $reelPoster)")
                reelPoster = true
            },
            // A calm, positive moment: after a week of use, Google Play's rating card (asked once).
            onCaughtUp = { (context as? android.app.Activity)?.let { RateAsk.onGoodMoment(it) } },
            onCreateInApp = {
                if (!CallInApp.create(context)) {
                    Toast.makeText(context, R.string.create_in_app_failed, Toast.LENGTH_LONG).show()
                }
            },
            onSettingsInApp = {
                if (!CallInApp.settings(context)) {
                    Toast.makeText(context, R.string.create_in_app_failed, Toast.LENGTH_LONG).show()
                }
            },
            // While locked or paused, screen readers must not reach the feed underneath.
            modifier = if (lockShowing || pauseShowing) Modifier.clearAndSetSemantics {} else Modifier,
        )

        // No Throwback button on the feed (owner's decision): Settings opens from the app icon's
        // long-press shortcut. Only the time-limit chip shows, top-centre, on the logged-in home feed.
        val onHomeFeed = path == "/" || path.isEmpty()
        if (loggedIn && onHomeFeed && mode == WebMode.FULL && !pauseShowing) {
            TimeRemainingChip(timeLimit, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp))
        }

        if (mode == WebMode.MESSAGES_ONLY) {
            MessagesOnlyBar(
                onAskForMoreTime = { mode = WebMode.FULL },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (offerInstagramPrompt && !lockShowing && !pauseShowing) {
            InstagramAppPrompt(
                onOpenMessagesSetup = {
                    offerInstagramPrompt = false
                    onOpenMessagesSetup()
                },
                onDone = { offerInstagramPrompt = false },
            )
        }

        if (settingsHint && loggedIn && onHomeFeed && mode == WebMode.FULL && !offerInstagramPrompt &&
            !lockShowing && !pauseShowing && !settingsOpen
        ) {
            SettingsHint(
                onDone = { settingsHint = false },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 54.dp),
            )
        }

        if (reelPoster && !lockShowing) {
            // The page has already closed Instagram's reel viewer: the poster just goes away.
            ReelPoster(onBack = {
                Log.d("ThrowbackApp", "reel poster dismissed (Back)")
                reelPoster = false
            })
        }

        if (lockShowing) {
            TimeLimitOverlay(
                controller = timeLimit,
                onOpenMessagesOnly = { mode = WebMode.MESSAGES_ONLY },
                onCloseApp = onCloseApp,
            )
        }

        // On top of Instagram, which already loads underneath: the feed is ready when the pause ends.
        if (pauseShowing) {
            // Taps on the pause's empty areas must not reach the page loading underneath.
            Box(
                // A plain click handler takes the touches; a consume-everything filter cancelled taps
                // on the pause's own buttons (a finger always moves a little).
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            ) {
                MindfulPauseScreen(onContinue = { pauseDone = true }, onNotNow = onCloseApp)
            }
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

private const val SETTINGS_HINT_DELAY_MS = 1500L
private const val LOCK_STATE_WAIT_MS = 3000L
