package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Timer
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule

internal class EventSubReconnectState {
    fun shouldCreateSubscriptions(isReplacement: Boolean): Boolean = !isReplacement
}

/**
 * Generic Twitch EventSub WebSocket wrapper.
 *
 * Twitch's session_reconnect is a handoff, not an ordinary disconnect: the
 * old socket stays alive while a replacement connects, and subscriptions are
 * transferred by Twitch. Only a genuinely new session gets the normal
 * onWelcomeMessage callback that creates subscriptions.
 */
class EventSubWebSocket(
    private val trustManager: Lazy<X509TrustManager>,
    private val listener: Listener,
) {
    private val reconnectState = EventSubReconnectState()
    private class Connection(
        val socket: WebSocket,
        val isHandoff: Boolean,
    ) {
        var job: Job? = null
        var pongTimer: Timer? = null
        var welcomed = false
    }

    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var activeConnection: Connection? = null
    private var handoffConnection: Connection? = null
    private var handoffTimeoutJob: Job? = null
    private val connections = mutableListOf<Connection>()
    private var disconnecting = false
    private var timeout = 10000L
    private val handledMessageIds = mutableListOf<String>()

    fun connect(coroutineScope: CoroutineScope): Job {
        synchronized(lock) {
            disconnecting = false
            scope = coroutineScope
            if (activeConnection == null) {
                activeConnection = newConnection("wss://eventsub.wss.twitch.tv/ws", false)
            }
            activeConnection?.let(::startConnectionLocked)
        }
        return activeConnection?.job ?: coroutineScope.launch { }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        val toClose = synchronized(lock) {
            disconnecting = true
            handoffTimeoutJob?.cancel()
            handoffTimeoutJob = null
            val current = connections.toList()
            connections.clear()
            activeConnection = null
            handoffConnection = null
            current
        }
        job?.cancel()
        toClose.forEach { connection ->
            connection.pongTimer?.cancel()
            connection.job?.cancel()
            connection.socket.disconnect()
        }
    }

    private fun newConnection(url: String, isHandoff: Boolean): Connection {
        val connection = Connection(
            socket = WebSocket(url, trustManager, WebSocketListener()),
            isHandoff = isHandoff,
        )
        synchronized(lock) { connections += connection }
        return connection
    }

    private fun startConnectionLocked(connection: Connection) {
        if (connection.job?.isActive == true) return
        val coroutineScope = scope ?: return
        connection.socket.coroutineScope = coroutineScope
        connection.job = coroutineScope.launch(Dispatchers.IO) {
            connection.socket.start()
        }
    }

    private suspend fun startPongTimer(connection: Connection) = withContext(Dispatchers.IO) {
        connection.pongTimer?.cancel()
        connection.pongTimer = Timer().apply {
            schedule(timeout) {
                scope?.launch {
                    val shouldDisconnect = synchronized(lock) {
                        !disconnecting && connections.contains(connection)
                    }
                    if (shouldDisconnect) connection.socket.disconnect()
                }
            }
        }
    }

    private suspend fun startHandoff(old: Connection, reconnectUrl: String) {
        val replacement = synchronized(lock) {
            if (disconnecting || activeConnection !== old || handoffConnection != null) return
            newConnection(reconnectUrl, true).also {
                handoffConnection = it
                startConnectionLocked(it)
            }
        }
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = scope?.launch {
            // Twitch's reconnect URL is valid for roughly 30 seconds. Leave a
            // little margin, then use a genuinely new session if needed.
            delay(27_000L)
            val shouldFallback = synchronized(lock) {
                handoffConnection === replacement
            }
            if (shouldFallback) {
                replacement.job?.cancel()
                replacement.pongTimer?.cancel()
                replacement.socket.disconnect()
                val oldConnection = synchronized(lock) {
                    if (handoffConnection === replacement) {
                        handoffConnection = null
                        connections.remove(replacement)
                        activeConnection.takeIf { it === old }
                    } else null
                }
                oldConnection?.let {
                    synchronized(lock) { connections.remove(it) }
                    it.pongTimer?.cancel()
                    it.job?.cancel()
                    it.socket.disconnect()
                }
                startFreshConnection()
            }
        }
    }

    private suspend fun promoteHandoff(connection: Connection, sessionId: String) {
        val old = synchronized(lock) {
            if (handoffConnection !== connection || disconnecting) return
            val previous = activeConnection
            activeConnection = connection
            handoffConnection = null
            previous?.let { connections.remove(it) }
            connection.welcomed = true
            previous
        }
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = null
        old?.pongTimer?.cancel()
        old?.job?.cancel()
        old?.socket?.disconnect()
        // Twitch transferred the subscriptions. Do not call onWelcomeMessage.
        listener.onReconnectWelcome(sessionId)
    }

    private fun startFreshConnection() {
        synchronized(lock) {
            if (disconnecting) return
            activeConnection = newConnection("wss://eventsub.wss.twitch.tv/ws", false)
            activeConnection?.let(::startConnectionLocked)
        }
    }

    private fun connectionFor(socket: WebSocket): Connection? = synchronized(lock) {
        connections.firstOrNull { it.socket === socket }
    }

    interface Listener {
        suspend fun onConnect() {}
        suspend fun onWelcomeMessage(sessionId: String) {}
        /** Called after a Twitch reconnect handoff; subscriptions were transferred. */
        suspend fun onReconnectWelcome(sessionId: String) {}
        suspend fun onChatMessage(event: JSONObject, timestamp: String?) {}
        suspend fun onUserNotice(event: JSONObject, timestamp: String?) {}
        suspend fun onClearChat(event: JSONObject, timestamp: String?) {}
        suspend fun onRoomState(event: JSONObject, timestamp: String?) {}
        suspend fun onStreamOnline(event: JSONObject, timestamp: String?) {}
        suspend fun onDisconnect(message: String, fullMsg: String?) {}
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            val connection = connectionFor(webSocket) ?: return
            if (!connection.isHandoff) listener.onConnect()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            try {
                val connection = connectionFor(webSocket) ?: return
                val isCurrentConnection = synchronized(lock) {
                    !disconnecting && (connection === activeConnection || connection === handoffConnection)
                }
                if (!isCurrentConnection) return
                val json = if (message.isNotBlank()) JSONObject(message) else null
                if (json != null) {
                    val metadata = json.optJSONObject("metadata")
                    val messageId = if (metadata?.isNull("message_id") == false) metadata.optString("message_id").takeIf { it.isNotBlank() } else null
                    val timestamp = if (metadata?.isNull("message_timestamp") == false) metadata.optString("message_timestamp").takeIf { it.isNotBlank() } else null
                    if (!messageId.isNullOrBlank()) {
                        synchronized(handledMessageIds) {
                            if (handledMessageIds.contains(messageId)) return
                            handledMessageIds.add(messageId)
                            if (handledMessageIds.size > 200) handledMessageIds.removeAt(0)
                        }
                    }
                    when (metadata?.optString("message_type")) {
                        "notification" -> {
                            startPongTimer(connection)
                            val payload = json.optJSONObject("payload")
                            val event = payload?.optJSONObject("event")
                            if (event != null) {
                                when (metadata.optString("subscription_type")) {
                                    "channel.chat.message" -> listener.onChatMessage(event, timestamp)
                                    "channel.chat.notification" -> listener.onUserNotice(event, timestamp)
                                    "channel.chat.clear" -> listener.onClearChat(event, timestamp)
                                    "channel.chat_settings.update" -> listener.onRoomState(event, timestamp)
                                    "stream.online" -> listener.onStreamOnline(event, timestamp)
                                }
                            }
                        }
                        "session_keepalive" -> startPongTimer(connection)
                        "session_reconnect" -> {
                            val payload = json.optJSONObject("payload")
                            val session = payload?.optJSONObject("session")
                            val reconnectUrl = if (session?.isNull("reconnect_url") == false) {
                                session.optString("reconnect_url").takeIf { it.isNotBlank() }
                            } else null
                            if (!connection.isHandoff && !reconnectUrl.isNullOrBlank()) {
                                startHandoff(connection, reconnectUrl)
                            }
                        }
                        "session_welcome" -> {
                            val payload = json.optJSONObject("payload")
                            val session = payload?.optJSONObject("session")
                            session?.optInt("keepalive_timeout_seconds")?.takeIf { it > 0 }?.let {
                                timeout = it * 1000L
                            }
                            startPongTimer(connection)
                            connection.welcomed = true
                            val sessionId = session?.optString("id")?.takeIf { it.isNotBlank() }
                            if (!sessionId.isNullOrBlank()) {
                                if (!reconnectState.shouldCreateSubscriptions(connection.isHandoff)) {
                                    promoteHandoff(connection, sessionId)
                                } else if (connection === activeConnection) {
                                    listener.onWelcomeMessage(sessionId)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A malformed EventSub message must not kill the socket loop.
            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            val connection = connectionFor(webSocket) ?: return
            if (connection.isHandoff && !connection.welcomed) return
            if (connection === activeConnection && !disconnecting) {
                listener.onDisconnect(message, fullMsg)
            }
        }
    }
}
