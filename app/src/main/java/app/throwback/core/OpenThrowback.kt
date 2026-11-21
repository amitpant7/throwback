package app.throwback.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The intents that open Throwback's main screen (MainActivity), and the actions it understands.
 * Notifications, the guard, home-screen shortcuts and Settings open it through here, so they don't
 * depend on the app's entry point. The activity is named as text: OpenThrowbackTest checks the name.
 */
object OpenThrowback {
    const val MAIN_ACTIVITY = "app.throwback.MainActivity"

    /** Opens Settings (the "Throwback settings" app-icon shortcut, res/xml/shortcuts.xml, uses it too). */
    const val ACTION_OPEN_SETTINGS = "app.throwback.action.OPEN_SETTINGS"

    /** A relayed message notification: open its chat (or the inbox), also in messages-only mode. */
    const val ACTION_OPEN_MESSAGES = "app.throwback.action.OPEN_MESSAGES"

    /** Any other relayed notification (a like, a comment, a new follower): open its page. */
    const val ACTION_OPEN_ACTIVITY = "app.throwback.action.OPEN_ACTIVITY"

    /** Opens Throwback; with [url] (an instagram.com page) its feed shows that page. */
    fun intent(context: Context, action: String? = null, url: String? = null): Intent =
        Intent().setClassName(context, MAIN_ACTIVITY).also { intent ->
            action?.let { intent.action = it }
            url?.let { intent.data = Uri.parse(it) }
        }
}
