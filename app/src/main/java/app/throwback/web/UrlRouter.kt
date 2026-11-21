package app.throwback.web

import app.throwback.core.WebMode
import app.throwback.web.rules.FilterRules

/**
 * Decides what happens to a top-level navigation: load it in the WebView, redirect it (blocked
 * pages, messages-only mode, the home feed), hand it to another app, or ignore it.
 *
 * Pure Kotlin so it can be unit tested. throwback.js mirrors the Instagram-path part of this logic
 * for in-page (pushState) navigation, which never reaches the WebViewClient.
 */
class UrlRouter(private val rules: FilterRules) {

    sealed interface Decision {
        /** Let the WebView load it. */
        data object Load : Decision

        /**
         * Load [url] instead. [kind] is set when content was blocked ("reels", "messagesOnly", ...)
         * and is null for plain normalisation (https, the home feed).
         */
        data class Redirect(val url: String, val kind: String?) : Decision

        /** Not Instagram: open in the user's browser or the right app. */
        data class External(val url: String) : Decision

        /** Do nothing ("open in app" intents, app store pages, unparseable or ambiguous links). */
        data object Ignore : Decision
    }

    private val instagram = HostMatcher(rules.hosts.instagram)
    private val internal = HostMatcher(rules.hosts.internal)
    private val login = HostMatcher(rules.hosts.login)
    private val loginPaths = rules.hosts.loginPaths.map(::Regex)
    private val shims = rules.hosts.linkShims.associate { it.host.lowercase() to it.param }
    private val blockedUrls = rules.hosts.blockedUrls.map(::Regex)
    private val blocked = rules.paths.blocked.map { Regex(it.pattern) to it }
    private val alwaysAllowed = rules.paths.alwaysAllowed.map(::Regex)
    private val auth = rules.paths.auth.map(::Regex)
    private val messagesOnly = rules.paths.messagesOnly.map(::Regex)
    private val pullToRefresh = rules.paths.pullToRefresh.map(::Regex)
    private val home = UrlParts.parse(rules.homeUrl)

    /** Rewritten paths go to the home URL's origin (www.instagram.com), saving Instagram's own redirect. */
    private val canonicalOrigin = home?.let { "https://${it.host}" }
    private val homeVariants: Set<String?> = rules.homeVariants.toSet() + home?.queryParam("variant")

    fun isInstagramHost(host: String): Boolean = instagram.matches(host)

    /** An Instagram page that gets the page script (its origin is one of Instagram's). */
    fun isInstagram(url: String?): Boolean {
        val parts = url?.let(UrlParts::parse) ?: return false
        return parts.isWeb() && parts.plainHost && instagram.matches(parts.host)
    }

    /** Where a mode starts: the home feed, or the inbox in messages-only mode. */
    fun homeUrl(mode: WebMode): String = if (mode == WebMode.MESSAGES_ONLY) rules.messagesUrl else rules.homeUrl

    /**
     * @param currentUrl the page the navigation starts from; it decides whether a Facebook or Accounts
     *        Center URL is part of a login flow (stays in the WebView) or a link someone shared (opens
     *        outside; in messages-only mode Accounts Center goes to the inbox). Null: not in a login.
     * @param userGesture false when a page navigates on its own (no tap, and not a server redirect of
     *        one): it may not send the user to another app, so an outside link is then ignored.
     */
    fun decide(url: String, mode: WebMode, currentUrl: String?, userGesture: Boolean = true): Decision {
        val decision = decideUrl(url, mode, currentUrl)
        return if (decision is Decision.External && !userGesture) Decision.Ignore else decision
    }

