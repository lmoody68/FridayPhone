package com.macvision.fridayphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arm the listener after a reboot, but only if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).listening) {
            ListenerService.start(context)
        }
    }
}
