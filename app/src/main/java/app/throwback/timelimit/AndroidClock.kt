package app.throwback.timelimit

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.throwback.BuildConfig
import app.throwback.timelimit.engine.Clock
import app.throwback.timelimit.engine.ClockReading
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The real clocks: monotonic elapsedRealtime, the wall clock, the time zone, the boot number and
 * network time: on Android 13+ Android's own last time sync, else Instagram's server time as the page
 * last saw it ([ServerTimeHint]). Neither makes a request for Throwback.
 */
internal open class AndroidClock(context: Context) : Clock {

    // A reboot ends the process, so the boot number can't change while this object exists.
    private val bootCount: Int = runCatching {
        Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    override fun now() = ClockReading(
        elapsedMillis = SystemClock.elapsedRealtime(),
        wallMillis = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        bootCount = bootCount,
        networkMillis = networkTime() ?: ServerTimeHint.now(),
    )

    /** Null below Android 13, or before the phone has synced its time since boot. */
    private fun networkTime(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            SystemClock.currentNetworkTimeClock().millis()
        } catch (e: Exception) {
            null // DateTimeException: no time sync yet since boot
        }
    }

    companion object {
        /**
         * TEST BUILDS ONLY: a file `files/debug_clock` makes time run faster, so the time-limit flow can
         * be tested end to end in minutes. Contents: `<speed> [HH:MM]`, e.g. `60 23:55` = 60x, and the
         * wall clock starts at 23:55 today (to reach midnight quickly). Release builds never read it.
         * Put it there with `adb shell "run-as app.throwback sh -c 'echo 60 > /data/data/app.throwback/files/debug_clock'"`.
         * Remove debug_clock and debug_clock_anchor (and the time-limit data) to go back to real time.
         */
        fun create(context: Context): Clock {
            val real = AndroidClock(context)
            if (!BuildConfig.DEBUG) return real
            val dir = context.filesDir
            val spec = runCatching { File(dir, "debug_clock").readText().trim() }.getOrNull() ?: return real
            val parts = spec.split(Regex("\\s+"))
            val speed = parts[0].toLongOrNull()?.coerceIn(1, 3600) ?: return real
            // The starting point is saved, so fast time continues across app restarts and reinstalls
            // (otherwise times saved by the previous run would lie "in the future").
            val anchorFile = File(dir, "debug_clock_anchor")
            val saved = runCatching {
                anchorFile.readText().trim().split(" ").map { it.toLong() }.takeIf { it.size == 2 }
            }.getOrNull()
            val anchor = saved ?: run {
                val now = real.now()
                val start = parts.getOrNull(1)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val fastWall = start?.let {
                    LocalDate.now(now.zone).atTime(it).atZone(now.zone).toInstant().toEpochMilli()
                } ?: now.wallMillis
                listOf(now.wallMillis, fastWall).also { a -> runCatching { anchorFile.writeText("${a[0]} ${a[1]}") } }
            }
            Log.w("ThrowbackTime", "DEBUG fast clock: ${speed}x, anchor ${anchor[0]} -> ${anchor[1]}")
            return FastClock(real, speed, anchorReal = anchor[0], anchorFast = anchor[1])
        }
    }
}

/**
 * Test-build clock. Fast wall time = anchorFast + (real wall time - anchorReal) x speed; monotonic
 * time keeps the same offset, so both advance [speed] times faster, consistently across restarts.
 */
private class FastClock(
    private val real: Clock,
    private val speed: Long,
    private val anchorReal: Long,
    private val anchorFast: Long,
) : Clock {
    override fun now(): ClockReading {
        val r = real.now()
        val fastWall = anchorFast + (r.wallMillis - anchorReal) * speed
        return r.copy(
            elapsedMillis = r.elapsedMillis + (fastWall - r.wallMillis),
            wallMillis = fastWall,
            networkMillis = r.networkMillis?.let { anchorFast + (it - anchorReal) * speed },
        )
    }
}
