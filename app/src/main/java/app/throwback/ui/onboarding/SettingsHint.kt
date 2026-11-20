package app.throwback.ui.onboarding

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.UiPrefs
import app.throwback.ui.common.PillButton
import app.throwback.ui.common.UiText
import kotlinx.coroutines.flow.first

/** Whether the one-time settings hint is still to be shown. */
internal suspend fun shouldShowSettingsHint(context: Context): Boolean =
    !UiPrefs.from(context).settingsHintShown.first()

/** Marks the hint as done, e.g. when the user found Throwback's settings on their own. */
internal suspend fun markSettingsHintShown(context: Context) {
    UiPrefs.from(context).setSettingsHintShown()
}

/**
 * A small card above Instagram's bottom bar, pointing at the Home tab: long-press Home opens
 * Throwback's settings. Shown once, on the feed, after setup.
 */
@Composable
internal fun SettingsHintContent(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val done: () -> Unit = {
        // Process-wide write: onDone removes this card, and a scope of its own would be cancelled
        // with it, possibly before the write.
        UiPrefs.from(context).write { setSettingsHintShown() }
        onDone()
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Instagram's bottom bar has 4 tabs once Reels is gone; Home is the first.
        val arrowX = (maxWidth / 8) - 12.dp
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                contentColor = colors.text,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_settings_hint_title), style = UiText.row15Strong)
                    Text(stringResource(R.string.ui_settings_hint_body), style = UiText.body14, color = colors.muted)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        PillButton(stringResource(R.string.ui_settings_hint_ok), onClick = done)
                    }
                }
            }
            // Arrow down to the Home tab.
            Canvas(Modifier.padding(start = arrowX.coerceAtLeast(8.dp)).size(width = 20.dp, height = 10.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, colors.surface)
                drawLine(colors.line, Offset(0f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 0f)
            }
        }
    }
}
