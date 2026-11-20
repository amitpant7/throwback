package app.throwback.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.throwback.guard.GuardPass

/**
 * Calls start in the real Instagram app: Instagram's mobile website has no call buttons (only the
 * desktop site does). The chat's call button opens that chat in the Instagram app with a short
 * guard pass; once the call runs, the guard allows it by its call audio, and after hanging up it
 * sends the user back to Throwback as usual.
 */
internal object CallInApp {
    /** Long enough to tap call; the running call itself is then allowed by its audio. */
    const val PASS_MS = 90_000L

    /** False if the Instagram app isn't installed (the caller explains and offers the setup). */
    fun open(context: Context, thread: String?, user: String? = null): Boolean {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return false
        // ig.me/m/<username> opens a chat with that person from whichever account the Instagram app
        // uses (a chat id only exists in one account); the chat id is for group chats.
        val byUser = user?.let { Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/$it")).setPackage(pkg) }
        val byThread = thread?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/t/$it/")).setPackage(pkg)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        for (intent in listOfNotNull(byUser, byThread, launch)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                GuardPass.grant(SystemClock.elapsedRealtime(), PASS_MS) // only once Instagram really opens
                return true
            } catch (e: ActivityNotFoundException) {
                // Try the next way in.
            } catch (e: SecurityException) {
                // Try the next way in.
            }
        }
        return false
    }

    /** "Open Instagram for 5 minutes" (switch accounts, change its settings). */
    const val VISIT_PASS_MS = 5 * 60_000L

    /** Opens the Instagram app with a pass of [VISIT_PASS_MS]; false if it isn't installed. */
    fun visit(context: Context): Boolean {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuardPass.grant(SystemClock.elapsedRealtime(), VISIT_PASS_MS)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
