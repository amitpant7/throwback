package app.throwback.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.Supporter
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.SwitchRow
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.UiText
import app.throwback.core.prefs.AppPrefs

/** The saved settings Settings home shows: the pause switch and the Instagram row's value. */
internal data class HomeValues(val mindfulPause: Boolean, val instagramConnected: Boolean)

/**
 * Settings home. No filter toggles anywhere: the filters are always on (DECISIONS.md #14).
 * [values] are read by SettingsRoot, so they are right from the first frame (no switch animating from
 * a default, no "Set up" flashing before "Connected").
 */
@Composable
internal fun SettingsHomeScreen(values: HomeValues, prefs: AppPrefs, onBack: () -> Unit, onOpen: (SettingsRoute) -> Unit) {
    SettingsScaffold(stringResource(R.string.ui_settings_title), onBack = onBack) {
        RemovesCard()

        SectionLabel(stringResource(R.string.ui_section_connect))
        TbCard {
            NavRow(
                icon = R.drawable.ic_camera_app,
                title = stringResource(R.string.ui_messages_title),
                subtitle = stringResource(R.string.ui_messages_sub),
                value = stringResource(if (values.instagramConnected) R.string.ui_messages_value_on else R.string.ui_messages_value_setup),
                onClick = { onOpen(SettingsRoute.Messages) },
            )
        }

        SectionLabel(stringResource(R.string.ui_section_focus))
        TbCard {
            NavRow(
                icon = R.drawable.ic_usage,
                title = stringResource(R.string.ui_today_title),
                subtitle = stringResource(R.string.ui_today_row_sub),
                onClick = { onOpen(SettingsRoute.Today) },
            )
            CardDivider()
            NavRow(
                icon = R.drawable.ic_clock,
                title = stringResource(R.string.tl_title),
                subtitle = stringResource(R.string.ui_time_limit_sub),
                onClick = { onOpen(SettingsRoute.TimeLimit) },
            )
            CardDivider()
            SwitchRow(
                icon = R.drawable.ic_pause,
                title = stringResource(R.string.ui_pause_title),
                subtitle = stringResource(R.string.ui_pause_sub),
                checked = values.mindfulPause,
                onCheckedChange = { on -> prefs.write { setMindfulPause(on) } },
            )
        }

        SectionLabel(stringResource(R.string.ui_section_app))
        TbCard {
            val iconUnlocked by Supporter.tipped(LocalContext.current).collectAsStateWithLifecycle()
            NavRow(
                icon = R.drawable.ic_add_home,
                title = stringResource(R.string.ui_shortcut_title),
                subtitle = stringResource(if (iconUnlocked) R.string.ui_shortcut_row_sub else R.string.ui_shortcut_locked_title),
                onClick = { onOpen(SettingsRoute.Shortcut) },
            )
            CardDivider()
            NavRow(
                icon = R.drawable.ic_heart,
                title = stringResource(R.string.ui_support_title),
                onClick = { onOpen(SettingsRoute.Support) },
            )
            CardDivider()
            NavRow(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.ui_about_title),
                onClick = { onOpen(SettingsRoute.About) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Read-only list of what Throwback always removes. */
@Composable
private fun RemovesCard() {
    val colors = ThrowbackTheme.colors
    val items = listOf(
        R.string.ui_removes_reels,
        R.string.ui_removes_explore,
        R.string.ui_removes_suggested,
        R.string.ui_removes_ads,
    ).map { stringResource(it) }
    TbCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text(
            stringResource(R.string.ui_removes_title),
            style = UiText.rowStrong,
            color = colors.text,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { label ->
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TbIcon(R.drawable.ic_blocked, size = 18.dp, tint = colors.muted)
                            Text(label, style = UiText.body14, color = colors.muted)
                        }
                    }
                }
            }
        }
        // Inset hairline, as in the mockup (inside the card padding).
        Spacer(Modifier.height(12.dp))
        CardDivider()
        Text(
            stringResource(R.string.ui_removes_note),
            style = UiText.sub,
            color = colors.muted,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}
