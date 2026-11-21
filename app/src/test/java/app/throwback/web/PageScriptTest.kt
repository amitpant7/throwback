package app.throwback.web

import app.throwback.core.WebMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScriptTest {

    private val rules = TestRules.bundled

    @Test
    fun `the real page script has the config token exactly once`() {
        val js = TestRules.pageScript
        assertEquals(1, Regex(PageScript.CONFIG_TOKEN).findAll(js).count())
        assertTrue(TestRules.mainAsset("inject/throwback.css").isFile)
    }

    @Test
    fun `CSS written by the page script works on old WebViews`() {
        // Old WebViews get the script the fallback way (pagescript/README.md): no inset (Chrome 87)
        // and no min()/max()/clamp() (Chrome 79) in the styles it writes.
        val js = TestRules.pageScript
        assertEquals(emptyList<String>(), Regex("[;{\"']\\s*inset\\s*:").findAll(js).map { it.value }.toList())
        assertEquals(emptyList<String>(), Regex("(?<![.\\w])(min|max|clamp)\\(").findAll(js).map { it.value }.toList())
    }

    @Test
    fun `build substitutes mode, css and rules`() {
        val features = PageFeatures(createInApp = true, storyInApp = true, supportAsk = "again")
        val script = PageScript.build("(function(c){})(${PageScript.CONFIG_TOKEN});", "a{color:red}", rules, WebMode.MESSAGES_ONLY, clock = null, features)
        assertFalse(PageScript.CONFIG_TOKEN in script)
        val json = script.removePrefix("(function(c){})(").removeSuffix(");")
        val config = Json.parseToJsonElement(json).jsonObject
        assertEquals("MESSAGES_ONLY", config["mode"]!!.jsonPrimitive.content)
        // A new document starts with the app's features as they are now.
        assertEquals("true", config["createInApp"]!!.jsonPrimitive.content)
        assertEquals("true", config["storyInApp"]!!.jsonPrimitive.content)
        assertEquals("again", config["supportAsk"]!!.jsonPrimitive.content)
        assertEquals("a{color:red}", config["css"]!!.jsonPrimitive.content)
        val encodedRules = config["rules"]!!.jsonObject
        assertEquals(rules.homeUrl, encodedRules["homeUrl"]!!.jsonPrimitive.content)
        // Documentation is not shipped into the page.
        assertFalse("\"note\"" in json)
        assertFalse("\"about\"" in json)
    }

    @Test
    fun `rules are encoded once per rules instance, never served stale`() {
        val first = PageScript.config(rules = rules)
        assertEquals(first, PageScript.config(rules = rules))
        val newer = rules.copy(version = rules.version + 1)
        assertTrue("\"version\":${rules.version + 1}" in PageScript.config(rules = newer))
        assertEquals(first, PageScript.config(rules = rules))
    }

    @Test
    fun `partial config for live updates`() {
        // Only what changed: the page keeps the rest.
        assertEquals("""{"mode":"FULL"}""", PageScript.config(mode = WebMode.FULL))
        // The page reads exactly these keys.
        assertEquals(
            """{"createInApp":true,"storyInApp":false,"supportAsk":"first"}""",
            PageScript.config(features = PageFeatures(createInApp = true, supportAsk = "first")),
        )
        assertEquals("""{"createInApp":false,"storyInApp":false,"supportAsk":null}""", PageScript.config(features = PageFeatures()))
    }

    @Test
    fun `line and paragraph separators are escaped for old JavaScript engines`() {
        val config = PageScript.config(css = "a b c")
        assertFalse(' ' in config)
        assertFalse(' ' in config)
        assertTrue("\\u2028" in config)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a template without the token is refused`() {
        PageScript.build("no token", "", rules, WebMode.FULL, clock = null, PageFeatures())
    }

    @Test
    fun `the trusted clock goes along only when known, with the moment it was read`() {
        val clock = PageClock.Reading(offsetMs = -1500, zoneOffsetMin = 60, day = 20412, nextDayAt = 1_763_683_200_000, readAt = 1_763_650_000_123)
        assertEquals("""{"offset":-1500,"zoneMin":60,"day":20412,"nextAt":1763683200000,"at":1763650000123}""", clock.json().toString())

        val withClock = Json.parseToJsonElement(PageScript.config(clock = clock.json())).jsonObject
        assertEquals(clock.json(), withClock["clock"])
        assertFalse("clock" in Json.parseToJsonElement(PageScript.config(mode = WebMode.FULL)).jsonObject)
        val script = PageScript.build("(${PageScript.CONFIG_TOKEN})", "", rules, WebMode.FULL, clock = null, PageFeatures())
        assertFalse("\"clock\"" in script)
    }

    @Test
    fun `features change through one state, sent to the page under the names it reads`() {
        val before = PageFeatures.current.value
        try {
            PageFeatures.current.value = PageFeatures(createInApp = true, storyInApp = true, supportAsk = "first")
            val fields = PageFeatures.current.value.configFields()
            assertEquals(listOf("createInApp", "storyInApp", "supportAsk"), fields.keys.toList())
            assertEquals("true", fields["storyInApp"].toString())
        } finally {
            PageFeatures.current.value = before
        }
    }

    @Test
    fun `no clock while the trusted one is unknown`() {
        val previous = PageClock.provider
        try {
            PageClock.provider = null
            assertEquals(null, PageClock.json())
            PageClock.provider = { null } // the time limit hasn't loaded its saved state yet
            assertEquals(null, PageClock.json())
            PageClock.provider = { PageClock.Reading(0, 0, 1, 2, 3) }
            assertEquals(3L, PageClock.json()!!["at"]!!.jsonPrimitive.content.toLong())
        } finally {
            PageClock.provider = previous
        }
    }
}
