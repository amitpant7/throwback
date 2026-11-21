package app.throwback.connect.relay

import app.throwback.connect.InstagramPackages
import app.throwback.web.rules.NotificationMatch
import app.throwback.web.rules.NotificationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {

    private val defaults = NotificationClassifier(null)

    private fun facts(
        channel: String? = null,
        category: String? = null,
        template: String? = null,
        group: String? = null,
        tag: String? = null,
        ongoing: Boolean = false,
        fullScreen: Boolean = false,
    ) = NotificationFacts(
        key = "0|com.instagram.android|1|$tag|10123",
        packageName = InstagramPackages.APP,
        channelId = channel,
        category = category,
        template = template,
        group = group,
        tag = tag,
        ongoing = ongoing,
        fullScreenIntent = fullScreen,
    )

    @Test
    fun `android's call signals always mean a call`() {
        assertEquals(NotificationKind.CALL, defaults.classify(facts(category = "call")))
        assertEquals(NotificationKind.CALL, defaults.classify(facts(template = "android.app.Notification\$CallStyle")))
        assertEquals(NotificationKind.CALL, defaults.classify(facts(fullScreen = true)))
    }

    @Test
    fun `no rule can turn a call into something else`() {
        val rules = NotificationRules(
            classify = listOf(
                NotificationMatch(kind = "likes", category = "call"),
                NotificationMatch(kind = "other", channel = ".*"),
            ),
        )
        val c = NotificationClassifier(rules)
        assertEquals(NotificationKind.CALL, c.classify(facts(category = "call", channel = "ig_calls")))
        assertEquals(NotificationKind.CALL, c.classify(facts(channel = "x", fullScreen = true)))
    }

    @Test
    fun `messages by category or MessagingStyle`() {
        assertEquals(NotificationKind.MESSAGE, defaults.classify(facts(category = "msg")))
        assertEquals(NotificationKind.MESSAGE, defaults.classify(facts(template = "android.app.Notification\$MessagingStyle")))
        assertEquals(NotificationKind.MISSED_CALL, defaults.classify(facts(category = "missed_call")))
    }

    @Test
    fun `ongoing without other signals is kept as maybe a call`() {
        assertEquals(NotificationKind.ONGOING, defaults.classify(facts(ongoing = true)))
        assertTrue(NotificationKind.ONGOING.isCall)
        // Not a rules kind: the rules can't name it.
        assertEquals(null, NotificationKind.fromRule("ongoing"))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(NotificationKind.UNKNOWN, defaults.classify(facts(channel = "ig_other", category = "social")))
        assertEquals(NotificationKind.UNKNOWN, defaults.classify(facts()))
    }

    @Test
    fun `rules decide before the defaults, first match wins`() {
        val rules = NotificationRules(
            classify = listOf(
                NotificationMatch(kind = "likes", channel = "^ig_likes$"),
                NotificationMatch(kind = "comments", channel = "comment", category = "social"),
                NotificationMatch(kind = "other", category = "msg", group = "promo"),
                NotificationMatch(kind = "follows", tag = "follow"),
                NotificationMatch(kind = "message", channel = "^ig_direct"),
                NotificationMatch(kind = "live", ongoing = false, channel = "live"),
            ),
        )
        val c = NotificationClassifier(rules)
        assertEquals(NotificationKind.LIKES, c.classify(facts(channel = "ig_likes")))
        assertEquals(NotificationKind.UNKNOWN, c.classify(facts(channel = "ig_likes_2")))
        // Every set field must match.
        assertEquals(NotificationKind.COMMENTS, c.classify(facts(channel = "ig_comments", category = "social")))
        assertEquals(NotificationKind.UNKNOWN, c.classify(facts(channel = "ig_comments", category = "msg2")))
        // No rule can make a message something that is removed (see the test below).
        assertEquals(NotificationKind.MESSAGE, c.classify(facts(category = "msg", group = "promo_1")))
        assertEquals(NotificationKind.MESSAGE, c.classify(facts(category = "msg", group = "thread_1")))
        assertEquals(NotificationKind.FOLLOWS, c.classify(facts(tag = "new_follower")))
        assertEquals(NotificationKind.MESSAGE, c.classify(facts(channel = "ig_direct_v2")))
        assertEquals(NotificationKind.LIVE, c.classify(facts(channel = "live_videos")))
        // A null field never matches a pattern.
        assertEquals(NotificationKind.UNKNOWN, c.classify(facts(category = "social")))
    }

    @Test
    fun `a message on a renamed chat channel stays a message, never other`() {
        val bundled = NotificationClassifier(app.throwback.web.TestRules.bundled.notifications)
        val style = "android.app.Notification\$MessagingStyle"
        // The rules' catch-all "other" (^ig_) would take it: every message would be removed.
        assertEquals(NotificationKind.OTHER, bundled.classify(facts(channel = "ig_direct_v2")))
        assertEquals(NotificationKind.MESSAGE, bundled.classify(facts(channel = "ig_direct_v2", template = style)))
        assertEquals(NotificationKind.MESSAGE, bundled.classify(facts(channel = "ig_direct_v2", category = "msg")))
        // Kinds that keep it still apply.
        assertEquals(NotificationKind.MISSED_CALL, bundled.classify(facts(channel = "ig_direct_v2", category = "missed_call", template = style)))
        assertEquals(NotificationKind.KEEP, bundled.classify(facts(channel = "ig_alerts", template = style)))
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_rtc", ongoing = true, template = style)))
    }

    @Test
    fun `a broken or unknown rule is dropped, not fatal`() {
        val rules = NotificationRules(
            classify = listOf(
                NotificationMatch(kind = "message", channel = "(broken"),
                NotificationMatch(kind = "nonsense", channel = "x"),
                NotificationMatch(kind = "unknown", channel = "x"),
                NotificationMatch(kind = "likes", channel = "x"),
            ),
        )
        assertEquals(NotificationKind.LIKES, NotificationClassifier(rules).classify(facts(channel = "x")))
    }

    @Test
    fun `every rules kind maps to a classifier kind`() {
        app.throwback.web.rules.NOTIFICATION_KINDS.forEach { assertTrue(it, NotificationKind.fromRule(it) != null) }
    }

    @Test
    fun `bundled rules sort Instagram's real channels (read from the owner's phone)`() {
        val bundled = NotificationClassifier(app.throwback.web.TestRules.bundled.notifications)
        val expected = mapOf(
            "ig_direct_video_chat" to NotificationKind.OTHER, // not ongoing: not a running call (ongoing case below)
            "ig_direct" to NotificationKind.MESSAGE,
            "ig_direct_requests" to NotificationKind.MESSAGE,
            "ig_likes" to NotificationKind.LIKES,
            "ig_comment_likes" to NotificationKind.LIKES,
            "ig_likes_and_comments_on_photos_of_you" to NotificationKind.LIKES,
            "ig_heads_up_comments" to NotificationKind.COMMENTS,
            "ig_new_followers" to NotificationKind.FOLLOWS,
            "ig_connections" to NotificationKind.FOLLOWS,
            "ig_private_user_follow_request" to NotificationKind.FOLLOWS,
            "ig_mentions_in_bio" to NotificationKind.MENTIONS,
            "ig_photos_of_you" to NotificationKind.MENTIONS,
            "ig_live_videos" to NotificationKind.LIVE,
            "ig_highlights" to NotificationKind.OTHER,
            "ig_first_posts_and_stories" to NotificationKind.OTHER,
            "ig_shopping_drops" to NotificationKind.OTHER,
            "ig_view_counts" to NotificationKind.OTHER,
            "uploads" to NotificationKind.OTHER,
        )
        expected.forEach { (channel, kind) -> assertEquals(channel, kind, bundled.classify(facts(channel = channel))) }
    }

    @Test
    fun `calls in progress are calls, even on a channel Instagram creates only during a call`() {
        val bundled = NotificationClassifier(app.throwback.web.TestRules.bundled.notifications)
        // An ongoing notification on an unlisted Instagram channel: never "other" (that let the guard
        // send the owner out of a running video call). Kept; a call for the guard with call audio.
        assertEquals(NotificationKind.ONGOING, bundled.classify(facts(channel = "ig_some_new_channel", ongoing = true)))
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_rtc_ongoing", ongoing = true)))
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_ongoing_call", ongoing = true)))
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_direct_video_chat", ongoing = true)))
        // Android's own call signals still win without "ongoing" (a ringing call).
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_direct_video_chat", category = "call")))
        assertEquals(NotificationKind.OTHER, bundled.classify(facts(channel = "ig_some_new_channel")))
    }

    @Test
    fun `missed calls, security alerts and uploads never count as a running call`() {
        val bundled = NotificationClassifier(app.throwback.web.TestRules.bundled.notifications)
        assertEquals(NotificationKind.MISSED_CALL, bundled.classify(facts(channel = "ig_direct_video_chat", category = "missed_call")))
        assertEquals(NotificationKind.KEEP, bundled.classify(facts(channel = "ig_alerts")))
        assertEquals(NotificationKind.OTHER, bundled.classify(facts(channel = "uploads", ongoing = true)))
        assertEquals(NotificationKind.OTHER, bundled.classify(facts(channel = "ig_direct_video_chat")))
        assertEquals(NotificationKind.CALL, bundled.classify(facts(channel = "ig_direct_video_chat", ongoing = true)))
    }
}
