package app.throwback.guard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.TimeLimitModule
import app.throwback.timelimit.engine.TrustedTime

/**
 * What the passes into the Instagram app remember, and the rules on it (DECISIONS.md #27a, #27f).
 * Days are epoch days and times wall ms of the time limit's trusted clock, so moving the phone's
 * clock or time zone changes nothing. That clock's day never goes back, so a stored day is never
 * later than today; if one were (lost time-limit data), it counts as today: no extra passes.
 * Pure, for tests; [PassLedger] keeps it.
 */
internal data class PassBook(
    /** The day "Open Instagram for 5 minutes" was last used. */
    val visitDay: Long? = null,
    /** [storyCount] story passes were used on [storyDay]. */
    val storyDay: Long? = null,
    val storyCount: Int = 0,
    /** No story pass before this. */
    val storyWaitUntil: Long? = null,
    /** When the chat's call button opened Instagram (the last hour is enough). */
    val callTaps: List<Long> = emptyList(),
    /** "Your day": [appMillis] of the Instagram app in front during passes on [appDay]. */
    val appDay: Long? = null,
    val appMillis: Long = 0,
) {
    // ---- "Open Instagram for 5 minutes": once a day, not while the daily limit is reached.

    fun visitAllowed(today: Long, locked: Boolean): Boolean = !locked && (visitDay == null || today > visitDay)

    fun visited(today: Long) = copy(visitDay = today)

    // ---- "Your story": [perDay] a day, with a wait after each.

    fun storiesOn(today: Long): Int = if (storyDay != null && storyDay >= today) storyCount else 0

    fun storyAllowed(today: Long, now: Long, perDay: Int): Boolean = storiesOn(today) < perDay && !storyWaiting(now)

    /** Minutes until the next story pass (at least 1 while waiting), 0 if one is available, null if none is left today. */
    fun storyWaitMinutes(today: Long, now: Long, perDay: Int): Int? {
        if (storiesOn(today) >= perDay) return null
        val until = storyWaitUntil ?: return 0
        return if (now >= until) 0 else ((until - now + 59_999) / 60_000).toInt()
    }

    fun storyStarted(today: Long, now: Long, waitMillis: Long) =
        copy(storyDay = today, storyCount = storiesOn(today) + 1, storyWaitUntil = now + waitMillis)

    /** The pass ended with a post (owner: a real upload means no cheating): it doesn't count, no wait. */
    fun storyPosted(today: Long) = copy(storyCount = (storiesOn(today) - 1).coerceAtLeast(0), storyWaitUntil = null)

    /** Left early having shown little but the camera: it doesn't count, but a short wait follows. */
    fun storyLeftEarly(today: Long, now: Long) =
        copy(storyCount = (storiesOn(today) - 1).coerceAtLeast(0), storyWaitUntil = now + PassRules.FREE_LEAVE_WAIT_MS)

    private fun storyWaiting(now: Long) = storyWaitUntil != null && now < storyWaitUntil

    // ---- The chat's call button: [CALL_TAPS_PER_HOUR] an hour.

    fun callAllowed(now: Long): Boolean = recentCallTaps(now).size < CALL_TAPS_PER_HOUR

    fun callTapped(now: Long) = copy(callTaps = recentCallTaps(now) + now)

    /** Taps of the last hour; one "in the future" (only a wrong clock does that) counts as recent. */
    private fun recentCallTaps(now: Long) = callTaps.filter { now - it < HOUR_MS }

    // ---- "Your day".

    fun appMillisOn(today: Long): Long = if (appDay == today) appMillis else 0L

    fun withAppTime(today: Long, millis: Long) = copy(appDay = today, appMillis = appMillisOn(today) + millis)

    companion object {
        const val CALL_TAPS_PER_HOUR = 3
        private const val HOUR_MS = 3_600_000L
    }
}

/** Keeps the [PassBook] (SharedPreferences `guard_passes`); "now" comes from the time limit's trusted clock. */
internal object PassLedger {
    private const val FILE = "guard_passes"

    /** Today and now on the trusted clock; null until the time limit has loaded its state (then: no new passes). */
    fun now(context: Context): TrustedTime? =
        (TimeLimitModule.create(context) as? DefaultTimeLimitController)?.trustedTime()

    @Synchronized
    fun read(context: Context): PassBook {
        val p = prefs(context)
        return PassBook(
            visitDay = p.longOrNull(VISIT_DAY),
            storyDay = p.longOrNull(STORY_DAY),
            storyCount = p.getInt(STORY_COUNT, 0),
            storyWaitUntil = p.longOrNull(STORY_WAIT_UNTIL),
            callTaps = p.getString(CALL_TAPS, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() },
            appDay = p.longOrNull(APP_DAY),
            appMillis = p.getLong(APP_MILLIS, 0L),
        )
    }

    /** Applies [change] to the stored book (one at a time: the guard and the relay both write). */
    @Synchronized
    fun update(context: Context, change: (PassBook) -> PassBook) {
        val b = change(read(context))
        prefs(context).edit {
            putOrRemove(VISIT_DAY, b.visitDay)
            putOrRemove(STORY_DAY, b.storyDay)
            putInt(STORY_COUNT, b.storyCount)
            putOrRemove(STORY_WAIT_UNTIL, b.storyWaitUntil)
            putString(CALL_TAPS, b.callTaps.joinToString(","))
            putOrRemove(APP_DAY, b.appDay)
            putLong(APP_MILLIS, b.appMillis)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun SharedPreferences.longOrNull(key: String): Long? = if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private const val VISIT_DAY = "visitDay"
    private const val STORY_DAY = "storyDay"
    private const val STORY_COUNT = "storyCount"
    private const val STORY_WAIT_UNTIL = "storyWaitUntil"
    private const val CALL_TAPS = "callTaps"
    private const val APP_DAY = "appDay"
    private const val APP_MILLIS = "appMillis"
}
