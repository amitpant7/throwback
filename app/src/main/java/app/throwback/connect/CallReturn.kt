package app.throwback.connect

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import app.throwback.connect.guard.CallAudioLink
import app.throwback.connect.relay.RelayListenerService

/**
 * Back to an Instagram call from Throwback (DECISIONS.md #34x). During a call only Instagram's call
 * screens are allowed, so a call minimised to its chat gets covered and the user lands in Throwback,
 * away from the call. The "Back to call" bar opens it again through Instagram's own call
 * notification, whose tap goes to the call screen (allowed during the call).
 */
internal object CallReturn {

    /**
     * An Instagram call runs that a tap can go back to: a call notification, or an ongoing one with
     * call audio (not, say, an upload's progress), whose tap action is there.
     */
    fun available(context: Context, calls: CallSnapshot = CallState.tracker.state.value): Boolean {
        val callAudio = CallAudioLink.isCallAudio(context.getSystemService(AudioManager::class.java)?.mode)
        return (calls.sureCall || (calls.inCall && callAudio)) && RelayListenerService.callTapAction(calls) != null
    }

    /** Opens the call screen; false if Instagram's notification can't (the caller says so). */
    fun open(context: Context): Boolean {
        val action = RelayListenerService.callTapAction(CallState.tracker.state.value) ?: return false
        // Android 14+: the tap is Instagram's PendingIntent, sent from Throwback in front, which must
        // lend its permission to start an activity.
        val options = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(@Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            else -> null
        }
        return try {
            action.send(context, 0, null, null, null, null, options?.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }
}
