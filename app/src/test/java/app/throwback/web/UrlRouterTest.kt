package app.throwback.web

import app.throwback.core.WebMode
import app.throwback.core.WebMode.FULL
import app.throwback.core.WebMode.MESSAGES_ONLY
import app.throwback.web.UrlRouter.Decision
import app.throwback.web.rules.FallbackRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing with the real filters/rules.json. */
class UrlRouterTest {

    private val router = UrlRouter(TestRules.bundled)
    private val home = "https://www.instagram.com/"
    private val inbox = "https://www.instagram.com/direct/inbox/"
    private val ig = "https://www.instagram.com"

    private fun decide(url: String, mode: WebMode = FULL, current: String? = "$ig/") = router.decide(url, mode, current)

    // ---- Reels

    @Test
    fun `reels tab and reels pages are blocked`() {
        assertEquals(Decision.Redirect(home, "reels"), decide("$ig/reels/"))
        assertEquals(Decision.Redirect(home, "reels"), decide("$ig/reels"))
        assertEquals(Decision.Redirect(home, "reels"), decide("$ig/reels/audio/1234567890/"))
        assertEquals(Decision.Redirect(home, "reels"), decide("$ig/reel/"))
    }

    @Test
    fun `a single reel opens as a post`() {
        assertEquals(Decision.Redirect("$ig/p/C1a2B3c4D5e/", "reels"), decide("$ig/reel/C1a2B3c4D5e/"))
        assertEquals(Decision.Redirect("$ig/p/C1a2B3c4D5e/", "reels"), decide("$ig/reels/C1a2B3c4D5e/?igsh=abc"))
        assertEquals(Decision.Redirect("$ig/p/C1a2B3c4D5e/", "reels"), decide("$ig/some.user/reel/C1a2B3c4D5e/"))
    }

    @Test
    fun `a profile's reels tab goes to the profile`() {
        assertEquals(Decision.Redirect("$ig/some.user_1/", "reels"), decide("$ig/some.user_1/reels/"))
    }

    @Test
    fun `posts, profiles, stories and search load normally`() {
        listOf(
            "$ig/p/C1a2B3c4D5e/", "$ig/some.user/", "$ig/stories/some.user/123/",
            "$ig/explore/", "$ig/explore/search/", "$ig/explore/tags/cats/", "$ig/accounts/activity/",
            "$ig/direct/inbox/", "$ig/direct/t/1234/", "$ig/accounts/edit/",
        ).forEach { assertEquals(it, Decision.Load, decide(it)) }
    }

    @Test
    fun `a profile's tagged tab loads, like the rest of the profile`() { // owner, 2026-11-21
        assertEquals(Decision.Load, decide("$ig/some.user_1/tagged/"))
        assertEquals(Decision.Load, decide("$ig/some.user_1/"))
        assertEquals(Decision.Load, decide("$ig/explore/tags/tagged/"))
    }

    @Test
    fun `suggested people page loads`() { // owner, 2026-11-21
        assertEquals(Decision.Load, decide("$ig/explore/people/"))
    }

    // ---- Home feed

    @Test
    fun `home is Instagram's regular feed, and the feed variants are allowed`() {
        assertEquals(Decision.Load, decide("$ig/"))
        assertEquals(Decision.Load, decide("https://instagram.com/"))
        assertEquals(Decision.Load, decide("$ig/?variant=home"))
        assertEquals(Decision.Load, decide("$ig/?variant=following"))
        assertEquals(Decision.Load, decide("$ig/?variant=favorites"))
        assertEquals(Decision.Redirect(home, null), decide("$ig/?variant=something_new"))
    }

    @Test
    fun `http Instagram links are upgraded`() {
        assertEquals(Decision.Redirect("$ig/p/X/?a=1", null), decide("http://www.instagram.com/p/X/?a=1"))
    }

    // ---- Messages-only mode

    @Test
    fun `messages-only allows the inbox and chats`() {
        assertEquals(Decision.Load, decide(inbox, MESSAGES_ONLY))
        assertEquals(Decision.Load, decide("$ig/direct/t/340282366841710300949128/", MESSAGES_ONLY))
        assertEquals(Decision.Load, decide("$ig/direct/requests/", MESSAGES_ONLY))
    }

    @Test
    fun `messages-only redirects everything else to the inbox`() {
        listOf("$ig/", home, "$ig/p/X/", "$ig/some.user/", "$ig/reels/", "$ig/explore/", "$ig/stories/x/1/").forEach {
            assertEquals(it, Decision.Redirect(inbox, UrlRouter.KIND_MESSAGES_ONLY), decide(it, MESSAGES_ONLY))
        }
    }

