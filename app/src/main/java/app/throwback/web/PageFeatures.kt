package app.throwback.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** App features the page needs to know about. */
internal data class PageFeatures(
    /** The Instagram app is connected: "+" and "Your story" open its create screen. */
    val createInApp: Boolean = false,
    /** "Your story" may open the Instagram app now (else the website's own story upload). */
    val storyInApp: Boolean = false,
    /** The support line is due at "You're all caught up": "first", "again", or null. */
    val supportAsk: String? = null,
) {
    /** The config fields the page reads, under these exact names. */
    fun configFields(): Map<String, JsonElement> = linkedMapOf(
        "createInApp" to JsonPrimitive(createInApp),
        "storyInApp" to JsonPrimitive(storyInApp),
        "supportAsk" to JsonPrimitive(supportAsk),
    )

    companion object {
        /**
         * The features now. Change them with `current.update { it.copy(...) }`; the Instagram screen
         * passes each change to the page while it is in front.
         */
        val current = MutableStateFlow(PageFeatures())

        /** For relay.CallInApp (another area), which sets this one field directly. */
        var storyInApp: Boolean
            get() = current.value.storyInApp
            set(value) = current.update { it.copy(storyInApp = value) }
    }
}
