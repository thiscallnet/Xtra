package com.github.andreyasadchy.xtra.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class LiveNotificationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val prefs = context.prefs()
        if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
            LiveNotificationScheduler.mode(context) == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT &&
            LiveNotificationScheduler.canPostNotifications(context)
        ) {
            LiveNotificationScheduler.applyMode(context)
        }
    }
}
