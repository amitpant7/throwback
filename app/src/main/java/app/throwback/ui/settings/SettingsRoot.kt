package app.throwback.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.OpenThrowback
import app.throwback.core.prefs.AppPrefs
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.UiText
import app.throwback.core.ui.blockTouches
import app.throwback.ui.about.AboutScreen
import app.throwback.ui.about.SupportScreen
import app.throwback.ui.connect.InstagramApp
import app.throwback.ui.connect.LimitConnectOffer
import app.throwback.ui.connect.MessagesOffHooks
import app.throwback.ui.connect.MessagesSettingsScreen
import app.throwback.ui.connect.MessagesWizard
import app.throwback.ui.shortcut.ShortcutScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val StackSaver = Saver<List<SettingsRoute>, ArrayList<String>>(
    save = { ArrayList(SettingsNav.save(it)) },
    restore = { SettingsNav.restore(it) },
)

/**
 * Settings and everything reachable from it. The time-limit screens and "Your day" come in as
 * [timeLimitScreen] and [todayScreen] (AppRoot builds them), so this package doesn't depend on the
 * time limit. A small back stack: system Back pops it and calls [onClose] at the root.
 */
@Composable
internal fun SettingsRoot(
    onClose: () -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit, onLimitSet: () -> Unit) -> Unit,
    startAt: SettingsStart,
    todayScreen: @Composable (onClose: () -> Unit) -> Unit,
    messagesOff: MessagesOffHooks,
) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val prefs = remember { AppPrefs.from(context) }
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    var stack by rememberSaveable(startAt, stateSaver = StackSaver) { mutableStateOf(SettingsNav.initial(startAt)) }
    // Read here, once per Settings visit: Home then has them from its first frame on every return
    // (collected inside Home, it started from a default each time). Null for a frame while loading.
    val homeValues by remember { combine(prefs.mindfulPause, prefs.messagesSetupDone, ::HomeValues) }
        .collectAsStateWithLifecycle(initialValue = null)

    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
    val back: () -> Unit = {
        val popped = SettingsNav.pop(stack)
        if (popped == null) {
            onClose()
        } else {
            holder.removeState(SettingsNav.key(stack))
            stack = popped
        }
    }
    val push: (SettingsRoute) -> Unit = { stack = SettingsNav.push(stack, it) }
    val replace: (SettingsRoute) -> Unit = {
        holder.removeState(SettingsNav.key(stack))
        stack = SettingsNav.replaceTop(stack, it)
    }

    // Registered after AppRoot's own handler, so it wins; screens deeper down (wizard steps) win over this.
    BackHandler(onBack = back)

    // blockTouches: a tap on an empty spot must not reach the page underneath (it did: the top bar's
    // empty right side opened Instagram's notifications, seen on the emulator 2026-11-21).
    Box(Modifier.fillMaxSize().background(colors.bg).blockTouches()) {
        AnimatedContent(
            targetState = stack,
            transitionSpec = {
                val dir = if (targetState.size >= initialState.size) 1 else -1
                (slideInHorizontally(tween(240)) { w -> dir * w / 8 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(200)) { w -> -dir * w / 8 } + fadeOut(tween(160)))
            },
            label = "settingsRoute",
        ) { current ->
            holder.SaveableStateProvider(SettingsNav.key(current)) {
                Box(Modifier.fillMaxSize().background(colors.bg)) {
                    RouteContent(
                        route = current.last(),
                        atRoot = current.size == 1,
                        prefs = prefs,
                        homeValues = homeValues,
                        back = back,
                        push = push,
                        replace = replace,
                        closeSettings = onClose,
                        showMessage = showMessage,
                        timeLimitScreen = timeLimitScreen,
                        todayScreen = todayScreen,
                        messagesOff = messagesOff,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(16.dp),
        ) { data ->
            Snackbar(
                shape = RoundedCornerShape(14.dp),
                containerColor = colors.text,
                contentColor = colors.bg,
            ) { Text(data.visuals.message, style = UiText.body14) }
        }
    }
}

@Composable
private fun RouteContent(
    route: SettingsRoute,
    atRoot: Boolean,
    prefs: AppPrefs,
    homeValues: HomeValues?,
    back: () -> Unit,
    push: (SettingsRoute) -> Unit,
    replace: (SettingsRoute) -> Unit,
    closeSettings: () -> Unit,
    showMessage: (String) -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit, onLimitSet: () -> Unit) -> Unit,
    todayScreen: @Composable (onClose: () -> Unit) -> Unit,
    messagesOff: MessagesOffHooks,
) {
    val padded = Modifier.fillMaxSize().statusBarsPadding()
    when (route) {
        SettingsRoute.Home -> Box(padded) {
            if (homeValues != null) {
                SettingsHomeScreen(
                    values = homeValues,
                    prefs = prefs,
                    onBack = back,
                    onOpen = push,
                )
            }
        }

        // The timelimit area draws this screen (and handles its own insets).
        SettingsRoute.TimeLimit -> {
            val context = LocalContext.current
            val messagesDone by prefs.messagesSetupDone.collectAsStateWithLifecycle(initialValue = null)
            timeLimitScreen(back) {
                // A new limit with the Instagram app installed but not connected: offer to connect it,
                // or opening that app skips the limit (DECISIONS.md #34c).
                if (messagesDone == false && InstagramApp.installedPackage(context) != null) {
                    replace(SettingsRoute.LimitConnectOffer)
                } else {
                    back()
                }
            }
        }

        SettingsRoute.LimitConnectOffer -> Box(padded) {
            LimitConnectOffer(onConnect = { replace(SettingsRoute.Messages) }, onNotNow = back)
        }
        SettingsRoute.Today -> Box(padded) { todayScreen(back) }

        SettingsRoute.Messages -> Box(padded) {
            // Decide once which screen this entry shows, so turning the feature off (which also goes back)
            // doesn't flash the wizard during the exit animation.
            val done by prefs.messagesSetupDone.collectAsStateWithLifecycle(initialValue = null)
            var showSettings by rememberSaveable { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(done) { if (showSettings == null && done != null) showSettings = done }
            val offState = messagesOff.state()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            when (showSettings) {
                null -> Unit
                true -> MessagesSettingsScreen(
                    prefs = prefs,
                    onBack = back,
                    onFinishSetup = { push(SettingsRoute.MessagesWizard) },
                    // Confirmed in the screen's dialog: it happens 24 hours from now (#27b).
                    onTurnOff = {
                        if (offState.limitOn) {
                            push(SettingsRoute.MessagesOffPin) // PIN first
                        } else {
                            scope.launch {
                                messagesOff.schedule()
                                showMessage(context.getString(R.string.ui_messages_off_scheduled))
                            }
                        }
                    },
                    pendingOff = offState.offAt,
                    onCancelPendingOff = {
                        messagesOff.cancel()
                        showMessage(context.getString(R.string.ui_messages_off_cancelled))
                    },
                    showMessage = showMessage,
                )
                false -> Box(Modifier.navigationBarsPadding()) {
                    MessagesWizard(
                        onExit = back,
                        onFinish = {
                            prefs.write { setMessagesSetupDone(true) }
                            // Settings was opened just for this setup ("Instagram app found" after login,
                            // or the call button without the app): back to Instagram. From Settings: "Ready".
                            if (atRoot) closeSettings() else showSettings = true
                        },
                        showMessage = showMessage,
                    )
                }
            }
        }

        SettingsRoute.MessagesOffPin -> Box(padded.navigationBarsPadding()) {
            val context = LocalContext.current
            messagesOff.pinScreen(
                {
                    back()
                    showMessage(context.getString(R.string.ui_messages_off_scheduled))
                },
                back,
            )
        }

        SettingsRoute.MessagesWizard -> Box(padded.navigationBarsPadding()) {
            MessagesWizard(
                onExit = back,
                onFinish = {
                    prefs.write { setMessagesSetupDone(true) }
                    back()
                },
                showMessage = showMessage,
            )
        }

        SettingsRoute.Shortcut -> Box(padded) {
            ShortcutScreen(onBack = back, onOpenSupport = { push(SettingsRoute.Support) }, showMessage = showMessage)
        }

        SettingsRoute.Support -> Box(padded) {
            val context = LocalContext.current
            SupportScreen(
                onBack = back,
                // An instagram.com page in Throwback's feed: the same way in as a link from another app.
                onOpenPage = { url ->
                    context.startActivity(OpenThrowback.intent(context, Intent.ACTION_VIEW, url))
                    closeSettings()
                },
                showMessage = showMessage,
            )
        }
        SettingsRoute.About -> Box(padded) { AboutScreen(onBack = back, showMessage = showMessage) }
    }
}
