package app.throwback.relay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import app.throwback.R

/**
 * Test builds only (src/debug): posts made-up notifications that the relay treats like Instagram's,
 * so the relay and the guard can be checked on an emulator without the Instagram app. Every text
 * is invented; nothing real is read.
 *
 * ```
 * adb shell am broadcast -n app.throwback/.relay.DebugRelayReceiver --es cmd notify --es kind message --ei id 1
 * adb shell am broadcast -n app.throwback/.relay.DebugRelayReceiver --es cmd notify --es kind call --ei id 2
 * adb shell am broadcast -n app.throwback/.relay.DebugRelayReceiver --es cmd remove --ei id 2
 * adb shell am broadcast -n app.throwback/.relay.DebugRelayReceiver --es cmd guard_pkg --es pkg com.android.settings
 * ```
 * kinds: message, missed, call, likes, comments, follows, mentions, live, other, unknown
 */
class DebugRelayReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // test builds; the emulator grants it
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = intent.getIntExtra("id", 1)
        val tag = RelayDebug.FAKE_TAG_PREFIX + id
        when (intent.getStringExtra("cmd")) {
            "notify" -> {
                val kind = intent.getStringExtra("kind") ?: "message"
                val channel = RelayDebug.FAKE_CHANNEL_PREFIX + kind
                nm.createNotificationChannel(NotificationChannel(channel, "Fake $kind (test build)", NotificationManager.IMPORTANCE_LOW))
                val b = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_throwback_mark)
                    .setContentTitle(intent.getStringExtra("title") ?: "Test sender")
                    .setContentText(intent.getStringExtra("text") ?: "Test $kind (fake)")
                when (kind) {
                    "message" -> b.setCategory(NotificationCompat.CATEGORY_MESSAGE).setStyle(
                        NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                            .addMessage("Test message (fake)", System.currentTimeMillis(), Person.Builder().setName("Test sender").build()),
                    )
                    "missed" -> b.setCategory("missed_call")
                    "call" -> b.setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true)
                    "unknown" -> b.setCategory(NotificationCompat.CATEGORY_SOCIAL)
                }
                nm.notify(tag, id, b.build())
                Log.i(TAG, "posted fake $kind as $tag")
            }
            "remove" -> nm.cancel(tag, id)
            "guard_pkg" -> {
                RelayDebug.guardTestPackage = intent.getStringExtra("pkg")?.takeIf { it.isNotBlank() }
                Log.i(TAG, "guard test package: ${RelayDebug.guardTestPackage}")
            }
            else -> Log.w(TAG, "unknown cmd")
        }
    }

    private companion object {
        const val TAG = "ThrowbackRelayDebug"
    }
}
