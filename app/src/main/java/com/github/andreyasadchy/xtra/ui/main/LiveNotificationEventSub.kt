package com.github.andreyasadchy.xtra.ui.main

import android.util.Log
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class LiveEventSubMessage(
    val messageType: String,
    val messageId: String?,
    val sessionId: String? = null,
    val keepAliveTimeoutSeconds: Int? = null,
    val reconnectUrl: String? = null,
    val subscriptionType: String? = null,
)

internal object LiveNotificationEventSubProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LiveEventSubMessage? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val metadata = root["metadata"]?.jsonObject ?: return null
        val messageType = metadata.stringValue("message_type") ?: return null
        val messageId = metadata.stringValue("message_id")
        val session = root["payload"]?.jsonObject?.get("session")?.jsonObject
        return LiveEventSubMessage(
            messageType = messageType,
            messageId = messageId,
            sessionId = session.stringValue("id"),
            keepAliveTimeoutSeconds = session.intValue("keepalive_timeout_seconds"),
            reconnectUrl = session.stringValue("reconnect_url"),
            subscriptionType = metadata.stringValue("subscription_type"),
        )
    }

    private fun JsonObject?.stringValue(key: String): String? =
        this?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * EventSub stream.online fast lane for the first ten notification channels.
 *
 * Helix polling remains authoritative because EventSub does not replay events
 * lost during a disconnected period and because the subscription cost limit is
 * independent of the number of WebSocket connections.
 */
