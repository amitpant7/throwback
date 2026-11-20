package app.throwback.core

import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.throwback.BuildConfig

/** TEST BUILDS ONLY: cold-start timing, "ms since the process started", log tag ThrowbackStart. */
object StartTrace {
    private var done = false
    private val seen = HashSet<String>()

    fun mark(stage: String) {
        if (!BuildConfig.DEBUG || done || !seen.add(stage)) return
        Log.i("ThrowbackStart", "+${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms $stage")
        if (stage == "load") done = true
    }
}
