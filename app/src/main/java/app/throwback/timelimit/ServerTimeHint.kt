package app.throwback.timelimit

import android.os.SystemClock

/**
 * Network time for phones that have none of their own (below Android 13): Instagram's server time,
 * from the Date of its own responses (the page reads it; no request is made for this), kept on the
 * phone's stopwatch from the moment it arrived. It lives in this process only, so it is never from an
 * earlier boot. The engine prefers network time to the phone's clock (see TimeLimitEngine.trustedWall).
 */
internal object ServerTimeHint {
    @Volatile
    private var hint: Pair<Long, Long>? = null // server ms, elapsedRealtime when it arrived

    fun note(serverMillis: Long, elapsedNow: Long = SystemClock.elapsedRealtime()) {
        hint = serverMillis to elapsedNow
    }

    /** The server's time now, or null before the first hint. */
    fun now(elapsedNow: Long = SystemClock.elapsedRealtime()): Long? =
        hint?.let { (server, at) -> server + (elapsedNow - at) }
}
