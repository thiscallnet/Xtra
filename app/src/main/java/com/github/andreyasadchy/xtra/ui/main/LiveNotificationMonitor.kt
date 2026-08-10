package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.util.Log
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.HelixRateLimit
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.edit

/**
 * Runs one notification reconciliation against the durable Room-backed queue.
 *
 * Both WorkManager and the real-time service use this class so they cannot
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
                if (repository.syncNotificationUsers(networkLibrary, gqlHeaders)) {
                    prefs.edit { putLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, System.currentTimeMillis()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Notification preference sync failed; retaining the previous channel IDs", e)
            }
        }

        repository.getNewStreams(
            networkLibrary = networkLibrary,
            gqlHeaders = gqlHeaders,
            helixHeaders = helixHeaders,
            includeFollowedStreams = false,
            preferHelix = true,
            enqueueNotificationEvents = !baselineOnly,
            onHelixRateLimit = onHelixRateLimit,
        )
        if (!prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !notifier.canPostNotifications()) {
            repository.clearPendingNotificationEvents()
            notifier.cancelLiveNotifications()
            return@withLock PollResult(0, 0, "notifications_disabled")
        }
        val delivered = notifier.deliverPending(repository)
        PollResult(delivered, repository.getNotificationUserIds().size, "helix")
    }

    private fun shouldSyncNotificationUsers(): Boolean {
        val prefs = context.prefs()
        val now = System.currentTimeMillis()
        val lastSuccess = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_SUCCESS, 0L)
        val lastAttempt = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SYNC_ATTEMPT, 0L)
        return (lastSuccess == 0L || now - lastSuccess >= FOLLOW_SYNC_INTERVAL_MS) &&
            (lastAttempt == 0L || now - lastAttempt >= FOLLOW_SYNC_RETRY_INTERVAL_MS)
    }

    data class PollResult(
        val delivered: Int,
        val channelCount: Int,
        val api: String,
    )

    companion object {
        private const val TAG = "LiveNotificationMonitor"
        private const val FOLLOW_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val FOLLOW_SYNC_RETRY_INTERVAL_MS = 30 * 60 * 1000L
        private val mutex = Mutex()
    }
}
