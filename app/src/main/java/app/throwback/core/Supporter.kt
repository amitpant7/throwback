package app.throwback.core

import android.content.Context
import androidx.core.content.edit
import app.throwback.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has tipped at least once (DECISIONS.md #4d): it unlocks Make It Yours and ends
 * the support ask. Kept in its own preferences file, which Auto Backup includes, so a reinstall
 * keeps it. With billing, the tips Google Play lists as owned set it too (the plan in #4d).
 */
object Supporter {
    internal const val PREFS = "supporter" // backup_rules.xml and data_extraction_rules.xml name it
    private const val TIPPED = "tipped"

    @Volatile
    private var state: MutableStateFlow<Boolean>? = null

    /** The current value, kept up to date while the app runs. */
    fun tipped(context: Context): StateFlow<Boolean> = flow(context).asStateFlow()

    /**
     * Whether Make It Yours is unlocked: by a tip in builds with Google Play tips; for everyone in
     * builds without them, where nothing in the app can unlock it (DECISIONS.md #4e).
     */
    fun makeItYours(context: Context): StateFlow<Boolean> = if (BuildConfig.PLAY_TIPS) tipped(context) else FREE

    private val FREE: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    /** Called once Google Play billing confirms a tip (and by test builds, see MainActivity). */
    fun markTipped(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(TIPPED, true) }
        flow(context).value = true
    }

    private fun flow(context: Context): MutableStateFlow<Boolean> = state ?: synchronized(this) {
        state ?: MutableStateFlow(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(TIPPED, false),
        ).also { state = it }
    }
}
