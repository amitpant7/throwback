package app.throwback.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Make It Yours is left out of builds without Google Play tips (owner, 2026-11-22, DECISIONS.md #4e):
 * it comes back with the tips that unlock it. src/playTips has the same two declarations.
 */
internal const val MAKE_IT_YOURS_IN_BUILD = false

/** Not reachable in this build (see above); free if it ever were. */
@Suppress("UNUSED_PARAMETER")
internal fun makeItYours(context: Context): StateFlow<Boolean> = FREE

private val FREE: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
