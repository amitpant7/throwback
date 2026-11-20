package app.throwback.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Messages the page script (throwback.js) sends to the app, as `{"t": type, ...}` JSON strings. */
internal sealed interface BridgeMessage {

    /**
     * Opens another app (the Instagram app, the share sheet) or starts a guard pass. Taken only from
     * Instagram's own top-level documents: the legacy bridge, which every frame can call, drops these.
     */
    val opensOtherApp: Boolean
        get() = this is CallInApp || this is CreateInApp || this is SettingsInApp || this is StoryWeb || this is Share

    /** The page's location changed (load, pushState, replaceState, back/forward). */
    data class Location(val url: String) : BridgeMessage

    /** The first filtered page is rendered. */
    data object Ready : BridgeMessage

    /** The user tapped something Throwback blocks; [kind] picks the message to show. */
    data class Blocked(val kind: String) : BridgeMessage

    /** A touch started; [atTop] says the page and the touched element are scrolled to the top. */
    data class Touch(val atTop: Boolean) : BridgeMessage

    /** The user tapped Throwback's settings icon on their profile page. */
    data object OpenSettings : BridgeMessage

    /**
     * The chat's call button: open the chat in the Instagram app to call. [user] (the other person's
     * username, one-to-one chats) works whichever account the app uses; [thread] is the chat id.
     */
    data class CallInApp(val thread: String?, val user: String? = null) : BridgeMessage

    /** The user swiped past the reel allowance in Instagram's reel viewer; the app shows its poster. */
    data object ReelLimit : BridgeMessage

    /** The feed showed "You're all caught up" (a good moment to ask for a rating). */
    data object CaughtUp : BridgeMessage

    /** "+" or "Your story" tapped while the Instagram app is connected: open its create screen. */
    data class CreateInApp(val story: Boolean = false) : BridgeMessage

    /** The support line was shown at "You're all caught up" (schedule the next one). */
    data object SupportAskShown : BridgeMessage

    /** "Say thanks" on that line: open Support Throwback. */
    data object SupportThanks : BridgeMessage

    /** "Your story" while the app route waits: the website's upload runs; the app explains why. */
    data object StoryWeb : BridgeMessage

    /** "Open settings in the Instagram app" at the end of Instagram's settings page. */
    data object SettingsInApp : BridgeMessage

    /** Share a reel's link (Explore reel player) through Android's share sheet. */
    data class Share(val url: String) : BridgeMessage

    /** The page's background colour (ARGB), for the system bar areas around the WebView. */
    data class Background(val argb: Int) : BridgeMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val THREAD = Regex("^\\d{5,40}$")
        private val SHARE_URL = Regex("^https://www\\.instagram\\.com/reel/[A-Za-z0-9_-]{5,40}/$")
        private val USER = Regex("^[A-Za-z0-9._]{1,30}$")

        fun parse(text: String?): BridgeMessage? {
            if (text.isNullOrEmpty() || text.length > 8_192) return null
            val obj: JsonObject = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) { // SerializationException is a subclass
                return null
            }
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return when (str("t")) {
                "loc" -> str("url")?.let(::Location)
                "ready" -> Ready
                "blocked" -> Blocked(str("kind") ?: "hidden")
                "touch" -> (obj["top"] as? JsonPrimitive)?.booleanOrNull?.let(::Touch)
                "bg" -> str("c")?.let(::parseCssColor)?.let(::Background)
                "settings" -> OpenSettings
                "callInApp" -> CallInApp(str("thread")?.takeIf { THREAD.matches(it) }, str("user")?.takeIf { USER.matches(it) })
                "reelLimit" -> ReelLimit
                "caughtUp" -> CaughtUp
                "createInApp" -> CreateInApp(story = str("kind") == "story")
                "settingsInApp" -> SettingsInApp
                "storyWeb" -> StoryWeb
                "supportAskShown" -> SupportAskShown
                "supportThanks" -> SupportThanks
                "share" -> str("url")?.takeIf { SHARE_URL.matches(it) }?.let(::Share)
                else -> null
            }
        }

        private val RGB = Regex("""^rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})(?:\s*[,/]\s*([\d.]+%?))?\s*\)$""")

        /** Parses the computed-style colours browsers return ("rgb(r, g, b)" / "rgba(r, g, b, a)"). Opaque only. */
        fun parseCssColor(css: String): Int? {
            val m = RGB.matchEntire(css.trim().lowercase()) ?: return null
            val (r, g, b) = m.destructured.let { listOf(it.component1(), it.component2(), it.component3()) }
                .map { it.toInt().coerceIn(0, 255) }
            val alpha = m.groupValues[4].takeIf { it.isNotEmpty() }?.let {
                if (it.endsWith('%')) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull()
            } ?: 1f
            // Translucent backgrounds would show whatever is behind the WebView; ignore them.
            if (alpha < 0.99f) return null
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
