package app.throwback

import app.throwback.web.PageClock
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.app.Application
import app.throwback.core.prefs.AppPrefs
import app.throwback.connect.MessagesFeature
import app.throwback.timelimit.TimeLimitController
import app.throwback.timelimit.ServerTimeHint
import app.throwback.timelimit.TimeLimitModule

/** Owned by integration. Holds the app-wide singletons; no DI framework needed at this size. */
class ThrowbackApp : Application() {
    internal val timeLimit: TimeLimitController by lazy { TimeLimitModule.create(this) }
    internal val prefs: AppPrefs by lazy { AppPrefs.from(this) }

    override fun onCreate() {
        super.onCreate()
        app.throwback.core.StartTrace.mark("app")
        warmUp()
        // Messages & calls: starts the guard if (and only if) the user finished its setup.
        MessagesFeature.install(this)
        // The page's daily counters use the time limit's trusted clock, not the phone's: only once
        // its saved state is loaded (before that it would be the phone's own clock), else none.
        PageClock.serverTimeSink = { ServerTimeHint.note(it) }
        PageClock.provider = {
            timeLimit.pageClock()?.let {
                PageClock.Reading(it.offsetMs, it.zoneOffsetMin, it.day, it.nextDayAt, readAt = System.currentTimeMillis())
            }
        }
    }

    /**
     * Cold start: read what the first screen waits for (saved settings, filter rules) in the
     * background right away, instead of one after another once the screen asks. The page script is
     * read by MainActivity: this also runs for the guard, the relay and boot, which never show a page.
     * Measured on the emulator: the Instagram screen was composed ~400 ms after the activity started.
     */
    private fun warmUp() {
        val context: Context = this
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            launch { runCatching { prefs.onboardingDone.first() } }
            launch { runCatching { prefs.mindfulPause.first() } }
            launch { runCatching { RulesRepository.get(context).load() } }
            launch { runCatching { MessagesFeature.isSetUp(context) } }
        }
        timeLimit // creates the time limit now; it loads its saved state in the background
    }
}
