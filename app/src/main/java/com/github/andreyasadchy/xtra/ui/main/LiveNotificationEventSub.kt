package com.github.andreyasadchy.xtra.ui.main

import android.util.Log
import com.github.andreyasadchy.xtra.repository.EventSubSubscriptionResult
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

data class LiveStreamOnlineEvent(
    val eventId: String,
    val broadcasterUserId: String,
    val broadcasterUserLogin: String?,
    val broadcasterUserName: String?,
    val startedAt: String,
)

internal data class LiveEventSubMessage(
    val messageType: String,
    val messageId: String?,
    val sessionId: String? = null,
    val keepAliveTimeoutSeconds: Int? = null,
    val reconnectUrl: String? = null,
    val subscriptionType: String? = null,
    val streamOnlineEvent: LiveStreamOnlineEvent? = null,
)

internal object LiveNotificationEventSubProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LiveEventSubMessage? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val metadata = root["metadata"]?.jsonObject ?: return null
        val messageType = metadata.stringValue("message_type") ?: return null
        val messageId = metadata.stringValue("message_id")
        val payload = root["payload"]?.jsonObject
        val session = payload?.get("session")?.jsonObject
        val subscription = payload?.get("subscription")?.jsonObject
        val event = payload?.get("event")?.jsonObject
        val subscriptionType = metadata.stringValue("subscription_type")
            ?: subscription.stringValue("type")
        val streamOnlineEvent = if (messageType == "notification" && subscriptionType == "stream.online") {
            event?.let {
                LiveStreamOnlineEvent(
                    eventId = it.stringValue("id") ?: return@let null,
                    broadcasterUserId = it.stringValue("broadcaster_user_id") ?: return@let null,
                    broadcasterUserLogin = it.stringValue("broadcaster_user_login"),
                    broadcasterUserName = it.stringValue("broadcaster_user_name"),
                    startedAt = it.stringValue("started_at") ?: return@let null,
                )
            }
        } else null
        return LiveEventSubMessage(
            messageType = messageType,
            messageId = messageId,
            sessionId = session.stringValue("id"),
            keepAliveTimeoutSeconds = session.intValue("keepalive_timeout_seconds"),
            reconnectUrl = session.stringValue("reconnect_url"),
            subscriptionType = subscriptionType,
            streamOnlineEvent = streamOnlineEvent,
        )
    }

    private fun JsonObject?.stringValue(key: String): String? =
        this?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Opportunistic EventSub stream.online fast lane.
 *
 * Helix polling remains authoritative because EventSub does not replay events
 * lost during a disconnected period and because the subscription cost limit
 * is independent of the number of WebSocket connections.
 */
