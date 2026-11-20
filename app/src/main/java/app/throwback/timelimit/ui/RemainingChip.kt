package app.throwback.timelimit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.LockState
import app.throwback.core.TimeLimitController
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.TbIcon

/**
 * "18 min left" on the home feed (mockup 2). In the last minute it switches to "0:42 left" in the
 * accent colour as a warning. Draws nothing unless the limit is running.
 */
@Composable
internal fun RemainingChip(controller: TimeLimitController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    val running = state as? LockState.Running ?: return
    val c = ThrowbackTheme.colors
    val text: String
    val description: String
    val warning: Boolean
    when (val chip = Durations.chip(running.remainingMillis)) {
        is Durations.Chip.Minutes -> {
            text = stringResource(R.string.tl_min_left, chip.minutes)
            description = pluralStringResource(R.plurals.tl_chip_minutes_cd, chip.minutes, chip.minutes)
            warning = false
        }
        is Durations.Chip.LastMinute -> {
            text = stringResource(R.string.tl_chip_seconds_left, minSecText(running.remainingMillis))
            description = pluralStringResource(R.plurals.tl_chip_seconds_cd, chip.seconds, chip.seconds)
            warning = true
        }
    }
    val fg = if (warning) c.onAccent else c.text
    // At least 30dp tall, and taller with a large font size instead of cutting the text off.
    Row(
        modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (warning) c.accent else c.surface2)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TbIcon(R.drawable.ic_clock, size = 16.dp, tint = fg)
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = fg, maxLines = 1)
    }
}
