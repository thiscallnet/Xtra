package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import java.util.concurrent.TimeUnit

object LiveNotificationScheduler {

    private val transitionLock = Any()

    fun enable(context: Context, baselineOnly: Boolean) {
        if (!canPostNotifications(context)) {
            disable(context)
            return
        }
        applyMode(context, baselineOnly)
    }

    fun disable(context: Context) {
        synchronized(transitionLock) {
            disableLocked(context)
        }
    }

    fun refresh(context: Context) {
        clearLegacyConnectionNotification(context)
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) && canPostNotifications(context)) {
            applyMode(context, baselineOnly = false)
        } else {
            disable(context)
        }
    }

    /** Applies the selected owner without ever running Fast and Persistent together. */
    fun applyMode(context: Context, baselineOnly: Boolean = false) {
        synchronized(transitionLock) {
            if (!context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) || !canPostNotifications(context)) {
                disableLocked(context)
                return
            }
            migrateMode(context)
            schedulePeriodicFallback(context)
            when (mode(context)) {
                C.LIVE_NOTIFICATIONS_MODE_FAST -> {
                    LiveNotificationService.stop(context)
                    if (!LiveNotificationService.isRunning()) {
                        LiveNotificationRealtimeEngine.start(context, LiveNotificationProcessOwner.FAST)
                    }
                    cancelImmediateWork(context)
                }
                C.LIVE_NOTIFICATIONS_MODE_PERSISTENT -> {
                    LiveNotificationRealtimeEngine.stop()
                    if (!LiveNotificationService.start(context)) {
                        // A background-start restriction should not leave the user
                        // with no fast path. Keep the selected mode and use the
                        // process runner until the next foreground opportunity.
                        LiveNotificationRealtimeEngine.start(
                            context,
                            LiveNotificationProcessOwner.PERSISTENT_FALLBACK,
                        )
                    }
                    cancelImmediateWork(context)
                }
                else -> {
                    LiveNotificationRealtimeEngine.stop()
                    LiveNotificationService.stop(context)
                    enqueueImmediateWork(context, baselineOnly)
                }
            }
            clearLegacyConnectionNotification(context)
        }
    }

    /** Called by the service after its asynchronous stop has completed. */
    fun onPersistentServiceStopped(context: Context) {
        synchronized(transitionLock) {
            if (!context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) ||
                !canPostNotifications(context)
            ) {
                return
            }
            when (mode(context)) {
                C.LIVE_NOTIFICATIONS_MODE_FAST ->
                    LiveNotificationRealtimeEngine.start(context, LiveNotificationProcessOwner.FAST)
                C.LIVE_NOTIFICATIONS_MODE_PERSISTENT ->
                    LiveNotificationRealtimeEngine.start(
                        context,
                        LiveNotificationProcessOwner.PERSISTENT_FALLBACK,
                    )
            }
        }
    }

    fun canPostNotifications(context: Context): Boolean = LiveNotificationNotifier(context).canPostNotifications()

    fun migrateMode(context: Context): String {
        val preferences = context.prefs()
        val stored = preferences.getString(C.LIVE_NOTIFICATIONS_MODE, null)
        val normalized = normalizeMode(stored)
        if (stored != normalized) {
            preferences.edit { putString(C.LIVE_NOTIFICATIONS_MODE, normalized) }
        }
        return normalized
    }

    internal fun normalizeMode(stored: String?): String = when (stored) {
        C.LIVE_NOTIFICATIONS_MODE_REALTIME -> C.LIVE_NOTIFICATIONS_MODE_FAST
        C.LIVE_NOTIFICATIONS_MODE_FAST,
        C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
        C.LIVE_NOTIFICATIONS_MODE_BATTERY,
        -> stored
        else -> C.LIVE_NOTIFICATIONS_MODE_BATTERY
    }

    fun mode(context: Context): String = migrateMode(context)

    /** Compatibility helper for callers that only need to know if a fast owner is selected. */
    fun isRealtime(context: Context): Boolean = mode(context) != C.LIVE_NOTIFICATIONS_MODE_BATTERY

    fun enqueueImmediateFallback(context: Context, baselineOnly: Boolean) {
        enqueueImmediateWork(context, baselineOnly)
    }

    private fun enqueueImmediateWork(context: Context, baselineOnly: Boolean) {
        val input = Data.Builder()
            .putBoolean(LiveNotificationWorker.INPUT_BASELINE_ONLY, baselineOnly)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateWork(input),
        )
    }

    private fun cancelImmediateWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    private fun disableLocked(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        LiveNotificationRealtimeEngine.stop()
        LiveNotificationService.stop(context)
        context.prefs().edit { remove(C.LIVE_NOTIFICATION_BASELINE_INITIALIZED) }
        LiveNotificationNotifier(context).cancelLiveNotifications()
        clearLegacyConnectionNotification(context)
    }

    private fun schedulePeriodicFallback(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun immediateWork(input: Data) =
        OneTimeWorkRequestBuilder<LiveNotificationWorker>()
            .setInputData(input)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

    private fun clearLegacyConnectionNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(LEGACY_CONNECTION_CHANNEL_ID)
        }
    }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val PERIODIC_WORK_NAME = "live_notifications"
    private const val IMMEDIATE_WORK_NAME = "live_notifications_now"
    private const val LEGACY_CONNECTION_CHANNEL_ID = "xtra_live_connection_channel"
}