    private fun decideUrl(url: String, mode: WebMode, currentUrl: String?): Decision {
        if (UrlParts.hasAmbiguousChars(url)) return Decision.Ignore
        val parts = UrlParts.parse(url) ?: return Decision.Ignore
        // Links into the Instagram app, an app store or the phone's files, and made-up documents, do nothing.
        if (parts.scheme in BLOCKED_SCHEMES) return Decision.Ignore
        if (blockedUrls.any { it.containsMatchIn(url.capped()) }) return Decision.Ignore
        return when (parts.scheme) {
            "http", "https" -> decideWeb(url, parts, mode, currentUrl)
            "about" -> Decision.Load
            // mailto:, tel:, sms:, whatsapp:, ... belong to other apps.
            else -> Decision.External(url)
        }
    }

    /**
     * The URL to load for a deep link (or null for the mode's home), after all redirects: an https
     * Instagram page, rebuilt from its parsed parts (never the raw string), else the mode's home.
     */
    fun startUrl(requested: String?, mode: WebMode): String {
        var url = requested ?: return homeUrl(mode)
        repeat(MAX_REDIRECT_HOPS) {
            when (val d = decide(url, mode, currentUrl = null)) {
                Decision.Load -> return UrlParts.parse(url)
                    ?.takeIf { it.scheme == "https" && instagram.matches(it.host) }
                    ?.toHttps() ?: homeUrl(mode)
                is Decision.Redirect -> url = d.url
                else -> return homeUrl(mode)
            }
        }
        return homeUrl(mode)
    }

    /** The path reported to integration: the path on Instagram, the full URL anywhere else. */
    fun locationFor(url: String): String {
        val parts = UrlParts.parse(url) ?: return url
        return if (parts.isWeb() && instagram.matches(parts.host)) parts.path else url
    }

    fun allowsPullToRefresh(url: String?): Boolean {
        val parts = url?.let(UrlParts::parse) ?: return false
        return parts.isWeb() && instagram.matches(parts.host) && pullToRefresh.any { it.containsMatchIn(parts.path.capped()) }
    }

    private fun decideWeb(url: String, parts: UrlParts, mode: WebMode, currentUrl: String?): Decision {
        shims[parts.host]?.let { param ->
            // The target is percent-decoded here, so it can hold what the outer URL could not.
            val target = parts.queryParam(param)
            if (target != null && UrlParts.hasAmbiguousChars(target)) return Decision.Ignore
            val targetParts = target?.let(UrlParts::parse)
            return when {
                target == null || targetParts == null || !targetParts.isWeb() -> Decision.External(url)
                instagram.matches(targetParts.host) || targetParts.host in SHORT_LINK_HOSTS ->
                    Decision.Redirect(targetParts.toHttps(), null)
                else -> Decision.External(target)
            }
        }
        return when {
            instagram.matches(parts.host) -> decideInstagram(parts, mode)
            // Instagram's own links stay in Throwback (another app would be the Instagram app).
            parts.host in SHORT_LINK_HOSTS ->
                if (parts.scheme == "https" && parts.plainHost) Decision.Load else Decision.Redirect(parts.toHttps(), null)
            // Accounts Center: messages-only mode shows it only on the way through a login.
            internal.matches(parts.host) -> if (mode != WebMode.MESSAGES_ONLY || inLoginFlow(currentUrl, mode)) {
                Decision.Load
            } else {
                Decision.Redirect(rules.messagesUrl, KIND_MESSAGES_ONLY)
            }
            // Facebook/Meta: only their login pages, and only during a login. The rest is Facebook itself.
            login.matches(parts.host) && isLoginPage(parts) && inLoginFlow(currentUrl, mode) -> Decision.Load
            else -> Decision.External(url)
        }
    }

    private fun decideInstagram(parts: UrlParts, mode: WebMode): Decision {
        val path = parts.path
        // http, or a host written with a port, user info or a trailing dot: the plain https address.
        if (parts.scheme == "http" || !parts.plainHost) return Decision.Redirect(parts.toHttps(), null)
        val matched = path.capped()
        if (alwaysAllowed.any { it.containsMatchIn(matched) }) return Decision.Load

        if (mode == WebMode.MESSAGES_ONLY) {
            return if (messagesOnly.any { it.containsMatchIn(matched) }) {
                Decision.Load
            } else {
                Decision.Redirect(rules.messagesUrl, KIND_MESSAGES_ONLY)
            }
        }

        for ((regex, rule) in blocked) {
            val match = regex.find(matched) ?: continue
            val origin = canonicalOrigin ?: "https://${parts.host}"
            val target = rule.redirect?.let { origin + expandTemplate(it, match) } ?: rules.homeUrl
            return Decision.Redirect(target, rule.kind)
        }

        if (home != null && path == home.path && parts.queryParam("variant") !in homeVariants) {
            return Decision.Redirect(rules.homeUrl, null)
        }
        return Decision.Load
    }

