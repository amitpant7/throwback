package app.throwback.timelimit.ui

/**
 * How countdowns are rounded for display. Pure, so it's unit-tested; the screens turn the parts
 * into text with string resources. Rounding is up, so a countdown never shows zero while time is left.
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

    /** What the home-feed chip says: whole minutes, or m:ss (warning style) in the last minute. */
    sealed interface Chip {
        data class Minutes(val minutes: Int) : Chip
        data class LastMinute(val time: MinSec, val seconds: Int) : Chip
    }

    fun chip(remainingMillis: Long): Chip =
        if (remainingMillis > MINUTE) Chip.Minutes(ceilDiv(remainingMillis, MINUTE).toInt())
        else Chip.LastMinute(minSec(remainingMillis), ceilDiv(remainingMillis, SECOND).toInt())
}
