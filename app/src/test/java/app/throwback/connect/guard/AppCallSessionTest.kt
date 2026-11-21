package app.throwback.connect.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCallSessionTest {

    @Test
    fun callScreenWithVoiceSoundStartsACall() {
        val s = AppCallSession()
        assertTrue(s.update(1_000, callScreenShown = true, voiceAudio = true))
    }

    @Test
    fun callScreenAloneOrVoiceSoundAloneIsNotACall() {
        val s = AppCallSession()
        assertFalse(s.update(1_000, callScreenShown = true, voiceAudio = false))
        // Another app's internet call (no Instagram call screen) never starts one.
        assertFalse(s.update(2_000, callScreenShown = false, voiceAudio = true))
    }

    @Test
    fun theCallLastsWhileItsSoundPlaysAfterItsScreenIsGone() {
        val s = AppCallSession()
        s.update(1_000, callScreenShown = true, voiceAudio = true)
        // Minimised: the call screen is gone, the call goes on.
        assertTrue(s.update(30_000, callScreenShown = false, voiceAudio = true))
        assertTrue(s.update(60_000, callScreenShown = false, voiceAudio = true))
    }

    @Test
    fun aShortSilenceKeepsTheCallAndALongerOneEndsIt() {
        val s = AppCallSession()
        s.update(1_000, callScreenShown = true, voiceAudio = true)
        assertTrue(s.update(1_000 + AppCallSession.GAP_MS, callScreenShown = false, voiceAudio = false))
        assertFalse(s.update(1_001 + AppCallSession.GAP_MS, callScreenShown = false, voiceAudio = false))
        // Over: voice sound alone (say, a WhatsApp call) doesn't bring it back.
        assertFalse(s.update(10_000, callScreenShown = false, voiceAudio = true))
    }
}
