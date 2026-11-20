package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.LockState
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.LockPhase
import app.throwback.timelimit.engine.Rules
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LockPage { Main, Pin, Reset }

/**
 * Full-screen lock over Instagram: the 5-minute wait (mockup 8), PIN for +5 minutes (9),
 * locked until midnight (10) and the forgot-PIN countdown (12). Draws nothing unless locked, and
 * swallows every touch so the WebView underneath can't be used.
 */
@Composable
internal fun LockOverlay(
    controller: DefaultTimeLimitController,
    onOpenMessagesOnly: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view by controller.view.collectAsStateWithLifecycle()
    val phase = view.lock
    if (view.lockState != LockState.Locked || phase == null) return

    var page by remember { mutableStateOf(LockPage.Main) }

    // Keep the countdowns moving even when no foreground session is ticking.
    LaunchedEffect(controller) {
        while (true) {
            delay(1_000)
            controller.refresh()
        }
    }
    // Back leaves a sub-page; on the main lock screen it closes Throwback rather than reaching the WebView.
    BackHandler {
        when {
            phase is LockPhase.Granted -> controller.acknowledgeExtension()
            page == LockPage.Main -> onCloseApp()
            else -> page = LockPage.Main
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(ThrowbackTheme.colors.bg)
            .swallowTouches()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when {
            phase is LockPhase.Granted -> GrantedScreen(view, onContinue = {
                page = LockPage.Main
                controller.acknowledgeExtension()
            })
            phase is LockPhase.DoneForToday -> DoneForTodayScreen(phase, onOpenMessagesOnly, onCloseApp)
            page == LockPage.Reset -> ForgotPinScreen(controller, view, onBack = { page = LockPage.Main })
            page == LockPage.Pin && phase is LockPhase.CanExtend && view.pinReset == null ->
                ExtensionPinScreen(
                    controller = controller,
                    view = view,
                    extensionNumber = phase.extensionNumber,
                    onBack = { page = LockPage.Main },
                    onForgot = { page = LockPage.Reset },
                )
            else -> TimesUpScreen(
                view = view,
                phase = phase,
                onAsk = { page = LockPage.Pin },
                onOpenReset = { page = LockPage.Reset },
                onOpenMessagesOnly = onOpenMessagesOnly,
                onCloseApp = onCloseApp,
            )
        }
    }
}

/** Consumes every pointer event that reaches the overlay, so nothing below it gets touched. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

// ---- Mockup 8: time's up, 5-minute wait ---------------------------------------------------

@Composable
private fun TimesUpScreen(
    view: TimeLimitView,
    phase: LockPhase,
    onAsk: () -> Unit,
    onOpenReset: () -> Unit,
    onOpenMessagesOnly: () -> Unit,
    onCloseApp: () -> Unit,
) {
    val c = ThrowbackTheme.colors
    val waitLeft = (phase as? LockPhase.Waiting)?.remainingMillis ?: 0L
    val waitText = minSecText(waitLeft)
    val reset = view.pinReset

    ScreenColumn(PaddingValues(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 28.dp)) {
        TlIcon(R.drawable.tl_ic_lock, size = 36.dp, tint = c.accentText)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.tl_times_up_title),
            style = screenTitleStyle(),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            pluralStringResource(R.plurals.tl_times_up_body, view.budgetMinutes, view.budgetMinutes),
            style = leadStyle(),
            color = c.muted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))
        CountdownRing(
            fraction = waitLeft.toFloat() / Rules.WAIT_MILLIS,
            description = if (waitLeft > 0) stringResource(R.string.tl_wait_cd, waitText) else stringResource(R.string.tl_you_can_ask_now),
        ) {
            Text(waitText, style = countdownStyle(44), color = c.text)
            Text(
                stringResource(if (waitLeft > 0) R.string.tl_until_you_can_ask else R.string.tl_you_can_ask_now),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (reset != null) R.string.tl_reset_paused_note else R.string.tl_then_pin),
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        ExtensionsLeft(view.extensionsLeft)

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                reset != null -> TlButton(
                    stringResource(if (reset.ready) R.string.tl_reset_set_new else R.string.tl_reset_in_progress),
                    onClick = onOpenReset,
                    style = if (reset.ready) TlButtonStyle.Primary else TlButtonStyle.Secondary,
                )
                phase is LockPhase.Waiting -> TlButton(stringResource(R.string.tl_ask_in, waitText), onClick = {}, enabled = false)
                else -> TlButton(stringResource(R.string.tl_ask), onClick = onAsk)
            }
            TlButton(
                stringResource(R.string.tl_open_messages),
                onClick = onOpenMessagesOnly,
                style = TlButtonStyle.Secondary,
                icon = R.drawable.tl_ic_chat,
            )
            TlButton(stringResource(R.string.tl_close), onClick = onCloseApp, style = TlButtonStyle.Plain)
        }
    }
}

@Composable
private fun ExtensionsLeft(left: Int) {
    val c = ThrowbackTheme.colors
    val text = pluralStringResource(
        R.plurals.tl_extensions_left,
        Rules.MAX_EXTENSIONS_PER_DAY,
        left,
        Rules.MAX_EXTENSIONS_PER_DAY,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = text },
    ) {
        repeat(Rules.MAX_EXTENSIONS_PER_DAY) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < left) c.accent else c.surface2))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

/** 200dp ring that empties as the wait runs out (mockup: r=88, 10dp stroke, round cap). */
@Composable
private fun CountdownRing(fraction: Float, description: String, content: @Composable () -> Unit) {
    val c = ThrowbackTheme.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "wait ring")
    Box(
        Modifier.size(200.dp).clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val radius = size.minDimension * 0.44f
            drawCircle(c.surface2, radius = radius, style = Stroke(stroke))
            if (animated > 0f) {
                drawArc(
                    color = c.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

// ---- Mockup 9: PIN for +5 minutes ---------------------------------------------------------

@Composable
private fun ExtensionPinScreen(
    controller: DefaultTimeLimitController,
    view: TimeLimitView,
    extensionNumber: Int,
    onBack: () -> Unit,
    onForgot: () -> Unit,
) {
    val c = ThrowbackTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<ActionResult?>(null) }

    Box(Modifier.fillMaxSize()) {
        PinEntry(
            eyebrow = stringResource(R.string.tl_extension_n_of, extensionNumber, Rules.MAX_EXTENSIONS_PER_DAY),
            title = stringResource(R.string.tl_enter_pin_title),
            subtitle = stringResource(R.string.tl_enter_pin_sub),
            message = if (busy) stringResource(R.string.tl_checking) else pinProblemText(lastResult, view.pinBlockedMillis),
            enabled = !busy && view.pinBlockedMillis == 0L,
            clearKey = attempt,
            onPin = { pin ->
                scope.launch {
                    busy = true
                    val result = controller.extend(pin)
                    busy = false
                    lastResult = result
                    if (result != ActionResult.Done) attempt++
                }
            },
            footer = {
                TextButton(onClick = onForgot, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.tl_forgot_pin), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            },
        )
        IconButton(onClick = onBack, modifier = Modifier.padding(6.dp).size(48.dp).align(Alignment.TopStart)) {
            TlIcon(R.drawable.tl_ic_back, size = 22.dp, tint = c.text, contentDescription = stringResource(R.string.tl_back))
        }
    }
}

@Composable
private fun GrantedScreen(view: TimeLimitView, onContinue: () -> Unit) {
    val c = ThrowbackTheme.colors
    val left = view.extensionsLeft
    val leftText = if (left > 0) pluralStringResource(R.plurals.tl_granted_left, left, left) else stringResource(R.string.tl_granted_last)
    ScreenColumn(PaddingValues(start = 28.dp, end = 28.dp, top = 64.dp, bottom = 28.dp)) {
        Spacer(Modifier.weight(1f))
        TlIcon(R.drawable.tl_ic_check, size = 44.dp, tint = c.accentText)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.tl_granted_title),
            style = screenTitleStyle(),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        AnnouncedText(
            leftText + "\n" + stringResource(R.string.tl_granted_lock_again),
            style = leadStyle().copy(color = c.muted),
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        TlButton(stringResource(R.string.tl_back_to_feed), onClick = onContinue)
    }
}

// ---- Mockup 10: locked until midnight -----------------------------------------------------

@Composable
private fun DoneForTodayScreen(phase: LockPhase.DoneForToday, onOpenMessagesOnly: () -> Unit, onCloseApp: () -> Unit) {
    val c = ThrowbackTheme.colors
    ScreenColumn(PaddingValues(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 28.dp)) {
        TlIcon(R.drawable.tl_ic_moon, size = 56.dp, tint = c.accent)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.tl_done_title),
            style = screenTitleStyle().copy(fontSize = MaterialTheme.typography.displaySmall.fontSize),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.tl_done_body), style = leadStyle(), color = c.muted, textAlign = TextAlign.Center)

        Spacer(Modifier.height(40.dp))
        val until = hoursMinutesText(phase.untilMidnightMillis)
        val untilLabel = stringResource(R.string.tl_until_unlock)
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .clearAndSetSemantics { contentDescription = "$until $untilLabel" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(until, style = countdownStyle(34), color = c.text)
            Text(untilLabel, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TlIcon(R.drawable.tl_ic_phone, size = 16.dp, tint = c.muted)
            Text(stringResource(R.string.tl_calls_note), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TlButton(
                stringResource(R.string.tl_open_messages),
                onClick = onOpenMessagesOnly,
                style = TlButtonStyle.Secondary,
                icon = R.drawable.tl_ic_chat,
            )
            TlButton(stringResource(R.string.tl_close), onClick = onCloseApp)
        }
    }
}
