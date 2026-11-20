package app.throwback.web.rules

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The filter rules schema. filters/rules.json is the single source of truth; it is bundled into the
 * APK and refreshed from the repository at most once a day (RulesRepository).
 *
 * Only fields the app uses are modelled. Unknown fields ("note", "about", or fields added for newer
 * app versions) are ignored, so documentation can live next to each rule and new fields can be added
 * without breaking installed apps. A breaking change bumps [FilterRules.schema]; apps that don't know
 * the new schema keep their current rules.
 *
 * Every regex here runs both in Kotlin (UrlRouter) and in JavaScript (throwback.js), so rules must stay
 * in the common subset of both flavours. Every selector runs in the page via querySelectorAll.
 */

/** The schema version this app understands. */
const val SUPPORTED_RULES_SCHEMA = 1

@Serializable
data class FilterRules(
    val schema: Int,
    /** Increases on every edit (yyyymmddNN). The app keeps whichever valid copy has the highest version. */
    val version: Long,
    val updated: String = "",
    /** Where "home" goes: the Following feed. */
    val homeUrl: String,
    /** `variant` query values that count as the home feed (anything else on "/" redirects to [homeUrl]). */
    val homeVariants: List<String> = emptyList(),
    /** Where messages-only mode lands. */
    val messagesUrl: String,
    val hosts: Hosts,
    val paths: Paths,
    /** Page name to path regex. The page script sets `tb-on-<name>` on <html> while it matches. */
    val pages: Map<String, String> = emptyMap(),
    /** The page counts as rendered (WebReadiness) once one of these exists. */
    val readySelector: String = "main, nav, article, form",
    val hide: List<HideRule> = emptyList(),
    val hideText: List<HideTextRule> = emptyList(),
    val posts: PostRules = PostRules(),
    val modules: List<TextRule> = emptyList(),
    val sectionMarkers: List<TextRule> = emptyList(),
    val dialogs: List<DialogRule> = emptyList(),
    val explore: ExploreRules? = null,
    val nav: NavRules? = null,
    val dismissMaxAttempts: Int = 40,
)

@Serializable
data class Hosts(
    /** Instagram itself: kept in the WebView, filtered. Exact hosts or `*.example.com`. */
    val instagram: List<String>,
    /** Account pages (Accounts Center) kept in the WebView without filters. */
    val internal: List<String> = emptyList(),
    /** Facebook/Meta login: kept in the WebView only during a login flow, otherwise opened outside. */
    val login: List<String> = emptyList(),
    /** Outbound link redirectors; the real target in [LinkShim.param] is opened outside directly. */
    val linkShims: List<LinkShim> = emptyList(),
    /** Full-URL regexes that are ignored outright ("open in app" intents, app store pages). */
    val blockedUrls: List<String> = emptyList(),
)

@Serializable
data class LinkShim(val host: String, val param: String)

@Serializable
data class Paths(
    /** First match wins. */
    val blocked: List<BlockedPath> = emptyList(),
    /** Login, recovery and call pages: allowed in every mode, even messages-only. */
    val alwaysAllowed: List<String> = emptyList(),
    /** Instagram pages that are part of logging in (Facebook login may follow from them). */
    val auth: List<String> = emptyList(),
    /** The only pages allowed in messages-only mode (besides [alwaysAllowed]). */
    val messagesOnly: List<String>,
    /** Pages where pull-to-refresh is offered. */
    val pullToRefresh: List<String> = emptyList(),
)

@Serializable
data class BlockedPath(
    val pattern: String,
    /** Optional path template ($1..$9 are the pattern's groups). Without it the page goes home. */
    val redirect: String? = null,
    /** Why it is blocked; picks the message shown when the user taps a blocked link. */
    val kind: String = "hidden",
)

@Serializable
data class HideRule(
    /** Becomes a token in the `data-tb` attribute of matched elements; lowercase letters, digits, '-'. */
    val id: String,
    val selector: String,
    /** Hide the nearest ancestor matching this (within [closestDepth] levels) instead of the match. */
    val closest: String? = null,
    val closestDepth: Int = 6,
    /** "always", "full", "messagesOnly", or a page name from [FilterRules.pages]. */
    val on: String = ON_ALWAYS,
    /** "remove" (display: none) or "invisible" (keeps its space, for top-bar items). */
    val style: String = STYLE_REMOVE,
)

@Serializable
data class HideTextRule(
    val id: String,
    /** Candidate elements; one is hidden when its whole text equals one of [text] (case-insensitive). */
    val elements: String,
    val text: List<String>,
    val on: String = ON_ALWAYS,
)

@Serializable
data class PostRules(
    val container: String = "article",
    /** A label only counts if at most this many images/videos come before it in the post (header, not caption). */
    val maxMediaBefore: Int = 2,
    val labels: List<TextRule> = emptyList(),
)

@Serializable
data class TextRule(val id: String, val text: List<String>, val on: String = ON_ALWAYS)

@Serializable
data class DialogRule(
    val id: String,
    /** The dialog matches when its text contains one of these (case-insensitive). */
    val whenText: List<String>,
    /** Buttons to press, by their exact text. */
    val click: List<String> = emptyList(),
    /** Fallback button when no text matches (e.g. a close icon). */
    val clickSelector: String? = null,
)

@Serializable
data class ExploreRules(val on: String = "explore", val keep: String, val items: String)

@Serializable
data class NavRules(val links: String, val minLinks: Int = 3)

const val ON_ALWAYS = "always"
const val ON_FULL = "full"
const val ON_MESSAGES_ONLY = "messagesOnly"
const val STYLE_REMOVE = "remove"
const val STYLE_INVISIBLE = "invisible"

/** JSON settings shared by parsing and by the copy handed to the page script. */
internal val RulesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

sealed interface RulesParseResult {
    data class Valid(val rules: FilterRules) : RulesParseResult
    data class Invalid(val errors: List<String>) : RulesParseResult
}

object RulesParser {
    /** Parses and validates; a file with any error is rejected as a whole. */
    fun parse(text: String?): RulesParseResult {
        if (text.isNullOrBlank()) return RulesParseResult.Invalid(listOf("empty"))
        val rules = try {
            RulesJson.decodeFromString(FilterRules.serializer(), text)
        } catch (e: SerializationException) {
            return RulesParseResult.Invalid(listOf("not valid rules JSON: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return RulesParseResult.Invalid(listOf("not valid rules JSON: ${e.message}"))
        }
        val errors = RulesValidator.validate(rules)
        return if (errors.isEmpty()) RulesParseResult.Valid(rules) else RulesParseResult.Invalid(errors)
    }

    fun parseOrNull(text: String?): FilterRules? = (parse(text) as? RulesParseResult.Valid)?.rules
}

object RulesSelection {
    /**
     * Picks the rules to use: the cached (downloaded) copy only if it is newer than the bundled one,
     * so an app update with fresher bundled rules wins over an old download.
     * Both arguments must already be validated.
     */
    fun choose(bundled: FilterRules?, cached: FilterRules?): FilterRules? = when {
        cached == null -> bundled
        bundled == null -> cached
        cached.version > bundled.version -> cached
        else -> bundled
    }
}
