package app.throwback.ui.messages

import androidx.compose.ui.platform.LocalContext
import app.throwback.ui.common.SecondaryButton
import app.throwback.relay.CallInApp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.ExtraNotification
import app.throwback.ui.UiPrefs
import app.throwback.ui.common.CardDivider
import app.throwback.ui.common.LineIcon
import app.throwback.ui.common.PillButton
import app.throwback.ui.common.QuietButton
import app.throwback.ui.common.SectionLabel
import app.throwback.ui.common.SwitchRow
import app.throwback.ui.common.TbCard
import app.throwback.ui.common.TopBar
import app.throwback.ui.common.UiText
import app.throwback.ui.common.rememberCheckedOnResume

/** Labels for the "Also notify me about" toggles, in the order shown. */
@StringRes
internal fun ExtraNotification.label(): Int = when (this) {
    ExtraNotification.LIKES -> R.string.ui_notify_likes
    ExtraNotification.COMMENTS -> R.string.ui_notify_comments
    ExtraNotification.NEW_FOLLOWERS -> R.string.ui_notify_new_followers
    ExtraNotification.STORY_MENTIONS_AND_TAGS -> R.string.ui_notify_story_mentions
    ExtraNotification.LIVE_VIDEOS -> R.string.ui_notify_live_videos
}

/** Messages & calls once set up: live status, always-on types, extra toggles, turn off. */
@Composable
internal fun MessagesSettingsScreen(
    prefs: UiPrefs,
    onBack: () -> Unit,
    onFinishSetup: () -> Unit,
    onTurnOff: () -> Unit,
    showMessage: (String) -> Unit = {},
) {
    val colors = ThrowbackTheme.colors
    val state by rememberCheckedOnResume { MessagesSetupState.read(it) }
    val extras by prefs.extraNotifications.collectAsStateWithLifecycle(initialValue = emptySet())

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        TopBar(stringResource(R.string.ui_messages_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            StatusCard(state.status, onFinishSetup)

            SectionLabel(stringResource(R.string.ui_always_on))
            TbCard {
                AlwaysOnRow(R.drawable.ui_ic_chat, R.string.ui_always_messages)
                CardDivider()
                AlwaysOnRow(R.drawable.ui_ic_phone, R.string.ui_always_calls)
            }

            SectionLabel(stringResource(R.string.ui_also_notify))
            TbCard {
                ExtraNotification.entries.forEachIndexed { index, type ->
                    if (index > 0) CardDivider()
                    SwitchRow(
                        title = stringResource(type.label()),
                        checked = type in extras,
                        onCheckedChange = { on -> prefs.write { setExtraNotification(type, on) } },
                        minHeight = 52.dp,
                        titleStyle = UiText.row15,
                    )
                }
            }
            Text(
                stringResource(R.string.ui_notify_footer),
                style = UiText.sub,
                color = colors.muted,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
            )

            QuietTipCard(showMessage = showMessage, modifier = Modifier.padding(top = 18.dp))

            // A deliberate, short way into the real app (switching accounts, its own settings).
            val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
            val context = LocalContext.current
            TbCard(Modifier.padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_visit_title), style = UiText.rowStrong, color = colors.text)
                    Text(stringResource(R.string.ui_visit_body), style = UiText.sub, color = colors.muted)
                    SecondaryButton(
                        stringResource(R.string.ui_visit_button),
                        onClick = {
                            when (CallInApp.visit(context)) {
                                CallInApp.VisitResult.OPENED -> Unit
                                CallInApp.VisitResult.NO_APP -> showMessage(notInstalled)
                                CallInApp.VisitResult.USED_TODAY -> showMessage(context.getString(R.string.ui_visit_used))
                                CallInApp.VisitResult.LOCKED -> showMessage(context.getString(R.string.ui_visit_locked))
                            }
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            QuietButton(
                stringResource(R.string.ui_turn_off_messages),
                onClick = onTurnOff,
                style = UiText.body14,
                modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
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
            LineIcon(
                if (ready) R.drawable.ui_ic_shield_check else R.drawable.ui_ic_shield,
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
        LineIcon(icon, size = 20.dp)
        Text(stringResource(label), style = UiText.row15, color = colors.text, modifier = Modifier.weight(1f))
        LineIcon(
            R.drawable.ui_ic_check,
            size = 20.dp,
            tint = colors.ok,
            contentDescription = stringResource(R.string.ui_always_on_description),
        )
    }
}