    /**
     * True when [currentUrl] is part of a login, so Facebook/Meta login pages (and Accounts Center in
     * messages-only mode) may load from it: an Instagram login page that [mode] shows, Accounts
     * Center, or a Facebook/Meta login page. Anything else, or no page at all, is not.
     */
    private fun inLoginFlow(currentUrl: String?, mode: WebMode): Boolean {
        val current = currentUrl?.let(UrlParts::parse)?.takeIf { it.isWeb() } ?: return false
        return when {
            instagram.matches(current.host) ->
                auth.any { it.containsMatchIn(current.path.capped()) } && decideInstagram(current, mode) == Decision.Load
            internal.matches(current.host) -> true
            login.matches(current.host) -> isLoginPage(current)
            else -> false
        }
    }

    /** A page of a login on a Facebook/Meta host (hosts.loginPaths): not its feed, Reels or profiles. */
    private fun isLoginPage(parts: UrlParts) = loginPaths.any { it.containsMatchIn(parts.path.capped()) }

    private fun UrlParts.isWeb() = scheme == "https" || scheme == "http"

    /** Patterns only ever see the start of a URL or path, so a page can't make them run long. */
    private fun String.capped() = if (length <= MAX_MATCH_CHARS) this else substring(0, MAX_MATCH_CHARS)

    companion object {
        const val KIND_MESSAGES_ONLY = "messagesOnly"
        private const val MAX_REDIRECT_HOPS = 4
        private const val MAX_MATCH_CHARS = 2048

        /**
         * Never followed, whatever the rules say: schemes that open the Instagram app or an app store,
         * the phone's own files (another app can't open a file: link, and Android throws on it), and
         * documents a page makes up itself (data:, blob:, javascript:), which would show unfiltered.
         */
        private val BLOCKED_SCHEMES = setOf("intent", "instagram", "market", "file", "content", "data", "blob", "javascript")

        /**
         * Instagram's short-link sites (ig.me/m/<user> opens a chat, instagr.am/p/<code> a post). Loaded
         * here: their server sends the link on to instagram.com, which is routed as usual.
         */
        private val SHORT_LINK_HOSTS = setOf("ig.me", "instagr.am", "www.instagr.am")

        /** Fills $1..$9 from [match]; always returns a single-slash path (never "//host"). */
        internal fun expandTemplate(template: String, match: MatchResult): String {
            val filled = Regex("\\$(\\d)").replace(template) { g ->
                match.groupValues.getOrElse(g.groupValues[1].toInt()) { "" }
            }
            return "/" + filled.trimStart('/')
        }
    }
}

/**
 * Stops redirect ping-pong (for example if Instagram ever sends /p/<code>/ back to /reel/<code>/):
 * the same target may be redirected to at most [maxRepeats] times within [windowMillis].
 */
internal class RedirectLoopGuard(
    private val now: () -> Long,
    private val windowMillis: Long = 15_000,
    private val maxRepeats: Int = 3,
) {
    private val recent = ArrayDeque<Pair<String, Long>>()

    /** Records and allows the redirect, or returns false if it would loop. */
    fun allow(target: String): Boolean {
        val t = now()
        while (recent.isNotEmpty() && t - recent.first().second > windowMillis) recent.removeFirst()
        if (recent.count { it.first == target } >= maxRepeats) return false
        recent.addLast(target to t)
        if (recent.size > 50) recent.removeFirst()
        return true
    }
}
