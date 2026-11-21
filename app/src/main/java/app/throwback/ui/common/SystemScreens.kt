package app.throwback.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.throwback.connect.relay.RelayListenerService

/**
 * Opens Android and Play Store screens. Every function returns false instead of crashing when
 * the phone has no app for the intent (some OEM builds strip settings screens).
 */
internal object SystemScreens {

    fun openPlayStore(context: Context, packageName: String): Boolean =
        context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))) ||
            openUrl(context, "https://play.google.com/store/apps/details?id=$packageName")

    fun openAppDetails(context: Context, packageName: String): Boolean =
        context.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    /**
     * Android's notification settings for another app (its notification categories with on/off
     * switches). Falls back to that app's details page.
     */
    fun openAppNotificationSettings(context: Context, packageName: String): Boolean =
        context.tryStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        ) || openAppDetails(context, packageName)

    fun openUrl(context: Context, url: String): Boolean =
        context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

    /** Android 11+: straight to Throwback's own switch; older: the list of apps. */
    fun openNotificationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, RelayListenerService::class.java).flattenToString(),
            )
            if (context.tryStart(detail)) return true
        }
        return context.tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) || openOwnDetails(context)
    }

    /** Android 10+ opens Throwback's own page when given the package; otherwise the list of apps. */
    fun openUsageAccess(context: Context): Boolean =
        context.tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))) ||
            context.tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) || openOwnDetails(context)

    fun openOwnAppDetails(context: Context): Boolean = openOwnDetails(context)

    fun openOverlaySettings(context: Context): Boolean =
        context.tryStart(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        ) || context.tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) || openOwnDetails(context)

    private fun openOwnDetails(context: Context): Boolean = openAppDetails(context, context.packageName)

    private fun Context.tryStart(intent: Intent): Boolean {
        if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * Runs [check] now and again every time the screen resumes, e.g. when the user comes back
 * from an Android settings screen or the Play Store.
 */
@Composable
internal fun <T> rememberCheckedOnResume(check: (Context) -> T): State<T> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(check(context)) }
    val currentCheck by rememberUpdatedState(check)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        // An observer added to a resumed screen is sent ON_RESUME straight away; [check] has just
        // run, so only the later ones count.
        var adding = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !adding) state.value = currentCheck(context)
        }
        lifecycle.addObserver(observer)
        adding = false
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}
