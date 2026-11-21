package app.throwback.connect.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassRulesTest {
    @Test
    fun `Android's photo picker and permission dialog are part of posting`() {
        assertTrue(PassRules.partOfInstagramFlow("com.google.android.providers.media.module"))
        assertTrue(PassRules.partOfInstagramFlow("com.google.android.permissioncontroller"))
        assertTrue(PassRules.partOfInstagramFlow("com.example.picker", setOf("com.example.picker")))
        assertFalse(PassRules.partOfInstagramFlow("com.whatsapp"))
    }

    @Test
    fun `story pass gives 5 minutes not counting camera time`() {
        assertTrue(PassRules.storyPassHolds(cameraOffMillis = 5 * 60_000L, limitMillis = 5 * 60_000L))
        assertFalse(PassRules.storyPassHolds(cameraOffMillis = 5 * 60_000L + 1, limitMillis = 5 * 60_000L))
    }

    @Test
    fun `a create pass holds only on a creation screen, and fails closed`() {
        val screens = PassRules.screensPattern("^instagram\\.features\\.creation\\.")
        val editor = "instagram.features.creation.capture.MediaCaptureActivity"
        val feed = "com.instagram.mainactivity.InstagramMainActivity"
        val later = PassRules.CREATE_GRACE_MS
        assertTrue(PassRules.createPassHolds(editor, screens, later))
        assertFalse(PassRules.createPassHolds(feed, screens, later))
        assertTrue(PassRules.createPassHolds(feed, screens, later - 1)) // the editor is still opening
        // No usable pattern or no known screen: only that grace (it allowed the whole app before).
        assertFalse(PassRules.createPassHolds(editor, null, later))
        assertFalse(PassRules.createPassHolds(null, screens, later))
        assertFalse(PassRules.createPassHolds(feed, PassRules.screensPattern(""), later))
    }

    @Test
    fun `a screens pattern that matches every screen is not used`() {
        assertNull(PassRules.screensPattern(""))
        assertNull(PassRules.screensPattern(".*"))
        assertNull(PassRules.screensPattern("x|"))
        assertNull(PassRules.screensPattern("[")) // invalid
        assertNull(PassRules.screensPattern(null))
        val settings = PassRules.screensPattern("^com\\.instagram\\.urlhandlers\\.settings\\.")
        assertTrue(PassRules.onScreens("com.instagram.urlhandlers.settings.SettingsUrlHandlerActivity", settings))
        assertFalse(PassRules.onScreens("com.instagram.mainactivity.InstagramMainActivity", settings))
    }

    @Test
    fun `a story pass left early is forgiven only if it showed little but the camera`() {
        val m = 60_000L
        fun leftEarly(since: Long, cameraOff: Long) = PassRules.storyLeftEarly(since, cameraOff, freeLeaveMillis = 3 * m)
        assertTrue(leftEarly(since = 2 * m, cameraOff = 0)) // opened the camera, changed their mind
        assertTrue(leftEarly(since = 2 * m + 50_000, cameraOff = 30_000)) // a few seconds on the feed first
        // The loop the review found: "Your story", scroll the feed for ~2:50, Home, again.
        assertFalse(leftEarly(since = 2 * m + 50_000, cameraOff = 2 * m + 45_000))
        assertFalse(leftEarly(since = 3 * m, cameraOff = 0)) // stayed past the free-leave minutes
    }
}
