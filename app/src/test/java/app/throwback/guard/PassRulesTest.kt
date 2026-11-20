package app.throwback.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassRulesTest {
    @Test
    fun `open Instagram for 5 minutes works once a day and never while locked`() {
        assertTrue(PassRules.visitAllowed(lastVisitDay = null, today = 200, locked = false))
        assertFalse(PassRules.visitAllowed(lastVisitDay = 200, today = 200, locked = false))
        assertTrue(PassRules.visitAllowed(lastVisitDay = 199, today = 200, locked = false))
        assertFalse(PassRules.visitAllowed(lastVisitDay = null, today = 200, locked = true))
    }

    @Test
    fun `call button allows 3 tries an hour`() {
        val h = 3_600_000L
        val now = 10 * h
        assertTrue(PassRules.callAllowed(listOf(now - 1000, now - 2000), now))
        assertFalse(PassRules.callAllowed(listOf(now - 1000, now - 2000, now - 3000), now))
        assertTrue(PassRules.callAllowed(listOf(now - h - 1, now - 2000, now - 3000), now)) // oldest is over an hour old
        assertEquals(2, PassRules.recentTaps(listOf(now - h - 1, now - 2000, now - 3000), now).size)
    }

    @Test
    fun `Android's photo picker and permission dialog are part of posting`() {
        assertTrue(PassRules.partOfInstagramFlow("com.google.android.providers.media.module"))
        assertTrue(PassRules.partOfInstagramFlow("com.google.android.permissioncontroller"))
        assertTrue(PassRules.partOfInstagramFlow("com.example.picker", setOf("com.example.picker")))
        assertFalse(PassRules.partOfInstagramFlow("com.whatsapp"))
    }

    @Test
    fun `story pass gives 5 minutes not counting camera time, 3 a day`() {
        assertTrue(PassRules.storyPassHolds(cameraOffMillis = 5 * 60_000L, limitMillis = 5 * 60_000L))
        assertFalse(PassRules.storyPassHolds(cameraOffMillis = 5 * 60_000L + 1, limitMillis = 5 * 60_000L))
        val m = 60_000L
        fun allowed(lastDay: Long?, count: Int, waitUntil: Long?, now: Long) =
            PassRules.storyAllowed(lastDay, count, 10, waitUntil, now, perDay = 3)
        assertTrue(allowed(null, 0, null, 0))
        assertFalse(allowed(10, 1, waitUntil = 30 * m, now = 29 * m)) // 30 minutes between story passes
        assertTrue(allowed(10, 1, waitUntil = 30 * m, now = 30 * m))
        assertFalse(allowed(10, 3, waitUntil = 30 * m, now = 300 * m)) // 3 a day
        assertTrue(allowed(9, 3, waitUntil = 30 * m, now = 300 * m)) // a new day
        assertFalse(allowed(10, 1, waitUntil = 130 * m, now = 50 * m)) // clock moved back: wait
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
