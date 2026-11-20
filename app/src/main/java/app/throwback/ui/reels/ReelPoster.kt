package app.throwback.ui.reels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.Nunito
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.common.PrimaryButton
import app.throwback.ui.common.UiText

/**
 * Full-screen poster after the reel allowance in Instagram's swipe viewer. A first, plain version;
 * the designed poster (and its animated version) replaces this. Back and the button both return to
 * the chat; touches never reach the page underneath.
 */
@Composable
internal fun ReelPosterContent(onBack: () -> Unit) {
    val colors = ThrowbackTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val big = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp)
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
        PrimaryButton(stringResource(R.string.ui_reel_poster_back), onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.ui_reel_poster_rule), style = UiText.body14, color = colors.muted)
    }
}
