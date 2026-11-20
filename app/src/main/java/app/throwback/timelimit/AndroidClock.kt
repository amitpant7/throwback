package app.throwback.timelimit

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.throwback.timelimit.engine.Clock
import app.throwback.timelimit.engine.ClockReading
import java.time.ZoneId

/** The real clocks: monotonic elapsedRealtime, the wall clock, the time zone and the boot number. */
internal class AndroidClock(context: Context) : Clock {

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
}
