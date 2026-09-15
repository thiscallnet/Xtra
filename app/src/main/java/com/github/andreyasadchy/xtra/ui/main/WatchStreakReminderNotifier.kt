package com.github.andreyasadchy.xtra.ui.main

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.WatchStreakReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchStreakReminderNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    suspend fun deliver(reminders: List<WatchStreakReminder>): Int {
        if (!canPostNotifications() || reminders.isEmpty()) return 0
        ensureNotificationChannel()
        var delivered = 0
        reminders.forEach { reminder ->
            runCatching {
                synchronized(notificationLock) {
                    notificationManager.notify(
                        notificationTag(reminder),
                        notificationId(reminder),
                        buildNotification(reminder, silent = false),
                    )
                }
                delivered += 1
                enqueueRichUpdate(reminder)
            }
        }
        return delivered
    }

    fun canPostNotifications(): Boolean = notificationBlockReason() == null

    fun notificationBlockReason(): NotificationBlockReason? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationBlockReason.POST_NOTIFICATIONS_PERMISSION
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationBlockReason.APP_NOTIFICATIONS_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(WATCH_STREAK_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return NotificationBlockReason.WATCH_STREAK_CHANNEL_DISABLED
        }
        return null
    }

    fun cancelWatchStreakNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        synchronized(notificationLock) {
            notificationManager.activeNotifications
                .filter { it.notification.channelId == WATCH_STREAK_CHANNEL_ID }
                .forEach { notificationManager.cancel(it.tag, it.id) }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(WATCH_STREAK_CHANNEL_ID) == null
        ) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    WATCH_STREAK_CHANNEL_ID,
                    context.getString(R.string.notification_watch_streak_channel_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_watch_streak_channel_description)
                },
            )
        }
    }

    private fun buildNotification(reminder: WatchStreakReminder, silent: Boolean, largeIcon: Bitmap? = null, picture: Bitmap? = null) =
        NotificationCompat.Builder(context, WATCH_STREAK_CHANNEL_ID).apply {
            setContentTitle(reminder.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.watch_streak_notification_title))
            setContentText(reminder.body)
            if (picture != null) {
                setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(largeIcon))
            } else {
                setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            }
            reminder.channelName?.takeIf { it.isNotBlank() }?.let(::setSubText)
            setSmallIcon(R.drawable.notification_icon)
            largeIcon?.let(::setLargeIcon)
            setWhen(reminder.createdAt?.toEpochMilli() ?: System.currentTimeMillis())
            setShowWhen(true)
            setAutoCancel(true)
            setOnlyAlertOnce(true)
            setSilent(silent)
            addExtras(android.os.Bundle().apply {
                putString(NOTIFICATION_ID_EXTRA, reminder.notificationId)
            })
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId(reminder),
                    notificationIntent(reminder),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }.build()

    private fun enqueueRichUpdate(reminder: WatchStreakReminder) {
        if (reminder.vodThumbnailUrl.isNullOrBlank() && reminder.channelImageUrl.isNullOrBlank()) return
        richUpdateScope.launch(Dispatchers.IO) {
            val pictureDeferred = async { loadBitmap(reminder.vodThumbnailUrl, 720, 405) }
            val iconDeferred = async { loadBitmap(reminder.channelImageUrl, 192, 192) }
            val picture = pictureDeferred.await()
            val icon = iconDeferred.await()
            if (picture == null && icon == null) return@launch
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    synchronized(notificationLock) {
                        val active = notificationManager.activeNotifications.firstOrNull {
                            it.tag == notificationTag(reminder) && it.id == notificationId(reminder)
                        } ?: return@synchronized
                        if (active.notification.extras.getString(NOTIFICATION_ID_EXTRA) != reminder.notificationId) return@synchronized
                        notificationManager.notify(
                            notificationTag(reminder),
                            notificationId(reminder),
                            buildNotification(reminder, silent = true, largeIcon = icon, picture = picture),
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadBitmap(url: String?, width: Int, height: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(width, height)
                    .build(),
            )
            (result as? coil3.request.SuccessResult)?.image?.asDrawable(context.resources)?.let(::drawableToBitmap)
        }.getOrNull()
    }

    private fun notificationIntent(reminder: WatchStreakReminder): Intent = Intent(
        Intent.ACTION_VIEW,
        reminder.actionUrl?.let(Uri::parse),
    ).apply {
        setClass(context, MainActivity::class.java)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).also { canvas ->
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
    }

    // Keep one Android notification per inbox item so a later warning for the same channel
    // can alert again even though the rich-content update remains silent. The full inbox ID is
    // in the tag rather than a hashed integer so separate items cannot collide.
    private fun notificationId(reminder: WatchStreakReminder): Int = NOTIFICATION_ID

    private fun notificationTag(reminder: WatchStreakReminder): String =
        "xtra_watch_streak:${reminder.notificationId}"

    companion object {
        const val WATCH_STREAK_CHANNEL_ID = "xtra_watch_streak_recovery"
        private const val NOTIFICATION_ID = 0
        private const val NOTIFICATION_ID_EXTRA = "com.github.andreyasadchy.xtra.watch_streak_notification_id"
        private val notificationLock = Any()
        private val richUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
