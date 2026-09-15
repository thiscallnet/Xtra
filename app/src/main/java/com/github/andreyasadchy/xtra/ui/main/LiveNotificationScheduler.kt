package com.github.andreyasadchy.xtra.ui.main

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

sealed interface LiveNotificationSchedulerResult {
    object Started : LiveNotificationSchedulerResult
    data class Blocked(val reason: NotificationBlockReason) : LiveNotificationSchedulerResult
    object NotEnabled : LiveNotificationSchedulerResult
    data class Failed(val error: Throwable) : LiveNotificationSchedulerResult
}

object LiveNotificationScheduler {

    private val transitionLock = Any()

    fun enable(context: Context, baselineOnly: Boolean): LiveNotificationSchedulerResult {
        synchronized(transitionLock) {
            return applyModeLocked(context, baselineOnly)
        }
    }

    fun disable(context: Context) {
        synchronized(transitionLock) {
            bestEffortDisableLocked(context)
        }
    }

    fun refresh(context: Context) {
        clearLegacyConnectionNotification(context)
        if (hasEnabledFeature(context) && canPostEnabledFeature(context)) {
            applyMode(context, baselineOnly = false)
        } else {
            disable(context)
        }
    }

    /** Applies the selected owner without ever running Fast and Persistent together. */
    fun applyMode(context: Context, baselineOnly: Boolean = false): LiveNotificationSchedulerResult =
        synchronized(transitionLock) {
            applyModeLocked(context, baselineOnly)
        }

    private fun applyModeLocked(context: Context, baselineOnly: Boolean): LiveNotificationSchedulerResult {
        return try {
            if (!hasEnabledFeature(context)) {
                bestEffortDisableLocked(context)
                return LiveNotificationSchedulerResult.NotEnabled
            }
            val initialBlockReason = enabledFeatureBlockReason(context)
            if (initialBlockReason != null) {
                bestEffortDisableLocked(context)
                return LiveNotificationSchedulerResult.Blocked(initialBlockReason)
            }
            migrateMode(context)
            schedulePeriodicFallback(context)
            val liveMonitoringAllowed = context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
                canPostNotifications(context)
            if (liveMonitoringAllowed) {
                scheduleWatchdogAlarm(context)
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
            } else {
                cancelWatchdogAlarm(context)
                LiveNotificationRealtimeEngine.stop()
                LiveNotificationService.stop(context)
                enqueueImmediateWork(context, baselineOnly)
            }
            clearLegacyConnectionNotification(context)
            val finalBlockReason = enabledFeatureBlockReason(context)
            if (finalBlockReason != null) {
                bestEffortDisableLocked(context)
                return LiveNotificationSchedulerResult.Blocked(finalBlockReason)
            }
            LiveNotificationSchedulerResult.Started
        } catch (error: Exception) {
            bestEffortDisableLocked(context)
            Log.e(TAG, "Unable to establish live notification scheduling", error)
            LiveNotificationSchedulerResult.Failed(error)
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

    /** Restores process-independent fallback scheduling after a reboot or app update. */
    fun restoreFallbacks(context: Context) {
        synchronized(transitionLock) {
            if (!hasEnabledFeature(context) || !canPostEnabledFeature(context)) {
                return
            }
            migrateMode(context)
            schedulePeriodicFallback(context)
            scheduleWatchdogAlarm(context)
        }
    }

    /** Handles the cheap watchdog wake. The worker performs network work if needed. */
    fun onWatchdogAlarm(context: Context) {
        synchronized(transitionLock) {
            val preferences = context.prefs()
            val enabled = preferences.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
            val notificationsAllowed = canPostNotifications(context)
            val selectedMode = mode(context)
            preferences.edit {
                putLong(C.LIVE_NOTIFICATION_LAST_WATCHDOG, System.currentTimeMillis())
            }
            if (!shouldScheduleLiveNotificationWatchdog(enabled, notificationsAllowed, selectedMode)) {
                cancelWatchdogAlarm(context)
                preferences.edit { putString(C.LIVE_NOTIFICATION_LAST_WATCHDOG_ACTION, "disabled") }
                return
            }
            scheduleWatchdogAlarm(context)
            val healthyOwner = hasHealthyRealtimeOwner(context)
            if (shouldRunLiveNotificationWatchdog(
                    notificationsEnabled = enabled,
                    notificationsAllowed = notificationsAllowed,
                    mode = selectedMode,
                    healthyRealtimeOwner = healthyOwner,
                )
            ) {
                preferences.edit { putString(C.LIVE_NOTIFICATION_LAST_WATCHDOG_ACTION, "fallback_enqueued") }
                enqueueWatchdogWork(context)
            } else {
                preferences.edit { putString(C.LIVE_NOTIFICATION_LAST_WATCHDOG_ACTION, "owner_healthy") }
            }
        }
    }

    fun canPostNotifications(context: Context): Boolean = LiveNotificationNotifier(context).canPostNotifications()

    fun canPostWatchStreakNotifications(context: Context): Boolean =
        WatchStreakReminderNotifier(context).canPostNotifications()

    internal fun hasHealthyRealtimeOwner(context: Context): Boolean = synchronized(transitionLock) {
        if (!context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) ||
            mode(context) == C.LIVE_NOTIFICATIONS_MODE_BATTERY
        ) {
            false
        } else {
            LiveNotificationRealtimeEngine.hasHealthyOwner() ||
                LiveNotificationService.hasHealthyRunner()
        }
    }

    /** Wakes the current realtime owner, or uses the existing WorkManager fallback when needed. */
    fun requestImmediateReconciliation(context: Context, reason: String): Boolean =
        synchronized(transitionLock) {
            if (!hasEnabledFeature(context) || !canPostEnabledFeature(context)) {
                return false
            }
            val mode = mode(context)
            routeImmediateReconciliation(
                mode = mode,
                wakePersistentOwner = {
                    LiveNotificationService.requestImmediateReconciliation(reason)
                },
                wakeProcessOwner = {
                    LiveNotificationRealtimeEngine.requestImmediateReconciliation(reason)
                },
                enqueueFallback = {
                    enqueueImmediateWork(context, baselineOnly = false)
                },
            )
        }

    /** Keeps owner selection testable without constructing Android services or WorkManager. */
    internal fun routeImmediateReconciliation(
        mode: String,
        wakePersistentOwner: () -> Boolean,
        wakeProcessOwner: () -> Boolean,
        enqueueFallback: () -> Unit,
    ): Boolean {
        val wokeOwner = when (mode) {
            C.LIVE_NOTIFICATIONS_MODE_PERSISTENT ->
                wakePersistentOwner() || wakeProcessOwner()
            C.LIVE_NOTIFICATIONS_MODE_FAST -> wakeProcessOwner()
            else -> false
        }
        if (!wokeOwner) enqueueFallback()
        return true
    }

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

    private fun enqueueWatchdogWork(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATCHDOG_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            immediateWork(
                Data.Builder()
                    .putBoolean(LiveNotificationWorker.INPUT_BASELINE_ONLY, false)
                    .build()
            ),
        )
    }

