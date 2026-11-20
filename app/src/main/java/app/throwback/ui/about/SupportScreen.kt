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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.ExpandChevron
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.UiText
import app.throwback.ui.common.SystemScreens

/**
 * Tip tiers (DECISIONS.md #4), in dollars until Google Play billing supplies local prices (the
 * currency is the one string ui_tip_currency). Each icon is one drawable so the artwork can be swapped.
 */
private enum class Tip(@DrawableRes val icon: Int, @StringRes val label: Int, @StringRes val line: Int, val amount: Int) {
    Beer(R.drawable.ui_tip_beer, R.string.ui_tip_beer, R.string.ui_tip_beer_line, 3),
    RockShow(R.drawable.ui_tip_rock, R.string.ui_tip_rock, R.string.ui_tip_rock_line, 10),
    Gta6(R.drawable.ui_tip_gta, R.string.ui_tip_gta, R.string.ui_tip_gta_line, 25),
}

/**
 * Support Throwback (design A "list rows", owner 2026-11-20): one row per tip with its icon, a line
 * and the price, plus "Whatever you want", a typed amount. Prices are placeholders until Google Play
 * billing supplies the local ones.
 */
@Composable
internal fun SupportScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val tipsSoon = stringResource(R.string.ui_tips_soon)
    val currency = stringResource(R.string.ui_tip_currency)
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }
    val focus = LocalFocusManager.current
    var bigOpen by rememberSaveable { mutableStateOf(false) }

    // The scaffold keeps the amount field above the keyboard.
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
                    icon = { Image(painterResource(tip.icon), contentDescription = null, modifier = Modifier.size(40.dp)) },
                    name = stringResource(tip.label),
                    line = stringResource(tip.line),
                    onClick = { showMessage(tipsSoon) },
                ) { PricePill(stringResource(R.string.ui_tip_price, currency, tip.amount)) }
            }
            CardDivider()
            TipRow(
                icon = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surface2), contentAlignment = Alignment.Center) {
                        TbIcon(R.drawable.ic_heart, size = 22.dp, tint = colors.accentText)
                    }
                },
                name = stringResource(R.string.ui_tip_any),
                line = stringResource(R.string.ui_tip_any_line),
                onClick = { bigOpen = !bigOpen },
                expanded = bigOpen,
            ) { ExpandChevron(bigOpen) }
            if (bigOpen) {
                // Any amount (owner): typed freely. Play needs fixed prices, so billing will charge
                // the closest tip product (set up in steps); until then it says tips are coming.
                var amount by rememberSaveable { mutableStateOf("") }
                val value = amount.toIntOrNull()?.takeIf { it in 1..9999 }
                val tip = { if (value != null) showMessage(tipsSoon) }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface2)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currency, style = UiText.row15.copy(fontWeight = FontWeight.Bold), color = colors.muted)
                        androidx.compose.foundation.text.BasicTextField(
                            value = amount,
                            onValueChange = { t -> amount = t.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            textStyle = UiText.row15.copy(fontWeight = FontWeight.Bold, color = colors.text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focus.clearFocus()
                                    tip()
                                },
                            ),
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (amount.isEmpty()) {
                                        Text(stringResource(R.string.ui_tip_any_hint), style = UiText.row15, color = colors.muted)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    Surface(
                        onClick = tip,
                        enabled = value != null,
                        shape = RoundedCornerShape(12.dp),
                        color = if (value != null) colors.accent else colors.surface2,
                        contentColor = if (value != null) colors.onAccent else colors.muted,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (value != null) {
                                    stringResource(R.string.ui_tip_any_button, stringResource(R.string.ui_tip_price, currency, value))
                                } else {
                                    stringResource(R.string.ui_tip_any_button_empty)
                                },
                                style = UiText.row15.copy(fontWeight = FontWeight.ExtraBold),
                            )
                        }
                    }
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
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One tip: icon, name, line and [trailing]. With [expanded] it shows or hides more below it (TalkBack hears which). */
@Composable
private fun TipRow(
    icon: @Composable () -> Unit,
    name: String,
    line: String,
    onClick: () -> Unit,
    expanded: Boolean? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    val state = expanded?.let { stringResource(if (it) R.string.expanded else R.string.collapsed) }
    val action = expanded?.let { stringResource(if (it) R.string.collapse else R.string.expand) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { if (state != null) stateDescription = state }
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
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
