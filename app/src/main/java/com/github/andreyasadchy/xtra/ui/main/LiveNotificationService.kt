package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/** Thin Android lifetime owner for the opt-in Persistent real-time mode. */
class LiveNotificationService : Service() {

    private var runner: LiveNotificationRunner? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
        if (stopRequested) {
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SWITCH_TO_FAST) {
            applicationContext.prefs().edit {
                putString(C.LIVE_NOTIFICATIONS_MODE, C.LIVE_NOTIFICATIONS_MODE_FAST)
            }
            LiveNotificationScheduler.applyMode(applicationContext)
            return START_NOT_STICKY
        }
        if (!shouldRunPersistentMode()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runner == null) {
            // A process runner may have been used while this service start was
            // pending or retried. Make the ownership handoff explicit.
            LiveNotificationRealtimeEngine.stop()
            running = true
            runner = LiveNotificationRunner(
                context = applicationContext,
                shouldContinue = ::shouldRunPersistentMode,
                onMonitoringStopped = { stopSelf() },
            ).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runner?.stop()
        runner = null
        LiveNotificationScheduler.onPersistentServiceStopped(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shouldRunPersistentMode(): Boolean {
        val prefs = applicationContext.prefs()
        return !stopRequested &&
            prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) &&
            LiveNotificationScheduler.mode(applicationContext) == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT &&
            LiveNotificationScheduler.canPostNotifications(applicationContext)
    }

    private fun createNotification() = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
        .setSmallIcon(R.drawable.notification_icon)
        .setContentTitle(getString(R.string.live_notifications))
        .setContentText(getString(R.string.live_notifications_persistent_notification_text))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                SERVICE_NOTIFICATION_SETTINGS_REQUEST_CODE,
                notificationSettingsIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            NotificationCompat.Action(
                R.drawable.notification_icon,
                getString(R.string.live_notifications_switch_to_fast),
                PendingIntent.getBroadcast(
                    this,
                    ACTION_SWITCH_TO_FAST.hashCode(),
                    Intent(this, LiveNotificationModeReceiver::class.java).setAction(ACTION_SWITCH_TO_FAST),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        )
        .build()

    private fun notificationSettingsIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, SERVICE_CHANNEL_ID)
        }
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra("android.provider.extra.APP_PACKAGE", packageName)
        }
    }

    companion object {
        const val ACTION_SWITCH_TO_FAST = "com.github.andreyasadchy.xtra.action.SWITCH_LIVE_NOTIFICATIONS_TO_FAST"
        internal const val SERVICE_CHANNEL_ID = "xtra_live_monitoring_service"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val SERVICE_NOTIFICATION_SETTINGS_REQUEST_CODE = 1002

        @Volatile
        private var running = false

        @Volatile
        private var stopRequested = false

        fun isRunning(): Boolean = running

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    context.getString(R.string.live_notifications),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.live_notifications_persistent_notification_description)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        fun openNotificationChannelSettings(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return false
            }
            ensureNotificationChannel(context)
            return runCatching {
                context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, SERVICE_CHANNEL_ID)
                })
                true
            }.getOrDefault(false)
        }

        fun start(context: Context): Boolean = runCatching {
            stopRequested = false
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveNotificationService::class.java),
            )
        }.isSuccess

        fun stop(context: Context) {
            stopRequested = true
            context.stopService(Intent(context, LiveNotificationService::class.java))
        }
    }
}
