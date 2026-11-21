package app.throwback.connect.relay

import app.throwback.connect.relay.NotificationKind.CALL
import app.throwback.connect.relay.NotificationKind.COMMENTS
import app.throwback.connect.relay.NotificationKind.FOLLOWS
import app.throwback.connect.relay.NotificationKind.LIKES
import app.throwback.connect.relay.NotificationKind.LIVE
import app.throwback.connect.relay.NotificationKind.MENTIONS
import app.throwback.connect.relay.NotificationKind.MESSAGE
import app.throwback.connect.relay.NotificationKind.MISSED_CALL
import app.throwback.connect.relay.NotificationKind.ONGOING
import app.throwback.connect.relay.NotificationKind.OTHER
import app.throwback.connect.relay.NotificationKind.UNKNOWN
import app.throwback.core.prefs.ExtraNotification
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayRoutingTest {

    private val allExtras = ExtraNotification.entries.toSet()
    private val extraKinds = listOf(LIKES, COMMENTS, FOLLOWS, MENTIONS, LIVE)

    private fun ctx(locked: Boolean = false, extras: Set<ExtraNotification> = emptySet(), canPost: Boolean = true, keepUnknown: Boolean = false) =
        RelayContext(locked = locked, extras = extras, canPost = canPost, keepUnknown = keepUnknown)

    /** Every lock state × toggle state × posting state. */
    private val allContexts = listOf(false, true).flatMap { locked ->
        listOf(emptySet(), allExtras).flatMap { extras ->
            listOf(false, true).flatMap { canPost -> listOf(false, true).map { keep -> ctx(locked, extras, canPost, keep) } }
        }
    }

    @Test
    fun `calls are always kept, whatever else is true`() {
        allContexts.forEach { c ->
            listOf(CALL, ONGOING).forEach { kind ->
                assertEquals(RelayAction.Keep, RelayRouting.decide(kind, c))
                assertEquals(RelayAction.Keep, RelayRouting.decide(kind, c, groupSummary = true))
            }
        }
    }

    @Test
    fun `messages are relayed to the inbox, locked or not, toggles or not`() {
        listOf(false, true).forEach { locked ->
            listOf(emptySet(), allExtras).forEach { extras ->
                assertEquals(
                    RelayAction.Relay(RelayTarget.MESSAGES, MESSAGE),
                    RelayRouting.decide(MESSAGE, ctx(locked = locked, extras = extras)),
                )
                assertEquals(
                    RelayAction.Relay(RelayTarget.MESSAGES, MISSED_CALL),
                    RelayRouting.decide(MISSED_CALL, ctx(locked = locked, extras = extras)),
                )
            }
        }
    }

    @Test
    fun `a message is never lost when Throwback can't post`() {
        listOf(false, true).forEach { locked ->
            assertEquals(RelayAction.Keep, RelayRouting.decide(MESSAGE, ctx(locked = locked, canPost = false)))
            assertEquals(RelayAction.Keep, RelayRouting.decide(MISSED_CALL, ctx(locked = locked, canPost = false)))
        }
    }

    @Test
    fun `extra types follow their own toggle when unlocked`() {
        extraKinds.forEach { kind ->
            val toggle = RelayRouting.extraFor(kind)!!
            assertEquals(RelayAction.Cancel, RelayRouting.decide(kind, ctx()))
            assertEquals(RelayAction.Relay(RelayTarget.ACTIVITY, kind), RelayRouting.decide(kind, ctx(extras = setOf(toggle))))
            // Another type's toggle doesn't count.
            assertEquals(RelayAction.Cancel, RelayRouting.decide(kind, ctx(extras = allExtras - toggle)))
        }
    }

    @Test
    fun `extra types are dropped while locked, even when switched on`() {
        extraKinds.forEach { kind ->
            assertEquals(RelayAction.Cancel, RelayRouting.decide(kind, ctx(locked = true, extras = allExtras)))
            assertEquals(RelayAction.Cancel, RelayRouting.decide(kind, ctx(locked = true)))
        }
    }

    @Test
    fun `a switched-on type that can't be posted stays as Instagram's own`() {
        extraKinds.forEach { kind ->
            assertEquals(RelayAction.Keep, RelayRouting.decide(kind, ctx(extras = allExtras, canPost = false)))
            assertEquals(RelayAction.Cancel, RelayRouting.decide(kind, ctx(canPost = false)))
        }
    }

    @Test
    fun `other is dropped, unknown follows the rules`() {
        allContexts.forEach { c ->
            assertEquals(RelayAction.Cancel, RelayRouting.decide(OTHER, c))
            assertEquals(if (c.keepUnknown) RelayAction.Keep else RelayAction.Cancel, RelayRouting.decide(UNKNOWN, c))
        }
    }

    @Test
    fun `group summaries are never relayed, and kept when their group holds a call`() {
        assertEquals(RelayAction.Cancel, RelayRouting.decide(MESSAGE, ctx(), groupSummary = true))
        assertEquals(RelayAction.Keep, RelayRouting.decide(MESSAGE, ctx(), groupSummary = true, groupHasCall = true))
        assertEquals(RelayAction.Keep, RelayRouting.decide(UNKNOWN, ctx(), groupSummary = true, groupHasCall = true))
        assertEquals(RelayAction.Keep, RelayRouting.decide(MESSAGE, ctx(canPost = false), groupSummary = true))
    }

    @Test
    fun `every extra toggle has exactly one kind`() {
        val mapped = NotificationKind.entries.mapNotNull { RelayRouting.extraFor(it) }
        assertEquals(ExtraNotification.entries.toSet(), mapped.toSet())
        assertEquals(mapped.size, mapped.toSet().size)
    }
}
