package app.throwback.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.BuildConfig
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.ThrowbackLogo
import app.throwback.core.ui.UiText
import app.throwback.core.ui.ValueRow
import app.throwback.ui.common.SystemScreens
import app.throwback.web.rules.RulesRepository

/** Links shown on About & support. */
internal object AboutLinks {
    const val REPO_URL = "https://github.com/amitpant7/throwback"

    /** The app's page on the developer's website (DECISIONS.md #34p): privacy policy, contact form. */
    const val WEBSITE_URL = "https://amitpant.com.np/throwback/"
    const val PRIVACY_POLICY_URL = "${WEBSITE_URL}privacy/"

    /** The website's contact form (no GitHub account needed); it has a "Something Throwback missed" topic. */
    const val REPORT_MISSED_URL = "${WEBSITE_URL}#contact"

    /** The website's section on tips (Play Billing only; no other payment links: Play policy). */
    const val HOW_TIPS_WORK_URL = "${WEBSITE_URL}#support"

    /** The developer on Instagram (Support → Say Hi), opened in Throwback. */
    const val DEV_INSTAGRAM_HANDLE = "@97m.it"
    const val DEV_INSTAGRAM_URL = "https://www.instagram.com/97m.it/"
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.ui_link_failed)
    val open: (String) -> Unit = { url -> if (!SystemScreens.openUrl(context, url)) showMessage(linkFailed) }

    SettingsScaffold(
        stringResource(R.string.ui_about_title),
        onBack = onBack,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
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
                title = stringResource(R.string.ui_link_website),
                onClick = { open(AboutLinks.WEBSITE_URL) },
                minHeight = 52.dp,
            )
            CardDivider()
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

/** The date of the filter rules in use (bundled, or the latest fetched copy); a dash until known. */
@Composable
private fun FiltersUpdatedRow() {
    val context = LocalContext.current
    val repository = remember { RulesRepository.get(context) }
    val rules by repository.rules.collectAsStateWithLifecycle()
    LaunchedEffect(repository) { repository.load() }
    val date = remember(rules?.updated) { rules?.updated?.let(::formatRulesDate) }
    ValueRow(stringResource(R.string.ui_filters_updated), date)
}

/** "2026-11-20" → the phone's medium date format, e.g. "20 Nov 2026"; null if it isn't a date. */
private fun formatRulesDate(iso: String): String? = try {
    java.time.LocalDate.parse(iso).format(
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM),
    )
} catch (e: java.time.format.DateTimeParseException) {
    null
}
