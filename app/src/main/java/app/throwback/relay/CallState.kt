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
    /** When the last call notification went away (elapsed-realtime millis), or null. */
    val lastCallEndedAt: Long? = null,
) {
    val inCall: Boolean get() = activeCalls.isNotEmpty()

    /** In a call, or a call ended less than [graceMillis] ago. */
    fun inCallOrGrace(now: Long, graceMillis: Long): Boolean =
        inCall || (lastCallEndedAt != null && now - lastCallEndedAt in 0 until graceMillis)
}

/**
 * Tracks Instagram call notifications (DECISIONS.md #20, #27). Fed by the relay, read by the guard.
 * Times are elapsed-realtime millis, so changing the phone's clock changes nothing.
 */
internal class CallTracker {
    private val _state = MutableStateFlow(CallSnapshot())
    val state: StateFlow<CallSnapshot> = _state.asStateFlow()

    /** The listener (re)connected: start over from the call notifications showing now. */
    fun onConnected(activeCallKeys: Set<String>) =
        _state.update { it.copy(listenerConnected = true, activeCalls = activeCallKeys) }

    fun onDisconnected(now: Long) = _state.update {
        CallSnapshot(listenerConnected = false, lastCallEndedAt = if (it.inCall) now else it.lastCallEndedAt)
    }

    fun onCallShowing(key: String) = _state.update { it.copy(activeCalls = it.activeCalls + key) }

    /** Any notification went away; only call keys matter. */
    fun onRemoved(key: String, now: Long) = _state.update {
        if (key !in it.activeCalls) return@update it
        val left = it.activeCalls - key
        it.copy(activeCalls = left, lastCallEndedAt = if (left.isEmpty()) now else it.lastCallEndedAt)
    }

    /**
     * Keeps only the calls still [showing] (the keys of the notifications Android shows now): a
     * removal that was never reported must not keep a call, and with it Instagram, allowed.
     */
    fun keepOnly(showing: Set<String>, now: Long) = _state.update {
        val left = it.activeCalls intersect showing
        if (left.size == it.activeCalls.size) return@update it
        it.copy(activeCalls = left, lastCallEndedAt = if (left.isEmpty()) now else it.lastCallEndedAt)
    }
}

/** The process-wide call state (relay and guard run in the same process). */
internal object CallState {
    val tracker = CallTracker()
}
