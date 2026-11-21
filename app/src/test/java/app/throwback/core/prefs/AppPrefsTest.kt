package app.throwback.core.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newPrefs(file: File = File(tmp.root, "ui_prefs.preferences_pb")) =
        AppPrefs(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))

    @Test
    fun `everything is off by default, except the mindful pause`() = runTest {
        val prefs = newPrefs()
        assertFalse(prefs.onboardingDone.first())
        assertTrue(prefs.mindfulPause.first()) // on by default (owner, 2026-11-20)
        assertFalse(prefs.instagramPromptShown.first())
        assertFalse(prefs.messagesSetupDone.first())
        assertEquals(emptySet<ExtraNotification>(), prefs.extraNotifications.first())
    }

    @Test
    fun `setters persist`() = runTest {
        val prefs = newPrefs()
        prefs.setOnboardingDone()
        prefs.setMindfulPause(true)
        prefs.setInstagramPromptShown()
        prefs.setMessagesSetupDone(true)

        assertTrue(prefs.onboardingDone.first())
        assertTrue(prefs.mindfulPause.first())
        assertTrue(prefs.instagramPromptShown.first())
        assertTrue(prefs.messagesSetupDone.first())

        prefs.setMindfulPause(false)
        prefs.setMessagesSetupDone(false)
        assertFalse(prefs.mindfulPause.first())
        assertFalse(prefs.messagesSetupDone.first())
        // Unrelated flags are untouched.
        assertTrue(prefs.onboardingDone.first())
    }

    @Test
    fun `extra notification toggles are independent`() = runTest {
        val prefs = newPrefs()
        prefs.setExtraNotification(ExtraNotification.COMMENTS, true)
        prefs.setExtraNotification(ExtraNotification.LIVE_VIDEOS, true)
        assertEquals(
            setOf(ExtraNotification.COMMENTS, ExtraNotification.LIVE_VIDEOS),
            prefs.extraNotifications.first(),
        )

        prefs.setExtraNotification(ExtraNotification.COMMENTS, false)
        assertEquals(setOf(ExtraNotification.LIVE_VIDEOS), prefs.extraNotifications.first())
    }

    @Test
    fun `extra notification keys are unique`() {
        val keys = ExtraNotification.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `file name matches the architecture doc`() {
        assertEquals("ui_prefs", AppPrefs.FILE_NAME)
    }
}
