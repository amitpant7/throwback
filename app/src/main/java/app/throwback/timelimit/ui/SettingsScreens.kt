package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → Daily time limit: first-time setup, or managing an existing limit. */
@Composable
internal fun TimeLimitSettings(controller: DefaultTimeLimitController, onClose: () -> Unit) {
    val view by controller.view.collectAsStateWithLifecycle()
    val ready by controller.ready.collectAsStateWithLifecycle()

    LaunchedEffect(controller) {
        while (true) {
            controller.refresh()
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ThrowbackTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when {
            !ready -> Unit // The saved state is still loading (a few milliseconds).
            view.limitMinutes == null -> SetupScreen(controller, onClose)
            else -> ManageScreen(controller, view, onClose)
        }
    }
}

// ---- Mockup 7: set up -----------------------------------------------------------------------

@Composable
private fun SetupScreen(controller: DefaultTimeLimitController, onClose: () -> Unit) {
    val c = ThrowbackTheme.colors
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    // The PIN lives only in memory (never in saved instance state).
    var pin by remember { mutableStateOf<String?>(null) }
    var creatingPin by remember { mutableStateOf(false) }
    var turnOnAfterPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun turnOn(p: String) {
        scope.launch {
            busy = true
            val result = controller.setup(minutes, p)
            busy = false
            if (result == ActionResult.Done) onClose() else failed = true
        }
    }

    BackHandler { if (creatingPin) creatingPin = false else onClose() }

    if (creatingPin) {
        CreatePinFlow(
            headerTitle = stringResource(R.string.tl_title),
            onBack = { creatingPin = false },
            onCreated = { p ->
                pin = p
                creatingPin = false
                if (turnOnAfterPin) turnOn(p)
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        TlHeader(stringResource(R.string.tl_title), onClose)
        ScreenColumn(
            PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            SectionLabel(stringResource(R.string.tl_setup_step_minutes))
            LimitChoices(selected = minutes, onPick = { minutes = it })

            Spacer(Modifier.height(18.dp))
            TlCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconLine(R.drawable.tl_ic_lock, pluralStringResource(R.plurals.tl_rule_locks, minutes, minutes))
                IconLine(R.drawable.tl_ic_clock, stringResource(R.string.tl_rule_extend))
                IconLine(R.drawable.tl_ic_chat, stringResource(R.string.tl_rule_messages))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.tl_setup_step_pin))
            val pinDescription = stringResource(if (pin == null) R.string.tl_setup_pin_empty_cd else R.string.tl_setup_pin_set_cd)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
                    .buttonSemantics(pinDescription) {
                        turnOnAfterPin = false
                        creatingPin = true
                    }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                PinDots(if (pin == null) 0 else Rules.PIN_LENGTH, gap = 14.dp)
            }
            Text(
                stringResource(R.string.tl_setup_pin_note),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))
            if (failed) {
                AnnouncedText(stringResource(R.string.tl_error_generic), Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
            Text(
                stringResource(R.string.tl_speed_bump),
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
            TlButton(
                stringResource(R.string.tl_turn_on, minutes),
                enabled = !busy,
                onClick = {
                    val p = pin
                    if (p == null) {
                        turnOnAfterPin = true
                        creatingPin = true
                    } else {
                        turnOn(p)
                    }
                },
            )
        }
    }
}

/** The 15/30/45/60 minute choice (mockup `.chip`; 48dp tall for touch). */
@Composable
private fun LimitChoices(selected: Int, onPick: (Int) -> Unit) {
    val c = ThrowbackTheme.colors
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Rules.LIMIT_CHOICES.forEach { m ->
            val isSelected = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSelected) c.accent else c.surface2)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(m) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.tl_minutes_chip, m),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) c.onAccent else c.text,
                )
            }
        }
    }
}

// ---- Manage an existing limit ---------------------------------------------------------------

