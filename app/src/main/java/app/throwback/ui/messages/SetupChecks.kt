package app.throwback.ui.messages

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** The real Instagram app (and Lite), which the manifest's <queries> lets us see. */
internal object InstagramApp {
    const val PACKAGE = "com.instagram.android"
    const val LITE_PACKAGE = "com.instagram.lite"
    private val PACKAGES = listOf(PACKAGE, LITE_PACKAGE)

    /** The installed (and enabled) Instagram package, preferring the full app, or null. */
    fun installedPackage(context: Context): String? =
        PACKAGES.firstOrNull { isInstalled(context.packageManager, it) }

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

/** Live state of everything the Messages & calls feature needs. */
internal data class MessagesSetupState(
    val instagramPackage: String?,
    val notificationAccess: Boolean,
    val usageAccess: Boolean,
    val drawOverlays: Boolean,
) {
    val instagramInstalled: Boolean get() = instagramPackage != null

    val permissionsGranted: Int get() = listOf(notificationAccess, usageAccess, drawOverlays).count { it }

    val permissionsMissing: Int get() = PERMISSION_COUNT - permissionsGranted

    val status: MessagesStatus
        get() = when {
            !instagramInstalled -> MessagesStatus.InstagramMissing
            permissionsMissing > 0 -> MessagesStatus.PermissionsMissing(permissionsMissing)
            else -> MessagesStatus.Ready
        }

    companion object {
        const val PERMISSION_COUNT = 3

        fun read(context: Context) = MessagesSetupState(
            instagramPackage = InstagramApp.installedPackage(context),
            notificationAccess = Permissions.hasNotificationAccess(context),
            usageAccess = Permissions.hasUsageAccess(context),
            drawOverlays = Permissions.canDrawOverlays(context),
        )
    }
}

internal sealed interface MessagesStatus {
    data object Ready : MessagesStatus
    data object InstagramMissing : MessagesStatus
    data class PermissionsMissing(val count: Int) : MessagesStatus
}

/** Real permission checks (no fakes): each re-read when the screen resumes. */
internal object Permissions {

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}

/** Launcher families with different "hide app" steps. Order is the order shown on screen. */
internal enum class PhoneBrand { Samsung, OnePlusOppo, Xiaomi, Other }

internal object HideIconInstructions {

    /** Maps Build.MANUFACTURER (any case, may be blank) to a launcher family. */
    fun brandFor(manufacturer: String?): PhoneBrand {
        val m = manufacturer.orEmpty().trim().lowercase()
        return when {
            m.isEmpty() -> PhoneBrand.Other
            m == "samsung" -> PhoneBrand.Samsung
            m in setOf("oneplus", "oppo", "realme") -> PhoneBrand.OnePlusOppo
            m in setOf("xiaomi", "redmi", "poco") -> PhoneBrand.Xiaomi
            else -> PhoneBrand.Other
        }
    }

    /** All brands, with this phone's brand first so its steps are the first thing the user reads. */
    fun ordered(manufacturer: String?): List<PhoneBrand> {
        val mine = brandFor(manufacturer)
        return listOf(mine) + PhoneBrand.entries.filter { it != mine }
    }
}
