package app.throwback.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Messages the page script (throwback.js) sends to the app, as `{"t": type, ...}` JSON strings. */
internal sealed interface BridgeMessage {
    /** The page's location changed (load, pushState, replaceState, back/forward). */
    data class Location(val url: String) : BridgeMessage

    /** The first filtered page is rendered. */
    data object Ready : BridgeMessage

    /** The user tapped something Throwback blocks; [kind] picks the message to show. */
    data class Blocked(val kind: String) : BridgeMessage

    /** A touch started; [atTop] says the page and the touched element are scrolled to the top. */
    data class Touch(val atTop: Boolean) : BridgeMessage

    /** The page's background colour (ARGB), for the system bar areas around the WebView. */
    data class Background(val argb: Int) : BridgeMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

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
