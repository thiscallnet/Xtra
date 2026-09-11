package com.github.andreyasadchy.xtra.util.chat

import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.util.WebSocket
import com.github.andreyasadchy.xtra.util.watch.WatchCreditTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.Timer
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MINUTE_WATCHED_INTERVAL_MILLIS = 59_000L

internal fun shouldStartMinuteWatchedTimer(
    listenForDrops: Boolean,
    userIdPresent: Boolean,
    gqlTokenPresent: Boolean,
    authenticationAccepted: Boolean,
): Boolean = listenForDrops && userIdPresent && gqlTokenPresent && authenticationAccepted

class HermesWebSocket(
    private val channelId: String,
    private val userId: String?,
    private val gqlClientId: String?,
    private val gqlToken: String?,
    private val collectPoints: Boolean,
    private val listenForPoints: Boolean,
    private val showRaids: Boolean,
    private val showPolls: Boolean,
    private val showPredictions: Boolean,
    private val includeChannelTopics: Boolean = true,
    private val trustManager: Lazy<X509TrustManager>,
    private val listener: Listener,
    private val listenForDrops: Boolean = false,
) {
    private var webSocket: WebSocket? = null
    private var pongTimer: Timer? = null
    private var timeout = 15000L
    private var minuteWatchedTimer: Timer? = null
    private var topics = emptyMap<String, String>()
    private val subscriptionResponseTopics = mutableMapOf<String, String>()
    private val acknowledgedPrivateTopics = mutableSetOf<String>()
    private val handledMessageIds = mutableListOf<String>()
    private var hasSubscribed = false
    private var authenticationAccepted = false
    private var privateSubscriptionsSent = false
    private var subscriptionsSentNotified = false
    private var subscriptionsReconnected = false

    fun connect(coroutineScope: CoroutineScope): Job {
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes connect requested channelIdPresent=${channelId.isNotBlank()} userIdPresent=${!userId.isNullOrBlank()} collectPoints=$collectPoints listenForPoints=$listenForPoints listenForDrops=$listenForDrops")
        hasSubscribed = false
        authenticationAccepted = false
        subscriptionResponseTopics.clear()
        acknowledgedPrivateTopics.clear()
        privateSubscriptionsSent = false
        subscriptionsSentNotified = false
        webSocket = WebSocket("wss://hermes.twitch.tv/v1?clientId=${gqlClientId}", trustManager, WebSocketListener())
        webSocket?.coroutineScope = coroutineScope
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes disconnect requested")
        pongTimer?.cancel()
        minuteWatchedTimer?.cancel()
        minuteWatchedTimer = null
        job?.cancel()
        webSocket?.disconnect()
    }

    private suspend fun subscribe() = withContext(Dispatchers.IO) {
        authenticationAccepted = false
        subscriptionResponseTopics.clear()
        acknowledgedPrivateTopics.clear()
        privateSubscriptionsSent = false
        subscriptionsSentNotified = false
        subscriptionsReconnected = hasSubscribed
        if (listenForDrops) {
            minuteWatchedTimer?.cancel()
            minuteWatchedTimer = null
        }
        if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && (listenForPoints || listenForDrops)) {
            val authenticate = JSONObject().apply {
                put("id", Uuid.random().toHexString().substring(0, 21))
                put("type", "authenticate")
                put("authenticate", JSONObject().apply {
                    put("token", gqlToken)
                })
                put("timestamp", Clock.System.now().toString())
            }.toString()
            webSocket?.write(authenticate)
            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes authentication request sent")
        }
        topics = buildMap {
            hermesChannelTopics(channelId, includeChannelTopics).forEach { topic ->
                put(Uuid.random().toHexString().substring(0, 21), topic)
            }
            if (showRaids) {
                put(Uuid.random().toHexString().substring(0, 21), "raid.$channelId")
            }
            if (showPolls) {
                put(Uuid.random().toHexString().substring(0, 21), "polls.$channelId")
            }
            if (showPredictions) {
                put(Uuid.random().toHexString().substring(0, 21), "predictions-channel-v1.$channelId")
            }
            if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank()) {
                if (listenForPoints) {
                    put(Uuid.random().toHexString().substring(0, 21), "community-points-user-v1.$userId")
                }
                if (listenForDrops) {
                    put(Uuid.random().toHexString().substring(0, 21), "user-drop-events.$userId")
                }
            }
        }
        val needsAuthentication = !userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && (listenForPoints || listenForDrops)
        sendTopicSubscriptions(
            if (needsAuthentication) {
                topics.filterValues { !isPrivateTopic(it) }
            } else {
                topics
            },
        )
        if (!needsAuthentication) {
            notifySubscriptionsSent()
        }
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes subscriptions sent count=${if (needsAuthentication) topics.count { !isPrivateTopic(it.value) } else topics.size}")
        hasSubscribed = true
    }

    private fun isPrivateTopic(topic: String): Boolean =
        topic.startsWith("community-points-user") || topic.startsWith("user-drop-events")

    private suspend fun sendTopicSubscriptions(topicEntries: Map<String, String>) {
        topicEntries.forEach {
            val requestId = Uuid.random().toHexString().substring(0, 21)
            subscriptionResponseTopics[requestId] = it.value
            subscriptionResponseTopics[it.key] = it.value
            val subscribe = JSONObject().apply {
                put("type", "subscribe")
                put("id", requestId)
                put("subscribe", JSONObject().apply {
                    put("id", it.key)
                    put("type", "pubsub")
                    put("pubsub", JSONObject().apply {
                        put("topic", it.value)
                    })
                })
                put("timestamp", Clock.System.now().toString())
            }.toString()
            webSocket?.write(subscribe)
            if (it.value.startsWith("community-points-user")) {
                Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes community-points-user subscription sent")
            }
            if (it.value.startsWith("user-drop-events")) {
                Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes user-drop-events subscription sent")
            }
        }
    }

    private suspend fun sendPrivateSubscriptions() {
        if (privateSubscriptionsSent) return
        privateSubscriptionsSent = true
        sendTopicSubscriptions(topics.filterValues(::isPrivateTopic))
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes private subscriptions sent count=${topics.count { isPrivateTopic(it.value) }}")
        notifySubscriptionsSent()
    }

    private suspend fun notifySubscriptionsSent() {
        if (subscriptionsSentNotified) return
        subscriptionsSentNotified = true
        listener.onSubscriptionsSent(subscriptionsReconnected)
    }

    private suspend fun maybeStartMinuteWatchedTimer() {
        if (!shouldStartMinuteWatchedTimer(
                listenForDrops = listenForDrops,
                userIdPresent = !userId.isNullOrBlank(),
                gqlTokenPresent = !gqlToken.isNullOrBlank(),
                authenticationAccepted = authenticationAccepted,
            )
        ) {
            if (!listenForDrops || userId.isNullOrBlank() || gqlToken.isNullOrBlank()) {
                Log.w(WatchCreditTelemetry.LOG_TAG, "Hermes minute-watched timer not started: missing userId or GQL token")
            } else {
                Log.w(
                    WatchCreditTelemetry.LOG_TAG,
                    "Hermes minute-watched timer waiting for authentication acknowledgement",
                )
            }
            return
        }
        if (minuteWatchedTimer == null) {
            Log.d(
                WatchCreditTelemetry.LOG_TAG,
                "Hermes minute-watched timer starting privateSubscriptionAcks=${acknowledgedPrivateTopics.size}",
            )
            startMinuteWatchedTimer()
        }
    }

    private suspend fun handleAuthenticationResponse(json: JSONObject) {
        val response = json.optJSONObject("authenticateResponse")
        val result = response?.optString("result").orEmpty()
        authenticationAccepted = result.equals("ok", ignoreCase = true)
        if (authenticationAccepted) {
            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes authentication accepted")
            sendPrivateSubscriptions()
        } else {
            Log.w(
                WatchCreditTelemetry.LOG_TAG,
                "Hermes authentication rejected result=${result.ifBlank { "unknown" }} error=${response?.optString("error").orEmpty().ifBlank { "none" }} errorCode=${response?.optString("errorCode").orEmpty().ifBlank { "none" }}",
            )
            notifySubscriptionsSent()
        }
        maybeStartMinuteWatchedTimer()
    }

    private suspend fun handleSubscriptionResponse(json: JSONObject) {
        val response = json.optJSONObject("subscribeResponse")
        val result = response?.optString("result").orEmpty()
        val subscriptionId = response?.optJSONObject("subscription")?.optString("id")
            ?.takeIf { it.isNotBlank() }
            ?: response?.optString("id")?.takeIf { it.isNotBlank() }
            ?: json.optString("parentId").takeIf { it.isNotBlank() }
            ?: json.optString("id").takeIf { it.isNotBlank() }
        val topic = subscriptionId?.let(subscriptionResponseTopics::get)
        if (result.equals("ok", ignoreCase = true)) {
            topic?.takeIf {
                it.startsWith("community-points-user") || it.startsWith("user-drop-events")
            }?.let(acknowledgedPrivateTopics::add)
            Log.d(
                WatchCreditTelemetry.LOG_TAG,
                "Hermes subscription accepted topic=${topic ?: "unknown"}",
            )
        } else {
            Log.w(
                WatchCreditTelemetry.LOG_TAG,
                "Hermes subscription rejected topic=${topic ?: "unknown"} result=${result.ifBlank { "unknown" }} error=${response?.optString("error").orEmpty().ifBlank { "none" }} errorCode=${response?.optString("errorCode").orEmpty().ifBlank { "none" }}",
            )
        }
        maybeStartMinuteWatchedTimer()
    }

    private suspend fun startPongTimer() = withContext(Dispatchers.IO) {
        pongTimer = Timer().apply {
            schedule(timeout) {
                webSocket?.coroutineScope?.launch {
                    webSocket?.reconnect()
                }
            }
        }
    }

    private suspend fun startMinuteWatchedTimer() = withContext(Dispatchers.IO) {
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes minute-watched timer started intervalMs=$MINUTE_WATCHED_INTERVAL_MILLIS")
        minuteWatchedTimer = Timer().apply {
            scheduleAtFixedRate(
                MINUTE_WATCHED_INTERVAL_MILLIS,
                MINUTE_WATCHED_INTERVAL_MILLIS,
            ) {
                val scope = webSocket?.coroutineScope
                Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes minute-watched timer fired scopePresent=${scope != null}")
                scope?.launch {
                    listener.onMinuteWatched()
                }
            }
        }
    }

    interface Listener {
        suspend fun onConnect() {}
        /** Called after every topic (re-)subscription message has been sent. */
        suspend fun onSubscriptionsSent(reconnected: Boolean) {}
        suspend fun onPlaybackMessage(message: JSONObject) {}
        suspend fun onStreamInfo(message: JSONObject) {}
        suspend fun onRewardMessage(message: JSONObject) {}
        suspend fun onPointsEarned(message: JSONObject) {}
        suspend fun onPointsSpent(message: JSONObject) {}
        suspend fun onClaimAvailable(message: JSONObject? = null) {}
        suspend fun onDropMessage(message: JSONObject) {}
        suspend fun onMinuteWatched() {}
        suspend fun onRaidUpdate(message: JSONObject, openStream: Boolean) {}
        suspend fun onPollUpdate(message: JSONObject) {}
        suspend fun onPredictionUpdate(message: JSONObject) {}
        suspend fun onDisconnect(message: String, fullMsg: String?) {}
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes connected")
            listener.onConnect()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            try {
                val json = if (message.isNotBlank()) JSONObject(message) else null
                val messageId = if (json?.isNull("id") == false) json.optString("id").takeIf { it.isNotBlank() } else null
                if (!messageId.isNullOrBlank()) {
                    if (handledMessageIds.contains(messageId)) {
                        return
                    } else {
                        handledMessageIds.add(messageId)
                        if (handledMessageIds.size > 200) {
                            handledMessageIds.removeAt(0)
                        }
                    }
                }
                when (json?.optString("type")) {
                    "notification" -> {
                        pongTimer?.cancel()
                        startPongTimer()
                        val notification = json.optJSONObject("notification")
                        val subscription = notification?.optJSONObject("subscription")
                        val subscriptionId = subscription?.optString("id")
                        val topic = topics[subscriptionId]
                        val message = notification?.optString("pubsub")?.let { if (it.isNotBlank()) JSONObject(it) else null }
                        val messageType = message?.optString("type")?.lowercase(Locale.US)
                        if (topic != null && messageType != null) {
                            when {
                                topic.startsWith("video-playback-by-id") -> listener.onPlaybackMessage(message)
                                topic.startsWith("broadcast-settings-update") -> {
                                    when {
                                        messageType.startsWith("broadcast_settings_update") -> listener.onStreamInfo(message)
                                    }
                                }
                                topic.startsWith("community-points-channel") -> {
                                    when {
                                        messageType.startsWith("reward-redeemed") -> listener.onRewardMessage(message)
                                    }
                                }
                                topic.startsWith("community-points-user") -> {
                                    when {
                                        messageType.startsWith("points-earned") -> {
                                            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes points-earned received")
                                            listener.onPointsEarned(message)
                                        }
                                        messageType.startsWith("points-spent") -> {
                                            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes points-spent received")
                                            listener.onPointsSpent(message)
                                        }
                                        messageType.startsWith("claim-available") -> {
                                            Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes claim-available received")
                                            listener.onClaimAvailable(message)
                                        }
                                        else -> {
                                            if (BuildConfig.DEBUG) {
                                                Log.w(WatchCreditTelemetry.LOG_TAG, "Hermes unknown community-points-user event type=$messageType")
                                            }
                                        }
                                    }
                                }
                                topic.startsWith("user-drop-events") -> listener.onDropMessage(message)
                                topic.startsWith("raid") -> {
                                    when {
                                        messageType.startsWith("raid_update") -> listener.onRaidUpdate(message, false)
                                        messageType.startsWith("raid_go") -> listener.onRaidUpdate(message, true)
                                    }
                                }
                                topic.startsWith("polls") -> listener.onPollUpdate(message)
                                topic.startsWith("predictions-channel") -> listener.onPredictionUpdate(message)
                            }
                        }
                    }
                    "keepalive" -> {
                        pongTimer?.cancel()
                        startPongTimer()
                    }
                    "authenticated" -> {
                        authenticationAccepted = true
                        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes authentication accepted")
                        sendPrivateSubscriptions()
                        maybeStartMinuteWatchedTimer()
                    }
                    "reconnect" -> {
                        //val reconnect = json.optJSONObject("reconnect")
                        //val reconnectUrl = if (reconnect?.isNull("url") == false) reconnect.optString("url").takeIf { it.isNotBlank() } else null
                        pongTimer?.cancel()
                        webSocket.reconnect()
                    }
                    "welcome" -> {
                        val welcome = json.optJSONObject("welcome")
                        if (welcome?.isNull("keepaliveSec") == false) {
                            welcome.optInt("keepaliveSec").takeIf { it > 0 }?.let {
                                timeout = it * 1000L
                            }
                        }
                        pongTimer?.cancel()
                        startPongTimer()
                        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes welcome received collectPoints=$collectPoints listenForDrops=$listenForDrops userIdPresent=${!userId.isNullOrBlank()} gqlTokenPresent=${!gqlToken.isNullOrBlank()}")
                        subscribe()
                    }
                    "authenticateResponse" -> handleAuthenticationResponse(json)
                    "subscribeResponse" -> handleSubscriptionResponse(json)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(WatchCreditTelemetry.LOG_TAG, "Hermes message handling failed", e)
            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            Log.w(WatchCreditTelemetry.LOG_TAG, "Hermes disconnected message=$message")
            listener.onDisconnect(message, fullMsg)
        }
    }
}

internal fun hermesChannelTopics(channelId: String, includeChannelTopics: Boolean): List<String> {
    if (!includeChannelTopics) return emptyList()
    return listOf(
        "video-playback-by-id.$channelId",
        "broadcast-settings-update.$channelId",
        "community-points-channel-v1.$channelId",
    )
}
