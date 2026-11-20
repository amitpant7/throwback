package app.throwback.web.rules

import app.throwback.web.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    private fun errorsOf(json: String): List<String> =
        (RulesParser.parse(json) as? RulesParseResult.Invalid)?.errors ?: emptyList()

    private fun assertRejected(json: String, expectedFragment: String) {
        val errors = errorsOf(json)
        assertTrue("expected an error containing '$expectedFragment', got $errors", errors.any { expectedFragment in it })
    }

    /** The bundled rules with their classify table replaced by [entries]. */
    private fun withClassify(entries: String) =
        TestRules.bundledWith { it.replace(Regex("\"classify\": \\[.*?\\],"), "\"classify\": [$entries],") }

    @Test
    fun `bundled rules have a conservative notifications section`() {
        val n = TestRules.bundled.notifications
        assertNotNull(n)
        // Filled from the owner's phone (Instagram's channel list), calls first and never guessed.
        assertEquals("call", n!!.classify.first().kind)
        assertEquals("^ig_direct_video_chat$", n.classify.first().channel)
        assertEquals(UNKNOWN_CANCEL, n.unknown)
        assertNull(n.threadId)
        assertEquals("https://www.instagram.com/accounts/activity/", n.activityUrl)
        assertEquals(5, n.callGraceSeconds)
    }

    @Test
    fun `the section is optional`() {
        val text = TestRules.bundledWith { it.replace(Regex("\"notifications\": \\{.*?\"callGraceSeconds\": 5\\s*},", RegexOption.DOT_MATCHES_ALL), "") }
        val result = RulesParser.parse(text)
        assertTrue("$result", result is RulesParseResult.Valid)
        assertNull((result as RulesParseResult.Valid).rules.notifications)
    }

    @Test
    fun `a filled classify table parses`() {
        val text = withClassify(
            """{"kind": "message", "channel": "^ig_direct"}, {"kind": "likes", "category": "social", "ongoing": false}""",
        )
        val result = RulesParser.parse(text)
        assertTrue("$result", result is RulesParseResult.Valid)
        val rules = (result as RulesParseResult.Valid).rules.notifications!!
        assertEquals(2, rules.classify.size)
        assertEquals("^ig_direct", rules.classify[0].channel)
        assertEquals(false, rules.classify[1].ongoing)
    }

    @Test
    fun `broken classify entries are rejected`() {
        assertRejected(withClassify("""{"kind": "bogus", "channel": "x"}"""), "unknown kind")
        assertRejected(withClassify("""{"kind": "message"}"""), "matches everything")
        assertRejected(withClassify("""{"kind": "message", "channel": "(unclosed"}"""), "invalid pattern")
        assertRejected(withClassify("""{"kind": "message", "category": " "}"""), "bad category")
    }

    @Test
    fun `unknown handling, thread id and grace are checked`() {
        assertRejected(TestRules.bundledWith { it.replace("\"unknown\": \"cancel\"", "\"unknown\": \"relay\"") }, "notifications.unknown")
        assertRejected(
            TestRules.bundledWith { it.replace("\"threadId\": null", "\"threadId\": {\"from\": \"title\", \"pattern\": \"(\\\\d+)\"}") },
            "threadId.from",
        )
        assertRejected(
            TestRules.bundledWith { it.replace("\"threadId\": null", "\"threadId\": {\"from\": \"tag\", \"pattern\": \"\\\\d+\"}") },
            "needs a group",
        )
        assertRejected(TestRules.bundledWith { it.replace("\"callGraceSeconds\": 5", "\"callGraceSeconds\": 600") }, "callGraceSeconds")
    }

    @Test
    fun `the activity page must be an Instagram page the full mode shows`() {
        assertRejected(
            TestRules.bundledWith { it.replace("\"activityUrl\": \"https://www.instagram.com/accounts/activity/\"", "\"activityUrl\": \"https://example.com/\"") },
            "not an https Instagram URL",
        )
        assertRejected(
            TestRules.bundledWith { it.replace("\"activityUrl\": \"https://www.instagram.com/accounts/activity/\"", "\"activityUrl\": \"https://www.instagram.com/reels/\"") },
            "not allowed in FULL mode",
        )
    }
}
