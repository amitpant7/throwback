package app.throwback.ui.about

import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.TbIcon
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import app.throwback.web.rules.RulesRepository
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.BuildConfig
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.ui.common.SystemScreens
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.ThrowbackLogo
import app.throwback.core.ui.TbTopBar
import app.throwback.core.ui.UiText

/**
 * Links shown on About & support.
 * PLACEHOLDERS: the public repository doesn't exist yet. Replace OWNER/REPO before release.
 */
internal object AboutLinks {
    const val REPO_URL = "https://github.com/amitpant7/throwback"
    const val PRIVACY_POLICY_URL = "$REPO_URL/blob/main/PRIVACY.md"
    const val REPORT_MISSED_URL = "$REPO_URL/issues/new"
    /** README section explaining tips (Play Billing only; no other payment links: Play policy). */
    const val HOW_TIPS_WORK_URL = "$REPO_URL#how-tips-work"
}

/** Tip tiers (DECISIONS.md #4). Each icon is one drawable so the artwork can be swapped later. */
private enum class Tip(@DrawableRes val icon: Int, @StringRes val label: Int, @StringRes val line: Int, @StringRes val price: Int) {
    Beer(R.drawable.ui_tip_beer, R.string.ui_tip_beer, R.string.ui_tip_beer_line, R.string.ui_tip_beer_price),
    RockShow(R.drawable.ui_tip_rock, R.string.ui_tip_rock, R.string.ui_tip_rock_line, R.string.ui_tip_rock_price),
    Gta6(R.drawable.ui_tip_gta, R.string.ui_tip_gta, R.string.ui_tip_gta_line, R.string.ui_tip_gta_price),
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }

    Column(Modifier.fillMaxSize()) {
        TbTopBar(stringResource(R.string.ui_about_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .navigationBarsPadding(),
        ) {
            // Identity
            val appName = stringResource(R.string.app_name)
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThrowbackLogo(56.dp)
                Text(appName, style = UiText.wordmark.copy(fontSize = 22.sp, lineHeight = 26.sp), color = colors.text)
                Text(
                    stringResource(R.string.ui_about_version, BuildConfig.VERSION_NAME),
                    style = UiText.sub,
                    color = colors.muted,
                )
            }

            // Links
            TbCard {
                NavRow(
                    title = stringResource(R.string.ui_link_source),
                    onClick = { open(AboutLinks.REPO_URL) },
                    minHeight = 52.dp,
                )
                CardDivider()
                NavRow(
                    title = stringResource(R.string.ui_link_privacy),
                    onClick = { open(AboutLinks.PRIVACY_POLICY_URL) },
                    minHeight = 52.dp,
                )
                CardDivider()
                FiltersUpdatedRow()
                CardDivider()
                NavRow(
                    title = stringResource(R.string.ui_link_report),
                    onClick = { open(AboutLinks.REPORT_MISSED_URL) },
                    minHeight = 52.dp,
                )
            }

            Text(
                stringResource(R.string.ui_disclaimer),
                style = UiText.fine,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 24.dp),
            )
        }
    }
}

/**
 * Support Throwback (design A "list rows", owner 2026-11-20): one row per tip with its icon, a line
 * and the price, plus "Whatever you want" ($50 / $100 / $200: Play needs fixed prices). Prices are
 * placeholders until Google Play billing supplies the local ones.
 */
@Composable
internal fun SupportScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val tipsSoon = stringResource(R.string.ui_tips_soon)
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }
    val focus = LocalFocusManager.current
    var bigOpen by rememberSaveable { mutableStateOf(false) }

    // imePadding: edge to edge, the screen itself keeps the amount field above the keyboard.
    Column(Modifier.fillMaxSize().imePadding()) {
        TbTopBar(stringResource(R.string.ui_support_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .navigationBarsPadding(),
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
                    ) { PricePill(stringResource(tip.price)) }
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
                ) {
                    TbIcon(
                        R.drawable.ic_chevron, size = 18.dp, tint = colors.muted,
                        modifier = Modifier.rotate(if (bigOpen) 270f else 90f),
                    )
                }
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
                            Text("$", style = UiText.row15.copy(fontWeight = FontWeight.Bold), color = colors.muted)
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
                                    if (value != null) stringResource(R.string.ui_tip_any_button, value) else stringResource(R.string.ui_tip_any_button_empty),
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
}

@Composable
private fun TipRow(icon: @Composable () -> Unit, name: String, line: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
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

/** The date of the filter rules in use (bundled, or the latest fetched copy); a dash until known. */
@Composable
private fun FiltersUpdatedRow() {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val repository = remember { RulesRepository.get(context) }
    val rules by repository.rules.collectAsStateWithLifecycle()
    LaunchedEffect(repository) { repository.load() }
    val date = remember(rules?.updated) { rules?.updated?.let(::formatRulesDate) }
    val title = stringResource(R.string.ui_filters_updated)
    val value = date ?: stringResource(R.string.ui_filters_updated_unknown)
    val description = if (date != null) "$title: $date" else stringResource(R.string.ui_filters_updated_unknown_description, title)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = UiText.row, color = colors.text, modifier = Modifier.weight(1f))
        Text(value, style = UiText.body14, color = colors.muted)
    }
}

/** "2026-11-20" → the phone's medium date format, e.g. "20 Nov 2026"; null if it isn't a date. */
private fun formatRulesDate(iso: String): String? = try {
    java.time.LocalDate.parse(iso).format(
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM),
    )
} catch (e: java.time.format.DateTimeParseException) {
    null
}
