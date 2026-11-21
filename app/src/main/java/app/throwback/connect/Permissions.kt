package app.throwback.connect

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import app.throwback.connect.relay.RelayListenerService

/** Real permission checks (no fakes): each re-read when the screen resumes. */
internal object Permissions {
    /** Settings.Secure key of the allowed notification listeners (not public before Android 8.1). */
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    /**
     * Throwback's listener itself is allowed, checked by its class name, not just by the package:
     * Android keeps the permission under the class name, so after an update that renamed it the
     * package still shows up as allowed while the listener can no longer connect.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val listener = ComponentName(context, RelayListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listener) == true
        } else {
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == listener }
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        // The same check on every version: Android 10 renamed it unsafeCheckOpNoThrow, Android 16 named
        // it back (and deprecated the other name).
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
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
