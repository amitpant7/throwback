package app.throwback.relay

import app.throwback.ui.ExtraNotification

/** What the relay does with one Instagram notification. */
internal sealed interface RelayAction {
    /** Leave Instagram's notification exactly as it is (calls, and safe fallbacks). */
    data object Keep : RelayAction

    /** Remove Instagram's notification. */
    data object Cancel : RelayAction

    /** Remove Instagram's notification and post Throwback's own, opening [target]. */
    data class Relay(val target: RelayTarget, val kind: NotificationKind) : RelayAction
}

internal enum class RelayTarget {
    /** The inbox or a chat; messages-only mode when the time limit is locked. */
    MESSAGES,

    /** Instagram's activity page (likes, comments, follows, mentions, live). */
    ACTIVITY,
}

/** Everything the routing decision depends on besides the notification's kind. */
internal data class RelayContext(
    /** The daily time limit is locked right now. */
    val locked: Boolean,
    /** The extra types the user switched on in Settings → Messages & calls. */
    val extras: Set<ExtraNotification>,
    /** Throwback may post notifications (Android 13+ permission, channel not blocked). */
    val canPost: Boolean,
    /** Rules: keep unrecognised notifications instead of removing them. */
    val keepUnknown: Boolean = false,
)

/**
 * The routing table (DECISIONS.md #26), pure so every combination is unit-tested:
 *
 * | kind                     | result                                                        |
 * |--------------------------|---------------------------------------------------------------|
 * | call                     | keep, always (never touched)                                  |
 * | message, missed call     | relay to the inbox (the tap picks messages-only when locked)  |
 * | likes … live             | relay to activity if switched on and not locked, else cancel  |
 * | other                    | cancel                                                        |
 * | unknown                  | cancel, or keep if the rules say so                           |
 *
 * Safety: if Throwback can't post notifications, anything it would relay is kept as Instagram's
 * own instead, so a message is never lost. A group summary is never relayed (its children are)
 * and is kept whenever its group holds a call.
 */
internal object RelayRouting {

    fun decide(kind: NotificationKind, ctx: RelayContext, groupSummary: Boolean = false, groupHasCall: Boolean = false): RelayAction {
        if (kind == NotificationKind.CALL || kind == NotificationKind.KEEP) return RelayAction.Keep
        if (groupSummary && groupHasCall) return RelayAction.Keep
        val action = decideSingle(kind, ctx)
        if (action is RelayAction.Relay) {
            if (!ctx.canPost) return RelayAction.Keep
            if (groupSummary) return RelayAction.Cancel
        }
        return action
    }

    private fun decideSingle(kind: NotificationKind, ctx: RelayContext): RelayAction = when (kind) {
        NotificationKind.CALL, NotificationKind.KEEP -> RelayAction.Keep
        NotificationKind.MESSAGE, NotificationKind.MISSED_CALL -> RelayAction.Relay(RelayTarget.MESSAGES, kind)
        NotificationKind.LIKES, NotificationKind.COMMENTS, NotificationKind.FOLLOWS,
        NotificationKind.MENTIONS, NotificationKind.LIVE -> {
            val toggle = extraFor(kind)
            if (!ctx.locked && toggle != null && toggle in ctx.extras) {
                RelayAction.Relay(RelayTarget.ACTIVITY, kind)
            } else {
                RelayAction.Cancel
            }
        }
        NotificationKind.OTHER -> RelayAction.Cancel
        NotificationKind.UNKNOWN -> if (ctx.keepUnknown) RelayAction.Keep else RelayAction.Cancel
    }

    /** The Settings toggle for an extra type, or null for kinds without one. */
    fun extraFor(kind: NotificationKind): ExtraNotification? = when (kind) {
        NotificationKind.LIKES -> ExtraNotification.LIKES
        NotificationKind.COMMENTS -> ExtraNotification.COMMENTS
        NotificationKind.FOLLOWS -> ExtraNotification.NEW_FOLLOWERS
        NotificationKind.MENTIONS -> ExtraNotification.STORY_MENTIONS_AND_TAGS
        NotificationKind.LIVE -> ExtraNotification.LIVE_VIDEOS
        else -> null
    }
}
