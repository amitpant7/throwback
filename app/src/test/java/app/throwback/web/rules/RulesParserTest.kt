package app.throwback.web.rules

import app.throwback.web.TestRules
import org.junit.Assert.assertEquals
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
        assertEquals("https://www.instagram.com/?variant=following", rules.homeUrl)
        assertTrue(rules.hide.isNotEmpty())
        assertTrue(rules.posts.labels.any { it.id == "sponsored" })
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
        assertRejected(TestRules.bundledWith { it.replaceFirst("\"version\": 2026112001", "\"version\": 0") }, "version")
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
            TestRules.bundledWith { it.replace("\"homeUrl\": \"https://www.instagram.com/?variant=following\"", "\"homeUrl\": \"https://example.com/\"") },
            "not an https Instagram URL",
        )
        assertRejected(
            TestRules.bundledWith { it.replace("\"homeUrl\": \"https://www.instagram.com/?variant=following\"", "\"homeUrl\": \"http://www.instagram.com/?variant=following\"") },
            "not an https Instagram URL",
        )
    }

    @Test
    fun `bad hosts are rejected`() {
        assertRejected(TestRules.bundledWith { it.replace("\"instagram\": [\"instagram.com\",", "\"instagram\": [\"https://instagram.com\",") }, "bad host")
    }
}