    @Test
    fun `login and calls work in every mode`() {
        listOf(
            "$ig/accounts/login/?next=%2Fdirect%2Finbox%2F", "$ig/accounts/onetap/?next=%2F",
            "$ig/challenge/abc/", "$ig/accounts/password/reset/", "$ig/call/?thread_id=1",
        ).forEach {
            assertEquals(it, Decision.Load, decide(it, MESSAGES_ONLY))
            assertEquals(it, Decision.Load, decide(it, FULL))
        }
    }

    // ---- Other sites

    @Test
    fun `other sites open outside`() {
        assertEquals(Decision.External("https://example.com/a"), decide("https://example.com/a"))
        assertEquals(Decision.External("https://threads.net/@x"), decide("https://threads.net/@x"))
    }

    @Test
    fun `link shim targets open outside directly`() {
        val shim = "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fpage%3Fq%3D1&e=AT0abc"
        assertEquals(Decision.External("https://example.com/page?q=1"), decide(shim))
    }

    @Test
    fun `link shims to Instagram stay inside`() {
        val shim = "https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%2Fp%2FX%2F&e=1"
        assertEquals(Decision.Redirect("$ig/p/X/", null), decide(shim))
    }

    @Test
    fun `facebook stays inside only during login`() {
        val fb = "https://www.facebook.com/login.php?next=x"
        assertEquals(Decision.Load, decide(fb, current = "$ig/accounts/login/"))
        assertEquals(Decision.Load, decide("https://m.facebook.com/v18.0/dialog/oauth?x", current = "https://www.facebook.com/login.php"))
        assertEquals(Decision.Load, decide(fb, current = null))
        assertEquals(Decision.External(fb), decide(fb, current = "$ig/direct/t/1/"))
        assertEquals(Decision.External("https://facebook.com/somepage"), decide("https://facebook.com/somepage", current = home))
        // Lookalike hosts are not Facebook.
        assertEquals(Decision.External("https://evilfacebook.com/login"), decide("https://evilfacebook.com/login", current = "$ig/accounts/login/"))
    }

    @Test
    fun `accounts center stays inside`() {
        assertEquals(Decision.Load, decide("https://accountscenter.instagram.com/password_and_security/"))
    }

    @Test
    fun `open-in-app and store links do nothing`() {
        listOf(
            "intent://instagram.com/_n/mainfeed/#Intent;package=com.instagram.android;scheme=https;end",
            "instagram://user?username=x",
            "market://details?id=com.instagram.android",
            "https://play.google.com/store/apps/details?id=com.instagram.android&referrer=x",
            "https://apps.apple.com/app/instagram/id389801252",
        ).forEach { assertEquals(it, Decision.Ignore, decide(it)) }
        assertEquals(Decision.Ignore, decide("not a url"))
    }

    @Test
    fun `app and store schemes do nothing even when the rules don't list them`() {
        val bare = UrlRouter(FallbackRules.rules)
        assertTrue(FallbackRules.rules.hosts.blockedUrls.isEmpty())
        listOf("intent://instagram.com/#Intent;scheme=https;end", "INTENT:#Intent;end", "instagram://user?username=x", "market://details?id=x")
            .forEach { assertEquals(it, Decision.Ignore, bare.decide(it, FULL, "$ig/")) }
        assertEquals(Decision.External("mailto:a@b.c"), bare.decide("mailto:a@b.c", FULL, "$ig/"))
    }

    @Test
    fun `links to the phone's files do nothing`() {
        // Handed to another app, a file: link makes Android throw (FileUriExposedException).
        listOf("file:///sdcard/Download/x.pdf", "FILE:///data/data/app.throwback/", "content://media/external/images/1")
            .forEach { assertEquals(it, Decision.Ignore, decide(it)) }
    }

    @Test
    fun `a page can't open another app without a tap`() {
        assertEquals(Decision.Ignore, router.decide("https://example.com/a", FULL, "$ig/", userGesture = false))
        assertEquals(Decision.Ignore, router.decide("mailto:a@b.c", FULL, "$ig/", userGesture = false))
        assertEquals(Decision.Ignore, router.decide("https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2F", FULL, "$ig/", userGesture = false))
        assertEquals(Decision.External("https://example.com/a"), router.decide("https://example.com/a", FULL, "$ig/", userGesture = true))
        // Only leaving is held back: Instagram pages and redirects work as before.
        assertEquals(Decision.Load, router.decide("$ig/p/X/", FULL, "$ig/", userGesture = false))
        assertEquals(Decision.Redirect(home, "reels"), router.decide("$ig/reels/", FULL, "$ig/", userGesture = false))
    }

