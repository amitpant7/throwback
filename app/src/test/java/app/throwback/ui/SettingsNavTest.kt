package app.throwback.ui

import app.throwback.ui.settings.SettingsNav
import app.throwback.ui.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavTest {

    @Test
    fun `start points`() {
        assertEquals(listOf(SettingsRoute.Home), SettingsNav.initial(SettingsStart.Home))
        // From "Instagram app found": the wizard is the root, so back leaves Settings.
        assertEquals(listOf(SettingsRoute.Messages), SettingsNav.initial(SettingsStart.MessagesAndCalls))
    }

    @Test
    fun `back pops one level and returns null at the root`() {
        val stack = SettingsNav.push(listOf(SettingsRoute.Home), SettingsRoute.About)
        assertEquals(listOf(SettingsRoute.Home, SettingsRoute.About), stack)
        assertEquals(listOf(SettingsRoute.Home), SettingsNav.pop(stack))
        assertNull(SettingsNav.pop(listOf(SettingsRoute.Home)))
        assertNull(SettingsNav.pop(emptyList()))
    }

    @Test
    fun `double tap does not push the same screen twice`() {
        val once = SettingsNav.push(listOf(SettingsRoute.Home), SettingsRoute.Shortcut)
        assertEquals(once, SettingsNav.push(once, SettingsRoute.Shortcut))
    }

    @Test
    fun `save and restore round trip, unknown names are dropped`() {
        val stack = listOf(SettingsRoute.Home, SettingsRoute.Messages, SettingsRoute.MessagesWizard)
        assertEquals(stack, SettingsNav.restore(SettingsNav.save(stack)))
        assertEquals(listOf(SettingsRoute.Home, SettingsRoute.About), SettingsNav.restore(listOf("Home", "Gone", "About")))
        assertEquals(listOf(SettingsRoute.Home), SettingsNav.restore(emptyList()))
    }

    @Test
    fun `keys differ per depth`() {
        assertNotEquals(
            SettingsNav.key(listOf(SettingsRoute.Messages)),
            SettingsNav.key(listOf(SettingsRoute.Home, SettingsRoute.Messages)),
        )
    }
}
