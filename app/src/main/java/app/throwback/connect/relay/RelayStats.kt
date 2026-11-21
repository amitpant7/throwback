package app.throwback.connect.relay

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Daily counts of Instagram's notifications, counts only (never which one or what it said), kept for
 * 14 days:
 * - received from its "Instagram" group (everything except messages and calls), so the Connect ›
 *   Instagram screen can tell whether that group is on, as it must be (#34bb: posting is seen through
 *   it). Android doesn't let Throwback read another app's notification switches;
 * - removed by the relay, for "Your day" (alerts the user switched on and got are not removed).
 */
internal object RelayStats {
    private const val FILE = "relay_stats"
    private const val KEEP_DAYS = 14L
    private const val RECEIVED = "d" // the original key prefix: earlier counts stay readable
    private const val REMOVED = "r"

    fun recordOtherGroup(context: Context, today: LocalDate = LocalDate.now()) = record(context, RECEIVED, today)

    fun recordRemoved(context: Context, today: LocalDate = LocalDate.now()) = record(context, REMOVED, today)

    /** Notifications from the "Instagram" group over the last [days] days, today included. */
    fun otherGroupLastDays(context: Context, days: Int = 7, today: LocalDate = LocalDate.now()): Int =
        lastDays(context, RECEIVED, days, today)

    /** Instagram alerts the relay removed today. */
    fun removedToday(context: Context, today: LocalDate = LocalDate.now()): Int = lastDays(context, REMOVED, 1, today)

    private fun record(context: Context, prefix: String, today: LocalDate) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = prefix + today.toEpochDay()
        prefs.edit {
            putInt(key, prefs.getInt(key, 0) + 1)
            prefs.all.keys.filter { it != key }.forEach { k ->
                val day = k.drop(1).toLongOrNull()
                if (day == null || day < today.toEpochDay() - KEEP_DAYS) remove(k)
            }
        }
    }

    private fun lastDays(context: Context, prefix: String, days: Int, today: LocalDate): Int {
        val counts = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> k.drop(prefix.length).toLongOrNull()?.let { it to ((v as? Int) ?: 0) } }.toMap()
        return sum(counts, today, days)
    }

    /** Pure: the sum of [counts] (epoch day -> count) over the [days] days ending [today]. */
    fun sum(counts: Map<Long, Int>, today: LocalDate, days: Int): Int {
        val first = today.toEpochDay() - (days - 1)
        return counts.filterKeys { it in first..today.toEpochDay() }.values.sum()
    }
}
