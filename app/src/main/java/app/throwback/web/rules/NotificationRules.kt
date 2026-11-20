package app.throwback.web.rules

import app.throwback.core.WebMode
import app.throwback.web.UrlParts
import app.throwback.web.UrlRouter
import kotlinx.serialization.Serializable

/*
 * Optional rules section "notifications": how the Messages & calls relay (app.throwback.relay) sorts
 * the Instagram app's notifications (DECISIONS.md #26). Kept in its own file so the web area's
 * FilterRules only gains one field.
 *
 * Only the Instagram apps' notifications are ever looked at; their package names are fixed in the
 * app, not here, so a rules edit can never touch other apps. Android's own call signals
 * (Notification.CATEGORY_CALL, CallStyle, a full-screen intent) are checked before these rules: no
 * rule can turn a call into something that gets removed.
 */
@Serializable
data class NotificationRules(
    /** Checked in order after the built-in call signals; the first match decides. */
    val classify: List<NotificationMatch> = emptyList(),
    /** What happens to a notification nothing recognises: [UNKNOWN_CANCEL] or [UNKNOWN_KEEP]. */
    val unknown: String = UNKNOWN_CANCEL,
    /** How to find a chat's thread id in a message notification (null: relayed messages open the inbox). */
    val threadId: ThreadIdRule? = null,
    /** Where relayed likes/comments/follows/mentions/live notifications open. */
    val activityUrl: String = DEFAULT_ACTIVITY_URL,
    /** The guard keeps allowing the Instagram app this long after the last call notification goes away. */
    val callGraceSeconds: Int = DEFAULT_CALL_GRACE_SECONDS,
)

/**
 * Every field that is set must match (regexes use containsMatchIn; [category] is exact). At least one
 * field must be set. [kind] is one of [NOTIFICATION_KINDS].
 */
@Serializable
data class NotificationMatch(
    val kind: String,
    /** Regex on the notification channel id. */
    val channel: String? = null,
    /** Exact Notification.category, e.g. "msg", "call", "social". */
    val category: String? = null,
    /** Regex on the style class name (extras "android.template"), e.g. "MessagingStyle". */
    val template: String? = null,
    /** Regex on the notification's group key (Notification.getGroup()). */
    val group: String? = null,
    /** Regex on the posting tag. */
    val tag: String? = null,
    /** Ongoing (not dismissible) or not. */
    val ongoing: Boolean? = null,
)

/**
 * A chat's thread id, taken from one field of a message notification with [pattern]'s first group.
 * Only an id of 5 to 40 digits is used (anything else falls back to the inbox), so a wrong rule can
 * at worst open the inbox.
 */
@Serializable
data class ThreadIdRule(
    /** One of [THREAD_ID_SOURCES]: the conversation shortcut id, the posting tag or the group key. */
    val from: String,
    val pattern: String,
)

/** Values of [NotificationMatch.kind]. */
val NOTIFICATION_KINDS = setOf("call", "message", "missedCall", "likes", "comments", "follows", "mentions", "live", "other", "keep")
const val UNKNOWN_CANCEL = "cancel"
const val UNKNOWN_KEEP = "keep"
val THREAD_ID_SOURCES = setOf("shortcut", "tag", "group")
const val DEFAULT_ACTIVITY_URL = "https://www.instagram.com/accounts/activity/"
const val DEFAULT_CALL_GRACE_SECONDS = 5

/** Validation of the "notifications" section; called from [RulesValidator]. */
internal object NotificationRulesValidator {
    private const val MAX_RULES = 100
    private const val MAX_PATTERN = 300
    private const val MAX_TEXT = 100

    fun validate(rules: FilterRules, errors: MutableList<String>) {
        val n = rules.notifications ?: return
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) errors += message()
        }
        check(n.classify.size <= MAX_RULES) { "notifications.classify: too many rules" }
        n.classify.forEachIndexed { i, m ->
            val where = "notifications.classify[$i]"
            check(m.kind in NOTIFICATION_KINDS) { "$where: unknown kind '${m.kind}'" }
            check(listOf(m.channel, m.category, m.template, m.group, m.tag, m.ongoing).any { it != null }) {
                "$where: matches everything (set at least one field)"
            }
            listOfNotNull(m.channel, m.template, m.group, m.tag).forEach { checkPattern(it, where, errors) }
            m.category?.let { check(it.isNotBlank() && it.length <= MAX_TEXT) { "$where: bad category" } }
        }
        check(n.unknown == UNKNOWN_CANCEL || n.unknown == UNKNOWN_KEEP) { "notifications.unknown: bad value '${n.unknown}'" }
        n.threadId?.let { t ->
            check(t.from in THREAD_ID_SOURCES) { "notifications.threadId.from: bad value '${t.from}'" }
            if (checkPattern(t.pattern, "notifications.threadId.pattern", errors)) {
                check(java.util.regex.Pattern.compile(t.pattern).matcher("").groupCount() >= 1) {
                    "notifications.threadId.pattern needs a group"
                }
            }
        }
        check(n.callGraceSeconds in 0..120) { "notifications.callGraceSeconds out of range" }

        // The activity page must be an Instagram page that the full mode actually shows.
        if (errors.isEmpty()) {
            val parts = UrlParts.parse(n.activityUrl)
            val router = UrlRouter(rules)
            check(parts != null && parts.scheme == "https" && router.isInstagramHost(parts.host)) {
                "notifications.activityUrl is not an https Instagram URL"
            }
            check(router.decide(n.activityUrl, WebMode.FULL, currentUrl = null) == UrlRouter.Decision.Load) {
                "notifications.activityUrl is not allowed in FULL mode"
            }
        }
    }

    /** @return true when [pattern] is a usable regex. */
    private fun checkPattern(pattern: String, where: String, errors: MutableList<String>): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN) {
            errors += "$where: pattern length"
            return false
        }
        return try {
            Regex(pattern)
            true
        } catch (e: IllegalArgumentException) {
            errors += "$where: invalid pattern '$pattern'"
            false
        }
    }
}
