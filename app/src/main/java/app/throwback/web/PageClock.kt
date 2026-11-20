package app.throwback.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Trusted time for the page's daily counters (Explore minutes, Discover, the chat-reel cooldown),
 * from the time limit's clock, which changing the phone's clock or time zone doesn't fool. The page
 * only gets one while that clock is known (its saved state is loaded): never the phone's own.
 */
internal object PageClock {

    /** One reading of the trusted clock. */
    data class Reading(
        /** Trusted time minus the phone's clock (ms), at [readAt]. */
        val offsetMs: Long,
        /** UTC offset of the zone days are counted in (minutes). */
        val zoneOffsetMin: Int,
        /** Today (epoch day). */
        val day: Long,
        /** When the next day may start (trusted ms). */
        val nextDayAt: Long,
        /** The phone's clock when this was read (ms): the page only trusts a fresh reading. */
        val readAt: Long,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("offset", offsetMs)
            put("zoneMin", zoneOffsetMin)
            put("day", day)
            put("nextAt", nextDayAt)
            put("at", readAt)
        }
    }

    /** Reads the trusted clock, or returns null while it is unknown. Set by the app. */
    @Volatile
    var provider: (() -> Reading?)? = null

    /** The clock for the page's config, read now, or null: then the config has no clock at all. */
    fun json(): JsonObject? = provider?.invoke()?.json()
}
