package app.throwback.relay

import app.throwback.web.rules.NotificationMatch
import app.throwback.web.rules.NotificationRules

/** Which Instagram app packages the relay and the guard look at. Nothing else is ever read. */
internal object InstagramPackages {
    const val APP = "com.instagram.android"
    const val LITE = "com.instagram.lite"
    val ALL = setOf(APP, LITE)
}

/**
 * The parts of an Instagram notification the classifier looks at, copied out of a
 * StatusBarNotification (see RelayListenerService.factsOf) so the logic is plain Kotlin.
 * Title and text are only carried along for the relayed notification; they never influence routing.
 */
internal data class NotificationFacts(
    val key: String,
    val packageName: String,
    val channelId: String? = null,
    val category: String? = null,
    /** extras "android.template", e.g. "android.app.Notification$MessagingStyle". */
    val template: String? = null,
    val group: String? = null,
    val tag: String? = null,
    val shortcutId: String? = null,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val fullScreenIntent: Boolean = false,
    val title: CharSequence? = null,
    val text: CharSequence? = null,
)

/** What an Instagram notification is. Names match the rules' "kind" values. */
internal enum class NotificationKind(val ruleName: String) {
    CALL("call"),
    MESSAGE("message"),
    MISSED_CALL("missedCall"),
    LIKES("likes"),
    COMMENTS("comments"),
    FOLLOWS("follows"),
    MENTIONS("mentions"),
    LIVE("live"),
    OTHER("other"),

    /** Left exactly as Instagram posted it (security and login alerts): never removed or relayed. */
    KEEP("keep"),

    /** Nothing recognised it; the rules' "unknown" setting decides. */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromRule(name: String): NotificationKind? = entries.firstOrNull { it.ruleName == name && it != UNKNOWN }
    }
}

/**
 * Sorts Instagram notifications into calls, messages and other types (DECISIONS.md #26).
 *
 * Order, most certain first:
 * 1. Android's own call signals: category "call", CallStyle, a full-screen intent. Always a call;
 *    no rule can override this, so a rules edit can never get a call removed.
 * 2. The rules' `classify` table (filters/rules.json, "notifications"), first match wins. A rule
 *    can't make a message (category "msg" or MessagingStyle) anything that is removed: a renamed
 *    chat channel would otherwise fall to the rules' catch-all "other" and every message be lost.
 * 3. Built-in defaults: category "missed_call", category "msg" or MessagingStyle, ongoing = call.
 * 4. Otherwise [NotificationKind.UNKNOWN].
 */
internal class NotificationClassifier(rules: NotificationRules?) {

    private class CompiledMatch(
        val kind: NotificationKind,
        val channel: Regex?,
        val category: String?,
        val template: Regex?,
        val group: Regex?,
        val tag: Regex?,
        val ongoing: Boolean?,
    )

    // The rules are validated before use; a pattern that still fails to compile just drops its rule.
    private val table: List<CompiledMatch> = rules?.classify.orEmpty().mapNotNull { it.compile() }

    fun classify(f: NotificationFacts): NotificationKind {
        if (isCertainCall(f)) return NotificationKind.CALL
        val ruled = table.firstOrNull { it.matches(f) }?.kind
        if (ruled != null && (ruled in KEEP_MESSAGES || !isMessage(f))) return ruled
        return when {
            f.category == CATEGORY_MISSED_CALL -> NotificationKind.MISSED_CALL
            isMessage(f) -> NotificationKind.MESSAGE
            // Instagram shows nothing else as ongoing that the hidden app would post; when in doubt,
            // treat it as a call (kept, and the guard lets the app open).
            f.ongoing -> NotificationKind.CALL
            else -> NotificationKind.UNKNOWN
        }
    }

    private fun CompiledMatch.matches(f: NotificationFacts): Boolean =
        (channel == null || (f.channelId != null && channel.containsMatchIn(f.channelId))) &&
            (category == null || category == f.category) &&
            (template == null || (f.template != null && template.containsMatchIn(f.template))) &&
            (group == null || (f.group != null && group.containsMatchIn(f.group))) &&
            (tag == null || (f.tag != null && tag.containsMatchIn(f.tag))) &&
            (ongoing == null || ongoing == f.ongoing)

    private fun NotificationMatch.compile(): CompiledMatch? = try {
        val k = NotificationKind.fromRule(kind) ?: return null
        CompiledMatch(
            kind = k,
            channel = channel?.let(::Regex),
            category = category,
            template = template?.let(::Regex),
            group = group?.let(::Regex),
            tag = tag?.let(::Regex),
            ongoing = ongoing,
        )
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        // The values of android.app.Notification.CATEGORY_* (plain strings, so tests run on the JVM).
        const val CATEGORY_CALL = "call"
        const val CATEGORY_MESSAGE = "msg"
        const val CATEGORY_MISSED_CALL = "missed_call"
        private const val CALL_STYLE = "\$CallStyle"
        private const val MESSAGING_STYLE = "\$MessagingStyle"

        /** Kinds a rule may give a message: all of them keep it (kept, or relayed to the inbox). */
        private val KEEP_MESSAGES = setOf(NotificationKind.CALL, NotificationKind.MESSAGE, NotificationKind.MISSED_CALL, NotificationKind.KEEP)

        /** Android's own signals for a call. */
        private fun isCertainCall(f: NotificationFacts): Boolean =
            f.category == CATEGORY_CALL || f.template?.endsWith(CALL_STYLE) == true || f.fullScreenIntent

        /** Android's own signals for a message. */
        private fun isMessage(f: NotificationFacts): Boolean =
            f.category == CATEGORY_MESSAGE || f.template?.endsWith(MESSAGING_STYLE) == true
    }
}