    @Test
    fun `patterns only see the first 2 KB of a long URL`() {
        val long = "$ig/p/X/" + "a".repeat(200_000)
        assertEquals(Decision.Load, decide(long))
        assertEquals(Decision.Redirect(home, "reels"), decide("$ig/reels/x/" + "a".repeat(200_000)))
        assertFalse(router.allowsPullToRefresh("$ig/direct/inbox/" + "a".repeat(200_000)))
    }

    @Test
    fun `other schemes go to their apps`() {
        assertEquals(Decision.External("mailto:a@b.c"), decide("mailto:a@b.c"))
        assertEquals(Decision.External("tel:+123"), decide("tel:+123"))
        assertEquals(
            Decision.External("https://play.google.com/store/apps/details?id=org.example"),
            decide("https://play.google.com/store/apps/details?id=org.example"),
        )
        assertEquals(Decision.Load, decide("about:blank"))
    }

    // ---- URLs Chromium reads differently

    @Test
    fun `a backslash ends the host, as in Chromium`() {
        val trick = "https://instagram.computer\\@instagram.com/accounts/login/"
        assertEquals("instagram.computer", UrlParts.parse(trick)!!.host)
        assertEquals(Decision.Ignore, decide(trick))
        assertEquals(Decision.Ignore, decide(trick, current = null))
        assertEquals(home, router.startUrl(trick, FULL))
        assertEquals(Decision.Ignore, decide("https://www.instagram.com\\p\\X\\"))
    }

    @Test
    fun `link shim targets with an encoded backslash do nothing`() {
        val shim = "https://l.instagram.com/?u=https%3A%2F%2Finstagram.computer%5C%40instagram.com%2Faccounts%2Flogin%2F&e=1"
        assertEquals(Decision.Ignore, decide(shim))
        assertEquals(Decision.Ignore, decide("https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%5Cp%2FX%2F"))
        assertEquals(home, router.startUrl(shim, FULL))
    }

    @Test
    fun `control characters make a URL do nothing`() {
        listOf(
            "https://www.instagram.com/p/X/\n", "https://evil.example\t@www.instagram.com/", "https://www.instagram.com/\u0000",
            "https://www.instagram.com/\u007f", "https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%2F%0A",
        ).forEach { assertEquals(it, Decision.Ignore, decide(it)) }
        assertEquals(home, router.startUrl("https://www.instagram.com/p/X/\r", FULL))
    }

    @Test
    fun `an Instagram host with a trailing dot, a port or user info goes to the plain address`() {
        // Chromium keeps these, so the page's origin would not be Instagram's: no page script, no filters.
        assertEquals(Decision.Redirect("$ig/p/X/", null), decide("https://www.instagram.com./p/X/"))
        assertEquals(Decision.Redirect(home, null), decide("https://www.instagram.com:8443/"))
        assertEquals(Decision.Redirect("$ig/reels/?a=1", null), decide("https://user@www.instagram.com/reels/?a=1"))
        assertEquals(Decision.Redirect(inbox, null), decide("https://www.instagram.com./direct/inbox/", MESSAGES_ONLY))
        assertFalse(router.isInstagram("https://www.instagram.com./"))
        assertTrue(router.isInstagram("https://WWW.Instagram.com/"))
        assertEquals("$ig/p/X/", router.startUrl("https://www.instagram.com./p/X/", FULL))
    }

    @Test
    fun `start urls and shim targets are rebuilt from their parts`() {
        assertEquals("$ig/p/X/?a=1", router.startUrl("https://user:pw@www.instagram.com:443/p/X/?a=1#frag", FULL))
        assertEquals("$ig/direct/inbox/", router.startUrl("$ig/direct/inbox/#", FULL))
        assertEquals(
            Decision.Redirect("$ig/p/X/?a=1", null),
            decide("https://l.instagram.com/?u=https%3A%2F%2Fuser%40www.instagram.com%3A8443%2Fp%2FX%2F%3Fa%3D1%23f"),
        )
        // Only https Instagram pages start; anything else that would load goes home.
        assertEquals(home, router.startUrl("javascript:alert(1)", FULL))
        assertEquals(home, router.startUrl("about:blank", FULL))
        assertEquals(home, router.startUrl("https://accountscenter.instagram.com/", FULL))
        assertEquals(home, router.startUrl("https://www.facebook.com/login.php", FULL))
    }

