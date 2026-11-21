package app.throwback.relay

import android.content.Context
import java.time.LocalDate

/**
 * How many notifications from Instagram's "Instagram" group (everything except messages and calls)
 * reached the relay per day, so the Connect › Instagram screen can suggest turning that group off at
 * the source. Android doesn't let Throwback read another app's notification switches, but this shows
 * they're on. Counts only: never which notification or what it said. Kept for 14 days.
 */
internal object RelayStats {
    private const val FILE = "relay_stats"
    private const val KEEP_DAYS = 14L

    fun recordOtherGroup(context: Context, today: LocalDate = LocalDate.now()) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = key(today)
        val edit = prefs.edit().putInt(key, prefs.getInt(key, 0) + 1)
        prefs.all.keys.filter { it.startsWith("d") && it != key }.forEach { k ->
            val day = k.drop(1).toLongOrNull()
            if (day == null || day < today.toEpochDay() - KEEP_DAYS) edit.remove(k)
        }
        edit.apply()
    }

    /** Notifications from the "Instagram" group over the last [days] days, today included. */
    fun otherGroupLastDays(context: Context, days: Int = 7, today: LocalDate = LocalDate.now()): Int {
        val counts = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> k.drop(1).toLongOrNull()?.let { it to ((v as? Int) ?: 0) } }.toMap()
        return sum(counts, today, days)
    }

    /** Pure: the sum of [counts] (epoch day -> count) over the [days] days ending [today]. */
    fun sum(counts: Map<Long, Int>, today: LocalDate, days: Int): Int {
        val first = today.toEpochDay() - (days - 1)
        return counts.filterKeys { it in first..today.toEpochDay() }.values.sum()
    }

    private fun key(day: LocalDate) = "d${day.toEpochDay()}"
}
