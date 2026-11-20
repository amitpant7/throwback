package app.throwback.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.throwback.R

/**
 * How durations are rounded for display. Pure, so it's unit-tested; the text helpers below turn the
 * parts into text with string resources. Time left rounds up, so a countdown never shows zero (or less
 * than the home-feed chip) while time is left; time spent rounds down, so it never claims more than
 * was used.
 */
internal object Durations {

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b

    /** "3:28": minutes and seconds. */
    data class MinSec(val minutes: Int, val seconds: Int)

    fun minSec(millis: Long): MinSec {
        val s = ceilDiv(millis, SECOND)
        return MinSec((s / 60).toInt(), (s % 60).toInt())
    }

    /** "6h 42m", or "42m" under an hour. */
    data class HoursMinutes(val hours: Int, val minutes: Int)

    fun hoursMinutes(millis: Long): HoursMinutes {
        val m = ceilDiv(millis, MINUTE)
        return HoursMinutes((m / 60).toInt(), (m % 60).toInt())
    }

    /** "2d 23h" for a day or more (whole hours, rounded down so it ticks like the mockup), else null. */
    data class DaysHours(val days: Int, val hours: Int)

    fun daysHours(millis: Long): DaysHours? {
        if (millis < DAY) return null
        val h = millis / HOUR
        return DaysHours((h / 24).toInt(), (h % 24).toInt())
    }

    /** Time left as the home-feed chip shows it: whole minutes, or m:ss (warning style) in the last minute. */
    sealed interface Chip {
        data class Minutes(val minutes: Int) : Chip
        data class LastMinute(val seconds: Int) : Chip
    }

    fun chip(remainingMillis: Long): Chip =
        if (remainingMillis > MINUTE) Chip.Minutes(ceilDiv(remainingMillis, MINUTE).toInt())
        else Chip.LastMinute(ceilDiv(remainingMillis, SECOND).toInt())

    /** Time spent in hours and minutes, rounded down; null for a moment under a minute. */
    fun spent(millis: Long): HoursMinutes? {
        if (millis in 1 until MINUTE) return null
        val m = millis.coerceAtLeast(0) / MINUTE
        return HoursMinutes((m / 60).toInt(), (m % 60).toInt())
    }
}

// ---- As text ------------------------------------------------------------------------------------

/** "3:28", rounded up to the second. */
@Composable
internal fun minSecText(millis: Long): String {
    val t = Durations.minSec(millis)
    return stringResource(R.string.duration_min_sec, t.minutes, t.seconds)
}

/** Compact, for countdowns in large type: "6h 42m", or "42m" under an hour; rounded up to the minute. */
@Composable
internal fun hoursMinutesText(millis: Long): String {
    val t = Durations.hoursMinutes(millis)
    return if (t.hours > 0) stringResource(R.string.duration_hours_minutes_short, t.hours, t.minutes)
    else stringResource(R.string.duration_minutes_short, t.minutes)
}

/** Compact: "2d 23h" for a day or more, else as [hoursMinutesText]. */
@Composable
internal fun longDurationText(millis: Long): String =
    Durations.daysHours(millis)?.let { stringResource(R.string.duration_days_hours_short, it.days, it.hours) }
        ?: hoursMinutesText(millis)

/** Time left, as on the home-feed chip: "18 min" (minutes rounded up), "0:42" in the last minute. */
@Composable
internal fun remainingText(millis: Long): String = when (val chip = Durations.chip(millis)) {
    is Durations.Chip.Minutes -> stringResource(R.string.duration_minutes, chip.minutes)
    is Durations.Chip.LastMinute -> minSecText(millis)
}

/** Time spent: "45 min", "1 h 5 min" (rounded down); "Under a minute"; "0 min" for none. */
@Composable
internal fun elapsedText(millis: Long): String {
    val t = Durations.spent(millis) ?: return stringResource(R.string.duration_under_a_minute)
    return if (t.hours > 0) stringResource(R.string.duration_hours_minutes, t.hours, t.minutes)
    else stringResource(R.string.duration_minutes, t.minutes)
}
