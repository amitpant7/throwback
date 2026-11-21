package app.throwback.core

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * The support line in "You're all caught up" (owner, 2026-11-20): after a month of use, then every
 * 2 months; never again once the user has tipped. The page shows it with the ads and suggested posts
 * removed since the last time, and reports when it was shown.
 */
object SupportAsk {
    private const val PREFS = "support_ask"
    const val FIRST_AFTER_DAYS = 30L
    const val THEN_EVERY_DAYS = 60L

    /** "first", "again", or null (not due). Pure, for tests. */
    internal fun due(firstUseDay: Long?, today: Long, lastShownDay: Long?, tipped: Boolean): String? = when {
        tipped || firstUseDay == null -> null
        today - firstUseDay < FIRST_AFTER_DAYS -> null
        lastShownDay == null -> "first"
        today - lastShownDay >= THEN_EVERY_DAYS -> "again"
        else -> null
    }

    fun due(context: Context, today: LocalDate = LocalDate.now()): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("lastShown", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        return due(RateAsk.firstUseDay(context), today.toEpochDay(), last, Supporter.tipped(context).value)
    }

    fun markShown(context: Context, today: LocalDate = LocalDate.now()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("lastShown", today.toEpochDay()) }
    }
}
