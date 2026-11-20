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
        assertTrue(PassRules.storyAllowed(lastDay = 9, countThatDay = 3, today = 10))
        assertTrue(PassRules.storyAllowed(lastDay = 10, countThatDay = 2, today = 10))
        assertFalse(PassRules.storyAllowed(lastDay = 10, countThatDay = 3, today = 10))
    }
}
