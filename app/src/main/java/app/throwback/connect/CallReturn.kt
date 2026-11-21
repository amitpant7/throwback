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
 * away from the call. The "Back to your Instagram call" bar opens it again: through Instagram's own
 * call notification, whose tap goes to the call screen; or, where that notification doesn't show
 * (its channel turned off), by reopening the chat the call started from (CallInApp.lastChat), which
 * the guard allows during a call, where Instagram's own banner returns to the call. A pass during a
 * call only bridges the seconds the chat takes to open (GuardPolicy.CALL_PASS_BRIDGE_MS).
 */
internal object CallReturn {

    /**
     * An Instagram call runs that a tap can go back to: one the guard recognised by its screen and
     * sound, or a call notification (or an ongoing one with call audio, not an upload's progress)
     * whose tap action is there.
     */
    fun available(context: Context, calls: CallSnapshot = CallState.tracker.state.value): Boolean {
        if (CallState.appCall.value) return true
        val callAudio = CallAudioLink.isCallAudio(context.getSystemService(AudioManager::class.java)?.mode)
        return (calls.sureCall || (calls.inCall && callAudio)) && RelayListenerService.callTapAction(calls) != null
    }

    /**
     * Opens the call screen through Instagram's notification, else the call's chat. A call runs, so
     * this isn't counted as a call-button tap: getting back to a call is never limited (#20).
     */
    fun open(context: Context): CallInApp.Result {
        val action = RelayListenerService.callTapAction(CallState.tracker.state.value)
            ?: return openChat(context)
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
            CallInApp.Result.OPENED
        } catch (e: PendingIntent.CanceledException) {
            openChat(context)
        }
    }

    /** The chat the call started from, else Instagram as it was (a call answered there). */
    private fun openChat(context: Context): CallInApp.Result {
        val (thread, user) = CallInApp.lastChat ?: (null to null)
        return CallInApp.open(context, thread, user, countTap = false)
    }
}