private sealed interface ManagePage {
    data object Home : ManagePage
    data class RaisePin(val minutes: Int) : ManagePage
    data object TurnOffPin : ManagePage
    data object ChangePinCurrent : ManagePage
    data class ChangePinNew(val currentPin: String) : ManagePage
    data object Reset : ManagePage
}

private sealed interface Notice {
    data class Lowered(val minutes: Int) : Notice
    data class Raised(val minutes: Int) : Notice
    data object TurnsOff : Notice
    data object PinChanged : Notice
    data object PinReset : Notice
    data object Cancelled : Notice
}

@Composable
private fun ManageScreen(controller: DefaultTimeLimitController, view: TimeLimitView, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Plain remember: ChangePinNew holds a PIN, which must never land in saved instance state.
    var page by remember { mutableStateOf<ManagePage>(ManagePage.Home) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var confirmLower by remember { mutableStateOf<Int?>(null) }

    fun home(n: Notice? = null) {
        notice = n
        page = ManagePage.Home
    }

    BackHandler { if (page == ManagePage.Home) onClose() else home() }

    when (val p = page) {
        ManagePage.Home -> ManageHome(
            view = view,
            notice = notice,
            onClose = onClose,
            onPickLimit = { m ->
                val current = view.limitMinutes ?: return@ManageHome
                when {
                    m < current -> confirmLower = m
                    m > current -> page = ManagePage.RaisePin(m)
                    view.pendingLimit != null -> scope.launch {
                        if (controller.setLimit(m) == ActionResult.Done) notice = Notice.Cancelled
                    }
                }
            },
            onCancelPendingLimit = {
                controller.cancelPendingLimit()
                notice = Notice.Cancelled
            },
            onCancelPendingPin = {
                controller.cancelPendingPin()
                notice = Notice.Cancelled
            },
            onChangePin = { page = ManagePage.ChangePinCurrent },
            onForgotPin = { page = ManagePage.Reset },
            onTurnOff = { page = ManagePage.TurnOffPin },
        )
        is ManagePage.RaisePin -> PinPrompt(
            title = pluralStringResource(R.plurals.tl_enter_pin_raise, p.minutes, p.minutes),
            view = view,
            onBack = { home() },
            onForgot = { page = ManagePage.Reset },
            check = { pin -> controller.setLimit(p.minutes, pin) },
            onSuccess = { home(Notice.Raised(p.minutes)) },
        )
        ManagePage.TurnOffPin -> PinPrompt(
            title = stringResource(R.string.tl_enter_pin_off),
            view = view,
            onBack = { home() },
            onForgot = { page = ManagePage.Reset },
            check = { pin -> controller.turnOff(pin) },
            onSuccess = { home(Notice.TurnsOff) },
        )
        ManagePage.ChangePinCurrent -> PinPrompt(
            title = stringResource(R.string.tl_enter_pin_change),
            view = view,
            onBack = { home() },
            onForgot = { page = ManagePage.Reset },
            check = { pin -> controller.verifyPin(pin) },
            onSuccessWithPin = { pin -> page = ManagePage.ChangePinNew(pin) },
        )
        is ManagePage.ChangePinNew -> {
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf<ActionResult?>(null) }
            CreatePinFlow(
                headerTitle = stringResource(R.string.tl_title),
                firstTitle = stringResource(R.string.tl_new_pin_title),
                onBack = { home() },
                busy = busy,
                message = pinProblemText(failed, view.pinBlockedMillis),
                onCreated = { newPin ->
                    scope.launch {
                        busy = true
                        val result = controller.changePin(p.currentPin, newPin)
                        busy = false
                        if (result is ActionResult.Scheduled) home(Notice.PinChanged) else failed = result
                    }
                },
            )
        }
        ManagePage.Reset -> ForgotPinScreen(
            controller = controller,
            view = view,
            onBack = { home() },
            onNewPinSaved = { home(Notice.PinReset) },
        )
    }

    confirmLower?.let { m ->
        LowerLimitDialog(
            minutes = m,
            onConfirm = {
                confirmLower = null
                scope.launch {
                    if (controller.setLimit(m) == ActionResult.Done) notice = Notice.Lowered(m)
                }
            },
            onDismiss = { confirmLower = null },
        )
    }
}

