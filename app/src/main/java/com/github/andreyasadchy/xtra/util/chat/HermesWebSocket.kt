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
import java.util.Timer
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
) {
    private var webSocket: WebSocket? = null
    private var pongTimer: Timer? = null
    private var timeout = 15000L
    private var topics = emptyMap<String, String>()
    private val handledMessageIds = mutableListOf<String>()
    private var hasSubscribed = false

    fun connect(coroutineScope: CoroutineScope): Job {
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes connect requested channelIdPresent=${channelId.isNotBlank()} userIdPresent=${!userId.isNullOrBlank()} collectPoints=$collectPoints listenForPoints=$listenForPoints")
        hasSubscribed = false
        webSocket = WebSocket("wss://hermes.twitch.tv/v1?clientId=${gqlClientId}", trustManager, WebSocketListener())
        webSocket?.coroutineScope = coroutineScope
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes disconnect requested")
        pongTimer?.cancel()
        job?.cancel()
        webSocket?.disconnect()
    }

    private suspend fun subscribe() = withContext(Dispatchers.IO) {
        if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && listenForPoints) {
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
            }
        }
        topics.forEach {
            val subscribe = JSONObject().apply {
                put("type", "subscribe")
                put("id", Uuid.random().toHexString().substring(0, 21))
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
        }
        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes subscriptions sent count=${topics.size}")
        val reconnected = hasSubscribed
        hasSubscribed = true
        listener.onSubscriptionsSent(reconnected)
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

    interface Listener {
        suspend fun onConnect() {}
        /** Called after every topic (re-)subscription message has been sent. */
        suspend fun onSubscriptionsSent(reconnected: Boolean) {}
        suspend fun onPlaybackMessage(message: JSONObject) {}
        suspend fun onStreamInfo(message: JSONObject) {}
        suspend fun onRewardMessage(message: JSONObject) {}
        suspend fun onPointsEarned(message: JSONObject) {}
        suspend fun onPointsSpent(message: JSONObject) {}
        suspend fun onClaimAvailable() {}
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
                        val messageType = message?.optString("type")
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
                                            listener.onClaimAvailable()
                                        }
                                        else -> {
                                            if (BuildConfig.DEBUG) {
                                                Log.w(WatchCreditTelemetry.LOG_TAG, "Hermes unknown community-points-user event type=$messageType")
                                            }
                                        }
                                    }
                                }
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
                        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes authentication accepted")
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
                        Log.d(WatchCreditTelemetry.LOG_TAG, "Hermes welcome received collectPoints=$collectPoints userIdPresent=${!userId.isNullOrBlank()} gqlTokenPresent=${!gqlToken.isNullOrBlank()}")
                        subscribe()
                    }
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
