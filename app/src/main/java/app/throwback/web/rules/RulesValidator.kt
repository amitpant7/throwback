package app.throwback.web.rules

import app.throwback.core.WebMode
import app.throwback.web.UrlParts
import app.throwback.web.UrlRouter

/**
 * Checks a parsed rules file before it is used or cached. Remote rules replace the cache only when
 * this returns no errors, so a broken edit on GitHub can never break installed apps.
 *
 * Besides syntax, it checks the invariants the app relies on: the home and messages URLs are
 * reachable in their own mode (otherwise every load would redirect forever), selectors cannot
 * break out of the generated CSS, and sizes stay small.
 */
object RulesValidator {

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,40}$")
    private val PAGE_NAME = Regex("^[a-z][a-z0-9-]{0,30}$")
    private val HOST = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val FIELD = Regex("^[A-Za-z_][A-Za-z0-9_]{0,80}$")
    private val FIELD_PATH = Regex("^[A-Za-z_][A-Za-z0-9_]{0,80}(\\.[A-Za-z_][A-Za-z0-9_]{0,80}){0,6}$")
    private val KINDS = Regex("^[a-zA-Z][a-zA-Z0-9-]{0,30}$")

    private const val MAX_PATTERN = 300
    private const val MAX_SELECTOR = 1000
    private const val MAX_TEXT = 100
    private const val MAX_LIST = 300

    fun validate(rules: FilterRules): List<String> {
        val errors = mutableListOf<String>()
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) errors += message()
        }

        check(rules.schema == SUPPORTED_RULES_SCHEMA) { "unsupported schema ${rules.schema}" }
        check(rules.version > 0) { "version must be positive" }

        // Hosts
        check(rules.hosts.instagram.isNotEmpty()) { "hosts.instagram is empty" }
        val allHosts = rules.hosts.instagram + rules.hosts.internal + rules.hosts.login + rules.hosts.linkShims.map { it.host }
        allHosts.forEach { check(HOST.matches(it)) { "bad host '$it'" } }
        rules.hosts.linkShims.forEach { check(it.param.isNotBlank()) { "link shim ${it.host} has no param" } }
        rules.hosts.blockedUrls.forEach { checkPattern(it, "hosts.blockedUrls", errors) }

        // Paths
        val p = rules.paths
        check(p.messagesOnly.isNotEmpty()) { "paths.messagesOnly is empty" }
        check(p.blocked.size <= MAX_LIST) { "too many blocked paths" }
        p.blocked.forEach { b ->
            checkPattern(b.pattern, "paths.blocked", errors)
            check(KINDS.matches(b.kind)) { "bad kind '${b.kind}'" }
            b.redirect?.let { r ->
                // A template starting with "//" would turn into a link to another site in the page.
                check(r.startsWith("/") && !r.startsWith("//") && !r.contains("\\")) { "bad redirect '$r'" }
            }
        }
        (p.alwaysAllowed + p.auth + p.messagesOnly + p.pullToRefresh).forEach { checkPattern(it, "paths", errors) }

        // Pages
        rules.pages.forEach { (name, pattern) ->
            check(PAGE_NAME.matches(name)) { "bad page name '$name'" }
            checkPattern(pattern, "pages.$name", errors)
        }
        val onValues = setOf(ON_ALWAYS, ON_FULL, ON_MESSAGES_ONLY) + rules.pages.keys
        fun checkOn(on: String, where: String) = check(on in onValues) { "$where: unknown 'on' value '$on'" }

        // Hide rules. Their ids share the page's data-tb attribute, so they must be unique.
        check(rules.hide.size + rules.hideText.size <= MAX_LIST) { "too many hide rules" }
        val ids = (rules.hide.map { it.id } + rules.hideText.map { it.id })
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "duplicate rule id '$it'" }
        rules.hide.forEach { h ->
            check(ID.matches(h.id)) { "bad id '${h.id}'" }
            checkSelector(h.selector, "hide.${h.id}", errors)
            h.closest?.let { checkSelector(it, "hide.${h.id}.closest", errors) }
            check(h.closestDepth in 1..20) { "hide.${h.id}: closestDepth out of range" }
            check(h.style == STYLE_REMOVE || h.style == STYLE_INVISIBLE) { "hide.${h.id}: bad style '${h.style}'" }
            checkOn(h.on, "hide.${h.id}")
        }
        rules.hideText.forEach { h ->
            check(ID.matches(h.id)) { "bad id '${h.id}'" }
            checkSelector(h.elements, "hideText.${h.id}", errors)
            checkTexts(h.text, "hideText.${h.id}", errors)
            checkOn(h.on, "hideText.${h.id}")
        }

        // Feed posts and blocks
        checkSelector(rules.posts.container, "posts.container", errors)
        check(rules.posts.maxMediaBefore in 0..10) { "posts.maxMediaBefore out of range" }
        check(rules.posts.headerMaxFraction in 0.05..1.0) { "posts.headerMaxFraction out of range" }
        (rules.posts.labels + rules.modules + rules.sectionMarkers).forEach { t ->
            check(ID.matches(t.id)) { "bad id '${t.id}'" }
            checkTexts(t.text, t.id, errors)
            checkOn(t.on, t.id)
        }
        rules.dialogs.forEach { d ->
            check(ID.matches(d.id)) { "bad id '${d.id}'" }
            checkTexts(d.whenText, "dialogs.${d.id}.whenText", errors)
            check(d.click.isNotEmpty() || d.clickSelector != null) { "dialogs.${d.id}: nothing to click" }
            d.click.forEach { check(it.isNotBlank() && it.length <= MAX_TEXT) { "dialogs.${d.id}: bad click text" } }
            d.clickSelector?.let { checkSelector(it, "dialogs.${d.id}.clickSelector", errors) }
        }
        check(rules.dismissMaxAttempts in 0..500) { "dismissMaxAttempts out of range" }
        rules.explore?.let { e ->
            checkSelector(e.keep, "explore.keep", errors)
            checkSelector(e.items, "explore.items", errors)
            checkOn(e.on, "explore")
            check(e.dailySeconds in 0..3600) { "explore.dailySeconds out of range" }
            e.actions?.let { a ->
                (a.like + a.unlike + a.save + a.unsave).forEach { check(it.isNotBlank() && it.length <= 60) { "explore.actions: bad label" } }
            }
        }
        rules.nav?.let { n ->
            checkSelector(n.links, "nav.links", errors)
            check(n.minLinks in 2..10) { "nav.minLinks out of range" }
        }
        checkSelector(rules.readySelector, "readySelector", errors)
        rules.feedData?.let { fd ->
            checkTexts(fd.connections, "feedData.connections", errors)
            val fields = fd.connections + fd.adNodeFields + fd.suggestionNodeFields + fd.dropNodeFields + fd.endMarkerFields + fd.mediaField
            fields.forEach { check(FIELD.matches(it)) { "feedData: bad field '$it'" } }
            (fd.adMedia + fd.suggestedMedia).forEach { t ->
                check(FIELD_PATH.matches(t.path)) { "feedData: bad path '${t.path}'" }
                check((t.equals != null) != (t.present != null)) { "feedData: '${t.path}' needs exactly one of equals/present" }
            }
            check(fd.endAfterEmptyPages in 1..20) { "feedData.endAfterEmptyPages out of range" }
            check(fd.pageSize == 0 || fd.pageSize in 12..50) { "feedData.pageSize out of range" }
            fd.paginationQueries.forEach { check(FIELD.matches(it)) { "feedData: bad query name '$it'" } }
            check(fd.lookAheadPosts in 0..20) { "feedData.lookAheadPosts out of range" }
            check(fd.lookAheadVideos in 0..10) { "feedData.lookAheadVideos out of range" }
            check(fd.videoKB in 0..2048) { "feedData.videoKB out of range" }
        }
        rules.stories?.let { st ->
            (st.connections + st.adKeys).forEach { check(FIELD.matches(it)) { "stories: bad field '$it'" } }
            check(st.prefetchAhead in 0..10) { "stories.prefetchAhead out of range" }
            check(st.prefetchKB in 0..2048) { "stories.prefetchKB out of range" }
        }
        rules.reelViewer?.let { rv ->
            check(rv.posterOnSwipeMin in 1..50 && rv.posterOnSwipeMax in rv.posterOnSwipeMin..50) {
                "reelViewer.posterOnSwipe range out of range"
            }
            check(rv.windowMinutes in 1..1440) { "reelViewer.windowMinutes out of range" }
            check(rv.cooldownMinutes in 0..1440) { "reelViewer.cooldownMinutes out of range" }
        }
        rules.settingsSection?.let { ss ->
            checkPattern(ss.path, "settingsSection.path", errors)
            checkTexts(ss.after, "settingsSection.after", errors)
        }

        // Entry points must be Instagram pages that their own mode actually shows.
        // Skipped when the patterns above are broken (the router would throw).
        if (errors.isEmpty()) {
            val router = UrlRouter(rules)
            listOf(rules.homeUrl to WebMode.FULL, rules.messagesUrl to WebMode.MESSAGES_ONLY).forEach { (url, mode) ->
                val parts = UrlParts.parse(url)
                check(parts != null && parts.scheme == "https" && router.isInstagramHost(parts.host)) {
                    "$url is not an https Instagram URL"
                }
                check(router.decide(url, mode, currentUrl = null) == UrlRouter.Decision.Load) {
                    "$url is not allowed in $mode mode (it would redirect forever)"
                }
            }
        }
        NotificationRulesValidator.validate(rules, errors)
        return errors
    }

    private fun checkPattern(pattern: String, where: String, errors: MutableList<String>) {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN) {
            errors += "$where: pattern length"
            return
        }
        try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            errors += "$where: invalid pattern '$pattern'"
        }
    }

    /**
     * Selectors are pasted into generated CSS (`<selector> { display: none }`), so braces, '<' and
     * comment markers are refused: they could close the rule and hide the whole page.
     */
    private fun checkSelector(selector: String, where: String, errors: MutableList<String>) {
        if (selector.isBlank() || selector.length > MAX_SELECTOR) {
            errors += "$where: selector length"
        } else if (selector.any { it == '{' || it == '}' || it == '<' || it == ';' } || "/*" in selector) {
            errors += "$where: selector contains a forbidden character"
        }
    }

    private fun checkTexts(texts: List<String>, where: String, errors: MutableList<String>) {
        if (texts.isEmpty() || texts.size > MAX_LIST) errors += "$where: text list size"
        texts.forEach { if (it.isBlank() || it.length > MAX_TEXT) errors += "$where: bad text '$it'" }
    }
}
