package app.throwback.ui.connect

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.throwback.connect.InstagramPackages
import app.throwback.connect.Permissions

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
