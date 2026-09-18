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
import androidx.core.content.edit
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class DropsLiveUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val json: Json,
) {
    private val preferences = context.prefs()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var snapshot: DropsLiveUpdateSnapshot? = loadSnapshot()
    private var lastFinishedDropId: String? = preferences.getString(C.DROPS_LIVE_UPDATE_RESULT_ID, null)

    init {
        if (snapshot != null && preferences.getBoolean(C.DROPS_TRACKING_ENABLED, true)) {
            if (LiveUpdateLogic.dropIsActive(snapshot!!)) {
                postSnapshot()
            } else finish()
        }
    }

    fun track(drop: TwitchDrop) {
        if (!preferences.getBoolean(C.DROPS_TRACKING_ENABLED, true)) return
        val id = drop.id.takeIf(String::isNotBlank) ?: return
        val campaignId = drop.campaignId?.takeIf(String::isNotBlank) ?: return
        if (snapshot?.dropId != id) {
            lastFinishedDropId = null
            preferences.edit { remove(C.DROPS_LIVE_UPDATE_RESULT_ID) }
        }
        snapshot = DropsLiveUpdateSnapshot(
            dropId = id,
            campaignId = campaignId,
            campaignName = drop.campaignName.orEmpty(),
            gameName = drop.gameName.orEmpty(),
            rewardName = drop.rewardName ?: drop.name ?: drop.campaignName.orEmpty(),
            currentMinutes = drop.currentMinutesWatched,
            requiredMinutes = drop.requiredMinutesWatched,
            claimed = drop.isClaimed,
            imageUrl = drop.imageUrl,
            updatedAtMs = System.currentTimeMillis(),
        )
        val expiresAtMs = drop.campaignEndTime?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        }
        val now = System.currentTimeMillis()
        snapshot = snapshot!!.copy(
            state = when {
                drop.isClaimed -> LiveUpdateLogic.DROP_COMPLETED
                expiresAtMs != null && expiresAtMs <= now -> LiveUpdateLogic.DROP_EXPIRED
                drop.currentMinutesWatched >= drop.requiredMinutesWatched && drop.requiredMinutesWatched > 0 -> LiveUpdateLogic.DROP_COMPLETED
                drop.dropInstanceId.isNullOrBlank() -> LiveUpdateLogic.DROP_PAUSED
                else -> LiveUpdateLogic.DROP_ACTIVE
            },
            expiresAtMs = expiresAtMs,
        )
        saveSnapshot()
        when {
            LiveUpdateLogic.dropIsActive(snapshot!!) -> {
                postSnapshot()
            }
            snapshot!!.state == LiveUpdateLogic.DROP_COMPLETED || snapshot!!.state == LiveUpdateLogic.DROP_EXPIRED -> finish()
            else -> {
                postSnapshot()
            }
        }
    }

    fun isTracking(dropId: String): Boolean = snapshot?.dropId == dropId

    fun toggle(drop: TwitchDrop) {
        if (isTracking(drop.id)) untrack() else track(drop)
    }

    fun update(drops: List<TwitchDrop>) {
        val trackedId = snapshot?.dropId
        if (trackedId != null) {
            drops.firstOrNull { it.id == trackedId }?.let(::track)
        } else if (preferences.getBoolean(C.DROPS_AUTO_TRACK_ACTIVE, false)) {
            drops.firstOrNull {
                !it.isClaimed &&
                    !it.dropInstanceId.isNullOrBlank() &&
                    it.currentMinutesWatched < it.requiredMinutesWatched &&
                    it.requiredMinutesWatched > 0
            }?.let(::track)
        }
    }

    fun untrack() {
        snapshot = null
        lastFinishedDropId = null
        artworkJob?.cancel()
        artworkJob = null
        artworkKey = null
        preferences.edit {
            remove(C.DROPS_LIVE_UPDATE_SNAPSHOT)
            remove(C.DROPS_LIVE_UPDATE_RESULT_ID)
        }
        notificationManager.cancel(LiveUpdateLogic.DROPS_TAG, NOTIFICATION_ID)
    }

    private fun finish() {
        artworkJob?.cancel()
        artworkJob = null
        artworkKey = null
        notificationManager.cancel(LiveUpdateLogic.DROPS_TAG, NOTIFICATION_ID)
        val current = snapshot ?: return
        if (lastFinishedDropId == current.dropId) return
        lastFinishedDropId = current.dropId
        preferences.edit { putString(C.DROPS_LIVE_UPDATE_RESULT_ID, current.dropId) }
        if (preferences.getBoolean(C.DROPS_NOTIFY_COMPLETED, true)) {
            postResult(current)
        }
        snapshot = null
        preferences.edit { remove(C.DROPS_LIVE_UPDATE_SNAPSHOT) }
    }

    private fun postSnapshot(current: DropsLiveUpdateSnapshot? = snapshot, largeIcon: Bitmap? = null) {
        current ?: return
        if (!canPost() || !preferences.getBoolean(C.DROPS_TRACKING_ENABLED, true)) return
        ensureChannels()
        val percent = LiveUpdateLogic.dropPercent(current.currentMinutes, current.requiredMinutes)
        val body = when (current.state) {
            LiveUpdateLogic.DROP_PAUSED -> context.getString(
                R.string.drops_live_update_paused,
                current.currentMinutes,
                current.requiredMinutes,
                percent,
            )
            LiveUpdateLogic.DROP_EXPIRED -> context.getString(R.string.drops_live_update_expired)
            else -> context.getString(
                R.string.drops_live_update_progress,
                percent,
                LiveUpdateLogic.dropRemainingMinutes(current.currentMinutes, current.requiredMinutes),
                current.currentMinutes,
                current.requiredMinutes,
            )
        }
        val builder = NotificationCompat.Builder(context, trackingChannelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.drops_live_update_title, current.rewardName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(100, percent, false)
            .setOngoing(current.state == LiveUpdateLogic.DROP_ACTIVE || current.state == LiveUpdateLogic.DROP_PAUSED)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(current))
            .addAction(untrackAction())
            .requestPromotedOngoingIfAllowed(
                Build.VERSION.SDK_INT,
                preferences.getBoolean(C.DROPS_REQUEST_PROMOTED, true),
                hasPromotedPermission(),
                canPostPromotedNotifications(),
                criticalText(current, percent),
            )
            .apply {
                if (current.gameName.isNotBlank()) addAction(findStreamsAction(current))
                largeIcon?.let(::setLargeIcon)
            }
        current.expiresAtMs?.let {
            builder.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(0L) + CLEANUP_GRACE_MS)
        }
        notificationManager.notify(LiveUpdateLogic.DROPS_TAG, NOTIFICATION_ID, builder.build())
        if (largeIcon == null) enqueueArtwork(current)
    }

    private fun enqueueArtwork(current: DropsLiveUpdateSnapshot) {
        val url = current.imageUrl?.takeIf(String::isNotBlank) ?: return
        val key = "${current.dropId}:$url"
        if (artworkKey == key || artworkJob?.isActive == true) return
        artworkKey = key
        artworkJob = scope.launch(Dispatchers.IO) {
            val bitmap = loadArtwork(url) ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                val latest = snapshot?.takeIf { it.dropId == current.dropId && it.imageUrl == current.imageUrl } ?: return@withContext
                postSnapshot(latest, bitmap)
            }
        }
    }

    private suspend fun loadArtwork(url: String): Bitmap? = runCatching {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).size(192, 192).build(),
        )
        (result as? coil3.request.SuccessResult)?.image?.asDrawable(context.resources)?.let(::drawableToBitmap)
    }.getOrNull()

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

    private fun postResult(current: DropsLiveUpdateSnapshot) {
        if (!canPost()) return
        ensureChannels()
        val text = if (current.state == LiveUpdateLogic.DROP_EXPIRED) {
            context.getString(R.string.drops_live_update_expired_result, current.rewardName)
        } else {
            context.getString(R.string.drops_live_update_completed_text, current.rewardName)
        }
        notificationManager.notify(
            LiveUpdateLogic.DROPS_RESULT_TAG,
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, resultChannelId)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(
                    context.getString(
                        if (current.state == LiveUpdateLogic.DROP_EXPIRED) R.string.drops_live_update_expired_title
                        else R.string.drops_live_update_completed,
                    ),
                )
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openIntent(current))
                .build(),
        )
    }

    private fun findStreamsAction(current: DropsLiveUpdateSnapshot): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.notification_icon,
        context.getString(R.string.drops_find_streams),
        openIntent(current, findStreams = true),
    ).build()

    private fun untrackAction(): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.notification_icon,
        context.getString(R.string.live_update_unpin),
        PendingIntent.getBroadcast(
            context,
            4201,
            Intent(context, LiveUpdateActionReceiver::class.java).setAction(LiveUpdateActionReceiver.ACTION_UNPIN_DROPS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    private fun openIntent(current: DropsLiveUpdateSnapshot, findStreams: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context,
        current.dropId.hashCode() + if (findStreams) 1 else 0,
        Intent(context, MainActivity::class.java).apply {
            action = if (findStreams) MainActivity.INTENT_FIND_DROPS_STREAMS else MainActivity.INTENT_OPEN_DROPS
            putExtra(MainActivity.EXTRA_DROPS_CAMPAIGN_ID, current.campaignId)
            putExtra(MainActivity.EXTRA_DROPS_ID, current.dropId)
            putExtra(MainActivity.EXTRA_DROPS_GAME_NAME, current.gameName)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(trackingChannelId, context.getString(R.string.drops_live_update_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(resultChannelId, context.getString(R.string.drops_live_update_result_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun hasPromotedPermission(): Boolean = Build.VERSION.SDK_INT < 36 ||
        ContextCompat.checkSelfPermission(context, "android.permission.POST_PROMOTED_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun canPostPromotedNotifications(): Boolean = Build.VERSION.SDK_INT >= 36 &&
        NotificationManagerCompat.from(context).canPostPromotedNotifications()

    private fun criticalText(current: DropsLiveUpdateSnapshot, percent: Int): String = when (
        preferences.getString(C.DROPS_STATUS_CHIP_TEXT, C.DROPS_CHIP_PERCENT)
    ) {
        C.DROPS_CHIP_TIME_REMAINING -> context.getString(
            R.string.drops_live_update_remaining_chip,
            LiveUpdateLogic.dropRemainingMinutes(current.currentMinutes, current.requiredMinutes),
        )
        C.DROPS_CHIP_DROP -> context.getString(R.string.drops_live_update_chip)
        else -> context.getString(R.string.drops_live_update_percent_chip, percent)
    }

    private fun saveSnapshot() = preferences.edit { putString(C.DROPS_LIVE_UPDATE_SNAPSHOT, json.encodeToString(snapshot)) }

    private fun loadSnapshot(): DropsLiveUpdateSnapshot? = preferences.getString(C.DROPS_LIVE_UPDATE_SNAPSHOT, null)
        ?.let { runCatching { json.decodeFromString<DropsLiveUpdateSnapshot>(it) }.getOrNull() }

    private val trackingChannelId get() = context.getString(R.string.notification_drops_tracking_channel_id)
    private val resultChannelId get() = context.getString(R.string.notification_drops_results_channel_id)

    private companion object {
        const val NOTIFICATION_ID = 6201
        const val RESULT_NOTIFICATION_ID = 6202
        const val CLEANUP_GRACE_MS = 5 * 60_000L
    }
}
