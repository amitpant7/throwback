package app.throwback

import app.throwback.web.PageAssets
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        app.throwback.core.StartTrace.mark("app")
        warmUp()
        // Messages & calls: starts the guard if (and only if) the user finished its setup.
        MessagesFeature.install(this)
        // The page's daily counters use the time limit's trusted clock, not the phone's.
        app.throwback.web.PageClock.provider = {
            (timeLimit as? app.throwback.timelimit.DefaultTimeLimitController)?.pageClock()?.let {
                longArrayOf(it.offsetMs, it.zoneOffsetMin.toLong(), it.day, it.nextDayAt)
            } ?: longArrayOf(0L, 0L, 0L, 0L)
        }
    }

    /**
     * Cold start: read everything the first screen waits for (saved settings, filter rules, the page
     * script) in the background right away, instead of one after another once the screen asks.
     * Measured on the emulator: the Instagram screen was composed ~400 ms after the activity started.
     */
    private fun warmUp() {
        val context: Context = this
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            launch { runCatching { uiPreferences.onboardingDone.first() } }
            launch { runCatching { uiPreferences.mindfulPause.first() } }
            launch { runCatching { RulesRepository.get(context).load() } }
            launch { runCatching { PageAssets.load(context) } }
            launch { runCatching { MessagesFeature.isSetUp(context) } }
        }
        timeLimit // creates the time limit now; it loads its saved state in the background
    }
}