@Composable
private fun ManageHome(
    view: TimeLimitView,
    notice: Notice?,
    onClose: () -> Unit,
    onPickLimit: (Int) -> Unit,
    onCancelPendingLimit: () -> Unit,
    onCancelPendingPin: () -> Unit,
    onChangePin: () -> Unit,
    onForgotPin: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val c = ThrowbackTheme.colors
    val limit = view.limitMinutes ?: return
    val reset = view.pinReset

    Column(Modifier.fillMaxSize()) {
        TlHeader(stringResource(R.string.tl_title), onClose)
        ScreenColumn(
            PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (notice != null) {
                TlCard(Modifier.padding(bottom = 12.dp)) {
                    AnnouncedText(noticeText(notice), Modifier.fillMaxWidth(), MaterialTheme.typography.bodyMedium)
                }
            }

            TlCard {
                Eyebrow(stringResource(R.string.tl_today_label))
                Spacer(Modifier.height(6.dp))
                Text(todayStatus(view), style = MaterialTheme.typography.headlineSmall, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(
                        R.plurals.tl_manage_summary,
                        Rules.MAX_EXTENSIONS_PER_DAY,
                        limit,
                        view.extensionsUsed,
                        Rules.MAX_EXTENSIONS_PER_DAY,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }

            val pendingLimit = view.pendingLimit
            val pendingPin = view.pendingPinEffective
            if (pendingLimit != null || pendingPin != null) {
                val effective = listOfNotNull(pendingLimit?.effective, pendingPin).min()
                Spacer(Modifier.height(12.dp))
                TlCard(padding = PaddingValues(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 6.dp)) {
                    Eyebrow(stringResource(R.string.tl_pending_label, whenText(effective, view.today).uppercase()))
                    if (pendingLimit != null) {
                        PendingRow(
                            text = pendingLimit.minutes?.let { pluralStringResource(R.plurals.tl_pending_raise, it, it) }
                                ?: stringResource(R.string.tl_pending_off),
                            onCancel = onCancelPendingLimit,
                        )
                    }
                    if (pendingPin != null) PendingRow(stringResource(R.string.tl_pending_pin), onCancelPendingPin)
                }
            }

            ManageSection(stringResource(R.string.tl_section_limit))
            LimitChoices(selected = limit, onPick = onPickLimit)
            Text(
                stringResource(R.string.tl_limit_help),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
            )

            ManageSection(stringResource(R.string.tl_section_pin))
            val pinLocked = reset != null
            TlCard(padding = PaddingValues(0.dp)) {
                SettingsRow(
                    title = stringResource(R.string.tl_change_pin),
                    subtitle = stringResource(if (pinLocked) R.string.tl_unavailable_reset else R.string.tl_change_pin_sub),
                    enabled = !pinLocked,
                    onClick = onChangePin,
                )
                HorizontalDivider(color = c.line)
                SettingsRow(
                    title = stringResource(R.string.tl_forgot_pin),
                    subtitle = when {
                        reset == null -> stringResource(R.string.tl_forgot_pin_sub)
                        reset.ready -> stringResource(R.string.tl_reset_ready_row_sub)
                        else -> stringResource(R.string.tl_reset_running_sub, longDurationText(reset.remainingMillis))
                    },
                    onClick = onForgotPin,
                )
            }
            Spacer(Modifier.height(12.dp))
            TlCard(padding = PaddingValues(0.dp)) {
                SettingsRow(
                    title = stringResource(R.string.tl_turn_off),
                    subtitle = stringResource(if (pinLocked) R.string.tl_unavailable_reset else R.string.tl_turn_off_sub),
                    enabled = !pinLocked,
                    onClick = onTurnOff,
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.tl_speed_bump),
                style = MaterialTheme.typography.bodyLarge,
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun todayStatus(view: TimeLimitView): String = when (val state = view.lockState) {
    is LockState.Running -> when (val chip = Durations.chip(state.remainingMillis)) {
        is Durations.Chip.Minutes -> stringResource(R.string.tl_min_left, chip.minutes)
        is Durations.Chip.LastMinute -> stringResource(R.string.tl_chip_seconds_left, minSecText(state.remainingMillis))
    }
    LockState.Locked -> stringResource(
        if (view.lock is LockPhase.DoneForToday) R.string.tl_locked_until_midnight else R.string.tl_locked_now,
    )
    LockState.Disabled -> ""
}

@Composable
private fun noticeText(n: Notice): String = when (n) {
    is Notice.Lowered -> pluralStringResource(R.plurals.tl_notice_lowered, n.minutes, n.minutes)
    is Notice.Raised -> pluralStringResource(R.plurals.tl_notice_raise, n.minutes, n.minutes)
    Notice.TurnsOff -> stringResource(R.string.tl_notice_off)
    Notice.PinChanged -> stringResource(R.string.tl_notice_pin)
    Notice.PinReset -> stringResource(R.string.tl_notice_reset_done)
    Notice.Cancelled -> stringResource(R.string.tl_notice_cancelled)
}

/** "tomorrow", or a date if a change is further out. */
@Composable
private fun whenText(date: LocalDate, today: LocalDate?): String =
    if (today != null && date == today.plusDays(1)) stringResource(R.string.tl_tomorrow)
    else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
private fun ManageSection(text: String) {
    SectionLabel(text, Modifier.padding(start = 2.dp, top = 22.dp))
}

@Composable
private fun PendingRow(text: String, onCancel: () -> Unit) {
    val c = ThrowbackTheme.colors
    val cancelLabel = stringResource(R.string.tl_cancel_pending_cd, text)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onCancel,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cancelLabel },
        ) {
            Text(stringResource(R.string.tl_cancel), style = MaterialTheme.typography.labelLarge, color = c.accentText)
        }
    }
}

/** Settings list row (mockup `.row`: 56dp minimum, title 16sp, subtitle 13sp muted, chevron). */
@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit, enabled: Boolean = true) {
    val c = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.text else c.muted)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        TlIcon(R.drawable.tl_ic_chevron, size = 18.dp, tint = c.muted)
    }
}

