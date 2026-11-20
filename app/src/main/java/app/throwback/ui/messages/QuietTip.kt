package app.throwback.ui.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.common.CardDivider
import app.throwback.ui.common.SecondaryButton
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.TbCard
import app.throwback.ui.common.UiText

/*
 * Optional tip (owner, 2026-11-20: it was step 2 of the setup). Throwback's relay already removes
 * every Instagram notification except messages and calls; turning the rest off in Instagram itself
 * just keeps the phone extra quiet (e.g. while the relay is off). Folded by default.
 */

private class QuietRow(@StringRes val label: Int, val keepOn: Boolean)

private val QUIET_ROWS = listOf(
    QuietRow(R.string.ui_quiet_row_messages, keepOn = true),
    QuietRow(R.string.ui_quiet_row_calls, keepOn = true),
    QuietRow(R.string.ui_quiet_row_posts, keepOn = false),
    QuietRow(R.string.ui_quiet_row_following, keepOn = false),
    QuietRow(R.string.ui_quiet_row_live, keepOn = false),
    QuietRow(R.string.ui_quiet_row_fundraisers, keepOn = false),
    QuietRow(R.string.ui_quiet_row_from_instagram, keepOn = false),
)

@Composable
internal fun QuietTipCard(showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
    val openFailed = stringResource(R.string.ui_instagram_open_failed)
    var open by rememberSaveable { mutableStateOf(false) }

    TbCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_quiet_tip_title), style = UiText.rowStrong, color = colors.text)
            Text(stringResource(R.string.ui_quiet_tip_body), style = UiText.sub, color = colors.muted)
            Text(
                stringResource(if (open) R.string.ui_quiet_tip_hide else R.string.ui_quiet_tip_show),
                style = UiText.body14Strong,
                color = colors.accentText,
                modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
            )
        }
        if (open) {
            QUIET_ROWS.forEach { row ->
                CardDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(row.label), style = UiText.row15, color = colors.text, modifier = Modifier.weight(1f))
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
            SecondaryButton(
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
            )
        }
    }
}
