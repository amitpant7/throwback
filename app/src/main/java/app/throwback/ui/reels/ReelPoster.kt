package app.throwback.ui.reels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.ScreenColumn
import app.throwback.core.ui.UiText
import app.throwback.core.ui.blockTouches

/**
 * Full-screen poster after the reel allowance in Instagram's swipe viewer. A first, plain version;
 * the designed poster (and its animated version) replaces this. The page has already closed
 * Instagram's reel viewer underneath, so system Back (or the back swipe) just dismisses the poster.
 * No button: Instagram's reel viewer has none either (owner's decision). Touches never reach the
 * page underneath.
 */
@Composable
internal fun ReelPosterContent(onBack: () -> Unit, backEnabled: Boolean = true) {
    val colors = ThrowbackTheme.colors
    BackHandler(enabled = backEnabled, onBack = onBack)
    // Centred, and scrolls when it doesn't fit (large font sizes): the last line is the only way-out hint.
    ScreenColumn(
        PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        Modifier.background(colors.bg).blockTouches().safeDrawingPadding(),
        horizontalAlignment = Alignment.Start,
    ) {
        val big = MaterialTheme.typography.displaySmall.copy(lineHeight = 38.sp)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.ui_reel_poster_title),
            style = big,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ui_reel_poster_nah), style = big.copy(fontSize = 26.sp), color = colors.accentText)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.ui_reel_poster_go), style = big.copy(fontSize = 22.sp), color = colors.text)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ui_reel_poster_ideas),
            style = UiText.row15.copy(fontSize = 18.sp, lineHeight = 28.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ui_reel_poster_bored),
            style = UiText.row15.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.ui_reel_poster_rule), style = UiText.body14, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ui_reel_poster_hint), style = UiText.body14Strong, color = colors.muted)
        Spacer(Modifier.weight(1f))
    }
}
