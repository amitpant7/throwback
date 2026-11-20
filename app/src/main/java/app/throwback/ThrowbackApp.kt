package app.throwback

import android.app.Application
import app.throwback.core.TimeLimitController
import app.throwback.core.UiPreferences
import app.throwback.relay.MessagesFeature
import app.throwback.timelimit.TimeLimitModule
import app.throwback.ui.UiModule

/** Owned by integration. Holds the app-wide singletons; no DI framework needed at this size. */
class ThrowbackApp : Application() {
    val timeLimit: TimeLimitController by lazy { TimeLimitModule.create(this) }
    val uiPreferences: UiPreferences by lazy { UiModule.create(this) }

    override fun onCreate() {
        super.onCreate()
        // Messages & calls: starts the guard if (and only if) the user finished its setup.
        MessagesFeature.install(this)
        // The page's daily counters use the time limit's trusted clock, not the phone's.
        app.throwback.web.PageClock.provider = {
            (timeLimit as? app.throwback.timelimit.DefaultTimeLimitController)?.pageClock()?.let {
                longArrayOf(it.offsetMs, it.zoneOffsetMin.toLong(), it.day, it.nextDayAt)
            } ?: longArrayOf(0L, 0L, 0L, 0L)
        }
    }
}
