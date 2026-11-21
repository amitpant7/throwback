package app.throwback.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has tipped at least once (DECISIONS.md #4d): it unlocks Customize icon and ends
 * the support ask. Kept in its own preferences file, which Auto Backup includes, so a reinstall
 * keeps it (tips are one-off purchases Google Play doesn't list again).
 */
object Supporter {
    internal const val PREFS = "supporter" // backup_rules.xml and data_extraction_rules.xml name it
    private const val TIPPED = "tipped"

    @Volatile
    private var state: MutableStateFlow<Boolean>? = null

    /** The current value, kept up to date while the app runs. */
    fun tipped(context: Context): StateFlow<Boolean> = flow(context).asStateFlow()

    /** Called once Google Play billing confirms a tip (and by test builds, see MainActivity). */
    fun markTipped(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TIPPED, true).apply()
        flow(context).value = true
    }

    private fun flow(context: Context): MutableStateFlow<Boolean> = state ?: synchronized(this) {
        state ?: MutableStateFlow(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(TIPPED, false),
        ).also { state = it }
    }
}
