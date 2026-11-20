package app.throwback.ui.pause

import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.common.LineIcon
import app.throwback.ui.common.UiText
import kotlin.math.ceil

/** Timing of the pause, kept pure for tests. */
internal object PauseTiming {
    const val DURATION_MILLIS = 4_000L

    fun progress(elapsedMillis: Long): Float =
        (elapsedMillis.toFloat() / DURATION_MILLIS).coerceIn(0f, 1f)

    /** Whole seconds left for "Opening in N": 4, 3, 2, 1 (never 0 while on screen). */
    fun secondsLeft(elapsedMillis: Long): Int {
        val remaining = (DURATION_MILLIS - elapsedMillis).coerceAtLeast(0)
        return ceil(remaining / 1000.0).toInt().coerceAtLeast(1)
    }

    fun isDone(elapsedMillis: Long): Boolean = elapsedMillis >= DURATION_MILLIS
}

/** See UiApi.MindfulPauseScreen. */
@Composable
internal fun MindfulPauseContent(onContinue: () -> Unit, onNotNow: () -> Unit) {
    val colors = ThrowbackTheme.colors
    val lines = stringArrayResource(R.array.ui_pause_lines)
    val context = LocalContext.current
    val lineIndex = rememberSaveable { PauseLines.next(context, lines.size) }
    val continueNow by rememberUpdatedState(onContinue)
    var elapsed by remember { mutableLongStateOf(0L) }

    // Frame-driven rather than an Animatable, so it still takes 4 seconds when the phone has
    // animations turned off, and it pauses while the app is in the background.
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (!PauseTiming.isDone(elapsed)) {
            elapsed = withFrameMillis { it } - start
        }
        continueNow()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 28.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            LineIcon(R.drawable.ui_ic_quote, size = 40.dp, tint = colors.accent)
            Text(lines[lineIndex.coerceIn(lines.indices)], style = UiText.pauseLine, color = colors.text)
        }

        val progress = PauseTiming.progress(elapsed)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.surface2)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
            }
            Text(
                stringResource(R.string.ui_pause_opening_in, PauseTiming.secondsLeft(elapsed)),
                style = UiText.sub,
                color = colors.muted,
            )
        }
    }
    // No buttons (owner): the pause just runs; the phone's Back still leaves.
    BackHandler { onNotNow() }
}

/**
 * The pause lines in a shuffled order that goes through all of them before any repeats (owner:
 * "people will get bored"). The order and position are kept on the device.
 */
internal object PauseLines {
    private const val PREFS = "pause_lines"

    fun next(context: Context, count: Int): Int {
        if (count <= 1) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var order = prefs.getString("order", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == count && it.toSet().size == count }
        var pos = prefs.getInt("pos", 0)
        if (order == null || pos >= count) {
            val last = order?.lastOrNull()
            order = (0 until count).shuffled().let { if (it.first() == last) it.drop(1) + it.first() else it }
            pos = 0
        }
        prefs.edit().putString("order", order.joinToString(",")).putInt("pos", pos + 1).apply()
        return order[pos]
    }
}
