package app.throwback.connect

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import app.throwback.R
import app.throwback.web.PageFeatures
import app.throwback.web.rules.AppStoryRules
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.flow.update

/**
 * Throwback's ways into the real Instagram app, each with a guard pass (DECISIONS.md #27a, #27c,
 * #27e, #27f). How often each can be used is kept in [PassLedger], on the time limit's trusted clock.
 *
 * Calls start in the Instagram app: its mobile website has no call buttons (only the desktop site
 * does). The chat's call button opens that chat in the app with a short pass; once the call runs and
 * its call screen is up the pass ends and the call keeps its own screens allowed (#34y); after
 * hanging up the guard sends the user back to Throwback.
 */
internal object CallInApp {
    /** Long enough to tap call; the running call then keeps its screens allowed by itself. */
    const val PASS_MS = 15_000L

    /** The call button's outcome and what to tell the user (NOT_READY: the time limit's clock, which counts the taps, is still loading). */
    enum class Result(@StringRes val message: Int?) {
        OPENED(null),
        NO_APP(R.string.call_needs_instagram_app),
        LIMITED(R.string.call_too_many),
        NOT_READY(R.string.call_not_ready),
    }

    /**
     * The chat the call button last opened (thread id, username): "Back to your Instagram call"
     * reopens it, where Instagram shows its return-to-call banner (CallReturn).
     */
    @Volatile
    var lastChat: Pair<String?, String?>? = null
        private set

