package app.throwback.ui.messages

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.throwback.relay.InstagramPackages

/** The real Instagram app (and Lite), which the manifest's <queries> lets us see. */
internal object InstagramApp {
    /** The full app, for its Play Store page. */
    const val PACKAGE = InstagramPackages.APP

    /** The installed (and enabled) Instagram package, preferring the full app, or null. */
    fun installedPackage(context: Context): String? = InstagramPackages.installed(context)
}

/** Live state of everything the connected Instagram app needs. */
internal data class MessagesSetupState(
    val instagramPackage: String?,
    val notificationAccess: Boolean,
    val usageAccess: Boolean,
    val drawOverlays: Boolean,
    /** Throwback may post notifications (Android 13+ asks; always true before). */
    val postNotifications: Boolean = true,
) {
    val instagramInstalled: Boolean get() = instagramPackage != null

    val permissionsGranted: Int get() = listOf(notificationAccess, usageAccess, drawOverlays).count { it }

    /** The three special permissions of the wizard's permissions step, plus Android 13's notification permission. */
    val permissionsMissing: Int get() = PERMISSION_COUNT - permissionsGranted + (if (postNotifications) 0 else 1)

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
            postNotifications = Permissions.canPostNotifications(context),
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

    /** Android 13+ runtime permission for Throwback's own notifications (relayed messages). */
    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Where Throwback was installed from. */
internal object InstallSource {
    private const val PLAY_STORE = "com.android.vending"

    /** Installed by Google Play (not from a downloaded APK, adb or another store). */
    fun fromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)
}
