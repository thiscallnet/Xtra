package com.github.andreyasadchy.xtra.ui.main

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.PredictionBetState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class PredictionLiveUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val json: Json,
    private val chatSessionManager: ChatSessionManager,
) {
    private val preferences = context.prefs()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var ticker: Job? = null
    private var lastNotificationAt = 0L
    private var snapshot: PredictionLiveUpdateSnapshot? = loadSnapshot()
    private var lastFinishedPredictionId: String? = preferences.getString(C.PREDICTION_LIVE_UPDATE_RESULT_ID, null)

    init {
        scope.launch {
            chatSessionManager.predictionStates.collectLatest { states ->
                val current = snapshot ?: return@collectLatest
                val shared = states[current.channelId] ?: return@collectLatest
                update(shared.prediction, shared.betState)
            }
        }
        if (snapshot != null && preferences.getBoolean(C.PREDICTION_TRACKING_ENABLED, true)) {
            if (LiveUpdateLogic.predictionIsFinal(snapshot!!.status)) finishIfNeeded()
            else {
                postSnapshot()
                startTicker()
            }
        }
    }

    fun track(
        prediction: Prediction,
        channelId: String,
        channelLogin: String,
        channelName: String,
        betState: PredictionBetState = PredictionBetState(),
        streamId: String? = null,
    ) {
        if (!preferences.getBoolean(C.PREDICTION_TRACKING_ENABLED, true)) return
        val id = prediction.id?.takeIf(String::isNotBlank) ?: return
        val channel = channelId.takeIf(String::isNotBlank) ?: return
        if (snapshot?.predictionId != id) {
            lastFinishedPredictionId = null
            preferences.edit { remove(C.PREDICTION_LIVE_UPDATE_RESULT_ID) }
        }
        snapshot = prediction.toSnapshot(id, channel, channelLogin, channelName, betState, streamId)
        saveSnapshot()
        if (!LiveUpdateLogic.predictionIsFinal(prediction.status.orEmpty())) {
            postSnapshot()
            startTicker()
        } else finishIfNeeded()
    }

    fun update(prediction: Prediction, betState: PredictionBetState = PredictionBetState()) {
        val current = snapshot ?: return
        if (prediction.id != current.predictionId) return
        track(prediction, current.channelId, current.channelLogin, current.channelName, betState, current.streamId)
    }

    fun isTracking(predictionId: String): Boolean = snapshot?.predictionId == predictionId

    fun toggle(
        prediction: Prediction,
        channelId: String,
        channelLogin: String,
        channelName: String,
        betState: PredictionBetState,
        streamId: String? = null,
    ) {
        if (isTracking(prediction.id.orEmpty())) untrack()
        else track(prediction, channelId, channelLogin, channelName, betState, streamId)
    }

    fun untrack() {
        snapshot = null
        lastFinishedPredictionId = null
        ticker?.cancel()
        ticker = null
        lastNotificationAt = 0L
        preferences.edit {
            remove(C.PREDICTION_LIVE_UPDATE_SNAPSHOT)
            remove(C.PREDICTION_LIVE_UPDATE_RESULT_ID)
        }
        notificationManager.cancel(LiveUpdateLogic.PREDICTION_TAG, NOTIFICATION_ID)
    }

    private fun finishIfNeeded() {
        val current = snapshot ?: return
        if (lastFinishedPredictionId == current.predictionId) return
        lastFinishedPredictionId = current.predictionId
        preferences.edit { putString(C.PREDICTION_LIVE_UPDATE_RESULT_ID, current.predictionId) }
        ticker?.cancel()
        ticker = null
        notificationManager.cancel(LiveUpdateLogic.PREDICTION_TAG, NOTIFICATION_ID)
        if (preferences.getBoolean(C.PREDICTION_NOTIFY_RESOLVED, true)) {
            postResult(current)
        }
        snapshot = null
        clearSnapshot()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = snapshot ?: break
                val remaining = LiveUpdateLogic.remainingSeconds(current.locksAtMs, System.currentTimeMillis())
                val interval = if (remaining == null || remaining >= 120L) 60_000L else 15_000L
                delay(interval)
                val latest = snapshot ?: break
                val latestRemaining = LiveUpdateLogic.remainingSeconds(latest.locksAtMs, System.currentTimeMillis())
                val latestInterval = if (latestRemaining == null || latestRemaining >= 120L) 60_000L else 15_000L
                if (SystemClock.elapsedRealtime() - lastNotificationAt >= latestInterval) {
                    postSnapshot()
                }
            }
        }
    }

    private fun postSnapshot() {
        val current = snapshot ?: return
        if (!canPost() || !preferences.getBoolean(C.PREDICTION_TRACKING_ENABLED, true)) return
        ensureChannels()
        val remainingSeconds = LiveUpdateLogic.remainingSeconds(current.locksAtMs, System.currentTimeMillis())
        val remaining = LiveUpdateLogic.formatRemaining(remainingSeconds)
        val percentages = LiveUpdateLogic.outcomePercentages(current.outcomes)
        val outcomeText = current.outcomes.mapIndexed { index, outcome ->
            context.getString(R.string.prediction_live_update_outcome, outcome.title, percentages.getOrElse(index) { 0 })
        }.joinToString(context.getString(R.string.notification_detail_separator))
        val body = listOfNotNull(
            remaining?.let { context.getString(R.string.prediction_live_update_time, it) },
            current.myOutcomeId?.let { selected ->
                current.outcomes.firstOrNull { it.id == selected }?.title?.let {
                    context.getString(R.string.prediction_live_update_my_pick, it)
                }
            },
            outcomeText.takeIf(String::isNotBlank),
        ).joinToString(context.getString(R.string.notification_detail_separator))
        val builder = NotificationCompat.Builder(context, predictionChannelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.prediction_live_update_title, current.channelName))
            .setContentText(current.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { current.title }))
            .setOngoing(!LiveUpdateLogic.predictionIsFinal(current.status))
            .setOnlyAlertOnce(true)
            .setWhen(current.locksAtMs ?: current.updatedAtMs)
            .setContentIntent(openIntent(current))
            .addAction(unpinAction())
            .requestPromotedOngoingIfAllowed(
                Build.VERSION.SDK_INT,
                preferences.getBoolean(C.PREDICTION_REQUEST_PROMOTED, true),
                hasPromotedPermission(),
                canPostPromotedNotifications(),
                criticalText(current, remaining),
            )
        remainingSeconds?.takeIf { it > 0L }?.let {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        current.locksAtMs?.let {
            builder.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(0L) + CLEANUP_GRACE_MS)
        }
        notificationManager.notify(LiveUpdateLogic.PREDICTION_TAG, NOTIFICATION_ID, builder.build())
        lastNotificationAt = SystemClock.elapsedRealtime()
    }

    private fun postResult(current: PredictionLiveUpdateSnapshot) {
        if (!canPost()) return
        ensureChannels()
        val winning = current.outcomes.firstOrNull { it.id == current.winningOutcomeId }?.title
        val text = when {
            winning == null -> context.getString(R.string.prediction_live_update_canceled)
            current.myOutcomeId != null && current.myOutcomeId == current.winningOutcomeId -> context.getString(R.string.prediction_live_update_won, winning)
            current.myOutcomeId != null -> context.getString(R.string.prediction_live_update_lost, winning)
            else -> context.getString(R.string.prediction_live_update_resolved, winning)
        }
        val notification = NotificationCompat.Builder(context, resultChannelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.prediction_live_update_result_title, current.channelName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(current))
            .build()
        notificationManager.notify(LiveUpdateLogic.PREDICTION_RESULT_TAG, RESULT_NOTIFICATION_ID, notification)
    }

    private fun unpinAction(): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.notification_icon,
        context.getString(R.string.live_update_unpin),
        PendingIntent.getBroadcast(
            context,
            4101,
            Intent(context, LiveUpdateActionReceiver::class.java).setAction(LiveUpdateActionReceiver.ACTION_UNPIN_PREDICTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    private fun openIntent(current: PredictionLiveUpdateSnapshot): PendingIntent = PendingIntent.getActivity(
        context,
        current.predictionId.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.INTENT_LIVE_NOTIFICATION_CHAT
            putExtra(MainActivity.KEY_VIDEO, Stream(
                channelId = current.channelId,
                id = current.streamId,
                channelLogin = current.channelLogin,
                channelName = current.channelName,
            ))
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(predictionChannelId, context.getString(R.string.prediction_live_update_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(resultChannelId, context.getString(R.string.prediction_live_update_result_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun hasPromotedPermission(): Boolean = Build.VERSION.SDK_INT < 36 ||
        ContextCompat.checkSelfPermission(context, "android.permission.POST_PROMOTED_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun canPostPromotedNotifications(): Boolean = Build.VERSION.SDK_INT >= 36 &&
        NotificationManagerCompat.from(context).canPostPromotedNotifications()

    private fun criticalText(current: PredictionLiveUpdateSnapshot, remaining: String?): String? = when (
        preferences.getString(C.PREDICTION_STATUS_CHIP_TEXT, C.PREDICTION_CHIP_TIME_REMAINING)
    ) {
        C.PREDICTION_CHIP_MY_PICK -> current.outcomes.firstOrNull { it.id == current.myOutcomeId }?.title?.take(20)
        C.PREDICTION_CHIP_PREDICTION -> context.getString(R.string.prediction_live_update_chip)
        else -> remaining
    }

    private fun saveSnapshot() {
        preferences.edit { putString(C.PREDICTION_LIVE_UPDATE_SNAPSHOT, json.encodeToString(snapshot)) }
    }

    private fun clearSnapshot() = preferences.edit { remove(C.PREDICTION_LIVE_UPDATE_SNAPSHOT) }

    private fun loadSnapshot(): PredictionLiveUpdateSnapshot? = preferences.getString(C.PREDICTION_LIVE_UPDATE_SNAPSHOT, null)
        ?.let { runCatching { json.decodeFromString<PredictionLiveUpdateSnapshot>(it) }.getOrNull() }

    private val predictionChannelId get() = context.getString(R.string.notification_prediction_tracking_channel_id)
    private val resultChannelId get() = context.getString(R.string.notification_prediction_results_channel_id)

    private companion object {
        const val NOTIFICATION_ID = 6101
        const val RESULT_NOTIFICATION_ID = 6102
        const val CLEANUP_GRACE_MS = 5 * 60_000L
    }
}

private fun Prediction.toSnapshot(
    id: String,
    channelId: String,
    channelLogin: String,
    channelName: String,
    betState: PredictionBetState,
    streamId: String?,
) = PredictionLiveUpdateSnapshot(
    predictionId = id,
    title = title.orEmpty(),
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    status = status.orEmpty(),
    outcomes = outcomes.orEmpty().mapNotNull { outcome ->
        val outcomeId = outcome.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        PredictionOutcomeSnapshot(
            id = outcomeId,
            title = outcome.title.orEmpty(),
            totalPoints = outcome.totalPoints ?: 0,
            totalUsers = outcome.totalUsers ?: 0,
        )
    },
    winningOutcomeId = winningOutcomeId,
    myOutcomeId = betState.outcomeId,
    myAmount = betState.amount,
    locksAtMs = locksAt,
    updatedAtMs = System.currentTimeMillis(),
    streamId = streamId,
)