    /** [countTap] false: the way back to a running call ("Back to your Instagram call"), not limited. */
    fun open(context: Context, thread: String?, user: String? = null, countTap: Boolean = true): Result {
        val pkg = InstagramPackages.installed(context) ?: return Result.NO_APP
        // Instagram not connected: no guard, so no pass and nothing to count (still loading: count).
        val guarded = MessagesFeature.setUpNow != false
        val now = if (guarded) PassLedger.now(context) ?: return Result.NOT_READY else null
        if (now != null && countTap && !PassLedger.read(context).callAllowed(now.wallMillis)) return Result.LIMITED
        // ig.me/m/<username> opens a chat with that person from whichever account the Instagram app
        // uses (a chat id only exists in one account); the chat id is for group chats.
        val byUser = user?.let { Intent(Intent.ACTION_VIEW, "https://ig.me/m/$it".toUri()).setPackage(pkg) }
        val byThread = thread?.let {
            Intent(Intent.ACTION_VIEW, "https://www.instagram.com/direct/t/$it/".toUri()).setPackage(pkg)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        for (intent in listOfNotNull(byUser, byThread, launch)) {
            if (start(context, intent)) {
                if (intent !== launch) lastChat = thread to user
                // Only once Instagram really opens.
                if (now != null) {
                    GuardPass.grant(SystemClock.elapsedRealtime(), PASS_MS, GuardPass.Kind.CALL)
                    if (countTap) PassLedger.update(context) { it.callTapped(now.wallMillis) }
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
        if (!start(context, Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(pkg))) return AppPass.NO_APP
        GuardPass.grant(SystemClock.elapsedRealtime(), CREATE_PASS_MS, GuardPass.Kind.CREATE)
        PassLedger.update(context) { it.createOpened(now.epochDay) }
        return AppPass.OPENED
    }

    /** OPENED; NO_APP; WAIT (the story budget is used up: its lock); NOT_READY (the time limit is still loading); LOCKED (the daily limit). */
    enum class StoryResult { OPENED, NO_APP, WAIT, NOT_READY, LOCKED }

    /** What to tell the user after "Your story" ([story]'s result). */
    fun storyMessage(context: Context, result: StoryResult): String {
        val res = context.resources
        return when (result) {
            StoryResult.OPENED -> storyMinutes(context).let { m -> res.getQuantityString(R.plurals.story_in_app_hint, m, m) }
            StoryResult.WAIT -> storyLockMinutes(context).coerceAtLeast(1).let { m -> res.getQuantityString(R.plurals.story_in_app_wait, m, m) }
            StoryResult.NOT_READY -> res.getString(R.string.story_in_app_not_ready)
            StoryResult.LOCKED -> res.getString(R.string.app_pass_locked)
            StoryResult.NO_APP -> res.getString(R.string.create_in_app_failed)
        }
    }

    /**
     * What to tell the user when the page sent "Your story" to the website because it believed the
     * app route was closed (the story budget's lock). It may be a step behind (the lock just ended).
     */
    fun storyWebMessage(context: Context): String {
        val minutes = storyLockMinutes(context)
        val res = context.resources
        return if (minutes > 0) res.getQuantityString(R.plurals.story_web_wait, minutes, minutes) else res.getString(R.string.story_web_ready)
    }

    /** The rules' story numbers, or their defaults (5 / 10 / 2 / 30 minutes, the posting channels). */
    fun storyRules(context: Context): AppStoryRules = RulesRepository.get(context).rules.value?.appCreate?.story ?: AppStoryRules()

    /** Minutes of Instagram one story pass gives. */
    fun storyMinutes(context: Context) = storyRules(context).editMinutes

    private fun storyBudgetMillis(context: Context) = storyRules(context).budgetMinutes * 60_000L
    private fun storyPostMillis(context: Context) = storyRules(context).postMinutes * 60_000L
    private fun storyLockMillis(context: Context) = storyRules(context).lockMinutes * 60_000L

    /**
     * Whether "Your story" can open the Instagram app now (else the website's story upload). Only
     * tells the page where to send the tap; [story] checks again. So while the time limit is still
     * loading its state (at start), it says yes rather than send the page to the website for a minute.
     */
    fun storyAvailable(context: Context): Boolean {
        val now = PassLedger.now(context) ?: return true
        return PassLedger.read(context).storyAllowed(now.wallMillis)
    }

    /** Today's use of the Instagram app through Throwback's passes ("Your day"). */
    data class AppDay(
        val storyUsedMillis: Long,
        val storyBudgetMillis: Long,
        val storyLockMinutes: Int,
        val visitUsed: Boolean,
        val appMillis: Long,
    )

    fun appDay(context: Context): AppDay {
        val budget = storyBudgetMillis(context)
        val now = PassLedger.now(context) ?: return AppDay(0L, budget, 0, visitUsed = false, appMillis = 0L)
        val book = PassLedger.read(context)
        return AppDay(
            storyUsedMillis = book.storyUsed(now.wallMillis),
            storyBudgetMillis = budget,
            storyLockMinutes = book.storyLockMinutes(now.wallMillis),
            visitUsed = !book.visitAllowed(now.epochDay, locked = false),
            appMillis = book.appMillisOn(now.epochDay),
        )
    }

    /** Called by the guard: [millis] more of the Instagram app in front during a pass today. */
    fun addAppTime(context: Context, millis: Long) {
        val now = PassLedger.now(context) ?: return
        PassLedger.update(context) { it.withAppTime(now.epochDay, millis) }
    }

    /** Minutes left of the story budget's lock (0: not locked). */
    fun storyLockMinutes(context: Context): Int {
        val now = PassLedger.now(context) ?: return 0
        return PassLedger.read(context).storyLockMinutes(now.wallMillis)
    }

    /**
     * "Your story": the Instagram app with a STORY pass of [storyMinutes] (the guard ends it then, or
     * when the user leaves Instagram). The pass is recorded as open in the ledger and charged once,
     * when it closes ([storyEnded], [storyPosted], [settleOpenStory]); while the story budget is
     * locked, the website's own upload instead (DECISIONS.md #34cc).
     */
    fun story(context: Context): StoryResult {
        val pkg = InstagramPackages.installed(context) ?: return StoryResult.NO_APP
        val now = PassLedger.now(context) ?: return StoryResult.NOT_READY
        if (PassLedger.locked(context) == true) return StoryResult.LOCKED
        settleOpenStory(context) // a pass that ended without being charged counts first
        if (!PassLedger.read(context).storyAllowed(now.wallMillis)) return StoryResult.WAIT
        if (!start(context, Intent(Intent.ACTION_VIEW, storyRules(context).link.toUri()).setPackage(pkg))) return StoryResult.NO_APP
        synchronized(this) {
            // A little longer than the pass itself: the guard ends it after its minutes in Instagram,
            // which start once Instagram is in front, not at the tap.
            GuardPass.grant(SystemClock.elapsedRealtime(), (storyMinutes(context) + 1) * 60_000L, GuardPass.Kind.STORY)
            openedElapsed = SystemClock.elapsedRealtime()
            storyMaybeOpen = true
            PassLedger.update(context) { it.storyOpened(now.wallMillis) }
        }
        return StoryResult.OPENED
    }

    /**
     * The guard: the story pass ended without a post (left Instagram, its minutes ran out, a call
     * came): charged [spentMillis]. Nothing if it was already charged (posted).
     */
    @Synchronized
    fun storyEnded(context: Context, spentMillis: Long) {
        val now = PassLedger.now(context) ?: return
        closeOpenStory(context, now.wallMillis, spentMillis)
    }

    /**
     * Called by the guard on every check: a story pass that is open in the ledger but no longer
     * running (it ran out, another pass replaced it, or Throwback was stopped during it) is charged
     * its time since it opened, at most a whole pass: how it was used can't be known.
     */
    @Synchronized
    fun settleOpenStory(context: Context) {
        if (storyMaybeOpen == false) return
        if (GuardPass.kind == GuardPass.Kind.STORY && GuardPass.active(SystemClock.elapsedRealtime())) return
        val now = PassLedger.now(context) ?: return
        val openAt = PassLedger.read(context).storyOpenAt
        if (openAt == null) {
            storyMaybeOpen = false
            return
        }
        Log.i(TAG, "a story pass ended without being charged: charged its time")
        closeOpenStory(context, now.wallMillis, (now.wallMillis - openAt).coerceIn(0L, storyMinutes(context) * 60_000L))
    }

    /**
     * Instagram's posting notification ([madeAtWall]: the notification's own time, phone clock). Made
     * after the open pass began, it is that pass's post: the pass closes, charged the post amount.
     * Made earlier, or with no pass open, it is a post from the pass that ended moments before
     * ([LATE_POST_MS]: the user left while it was posting), which is then charged the post instead of
     * its time. Otherwise (an older upload, a progress update) it is ignored.
     */
    @Synchronized
    fun storyPosted(context: Context, madeAtWall: Long) {
        val now = PassLedger.now(context) ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val madeAt = if (madeAtWall > 0) nowElapsed - (System.currentTimeMillis() - madeAtWall) else nowElapsed
        val book = PassLedger.read(context)
        val opened = openedElapsed
        val forOpenPass = book.storyOpenAt != null && (opened == null || madeAt >= opened - POST_CLOCK_SLACK_MS)
        val late = lastStoryEnd?.takeIf { nowElapsed - it.at <= LATE_POST_MS }
        when {
            forOpenPass -> {
                if (GuardPass.kind == GuardPass.Kind.STORY) GuardPass.endIfActive(nowElapsed)
                closeOpenStory(context, now.wallMillis, storyPostMillis(context), posted = true)
            }
            // Nothing was charged since that pass: undo its time, charge the post instead.
            late != null && book.storyUsedMillis == late.usedAfter && book.storyLockUntil == late.lockAfter -> {
                lastStoryEnd = null
                PassLedger.update(context) {
                    it.copy(storyUsedMillis = late.usedBefore, storyLockUntil = late.lockBefore)
                        .storyCharged(now.wallMillis, storyPostMillis(context), storyBudgetMillis(context), storyLockMillis(context))
                }
                storyAvailabilityChanged(context)
            }
        }
    }

    /** Charges and closes the open story pass (the caller holds the lock); a pass without a post is remembered for a late post. */
    private fun closeOpenStory(context: Context, wall: Long, cost: Long, posted: Boolean = false) {
        storyMaybeOpen = false
        openedElapsed = null
        val before = PassLedger.read(context)
        if (before.storyOpenAt == null) return
        val budget = storyBudgetMillis(context)
        val lock = storyLockMillis(context)
        PassLedger.update(context) { it.storyClosed(wall, cost, budget, lock) }
        val after = before.storyClosed(wall, cost, budget, lock)
        lastStoryEnd = if (posted) null else StoryEnd(SystemClock.elapsedRealtime(), before.storyUsedMillis, before.storyLockUntil, after.storyUsedMillis, after.storyLockUntil)
        storyAvailabilityChanged(context)
    }

    /** A story pass that ended without a post: when (elapsed ms), and the budget before and after its charge. */
    private class StoryEnd(val at: Long, val usedBefore: Long, val lockBefore: Long?, val usedAfter: Long, val lockAfter: Long?)

    /** Guarded by this object's lock. */
    private var lastStoryEnd: StoryEnd? = null

    /** Whether a story pass may be open in the ledger (null: not looked since the process started). */
    @Volatile
    private var storyMaybeOpen: Boolean? = null

    /** When this process opened the current story pass (elapsed ms), to match posting notifications to it. */
    @Volatile
    private var openedElapsed: Long? = null

    /** Instagram's posting notification may come this long after the user left the editor. */
    private const val LATE_POST_MS = 90_000L

    /** The phone's clock and the elapsed clock can disagree by this much. */
    private const val POST_CLOCK_SLACK_MS = 2_000L

    private const val TAG = "ThrowbackPasses"

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
        if (!start(context, Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(pkg))) return AppPass.NO_APP
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
