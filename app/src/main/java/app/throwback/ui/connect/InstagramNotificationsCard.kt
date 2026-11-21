package app.throwback.ui.connect

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.connect.relay.RelayStats
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.rememberCheckedOnResume

/*
 * Instagram's notifications must stay on (owner, 2026-11-21, DECISIONS.md #34bb; replaces the
 * "quieter phone" tip, which said to switch the 'Instagram' group off). Throwback reads them: messages
 * to pass on, calls, and its posting notification, the sign that a story went up. Instagram puts them
 * in two groups in Android's settings, 'Instagram messages' and 'Instagram'; both stay on, and
 * Throwback removes what the user doesn't want. Android doesn't let Throwback read another app's
 * switches, so the card asks, and says what the relay has seen.
 */

/** The two groups Instagram puts its notifications in (as seen in Android's settings for the app). */
private class NotificationGroup(@StringRes val label: Int, @StringRes val sub: Int)

private val GROUPS = listOf(
    NotificationGroup(R.string.ui_ig_notif_group_messages, R.string.ui_ig_notif_group_messages_sub),
    NotificationGroup(R.string.ui_ig_notif_group_other, R.string.ui_ig_notif_group_other_sub),
)

@Composable
internal fun InstagramNotificationsCard(showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
    val openFailed = stringResource(R.string.ui_instagram_open_failed)

    TbCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_ig_notif_title), style = UiText.rowStrong, color = colors.text)
            Text(stringResource(R.string.ui_ig_notif_body), style = UiText.sub, color = colors.muted)
            // What the relay saw from the 'Instagram' group: none for a week suggests it is switched off.
            val seen by rememberCheckedOnResume { RelayStats.otherGroupLastDays(it) }
            Text(
                stringResource(if (seen > 0) R.string.ui_ig_notif_working else R.string.ui_ig_notif_check),
                style = UiText.body14Strong,
                color = if (seen > 0) colors.accentText else colors.text,
            )
        }
        GROUPS.forEach { row ->
            CardDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(row.label), style = UiText.row15, color = colors.text)
                    Text(stringResource(row.sub), style = UiText.fine, color = colors.muted)
                }
                Text(
                    stringResource(R.string.ui_ig_notif_on),
                    style = UiText.fine.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        CardDivider()
        TbButton(
            stringResource(R.string.ui_ig_notif_open),
            onClick = {
                // Android's own notification settings for Instagram: both groups, one tap away
                // (Instagram's in-app menus move around). Not the Instagram app itself, so the guard
                // isn't involved.
                val pkg = InstagramApp.installedPackage(context)
                when {
                    pkg == null -> showMessage(notInstalled)
                    !SystemScreens.openAppNotificationSettings(context, pkg) -> showMessage(openFailed)
                }
            },
            modifier = Modifier.padding(16.dp),
            style = TbButtonStyle.Secondary,
        )
    }
}
