package app.throwback.timelimit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.Rules

/** Settings → Daily time limit: first-time setup, or managing an existing limit. */
@Composable
internal fun TimeLimitSettings(controller: DefaultTimeLimitController, onClose: () -> Unit) {
    val view by rememberLiveView(controller)
    val ready by controller.ready.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(ThrowbackTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when {
            !ready -> Unit // The saved state is still loading (a few milliseconds).
            view.limitMinutes == null -> SetupScreen(controller, onClose)
            else -> ManageScreen(controller, view, onClose)
        }
    }
}

// ---- Shared by setup and manage ---------------------------------------------------------------

/** Padding of the cards with free content on the limit screens (mockup `.card`). */
internal val CardPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)

/**
 * The 15/30/45/60 minute choice (mockup `.chip`; at least 48dp tall for touch). With a very large font
 * size a label wraps onto two lines and the chip grows, rather than being cut off.
 */
@Composable
internal fun LimitChoices(selected: Int, onPick: (Int) -> Unit) {
    val c = ThrowbackTheme.colors
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Rules.LIMIT_CHOICES.forEach { m ->
            val isSelected = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (isSelected) c.accent else c.surface2)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(m) })
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.tl_minutes_chip, m),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) c.onAccent else c.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
