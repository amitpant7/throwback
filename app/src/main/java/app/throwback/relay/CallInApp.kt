package app.throwback.relay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import app.throwback.R
import app.throwback.guard.GuardPass
import app.throwback.guard.PassLedger
import app.throwback.web.PageFeatures
import kotlinx.coroutines.flow.update
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

    /** The call button's outcome and what to tell the user (NOT_READY: the time limit's clock, which counts the taps, is still loading). */
    enum class Result(@StringRes val message: Int?) {
        OPENED(null),
        NO_APP(R.string.call_needs_instagram_app),
        LIMITED(R.string.call_too_many),
        NOT_READY(R.string.call_not_ready),
    }

    fun open(context: Context, thread: String?, user: String? = null): Result {
        val pkg = InstagramPackages.installed(context) ?: return Result.NO_APP
        // Instagram not connected: no guard, so no pass and nothing to count (still loading: count).
        val guarded = MessagesFeature.setUpNow != false
        val now = if (guarded) PassLedger.now(context) ?: return Result.NOT_READY else null
        if (now != null && !PassLedger.read(context).callAllowed(now.wallMillis)) return Result.LIMITED
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
                if (now != null) {
                    GuardPass.grant(SystemClock.elapsedRealtime(), PASS_MS, GuardPass.Kind.CALL)
                    PassLedger.update(context) { it.callTapped(now.wallMillis) }
                }
                return Result.OPENED
            }
        }
        return Result.NO_APP
    }

    /** "Open Instagram for 5 minutes" (switch accounts, change its settings). */
    const val VISIT_PASS_MS = 5 * 60_000L

    /** NOT_READY: the time limit (which counts the visit) is still loading, a moment after start. */
    enum class VisitResult { OPENED, NO_APP, USED_TODAY, LOCKED, NOT_READY }

    /**
     * Opens the Instagram app with a pass of up to [VISIT_PASS_MS]: once a day, not while the daily
     * limit is reached, and it ends when the user leaves Instagram.
     */
    fun visit(context: Context): VisitResult {
        val pkg = InstagramPackages.installed(context) ?: return VisitResult.NO_APP
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return VisitResult.NO_APP
        val now = PassLedger.now(context) ?: return VisitResult.NOT_READY
        val locked = PassLedger.locked(context) == true
        if (!PassLedger.read(context).visitAllowed(now.epochDay, locked)) {
            return if (locked) VisitResult.LOCKED else VisitResult.USED_TODAY
        }
        if (!start(context, launch)) return VisitResult.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), VISIT_PASS_MS, GuardPass.Kind.VISIT)
        PassLedger.update(context) { it.visited(now.epochDay) }
        return VisitResult.OPENED
    }

    /** A post-editor or settings pass: opened, or why not, with the message to show then. */
    enum class AppPass(@StringRes val message: Int?) {
        OPENED(null),
        NO_APP(R.string.create_in_app_failed),
        LOCKED(R.string.app_pass_locked),
        CREATE_USED_UP(R.string.create_in_app_used_up),
        SETTINGS_USED_UP(R.string.settings_in_app_used_up),

        /** The time limit, which counts the passes, is still loading. */
        NOT_READY(R.string.app_pass_not_ready),
    }

    /** Longest a create pass lasts (it normally ends when Instagram leaves its create screens). */
    const val CREATE_PASS_MS = 20 * 60_000L

    /**
     * "+" with the Instagram app connected: its post editor (the better one), with a pass that only
     * allows the creation screens (see GuardService). [PassBook.CREATE_PER_DAY] a day, and not while
     * the daily limit is reached.
     */
    fun create(context: Context): AppPass {
        val pkg = InstagramPackages.installed(context) ?: return AppPass.NO_APP
        val now = PassLedger.now(context) ?: return AppPass.NOT_READY
        if (PassLedger.locked(context) == true) return AppPass.LOCKED
        if (!PassLedger.read(context).createAllowed(now.epochDay)) return AppPass.CREATE_USED_UP
        val link = RulesRepository.get(context).rules.value?.appCreate?.link ?: "instagram://share"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return AppPass.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), CREATE_PASS_MS, GuardPass.Kind.CREATE)
        PassLedger.update(context) { it.createOpened(now.epochDay) }
        return AppPass.OPENED
    }

    /** Longest a story pass lasts; its minutes without the camera end it much sooner (see GuardService). */
    private const val STORY_PASS_MS = 30 * 60_000L

    /** OPENED; NO_APP; USED_UP (no more today); WAIT (the wait after a pass); NOT_READY (the time limit is still loading); LOCKED. */
    enum class StoryResult { OPENED, NO_APP, USED_UP, WAIT, NOT_READY, LOCKED }

    /** What to tell the user after "Your story" ([story]'s result). */
    fun storyMessage(context: Context, result: StoryResult): String {
        val res = context.resources
        return when (result) {
            StoryResult.OPENED -> storyMinutes(context).let { m -> res.getQuantityString(R.plurals.story_in_app_hint, m, m) }
            StoryResult.USED_UP -> storiesPerDay(context).let { n -> res.getQuantityString(R.plurals.story_in_app_used_up, n, n) }
            StoryResult.WAIT -> (storyWaitMinutes(context) ?: 1).coerceAtLeast(1).let { m -> res.getQuantityString(R.plurals.story_in_app_wait, m, m) }
            StoryResult.NOT_READY -> res.getString(R.string.story_in_app_not_ready)
            StoryResult.LOCKED -> res.getString(R.string.app_pass_locked)
            StoryResult.NO_APP -> res.getString(R.string.create_in_app_failed)
        }
    }

    /**
     * What to tell the user when the page sent "Your story" to the website because it believed the
     * app route was closed. It may be a step behind (a wait just ended): then it says so.
     */
    fun storyWebMessage(context: Context): String {
        val res = context.resources
        val minutes = storyWaitMinutes(context)
        return when {
            minutes == null -> storiesPerDay(context).let { n -> res.getQuantityString(R.plurals.story_web_tomorrow, n, n) }
            minutes > 0 -> res.getQuantityString(R.plurals.story_web_wait, minutes, minutes)
            else -> res.getString(R.string.story_web_ready)
        }
    }

    private fun storyRules(context: Context) = RulesRepository.get(context).rules.value?.appCreate?.story

    fun storiesPerDay(context: Context) = storyRules(context)?.perDay ?: 3

    /** Minutes of Instagram a story pass gives (time with Instagram's camera on doesn't count). */
    fun storyMinutes(context: Context) = storyRules(context)?.editMinutes ?: 5

    /**
     * Whether "Your story" can open the Instagram app now (else the website's story upload). Only
     * tells the page where to send the tap; [story] checks again. So while the time limit is still
     * loading its state (at start), it says yes rather than send the page to the website for a minute.
     */
    fun storyAvailable(context: Context): Boolean {
        val now = PassLedger.now(context) ?: return true
        return PassLedger.read(context).storyAllowed(now.epochDay, now.wallMillis, storiesPerDay(context))
    }

    /** Today's use of the Instagram app through Throwback's passes ("Your day"). */
    data class AppDay(val storiesUsed: Int, val storiesMax: Int, val storyWaitMinutes: Int?, val visitUsed: Boolean, val appMillis: Long)

    fun appDay(context: Context): AppDay {
        val perDay = storiesPerDay(context)
        val now = PassLedger.now(context) ?: return AppDay(0, perDay, 0, visitUsed = false, appMillis = 0L)
        val book = PassLedger.read(context)
        return AppDay(
            storiesUsed = book.storiesOn(now.epochDay),
            storiesMax = perDay,
            storyWaitMinutes = book.storyWaitMinutes(now.epochDay, now.wallMillis, perDay),
            visitUsed = !book.visitAllowed(now.epochDay, locked = false),
            appMillis = book.appMillisOn(now.epochDay),
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
        val pkg = InstagramPackages.installed(context) ?: return StoryResult.NO_APP
        val now = PassLedger.now(context) ?: return StoryResult.NOT_READY
        if (PassLedger.locked(context) == true) return StoryResult.LOCKED
        val ledger = PassLedger.read(context)
        if (!ledger.storyAllowed(now.epochDay, now.wallMillis, storiesPerDay(context))) {
            val wait = ledger.storyWaitMinutes(now.epochDay, now.wallMillis, storiesPerDay(context))
            return if (wait != null && wait > 0) StoryResult.WAIT else StoryResult.USED_UP
        }
        val link = storyRules(context)?.link ?: "instagram://story-camera"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return StoryResult.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), STORY_PASS_MS, GuardPass.Kind.STORY)
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

    /** Tells the page whether "Your story" opens the app now (only with the Instagram app connected). */
    fun storyAvailabilityChanged(context: Context) {
        val available = storyAvailable(context)
        PageFeatures.current.update { it.copy(storyInApp = it.createInApp && available) }
    }

    /** Longest a settings pass lasts. */
    const val SETTINGS_PASS_MS = 10 * 60_000L

    /**
     * "Open settings in the Instagram app" (end of Instagram's settings page): its Settings and
     * activity, with a pass for its settings screens (see GuardService). The settings screen also
     * holds Saved, Archive and Your activity, hence [SETTINGS_PASS_MS], [PassBook.SETTINGS_PER_DAY]
     * a day, and not while the daily limit is reached.
     */
    fun settings(context: Context): AppPass {
        val pkg = InstagramPackages.installed(context) ?: return AppPass.NO_APP
        val now = PassLedger.now(context) ?: return AppPass.NOT_READY
        if (PassLedger.locked(context) == true) return AppPass.LOCKED
        if (!PassLedger.read(context).settingsAllowed(now.epochDay)) return AppPass.SETTINGS_USED_UP
        val link = RulesRepository.get(context).rules.value?.appSettings?.link ?: "instagram://settings"
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))) return AppPass.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), SETTINGS_PASS_MS, GuardPass.Kind.SETTINGS)
        PassLedger.update(context) { it.settingsOpened(now.epochDay) }
        return AppPass.OPENED
    }

    /** Starts [intent] in a new task; false if nothing could open it. */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
