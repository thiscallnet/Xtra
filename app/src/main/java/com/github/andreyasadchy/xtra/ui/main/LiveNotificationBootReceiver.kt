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
        val appContext = context.applicationContext
        if (appContext.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
            LiveNotificationScheduler.isRealtime(appContext) &&
            LiveNotificationScheduler.canPostNotifications(appContext)
        ) {
            LiveNotificationScheduler.enable(appContext, baselineOnly = true)
        }
    }
}
