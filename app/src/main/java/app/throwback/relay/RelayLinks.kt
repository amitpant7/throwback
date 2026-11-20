package app.throwback.relay

import app.throwback.web.rules.ThreadIdRule

/** Where a relayed notification opens inside Throwback. */
internal object RelayLinks {
    private const val THREAD_URL = "https://www.instagram.com/direct/t/%s/"
    private val THREAD_ID = Regex("^[0-9]{5,40}$")

    /**
     * A chat's address if the rules say where its id is and the id looks right (digits only),
     * else null (open the inbox). A wrong rule can at worst open the inbox.
     */
    fun threadUrl(facts: NotificationFacts, rule: ThreadIdRule?): String? {
        rule ?: return null
        val source = when (rule.from) {
            "shortcut" -> facts.shortcutId
            "tag" -> facts.tag
            "group" -> facts.group
            else -> null
        } ?: return null
        val id = try {
            Regex(rule.pattern).find(source)?.groupValues?.getOrNull(1)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return if (THREAD_ID.matches(id)) THREAD_URL.format(id) else null
    }

    /**
     * The deep link to hand to the WebView. The web layer opens each new link once, so opening the
     * same address twice in a row (two message notifications for the inbox) would do nothing; a
     * trailing "#" alternates so a repeat always differs. The fragment never reaches Instagram's
     * server and the router ignores it.
     */
    fun repeatable(url: String, previous: String?): String {
        if (previous == null) return url
        val base = url.removeSuffix("#")
        return when (previous) {
            base -> "$base#"
            "$base#" -> base
            else -> url
        }
    }
}
