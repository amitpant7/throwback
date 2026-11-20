package app.throwback.ui.settings

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
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.SettingsStart
import app.throwback.ui.UiPrefs
import app.throwback.ui.about.AboutScreen
import app.throwback.ui.common.UiText
import app.throwback.ui.messages.MessagesSettingsScreen
import app.throwback.ui.messages.MessagesWizard
import app.throwback.ui.shortcut.ShortcutScreen
import kotlinx.coroutines.launch

private val StackSaver = Saver<List<SettingsRoute>, ArrayList<String>>(
    save = { ArrayList(SettingsNav.save(it)) },
    restore = { SettingsNav.restore(it) },
)

/** See UiApi.SettingsRoot. A small back stack; system back pops it and closes Settings at the root. */
@Composable
internal fun SettingsRootContent(
    onClose: () -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit) -> Unit,
    startAt: SettingsStart,
) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val prefs = remember { UiPrefs.from(context) }
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    var stack by rememberSaveable(startAt, stateSaver = StackSaver) { mutableStateOf(SettingsNav.initial(startAt)) }

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

    // Registered after AppRoot's own handler, so it wins; screens deeper down (wizard steps) win over this.
    BackHandler(onBack = back)

    Box(Modifier.fillMaxSize().background(colors.bg)) {
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
                        prefs = prefs,
                        back = back,
                        push = push,
                        showMessage = showMessage,
                        timeLimitScreen = timeLimitScreen,
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
    prefs: UiPrefs,
    back: () -> Unit,
    push: (SettingsRoute) -> Unit,
    showMessage: (String) -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit) -> Unit,
) {
    val padded = Modifier.fillMaxSize().statusBarsPadding()
    when (route) {
        SettingsRoute.Home -> Box(padded) {
            SettingsHomeScreen(
                prefs = prefs,
                onBack = back,
                onOpen = push,
            )
        }

        // The timelimit area draws this screen (and handles its own insets).
        SettingsRoute.TimeLimit -> timeLimitScreen(back)

        SettingsRoute.Messages -> Box(padded) {
            // Decide once which screen this entry shows, so turning the feature off (which also goes back)
            // doesn't flash the wizard during the exit animation.
            val done by prefs.messagesSetupDone.collectAsStateWithLifecycle(initialValue = null)
            var showSettings by rememberSaveable { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(done) { if (showSettings == null && done != null) showSettings = done }
            when (showSettings) {
                null -> Unit
                true -> MessagesSettingsScreen(
                    prefs = prefs,
                    onBack = back,
                    onFinishSetup = { push(SettingsRoute.MessagesWizard) },
                    onTurnOff = {
                        prefs.write { setMessagesSetupDone(false) }
                        back()
                    },
                )
                false -> Box(Modifier.navigationBarsPadding()) {
                    MessagesWizard(
                        onExit = back,
                        onFinish = {
                            prefs.write { setMessagesSetupDone(true) }
                            showSettings = true
                        },
                        showMessage = showMessage,
                    )
                }
            }
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

        SettingsRoute.Shortcut -> Box(padded) { ShortcutScreen(onBack = back, showMessage = showMessage) }

        SettingsRoute.About -> Box(padded) { AboutScreen(onBack = back, showMessage = showMessage) }
    }
}
