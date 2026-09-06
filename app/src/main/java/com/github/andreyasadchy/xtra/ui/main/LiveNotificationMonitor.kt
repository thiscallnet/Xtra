package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.util.Log
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.HelixRateLimit
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.NotificationUserSyncResult
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.edit

internal const val LIVE_NOTIFICATION_FOLLOW_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
internal const val LIVE_NOTIFICATION_FOLLOW_SYNC_RETRY_INTERVAL_MS = 30 * 60 * 1000L

/**
 * Runs one notification reconciliation against the durable Room-backed queue.
 *
 * Both WorkManager and the opportunistic EventSub engine use this class so they cannot
 * race while updating the live-state and pending-event tables.
 */
class LiveNotificationMonitor(context: Context) {

    private val context = context.applicationContext
    private val notifier = LiveNotificationNotifier(this.context)
    private val xtraApp = this.context as XtraApp

    suspend fun poll(
        baselineOnly: Boolean = false,
        onHelixRateLimit: ((HelixRateLimit) -> Unit)? = null,
    ): PollResult = mutex.withLock {
        val prefs = context.prefs()
        val repository = xtraApp.xtraModule.notificationsRepository
        if (!prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !notifier.canPostNotifications()) {
            repository.clearPendingNotificationEvents()
            notifier.cancelLiveNotifications()
            return@withLock PollResult(0, 0, "notifications_disabled")
        }

        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
        val useLocalFollows = (prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0) != 0

        if (!useLocalFollows && shouldSyncNotificationUsers()) {
            prefs.edit { putLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, System.currentTimeMillis()) }
            try {
                val syncResult = repository.syncNotificationUsers(
                    networkLibrary = networkLibrary,
                    gqlHeaders = gqlHeaders,
                    helixHeaders = helixHeaders,
                    userId = context.tokenPrefs().getString(C.USER_ID, null),
                )
                if (syncResult == NotificationUserSyncResult.SUCCESS) {
                    prefs.edit { putLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, System.currentTimeMillis()) }
                } else {
                    Log.w(TAG, "Notification preference enrichment was transient; retained previous channel IDs")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notification preference sync failed; retaining the previous channel IDs", e)
            }
        }

        val effectiveBaselineOnly = baselineOnly &&
            !prefs.getBoolean(C.LIVE_NOTIFICATION_BASELINE_INITIALIZED, false)
        var apiUsed = "none"
        repository.getNewStreams(
            networkLibrary = networkLibrary,
            gqlHeaders = gqlHeaders,
            helixHeaders = helixHeaders,
            includeFollowedStreams = false,
            preferHelix = gqlHeaders[C.HEADER_TOKEN].isNullOrBlank(),
            enqueueNotificationEvents = !effectiveBaselineOnly,
            onHelixRateLimit = onHelixRateLimit,
            onApiUsed = { apiUsed = it },
        )
        if (!prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !notifier.canPostNotifications()) {
            repository.clearPendingNotificationEvents()
            notifier.cancelLiveNotifications()
            return@withLock PollResult(0, 0, "notifications_disabled")
        }
        val delivered = notifier.deliverPending(repository)
        if (effectiveBaselineOnly) {
            prefs.edit { putBoolean(C.LIVE_NOTIFICATION_BASELINE_INITIALIZED, true) }
        }
        val channelCount = repository.getNotificationUserIds().size
        prefs.edit { putInt(C.LIVE_NOTIFICATION_CACHED_CHANNEL_COUNT, channelCount) }
        PollResult(delivered, channelCount, apiUsed)
    }

    internal fun nextNotificationUserSyncDelayMs(now: Long = System.currentTimeMillis()): Long {
        val prefs = context.prefs()
        val useLocalFollows = (prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0) != 0
        if (useLocalFollows) return NO_CHANNELS_RECONCILE_INTERVAL_MS
        return nextNotificationUserSyncDelayMs(
            lastSuccessMs = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, 0L),
            lastAttemptMs = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, 0L),
            nowMs = now,
        )
    }

    private fun shouldSyncNotificationUsers(): Boolean = shouldSyncNotificationUsers(System.currentTimeMillis())

    private fun shouldSyncNotificationUsers(now: Long): Boolean {
        val prefs = context.prefs()
        val lastSuccess = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, 0L)
        val lastAttempt = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, 0L)
        return nextNotificationUserSyncDelayMs(lastSuccess, lastAttempt, now) == 0L
    }

    data class PollResult(
        val delivered: Int,
        val channelCount: Int,
        val api: String,
    )

    companion object {
        private const val TAG = "LiveNotificationMonitor"
        private val mutex = Mutex()
    }
}

internal fun nextNotificationUserSyncDelayMs(
    lastSuccessMs: Long,
    lastAttemptMs: Long,
    nowMs: Long,
): Long {
    val successDeadline = if (lastSuccessMs > 0L) {
        lastSuccessMs + LIVE_NOTIFICATION_FOLLOW_SYNC_INTERVAL_MS
    } else {
        nowMs
    }
    val attemptDeadline = if (lastAttemptMs > 0L) {
        lastAttemptMs + LIVE_NOTIFICATION_FOLLOW_SYNC_RETRY_INTERVAL_MS
    } else {
        nowMs
    }
    return (maxOf(nowMs, successDeadline, attemptDeadline) - nowMs).coerceAtLeast(0L)
}
