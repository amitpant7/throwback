package app.throwback.ui.connect

import app.throwback.ui.common.rememberCheckedOnResume
import app.throwback.connect.relay.RelayStats
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.ui.common.SystemScreens
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText

/*
 * Optional tip (owner, 2026-11-20: it was step 2 of the setup). Throwback's relay already removes
 * every Instagram notification except messages and calls; turning the rest off in Instagram itself
 * just keeps the phone extra quiet (e.g. while the relay is off). Instagram puts its notifications in
 * two groups in Android's settings: 'Instagram messages' (messages, requests, video calls; keep) and
 * 'Instagram' (everything else; turn off, on most phones one switch). Same groups on every phone with
 * the same Instagram version; the settings screen's look varies by phone.
 */

/** The two groups Instagram puts its notifications in (as seen in Android's settings for the app). */
private class QuietGroup(@StringRes val label: Int, @StringRes val sub: Int, val keepOn: Boolean)

private val QUIET_GROUPS = listOf(
    QuietGroup(R.string.ui_quiet_group_messages, R.string.ui_quiet_group_messages_sub, keepOn = true),
    QuietGroup(R.string.ui_quiet_group_other, R.string.ui_quiet_group_other_sub, keepOn = false),
)

@Composable
internal fun QuietTipCard(showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
    val openFailed = stringResource(R.string.ui_instagram_open_failed)

    TbCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_quiet_tip_title), style = UiText.rowStrong, color = colors.text)
            Text(stringResource(R.string.ui_quiet_tip_body), style = UiText.sub, color = colors.muted)
            // What the relay saw (Android doesn't let Throwback read Instagram's switches directly).
            val seen by rememberCheckedOnResume { RelayStats.otherGroupLastDays(it) }
            Text(
                if (seen > 0) pluralStringResource(R.plurals.ui_quiet_seen_other, seen, seen) else stringResource(R.string.ui_quiet_seen_none),
                style = UiText.body14Strong,
                color = if (seen > 0) colors.text else colors.accentText,
            )
        }
        QUIET_GROUPS.forEach { row ->
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
                val tagBg = if (row.keepOn) colors.accent else colors.surface2
                val tagFg = if (row.keepOn) colors.onAccent else colors.text
                Text(
                    stringResource(if (row.keepOn) R.string.ui_quiet_keep_on else R.string.ui_quiet_turn_off),
                    style = UiText.fine.copy(fontWeight = FontWeight.SemiBold),
                    color = tagFg,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tagBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        CardDivider()
        TbButton(
            stringResource(R.string.ui_quiet_open_instagram),
            onClick = {
                // Android's own notification settings for Instagram: every category with a switch,
                // one tap away (Instagram's in-app menus move around). Not the Instagram app itself,
                // so the guard isn't involved.
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
