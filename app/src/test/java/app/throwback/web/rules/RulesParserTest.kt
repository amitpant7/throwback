package app.throwback.web.rules

import app.throwback.web.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesParserTest {

    private fun errorsOf(json: String): List<String> =
        (RulesParser.parse(json) as? RulesParseResult.Invalid)?.errors ?: emptyList()

    private fun assertRejected(json: String, expectedFragment: String) {
        val errors = errorsOf(json)
        assertTrue("expected an error containing '$expectedFragment', got $errors", errors.any { expectedFragment in it })
    }

    @Test
    fun `bundled rules file is valid`() {
        val result = RulesParser.parse(TestRules.bundledText)
        assertTrue("filters/rules.json: $result", result is RulesParseResult.Valid)
        val rules = TestRules.bundled
        assertEquals(SUPPORTED_RULES_SCHEMA, rules.schema)
        assertEquals("https://www.instagram.com/", rules.homeUrl)
        assertTrue(rules.hide.isNotEmpty())
        assertTrue(rules.posts.labels.any { it.id == "sponsored" })
    }

    @Test
    fun `bundled rules place the settings section after 'Your account'`() {
        val section = TestRules.bundled.settingsSection
        assertNotNull(section)
        assertTrue(Regex(section!!.path).containsMatchIn("/accounts/settings/"))
        assertTrue("Your account" in section.after)
    }

    @Test
    fun `broken settings section rules are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"^/accounts/settings/?$\"", "\"^/accounts/(settings\"") }, "invalid pattern")
        assertRejected(
            TestRules.bundledWith { it.replace(Regex("\"after\": \\[[^\\]]*\\]"), "\"after\": []") },
            "text list size",
        )
    }

    @Test
    fun `bundled rules filter feed data by node kind`() {
        val fd = TestRules.bundled.feedData
        assertNotNull(fd)
        assertTrue("xdt_api__v1__feed__timeline__connection" in fd!!.connections)
        assertEquals(listOf("ad"), fd.adNodeFields)
        assertTrue("explore_story" in fd.suggestionNodeFields)
        // friendship_status.following is unreliable on the real feed: it must not hide posts.
        assertTrue(fd.suggestedMedia.none { it.path.contains("following") })
    }

    @Test
    fun `broken feed data rules are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"suggestionNodeFields\": [\"explore_story\"]", "\"suggestionNodeFields\": [\"explore story\"]") }, "bad field")
        assertRejected(TestRules.bundledWith { it.replace("{\"path\": \"ad_id\", \"present\": true}", "{\"path\": \"ad_id\"}") }, "exactly one of")
        assertRejected(TestRules.bundledWith { it.replace("\"endAfterEmptyPages\": 3", "\"endAfterEmptyPages\": 0") }, "endAfterEmptyPages")
    }

    @Test
    fun `reel viewer limit is bundled and range-checked`() {
        val rv = TestRules.bundled.reelViewer
        assertNotNull(rv)
        assertEquals(1, rv!!.posterOnSwipeMin)
        assertEquals(4, rv.posterOnSwipeMax)
        assertEquals(60, rv.cooldownMinutes)
        assertRejected(TestRules.bundledWith { it.replace("\"windowMinutes\": 10", "\"windowMinutes\": 0") }, "windowMinutes")
    }

    @Test
    fun `stories rules are bundled and range-checked`() {
        val st = TestRules.bundled.stories
        assertNotNull(st)
        assertTrue("xdt_injected_story_units" in st!!.adKeys)
        assertEquals(5, st.prefetchAhead)
        assertEquals(400, st.prefetchKB)
        assertRejected(TestRules.bundledWith { it.replace("\"prefetchKB\": 400", "\"prefetchKB\": 99999") }, "prefetchKB")
        assertRejected(TestRules.bundledWith { it.replace("\"xdt_injected_story_units\"", "\"bad key!\"") }, "bad field")
    }

    @Test
    fun `built-in fallback rules are valid`() {
        assertEquals(emptyList<String>(), RulesValidator.validate(FallbackRules.rules))
    }

    @Test
    fun `documentation fields and unknown fields are ignored`() {
        val json = TestRules.bundledWith { it.replaceFirst("\"schema\": 1,", "\"schema\": 1, \"futureField\": {\"x\": [1, 2]},") }
        assertTrue(RulesParser.parse(json) is RulesParseResult.Valid)
    }

    @Test
    fun `empty and malformed files are rejected`() {
        assertTrue(RulesParser.parse(null) is RulesParseResult.Invalid)
        assertTrue(RulesParser.parse("") is RulesParseResult.Invalid)
        assertTrue(RulesParser.parse("{ not json") is RulesParseResult.Invalid)
        assertTrue(RulesParser.parse("[]") is RulesParseResult.Invalid)
        // Valid JSON, but required fields are missing.
        assertTrue(RulesParser.parse("""{"schema": 1, "version": 5}""") is RulesParseResult.Invalid)
    }

    @Test
    fun `a different schema is rejected`() {
        assertRejected(TestRules.bundledWith { it.replaceFirst("\"schema\": 1", "\"schema\": 2") }, "unsupported schema")
    }

    @Test
    fun `version must be positive`() {
        assertRejected(TestRules.bundledWith { it.replaceFirst(Regex("\"version\": \\d+"), "\"version\": 0") }, "version")
    }

    @Test
    fun `invalid regex is rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"^/challenge(/|$)\",", "\"^/challenge(/|$\",") }, "invalid pattern")
    }

    @Test
    fun `selectors that could break out of the generated CSS are rejected`() {
        val json = TestRules.bundledWith {
            it.replace("\"selector\": \"a[href^='/explore/people']\"", "\"selector\": \"a{} body{display:none\"")
        }
        assertRejected(json, "forbidden character")
    }

    @Test
    fun `duplicate rule ids are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"id\": \"people-links\"", "\"id\": \"reels-links\"") }, "duplicate rule id")
    }

    @Test
    fun `unknown page in 'on' is rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"on\": \"explore\", \"style\"", "\"on\": \"nowhere\", \"style\"") }, "unknown 'on'")
    }

    @Test
    fun `protocol-relative redirect templates are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"redirect\": \"/\$1/\"", "\"redirect\": \"//\$1/\"") }, "bad redirect")
    }

    @Test
    fun `rules that would block the home feed are rejected`() {
        // A blocked pattern matching "/" would send every load of home back to home forever.
        val json = TestRules.bundledWith { it.replace("\"pattern\": \"^/explore/people(/|$)\"", "\"pattern\": \"^/$\"") }
        assertRejected(json, "redirect forever")
    }

    @Test
    fun `messages-only rules must allow the inbox`() {
        assertRejected(TestRules.bundledWith { it.replace("\"messagesOnly\": [\"^/direct(/|$)\"]", "\"messagesOnly\": [\"^/inbox/\"]") }, "redirect forever")
    }

    @Test
    fun `home must be an https Instagram URL`() {
        assertRejected(
            TestRules.bundledWith { it.replace("\"homeUrl\": \"https://www.instagram.com/\"", "\"homeUrl\": \"https://example.com/\"") },
            "not an https Instagram URL",
        )
        assertRejected(
            TestRules.bundledWith { it.replace("\"homeUrl\": \"https://www.instagram.com/\"", "\"homeUrl\": \"http://www.instagram.com/\"") },
            "not an https Instagram URL",
        )
    }

    @Test
    fun `bad hosts are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"instagram\": [\"instagram.com\",", "\"instagram\": [\"https://instagram.com\",") }, "bad host")
    }
}
