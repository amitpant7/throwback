package app.throwback.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** Make It Yours is in builds with Google Play tips. */
internal const val MAKE_IT_YOURS_IN_BUILD = true

/** Whether Make It Yours is unlocked: by any tip (DECISIONS.md #4d). src/webTips: free (#4e). */
internal fun makeItYours(context: Context): StateFlow<Boolean> = Supporter.tipped(context)
