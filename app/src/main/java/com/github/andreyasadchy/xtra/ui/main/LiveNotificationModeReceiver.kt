package com.github.andreyasadchy.xtra.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

class LiveNotificationModeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LiveNotificationService.ACTION_SWITCH_TO_FAST) {
            return
        }
        context.prefs().edit {
            putString(C.LIVE_NOTIFICATIONS_MODE, C.LIVE_NOTIFICATIONS_MODE_FAST)
        }
        LiveNotificationScheduler.applyMode(context)
    }
}
