package com.github.andreyasadchy.xtra.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import java.util.Date

internal fun liveNotificationDiagnostics(context: Context): String = buildString {
    val prefs = context.prefs()
    fun timestamp(key: String): String {
        val value = prefs.getLong(key, 0L)
        return if (value > 0L) "$value (${Date(value)})" else "never"
    }

    appendLine("Live notifications enabled: ${prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)}")
    appendLine("Live notifications mode: ${prefs.getString(C.LIVE_NOTIFICATIONS_MODE, C.LIVE_NOTIFICATIONS_MODE_BATTERY)}")
    appendLine("Live notifications last setup attempt: ${timestamp(C.LIVE_NOTIFICATION_LAST_SETUP_ATTEMPT)}")
    appendLine("Live notifications last setup success: ${timestamp(C.LIVE_NOTIFICATION_LAST_SETUP_SUCCESS)}")
    appendLine("Live notifications last setup failure: ${timestamp(C.LIVE_NOTIFICATION_LAST_SETUP_ERROR_AT)}")
    appendLine("Live notifications last setup API used: ${prefs.getString(C.LIVE_NOTIFICATION_LAST_SETUP_API, null) ?: "none"}")
    appendLine("Live notifications last setup failure stage: ${prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STAGE, null) ?: "none"}")
    appendLine("Live notifications last setup failure reason: ${prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_REASON, null) ?: "none"}")
    val status = prefs.getInt(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STATUS, 0)
    appendLine("Live notifications last setup failure HTTP status: ${status.takeIf { it > 0 } ?: "none"}")
    appendLine(
        "Live notifications last setup failure exception: " +
                (sanitizeLiveNotificationTechnicalMessage(
                    prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_EXCEPTION, null)
                ) ?: "none")
    )
    appendLine(
        "Live notifications last setup failure message: " +
                (sanitizeLiveNotificationTechnicalMessage(
                    prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_MESSAGE, null)
                ) ?: "none")
    )
    appendLine("Live notifications cached channel count: ${prefs.getInt(C.LIVE_NOTIFICATION_CACHED_CHANNEL_COUNT, 0)}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        appendLine("Live notifications Android channel count: ${notificationManager.notificationChannels.size}")
    }
    appendLine("Live notifications last runtime run: ${timestamp(C.LIVE_NOTIFICATION_LAST_RUN)}")
    appendLine("Live notifications last runtime success: ${timestamp(C.LIVE_NOTIFICATION_LAST_SUCCESS)}")
    appendLine("Live notifications last runtime error: ${timestamp(C.LIVE_NOTIFICATION_LAST_ERROR_AT)}")
    appendLine("Live notifications last runtime API used: ${prefs.getString(C.LIVE_NOTIFICATION_LAST_API, null) ?: "none"}")
    appendLine(
        "Live notifications last runtime error details: " +
                (sanitizeLiveNotificationTechnicalMessage(
                    prefs.getString(C.LIVE_NOTIFICATION_LAST_ERROR, null)
                ) ?: "none")
    )
}
