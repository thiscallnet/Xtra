package com.github.andreyasadchy.xtra.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class LiveNotificationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = context.prefs()
        val liveEnabled = prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
        val watchStreakEnabled = prefs.getBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, false)
        val canPost = (liveEnabled && LiveNotificationScheduler.canPostNotifications(context)) ||
            (watchStreakEnabled && LiveNotificationScheduler.canPostWatchStreakNotifications(context))
        if ((liveEnabled || watchStreakEnabled) && canPost) {
            if (liveEnabled && LiveNotificationScheduler.mode(context) == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT) {
                LiveNotificationScheduler.applyMode(context)
            } else {
                LiveNotificationScheduler.restoreFallbacks(context)
            }
        }
    }
}
