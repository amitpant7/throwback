package app.throwback.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText
import app.throwback.ui.common.SystemScreens

/**
 * Support Throwback: a line from the developer, then the build's way to say thanks ([SupportTips]:
 * Google Play tips, or the website's support page; DECISIONS.md #4e), then reporting and Say Hi.
 */
@Composable
internal fun SupportScreen(onBack: () -> Unit, onOpenPage: (String) -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }

    SettingsScaffold(
        stringResource(R.string.ui_support_title),
        onBack = onBack,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.tb_logo), contentDescription = null, modifier = Modifier.size(52.dp))
            Text(stringResource(R.string.ui_support_intro), style = UiText.body14, color = colors.muted)
        }
        SupportTips(showMessage, open)
        TbCard(Modifier.padding(top = 18.dp)) {
            NavRow(title = stringResource(R.string.ui_link_report), onClick = { open(AboutLinks.REPORT_MISSED_URL) })
            CardDivider()
            // Opens in Throwback, in the feed's place (owner, 2026-11-21).
            NavRow(
                title = stringResource(R.string.ui_support_say_hi),
                value = AboutLinks.DEV_INSTAGRAM_HANDLE,
                onClick = { onOpenPage(AboutLinks.DEV_INSTAGRAM_URL) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
