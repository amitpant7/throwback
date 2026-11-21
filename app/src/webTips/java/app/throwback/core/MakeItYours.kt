package app.throwback.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Make It Yours is unlocked. Without Google Play tips nothing in the app could unlock it, so
 * it is free (DECISIONS.md #4e). src/playTips has the same function: unlocked by a tip there.
 */
@Suppress("UNUSED_PARAMETER")
internal fun makeItYours(context: Context): StateFlow<Boolean> = FREE

private val FREE: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