class LiveNotificationEventSub(
    private val okHttpClient: Lazy<OkHttpClient>,
    private val helixRepository: HelixRepository,
    private val networkLibrary: () -> String?,
    private val helixHeaders: () -> Map<String, String>,
    private val channelIds: suspend () -> List<String>,
    private val scope: CoroutineScope,
    private val onStreamOnline: () -> Unit,
) {

    private val stateMutex = Mutex()
    private val messageMutex = Mutex()
    private val handledMessageIds = LinkedHashSet<String>()
    private var activeConnection: Connection? = null
    private var pendingReconnect: Connection? = null
    private var reconnectJob: Job? = null
    private var desiredChannelIds = emptySet<String>()
    private var started = false

    suspend fun start() {
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
            reconnectJob = null
            val sockets = listOfNotNull(activeConnection, pendingReconnect)
                .mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
            sockets
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "service stopped") }
    }

    /** Synchronous best-effort close used from Service.onDestroy(). */
    fun shutdown() {
        val sockets = runBlocking {
            stateMutex.withLock {
                started = false
                reconnectJob?.cancel()
                reconnectJob = null
                val sockets = listOfNotNull(activeConnection, pendingReconnect)
                    .mapNotNull { it.socket }
                activeConnection = null
                pendingReconnect = null
                sockets
            }
        }
        sockets.forEach { it.close(NORMAL_CLOSE_CODE, "service destroyed") }
    }

    /** Rebuilds the socket only when the EventSub subset actually changed. */
    suspend fun refreshIfNeeded() {
        val ids = eventSubChannelIds()
        val nextIds = ids.toSet()
        var socketsToClose = emptyList<WebSocket>()
        var changed = false
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
            changed = true
            desiredChannelIds = nextIds
            reconnectJob?.cancel()
            reconnectJob = null
            socketsToClose = listOfNotNull(activeConnection, pendingReconnect)
                .mapNotNull { it.socket }
            activeConnection = null
            pendingReconnect = null
        }
        socketsToClose.forEach { it.close(NORMAL_CLOSE_CODE, "subscriptions changed") }
        if (changed) {
            stateMutex.withLock {
                if (started && desiredChannelIds == nextIds && activeConnection == null && pendingReconnect == null) {
                    openNormalConnectionLocked()
                }
            }
        }
    }

    private suspend fun eventSubChannelIds(): List<String> {
        val headers = helixHeaders()
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
            return emptyList()
        }
        return channelIds().filter { it.isNotBlank() }.distinct().take(MAX_SUBSCRIPTIONS)
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
    }

    private fun openSocket(connection: Connection): WebSocket = okHttpClient.value.newWebSocket(
        Request.Builder().url(connection.url).build(),
        connection,
    )

    private suspend fun handleMessage(connection: Connection, text: String) {
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
            "session_keepalive" -> {
                // lastMessageAt is enough; the watchdog is armed at welcome.
            }
            "session_reconnect" -> {
                if (message.reconnectUrl != null) {
                    startReconnectHandoff(connection, message.reconnectUrl)
                }
            }
            "notification" -> {
                if (message.subscriptionType == "stream.online") {
                    onStreamOnline()
                }
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
        connection.welcomed = true
        connection.keepAliveTimeoutMs = keepAliveTimeoutSeconds * 1000L
        armKeepAliveWatchdog(connection)

        if (connection.transferredSubscriptions) {
            val oldConnection = stateMutex.withLock {
                if (pendingReconnect !== connection || !started) {
                    return@withLock null
                }
                pendingReconnect = null
                val old = activeConnection
                activeConnection = connection
                old
            }
            oldConnection?.socket?.close(NORMAL_CLOSE_CODE, "reconnected")
            return
        }

        val shouldSubscribe = stateMutex.withLock {
            started && activeConnection === connection
        }
        if (shouldSubscribe) {
            subscribe(sessionId)
        }
    }

    private suspend fun subscribe(sessionId: String) {
        val ids = stateMutex.withLock { desiredChannelIds.toList() }
        ids.forEach { channelId ->
            try {
                val error = helixRepository.createEventSubSubscription(
                    networkLibrary = networkLibrary(),
                    headers = helixHeaders(),
                    userId = null,
                    channelId = channelId,
                    type = "stream.online",
                    sessionId = sessionId,
                )
                if (error != null) {
                    Log.w(TAG, "EventSub subscription rejected for $channelId: ${error.take(240)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "EventSub subscription failed for $channelId", e)
            }
        }
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
        connection.keepAliveJob?.cancel()
        var scheduleReconnect = false
        stateMutex.withLock {
            when {
                activeConnection === connection -> {
                    activeConnection = null
                    scheduleReconnect = started
                }
                pendingReconnect === connection -> {
                    pendingReconnect = null
                    scheduleReconnect = started && activeConnection == null
                }
            }
            if (scheduleReconnect) {
                scheduleNormalReconnectLocked(reason)
            }
        }
    }

    private fun scheduleNormalReconnectLocked(reason: String) {
        if (reconnectJob?.isActive == true || !started || desiredChannelIds.isEmpty()) {
            return
        }
        Log.w(TAG, "EventSub disconnected ($reason); reconnecting")
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            stateMutex.withLock {
                reconnectJob = null
                if (started && activeConnection == null && desiredChannelIds.isNotEmpty()) {
                    openNormalConnectionLocked()
                }
            }
        }
    }

    private inner class Connection(
        val url: String,
        val transferredSubscriptions: Boolean,
    ) : WebSocketListener() {
        var socket: WebSocket? = null
        var welcomed = false
        var lastMessageAt = System.currentTimeMillis()
        var keepAliveTimeoutMs = DEFAULT_KEEPALIVE_TIMEOUT_SECONDS * 1000L
        var keepAliveJob: Job? = null

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                try {
                    handleMessage(this@Connection, text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to process EventSub message", e)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { handleClosed(this@Connection, t.message ?: "failure") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { handleClosed(this@Connection, "$code $reason") }
        }
    }

    companion object {
        private const val TAG = "LiveNotificationEventSub"
        private const val EVENTSUB_URL = "wss://eventsub.wss.twitch.tv/ws"
        private const val MAX_SUBSCRIPTIONS = 10
        private const val MAX_HANDLED_MESSAGE_IDS = 200
        private const val DEFAULT_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MIN_KEEPALIVE_TIMEOUT_SECONDS = 10
        private const val MAX_KEEPALIVE_TIMEOUT_SECONDS = 600
        private const val KEEPALIVE_SAFETY_MARGIN_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
