package app.throwback.web.rules

import app.throwback.web.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePatternsTest {

    private fun errorsFor(pattern: String): List<String> = mutableListOf<String>().also { RulePatterns.check(pattern, "p", it) }

    private fun assertRefused(pattern: String, fragment: String) {
        val errors = errorsFor(pattern)
        assertTrue("'$pattern': expected an error containing '$fragment', got $errors", errors.any { fragment in it })
    }

    private fun assertAccepted(pattern: String) = assertEquals(pattern, emptyList<String>(), errorsFor(pattern))

    @Test
    fun `every pattern in the bundled rules passes`() {
        val r = TestRules.bundled
        val all = r.hosts.blockedUrls + r.paths.blocked.map { it.pattern } + r.paths.alwaysAllowed + r.paths.auth +
            r.paths.messagesOnly + r.paths.pullToRefresh + r.pages.values +
            listOfNotNull(r.settingsSection?.path, r.appCreate?.screens, r.appSettings?.screens, r.appCreate?.story?.postingChannels) +
            r.notifications!!.classify.flatMap { listOfNotNull(it.channel, it.template, it.group, it.tag) }
        assertTrue(all.size > 30)
        all.forEach(::assertAccepted)
    }

    @Test
    fun `Java-only syntax is refused`() {
        listOf(
            "(?i)^/direct(/|$)", "^/direct(?i:/inbox)", "(?-s)^/x", "^/(?>reels|reel)/", // inline flags, atomic group
            "^/reels*+/", "^/a++", "^/a?+b", "^/a{2}+b", // possessive
            "\\A/x", "^/x\\Z", "^/x\\z", "\\G/x", "\\Q/x\\E", "^/\\p{L}+", "^/\\P{L}", "^/\\h", "^/x\\R", "^/\\X", "^/\\x{41}",
            "^/[a-z&&[^b]]", "^/[a[b]]", "^/[]a]", "^/[^]a]", "^/[\\p{L}]",
        ).forEach { assertRefused(it, "Java-only") }
    }

    @Test
    fun `syntax both engines read alike is accepted`() {
        listOf(
            "^/(?:a|b)/", "^/x(?=/)", "^/x(?!y)", "(?<=/)x$", "^/(?<user>[a-z]+)/$", "^/p/(.+?)/$", "^/a{2,3}?b",
            "^/[\\w.-]+$", "^/\\d+/\\$", "^/x\\b", "[+*?]", "^/\\(x\\)+$", "^\\\\Q$",
        ).forEach(::assertAccepted)
    }

    @Test
    fun `a repeated group holding a quantifier or an alternative is refused`() {
        // Exponential backtracking in some engine (OpenJDK hides a few of these; Android's ICU doesn't).
        listOf(
            "^/(a+)+$", "^/(a*)*$", "^/(?:ab+)*$", "^/([a-z]{2,})+$", "^/((a|b)*c)+$", "^/(x(y+))*$", "(a+){2}",
            "^/(a|aa)+$", "(a|a)*b", "^/explore/(a|aa)+$", "^(\\w|\\d)+$", "^/(a?a)+$", "^/((a+)?)+$",
        ).forEach { assertRefused(it, "repeats a group") }
        // Optional, or not repeated at all: fine.
        listOf("^/(a+)?$", "^/(a+)/(b*)$", "^/(ab)+$", "^/([A-Za-z0-9_-]{9,})/?$", "^ig_direct(_requests)?$", "^/(login|signup)(/|$)")
            .forEach(::assertAccepted)
    }

    @Test
    fun `patterns that are slow on long input are refused where reads can be counted`() {
        // The unit tests run on the JVM, whose regex engine reads through the CharSequence.
        assertTrue(RulePatterns.canBudgetReads)
        listOf("[a-z]+/x", ".*\\.instagram\\.com", "^/.*/.*/.*x$", "^/explore/[a-z]*[a-z0-9]*[a-z_]*!x")
            .forEach { assertRefused(it, "too long") }
    }

    @Test
    fun `length and compile errors`() {
        assertRefused("", "pattern length")
        assertRefused("a".repeat(RulePatterns.MAX_PATTERN + 1), "pattern length")
        assertRefused("^/(unclosed", "invalid pattern")
        assertNotNull(RulePatterns.check("^/x$", "p", mutableListOf()))
        assertNull(RulePatterns.check("^/(a+)+$", "p", mutableListOf()))
    }

    @Test
    fun `the probes reach past a literal start`() {
        // Slow only after "/explore/": the probes start with the pattern's own literal text too.
        assertRefused("^/explore/[a-z]*[a-z0-9]*[a-z_]*!x", "too long")
        assertTrue(errorsFor("^/explore/[a-z]+$").isEmpty())
    }

    @Test
    fun `literal prefix for the probes`() {
        assertEquals("/explore/", RulePatterns.literalPrefix("^/explore/(a|b)+"))
        assertEquals("play.google", RulePatterns.literalPrefix("play\\.google\\w"))
        assertEquals("/reel", RulePatterns.literalPrefix("^/reels?/"))
        assertEquals("", RulePatterns.literalPrefix("(call|rtc)"))
    }
}