/** PIN keypad for a protected change, with a back arrow and "Forgot PIN?". */
@Composable
private fun PinPrompt(
    title: String,
    view: TimeLimitView,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    check: suspend (String) -> ActionResult,
    onSuccess: () -> Unit = {},
    onSuccessWithPin: ((String) -> Unit)? = null,
) {
    val c = ThrowbackTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<ActionResult?>(null) }

    Column(Modifier.fillMaxSize()) {
        TlHeader(stringResource(R.string.tl_title), onBack)
        PinEntry(
            title = title,
            subtitle = stringResource(R.string.tl_starts_tomorrow).takeIf { onSuccessWithPin == null },
            message = if (busy) stringResource(R.string.tl_checking) else pinProblemText(lastResult, view.pinBlockedMillis),
            enabled = !busy && view.pinBlockedMillis == 0L,
            clearKey = attempt,
            topPadding = 16.dp,
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
            footer = {
                TextButton(onClick = onForgot, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.tl_forgot_pin), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            },
        )
    }
}

@Composable
private fun LowerLimitDialog(minutes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = ThrowbackTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.muted,
        title = { Text(pluralStringResource(R.plurals.tl_lower_dialog_title, minutes, minutes), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(R.string.tl_lower_dialog_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.tl_lower_confirm), color = c.accentText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tl_cancel), color = c.text, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
