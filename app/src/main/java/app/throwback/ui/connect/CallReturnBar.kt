package app.throwback.ui.connect

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.connect.CallReturn
import app.throwback.connect.CallState
import app.throwback.connect.PassBook
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.UiText
import kotlinx.coroutines.delay

/**
 * "Back to your Instagram call", over everything while an Instagram call runs (DECISIONS.md #34x):
 * the guard keeps the rest of Instagram covered during a call, so a call minimised to its chat
 * lands the user here; this opens the call screen again. Shown only when the tap can work.
 */
@Composable
internal fun CallReturnBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val calls by CallState.tracker.state.collectAsStateWithLifecycle()
    val appCall by CallState.appCall.collectAsStateWithLifecycle()
    // Call audio isn't observable: re-checked every second while a call notification shows.
    val show by produceState(false, calls, appCall) {
        while (true) {
            value = CallReturn.available(context, calls)
            if (!calls.inCall) break
            delay(1_000)
        }
    }
    if (!show) return
    val colors = ThrowbackTheme.colors
    Surface(
        onClick = {
            // Same messages as the call button (its hourly limit, no Instagram app).
            CallReturn.open(context).message?.let {
                Toast.makeText(context, context.getString(it, PassBook.CALL_TAPS_PER_HOUR), Toast.LENGTH_LONG).show()
            }
        },
        shape = RoundedCornerShape(999.dp),
        color = colors.accent,
        contentColor = colors.onAccent,
        shadowElevation = 4.dp,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TbIcon(R.drawable.ic_phone, size = 18.dp, tint = colors.onAccent)
            Text(stringResource(R.string.ui_call_return), style = UiText.row15.copy(fontWeight = FontWeight.Bold))
        }
    }
}
