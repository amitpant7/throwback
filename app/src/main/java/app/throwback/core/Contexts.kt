package app.throwback.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The activity behind this context (Compose's LocalContext, a themed wrapper), or null outside one. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
