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
        val js = TestRules.mainAsset("inject/throwback.js").readText()
        assertEquals(1, Regex(PageScript.CONFIG_TOKEN).findAll(js).count())
        assertTrue(TestRules.mainAsset("inject/throwback.css").isFile)
    }

    @Test
    fun `build substitutes mode, css and rules`() {
        val script = PageScript.build("(function(c){})(${PageScript.CONFIG_TOKEN});", "a{color:red}", rules, WebMode.MESSAGES_ONLY)
        assertFalse(PageScript.CONFIG_TOKEN in script)
        val json = script.removePrefix("(function(c){})(").removeSuffix(");")
        val config = Json.parseToJsonElement(json).jsonObject
        assertEquals("MESSAGES_ONLY", config["mode"]!!.jsonPrimitive.content)
        assertEquals("a{color:red}", config["css"]!!.jsonPrimitive.content)
        val encodedRules = config["rules"]!!.jsonObject
        assertEquals(rules.homeUrl, encodedRules["homeUrl"]!!.jsonPrimitive.content)
        // Documentation is not shipped into the page.
        assertFalse("\"note\"" in json)
        assertFalse("\"about\"" in json)
    }

    @Test
    fun `partial config for live updates`() {
        assertEquals("""{"mode":"FULL"}""", PageScript.config(mode = WebMode.FULL))
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
        PageScript.build("no token", "", rules, WebMode.FULL)
    }
}
