package com.github.andreyasadchy.xtra.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import com.github.andreyasadchy.xtra.util.tokenPrefs
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
    appendLine(
        "Live notifications last setup failure operation: " +
                (sanitizeLiveNotificationTechnicalMessage(
                    prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_OPERATION, null)
                ) ?: "none")
    )
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
    val auth = AuthSessionStore(context.prefs(), context.tokenPrefs()).diagnostics()
    appendLine("Official access token present: ${auth.officialAccessTokenPresent}")
    appendLine("Official refresh token present: ${auth.officialRefreshTokenPresent}")
    appendLine("Official token client ID present: ${auth.officialClientIdPresent}")
    appendLine("Official token expires at: ${auth.officialExpiresAtMillis.takeIf { it > 0 } ?: "none"}")
    appendLine("GQL_TOKEN2 present: ${auth.gqlToken2Present}")
    appendLine("GQL_TOKEN_WEB present: ${auth.gqlTokenWebPresent}")
    appendLine("GQL_TOKEN2 refresh present: ${auth.gqlToken2RefreshPresent}")
    appendLine("GQL_TOKEN2 client ID present: ${auth.gqlToken2ClientIdPresent}")
    appendLine("GQL_TOKEN2 user ID present: ${auth.gqlToken2UserIdPresent}")
    appendLine("GQL_TOKEN2 expires at: ${auth.gqlToken2ExpiresAtMillis.takeIf { it > 0 } ?: "none"}")
    appendLine("GQL_TOKEN2 scopes present: ${auth.gqlToken2ScopesPresent}")
    appendLine("GQL_TOKEN2 type present: ${auth.gqlToken2TypePresent}")
    appendLine("GQL headers present: ${auth.gqlHeadersPresent}")
    appendLine("GQL headers Authorization present: ${auth.gqlHeadersAuthorizationPresent}")
    appendLine("Structured compatibility session present: ${auth.structuredCompatibilityPresent}")
    appendLine("Effective GQL Authorization present: ${auth.gqlAuthorizationPresent}")
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
