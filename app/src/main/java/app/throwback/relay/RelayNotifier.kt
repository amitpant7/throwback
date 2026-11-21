package app.throwback.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.throwback.MainActivity
import app.throwback.R
import app.throwback.ui.messages.Permissions

/** Throwback's own notifications: relayed messages and activity, and the guard's ongoing one. */
internal object RelayNotifier {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_ACTIVITY = "instagram_activity"
    const val CHANNEL_GUARD = "guard"
    const val CHANNEL_GUARD_OFF = "guard_off"

    /** Fixed id of the guard's foreground-service notification. Relayed ones use other ids. */
    const val GUARD_NOTIFICATION_ID = 0x7b0001

    /** Fixed id of "Instagram guard is off" (Android refused to restart it). */
    private const val GUARD_OFF_NOTIFICATION_ID = 0x7b0002

    /** Intent action: opened from a relayed message (messages-only mode if the time limit is locked). */
    const val ACTION_OPEN_MESSAGES = "app.throwback.action.OPEN_MESSAGES"

    /** Intent action: opened from a relayed activity notification (likes, comments…). */
    const val ACTION_OPEN_ACTIVITY = "app.throwback.action.OPEN_ACTIVITY"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.relay_channel_messages), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.relay_channel_messages_description) },
                NotificationChannel(CHANNEL_ACTIVITY, context.getString(R.string.relay_channel_activity), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.relay_channel_activity_description) },
                NotificationChannel(CHANNEL_GUARD, context.getString(R.string.relay_channel_guard), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = context.getString(R.string.relay_channel_guard_description)
                        setShowBadge(false)
                    },
                NotificationChannel(CHANNEL_GUARD_OFF, context.getString(R.string.relay_channel_guard_off), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.relay_channel_guard_off_description) },
            ),
        )
    }

    /**
     * "Instagram guard is off", instead of leaving the Instagram app open without a word: Android
     * stopped it or refused to (re)start it from the background (Android 12+ may), and the tap opens
     * Throwback, which starts it; or ([permissionOff]) a permission it needs was turned off, and the
     * tap opens Settings, where Connect › Instagram says which one.
     */
    fun showGuardOff(context: Context, permissionOff: Boolean = false) {
        ensureChannels(context)
        if (!Permissions.canPostNotifications(context)) return
        val open = PendingIntent.getActivity(
            context,
            GUARD_OFF_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (permissionOff) action = MainActivity.ACTION_OPEN_SETTINGS },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_GUARD_OFF)
            .setSmallIcon(R.drawable.ic_stat_throwback)
            .setContentTitle(context.getString(R.string.guard_off_title))
            .setContentText(context.getString(if (permissionOff) R.string.guard_off_permission_text else R.string.guard_off_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(GUARD_OFF_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Revoked between the check and now; nothing else to do.
        }
    }

    /** The guard runs again. */
    fun cancelGuardOff(context: Context) {
        NotificationManagerCompat.from(context).cancel(GUARD_OFF_NOTIFICATION_ID)
    }

    /** Whether a notification on [target]'s channel would actually show (app and channel not blocked). */
    fun canPost(context: Context, target: RelayTarget): Boolean {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelFor(target))
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Posts Throwback's notification for a relayed Instagram notification. Nothing is stored. */
    fun post(context: Context, facts: NotificationFacts, kind: NotificationKind, target: RelayTarget, url: String) {
        ensureChannels(context)
        val id = notificationId(facts.key)
        val fallback = context.getString(
            when (kind) {
                NotificationKind.MISSED_CALL -> R.string.relay_missed_call
                NotificationKind.MESSAGE -> R.string.relay_new_message
                else -> R.string.relay_new_activity
            },
        )
        val title = facts.title?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        val text = facts.text?.toString()?.takeIf { it.isNotBlank() }
        val action = if (target == RelayTarget.MESSAGES) ACTION_OPEN_MESSAGES else ACTION_OPEN_ACTIVITY
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(action, Uri.parse(url), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val category = if (target == RelayTarget.MESSAGES) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL
        // On the lock screen only "New message" shows, never who or what.
        val public = NotificationCompat.Builder(context, channelFor(target))
            .setSmallIcon(R.drawable.ic_stat_throwback)
            .setContentTitle(fallback)
            .build()
        val notification = NotificationCompat.Builder(context, channelFor(target))
            .setSmallIcon(R.drawable.ic_stat_throwback)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setCategory(category)
            .setPriority(if (target == RelayTarget.MESSAGES) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Instagram keeps one notification per chat: a new message there rings again, the same
            // text posted again (an update of the same message) doesn't.
            .setOnlyAlertOnce(shownText(context, id) == title to text)
            .build()
        // The router keeps Instagram's own notification in this case (canPost is false).
        if (!Permissions.canPostNotifications(context)) return
        try {
            NotificationManagerCompat.from(context).notify(TAG_RELAYED, id, notification)
        } catch (e: SecurityException) {
            // Revoked between the check and now; nothing else to do.
        }
    }

    /** Title and text of relayed notification [id] if it still shows, else null. */
    private fun shownText(context: Context, id: Int): Pair<String?, String?>? = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.firstOrNull { it.tag == TAG_RELAYED && it.id == id }
            ?.notification?.extras
            ?.let { it.getCharSequence(Notification.EXTRA_TITLE)?.toString() to it.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
    } catch (e: RuntimeException) {
        null
    }

    private fun channelFor(target: RelayTarget) = if (target == RelayTarget.MESSAGES) CHANNEL_MESSAGES else CHANNEL_ACTIVITY

    /** Same Instagram notification (e.g. a chat getting a new message) → same Throwback notification. */
    private fun notificationId(instagramKey: String): Int = instagramKey.hashCode() and 0x3fffffff

    /** Tag of relayed notifications, so they never look like Instagram's or the debug fakes. */
    private const val TAG_RELAYED = "relayed"
}
