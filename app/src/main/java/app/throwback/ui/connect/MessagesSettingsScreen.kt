package app.throwback.ui.connect

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.connect.CallInApp
import app.throwback.core.prefs.AppPrefs
import app.throwback.core.prefs.ExtraNotification
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.ExpandableRow
import app.throwback.core.ui.PillButton
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.SwitchRow
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.UiText
import app.throwback.ui.common.rememberCheckedOnResume
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Labels for the "Also notify me about" toggles, in the order shown. */
@StringRes
internal fun ExtraNotification.label(): Int = when (this) {
    ExtraNotification.LIKES -> R.string.ui_notify_likes
    ExtraNotification.COMMENTS -> R.string.ui_notify_comments
    ExtraNotification.NEW_FOLLOWERS -> R.string.ui_notify_new_followers
    ExtraNotification.STORY_MENTIONS_AND_TAGS -> R.string.ui_notify_story_mentions
    ExtraNotification.LIVE_VIDEOS -> R.string.ui_notify_live_videos
}

/** Connect › Instagram once set up: live status, always-on types, extra toggles, turn off. */
@Composable
internal fun MessagesSettingsScreen(
    prefs: AppPrefs,
    onBack: () -> Unit,
    onFinishSetup: () -> Unit,
    onTurnOff: () -> Unit,
    showMessage: (String) -> Unit = {},
    /** When a requested disconnect happens (ms; shown instead of the disconnect link), or null. */
    pendingOff: Long? = null,
    onCancelPendingOff: () -> Unit = {},
) {
    val colors = ThrowbackTheme.colors
    val state by rememberCheckedOnResume { MessagesSetupState.read(it) }
    val extras by prefs.extraNotifications.collectAsStateWithLifecycle(initialValue = emptySet())

    SettingsScaffold(stringResource(R.string.ui_messages_title), onBack = onBack) {
        StatusCard(state.status, onFinishSetup)

        SectionLabel(stringResource(R.string.ui_always_on))
        TbCard {
            AlwaysOnRow(R.drawable.ic_chat, R.string.ui_always_messages)
            CardDivider()
            AlwaysOnRow(R.drawable.ic_phone, R.string.ui_always_calls)
            CardDivider()
            AlwaysOnRow(R.drawable.ic_camera_app, R.string.ui_always_posting)
        }

        // Other alerts: one row (owner: the five switches took a lot of room); tap to show them.
        var extrasOpen by rememberSaveable { mutableStateOf(false) }
        val onLabels = ExtraNotification.entries.filter { it in extras }.map { stringResource(it.label()) }
        val summary = when {
            onLabels.isEmpty() -> stringResource(R.string.ui_extras_off)
            onLabels.size <= 2 -> onLabels.joinToString(", ")
            else -> stringResource(R.string.ui_extras_count, onLabels.size)
        }
        TbCard(Modifier.padding(top = 16.dp)) {
            ExpandableRow(
                title = stringResource(R.string.ui_also_notify),
                summary = summary,
                expanded = extrasOpen,
                onToggle = { extrasOpen = !extrasOpen },
                icon = R.drawable.ic_bell,
            )
            if (extrasOpen) {
                ExtraNotification.entries.forEach { type ->
                    CardDivider()
                    SwitchRow(
                        title = stringResource(type.label()),
                        checked = type in extras,
                        onCheckedChange = { on -> prefs.write { setExtraNotification(type, on) } },
                        minHeight = 52.dp,
                        titleStyle = UiText.row15,
                    )
                }
                Text(
                    stringResource(R.string.ui_notify_footer),
                    style = UiText.sub,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
                )
            }
        }

        InstagramNotificationsCard(showMessage = showMessage, modifier = Modifier.padding(top = 18.dp))

        // A deliberate, short way into the real app (switching accounts, its own settings).
        val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
        val context = LocalContext.current
        TbCard(
            Modifier.padding(top = 16.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.ui_visit_title), style = UiText.rowStrong, color = colors.text)
            Text(stringResource(R.string.ui_visit_body), style = UiText.sub, color = colors.muted)
            TbButton(
                stringResource(R.string.ui_visit_button),
                onClick = {
                    when (CallInApp.visit(context)) {
                        CallInApp.VisitResult.OPENED -> Unit
                        CallInApp.VisitResult.NO_APP -> showMessage(notInstalled)
                        CallInApp.VisitResult.USED_TODAY -> showMessage(context.getString(R.string.ui_visit_used))
                        CallInApp.VisitResult.LOCKED -> showMessage(context.getString(R.string.ui_visit_locked))
                        CallInApp.VisitResult.NOT_READY -> showMessage(context.getString(R.string.app_pass_not_ready))
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
                style = TbButtonStyle.Secondary,
            )
        }

        if (pendingOff != null) {
            Text(
                disconnectWhen(pendingOff),
                style = UiText.body14,
                color = colors.muted,
                modifier = Modifier.padding(start = 6.dp, top = 18.dp),
            )
            TbButton(
                stringResource(R.string.ui_messages_off_keep),
                onClick = onCancelPendingOff,
                modifier = Modifier.padding(bottom = 16.dp),
                style = TbButtonStyle.Quiet,
                textStyle = UiText.body14,
            )
        } else {
            var confirm by rememberSaveable { mutableStateOf(false) }
            if (confirm) DisconnectDialog(onConfirm = { confirm = false; onTurnOff() }, onDismiss = { confirm = false })
            TbButton(
                stringResource(R.string.ui_turn_off_messages),
                onClick = { confirm = true },
                modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
                style = TbButtonStyle.Quiet,
                textStyle = UiText.body14,
            )
        }
    }
}

@Composable
private fun StatusCard(status: MessagesStatus, onFinishSetup: () -> Unit) {
    val colors = ThrowbackTheme.colors
    val ready = status == MessagesStatus.Ready
    val title = when (status) {
        MessagesStatus.Ready -> stringResource(R.string.ui_status_ready_title)
        MessagesStatus.InstagramMissing -> stringResource(R.string.ui_status_no_app_title)
        is MessagesStatus.PermissionsMissing -> stringResource(R.string.ui_status_incomplete_title)
    }
    val body = when (status) {
        MessagesStatus.Ready -> stringResource(R.string.ui_status_ready_body)
        MessagesStatus.InstagramMissing -> stringResource(R.string.ui_status_no_app_body)
        is MessagesStatus.PermissionsMissing ->
            pluralStringResource(R.plurals.ui_status_permissions_off, status.count, status.count)
    }
    TbCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TbIcon(
                if (ready) R.drawable.ic_shield_check else R.drawable.ic_shield,
                size = 26.dp,
                tint = if (ready) colors.ok else colors.muted,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = UiText.rowStrong, color = colors.text)
                Text(body, style = UiText.sub, color = colors.muted)
            }
            if (!ready) PillButton(stringResource(R.string.ui_status_finish), onClick = onFinishSetup)
        }
    }
}

