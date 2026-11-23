package app.throwback.personal

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import app.throwback.R

/**
 * The personal build's device admin (DECISIONS.md #34ii). Android won't uninstall an app whose
 * device admin is on, so Throwback stays until the admin is switched off in Settings (Security ›
 * Device admin apps). It asks for no device policies. Switching it off can't be delayed or refused
 * (Android doesn't allow that); Throwback only shows a last question.
 */
class UninstallGuard : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.personal_admin_disable_warning)
}
