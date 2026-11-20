package app.throwback.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.throwback.core.LockState
import app.throwback.guard.GuardPass
import app.throwback.guard.PassLedger
import app.throwback.timelimit.TimeLimitModule
import app.throwback.web.InstagramWebController
import app.throwback.web.PageFeatures
import app.throwback.web.rules.RulesRepository

/**
 * Throwback's ways into the real Instagram app, each with a guard pass (DECISIONS.md #27a, #27c,
 * #27e, #27f). How often each can be used is kept in [PassLedger], on the time limit's trusted clock.
 *
 * Calls start in the Instagram app: its mobile website has no call buttons (only the desktop site
 * does). The chat's call button opens that chat in the app with a short pass; once the call runs,
 * the guard allows it by its call audio, and after hanging up it sends the user back to Throwback.
 */
internal object CallInApp {
    /** Long enough to tap call; the running call itself is then allowed by its audio. */
    const val PASS_MS = 30_000L

    enum class Result { OPENED, NO_APP, LIMITED }

    fun open(context: Context, thread: String?, user: String? = null): Result {
        val pkg = installedInstagram(context) ?: return Result.NO_APP
        val now = PassLedger.now(context) ?: return Result.LIMITED
        if (!PassLedger.read(context).callAllowed(now.wallMillis)) return Result.LIMITED
        // ig.me/m/<username> opens a chat with that person from whichever account the Instagram app
        // uses (a chat id only exists in one account); the chat id is for group chats.
        val byUser = user?.let { Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/$it")).setPackage(pkg) }
        val byThread = thread?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/t/$it/")).setPackage(pkg)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        for (intent in listOfNotNull(byUser, byThread, launch)) {
            if (start(context, intent)) {
                // Only once Instagram really opens.
                GuardPass.grant(SystemClock.elapsedRealtime(), PASS_MS, GuardPass.Kind.CALL)
                PassLedger.update(context) { it.callTapped(now.wallMillis) }
                return Result.OPENED
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
    fun visit(context: Context): VisitResult {
        val pkg = installedInstagram(context) ?: return VisitResult.NO_APP
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return VisitResult.NO_APP
        val now = PassLedger.now(context) ?: return VisitResult.USED_TODAY
        val locked = TimeLimitModule.create(context).state.value is LockState.Locked
        if (!PassLedger.read(context).visitAllowed(now.epochDay, locked)) {
            return if (locked) VisitResult.LOCKED else VisitResult.USED_TODAY
        }
        if (!start(context, launch)) return VisitResult.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), VISIT_PASS_MS, GuardPass.Kind.VISIT)
        PassLedger.update(context) { it.visited(now.epochDay) }
        return VisitResult.OPENED
    }

    /** Longest a create pass lasts (it normally ends when Instagram leaves its create screens). */
    const val CREATE_PASS_MS = 30 * 60_000L

    /**
     * "+" / "Your story" with the Instagram app connected: its create screen (better editor), with a
     * pass that only allows the creation screens (see GuardService). False if it couldn't open.
     */
    fun create(context: Context): Boolean {
        val pkg = installedInstagram(context) ?: return false
        val link = RulesRepository.get(context).rules.value?.appCreate?.link ?: "instagram://share"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return false
        GuardPass.grant(SystemClock.elapsedRealtime(), CREATE_PASS_MS, GuardPass.Kind.CREATE)
        return true
    }

    enum class StoryResult { OPENED, NO_APP, USED_UP }

    private fun storyRules(context: Context) = RulesRepository.get(context).rules.value?.appCreate?.story

    private fun storiesPerDay(context: Context) = storyRules(context)?.perDay ?: 3

    /** Whether "Your story" can open the Instagram app now (else the website's story upload). */
    fun storyAvailable(context: Context): Boolean {
        val now = PassLedger.now(context) ?: return false
        return PassLedger.read(context).storyAllowed(now.epochDay, now.wallMillis, storiesPerDay(context))
    }

    /** Today's use of the Instagram app through Throwback's passes ("Your day"). */
    data class AppDay(val storiesUsed: Int, val storiesMax: Int, val storyWaitMinutes: Int?, val visitUsed: Boolean, val appMinutes: Int)

    fun appDay(context: Context): AppDay {
        val perDay = storiesPerDay(context)
        val now = PassLedger.now(context) ?: return AppDay(0, perDay, 0, visitUsed = false, appMinutes = 0)
        val book = PassLedger.read(context)
        return AppDay(
            storiesUsed = book.storiesOn(now.epochDay),
            storiesMax = perDay,
            storyWaitMinutes = book.storyWaitMinutes(now.epochDay, now.wallMillis, perDay),
            visitUsed = !book.visitAllowed(now.epochDay, locked = false),
            appMinutes = (book.appMillisOn(now.epochDay) / 60_000).toInt(),
        )
    }

    /** Called by the guard: [millis] more of the Instagram app in front during a pass today. */
    fun addAppTime(context: Context, millis: Long) {
        val now = PassLedger.now(context) ?: return
        PassLedger.update(context) { it.withAppTime(now.epochDay, millis) }
    }

    /** Why "Your story" uses the website now: minutes until the next pass, or null = not today. */
    fun storyWaitMinutes(context: Context): Int? {
        val now = PassLedger.now(context) ?: return 0
        return PassLedger.read(context).storyWaitMinutes(now.epochDay, now.wallMillis, storiesPerDay(context))
    }

    /**
     * "Your story": the Instagram app with a STORY pass: a few minutes (camera time not counted),
     * ended by posting or leaving; then a cooldown (see [storyAvailable]).
     */
    fun story(context: Context): StoryResult {
        val pkg = installedInstagram(context) ?: return StoryResult.NO_APP
        val now = PassLedger.now(context) ?: return StoryResult.USED_UP
        if (!PassLedger.read(context).storyAllowed(now.epochDay, now.wallMillis, storiesPerDay(context))) return StoryResult.USED_UP
        val link = storyRules(context)?.link ?: "instagram://story-camera"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return StoryResult.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), CREATE_PASS_MS, GuardPass.Kind.STORY)
        val wait = (storyRules(context)?.cooldownMinutes ?: 30) * 60_000L
        PassLedger.update(context) { it.storyStarted(now.epochDay, now.wallMillis, wait) }
        return StoryResult.OPENED
    }

    /**
     * The story pass ended with Instagram posting (owner, 2026-11-20: a real upload means no
     * cheating): it neither starts the wait nor counts toward the day's passes.
     */
    fun storyPosted(context: Context) {
        val now = PassLedger.now(context) ?: return
        PassLedger.update(context) { it.storyPosted(now.epochDay) }
        storyAvailabilityChanged(context)
    }

    /**
     * The story pass was left early, having shown little but the camera (a change of mind): it
     * doesn't count toward the day's passes, but a short wait follows so this can't be repeated.
     */
    fun storyLeftEarly(context: Context) {
        val now = PassLedger.now(context) ?: return
        PassLedger.update(context) { it.storyLeftEarly(now.epochDay, now.wallMillis) }
        storyAvailabilityChanged(context)
    }

    /** Tells the page whether "Your story" opens the app now. */
    private fun storyAvailabilityChanged(context: Context) {
        PageFeatures.storyInApp = storyAvailable(context)
        Handler(Looper.getMainLooper()).post { InstagramWebController.current?.get()?.pushFeatures() }
    }

    /** Longest a settings pass lasts. */
    const val SETTINGS_PASS_MS = 60 * 60_000L

    /**
     * "Open settings in the Instagram app" (end of Instagram's settings page): its Settings and
     * activity, with a pass for its settings screens (see GuardService). False if it couldn't open.
     */
    fun settings(context: Context): Boolean {
        val pkg = installedInstagram(context) ?: return false
        val link = RulesRepository.get(context).rules.value?.appSettings?.link ?: "instagram://settings"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return false
        GuardPass.grant(SystemClock.elapsedRealtime(), SETTINGS_PASS_MS, GuardPass.Kind.SETTINGS)
        return true
    }

    private fun installedInstagram(context: Context): String? = InstagramPackages.ALL.firstOrNull { isInstalled(context, it) }

    /** Starts [intent] in a new task; false if nothing could open it. */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