@Composable
private fun AlwaysOnRow(@DrawableRes icon: Int, @StringRes label: Int) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TbIcon(icon, size = 20.dp)
        Text(stringResource(label), style = UiText.row15, color = colors.text, modifier = Modifier.weight(1f))
        TbIcon(
            R.drawable.ic_check,
            size = 20.dp,
            tint = colors.ok,
            contentDescription = stringResource(R.string.ui_always_on_description),
        )
    }
}

/**
 * "Disconnect Instagram" takes effect 24 hours later (DECISIONS.md #27b), so the dialog says when,
 * and that the Instagram app stays covered until then.
 */
@Composable
private fun DisconnectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = ThrowbackTheme.colors
    val context = LocalContext.current
    val at = remember { System.currentTimeMillis() + 24 * 60 * 60 * 1000L }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.muted,
        title = { Text(stringResource(R.string.ui_messages_off_dialog_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                stringResource(R.string.ui_messages_off_dialog_body, DateFormat.getTimeFormat(context).format(Date(at))),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ui_messages_off_dialog_confirm), color = c.accentText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_messages_off_keep), color = c.text, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/** "Instagram disconnects today/tomorrow at 10:40 PM" (or a weekday and time further out). */
@Composable
private fun disconnectWhen(at: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = DateFormat.getTimeFormat(context).format(Date(at))
    return when (day) {
        today -> stringResource(R.string.ui_messages_off_today, time)
        today.plusDays(1) -> stringResource(R.string.ui_messages_off_tomorrow, time)
        else -> stringResource(
            R.string.ui_messages_off_on,
            DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL),
        )
    }
}
