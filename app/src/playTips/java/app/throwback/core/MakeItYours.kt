package app.throwback.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** Whether Make It Yours is unlocked: by any tip (DECISIONS.md #4d). src/webTips: free (#4e). */
internal fun makeItYours(context: Context): StateFlow<Boolean> = Supporter.tipped(context)
