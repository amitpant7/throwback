package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.LockState
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.longDurationText
import app.throwback.core.ui.remainingText
import app.throwback.timelimit.TimeLimitController
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.LockPhase
import app.throwback.timelimit.engine.Rules
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
internal fun ManageScreen(controller: TimeLimitController, view: TimeLimitView, onClose: () -> Unit) {
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
            subtitle = stringResource(R.string.tl_starts_tomorrow),
        )
        ManagePage.TurnOffPin -> PinPrompt(
            title = stringResource(R.string.tl_enter_pin_off),
            view = view,
            onBack = { home() },
            onForgot = { page = ManagePage.Reset },
            check = { pin -> controller.turnOff(pin) },
            onSuccess = { home(Notice.TurnsOff) },
            subtitle = stringResource(R.string.tl_starts_tomorrow),
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
            val saving = rememberBusyAction()
            var failed by remember { mutableStateOf<ActionResult?>(null) }
            CreatePinFlow(
                headerTitle = stringResource(R.string.tl_title),
                firstTitle = stringResource(R.string.tl_new_pin_title),
                onBack = { home() },
                busy = saving.running,
                message = pinProblemText(failed, view.pinBlockedMillis),
                onCreated = { newPin ->
                    saving.run {
                        val result = controller.changePin(p.currentPin, newPin)
                        if (result == ActionResult.Done) home(Notice.PinChanged) else failed = result
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
    onChangePin: () -> Unit,
    onForgotPin: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val c = ThrowbackTheme.colors
    val limit = view.limitMinutes ?: return
    val reset = view.pinReset
    // No PIN can be used while a PIN reset runs: what needs it is greyed out.
    val pinLocked = reset != null

    SettingsScaffold(
        stringResource(R.string.tl_title),
        onBack = onClose,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        fillViewport = true,
    ) {
        if (notice != null) {
            TbCard(Modifier.padding(bottom = 12.dp), contentPadding = CardPadding) {
                AnnouncedText(noticeText(notice), Modifier.fillMaxWidth(), MaterialTheme.typography.bodyMedium)
            }
        }

        TbCard(contentPadding = CardPadding) {
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
        if (pendingLimit != null) {
            val effective = pendingLimit.effective
            Spacer(Modifier.height(12.dp))
            TbCard(contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 6.dp)) {
                Eyebrow(stringResource(R.string.tl_pending_label, whenText(effective, view.today).uppercase()))
                PendingRow(
                    text = pendingLimit.minutes?.let { pluralStringResource(R.plurals.tl_pending_raise, it, it) }
                        ?: stringResource(R.string.tl_pending_off),
                    onCancel = onCancelPendingLimit,
                )
            }
        }

        SectionLabel(stringResource(R.string.tl_section_limit))
        LimitChoices(selected = limit, onPick = onPickLimit, enabled = { m -> m <= limit || !pinLocked })
        Text(
            stringResource(if (pinLocked) R.string.tl_limit_help_reset else R.string.tl_limit_help),
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
        )

        SectionLabel(stringResource(R.string.tl_section_pin))
        TbCard {
            NavRow(
                title = stringResource(R.string.tl_change_pin),
                subtitle = stringResource(if (pinLocked) R.string.tl_unavailable_reset else R.string.tl_change_pin_sub),
                enabled = !pinLocked,
                onClick = onChangePin,
            )
            CardDivider()
            NavRow(
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
        TbCard {
            NavRow(
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

@Composable
private fun todayStatus(view: TimeLimitView): String = when (val state = view.lockState) {
    is LockState.Running -> stringResource(R.string.tl_time_left, remainingText(state.remainingMillis))
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
private fun whenText(date: LocalDate, today: LocalDate?): String {
    // Built once per screen, not on every one-second refresh (and anew after a language change).
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    return if (today != null && date == today.plusDays(1)) stringResource(R.string.tl_tomorrow) else date.format(format)
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
