package app.throwback.timelimit.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.timelimit.TimeLimitController
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Pieces shared by the lock screens and the limit settings. The UI kit both areas use is in core/ui;
 * the PIN keypad is in PinEntry.kt.
 */

// ---- Live state -----------------------------------------------------------------------------

/**
 * The controller's view for a time-limit screen, refreshed every second while the screen is resumed
 * so countdowns and the wrong-PIN pause keep moving (the controller's own tick runs only while
 * Instagram is in front). Stops when the app goes to the background: each refresh may save.
 */
@Composable
internal fun rememberLiveView(controller: TimeLimitController): State<TimeLimitView> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                controller.refresh()
                delay(1_000)
            }
        }
    }
    return controller.view.collectAsStateWithLifecycle()
}

// ---- Actions ------------------------------------------------------------------------------

/**
 * One action at a time on a screen (checking a PIN, saving a limit): [running] is true from the tap
 * until the work has ended, also when it failed or the screen went away. It is set before the work's
 * coroutine starts (that only happens on the next frame), so a quick second tap does nothing.
 */
@Stable
internal class BusyAction(private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set

    fun run(work: suspend () -> Unit) {
        if (running) return
        running = true
        scope.launch {
            try {
                work()
            } finally {
                running = false
            }
        }
    }
}

@Composable
internal fun rememberBusyAction(): BusyAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { BusyAction(scope) }
}

// ---- Text ---------------------------------------------------------------------------------

/** Small capitals label ("EXTENSION 1 OF 2", "RESET REQUESTED"). */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em),
        color = ThrowbackTheme.colors.accentText,
        modifier = modifier,
    )
}

/** A line that screen readers announce when it changes (errors, confirmations). */
@Composable
internal fun AnnouncedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = ThrowbackTheme.colors.text,
) {
    Text(
        text,
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
