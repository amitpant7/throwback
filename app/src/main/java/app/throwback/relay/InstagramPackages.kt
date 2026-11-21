package app.throwback.relay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Which Instagram app packages the relay and the guard look at. Nothing else is ever read. */
internal object InstagramPackages {
    const val APP = "com.instagram.android"
    const val LITE = "com.instagram.lite"
    val ALL = setOf(APP, LITE)

    /** The installed (and enabled) Instagram app, preferring the full one, or null. The manifest's <queries> lets Throwback see them. */
    fun installed(context: Context): String? = ALL.firstOrNull { isInstalled(context.packageManager, it) }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getApplicationInfo(packageName, 0)
        }
        info.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
