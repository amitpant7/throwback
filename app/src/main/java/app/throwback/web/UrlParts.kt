package app.throwback.web

import java.net.URLDecoder

/**
 * A small, lenient URL splitter. Plain Kotlin on purpose: android.net.Uri is not available in JVM
 * unit tests, and java.net.URI rejects characters Instagram links do contain (e.g. '|' or spaces).
 */
internal data class UrlParts(
    /** Lowercase scheme, e.g. "https", "intent", "mailto". */
    val scheme: String,
    /** Lowercase host without user info or port; empty when the URL has no authority. */
    val host: String,
    /** Path as written; "/" when there is an authority but no path. */
    val path: String,
    /** Raw query without '?', or "". */
    val query: String,
) {
    /** This URL as https from its parts alone: no user info, port or fragment. What a checked link loads as. */
    fun toHttps(): String = "https://$host$path" + if (query.isEmpty()) "" else "?$query"

    /** First value of a query parameter, percent-decoded. */
    fun queryParam(name: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            if (key == name) return if (eq < 0) "" else decode(pair.substring(eq + 1))
        }
        return null
    }

    companion object {
        /**
         * The authority ends at '/', '?', '#' and, as in Chromium for http(s), at '\'. Otherwise
         * "https://instagram.computer\@instagram.com/" would look like instagram.com here while the
         * WebView loads instagram.computer.
         */
        private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(//([^/\\\\?#]*))?([^?#]*)(\\?([^#]*))?(#.*)?$")

        fun parse(url: String): UrlParts? {
            val m = URL.matchEntire(url.trim()) ?: return null
            val scheme = m.groupValues[1].lowercase()
            val hasAuthority = m.groups[2] != null
            val authority = m.groupValues[3]
            val host = authority
                .substringAfterLast('@')
                .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
                .lowercase()
                .trimEnd('.')
            val rawPath = m.groupValues[4]
            val path = if (hasAuthority && rawPath.isEmpty()) "/" else rawPath
            return UrlParts(scheme, host, path, m.groupValues[6])
        }

        /**
         * Backslashes and ASCII control characters: Chromium reads URLs holding them differently from
         * [parse] (a backslash ends the host, tabs and newlines are dropped), so such URLs are never used.
         */
        fun hasAmbiguousChars(url: String): Boolean = url.any { it == '\\' || it < ' ' || it == '\u007f' }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}

/** Matches hosts against rule patterns: exact ("instagram.com") or subdomain wildcard ("*.facebook.com"). */
internal class HostMatcher(patterns: List<String>) {
    private val exact = patterns.filterNot { it.startsWith("*.") }.map { it.lowercase() }.toSet()
    private val suffixes = patterns.filter { it.startsWith("*.") }.map { it.substring(1).lowercase() }

    fun matches(host: String): Boolean {
        val h = host.lowercase()
        return h in exact || suffixes.any { h.endsWith(it) && h.length > it.length }
    }
}
