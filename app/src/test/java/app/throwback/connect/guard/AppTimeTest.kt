package app.throwback.connect.guard

import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_PAUSED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_RESUMED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_STOPPED
import app.throwback.connect.InstagramPackages
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Instagram app's time while the guard was off, from usage events (DECISIONS.md #27b). */
class AppTimeTest {
    private val ig = InstagramPackages.APP
    private val lite = InstagramPackages.LITE
    private val watched = InstagramPackages.ALL
    private val feed = "com.instagram.mainactivity.InstagramMainActivity"
    private val chat = "com.instagram.direct.DirectActivity"

    private fun e(time: Long, type: Int, pkg: String = ig, cls: String? = feed) = ActivityEvent(time, type, pkg, cls)

    @Test
    fun `counts from resumed to paused`() {
        val events = listOf(e(10, ACTIVITY_RESUMED), e(40, ACTIVITY_PAUSED), e(41, ACTIVITY_STOPPED))
        assertEquals(30L, AppTime.inFront(events, watched, from = 0, to = 100))
    }

    @Test
    fun `screens within Instagram add up, other apps don't count`() {
        val events = listOf(
            e(10, ACTIVITY_RESUMED),
            e(20, ACTIVITY_PAUSED),
            e(21, ACTIVITY_RESUMED, cls = chat),
            e(22, ACTIVITY_STOPPED),
            e(30, ACTIVITY_PAUSED, cls = chat),
            e(31, ACTIVITY_RESUMED, pkg = "com.whatsapp", cls = "Home"),
            e(60, ACTIVITY_PAUSED, pkg = "com.whatsapp", cls = "Home"),
        )
        assertEquals(19L, AppTime.inFront(events, watched, from = 0, to = 100))
    }

    @Test
    fun `only the part between from and to counts`() {
        // Already in front before the guard's last beat; still in front now.
        assertEquals(20L, AppTime.inFront(listOf(e(-50, ACTIVITY_RESUMED), e(20, ACTIVITY_PAUSED)), watched, from = 0, to = 100))
        assertEquals(20L, AppTime.inFront(listOf(e(80, ACTIVITY_RESUMED)), watched, from = 0, to = 100))
        assertEquals(0L, AppTime.inFront(listOf(e(-50, ACTIVITY_RESUMED), e(-20, ACTIVITY_PAUSED)), watched, from = 0, to = 100))
    }

    @Test
    fun `two Instagram windows at once count once`() {
        val events = listOf(
            e(10, ACTIVITY_RESUMED),
            e(20, ACTIVITY_RESUMED, pkg = lite, cls = "com.instagram.lite.MainActivity"),
            e(30, ACTIVITY_PAUSED),
            e(50, ACTIVITY_DESTROYED, pkg = lite, cls = "com.instagram.lite.MainActivity"),
        )
        assertEquals(40L, AppTime.inFront(events, watched, from = 0, to = 100))
    }

    @Test
    fun `Instagram's call screens don't count (calls never count)`() {
        val call = "com.instagram.rtc.activity.RtcCallActivity"
        val events = listOf(
            e(0, ACTIVITY_RESUMED), e(10, ACTIVITY_PAUSED), // 10 on the feed
            e(10, ACTIVITY_RESUMED, cls = call), e(60, ACTIVITY_PAUSED, cls = call), // a 50 call
            e(60, ACTIVITY_RESUMED), e(70, ACTIVITY_PAUSED), // 10 more on the feed
        )
        val callScreens = PassRules.screensPattern("^com\\.instagram\\.rtc\\.")
        assertEquals(20L, AppTime.inFront(events, watched, from = 0, to = 100, notCounted = callScreens))
        assertEquals(70L, AppTime.inFront(events, watched, from = 0, to = 100))
    }
}
