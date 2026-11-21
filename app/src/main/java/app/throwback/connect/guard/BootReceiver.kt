package app.throwback.connect.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.throwback.connect.MessagesFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the guard after a reboot or an app update, if the Instagram app is connected. Android allows
 * starting a foreground service from these two broadcasts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                MessagesFeature.sync(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
