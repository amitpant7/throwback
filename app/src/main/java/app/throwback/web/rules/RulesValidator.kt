package app.throwback.web.rules

import app.throwback.core.WebMode
import app.throwback.web.UrlParts
import app.throwback.web.UrlRouter

/**
 * Checks a parsed rules file before it is used or cached. Remote rules replace the cache only when
 * this returns no errors, so a broken edit on GitHub can never break installed apps.
 *
 * Besides syntax, it checks the invariants the app relies on: the home and messages URLs are
 * reachable in their own mode (otherwise every load would redirect forever), only Instagram's own
 * hosts are treated as Instagram, patterns work the same in the page and stay cheap
 * ([RulePatterns]), selectors cannot break out of the generated CSS, and sizes stay small.
 */
object RulesValidator {

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,40}$")
    private val PAGE_NAME = Regex("^[a-z][a-z0-9-]{0,30}$")
    private val HOST = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val FIELD = Regex("^[A-Za-z_][A-Za-z0-9_]{0,80}$")
    private val FIELD_PATH = Regex("^[A-Za-z_][A-Za-z0-9_]{0,80}(\\.[A-Za-z_][A-Za-z0-9_]{0,80}){0,6}$")
    private val KINDS = Regex("^[a-zA-Z][a-zA-Z0-9-]{0,30}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]{0,40}(\\.[A-Za-z][A-Za-z0-9_]{0,40}){1,8}$")

    /** Where `explore.images` may point: Throwback's own repository. */
    private const val IMAGES_PREFIX = "https://raw.githubusercontent.com/amitpant7/throwback/"

    private const val MAX_SELECTOR = 1000
    private const val MAX_LIST = 300

    /** Values of CSS scroll-snap-type's axis that the reel viewer's strip may snap along. */
    private val SNAP_AXES = setOf("y", "x", "block", "inline", "both")
    private const val MAX_PACKAGES = 20

    fun validate(rules: FilterRules): List<String> {
        val errors = mutableListOf<String>()
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) errors += message()
        }
        fun pattern(p: String, where: String): Regex? = RulePatterns.check(p, where, errors)

        check(rules.schema == SUPPORTED_RULES_SCHEMA) { "unsupported schema ${rules.schema}" }
        check(rules.version > 0) { "version must be positive" }

        // Hosts. Which sites count as Instagram, or may stay in the WebView, is pinned in the app.
        val h = rules.hosts
        check(h.instagram.isNotEmpty()) { "hosts.instagram is empty" }
        val allHosts = h.instagram + h.internal + h.login + h.linkShims.map { it.host }
        allHosts.forEach { check(HOST.matches(it)) { "bad host '$it'" } }
        h.instagram.forEach { check(it in INSTAGRAM_HOSTS) { "hosts.instagram: '$it' is not one of $INSTAGRAM_HOSTS" } }
        (h.internal + h.login + h.linkShims.map { it.host }).forEach {
            check(isMetaHost(it)) { "hosts: '$it' is not under ${META_DOMAINS.joinToString(", ")}" }
        }
        h.linkShims.forEach { check(it.param.isNotBlank()) { "link shim ${it.host} has no param" } }
        h.blockedUrls.forEach { pattern(it, "hosts.blockedUrls") }

        // Paths
        val p = rules.paths
        check(p.messagesOnly.isNotEmpty()) { "paths.messagesOnly is empty" }
        check(p.blocked.size <= MAX_LIST) { "too many blocked paths" }
        p.blocked.forEach { b ->
            pattern(b.pattern, "paths.blocked")
            check(KINDS.matches(b.kind)) { "bad kind '${b.kind}'" }
            b.redirect?.let { r ->
                // A template starting with "//" would turn into a link to another site in the page.
                check(r.startsWith("/") && !r.startsWith("//") && !r.contains("\\")) { "bad redirect '$r'" }
            }
        }
        (p.alwaysAllowed + p.auth + p.messagesOnly + p.pullToRefresh).forEach { pattern(it, "paths") }

        // Pages
        rules.pages.forEach { (name, regex) ->
            check(PAGE_NAME.matches(name)) { "bad page name '$name'" }
            pattern(regex, "pages.$name")
        }
        val onValues = setOf(ON_ALWAYS, ON_FULL, ON_MESSAGES_ONLY) + rules.pages.keys
        fun checkOn(on: String, where: String) = check(on in onValues) { "$where: unknown 'on' value '$on'" }

        // Hide rules. Their ids share the page's data-tb attribute, so they must be unique.
        check(rules.hide.size + rules.hideText.size <= MAX_LIST) { "too many hide rules" }
        val ids = (rules.hide.map { it.id } + rules.hideText.map { it.id })
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "duplicate rule id '$it'" }
        rules.hide.forEach { r ->
            check(ID.matches(r.id)) { "bad id '${r.id}'" }
            checkSelector(r.selector, "hide.${r.id}", errors)
            r.closest?.let { checkSelector(it, "hide.${r.id}.closest", errors) }
            check(r.closestDepth in 1..20) { "hide.${r.id}: closestDepth out of range" }
            check(r.style == STYLE_REMOVE || r.style == STYLE_INVISIBLE) { "hide.${r.id}: bad style '${r.style}'" }
            checkOn(r.on, "hide.${r.id}")
        }
        rules.hideText.forEach { r ->
            check(ID.matches(r.id)) { "bad id '${r.id}'" }
            checkSelector(r.elements, "hideText.${r.id}", errors)
            checkTexts(r.text, "hideText.${r.id}", errors)
            checkOn(r.on, "hideText.${r.id}")
        }

        // Feed posts and blocks
        checkSelector(rules.posts.container, "posts.container", errors)
        // The page pastes it into compound selectors: a list ("article, div") would hide every home post.
        check(!hasTopLevelComma(rules.posts.container)) { "posts.container must be a single selector (no top-level comma)" }
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
            // A selector's element is pressed only when its label is one of these, so a rule needs them too.
            check(d.click.isNotEmpty()) { "dialogs.${d.id}: nothing to click" }
            d.click.forEach { check(it.length <= RulePatterns.MAX_TEXT && DismissLabels.allows(it)) { "dialogs.${d.id}: '$it' is not a dismiss button" } }
            d.clickSelector?.let { checkSelector(it, "dialogs.${d.id}.clickSelector", errors) }
        }
        check(rules.dismissMaxAttempts in 0..500) { "dismissMaxAttempts out of range" }
        rules.explore?.let { e ->
            checkSelector(e.keep, "explore.keep", errors)
            checkSelector(e.items, "explore.items", errors)
            checkOn(e.on, "explore")
            check(e.dailySeconds in 0..3600) { "explore.dailySeconds out of range" }
            check(e.images.size <= 50) { "explore.images: too many" }
            e.images.forEach {
                // No way out of the repository: "throwback/../other/" or its encoded form.
                check(it.startsWith(IMAGES_PREFIX) && ".." !in it && !it.contains("%2e", ignoreCase = true)) {
                    "explore.images must be in Throwback's repo"
                }
            }
            e.actions?.let { a ->
                (a.like + a.unlike + a.save + a.unsave).forEach { check(it.isNotBlank() && it.length <= 60) { "explore.actions: bad label" } }
            }
        }

        // The Instagram app (when connected): its screens decide what a pass allows.
        rules.appSettings?.let { a ->
            check(a.link.startsWith("instagram://")) { "appSettings.link must be an instagram:// link" }
            checkScreens(a.screens, "appSettings.screens", errors)
            check(a.otherScreenMinutes in 0..30) { "appSettings.otherScreenMinutes out of range" }
            checkPackages(a.flowPackages, "appSettings.flowPackages", errors)
        }
        rules.appCreate?.let { a ->
            check(a.link.startsWith("instagram://")) { "appCreate.link must be an instagram:// link" }
            checkScreens(a.screens, "appCreate.screens", errors)
            check(a.triggers.size <= 10) { "appCreate.triggers: too many" }
            checkPackages(a.flowPackages, "appCreate.flowPackages", errors)
            a.story?.let { st ->
                check(st.link.startsWith("instagram://")) { "appCreate.story.link must be an instagram:// link" }
                check(st.editMinutes in 1..15) { "appCreate.story.editMinutes out of range" }
                check(st.openSeconds in 3..30) { "appCreate.story.openSeconds out of range" }
                check(st.cooldownMinutes in 0..240) { "appCreate.story.cooldownMinutes out of range" }
                check(st.perDay in 0..20) { "appCreate.story.perDay out of range" }
                // Any notification on a matching channel counts as "posted" (the pass is forgiven).
                checkNarrow(st.postingChannels, "appCreate.story.postingChannels", "notification channel", errors)
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
            rv.viewerSelector?.let { checkSelector(it, "reelViewer.viewerSelector", errors) }
            check(rv.snapAxis in SNAP_AXES) { "reelViewer.snapAxis must be one of $SNAP_AXES" }
            check(rv.minVideos in 1..10 && rv.maxDepth in 1..30) { "reelViewer.minVideos or maxDepth out of range" }
            check(rv.closeLabels.isNotEmpty() && rv.closeLabels.size <= MAX_LIST) { "reelViewer.closeLabels size" }
            rv.closeLabels.forEach { check(DismissLabels.allows(it)) { "reelViewer.closeLabels: '$it' is not a dismiss button" } }
        }
        rules.settingsSection?.let { ss ->
            pattern(ss.path, "settingsSection.path")
            checkTexts(ss.after, "settingsSection.after", errors)
        }

        // Entry points must be Instagram pages that their own mode actually shows.
        // Skipped when the patterns above are broken (the router would throw).
        val router = if (errors.isEmpty()) UrlRouter(rules) else null
        router?.let {
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
        NotificationRulesValidator.validate(rules, errors, router)
        return errors
    }

    /** One of [META_DOMAINS] or a subdomain ("*.facebook.com" counts as facebook.com). */
    private fun isMetaHost(host: String): Boolean {
        val bare = host.removePrefix("*.")
        return META_DOMAINS.any { bare == it || bare.endsWith(".$it") }
    }

    /**
     * Instagram app screens a pass allows (regex on the activity class). One that matches the empty
     * string (blank, ".*", "x?") matches every screen, so the pass would never hold anything back.
     */
    private fun checkScreens(screens: String, where: String, errors: MutableList<String>) =
        checkNarrow(screens, where, "screen", errors)

    /** A valid pattern that doesn't match the empty string (which would match every [what]). */
    private fun checkNarrow(pattern: String, where: String, what: String, errors: MutableList<String>) {
        val regex = RulePatterns.check(pattern, where, errors) ?: return
        if (regex.containsMatchIn("")) errors += "$where: matches every $what"
    }

    /** Apps a pass may pass through: package names, never Instagram's own (a pass can't extend itself). */
    private fun checkPackages(packages: List<String>, where: String, errors: MutableList<String>) {
        if (packages.size > MAX_PACKAGES) errors += "$where: too many"
        packages.forEach {
            if (!PACKAGE.matches(it)) {
                errors += "$where: bad package name '$it'"
            } else if (it == "com.instagram" || it.startsWith("com.instagram.")) {
                errors += "$where: '$it' is Instagram itself"
            }
        }
    }

    /** A comma outside brackets, parentheses and quotes: a selector list, not one selector. */
    internal fun hasTopLevelComma(selector: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < selector.length) {
            val c = selector[i]
            when {
                c == '\\' -> i++ // the escaped character is plain text
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth = (depth - 1).coerceAtLeast(0)
                c == ',' && depth == 0 -> return true
            }
            i++
        }
        return false
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
        texts.forEach { if (it.isBlank() || it.length > RulePatterns.MAX_TEXT) errors += "$where: bad text '$it'" }
    }
}
