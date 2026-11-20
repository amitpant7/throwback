package app.throwback.timelimit

import app.throwback.timelimit.engine.PinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PinHasherTest {

    private val fast = PinHasher(iterations = 1_000)

    @Test
    fun `right PIN verifies, wrong PIN doesn't`() {
        val h = fast.hash("4821")
        assertTrue(fast.verify("4821", h))
        assertFalse(fast.verify("4812", h))
        assertFalse(fast.verify("0000", h))
    }

    @Test
    fun `default settings use PBKDF2 with a 16-byte random salt and a 256-bit hash`() {
        val hasher = PinHasher()
        val h = hasher.hash("1234")
        assertEquals(PinHasher.DEFAULT_ITERATIONS, h.iterations)
        assertTrue(h.iterations >= 100_000)
        assertEquals(16, Base64.getDecoder().decode(h.salt).size)
        assertEquals(32, Base64.getDecoder().decode(h.hash).size)
        assertTrue(hasher.verify("1234", h))
        assertFalse(hasher.verify("1235", h))
    }

    @Test
    fun `same PIN gets a different salt and hash every time`() {
        val a = fast.hash("1111")
        val b = fast.hash("1111")
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
        assertTrue(fast.verify("1111", a))
        assertTrue(fast.verify("1111", b))
    }

    @Test
    fun `the PIN itself appears nowhere in what is stored`() {
        val h = fast.hash("2580")
        val stored = listOf(h.salt, h.hash, h.toString())
        assertTrue(stored.none { "2580" in it })
    }

    @Test
    fun `verification uses the stored iteration count`() {
        val old = PinHasher(iterations = 500).hash("9999")
        assertTrue(fast.verify("9999", old))
    }

    @Test
    fun `only four digits are PINs`() {
        assertTrue(PinHasher.isValidPin("0123"))
        listOf("", "123", "12345", "12a4", "１２３４", " 123").forEach { assertFalse(it, PinHasher.isValidPin(it)) }
        assertFalse(fast.verify("12345", fast.hash("1234")))
    }

    @Test
    fun `damaged stored values never verify`() {
        val h = fast.hash("1234")
        assertFalse(fast.verify("1234", h.copy(hash = "not base64!")))
        assertFalse(fast.verify("1234", h.copy(salt = "%%%")))
        assertFalse(fast.verify("1234", h.copy(iterations = 0)))
        assertFalse(fast.verify("1234", h.copy(hash = fast.hash("1234").hash)))
    }
}
