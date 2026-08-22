package com.github.andreyasadchy.xtra.ui.login

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R

/** Keeps device-code polling allowed while Twitch's browser page is foreground. */
class LoginAuthorizationService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(getString(R.string.login_authorization_notification_title))
            .setContentText(getString(R.string.login_authorization_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    LOGIN_NOTIFICATION_REQUEST_CODE,
                    Intent(this, LoginActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "xtra_login_authorization"
        private const val NOTIFICATION_ID = 1003
        private const val LOGIN_NOTIFICATION_REQUEST_CODE = 1004

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LoginAuthorizationService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoginAuthorizationService::class.java))
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.login_authorization_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.login_authorization_notification_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
