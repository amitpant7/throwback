package app.throwback.ui.about

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.BuildConfig
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.common.CardDivider
import app.throwback.ui.common.NavRow
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.TbCard
import app.throwback.ui.common.ThrowbackLogo
import app.throwback.ui.common.TopBar
import app.throwback.ui.common.UiText

/**
 * Links shown on About & support.
 * PLACEHOLDERS: the public repository doesn't exist yet. Replace OWNER/REPO before release.
 */
internal object AboutLinks {
    const val PLACEHOLDER_REPO_URL = "https://github.com/OWNER/throwback"
    const val PLACEHOLDER_PRIVACY_POLICY_URL = "$PLACEHOLDER_REPO_URL/blob/main/PRIVACY.md"
    const val PLACEHOLDER_REPORT_MISSED_URL = "$PLACEHOLDER_REPO_URL/issues/new"
}

/** Tip tiers (DECISIONS.md #4). Each icon is one drawable so the artwork can be swapped later. */
private enum class Tip(@DrawableRes val icon: Int, @StringRes val label: Int) {
    Beer(R.drawable.ui_tip_beer, R.string.ui_tip_beer),
    RockShow(R.drawable.ui_tip_rock, R.string.ui_tip_rock),
    Gta6(R.drawable.ui_tip_gta, R.string.ui_tip_gta),
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.ui_link_failed)
    val tipsSoon = stringResource(R.string.ui_tips_soon)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.ui_about_title), onBack = onBack)
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

            // Support
            TbCard {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.ui_support_title),
                        style = UiText.rowStrong,
                        color = colors.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.ui_support_body),
                        style = UiText.body14,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tip.entries.forEach { tip ->
                            TipButton(tip, onClick = { showMessage(tipsSoon) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Links
            TbCard(Modifier.padding(top = 16.dp)) {
                NavRow(
                    title = stringResource(R.string.ui_link_source),
                    onClick = { open(AboutLinks.PLACEHOLDER_REPO_URL) },
                    minHeight = 52.dp,
                )
                CardDivider()
                NavRow(
                    title = stringResource(R.string.ui_link_privacy),
                    onClick = { open(AboutLinks.PLACEHOLDER_PRIVACY_POLICY_URL) },
                    minHeight = 52.dp,
                )
                CardDivider()
                FiltersUpdatedRow()
                CardDivider()
                NavRow(
                    title = stringResource(R.string.ui_link_report),
                    onClick = { open(AboutLinks.PLACEHOLDER_REPORT_MISSED_URL) },
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

@Composable
private fun TipButton(tip: Tip, onClick: () -> Unit, modifier: Modifier) {
    val colors = ThrowbackTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = colors.surface2,
        contentColor = colors.text,
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Image(painterResource(tip.icon), contentDescription = null, modifier = Modifier.size(42.dp))
            Text(
                stringResource(tip.label),
                style = UiText.row15.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
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
