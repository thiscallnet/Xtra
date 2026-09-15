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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                synchronized(notificationLock) {
                    notificationManager.notify(liveNotificationTag(event), notificationId(event), buildNotification(event))
                }
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
        deliveredEvents.forEach(::enqueueRichUpdate)
        firstError?.let { throw it }
        return delivered
    }

    fun cancelLiveNotifications() {
        synchronized(notificationLock) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.activeNotifications
                    .filter { it.notification.channelId == liveChannelId }
                    .forEach { notificationManager.cancel(it.tag, it.id) }
            }
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

    private fun buildNotification(
        event: NotificationEvent,
        largeIcon: Bitmap? = null,
        preview: Bitmap? = null,
        onlyAlertOnce: Boolean = false,
    ) = NotificationCompat.Builder(context, liveChannelId).apply {
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
        val richContent = context.prefs().getBoolean(C.LIVE_NOTIFICATION_RICH_CONTENT, true)
        val showPreview = richContent && context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_PREVIEW, true)
        val showAvatar = richContent && context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_AVATAR, true)
        val showTitle = !richContent || context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_TITLE, true)
        val showCategory = !richContent || context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_CATEGORY, true)
        val showViewers = richContent && context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_VIEWERS, false)
        val streamTitle = event.title?.takeIf { it.isNotBlank() && showTitle }
            ?: gameName?.let { context.getString(R.string.live_notification_streaming, it) }
            ?: context.getString(R.string.live_notification_live_now)
        val notificationSubtext = listOfNotNull(
            gameName?.takeIf { showCategory },
            event.viewerCount?.takeIf { showViewers && it >= 0 }?.let {
                context.getString(R.string.live_notification_viewers, TwitchApiHelper.formatCount(it, compact = true))
            },
        ).joinToString(context.getString(R.string.notification_detail_separator))
        setContentTitle(context.getString(R.string.live_notification, displayName))
        setContentText(streamTitle)
        val effectivePreview = preview.takeIf { showPreview }
        val effectiveLargeIcon = largeIcon.takeIf { showAvatar }
        if (effectivePreview != null) {
            setStyle(NotificationCompat.BigPictureStyle().bigPicture(effectivePreview).bigLargeIcon(effectiveLargeIcon))
        } else {
            setStyle(NotificationCompat.BigTextStyle().bigText(streamTitle))
        }
        notificationSubtext.takeIf { it.isNotBlank() }?.let(::setSubText)
        setSmallIcon(R.drawable.notification_icon)
        effectiveLargeIcon?.let(::setLargeIcon)
        setWhen(event.startedAt)
        setAutoCancel(true)
        if (onlyAlertOnce) {
            setOnlyAlertOnce(true)
        }
        addExtras(Bundle().apply {
            putString(NOTIFICATION_EVENT_ID_EXTRA, event.eventId)
        })
        val notificationIntent = Intent()
        notificationIntent.setClassName(context, MainActivity::class.java.name)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        notificationIntent.action = MainActivity.INTENT_LIVE_NOTIFICATION
        notificationIntent.putExtra(MainActivity.KEY_VIDEO, event.toStream())
        setContentIntent(
            PendingIntent.getActivity(
                context,
                notificationId(event),
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATION_WATCH_ACTION, true)) {
            addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.notification_icon,
                    context.getString(R.string.live_notification_watch),
                    PendingIntent.getActivity(
                        context,
                        notificationId(event) + 1,
                        notificationIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
        }
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATION_CHAT_ACTION, true)) {
            addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.notification_icon,
                    context.getString(R.string.live_notification_chat),
                    PendingIntent.getActivity(
                        context,
                        notificationId(event) + 2,
                        Intent(notificationIntent).setAction(MainActivity.INTENT_LIVE_NOTIFICATION_LISTEN),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
        }
    }.build()

    private fun enqueueRichUpdate(event: NotificationEvent) {
        if (!context.prefs().getBoolean(C.LIVE_NOTIFICATION_RICH_CONTENT, true)) return
        if (!context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_AVATAR, true) &&
            !context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_PREVIEW, true)
        ) return
        richUpdateScope.launch(Dispatchers.IO) {
            val previewDeferred = async {
                if (context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_PREVIEW, true) && canLoadPreview()) {
                    loadBitmap(TwitchApiHelper.getStreamThumbnail(event.thumbnailURL, 720, 405), 720, 405)
                } else null
            }
            val avatarDeferred = async {
                if (context.prefs().getBoolean(C.LIVE_NOTIFICATION_SHOW_AVATAR, true)) {
                    loadBitmap(TwitchApiHelper.getProfileImage(event.channelImageURL), 192, 192)
                } else null
            }
            val preview = previewDeferred.await()
            val avatar = avatarDeferred.await()
            if (preview == null && avatar == null) return@launch
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    synchronized(notificationLock) {
                        val activeNotification = notificationManager.activeNotifications
                            .firstOrNull { it.tag == liveNotificationTag(event) && it.id == notificationId(event) }
                            ?: return@synchronized
                        val activeEventId = activeNotification.notification.extras.getString(NOTIFICATION_EVENT_ID_EXTRA)
                        if (!isLiveNotificationGenerationCurrent(activeEventId, event.eventId)) return@synchronized
                        notificationManager.notify(
                            liveNotificationTag(event),
                            notificationId(event),
                            buildNotification(event, largeIcon = avatar, preview = preview, onlyAlertOnce = true),
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

    private fun canLoadPreview(): Boolean {
        if (context.prefs().getBoolean(C.LIVE_NOTIFICATION_PREVIEW_METERED, true)) return true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
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

    private fun liveNotificationTag(event: NotificationEvent): String = "xtra_live:${event.channelId}"

    private val liveChannelId: String
        get() = context.getString(R.string.notification_live_channel_id)

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val NOTIFICATION_EVENT_ID_EXTRA =
            "com.github.andreyasadchy.xtra.live_notification_event_id"
        private val notificationLock = Any()
        private val richUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

internal fun isLiveNotificationGenerationCurrent(activeEventId: String?, callbackEventId: String): Boolean =
    activeEventId == callbackEventId

enum class NotificationBlockReason {
    POST_NOTIFICATIONS_PERMISSION,
    APP_NOTIFICATIONS_DISABLED,
    LIVE_CHANNEL_DISABLED,
    WATCH_STREAK_CHANNEL_DISABLED,
}
