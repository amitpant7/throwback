package app.throwback.ui.connect

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.IconBullet
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText

/**
 * Shown once, right after a daily limit is turned on while the Instagram app is installed but not
 * connected (DECISIONS.md #34c): opening that app would skip the limit. Connect, or not now.
 */
@Composable
internal fun LimitConnectOffer(onConnect: () -> Unit, onNotNow: () -> Unit) {
    val colors = ThrowbackTheme.colors
    SettingsScaffold(title = stringResource(R.string.ui_limit_connect_title), onBack = onNotNow, fillViewport = true) {
        Text(
            stringResource(R.string.ui_limit_connect_body),
            style = UiText.lead,
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        TbCard(contentPadding = PaddingValues(18.dp)) {
            IconBullet(R.drawable.ic_shield, stringResource(R.string.ui_install_point_guard))
        }
        Spacer(Modifier.weight(1f))
        TbButton(stringResource(R.string.ui_limit_connect_button), onClick = onConnect, modifier = Modifier.padding(top = 24.dp))
        TbButton(
            stringResource(R.string.ui_not_now),
            onClick = onNotNow,
            style = TbButtonStyle.Quiet,
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
        )
    }
}
