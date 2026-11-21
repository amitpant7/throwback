package app.throwback.connect.relay

import app.throwback.web.rules.ThreadIdRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.throwback.connect.InstagramPackages

class RelayLinksTest {

    private fun facts(tag: String? = null, shortcut: String? = null, group: String? = null) =
        NotificationFacts(key = "k", packageName = InstagramPackages.APP, tag = tag, shortcutId = shortcut, group = group)

    @Test
    fun `thread id from the configured field`() {
        val id = "340282366841710300949128123456789012345"
        assertEquals(
            "https://www.instagram.com/direct/t/$id/",
            RelayLinks.threadUrl(facts(shortcut = "direct_$id"), ThreadIdRule("shortcut", "direct_(\\d+)")),
        )
        assertEquals("https://www.instagram.com/direct/t/12345/", RelayLinks.threadUrl(facts(tag = "t:12345"), ThreadIdRule("tag", "t:(\\d+)")))
        assertEquals("https://www.instagram.com/direct/t/987654/", RelayLinks.threadUrl(facts(group = "g987654"), ThreadIdRule("group", "g(\\d+)")))
    }

    @Test
    fun `anything doubtful falls back to the inbox`() {
        assertNull(RelayLinks.threadUrl(facts(tag = "t:12345"), null))
        assertNull(RelayLinks.threadUrl(facts(tag = null), ThreadIdRule("tag", "t:(\\d+)")))
        assertNull(RelayLinks.threadUrl(facts(tag = "t:123"), ThreadIdRule("tag", "t:(\\d+)"))) // too short
        assertNull(RelayLinks.threadUrl(facts(tag = "t:12a45/../x"), ThreadIdRule("tag", "t:(.+)"))) // not digits
        assertNull(RelayLinks.threadUrl(facts(tag = "t:12345"), ThreadIdRule("title", "t:(\\d+)")))
        assertNull(RelayLinks.threadUrl(facts(tag = "t:12345"), ThreadIdRule("tag", "(broken")))
    }

    @Test
    fun `a repeated link still differs`() {
        val inbox = "https://www.instagram.com/direct/inbox/"
        assertEquals(inbox, RelayLinks.repeatable(inbox, null))
        assertEquals("$inbox#", RelayLinks.repeatable(inbox, inbox))
        assertEquals(inbox, RelayLinks.repeatable(inbox, "$inbox#"))
        assertEquals(inbox, RelayLinks.repeatable(inbox, "https://www.instagram.com/p/abc/"))
    }
}
