package app.throwback.core

import app.throwback.web.WebReadiness
import app.throwback.web.PageFeatures
import app.throwback.ui.UiPrefs
import app.throwback.ui.messages.MessagesOffState
import app.throwback.ui.messages.MessagesOffHooks
import app.throwback.timelimit.GuardOffPinScreen
import app.throwback.timelimit.GuardOffApi
import app.throwback.relay.CallInApp
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.throwback.R
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Owned by integration: decides what is on screen and wires the three feature areas together.
 *
 * Layering on the main screen (bottom to top): Instagram WebView, the time-left chip (home feed
 * only), the one-time cards, the reel poster, the time-limit overlay, the mindful pause, settings.
 * The WebView stays alive underneath everything so the user never loses their place.
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
    val messagesConnected by remember { UiPrefs.from(app).messagesSetupDone }.collectAsStateWithLifecycle(initialValue = false)
    val featureContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(messagesConnected, lifecycleOwner) {
        // Re-checked every minute while Throwback is in front: a story pass becomes available again
        // after its cooldown. The web layer passes each change on to the page.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val story = messagesConnected && CallInApp.storyAvailable(featureContext)
                val support = SupportAsk.due(featureContext)
                PageFeatures.current.update { it.copy(createInApp = messagesConnected, storyInApp = story, supportAsk = support) }
                delay(60_000)
            }
        }
    }

    // A Messages & calls turn-off scheduled with the PIN is due (a new day): turn it off now.
    // Only this flag: the whole view changes every second while a limit runs, recomposing AppRoot.
    val guardOffDue by remember {
        GuardOffApi.view(app.timeLimit)?.map { it.guardOffDue }?.distinctUntilChanged() ?: flowOf(false)
    }.collectAsStateWithLifecycle(initialValue = false)
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(guardOffDue) {
        if (guardOffDue) {
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
                onOpenSupport = { settingsOpen = SettingsStart.Support },
                messagesRequests = messagesRequests,
            )
        }

        settingsOpen?.let { start ->
            // SettingsRoot handles system back itself and calls onClose at its root.
            SettingsRoot(
                onClose = { settingsOpen = null },
                startAt = start,
                timeLimitScreen = { onClose, onLimitSet -> TimeLimitSettingsScreen(app.timeLimit, onClose, onLimitSet) },
                todayScreen = { onClose -> TodayRoute(app.timeLimit, onClose) },
                messagesOff = remember {
                    MessagesOffHooks(
                        // Mapped and distinct, so the screen doesn't recompose with every tick of the view.
                        state = {
                            remember {
                                GuardOffApi.view(app.timeLimit)
                                    ?.map { MessagesOffState(limitOn = it.limitMinutes != null, pendingDate = it.pendingGuardOff) }
                                    ?.distinctUntilChanged()
                                    ?: flowOf(MessagesOffState())
                            }.collectAsStateWithLifecycle(initialValue = MessagesOffState()).value
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
    onOpenSupport: () -> Unit,
    messagesRequests: Int = 0,
) {
    val timeLimit = app.timeLimit
    StartTrace.mark("mainscreen")
    // Only whether it is locked: the state itself changes every second while a limit runs.
    val lockState = timeLimit.state.collectAsStateWithLifecycle()
    val locked by remember { derivedStateOf { lockState.value is LockState.Locked } }
    // null while loading, so the feed never flashes for a frame before the pause screen.
    val mindfulPause by app.uiPreferences.mindfulPause.collectAsStateWithLifecycle(initialValue = null)
    var pauseDone by rememberSaveable { mutableStateOf(false) }
    // Opening Settings ends the pause for this launch. It's a pause before Instagram: opened straight
    // into Settings (the "Throwback settings" shortcut, a notification), it would only run unseen.
    var pauseSkipped by rememberSaveable { mutableStateOf(false) }
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
            pauseSkipped = true
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
    LaunchedEffect(locked) {
        if (!locked && mode == WebMode.MESSAGES_ONLY) mode = WebMode.FULL
    }
    // Only time actually spent on Instagram counts: not while Throwback's own settings or the
    // mindful pause cover it, and not while the activity is paused.
    // After its 4 seconds the pause stays until the feed is ready underneath (at most 5 s more), so a
    // slow connection shows "Almost there…" instead of Instagram's loading screen.
    val pageShown by WebReadiness.firstPageShown.collectAsStateWithLifecycle()
    var pauseOverdue by remember { mutableStateOf(false) }
    LaunchedEffect(pauseDone) {
        if (pauseDone) {
            kotlinx.coroutines.delay(5_000)
            pauseOverdue = true
        }
    }
    val pauseShowing = mindfulPause == true && !pauseSkipped && !(pauseDone && (pageShown || pauseOverdue)) && deepLink == null
    val onInstagram = mindfulPause != null && !pauseShowing && !settingsOpen
    // Straight from ON_RESUME / ON_PAUSE: a "resumed" state read in an effect only re-ran if a frame
    // came between ON_PAUSE and ON_STOP, so after the power button counting might not restart.
    LifecycleResumeEffect(onInstagram, mode) {
        if (onInstagram) timeLimit.onForeground(mode)
        onPauseOrDispose { timeLimit.onBackground() } // also when Settings opens or the mode changes
    }

    // Hold the system splash only until preferences are loaded. It must not wait for the WebView:
    // the WebView can't draw (and so can't report itself ready) while the splash blocks drawing.
    // Unfiltered content is still never visible: the web layer's own loading cover hides the page
    // until the filtered page is ready.
    SideEffect { onSplashHold(mindfulPause == null) }

    if (mindfulPause == null) return
    StartTrace.mark("mainscreen-ready")

    val lockShowing = mode == WebMode.FULL && locked
    val promptShowing = offerInstagramPrompt && !lockShowing && !pauseShowing && !settingsOpen && !reelPoster

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
            // Back reaches the page only when nothing covers it (a layer's own Back comes first).
            backEnabled = !settingsOpen && !pauseShowing && !reelPoster && !lockShowing && !promptShowing,
            covered = settingsOpen || pauseShowing || lockShowing || reelPoster,
            onReelLimit = { reelPoster = true },
            // A calm, positive moment: after a month (30 days, 10 days of use), Google Play's rating card, asked once.
            onCaughtUp = { (context as? android.app.Activity)?.let { RateAsk.onGoodMoment(it) } },
            onCreateInApp = { story ->
                if (story) {
                    val result = CallInApp.story(context)
                    CallInApp.storyAvailabilityChanged(context)
                    val res = context.resources
                    val text = when (result) {
                        CallInApp.StoryResult.OPENED -> CallInApp.storyMinutes(context).let { m -> res.getQuantityString(R.plurals.story_in_app_hint, m, m) }
                        CallInApp.StoryResult.USED_UP -> CallInApp.storiesPerDay(context).let { n -> res.getQuantityString(R.plurals.story_in_app_used_up, n, n) }
                        CallInApp.StoryResult.WAIT -> (CallInApp.storyWaitMinutes(context) ?: 1).coerceAtLeast(1).let { m ->
                            res.getQuantityString(R.plurals.story_in_app_wait, m, m)
                        }
                        CallInApp.StoryResult.NOT_READY -> res.getString(R.string.story_in_app_not_ready)
                        CallInApp.StoryResult.LOCKED -> res.getString(R.string.app_pass_locked)
                        CallInApp.StoryResult.NO_APP -> res.getString(R.string.create_in_app_failed)
                    }
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                } else {
                    CallInApp.create(context).message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            },
            onSupportAskShown = {
                SupportAsk.markShown(context)
                PageFeatures.current.update { it.copy(supportAsk = null) }
            },
            onSupportThanks = onOpenSupport,
            onStoryWeb = {
                // The page sent the tap to the website because it believed the app route was closed;
                // it may be a step behind (a wait just ended), so it learns the current state too.
                val minutes = CallInApp.storyWaitMinutes(context)
                CallInApp.storyAvailabilityChanged(context)
                val text = when {
                    minutes == null -> CallInApp.storiesPerDay(context).let { n -> context.resources.getQuantityString(R.plurals.story_web_tomorrow, n, n) }
                    minutes > 0 -> context.resources.getQuantityString(R.plurals.story_web_wait, minutes, minutes)
                    else -> context.getString(R.string.story_web_ready)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            },
            onSettingsInApp = {
                CallInApp.settings(context).message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            },
            // While Settings, the lock, the pause or the reel poster covers it, screen readers must not
            // reach the feed underneath.
            modifier = if (settingsOpen || lockShowing || pauseShowing || reelPoster) Modifier.clearAndSetSemantics {} else Modifier,
        )

        // No Throwback button on the feed (owner's decision): Settings opens from the page (profile icon,
        // Instagram's settings page, long-press on Home) and the app icon's long-press shortcut
        // (DECISIONS 33a). Only the time-limit chip shows, top-centre, on the logged-in home feed.
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

        // The layers below Settings leave system Back to it (backEnabled): Android gives Back to the
        // handler added last, and a layer can appear while Settings is open (the lock engaging).
        if (promptShowing) {
            InstagramAppPrompt(
                onOpenMessagesSetup = {
                    offerInstagramPrompt = false
                    onOpenMessagesSetup()
                },
                onDone = { offerInstagramPrompt = false },
                backEnabled = !settingsOpen && !lockShowing,
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
            ReelPoster(onBack = { reelPoster = false }, backEnabled = !settingsOpen && !lockShowing)
        }

        if (lockShowing) {
            TimeLimitOverlay(
                controller = timeLimit,
                onOpenMessagesOnly = { mode = WebMode.MESSAGES_ONLY },
                onCloseApp = onCloseApp,
                backEnabled = !settingsOpen,
            )
        }

        // On top of Instagram, which already loads underneath: the feed is ready when the pause ends.
        if (pauseShowing) {
            // The pause keeps its touches from reaching the page loading underneath.
            MindfulPauseScreen(onContinue = { pauseDone = true }, onBack = onCloseApp, backEnabled = !settingsOpen)
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
