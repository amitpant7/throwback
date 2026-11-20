package app.throwback.guard

import org.junit.Assert.assertFalse
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
