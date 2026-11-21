package app.throwback.ui.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.billing.TipProduct
import app.throwback.billing.Tips
import app.throwback.core.findActivity
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.connect.InstallSource
import kotlinx.coroutines.launch

/** The tips (DECISIONS.md #4, #4b), each a Google Play product. Each icon is one drawable so the artwork can be swapped. */
private enum class Tip(@DrawableRes val icon: Int, @StringRes val label: Int, @StringRes val line: Int, val product: TipProduct) {
    Beer(R.drawable.ui_tip_beer, R.string.ui_tip_beer, R.string.ui_tip_beer_line, TipProduct.SMALL),
    GigTicket(R.drawable.ui_tip_rock, R.string.ui_tip_gig, R.string.ui_tip_gig_line, TipProduct.MEDIUM),
    Gta6(R.drawable.ui_tip_gta, R.string.ui_tip_gta, R.string.ui_tip_gta_line, TipProduct.LARGE),
}

/**
 * Support Throwback (design A "list rows", owner 2026-11-20): one row per tip with its icon, a line
 * and its price in the user's currency (from Google Play; the dollar amounts until it answers). A tap
 * opens Google Play's purchase sheet; how it ended shows as a message. Any tip unlocks Make It Yours.
 */
@Composable
internal fun SupportScreen(onBack: () -> Unit, onOpenPage: (String) -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currency = stringResource(R.string.ui_tip_currency)
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }
    val prices by Tips.prices.collectAsStateWithLifecycle()
    val cantTip = stringResource(if (InstallSource.fromPlay(context)) R.string.ui_tips_unavailable else R.string.ui_tips_not_play)
    val outcome = mapOf(
        Tips.Event.THANKS to stringResource(R.string.ui_tip_thanks),
        Tips.Event.PENDING to stringResource(R.string.ui_tip_pending),
        Tips.Event.FAILED to stringResource(R.string.ui_tip_failed),
    )
    LaunchedEffect(Unit) { Tips.refresh(context) }
    LaunchedEffect(Unit) { Tips.events.collect { outcome[it]?.let(showMessage) } }

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
        SectionLabel(stringResource(R.string.ui_support_leave_tip))
        TbCard {
            Tip.entries.forEachIndexed { i, tip ->
                if (i > 0) CardDivider()
                TipRow(
                    icon = tip.icon,
                    name = stringResource(tip.label),
                    line = stringResource(tip.line),
                    onClick = {
                        val activity = context.findActivity()
                        scope.launch { if (activity == null || !Tips.buy(activity, tip.product)) showMessage(cantTip) }
                    },
                ) {
                    PricePill(prices[tip.product] ?: stringResource(R.string.ui_tip_price, currency, tip.product.fallbackUsd))
                }
            }
        }
        Text(
            stringResource(R.string.ui_support_fine),
            style = UiText.sub,
            color = colors.muted,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
        )
        TbCard(Modifier.padding(top = 18.dp)) {
            NavRow(title = stringResource(R.string.ui_support_how), onClick = { open(AboutLinks.HOW_TIPS_WORK_URL) })
            CardDivider()
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

/** One tip: icon, name, line and [trailing] (its price). */
@Composable
private fun TipRow(
    @DrawableRes icon: Int,
    name: String,
    line: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = UiText.row.copy(fontWeight = FontWeight.Bold), color = colors.text)
            Text(line, style = UiText.sub, color = colors.muted)
        }
        trailing()
    }
}

@Composable
private fun PricePill(price: String) {
    val colors = ThrowbackTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent)
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(price, style = UiText.row15.copy(fontWeight = FontWeight.ExtraBold), color = colors.onAccent)
    }
}
