package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.EventSubWebSocket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import javax.net.ssl.X509TrustManager

data class TwitchChatTransportConfig(
    val channelId: String,
    val channelLogin: String,
    val useEventSub: Boolean,
    val accountId: String? = null,
    val helixHeaders: Map<String, String> = emptyMap(),
    val networkLibrary: String? = null,
    val showUserNotices: Boolean = true,
    val showClearMessages: Boolean = true,
    val showClearChat: Boolean = true,
    val joinedMessage: String? = null,
    val messageDeletedMessage: String? = null,
    val chatClearedMessage: String? = null,
)

/**
 * Adapts the existing protocol sockets to the lossless v2 ingress contract.
 * Protocol callbacks only normalize and suspend-send an event; no catalog,
 * image, RecyclerView, or presentation work is performed here.
 */
class TwitchChatTransport(
    private val config: TwitchChatTransportConfig,
    private val trustManager: Lazy<X509TrustManager>,
    private val createSubscription: suspend (Map<String, String>, String?, String, String?) -> Unit = { _, _, _, _ -> },
) : com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport {
    override fun events(session: ChatSessionKey): Flow<ChatEvent> = if (config.useEventSub) {
        eventSubEvents(session).catch { error ->
            // EventSub chat subscriptions are capability-gated per channel. A token can have
            // the scopes but still be unable to subscribe to a channel it does not moderate.
            // Keep the v2 session live by falling back to the same normalized IRC stream.
            if (error is CancellationException) throw error
            emitAll(ircEvents(session))
        }
    } else {
        ircEvents(session)
    }

    private fun ircEvents(session: ChatSessionKey): Flow<ChatEvent> = callbackFlow {
        val flowScope = this
        val socket = ChatReadWebSocket(
            channelLogin = config.channelLogin,
            trustManager = trustManager,
            listener = object : ChatReadWebSocket.Listener {
                override suspend fun onConnect() {
                    systemMessage(session, "join", config.joinedMessage)?.let { flowScope.send(it) }
                }

                override suspend fun onChatMessage(message: ChatUtils.IRCMessage, userNotice: Boolean) {
                    if (userNotice && !config.showUserNotices) return
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event -> send(event) }
                }

                override suspend fun onClearMessage(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event ->
                        send(event)
                        if (config.showClearMessages) {
                            val eventKey = message.tags["target-msg-id"]
                                ?: message.tags["tmi-sent-ts"]
                                ?: System.nanoTime().toString()
                            systemMessage(session, "delete-$eventKey", config.messageDeletedMessage)?.let { flowScope.send(it) }
                        }
                    }
                }

                override suspend fun onClearChat(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event ->
                        send(event)
                        if (config.showClearChat) {
                            val eventKey = message.tags["tmi-sent-ts"] ?: System.nanoTime().toString()
                            systemMessage(session, "clear-$eventKey", config.chatClearedMessage)?.let { flowScope.send(it) }
                        }
                    }
                }

                override suspend fun onNotice(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event -> send(event) }
                }

                override suspend fun onRoomState(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event -> send(event) }
                }

                override suspend fun onDisconnect(message: String, fullMsg: String?) {
                    flowScope.send(ChatEvent.TransportDisconnected(message))
                }
            },
        )
        val connectionJob = socket.connect(this)
        awaitClose {
            flowScope.launch { socket.disconnect(connectionJob) }
        }
    }.buffer(4096, kotlinx.coroutines.channels.BufferOverflow.SUSPEND)

    private fun eventSubEvents(session: ChatSessionKey): Flow<ChatEvent> = callbackFlow {
        val flowScope = this
        val socket = EventSubWebSocket(
            trustManager = trustManager,
            listener = object : EventSubWebSocket.Listener {
                override suspend fun onConnect() {
                    systemMessage(session, "join", config.joinedMessage)?.let { flowScope.send(it) }
                }

                override suspend fun onWelcomeMessage(sessionId: String) {
                    flowScope.launch {
                        EVENTSUB_CHAT_SUBSCRIPTIONS.forEach { type ->
                            createSubscription(config.helixHeaders, config.accountId, type, sessionId)
                        }
                    }
                }

                override suspend fun onChatMessage(event: org.json.JSONObject, timestamp: String?) {
                    flowScope.send(TwitchChatEventParser.fromEventSub(event, timestamp))
                }

                override suspend fun onUserNotice(event: org.json.JSONObject, timestamp: String?) {
                    if (!config.showUserNotices) return
                    flowScope.send(TwitchChatEventParser.fromEventSub(event, timestamp, notice = true))
                }

                override suspend fun onClearChat(event: org.json.JSONObject, timestamp: String?) {
                    val clearEvent = TwitchChatEventParser.fromEventSubClear(event, timestamp)
                    flowScope.send(clearEvent)
                    if (config.showClearChat) {
                        systemMessage(session, "clear-${clearEvent.eventId ?: timestamp ?: System.nanoTime()}", config.chatClearedMessage)?.let { flowScope.send(it) }
                    }
                }

                override suspend fun onClearUserMessages(event: org.json.JSONObject, timestamp: String?) {
                    val clearEvent = TwitchChatEventParser.fromEventSubClear(event, timestamp)
                    flowScope.send(clearEvent)
                    if (config.showClearChat) {
                        systemMessage(session, "clear-user-${clearEvent.eventId ?: timestamp ?: System.nanoTime()}", config.chatClearedMessage)?.let { flowScope.send(it) }
                    }
                }

                override suspend fun onMessageDelete(event: org.json.JSONObject, timestamp: String?) {
                    val deleteEvent = TwitchChatEventParser.fromEventSubClear(event, timestamp)
                    flowScope.send(deleteEvent)
                    if (config.showClearMessages) {
                        systemMessage(session, "delete-${deleteEvent.eventId ?: timestamp ?: System.nanoTime()}", config.messageDeletedMessage)?.let { flowScope.send(it) }
                    }
                }

                override suspend fun onRoomState(event: org.json.JSONObject, timestamp: String?) {
                    flowScope.send(TwitchChatEventParser.fromEventSubSettings(event, timestamp, config.channelId))
                }

                override suspend fun onDisconnect(message: String, fullMsg: String?) {
                    flowScope.send(ChatEvent.TransportDisconnected(message))
                }
            },
        )
        val connectionJob = socket.connect(this)
        awaitClose {
            flowScope.launch { socket.disconnect(connectionJob) }
        }
    }.buffer(4096, kotlinx.coroutines.channels.BufferOverflow.SUSPEND)

    private fun systemMessage(session: ChatSessionKey, suffix: String, text: String?): ChatEvent.Message? =
        text?.takeIf { it.isNotBlank() }?.let {
            ChatEvent.Message(
                message = ChatMessage(
                    id = ChatMessageId("system-$suffix-${session.generation}"),
                    channelId = config.channelId,
                    timestampMs = System.currentTimeMillis(),
                    user = null,
                    badges = emptyList(),
                    segments = emptyList(),
                    kind = ChatMessageKind.SYSTEM,
                    systemText = it,
                ),
            )
        }

    private companion object {
        val EVENTSUB_CHAT_SUBSCRIPTIONS = listOf(
            "channel.chat.message",
            "channel.chat.notification",
            "channel.chat.clear",
            "channel.chat.clear_user_messages",
            "channel.chat.message_delete",
            "channel.chat_settings.update",
        )
    }
}
