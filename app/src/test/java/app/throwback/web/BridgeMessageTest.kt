package app.throwback.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeMessageTest {

    @Test
    fun `parses every message type`() {
        assertEquals(BridgeMessage.Location("https://www.instagram.com/direct/inbox/"), BridgeMessage.parse("""{"t":"loc","url":"https://www.instagram.com/direct/inbox/"}"""))
        assertEquals(BridgeMessage.Ready, BridgeMessage.parse("""{"t":"ready"}"""))
        assertEquals(BridgeMessage.Blocked("reels"), BridgeMessage.parse("""{"t":"blocked","kind":"reels"}"""))
        assertEquals(BridgeMessage.Blocked("hidden"), BridgeMessage.parse("""{"t":"blocked"}"""))
        assertEquals(BridgeMessage.Touch(true), BridgeMessage.parse("""{"t":"touch","top":true}"""))
        assertEquals(BridgeMessage.Touch(false), BridgeMessage.parse("""{"t":"touch","top":false}"""))
        assertEquals(BridgeMessage.Background(0xFF000000.toInt()), BridgeMessage.parse("""{"t":"bg","c":"rgb(0, 0, 0)"}"""))
    }

    @Test
    fun `ignores anything malformed`() {
        listOf(
            null, "", "nope", "[]", "{}", """{"t":"unknown"}""", """{"t":"loc"}""", """{"t":"loc","url":5}""",
            """{"t":"touch","top":"yes"}""", """{"t":"bg","c":"rgba(0, 0, 0, 0)"}""", """{"t":1}""",
            "{\"t\":\"loc\",\"url\":\"" + "x".repeat(10_000) + "\"}",
        ).forEach { assertNull(it, BridgeMessage.parse(it)) }
    }

    @Test
    fun `parses computed css colours`() {
        assertEquals(0xFFFFFFFF.toInt(), BridgeMessage.parseCssColor("rgb(255, 255, 255)"))
        assertEquals(0xFF102030.toInt(), BridgeMessage.parseCssColor("rgb(16, 32, 48)"))
        assertEquals(0xFF102030.toInt(), BridgeMessage.parseCssColor("rgba(16, 32, 48, 1)"))
        assertEquals(0xFF102030.toInt(), BridgeMessage.parseCssColor("rgb(16 32 48 / 100%)"))
        assertEquals(0xFFFFFFFF.toInt(), BridgeMessage.parseCssColor("rgb(300, 255, 255)"))
    }

    @Test
    fun `translucent and unknown colours are ignored`() {
        assertNull(BridgeMessage.parseCssColor("rgba(0, 0, 0, 0)"))
        assertNull(BridgeMessage.parseCssColor("rgba(255, 255, 255, 0.5)"))
        assertNull(BridgeMessage.parseCssColor("rgb(16 32 48 / 50%)"))
        assertNull(BridgeMessage.parseCssColor("transparent"))
        assertNull(BridgeMessage.parseCssColor("#ffffff"))
        assertNull(BridgeMessage.parseCssColor("color(srgb 1 1 1)"))
    }

    @Test
    fun `reel limit message parses`() {
        assertEquals(BridgeMessage.ReelLimit, BridgeMessage.parse("""{"t":"reelLimit"}"""))
    }

    @Test
    fun `call button message parses, with a safe chat id only`() {
        assertEquals(BridgeMessage.CallInApp("340282366841710300949128"), BridgeMessage.parse("""{"t":"callInApp","thread":"340282366841710300949128"}"""))
        assertEquals(BridgeMessage.CallInApp(null), BridgeMessage.parse("""{"t":"callInApp","thread":"../evil"}"""))
        assertEquals(BridgeMessage.CallInApp(null), BridgeMessage.parse("""{"t":"callInApp"}"""))
        assertEquals(BridgeMessage.CallInApp("12345", "some.user_1"), BridgeMessage.parse("""{"t":"callInApp","thread":"12345","user":"some.user_1"}"""))
        assertEquals(BridgeMessage.CallInApp("12345", null), BridgeMessage.parse("""{"t":"callInApp","thread":"12345","user":"x/../y"}"""))
    }
}
