package app.throwback

import android.app.Application
import app.throwback.core.TimeLimitController
import app.throwback.core.UiPreferences
import app.throwback.timelimit.TimeLimitModule
import app.throwback.ui.UiModule

/** Owned by integration. Holds the app-wide singletons; no DI framework needed at this size. */
class ThrowbackApp : Application() {
    val timeLimit: TimeLimitController by lazy { TimeLimitModule.create(this) }
    val uiPreferences: UiPreferences by lazy { UiModule.create(this) }
}
