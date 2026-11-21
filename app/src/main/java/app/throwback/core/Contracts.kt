package app.throwback.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Types shared by the app's areas (web, timelimit, ui) and the code that joins them (MainActivity,
 * AppRoot). See docs/ARCHITECTURE.md.
 */

/** What the Instagram WebView is allowed to show. */
enum class WebMode {
    /** Normal Throwback: feed, stories, profiles, search, messages (with filters applied). */
    FULL,

    /** Daily limit used up: only the inbox and chats (/direct/...). Time here is not counted. */
    MESSAGES_ONLY,
}

/**
 * What the main screen needs from the daily time limit (app.throwback.timelimit): its lock state,
 * and when Instagram is in use. The rest of the app (settings, lock screens, relay, guard, passes)
 * uses the DefaultTimeLimitController that TimeLimitModule makes.
 */
interface TimeLimitController {
    val state: StateFlow<LockState>

    /** Throwback's main screen is visible and in use (activity resumed, screen on). */
    fun onForeground(mode: WebMode)

    /** Throwback's main screen is no longer visible. */
    fun onBackground()
}

sealed interface LockState {
    /** No limit configured (also what [TimeLimitController.state] says until the saved state has loaded). */
    data object Disabled : LockState

    /** Limit configured and time is left today. */
    data class Running(val remainingMillis: Long) : LockState

    /** Limit reached (waiting for an extension, entering a PIN, or locked until midnight). */
    data object Locked : LockState
}

/** Small persisted flags owned by app.throwback.ui. */
interface UiPreferences {
    val onboardingDone: Flow<Boolean>
    val mindfulPause: Flow<Boolean>
    suspend fun setOnboardingDone()
}
