package app.throwback.relay

import android.app.Notification
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.throwback.BuildConfig
import app.throwback.guard.GuardPass
import app.throwback.guard.PassLedger
import app.throwback.ui.UiPrefs
import app.throwback.web.rules.DEFAULT_ACTIVITY_URL
import app.throwback.web.rules.NotificationRules
import app.throwback.web.rules.RulesRepository
import app.throwback.web.rules.UNKNOWN_KEEP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Messages & calls relay (DECISIONS.md #26). Android binds it while the user has granted
 * notification access.
 *
 * Only the Instagram apps' notifications in this profile are looked at; every other app's are
 * ignored without being read. Calls are never touched. Messages are replaced by Throwback's own notification, other types
 * follow the toggles in Settings. Nothing is stored and nothing leaves the phone: a notification's
 * sender and text are only copied into Throwback's replacement.
 *
 * Inactive (does nothing at all except following calls) until the setup wizard is finished.
 */
class RelayListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * What Android reports, and the notifications to relay: two queues, each handled in arrival order
     * and one item at a time to its end. (A dispatcher limited to one thread still interleaves
     * coroutines where they wait: while the rules or the time limit loaded, a call's "removed" ran
     * before its "posted", which kept the call, and Instagram, allowed for good; an older message
     * could replace a newer one.) Calls are followed in the first queue, so a relay waiting for the
     * time limit (up to 2 s at a cold start) never delays the guard seeing a call.
     */
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val toRelay = Channel<Pair<NotificationFacts, NotificationKind>>(Channel.UNLIMITED)
    @Volatile
    private var classifier: Pair<NotificationRules?, NotificationClassifier>? = null

    private sealed interface Event {
        class Connected(val active: List<NotificationFacts>) : Event
        class Posted(val facts: NotificationFacts) : Event
        class Removed(val key: String, val at: Long) : Event
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch { for (e in events) follow(e) }
        scope.launch { for ((facts, kind) in toRelay) relay(facts, kind) }
    }

    override fun onListenerConnected() {
        events.trySend(Event.Connected(snapshotActive()))
    }

    override fun onListenerDisconnected() {
        CallState.tracker.onDisconnected(SystemClock.elapsedRealtime())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (isRelevant(sbn)) events.trySend(Event.Posted(factsOf(sbn)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (isRelevant(sbn)) events.trySend(Event.Removed(sbn.key, SystemClock.elapsedRealtime()))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Calls (for the guard) and a pass ended by posting; then the notification goes to the relay. */
    private suspend fun follow(e: Event) {
        when (e) {
            is Event.Connected -> {
                val c = classifier()
                val sorted = e.active.map { it to c.classify(it) }
                fun keysOf(kind: NotificationKind) = sorted.filter { it.second == kind }.map { it.first.key }.toSet()
                CallState.tracker.onConnected(keysOf(NotificationKind.CALL), maybe = keysOf(NotificationKind.ONGOING))
                // Notifications that arrived while the relay wasn't running are sorted now.
                sorted.forEach { toRelay.send(it) }
                MessagesFeature.sync(applicationContext)
            }
            is Event.Posted -> {
                val kind = classifier().classify(e.facts)
                // Instagram started posting (a story or post made through a pass): the pass has done its job.
                endPassWhenPosting(e.facts)
                if (kind.isCall) {
                    CallState.tracker.onCallShowing(e.facts.key, sure = kind == NotificationKind.CALL)
                } else {
                    // An updated notification that stopped being a call (e.g. "Missed call") ends it.
                    CallState.tracker.onRemoved(e.facts.key, SystemClock.elapsedRealtime())
                }
                toRelay.send(e.facts to kind)
            }
            is Event.Removed -> {
                CallState.tracker.onRemoved(e.key, e.at)
                // And any call no longer showing: a removal Android never reported must not keep it going.
                activeKeys()?.let { CallState.tracker.keepOnly(it, e.at) }
            }
        }
    }

    private suspend fun relay(facts: NotificationFacts, kind: NotificationKind) {
        val rules = notificationRules()
        if (BuildConfig.DEBUG) {
            // Test builds: Instagram's real notification labels, to fill the rules from a real phone.
            // Labels only: never the title, text, names, tags, groups or thread ids.
            Log.d(
                LABELS_TAG,
                "pkg=${facts.packageName} channel=${facts.channelId} category=${facts.category} " +
                    "style=${facts.template?.substringAfterLast('$')} ongoing=${facts.ongoing} " +
                    "summary=${facts.groupSummary} fullScreen=${facts.fullScreenIntent} " +
                    "grouped=${facts.group != null} -> ${kind.ruleName}",
            )
        }
        if (kind.isCall) return
        if (!MessagesFeature.isSetUp(this)) return

        val target = if (kind == NotificationKind.MESSAGE || kind == NotificationKind.MISSED_CALL) RelayTarget.MESSAGES else RelayTarget.ACTIVITY
        val ctx = RelayContext(
            locked = lockedNow(),
            extras = UiPrefs.from(this).extraNotifications.first(),
            canPost = RelayNotifier.canPost(this, target),
            keepUnknown = rules?.unknown == UNKNOWN_KEEP,
        )
        val groupHasCall = facts.groupSummary && facts.group != null && groupHasCall(facts)
        val action = RelayRouting.decide(kind, ctx, facts.groupSummary, groupHasCall)
        if (!facts.groupSummary && kind != NotificationKind.MESSAGE && kind != NotificationKind.MISSED_CALL && kind != NotificationKind.KEEP) {
            RelayStats.recordOtherGroup(this) // for the "quieter phone" suggestion; a count only
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "${kind.ruleName}${if (facts.groupSummary) " (summary)" else ""} -> $action")
        when (action) {
            RelayAction.Keep -> Unit
            RelayAction.Cancel -> cancel(facts.key)
            is RelayAction.Relay -> {
                val url = when (action.target) {
                    RelayTarget.MESSAGES -> RelayLinks.threadUrl(facts, rules?.threadId) ?: messagesUrl()
                    RelayTarget.ACTIVITY -> rules?.activityUrl ?: DEFAULT_ACTIVITY_URL
                }
                RelayNotifier.post(this, facts, kind, action.target, url)
                cancel(facts.key)
            }
        }
    }

    /** The daily limit is reached right now, once the time limit has loaded (else: not locked). */
    private suspend fun lockedNow(): Boolean {
        PassLedger.awaitLoaded(this, LOAD_WAIT_MS)
        return PassLedger.locked(this) == true
    }

    private fun groupHasCall(summary: NotificationFacts): Boolean {
        val c = classifier ?: return true // be safe: keep the summary
        return snapshotActive().any {
            it.key != summary.key && it.packageName == summary.packageName && it.group == summary.group &&
                c.second.classify(it).isCall
        }
    }

    private fun cancel(key: String) {
        try {
            cancelNotification(key)
        } catch (e: SecurityException) {
            Log.w(TAG, "listener not connected, could not remove a notification")
        }
    }

    private fun snapshotActive(): List<NotificationFacts> = try {
        activeNotifications.orEmpty().filter(::isRelevant).map(::factsOf)
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: RuntimeException) {
        // The system service can throw while the listener is being (un)bound.
        emptyList()
    }

    /** Keys of the relevant notifications showing now, or null if Android won't say. */
    private fun activeKeys(): Set<String>? = try {
        activeNotifications.orEmpty().filter(::isRelevant).map { it.key }.toSet()
    } catch (e: RuntimeException) {
        null // not connected (SecurityException) or being (un)bound
    }

    /**
     * Instagram's notifications in this profile only. A second copy of Instagram (work profile, dual
     * apps, Private Space) isn't covered (DECISIONS.md #34f): its notifications are left alone, and
     * its calls don't open this one.
     */
    private fun isRelevant(sbn: StatusBarNotification): Boolean =
        sbn.user == Process.myUserHandle() &&
            (sbn.packageName in InstagramPackages.ALL || RelayDebug.isFake(sbn.packageName, packageName, sbn.tag))

    private suspend fun notificationRules(): NotificationRules? =
        RulesRepository.get(this).let { it.rules.value ?: it.load() }.notifications

    private suspend fun messagesUrl(): String = RulesRepository.get(this).let { it.rules.value ?: it.load() }.messagesUrl

    private suspend fun classifier(): NotificationClassifier {
        val rules = notificationRules()
        classifier?.let { (r, c) -> if (r == rules) return c }
        val debug = RelayDebug.extraMatches()
        val effective = if (debug.isEmpty()) rules else (rules ?: NotificationRules()).let { it.copy(classify = it.classify + debug) }
        return NotificationClassifier(effective).also { classifier = rules to it }
    }

    companion object {
        private const val TAG = "ThrowbackRelay"
        private const val LABELS_TAG = "ThrowbackRelayLabels"
        private const val LOAD_WAIT_MS = 2_000L

        /** Copies the fields the classifier and the relayed notification need; nothing is kept afterwards. */
        internal fun factsOf(sbn: StatusBarNotification): NotificationFacts {
            val n = sbn.notification
            val extras = n.extras
            return NotificationFacts(
                key = sbn.key,
                packageName = sbn.packageName,
                channelId = n.channelId,
                category = n.category,
                template = extras?.getString(Notification.EXTRA_TEMPLATE),
                group = n.group,
                tag = sbn.tag,
                shortcutId = n.shortcutId,
                ongoing = sbn.isOngoing,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                fullScreenIntent = n.fullScreenIntent != null,
                title = extras?.getCharSequence(Notification.EXTRA_TITLE),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT),
            )
        }
    }

    /** Ends a story or create pass when Instagram's posting notification shows. */
    private fun endPassWhenPosting(facts: NotificationFacts) {
        val now = SystemClock.elapsedRealtime()
        if (!GuardPass.active(now)) return
        val kind = GuardPass.kind
        if (kind != GuardPass.Kind.STORY && kind != GuardPass.Kind.CREATE) return
        val pattern = RulesRepository.get(this).rules.value?.appCreate?.story?.postingChannels ?: return
        val channel = facts.channelId ?: return
        if (facts.packageName in InstagramPackages.ALL && runCatching { Regex(pattern).containsMatchIn(channel) }.getOrDefault(false)) {
            Log.i(TAG, "Instagram is posting: pass ends")
            // Forgiven only if this ended it (the guard may just have ended it for leaving Instagram).
            if (GuardPass.endIfActive(now) && kind == GuardPass.Kind.STORY) CallInApp.storyPosted(this)
        }
    }
}
