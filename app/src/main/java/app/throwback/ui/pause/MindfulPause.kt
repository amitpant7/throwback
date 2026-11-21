package app.throwback.ui.pause

import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.TbProgressBar
import app.throwback.core.ui.UiText
import app.throwback.core.ui.blockTouches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Optional mindful pause before Instagram loads: a short line and a 4-second countdown, no buttons.
 * [onContinue] when the time is up; [onBack] is system Back (it leaves the app). [backEnabled] is
 * false while another layer is on top (AppRoot's Layer), so Back reaches that one.
 */
@Composable
internal fun MindfulPauseScreen(onContinue: () -> Unit, onBack: () -> Unit, backEnabled: Boolean = true) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val continueNow by rememberUpdatedState(onContinue)

    // The next of the shuffled lines and quotes kept on the device, or now and then a tip. Read and
    // saved off the main thread (the feed is loading underneath), so it shows a frame or two after
    // the pause, together with its mark.
    var pick by rememberSaveable { mutableStateOf<Int?>(null) } // see PauseLines.next
    LaunchedEffect(Unit) {
        if (pick == null) {
            val app = context.applicationContext
            val res = app.resources
            pick = withContext(Dispatchers.IO) {
                PauseLines.next(
                    app,
                    res.getStringArray(R.array.ui_pause_lines).size + res.getStringArray(R.array.ui_pause_quotes).size,
                    res.getStringArray(R.array.ui_pause_tips).size,
                )
            }
        }
    }
    val resources = context.resources
    val shown = remember(pick) { pick?.let { PauseLines.text(resources, it) } }

    // Only the progress bar reads the time every frame (while drawing); the countdown text changes once
    // a second, so the screen recomposes five times rather than every frame.
    val elapsed = remember { mutableLongStateOf(0L) }
    val secondsLeft by remember { derivedStateOf { PauseTiming.secondsLeft(elapsed.longValue) } }
    val done by remember { derivedStateOf { PauseTiming.isDone(elapsed.longValue) } }

    // Frame-driven rather than an Animatable, so it still takes 4 seconds when the phone has
    // animations turned off, and it pauses while the app is in the background.
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (!PauseTiming.isDone(elapsed.longValue)) {
            elapsed.longValue = withFrameMillis { it } - start
        }
        continueNow()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .blockTouches() // nothing reaches the page loading underneath
            .systemBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 28.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            when {
                shown == null -> Unit
                shown.tip -> {
                    TbIcon(R.drawable.ic_info, size = 40.dp, tint = colors.accent)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ui_pause_tip), style = UiText.sub, color = colors.accentText)
                        Text(shown.text, style = UiText.pauseLine, color = colors.text)
                    }
                }
                else -> {
                    TbIcon(R.drawable.ic_quote, size = 40.dp, tint = colors.accent)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(shown.text, style = UiText.pauseLine, color = colors.text)
                        shown.by?.let { Text(stringResource(R.string.ui_pause_quote_by, it), style = UiText.sub, color = colors.muted) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TbProgressBar({ PauseTiming.progress(elapsed.longValue) }, Modifier.weight(1f), height = 4.dp)
            Text(
                if (done) stringResource(R.string.ui_pause_almost) else stringResource(R.string.ui_pause_opening_in, secondsLeft),
                style = UiText.sub,
                color = colors.muted,
            )
        }
    }
    // No buttons (owner): the pause just runs; the phone's Back still leaves.
    BackHandler(enabled = backEnabled, onBack = onBack)
}

/**
 * What the pause shows: Throwback's own lines and sourced quotes (`ui_pause_lines` then
 * `ui_pause_quotes`, each "text|attribution"), in a shuffled order that goes through all of them
 * before any repeats (owner: "people will get bored"). Every [TIP_EVERY]th pause shows a tip
 * instead, at most [TIPS_MAX] times in all (owner, 2026-11-21: "long-press Home" for Throwback's
 * settings, rarely). Order and counts are kept on the device.
 */
internal object PauseLines {
    private const val PREFS = "pause_lines"
    const val TIP_EVERY = 50
    const val TIPS_MAX = 10

    /** Whether pause number [shown] (0 for the first, counted modulo [TIP_EVERY]) is a tip's turn. Pure, for tests. */
    fun tipTurn(shown: Int, tipsShown: Int): Boolean = tipsShown < TIPS_MAX && shown % TIP_EVERY == TIP_EVERY - 1

    /** A pause's text: a tip, or a line or quote ([by]: the quote's attribution). */
    class Shown(val text: String, val by: String?, val tip: Boolean)

    /** What the next pause shows: an index into the lines and quotes together, or -1 - i for tip i. */
    fun next(context: Context, count: Int, tipCount: Int): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getInt("shown", 0)
        val tipsShown = prefs.getInt("tipsShown", 0)
        prefs.edit {
            putInt("shown", (shown + 1) % TIP_EVERY)
            remove("tip") // the tip rotation before tips were capped
        }
        if (tipCount > 0 && tipTurn(shown, tipsShown)) {
            prefs.edit { putInt("tipsShown", tipsShown + 1) }
            return -1 - tipsShown % tipCount
        }
        return nextLine(context, count)
    }

    /** The text for [pick] (from [next]). */
    fun text(res: Resources, pick: Int): Shown {
        if (pick < 0) return res.getStringArray(R.array.ui_pause_tips).let { Shown(it[(-1 - pick).coerceIn(it.indices)], null, tip = true) }
        val lines = res.getStringArray(R.array.ui_pause_lines)
        if (pick < lines.size) return Shown(lines[pick], null, tip = false)
        val quotes = res.getStringArray(R.array.ui_pause_quotes)
        if (quotes.isEmpty()) return Shown(lines[pick.coerceIn(lines.indices)], null, tip = false)
        val (text, by) = splitQuote(quotes[(pick - lines.size).coerceIn(quotes.indices)])
        return Shown(text, by, tip = false)
    }

    /** "text|attribution" → text and attribution (null without a "|"). Pure, for tests. */
    fun splitQuote(item: String): Pair<String, String?> {
        val bar = item.lastIndexOf('|')
        return if (bar < 0) item.trim() to null else item.substring(0, bar).trim() to item.substring(bar + 1).trim().ifEmpty { null }
    }

    private fun nextLine(context: Context, count: Int): Int {
        if (count <= 1) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var order = prefs.getString("order", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == count && it.toSet().size == count }
        var pos = prefs.getInt("pos", 0)
        if (order == null || pos >= count) {
            val last = order?.lastOrNull()
            order = (0 until count).shuffled().let { if (it.first() == last) it.drop(1) + it.first() else it }
            pos = 0
        }
        prefs.edit {
            putString("order", order.joinToString(","))
            putInt("pos", pos + 1)
        }
        return order[pos]
    }
}
