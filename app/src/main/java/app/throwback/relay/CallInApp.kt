package app.throwback.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.throwback.core.LockState
import app.throwback.guard.GuardPass
import app.throwback.guard.PassRules
import app.throwback.timelimit.TimeLimitModule
import java.time.LocalDate

/**
 * Calls start in the real Instagram app: Instagram's mobile website has no call buttons (only the
 * desktop site does). The chat's call button opens that chat in the Instagram app with a short
 * guard pass; once the call runs, the guard allows it by its call audio, and after hanging up it
 * sends the user back to Throwback as usual.
 */
internal object CallInApp {
    /** Long enough to tap call; the running call itself is then allowed by its audio. */
    const val PASS_MS = 30_000L

    enum class Result { OPENED, NO_APP, LIMITED }

    private const val PREFS = "guard_passes"

    fun open(context: Context, thread: String?, user: String? = null): Result {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return Result.NO_APP
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wall = System.currentTimeMillis()
        val taps = prefs.getString("callTaps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        if (!PassRules.callAllowed(taps, wall)) return Result.LIMITED
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
                // Only once Instagram really opens.
                GuardPass.grant(SystemClock.elapsedRealtime(), PASS_MS, GuardPass.Kind.CALL)
                prefs.edit().putString("callTaps", (PassRules.recentTaps(taps, wall) + wall).joinToString(",")).apply()
                return Result.OPENED
            } catch (e: ActivityNotFoundException) {
                // Try the next way in.
            } catch (e: SecurityException) {
                // Try the next way in.
            }
        }
        return Result.NO_APP
    }

    /** "Open Instagram for 5 minutes" (switch accounts, change its settings). */
    const val VISIT_PASS_MS = 5 * 60_000L

    enum class VisitResult { OPENED, NO_APP, USED_TODAY, LOCKED }

    /**
     * Opens the Instagram app with a pass of up to [VISIT_PASS_MS]: once a day, not while the daily
     * limit is reached, and it ends when the user leaves Instagram.
     */
    fun visit(context: Context, today: LocalDate = LocalDate.now()): VisitResult {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return VisitResult.NO_APP
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return VisitResult.NO_APP
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("visitDay", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        val locked = TimeLimitModule.create(context).state.value is LockState.Locked
        if (!PassRules.visitAllowed(last, today.toEpochDay(), locked)) {
            return if (locked) VisitResult.LOCKED else VisitResult.USED_TODAY
        }
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuardPass.grant(SystemClock.elapsedRealtime(), VISIT_PASS_MS, GuardPass.Kind.VISIT)
            prefs.edit().putLong("visitDay", today.toEpochDay()).apply()
            VisitResult.OPENED
        } catch (e: ActivityNotFoundException) {
            VisitResult.NO_APP
        }
    }

    /** Longest a create pass lasts (it normally ends when Instagram leaves its create screens). */
    const val CREATE_PASS_MS = 30 * 60_000L

    /**
     * "+" / "Your story" with the Instagram app connected: its create screen (better editor), with a
     * pass that only allows the creation screens (see GuardService). False if it couldn't open.
     */
    fun create(context: Context): Boolean {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return false
        val link = app.throwback.web.rules.RulesRepository.get(context).rules.value?.appCreate?.link ?: "instagram://share"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuardPass.grant(SystemClock.elapsedRealtime(), CREATE_PASS_MS, GuardPass.Kind.CREATE)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Longest a settings pass lasts. */
    const val SETTINGS_PASS_MS = 60 * 60_000L

    /**
     * "Open settings in the Instagram app" (end of Instagram's settings page): its Settings and
     * activity, with a pass for its settings screens (see GuardService). False if it couldn't open.
     */
    fun settings(context: Context): Boolean {
        val pkg = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) } ?: return false
        val link = app.throwback.web.rules.RulesRepository.get(context).rules.value?.appSettings?.link ?: "instagram://settings"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuardPass.grant(SystemClock.elapsedRealtime(), SETTINGS_PASS_MS, GuardPass.Kind.SETTINGS)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
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
