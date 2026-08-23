package com.github.andreyasadchy.xtra.util

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Coroutine adapter around OkHttp's RFC 6455 implementation. */
class WebSocket(
    @Volatile private var url: String,
    private val trustManager: Lazy<X509TrustManager>,
    private val listener: Listener,
    private val headers: Map<String, String>? = null,
    private val sendPings: Boolean = false,
) {
    @Volatile
    private var socket: okhttp3.WebSocket? = null

    @Volatile
    private var disconnectRequested = false

    @Volatile
    private var connected = false

    var coroutineScope: CoroutineScope? = null

    private val client by lazy {
        OkHttpClient.Builder().apply {
            readTimeout(0, TimeUnit.MILLISECONDS)
            if (sendPings) {
                pingInterval(PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager.value), null)
                sslSocketFactory(sslContext.socketFactory, trustManager.value)
            }
        }.build()
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        disconnectRequested = false
        var connectionAttempt = 0
        while (isActive && !disconnectRequested) {
            connectionAttempt += 1
            var delayReconnect = false
            try {
                val result = runConnection()
                if (result.connected) {
                    connectionAttempt = 0
                }
                delayReconnect = result.httpCode == 429
                result.failure?.let { failure ->
                    if (!disconnectRequested) {
                        listener.onDisconnect(this@WebSocket, failure.toString(), failure.stackTraceToString())
                    }
                    if (failure is SSLHandshakeException) {
                        return@withContext
                    }
                }
            } catch (e: CancellationException) {
                socket?.cancel()
                throw e
            } catch (e: SSLHandshakeException) {
                listener.onDisconnect(this@WebSocket, e.toString(), e.stackTraceToString())
                return@withContext
            } catch (e: Exception) {
                if (!disconnectRequested) {
                    listener.onDisconnect(this@WebSocket, e.toString(), e.stackTraceToString())
                }
            } finally {
                connected = false
                socket?.cancel()
                socket = null
            }

            ensureActive()
            if (disconnectRequested || connectionAttempt >= MAX_CONNECTION_ATTEMPTS) {
                return@withContext
            }
            delay(if (delayReconnect) 1.minutes else 1.seconds)
        }
    }

    private suspend fun runConnection(): ConnectionResult {
        val events = Channel<Event>(EVENT_QUEUE_CAPACITY)
        fun enqueue(event: Event) {
            if (events.trySend(event).isFailure) {
                events.close(IOException("WebSocket event queue overflow"))
                socket?.cancel()
            }
        }
        val request = Request.Builder().url(url).apply {
            headers?.forEach { (name, value) -> header(name, value) }
        }.build()
        val currentSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                enqueue(Event.Open)
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                enqueue(Event.Message(text))
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString) {
                enqueue(Event.Message(bytes.utf8()))
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                enqueue(Event.Closed)
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                enqueue(Event.Failed(t, response?.code))
            }
        })
        socket = currentSocket

        var opened = false
        try {
            for (event in events) {
                when (event) {
                    Event.Open -> {
                        opened = true
                        connected = true
                        listener.onConnect(this)
                    }
                    is Event.Message -> listener.onMessage(this, event.text)
                    Event.Closed -> return ConnectionResult(connected = opened)
                    is Event.Failed -> return ConnectionResult(opened, event.error, event.httpCode)
                }
            }
        } finally {
            events.close()
        }
        return ConnectionResult(connected = opened)
    }

    suspend fun write(message: String) = withContext(Dispatchers.IO) {
        if (!connected || socket?.send(message) != true) {
            throw IOException("WebSocket is not connected")
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectRequested = true
        closeCurrentConnection()
    }

    /** Closes only the current connection so [start] can establish a replacement. */
    suspend fun reconnect() = withContext(Dispatchers.IO) {
        closeCurrentConnection()
    }

    private fun closeCurrentConnection() {
        connected = false
        val currentSocket = socket
        if (currentSocket?.close(NORMAL_CLOSURE, null) == false) {
            currentSocket.cancel()
        }
        socket = null
    }

    fun updateUrl(url: String) {
        this.url = url
    }

    interface Listener {
        suspend fun onConnect(webSocket: WebSocket) {}
        suspend fun onMessage(webSocket: WebSocket, message: String) {}
        suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String? = null) {}
    }

    private sealed interface Event {
        data object Open : Event
        data class Message(val text: String) : Event
        data object Closed : Event
        data class Failed(val error: Throwable, val httpCode: Int?) : Event
    }

    private data class ConnectionResult(
        val connected: Boolean,
        val failure: Throwable? = null,
        val httpCode: Int? = null,
    )

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_CONNECTION_ATTEMPTS = 20
        const val PING_INTERVAL_MILLIS = 270_000L
        const val EVENT_QUEUE_CAPACITY = 256
    }
}
