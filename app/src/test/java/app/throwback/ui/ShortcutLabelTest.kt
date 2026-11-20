package app.throwback.ui

import app.throwback.ui.shortcut.ShortcutLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutLabelTest {

    @Test
    fun `blank names fall back to the default`() {
        assertEquals("Throwback", ShortcutLabel.sanitize("", "Throwback"))
        assertEquals("Throwback", ShortcutLabel.sanitize("   \n ", "Throwback"))
    }

    @Test
    fun `whitespace is tidied`() {
        assertEquals("My Insta", ShortcutLabel.sanitize("  My   Insta \n", "Throwback"))
    }

    @Test
    fun `the user may name it anything, including Instagram`() {
        assertEquals("Instagram", ShortcutLabel.sanitize("Instagram", "Throwback"))
    }

    @Test
    fun `long names are capped`() {
        val label = ShortcutLabel.sanitize("a".repeat(60), "Throwback")
        assertEquals(ShortcutLabel.MAX_LENGTH, label.length)
    }
}
