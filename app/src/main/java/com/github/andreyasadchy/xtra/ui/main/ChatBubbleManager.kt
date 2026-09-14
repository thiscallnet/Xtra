package com.github.andreyasadchy.xtra.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatBubbleManager(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope,
) {
    private val preferences = context.prefs()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Volatile private var bubbleVisible = false
    private var notificationUpdateJob: Job? = null
    private var lastNotificationUpdateAt = 0L
    private val recentPreviews = ArrayDeque<ChatBubblePreview>(3)
    private val seenMessageKeys = LinkedHashSet<String>()

    fun setBubbleVisible(visible: Boolean) {
        bubbleVisible = visible
        if (visible) clearUnread()
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !context.isTelevision()

    fun areBubblesAllowed(): Boolean = isSupported() && notificationManager.areBubblesAllowed()

    fun isOpen(): Boolean = !preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null).isNullOrBlank()

    fun open(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?): Boolean {
        if (!areBubblesAllowed() || !preferences.getBoolean(C.CHAT_BUBBLE_ENABLED, false)) return false
        val id = channelId?.takeIf(String::isNotBlank) ?: return false
        val login = channelLogin?.takeIf(String::isNotBlank) ?: return false
        val name = channelName?.takeIf(String::isNotBlank) ?: login
        val oldId = preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null)
        if (oldId != null && oldId != id) close()
        ensureChannel()
        createShortcut(id, login, name, streamId)
        preferences.edit {
            putString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, id)
            putString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_LOGIN, login)
            putString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_NAME, name)
            putInt(C.CHAT_BUBBLE_UNREAD_COUNT, 0)
        }
        post(id, login, name, streamId)
        return true
    }

    fun toggle(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?): Boolean {
        if (isOpen() && preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null) == channelId) {
            close()
            return false
        }
        return open(channelId, channelLogin, channelName, streamId)
    }

    fun close() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        recentPreviews.clear()
        seenMessageKeys.clear()
        val id = preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null)
        if (id != null) notificationManager.cancel(LiveUpdateLogic.chatBubbleTag(id), NOTIFICATION_ID)
        preferences.edit {
            remove(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID)
            remove(C.CHAT_BUBBLE_ACTIVE_CHANNEL_LOGIN)
            remove(C.CHAT_BUBBLE_ACTIVE_CHANNEL_NAME)
            putInt(C.CHAT_BUBBLE_UNREAD_COUNT, 0)
        }
    }

    fun recordMessage(channelId: String?, sender: String?, message: String?, messageId: String? = null) {
        if (!isOpen() || bubbleVisible || channelId != preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null)) return
        val key = messageId?.takeIf(String::isNotBlank)?.let { "$channelId:$it" }
        if (key != null && !seenMessageKeys.add(key)) return
        while (seenMessageKeys.size > MAX_SEEN_MESSAGE_KEYS) {
            seenMessageKeys.remove(seenMessageKeys.first())
        }
        val unread = (preferences.getInt(C.CHAT_BUBBLE_UNREAD_COUNT, 0) + 1).coerceAtMost(99)
        preferences.edit { putInt(C.CHAT_BUBBLE_UNREAD_COUNT, unread) }
        if (preferences.getBoolean(C.CHAT_BUBBLE_SHOW_PREVIEW, false) &&
            !sender.isNullOrBlank() && !message.isNullOrBlank()
        ) {
            if (recentPreviews.size == 3) recentPreviews.removeFirst()
            recentPreviews.addLast(ChatBubblePreview(sender.take(64), message.replace(Regex("\\s+"), " ").trim().take(180)))
        }
        scheduleCollapsedRefresh()
    }

    fun clearUnread() {
        preferences.edit { putInt(C.CHAT_BUBBLE_UNREAD_COUNT, 0) }
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
        recentPreviews.clear()
        if (isOpen() && !bubbleVisible) postCurrentBubble()
    }

    private fun scheduleCollapsedRefresh() {
        val wait = (5_000L - (SystemClock.elapsedRealtime() - lastNotificationUpdateAt)).coerceAtLeast(0L)
        if (wait == 0L) {
            postCurrentBubble()
            return
        }
        if (notificationUpdateJob?.isActive == true) return
        notificationUpdateJob = scope.launch {
            delay(wait)
            postCurrentBubble()
            notificationUpdateJob = null
        }
    }

    private fun postCurrentBubble() {
        val channelId = preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_ID, null) ?: return
        val login = preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_LOGIN, null) ?: return
        val name = preferences.getString(C.CHAT_BUBBLE_ACTIVE_CHANNEL_NAME, null) ?: login
        val previews = recentPreviews.toList()
        recentPreviews.clear()
        lastNotificationUpdateAt = SystemClock.elapsedRealtime()
        post(channelId, login, name, null, previews)
    }

    private fun post(
        channelId: String,
        login: String,
        name: String,
        streamId: String?,
        previews: List<ChatBubblePreview> = emptyList(),
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val activityIntent = Intent(context, ChatBubbleActivity::class.java).apply {
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_ID, channelId)
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_LOGIN, login)
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_NAME, name)
            putExtra(ChatBubbleActivity.EXTRA_STREAM_ID, streamId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or bubblePendingIntentFlags(),
        )
        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val bubble = NotificationCompat.BubbleMetadata.Builder(contentIntent, icon)
            .setDesiredHeight(720)
            .setAutoExpandBubble(preferences.getBoolean(C.CHAT_BUBBLE_AUTO_EXPAND, true))
            .setSuppressNotification(true)
            .build()
        val unread = preferences.getInt(C.CHAT_BUBBLE_UNREAD_COUNT, 0)
        val messagingStyle = NotificationCompat.MessagingStyle(name)
        if (previews.isEmpty()) {
            messagingStyle.addMessage(context.getString(R.string.chat_bubble_open_text), System.currentTimeMillis(), name)
        } else {
            previews.forEach { preview -> messagingStyle.addMessage(preview.message, System.currentTimeMillis(), preview.sender) }
        }
        val builder = NotificationCompat.Builder(context, channelIdForBubbles)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(name)
            .setContentText(previews.lastOrNull()?.let {
                context.getString(R.string.chat_bubble_message_preview, it.sender, it.message)
            } ?: context.getString(R.string.chat_bubble_open_text))
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setBubbleMetadata(bubble)
            .setShortcutId(shortcutId(channelId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(closeAction())
        if (preferences.getBoolean(C.CHAT_BUBBLE_SHOW_UNREAD, true) && unread > 0) {
            builder.setNumber(unread)
        }
        notificationManager.notify(LiveUpdateLogic.chatBubbleTag(channelId), NOTIFICATION_ID, builder.build())
    }

    private fun closeAction(): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.notification_icon,
        context.getString(R.string.chat_bubble_close),
        PendingIntent.getBroadcast(
            context,
            4301,
            Intent(context, LiveUpdateActionReceiver::class.java).setAction(LiveUpdateActionReceiver.ACTION_CLOSE_CHAT_BUBBLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    private fun createShortcut(channelId: String, login: String, name: String, streamId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val shortcutIntent = Intent(context, ChatBubbleActivity::class.java).apply {
            // Android 15 validates every intent in a dynamic shortcut and requires an action,
            // even when the intent targets an explicit activity component.
            action = Intent.ACTION_VIEW
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_ID, channelId)
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_LOGIN, login)
            putExtra(ChatBubbleActivity.EXTRA_CHANNEL_NAME, name)
            putExtra(ChatBubbleActivity.EXTRA_STREAM_ID, streamId)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(channelId))
            .setShortLabel(name)
            .setLongLabel(context.getString(R.string.chat_bubble_shortcut_label, name))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelIdForBubbles, context.getString(R.string.chat_bubble_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(true)
                },
            )
        }
    }

    private fun bubblePendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun shortcutId(channelId: String): String = "xtra_chat_$channelId"
    private val channelIdForBubbles get() = context.getString(R.string.notification_chat_bubbles_channel_id)

    private data class ChatBubblePreview(val sender: String, val message: String)

    private companion object {
        const val NOTIFICATION_ID = 6301
        const val MAX_SEEN_MESSAGE_KEYS = 512
    }
}
