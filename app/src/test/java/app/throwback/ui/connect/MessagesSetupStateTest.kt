package app.throwback.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesSetupStateTest {

    private fun state(pkg: String? = "com.instagram.android", n: Boolean = true, u: Boolean = true, o: Boolean = true) =
        MessagesSetupState(instagramPackage = pkg, notificationAccess = n, usageAccess = u, drawOverlays = o)

    @Test
    fun `ready only with the app and all three permissions`() {
        assertEquals(MessagesStatus.Ready, state().status)
    }

    @Test
    fun `missing app wins over missing permissions`() {
        assertEquals(MessagesStatus.InstagramMissing, state(pkg = null, n = false).status)
    }

    @Test
    fun `counts missing permissions`() {
        assertEquals(MessagesStatus.PermissionsMissing(1), state(n = false).status)
        assertEquals(MessagesStatus.PermissionsMissing(2), state(n = false, o = false).status)
        assertEquals(MessagesStatus.PermissionsMissing(3), state(n = false, u = false, o = false).status)
        assertEquals(1, state(n = false, u = false).permissionsGranted)
    }

    @Test
    fun `Android 13's notification permission counts as missing`() {
        val noPost = MessagesSetupState("com.instagram.android", true, true, true, postNotifications = false)
        assertEquals(MessagesStatus.PermissionsMissing(1), noPost.status)
        assertEquals(MessagesStatus.PermissionsMissing(2), noPost.copy(usageAccess = false).status)
    }

    @Test
    fun `wizard steps run in order`() {
        assertEquals(2, WizardStep.COUNT)
        assertEquals(WizardStep.Permissions, WizardStep.Install.next())
        assertNull(WizardStep.Permissions.next())
        assertNull(WizardStep.Install.previous())
        assertEquals(listOf(1, 2), WizardStep.entries.map { it.number })
    }
}