class LiveNotificationEventSub(
    private val okHttpClient: Lazy<OkHttpClient>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: () -> String?,
    private val helixHeaders: () -> Map<String, String>,
    private val channelIds: suspend () -> List<String>,
    private val scope: CoroutineScope,
    private val onStreamOnline: suspend (LiveStreamOnlineEvent) -> Unit,
) {

    private val stateMutex = Mutex()
    private val messageMutex = Mutex()
    private val handledMessageIds = LinkedHashSet<String>()
    private val socketEvents = Channel<SocketEvent>(Channel.UNLIMITED)
    private var processorJob: Job? = null
    private var activeConnection: Connection? = null
    private var pendingReconnect: Connection? = null
    private var reconnectJob: Job? = null
    private var handoffRetryJob: Job? = null
    private var desiredChannelIds = emptySet<String>()
    private var started = false
    private var reconnectAttempt = 0

    suspend fun start() {
        ensureProcessor()
        val ids = eventSubChannelIds()
        stateMutex.withLock {
            started = true
            desiredChannelIds = ids.toSet()
            if (activeConnection == null && pendingReconnect == null && ids.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        }
    }

    suspend fun stop() {
        val sockets = stateMutex.withLock {
            started = false
            reconnectJob?.cancel()
            handoffRetryJob?.cancel()
            reconnectJob = null
            handoffRetryJob = null
            val connections = listOfNotNull(activeConnection, pendingReconnect)
            connections.forEach(::cancelConnectionJobs)
            val sockets = connections.mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
            sockets
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "monitoring stopped") }
    }

    /** Synchronous best-effort close used when the process-scoped engine stops. */
    fun shutdown() {
        val sockets = runBlocking {
            stateMutex.withLock {
                started = false
                reconnectJob?.cancel()
                handoffRetryJob?.cancel()
                reconnectJob = null
                handoffRetryJob = null
                val connections = listOfNotNull(activeConnection, pendingReconnect)
                connections.forEach(::cancelConnectionJobs)
                val sockets = connections.mapNotNull { it.socket }
                activeConnection = null
                pendingReconnect = null
                sockets
            }
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "process stopped") }
        processorJob?.cancel()
        socketEvents.close()
    }

    /** Rebuilds the socket only when the EventSub subset actually changed. */
    suspend fun refreshIfNeeded() {
        val ids = eventSubChannelIds()
        val nextIds = ids.toSet()
        var socketsToClose = emptyList<WebSocket>()
        stateMutex.withLock {
            if (!started) {
                return@withLock
            }
            if (nextIds == desiredChannelIds) {
                if (activeConnection == null && pendingReconnect == null && nextIds.isNotEmpty()) {
                    openNormalConnectionLocked()
                }
                return@withLock
            }
            desiredChannelIds = nextIds
            reconnectJob?.cancel()
            handoffRetryJob?.cancel()
            reconnectJob = null
            handoffRetryJob = null
            val connections = listOfNotNull(activeConnection, pendingReconnect)
            connections.forEach(::cancelConnectionJobs)
            socketsToClose = connections.mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
            if (nextIds.isNotEmpty()) {
                openNormalConnectionLocked()
            }
        }
        socketsToClose.forEach { it.close(NORMAL_CLOSE_CODE, "subscriptions changed") }
    }

    private suspend fun ensureProcessor() {
        stateMutex.withLock {
            if (processorJob?.isActive != true) {
                processorJob = scope.launch {
                    for (event in socketEvents) {
                        try {
                            when (event) {
                                is SocketEvent.Message -> handleMessage(event.connection, event.text)
                                is SocketEvent.Failure -> handleClosed(event.connection, event.reason)
                                is SocketEvent.Closed -> handleClosed(event.connection, event.reason)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to process EventSub socket event", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun eventSubChannelIds(): List<String> {
        val headers = helixHeaders()
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
            return emptyList()
        }
        // Keep the complete deterministic list here. The subscription response
        // reports the server's actual max_total_cost, so subscribe() can stop
        // at the live capacity instead of baking that limit into selection.
        return channelIds().filter { it.isNotBlank() }.distinct()
    }

    private fun openNormalConnectionLocked() {
        if (!started || desiredChannelIds.isEmpty()) {
            return
        }
        val connection = Connection(
            url = EVENTSUB_URL,
            transferredSubscriptions = false,
        )
        activeConnection = connection
        connection.socket = openSocket(connection)
        armWelcomeTimeout(connection)
    }

    private fun openSocket(connection: Connection): WebSocket = okHttpClient.value.newWebSocket(
        Request.Builder().url(connection.url).build(),
        connection,
    )

    private suspend fun handleMessage(connection: Connection, text: String) {
        val isCurrent = stateMutex.withLock {
            started && (activeConnection === connection || pendingReconnect === connection)
        }
        if (!isCurrent) {
            return
        }
        val message = LiveNotificationEventSubProtocol.parse(text) ?: return
        if (!message.messageId.isNullOrBlank() && isDuplicateMessage(message.messageId)) {
            return
        }
        connection.lastMessageAt = System.currentTimeMillis()
        if (connection.welcomed) {
            armKeepAliveWatchdog(connection)
        }
        when (message.messageType) {
            "session_welcome" -> handleWelcome(connection, message)
            "session_keepalive" -> Unit
            "session_reconnect" -> message.reconnectUrl?.let { startReconnectHandoff(connection, it) }
            "notification" -> if (message.subscriptionType == "stream.online") {
                message.streamOnlineEvent?.let { onStreamOnline(it) }
            }
        }
    }

    private suspend fun isDuplicateMessage(messageId: String): Boolean = messageMutex.withLock {
        if (!handledMessageIds.add(messageId)) {
            true
        } else {
            if (handledMessageIds.size > MAX_HANDLED_MESSAGE_IDS) {
                handledMessageIds.remove(handledMessageIds.first())
            }
            false
        }
    }

    private suspend fun handleWelcome(connection: Connection, message: LiveEventSubMessage) {
        val sessionId = message.sessionId ?: return
        val keepAliveTimeoutSeconds = (message.keepAliveTimeoutSeconds ?: DEFAULT_KEEPALIVE_TIMEOUT_SECONDS)
            .coerceIn(MIN_KEEPALIVE_TIMEOUT_SECONDS, MAX_KEEPALIVE_TIMEOUT_SECONDS)
        connection.welcomeJob?.cancel()
        connection.welcomed = true
        connection.keepAliveTimeoutMs = keepAliveTimeoutSeconds * 1000L
        reconnectAttempt = 0
        armKeepAliveWatchdog(connection)

        if (connection.transferredSubscriptions) {
            val oldConnection = stateMutex.withLock {
                if (pendingReconnect !== connection || !started) {
                    return@withLock null
                }
                pendingReconnect = null
                handoffRetryJob?.cancel()
                handoffRetryJob = null
                val old = activeConnection
                activeConnection = connection
                old
            }
            oldConnection?.let {
                cancelConnectionJobs(it)
                it.socket?.close(NORMAL_CLOSE_CODE, "reconnected")
            }
            return
        }

        val shouldSubscribe = stateMutex.withLock {
            started && activeConnection === connection
        }
        if (shouldSubscribe) {
            connection.subscriptionJob?.cancel()
            connection.subscriptionJob = scope.launch { subscribe(connection, sessionId) }
        }
    }

    private suspend fun subscribe(connection: Connection, sessionId: String) {
        val ids = stateMutex.withLock { desiredChannelIds.toList() }
        var maxTotalCost = DEFAULT_MAX_TOTAL_COST
        ids.forEach { channelId ->
            if (!isCurrent(connection) || !scope.isActive) {
                return
            }
            try {
                val result = helixRepository.createEventSubSubscriptionResult(
                    networkLibrary = networkLibrary(),
                    headers = helixHeaders(),
                    userId = null,
                    channelId = channelId,
                    type = "stream.online",
                    sessionId = sessionId,
                )
                if (!isCurrent(connection)) {
                    return
                }
                if (result.success) {
                    maxTotalCost = result.maxTotalCost ?: maxTotalCost
                    if ((result.totalCost ?: 0) >= maxTotalCost) {
                        return
                    }
                } else {
                    Log.w(TAG, "EventSub subscription rejected for $channelId: ${result.errorMessage.orEmpty().take(240)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "EventSub subscription failed for $channelId", e)
            }
        }
    }

    private suspend fun isCurrent(connection: Connection): Boolean = stateMutex.withLock {
        started && (activeConnection === connection || pendingReconnect === connection)
    }

    private suspend fun startReconnectHandoff(connection: Connection, reconnectUrl: String) {
        stateMutex.withLock {
            if (!started || activeConnection !== connection || pendingReconnect != null) {
                return@withLock
            }
            val replacement = Connection(
                url = reconnectUrl,
                transferredSubscriptions = true,
            )
            pendingReconnect = replacement
            replacement.socket = openSocket(replacement)
            armWelcomeTimeout(replacement)
        }
    }

    private fun armWelcomeTimeout(connection: Connection) {
        connection.welcomeJob?.cancel()
        connection.welcomeJob = scope.launch {
            delay(WELCOME_TIMEOUT_MS)
            val timedOut = stateMutex.withLock {
                started && !connection.welcomed &&
                    (activeConnection === connection || pendingReconnect === connection)
            }
            if (timedOut) {
                connection.socket?.cancel()
            }
        }
    }

    private fun armKeepAliveWatchdog(connection: Connection) {
        connection.keepAliveJob?.cancel()
        connection.keepAliveJob = scope.launch {
            while (isActive && connection.welcomed) {
                delay(connection.keepAliveTimeoutMs + KEEPALIVE_SAFETY_MARGIN_MS)
                if (System.currentTimeMillis() - connection.lastMessageAt >= connection.keepAliveTimeoutMs) {
                    connection.socket?.cancel()
                    break
                }
            }
        }
    }

    private suspend fun handleClosed(connection: Connection, reason: String) {
        cancelConnectionJobs(connection)
        var normalReconnect = false
        var handoffUrl: String? = null
        stateMutex.withLock {
            when {
                activeConnection === connection -> {
                    activeConnection = null
                    normalReconnect = started
                }
                pendingReconnect === connection -> {
                    pendingReconnect = null
                    if (started) {
                        handoffUrl = connection.url
                        normalReconnect = activeConnection == null
                    }
                }
            }
            val active = activeConnection
            val reconnectUrl = handoffUrl
            if (reconnectUrl != null && active != null) {
                scheduleHandoffRetryLocked(active, reconnectUrl, reason)
            } else if (normalReconnect) {
                scheduleNormalReconnectLocked(reason)
            }
        }
    }

    private fun scheduleHandoffRetryLocked(active: Connection, reconnectUrl: String, reason: String) {
        if (handoffRetryJob?.isActive == true || !started || desiredChannelIds.isEmpty()) {
            return
        }
        Log.w(TAG, "EventSub reconnect handoff failed ($reason); retrying the supplied URL")
        handoffRetryJob = scope.launch {
            delay(nextReconnectDelay())
            stateMutex.withLock {
                handoffRetryJob = null
                if (started && activeConnection === active && pendingReconnect == null) {
                    val replacement = Connection(reconnectUrl, transferredSubscriptions = true)
                    pendingReconnect = replacement
                    replacement.socket = openSocket(replacement)
                    armWelcomeTimeout(replacement)
                }
            }
        }
    }

    private fun scheduleNormalReconnectLocked(reason: String) {
        if (reconnectJob?.isActive == true || !started || desiredChannelIds.isEmpty()) {
            return
        }
        Log.w(TAG, "EventSub disconnected ($reason); reconnecting with backoff")
        reconnectJob = scope.launch {
            delay(nextReconnectDelay())
            stateMutex.withLock {
                reconnectJob = null
                if (started && activeConnection == null && pendingReconnect == null && desiredChannelIds.isNotEmpty()) {
                    openNormalConnectionLocked()
                }
            }
        }
    }

    private fun nextReconnectDelay(): Long {
        val exponential = 1_000L * (1L shl min(reconnectAttempt, 6))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        return exponential.coerceAtMost(MAX_RECONNECT_DELAY_MS) + Random.nextLong(0L, 1_001L)
    }

    private fun cancelConnectionJobs(connection: Connection) {
        connection.welcomeJob?.cancel()
        connection.keepAliveJob?.cancel()
        connection.subscriptionJob?.cancel()
        connection.welcomeJob = null
        connection.keepAliveJob = null
        connection.subscriptionJob = null
    }

    private sealed interface SocketEvent {
        data class Message(val connection: Connection, val text: String) : SocketEvent
        data class Failure(val connection: Connection, val reason: String) : SocketEvent
        data class Closed(val connection: Connection, val reason: String) : SocketEvent
    }

    private inner class Connection(
        val url: String,
        val transferredSubscriptions: Boolean,
    ) : WebSocketListener() {
        var socket: WebSocket? = null
        var welcomed = false
        var lastMessageAt = System.currentTimeMillis()
        var keepAliveTimeoutMs = DEFAULT_KEEPALIVE_TIMEOUT_SECONDS * 1_000L
        var welcomeJob: Job? = null
        var keepAliveJob: Job? = null
        var subscriptionJob: Job? = null

        override fun onMessage(webSocket: WebSocket, text: String) {
            socketEvents.trySend(SocketEvent.Message(this, text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socketEvents.trySend(SocketEvent.Failure(this, t.message ?: "failure"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socketEvents.trySend(SocketEvent.Closed(this, "$code $reason"))
        }
    }

    companion object {
        private const val TAG = "LiveNotificationEventSub"
        private const val EVENTSUB_URL = "wss://eventsub.wss.twitch.tv/ws"
        private const val DEFAULT_MAX_TOTAL_COST = 10
        private const val MAX_HANDLED_MESSAGE_IDS = 200
        private const val DEFAULT_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MIN_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MAX_KEEPALIVE_TIMEOUT_SECONDS = 600
        private const val KEEPALIVE_SAFETY_MARGIN_MS = 5_000L
        private const val WELCOME_TIMEOUT_MS = 15_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
