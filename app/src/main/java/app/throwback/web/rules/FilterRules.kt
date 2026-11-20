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
    /** Where "home" goes: Instagram's regular home feed (with stories); Throwback filters it. */
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
    /** Posting through the Instagram app (when it's connected): link, its creation screens, the web buttons. */
    val appCreate: AppCreateRules? = null,
    val nav: NavRules? = null,
    val dismissMaxAttempts: Int = 40,
    /** Where Throwback adds its own section to Instagram's settings page (null: no section). */
    val settingsSection: SettingsSectionRules? = null,
    /** Filtering of Instagram's feed data before the page draws it (null: off, page filtering only). */
    val feedData: FeedDataRules? = null,
    /** Limit on swiping through reels in Instagram's full-screen viewer (null: no limit). */
    val reelViewer: ReelViewerRules? = null,
    /** Stories: ads removed from Instagram's data, and a small look-ahead for the next videos (null: off). */
    val stories: StoryRules? = null,
    /** Messages & calls relay: how the Instagram app's notifications are sorted (NotificationRules.kt; null: built-in defaults). */
    val notifications: NotificationRules? = null,
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
    /**
     * A label only counts in the post's header, never in its caption: at most this many
     * images/videos (the avatar) may come before it...
     */
    val maxMediaBefore: Int = 1,
    /** ...and it must sit in this top fraction of the post's height (checked when the post has a layout). */
    val headerMaxFraction: Double = 0.3,
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
data class ExploreRules(
    val on: String = "explore",
    val keep: String,
    val items: String,
    /** Seconds a day the Explore grid may show (time on the Explore tab); then "Enough for today". 0: never. */
    val dailySeconds: Int = 0,
    /**
     * Labels (aria-label, any language) of Instagram's own buttons on a post page, which the Explore
     * reel player presses for its like and save buttons.
     */
    val actions: ExploreActions? = null,
)

@Serializable
data class ExploreActions(
    val like: List<String> = emptyList(),
    val unlike: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val unsave: List<String> = emptyList(),
)

@Serializable
data class SettingsSectionRules(
    /** Path pattern of Instagram's settings page. */
    val path: String,
    /** Section headings (any language) after whose section Throwback's section goes, e.g. "Your account". */
    val after: List<String>,
)

@Serializable
data class FeedDataRules(
    /** Keys of feed connections (objects with `edges[].node`) in Instagram's JSON data. */
    val connections: List<String>,
    /** Field of a feed node holding the post. */
    val mediaField: String = "media",
    /** Node fields that, when set, mark an ad. Never kept. */
    val adNodeFields: List<String> = emptyList(),
    /** Node fields holding a suggested post (the post is at `<field>.<mediaField>`). Discovery may keep a few. */
    val suggestionNodeFields: List<String> = emptyList(),
    /** Node fields marking other units (suggested accounts, surveys). Never kept. */
    val dropNodeFields: List<String> = emptyList(),
    /** Post tests marking an ad. Never kept. */
    val adMedia: List<FieldTest> = emptyList(),
    /** Post tests marking a post from someone you don't follow. Discovery may keep a few. */
    val suggestedMedia: List<FieldTest> = emptyList(),
    /** Node fields marking Instagram's own "You're all caught up" point. */
    val endMarkerFields: List<String> = emptyList(),
    /** End the feed at Instagram's marker (true) or only when only suggestions are left (false). */
    val endAtMarker: Boolean = false,
    /** This many pages in a row without a post from someone you follow = the end. */
    val endAfterEmptyPages: Int = 3,
    /**
     * Entries Instagram's own feed request asks for (its `first`), raised so each request brings more
     * posts from people you follow after filtering. 0: unchanged.
     */
    val pageSize: Int = 0,
    /** Friendly names of the feed requests whose `first` may be raised to [pageSize]. */
    val paginationQueries: List<String> = emptyList(),
    /** Look-ahead: photos (and video thumbnails) of this many upcoming posts go into the disk cache. */
    val lookAheadPosts: Int = 0,
    /** ... and the start of this many upcoming videos is kept in memory. */
    val lookAheadVideos: Int = 0,
    /** How much of each upcoming video's start to keep, in KB. */
    val videoKB: Int = 400,
)

/** Matches when the value at [path] (dot-separated) is present, or equals [equals] as text. */
@Serializable
data class FieldTest(val path: String, val equals: String? = null, val present: Boolean? = null)

@Serializable
data class StoryRules(
    /** Keys of story data in Instagram's JSON (where story items are learned from). */
    val connections: List<String> = emptyList(),
    /** Keys of story-ad data; their lists are emptied before the page uses them. */
    val adKeys: List<String> = emptyList(),
    /** How many upcoming story videos to look ahead (0: none). */
    val prefetchAhead: Int = 3,
    /** How much of each upcoming video's start to keep in memory, in KB. */
    val prefetchKB: Int = 400,
)

@Serializable
data class ReelViewerRules(
    /**
     * The poster shows on a random swipe between these (inclusive), picked each time a new count
     * starts, so it can't be predicted. The reel that was opened is free.
     */
    val posterOnSwipeMin: Int = 1,
    val posterOnSwipeMax: Int = 4,
    /** ... per this many minutes, so closing and reopening the viewer doesn't reset it. */
    val windowMinutes: Int = 10,
    /** After the poster showed, no free swipes for this many minutes (the opened reel still plays). */
    val cooldownMinutes: Int = 60,
)

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

@Serializable
data class AppCreateRules(
    /** Opens the Instagram app's create screen (post, story, reel). */
    val link: String,
    /** Regex on the activity class name: the screens the create pass allows. */
    val screens: String,
    /** The website's buttons that open the app's create screen instead. */
    val triggers: List<AppCreateTrigger> = emptyList(),
)

@Serializable
data class AppCreateTrigger(
    /** A CSS selector of the button (or something inside it). */
    val selector: String? = null,
    /** Or: a button whose text is one of these (e.g. "Your story", any language)... */
    val text: List<String> = emptyList(),
    /** ...and that holds a file input (the web's own "add to story" picker). */
    val fileInput: Boolean = false,
)
