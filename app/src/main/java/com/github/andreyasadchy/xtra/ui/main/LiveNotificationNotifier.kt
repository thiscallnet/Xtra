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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class LiveNotificationNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    suspend fun deliverPending(repository: NotificationsRepository): Int {
        if (!canPostNotifications()) {
            return 0
        }
        val events = repository.getPendingNotificationEvents()
        if (events.isEmpty()) {
            return 0
        }
        ensureLiveNotificationChannel()
        var delivered = 0
        var firstError: Throwable? = null
        val deliveredEvents = mutableListOf<NotificationEvent>()
        events.forEach { event ->
            try {
                notificationManager.notify(notificationId(event), buildNotification(event))
                repository.markNotificationDelivered(event.eventId)
                deliveredEvents += event
                delivered += 1
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e
                }
            }
        }
        // All durable text alerts are posted before any avatar request starts. An image CDN
        // failure must never delay or duplicate the live alert itself.
        deliveredEvents.forEach(::enqueueAvatarUpdate)
        firstError?.let { throw it }
        return delivered
    }

    fun cancelLiveNotifications() {
        notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.activeNotifications
                .filter { it.notification.channelId == liveChannelId }
                .forEach { notificationManager.cancel(it.tag, it.id) }
        }
    }

    fun canPostNotifications(): Boolean {
        return notificationBlockReason() == null
    }

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
            notificationManager.getNotificationChannel(liveChannelId)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return NotificationBlockReason.LIVE_CHANNEL_DISABLED
        }
        return null
    }

    private fun ensureLiveNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(liveChannelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    liveChannelId,
                    ContextCompat.getString(context, R.string.notification_live_channel_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    private fun buildNotification(event: NotificationEvent, largeIcon: Bitmap? = null) = NotificationCompat.Builder(context, liveChannelId).apply {
        val channelName = event.channelName?.takeIf { it.isNotBlank() }
        val channelLogin = event.channelLogin?.takeIf { it.isNotBlank() }
        val displayName = if (channelName != null && channelLogin != null && !channelLogin.equals(channelName, true)) {
            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                "0" -> "$channelName($channelLogin)"
                "1" -> channelName
                else -> channelLogin
            }
        } else {
            channelName ?: channelLogin ?: event.channelId
        }
        val gameName = event.gameName?.takeIf { it.isNotBlank() }
        val streamTitle = event.title?.takeIf { it.isNotBlank() }
            ?: gameName?.let { context.getString(R.string.live_notification_streaming, it) }
            ?: context.getString(R.string.live_notification_live_now)
        setContentTitle(context.getString(R.string.live_notification, displayName))
        setContentText(streamTitle)
        setStyle(NotificationCompat.BigTextStyle().bigText(streamTitle))
        gameName?.let(::setSubText)
        setSmallIcon(R.drawable.notification_icon)
        largeIcon?.let(::setLargeIcon)
        setWhen(event.startedAt)
        setAutoCancel(true)
        setOnlyAlertOnce(true)
        setContentIntent(
            PendingIntent.getActivity(
                context,
                notificationId(event),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = MainActivity.INTENT_LIVE_NOTIFICATION
                    putExtra(MainActivity.KEY_VIDEO, event.toStream())
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
    }.build()

    private fun enqueueAvatarUpdate(event: NotificationEvent) {
        val imageUrl = event.channelImageURL?.takeIf { it.isNotBlank() } ?: return
        val request = ImageRequest.Builder(context).apply {
            data(TwitchApiHelper.getProfileImage(imageUrl) ?: imageUrl)
            target(
                onSuccess = { image ->
                    runCatching {
                        notificationManager.notify(
                            notificationId(event),
                            buildNotification(event, drawableToBitmap(image.asDrawable(context.resources))),
                        )
                    }
                },
            )
        }.build()
        runCatching { context.imageLoader.enqueue(request) }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).also { canvas ->
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
    }

    private fun notificationId(event: NotificationEvent): Int = event.channelId.hashCode()

    private val liveChannelId: String
        get() = context.getString(R.string.notification_live_channel_id)

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0
    }
}

enum class NotificationBlockReason {
    POST_NOTIFICATIONS_PERMISSION,
    APP_NOTIFICATIONS_DISABLED,
    LIVE_CHANNEL_DISABLED,
}