    private fun disableLocked(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK_NAME)
        cancelWatchdogAlarm(context)
        LiveNotificationRealtimeEngine.stop()
        LiveNotificationService.stop(context)
        context.prefs().edit { remove(C.LIVE_NOTIFICATION_BASELINE_INITIALIZED) }
        LiveNotificationNotifier(context).cancelLiveNotifications()
        WatchStreakReminderNotifier(context).cancelWatchStreakNotifications()
        clearLegacyConnectionNotification(context)
    }

    private fun hasEnabledFeature(context: Context): Boolean = context.prefs().let {
        it.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) ||
            it.getBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, false)
    }

    private fun canPostEnabledFeature(context: Context): Boolean =
        enabledFeatureBlockReason(context) == null

    private fun enabledFeatureBlockReason(context: Context): NotificationBlockReason? {
        val preferences = context.prefs()
        val liveEnabled = preferences.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
        val watchStreakEnabled = preferences.getBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, false)
        val liveReason = if (liveEnabled) LiveNotificationNotifier(context).notificationBlockReason() else null
        val watchStreakReason = if (watchStreakEnabled) WatchStreakReminderNotifier(context).notificationBlockReason() else null
        return when {
            liveEnabled && watchStreakEnabled -> if (liveReason == null || watchStreakReason == null) null else liveReason
            liveEnabled -> liveReason
            watchStreakEnabled -> watchStreakReason
            else -> null
        }
    }

    private fun bestEffortDisableLocked(context: Context) {
        runCatching { disableLocked(context) }
            .onFailure { Log.w(TAG, "Unable to roll back live notification scheduling", it) }
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

    private fun scheduleWatchdogAlarm(context: Context) {
        val selectedMode = mode(context)
        val enabled = context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)
        val notificationsAllowed = canPostNotifications(context)
        if (!shouldScheduleLiveNotificationWatchdog(enabled, notificationsAllowed, selectedMode)) {
            cancelWatchdogAlarm(context)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + LIVE_NOTIFICATION_WATCHDOG_INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                watchdogPendingIntent(context),
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                watchdogPendingIntent(context),
            )
        }
    }

    private fun cancelWatchdogAlarm(context: Context) {
        val pendingIntent = watchdogPendingIntent(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        WATCHDOG_REQUEST_CODE,
        Intent()
            .setComponent(ComponentName(context, LiveNotificationWatchdogReceiver::class.java))
            .setAction(LiveNotificationWatchdogReceiver.ACTION_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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

    private const val TAG = "LiveNotificationScheduler"

    private const val PERIODIC_WORK_NAME = "live_notifications"
    private const val IMMEDIATE_WORK_NAME = "live_notifications_now"
    private const val WATCHDOG_WORK_NAME = "live_notifications_watchdog"
    private const val WATCHDOG_REQUEST_CODE = 4300
    private const val LEGACY_CONNECTION_CHANNEL_ID = "xtra_live_connection_channel"
}
