package app.throwback.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the relay knows about Instagram calls right now, shared with the guard. */
internal data class CallSnapshot(
    /** The notification listener is connected, so [activeCalls] can be trusted. */
    val listenerConnected: Boolean = false,
    /** Keys of Instagram call notifications currently showing (ringing or in progress). */
    val activeCalls: Set<String> = emptySet(),
    /** Of [activeCalls], those only taken for a call because they are ongoing (NotificationKind.ONGOING). */
    val maybeCalls: Set<String> = emptySet(),
    /** When the last call notification went away (elapsed-realtime millis), or null. */
    val lastCallEndedAt: Long? = null,
) {
    val inCall: Boolean get() = activeCalls.isNotEmpty()

    /** A notification Android or the rules mark as a call is showing (not only an ongoing one). */
    val sureCall: Boolean get() = activeCalls.any { it !in maybeCalls }

    /**
     * In a call, or a call ended less than [graceMillis] ago. A notification only ongoing counts
     * only with [callSigns] (call audio or an Instagram call screen).
     */
    fun inCallOrGrace(now: Long, graceMillis: Long, callSigns: Boolean = true): Boolean =
        sureCall || (inCall && callSigns) || (lastCallEndedAt != null && now - lastCallEndedAt in 0 until graceMillis)
}

/**
 * Tracks Instagram call notifications (DECISIONS.md #20, #27). Fed by the relay, read by the guard.
 * Times are elapsed-realtime millis, so changing the phone's clock changes nothing.
 */
internal class CallTracker {
    private val _state = MutableStateFlow(CallSnapshot())
    val state: StateFlow<CallSnapshot> = _state.asStateFlow()

    /** The listener (re)connected: start over from the call notifications showing now ([maybe]: only ongoing). */
    fun onConnected(activeCallKeys: Set<String>, maybe: Set<String> = emptySet()) =
        _state.update { it.copy(listenerConnected = true, activeCalls = activeCallKeys + maybe, maybeCalls = maybe) }

    fun onDisconnected(now: Long) = _state.update {
        CallSnapshot(listenerConnected = false, lastCallEndedAt = if (it.inCall) now else it.lastCallEndedAt)
    }

    /** A call notification is showing; [sure] false: it is only ongoing (NotificationKind.ONGOING). */
    fun onCallShowing(key: String, sure: Boolean = true) = _state.update {
        it.copy(activeCalls = it.activeCalls + key, maybeCalls = if (sure) it.maybeCalls - key else it.maybeCalls + key)
    }

    /** Any notification went away; only call keys matter. */
    fun onRemoved(key: String, now: Long) = _state.update {
        if (key !in it.activeCalls) return@update it
        val left = it.activeCalls - key
        it.copy(activeCalls = left, maybeCalls = it.maybeCalls - key, lastCallEndedAt = if (left.isEmpty()) now else it.lastCallEndedAt)
    }

    /**
     * Keeps only the calls still [showing] (the keys of the notifications Android shows now): a
     * removal that was never reported must not keep a call, and with it Instagram, allowed.
     */
    fun keepOnly(showing: Set<String>, now: Long) = _state.update {
        val left = it.activeCalls intersect showing
        if (left.size == it.activeCalls.size) return@update it
        it.copy(activeCalls = left, maybeCalls = it.maybeCalls intersect showing, lastCallEndedAt = if (left.isEmpty()) now else it.lastCallEndedAt)
    }
}

/** The process-wide call state (relay and guard run in the same process). */
internal object CallState {
    val tracker = CallTracker()
}
