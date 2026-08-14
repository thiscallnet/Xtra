package com.github.andreyasadchy.xtra.ui.multiview.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity

/** Keeps the multiview process alive while its video surfaces are detached in the background. */
class MultiviewBackgroundPlaybackService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_multiview)
            .setContentTitle(getString(R.string.multiview_background_playback))
            .setContentText(getString(R.string.multiview_background_playback_description))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_REQUEST_CODE,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "multiview_background_playback"
        private const val NOTIFICATION_ID = 1101
        private const val NOTIFICATION_REQUEST_CODE = 1102
        private const val ACTION_STOP = "com.github.andreyasadchy.xtra.action.STOP_MULTIVIEW_BACKGROUND"
        private const val TAG = "MultiviewBackground"

        fun start(context: Context): Boolean {
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MultiviewBackgroundPlaybackService::class.java),
                )
                true
            }.onFailure { error ->
                Log.e(TAG, "Unable to start multiview background playback", error)
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MultiviewBackgroundPlaybackService::class.java))
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.multiview_background_playback),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.multiview_background_playback_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }
}
