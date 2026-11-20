package app.throwback.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Today's counts kept by the page script (`__throwback.dayStats()`), all on the device. */
@Serializable
data class PageDayStats(
    /** Seconds per area: feed, stories, messages, explore, other. */
    val areas: Map<String, Long> = emptyMap(),
    val adsRemoved: Int = 0,
    val suggestionsRemoved: Int = 0,
    val exploreUsedSec: Int = 0,
    val exploreAllowanceSec: Int = 0,
    val discoverShown: Int = 0,
    val discoverMax: Int = 0,
    /** Time left before reels in chats get free swipes again (0 = none now). */
    val reelCooldownMs: Long = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String?): PageDayStats? = try {
            text?.let { json.decodeFromString(serializer(), it) }
        } catch (e: IllegalArgumentException) { // SerializationException is a subclass
            null
        }
    }
}