    // ---- Start URLs, locations, pull to refresh

    @Test
    fun `start urls`() {
        assertEquals(home, router.startUrl(null, FULL))
        assertEquals(inbox, router.startUrl(null, MESSAGES_ONLY))
        assertEquals("$ig/p/C1a2B3c4D5e/", router.startUrl("https://instagram.com/reel/C1a2B3c4D5e/", FULL))
        assertEquals("$ig/p/X/", router.startUrl("$ig/p/X/", FULL))
        assertEquals(inbox, router.startUrl("$ig/p/X/", MESSAGES_ONLY))
        assertEquals(home, router.startUrl("https://example.com/", FULL))
        assertEquals("https://instagram.com/", router.startUrl("http://instagram.com/", FULL)) // upgraded to https
    }

    @Test
    fun `location is the path on Instagram and the whole URL elsewhere`() {
        assertEquals("/", router.locationFor(home))
        assertEquals("/direct/inbox/", router.locationFor(inbox))
        assertEquals("https://www.facebook.com/login.php", router.locationFor("https://www.facebook.com/login.php"))
    }

    @Test
    fun `pull to refresh only on listed pages`() {
        assertTrue(router.allowsPullToRefresh(home))
        assertTrue(router.allowsPullToRefresh(inbox))
        assertFalse(router.allowsPullToRefresh("$ig/direct/t/1/"))
        assertFalse(router.allowsPullToRefresh("$ig/p/X/"))
        assertFalse(router.allowsPullToRefresh("https://www.facebook.com/"))
        assertFalse(router.allowsPullToRefresh(null))
    }

    @Test
    fun `redirect templates never produce protocol-relative paths`() {
        val match = Regex("^/(.*)$").find("//evil.example/x")!!
        assertEquals("/evil.example/x", UrlRouter.expandTemplate("/$1", match))
        assertEquals("/p//", UrlRouter.expandTemplate("/p/$2/", match)) // a missing group is empty
    }

    // ---- Loop guard and helpers

    @Test
    fun `loop guard allows three redirects to the same place within the window`() {
        var t = 0L
        val guard = RedirectLoopGuard(now = { t }, windowMillis = 1_000, maxRepeats = 3)
        assertTrue(guard.allow("a"))
        assertTrue(guard.allow("a"))
        assertTrue(guard.allow("b"))
        assertTrue(guard.allow("a"))
        assertFalse(guard.allow("a"))
        t = 2_000
        assertTrue(guard.allow("a"))
    }

    @Test
    fun `host matcher`() {
        val m = HostMatcher(listOf("instagram.com", "*.facebook.com"))
        assertTrue(m.matches("instagram.com"))
        assertTrue(m.matches("INSTAGRAM.com"))
        assertFalse(m.matches("www.instagram.com"))
        assertTrue(m.matches("m.facebook.com"))
        assertTrue(m.matches("a.b.facebook.com"))
        assertFalse(m.matches("facebook.com"))
        assertFalse(m.matches("evilfacebook.com"))
    }

    @Test
    fun `url parts`() {
        val p = UrlParts.parse("HTTPS://user:pw@WWW.Instagram.com:443/p/X/?a=1&b=two%20words&c#frag")!!
        assertEquals("https", p.scheme)
        assertEquals("www.instagram.com", p.host)
        assertEquals("/p/X/", p.path)
        assertEquals("1", p.queryParam("a"))
        assertEquals("two words", p.queryParam("b"))
        assertEquals("", p.queryParam("c"))
        assertNull(p.queryParam("d"))
        assertFalse(p.plainHost)
        assertTrue(UrlParts.parse("https://WWW.Instagram.com/")!!.plainHost)
        assertFalse(UrlParts.parse("https://www.instagram.com./")!!.plainHost)
        assertEquals("www.instagram.com", UrlParts.parse("https://www.instagram.com./")!!.host)
        assertEquals("/", UrlParts.parse("https://instagram.com")!!.path)
        assertEquals("", UrlParts.parse("mailto:a@b.c")!!.host)
        // Characters java.net.URI rejects.
        assertEquals("/explore/tags/a|b/", UrlParts.parse("https://www.instagram.com/explore/tags/a|b/")!!.path)
        assertNull(UrlParts.parse("no scheme here"))
        // Bad percent-encoding is kept as written.
        assertEquals("%zz", UrlParts.parse("https://x.com/?u=%zz")!!.queryParam("u"))
    }
}
