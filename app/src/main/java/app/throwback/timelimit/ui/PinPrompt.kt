package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.TbBackButton
import app.throwback.core.ui.TbTopBar
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.launch

/** "Turn off Messages & calls" while a limit is on: PIN, then it turns off tomorrow. */
@Composable
internal fun GuardOffPin(controller: DefaultTimeLimitController, onScheduled: () -> Unit, onBack: () -> Unit) {
    // Live, so the 30-second pause after wrong PINs counts down and ends here too.
    val view by rememberLiveView(controller)
    var forgot by remember { mutableStateOf(false) }
    BackHandler { if (forgot) forgot = false else onBack() }
    if (forgot) {
        ForgotPinScreen(controller, view, onBack = { forgot = false })
    } else {
        PinPrompt(
            title = stringResource(R.string.tl_enter_pin_guard_off),
            view = view,
            onBack = onBack,
            onForgot = { forgot = true },
            check = { pin -> controller.scheduleGuardOff(pin) },
            onSuccess = onScheduled,
            subtitle = stringResource(R.string.tl_starts_tomorrow),
        )
    }
}

/**
 * PIN keypad for a protected action, with "Forgot PIN?" below. [check] runs the action with the PIN;
 * when it's done (or scheduled) [onSuccess] runs, or [onSuccessWithPin] with the PIN; otherwise the
 * digits clear and the reason shows. The settings screens show the time-limit top bar ([showHeader]);
 * the lock screen has a floating back arrow instead.
 */
@Composable
internal fun PinPrompt(
    title: String,
    view: TimeLimitView,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    check: suspend (String) -> ActionResult,
    onSuccess: () -> Unit = {},
    onSuccessWithPin: ((String) -> Unit)? = null,
    eyebrow: String? = null,
    subtitle: String? = null,
    showHeader: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<ActionResult?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (showHeader) TbTopBar(stringResource(R.string.tl_title), onBack)
            PinEntry(
                title = title,
                eyebrow = eyebrow,
                subtitle = subtitle,
                message = if (busy) stringResource(R.string.tl_checking) else pinProblemText(lastResult, view.pinBlockedMillis),
                enabled = !busy && view.pinBlockedMillis == 0L,
                clearKey = attempt,
                topPadding = if (showHeader) 16.dp else 64.dp,
                onPin = { pin ->
                    scope.launch {
                        busy = true
                        val result = check(pin)
                        busy = false
                        lastResult = result
                        val ok = result == ActionResult.Done || result is ActionResult.Scheduled
                        when {
                            ok && onSuccessWithPin != null -> onSuccessWithPin(pin)
                            ok -> onSuccess()
                            else -> attempt++
                        }
                    }
                },
                footer = { ForgotPinLink(onForgot) },
            )
        }
        if (!showHeader) TbBackButton(onBack, Modifier.padding(6.dp).align(Alignment.TopStart))
    }
}

/** "Forgot PIN?" under a PIN keypad. */
@Composable
private fun ForgotPinLink(onForgot: () -> Unit) {
    TextButton(onClick = onForgot, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.tl_forgot_pin), style = MaterialTheme.typography.bodyMedium, color = ThrowbackTheme.colors.muted)
    }
}
