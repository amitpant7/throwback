package app.throwback.relay

import app.throwback.BuildConfig
import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.throwback.ThrowbackApp
import app.throwback.core.LockState
import app.throwback.ui.UiPrefs
import app.throwback.web.rules.NotificationRules
import app.throwback.web.rules.RulesRepository
import app.throwback.web.rules.UNKNOWN_KEEP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Messages & calls relay (DECISIONS.md #26). Android binds it while the user has granted
 * notification access.
 *
 * Only the Instagram apps' notifications are looked at; every other app's are ignored without being
 * read. Calls are never touched. Messages are replaced by Throwback's own notification, other types
 * follow the toggles in Settings. Nothing is stored and nothing leaves the phone: a notification's
 * sender and text are only copied into Throwback's replacement.
 *
 * Inactive (does nothing at all except following calls) until the setup wizard is finished.
 */
class RelayListenerService : NotificationListenerService() {

    // One notification at a time, in arrival order: a "removed" never overtakes its "posted".
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var classifier: Pair<NotificationRules?, NotificationClassifier>? = null

    override fun onListenerConnected() {
        val active = snapshotActive()
        scope.launch {
            val c = classifier()
            CallState.tracker.onConnected(active.filter { c.classify(it) == NotificationKind.CALL }.map { it.key }.toSet())
            // Notifications that arrived while the relay wasn't running are sorted now.
            active.forEach { handle(it, alreadyTracked = true) }
            MessagesFeature.sync(applicationContext)
        }
    }

    override fun onListenerDisconnected() {
        CallState.tracker.onDisconnected(SystemClock.elapsedRealtime())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isRelevant(sbn)) return
        val facts = factsOf(sbn)
        scope.launch { handle(facts, alreadyTracked = false) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isRelevant(sbn)) return
        val key = sbn.key
        scope.launch { CallState.tracker.onRemoved(key, SystemClock.elapsedRealtime()) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handle(facts: NotificationFacts, alreadyTracked: Boolean) {
        val rules = notificationRules()
        val kind = classifier().classify(facts)
        // Instagram started posting (a story or post made through a pass): the pass has done its job.
        endPassWhenPosting(facts)
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
        if (!alreadyTracked) {
            if (kind == NotificationKind.CALL) {
                CallState.tracker.onCallShowing(facts.key)
            } else {
                // An updated notification that stopped being a call (e.g. "Missed call") ends it.
                CallState.tracker.onRemoved(facts.key, SystemClock.elapsedRealtime())
            }
        }
        if (kind == NotificationKind.CALL) return
        if (!MessagesFeature.isSetUp(this)) return

        val target = if (kind == NotificationKind.MESSAGE || kind == NotificationKind.MISSED_CALL) RelayTarget.MESSAGES else RelayTarget.ACTIVITY
        val ctx = RelayContext(
            locked = (application as ThrowbackApp).timeLimit.state.value is LockState.Locked,
            extras = UiPrefs.from(this).extraNotifications.first(),
            canPost = RelayNotifier.canPost(this, target),
            keepUnknown = rules?.unknown == UNKNOWN_KEEP,
        )
        val groupHasCall = facts.groupSummary && facts.group != null && groupHasCall(facts)
        val action = RelayRouting.decide(kind, ctx, facts.groupSummary, groupHasCall)
        if (!facts.groupSummary && kind != NotificationKind.MESSAGE && kind != NotificationKind.MISSED_CALL &&
            kind != NotificationKind.CALL && kind != NotificationKind.KEEP
        ) {
            RelayStats.recordOtherGroup(this) // for the "quieter phone" suggestion; a count only
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "${kind.ruleName}${if (facts.groupSummary) " (summary)" else ""} -> $action")
        when (action) {
            RelayAction.Keep -> Unit
            RelayAction.Cancel -> cancel(facts.key)
            is RelayAction.Relay -> {
                val url = when (action.target) {
                    RelayTarget.MESSAGES -> RelayLinks.threadUrl(facts, rules?.threadId) ?: messagesUrl()
                    RelayTarget.ACTIVITY -> rules?.activityUrl ?: app.throwback.web.rules.DEFAULT_ACTIVITY_URL
                }
                RelayNotifier.post(this, facts, kind, action.target, url)
                cancel(facts.key)
            }
        }
    }

    private fun groupHasCall(summary: NotificationFacts): Boolean {
        val c = classifier ?: return true // be safe: keep the summary
        return snapshotActive().any {
            it.key != summary.key && it.packageName == summary.packageName && it.group == summary.group &&
                c.second.classify(it) == NotificationKind.CALL
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

    private fun isRelevant(sbn: StatusBarNotification): Boolean =
        sbn.packageName in InstagramPackages.ALL || RelayDebug.isFake(sbn.packageName, packageName, sbn.tag)

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

    private fun endPassWhenPosting(facts: NotificationFacts) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!app.throwback.guard.GuardPass.active(now)) return
        val kind = app.throwback.guard.GuardPass.kind
        if (kind != app.throwback.guard.GuardPass.Kind.STORY && kind != app.throwback.guard.GuardPass.Kind.CREATE) return
        val pattern = app.throwback.web.rules.RulesRepository.get(this).rules.value?.appCreate?.story?.postingChannels ?: return
        val channel = facts.channelId ?: return
        if (facts.packageName in InstagramPackages.ALL && runCatching { Regex(pattern).containsMatchIn(channel) }.getOrDefault(false)) {
            Log.i(TAG, "Instagram is posting: pass ends")
            app.throwback.guard.GuardPass.end()
            if (kind == app.throwback.guard.GuardPass.Kind.STORY) CallInApp.storyForgiven(this)
        }
    }

}
