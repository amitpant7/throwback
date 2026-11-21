package app.throwback.core

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.play.core.review.ReviewManagerFactory
import java.time.LocalDate

/**
 * Asking for a rating (owner, 2026-11-20): once, after a month, never again. Google Play's own in-app review card
 * (stays in the app, Play decides whether it actually shows and how often). Asked at a calm moment,
 * "You're all caught up", once the app is at least [MIN_DAYS] days old and was used on [MIN_USE_DAYS]
 * different days. Builds not installed from Play just do nothing.
 */
object RateAsk {
    private const val PREFS = "rate_ask"
    private const val MIN_DAYS = 30L
    private const val MIN_USE_DAYS = 10

    /** The first day the app was used (epoch day), or null before the first start. */
    fun firstUseDay(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("first", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    /** Pure rule, for tests. */
    internal fun due(firstDay: Long, today: Long, useDays: Int, asked: Boolean): Boolean =
        !asked && today - firstDay >= MIN_DAYS && useDays >= MIN_USE_DAYS

    /** Call whenever the app comes to the front: remembers the first day and counts the days it was used. */
    fun noteUse(context: Context, today: LocalDate = LocalDate.now()) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today.toEpochDay()
        p.edit {
            if (!p.contains("first")) putLong("first", day)
            if (p.getLong("last", -1) != day) {
                putLong("last", day)
                putInt("days", p.getInt("days", 0) + 1)
            }
        }
    }

    fun onGoodMoment(activity: Activity, today: LocalDate = LocalDate.now()) {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val first = p.getLong("first", today.toEpochDay())
        if (!due(first, today.toEpochDay(), p.getInt("days", 0), p.getBoolean("asked", false))) return
        p.edit { putBoolean("asked", true) }
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || activity.isFinishing) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener {
                Log.i("ThrowbackRate", "review flow finished")
            }
        }
    }
}
