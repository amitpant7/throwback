package app.throwback.timelimit

import android.content.Context
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

/** The real clocks: monotonic elapsedRealtime, the wall clock, the time zone and the boot number. */
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
    )

    companion object {
        /**
         * TEST BUILDS ONLY: a file `files/debug_clock` makes time run faster, so the time-limit flow can
         * be tested end to end in minutes. Contents: `<speed> [HH:MM]`, e.g. `60 23:55` = 60x, and the
         * wall clock starts at 23:55 today (to reach midnight quickly). Release builds never read it.
         * Put it there with `adb shell run-as app.throwback sh -c 'echo 60 > files/debug_clock'`.
         */
        fun create(context: Context): Clock {
            val real = AndroidClock(context)
            if (!BuildConfig.DEBUG) return real
            val spec = runCatching { File(context.filesDir, "debug_clock").readText().trim() }.getOrNull()
                ?: return real
            val parts = spec.split(Regex("\\s+"))
            val speed = parts[0].toLongOrNull()?.coerceIn(1, 3600) ?: return real
            val start = parts.getOrNull(1)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            Log.w("ThrowbackTime", "DEBUG fast clock: ${speed}x" + (start?.let { ", starting at $it" } ?: ""))
            return FastClock(real, speed, start)
        }
    }
}

/** Test-build clock: both clocks advance [speed] times faster from the moment the app started. */
private class FastClock(private val real: Clock, private val speed: Long, start: LocalTime?) : Clock {
    private val base = real.now()
    private val baseWall = start?.let {
        LocalDate.now(base.zone).atTime(it).atZone(base.zone).toInstant().toEpochMilli()
    } ?: base.wallMillis

    override fun now(): ClockReading {
        val r = real.now()
        val passed = (r.elapsedMillis - base.elapsedMillis) * speed
        return r.copy(elapsedMillis = base.elapsedMillis + passed, wallMillis = baseWall + passed)
    }
}
