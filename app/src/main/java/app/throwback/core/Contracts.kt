package app.throwback.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Types shared by the app's features (web, timelimit, connect, ui) and the shell that joins them.
 * See docs/ARCHITECTURE.md.
 */

/** What the Instagram WebView is allowed to show. */
enum class WebMode {
    /** Normal Throwback: feed, stories, profiles, search, messages (with filters applied). */
    FULL,

    /** Daily limit used up: only the inbox and chats (/direct/...). Time here is not counted. */
    MESSAGES_ONLY,
}


sealed interface LockState {
    /** No limit configured (also what the time limit says until its saved state has loaded). */
    data object Disabled : LockState

    /** Limit configured and time is left today. */
    data class Running(val remainingMillis: Long) : LockState

    /** Limit reached (waiting for an extension, entering a PIN, or locked until midnight). */
    data object Locked : LockState
}

