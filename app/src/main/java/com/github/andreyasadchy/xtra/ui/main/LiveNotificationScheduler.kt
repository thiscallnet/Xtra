package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import android.content.Intent
import java.util.concurrent.TimeUnit

object LiveNotificationScheduler {

    fun enable(context: Context, baselineOnly: Boolean) {
        if (!canPostNotifications(context)) {
            disable(context)
            return
        }
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        if (isRealtime(context)) {
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            startRealtimeService(context, baselineOnly)
        } else {
            context.stopService(Intent(context, LiveNotificationService::class.java))
            enqueueImmediateWork(context, baselineOnly)
        }
        clearLegacyConnectionNotification(context)
    }

    fun disable(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        context.stopService(Intent(context, LiveNotificationService::class.java))
        LiveNotificationNotifier(context).cancelLiveNotifications()
        clearLegacyConnectionNotification(context)
    }

    fun refresh(context: Context) {
        clearLegacyConnectionNotification(context)
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) && canPostNotifications(context)) {
            enable(context, baselineOnly = true)
        } else {
            disable(context)
        }
    }

    fun canPostNotifications(context: Context): Boolean = LiveNotificationNotifier(context).canPostNotifications()

    fun isRealtime(context: Context): Boolean =
        context.prefs().getString(C.LIVE_NOTIFICATIONS_MODE, C.LIVE_NOTIFICATIONS_MODE_BATTERY) == C.LIVE_NOTIFICATIONS_MODE_REALTIME

    fun enqueueImmediateFallback(context: Context, baselineOnly: Boolean) {
        enqueueImmediateWork(context, baselineOnly)
    }

    private fun startRealtimeService(context: Context, baselineOnly: Boolean) {
        val intent = Intent(context, LiveNotificationService::class.java).apply {
            action = LiveNotificationService.ACTION_POLL_NOW
            putExtra(LiveNotificationService.EXTRA_BASELINE_ONLY, baselineOnly)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // A background-start restriction or a device-specific FGS rule should
            // not disable alerts; the existing WorkManager path remains a fallback.
            enqueueImmediateWork(context, baselineOnly)
        }
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
