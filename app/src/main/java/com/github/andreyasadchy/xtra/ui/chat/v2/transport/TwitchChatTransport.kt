package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatCommunityGift
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModerationDisplayMode
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.EventSubWebSocket
import com.github.andreyasadchy.xtra.util.chat.HermesWebSocket
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import com.github.andreyasadchy.xtra.util.chat.STVEventApiUtils
import com.github.andreyasadchy.xtra.util.chat.STVEventApiWebSocket
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaintShadow
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.model.chat.Emote as LegacyEmote
import com.github.andreyasadchy.xtra.ui.chat.HappeningNowGiftParser
import com.github.andreyasadchy.xtra.ui.chat.HappeningNowGift
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.net.ssl.X509TrustManager

internal fun eventSubSubscriptionTypes(enableRewardRedemptions: Boolean): List<String> = buildList {
    add("channel.chat.message")
    add("channel.chat.notification")
    add("channel.chat.clear")
    add("channel.chat.clear_user_messages")
    add("channel.chat.message_delete")
    add("channel.chat_settings.update")
    if (enableRewardRedemptions) add("channel.channel_points_custom_reward_redemption.add")
}

data class TwitchChatTransportConfig(
    val channelId: String,
    val channelLogin: String,
    val useEventSub: Boolean,
    val accountId: String? = null,
    val gqlClientId: String? = null,
    val gqlToken: String? = null,
    /** Hermes channel rewards preserve the unrestricted legacy no-input path. */
    val enableHermesRewards: Boolean = true,
    /** EventSub redemption events require broadcaster/moderator redemption scopes. */
    val enableRewardRedemptions: Boolean = false,
    val enableSevenTv: Boolean = true,
    /** Reports this channel's presence through the v2-owned 7TV socket. */
    val updateSevenTvPresence: (suspend (sessionId: String?, self: Boolean) -> Unit)? = null,
    val helixHeaders: Map<String, String> = emptyMap(),
    val networkLibrary: String? = null,
    val showUserNotices: Boolean = true,
    /** Master toggle for single-message deletion notices and retained deleted rows. */
    val showClearMessages: Boolean = true,
    val showClearChat: Boolean = true,
    /** Shared display policy for single deletions and user-level moderation clears. */
    val moderationDisplayMode: () -> ChatModerationDisplayMode = { ChatModerationDisplayMode.NOTICE },
    val joinedMessage: String? = null,
    val messageDeletedMessage: String? = null,
    val chatClearedMessage: String? = null,
    val chatTimeoutMessage: ((String, Int) -> String)? = null,
    val chatBanMessage: ((String) -> String)? = null,
    val chatUserMessagesClearedMessage: ((String) -> String)? = null,
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
                    if (userNotice) {
                        HappeningNowGiftParser.fromIrc(message)?.let { gift ->
                            flowScope.send(ChatEvent.CommunityGift(gift.toChatCommunityGift()))
                        }
                    }
                    if (userNotice && !config.showUserNotices) return
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { event ->
                        send(event)
                        notifySevenTvPresence(flowScope, event)
                    }
                }

                override suspend fun onClearMessage(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { parsedEvent ->
                        val event = applyDeletionDisplay(parsedEvent)
                        send(event)
                        if (shouldShowDeletionNotice(event)) {
                            val eventKey = message.tags["target-msg-id"]
                                ?: message.tags["tmi-sent-ts"]
                                ?: System.nanoTime().toString()
                            systemMessage(session, "delete-$eventKey", config.messageDeletedMessage)?.let { flowScope.send(it) }
                        }
                    }
                }

                override suspend fun onClearChat(message: ChatUtils.IRCMessage) {
                    TwitchChatEventParser.fromIrc(message, config.channelId)?.let { parsedEvent ->
                        val event = applyModerationDisplay(parsedEvent)
                        send(event)
                        moderationSystemMessage(session, event)?.let { flowScope.send(it) }
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
        val sevenTv = attachSevenTv(this) { event -> send(event) }
        val hermes = attachHermesRewards(this) { event -> send(event) }
        val connectionJob = socket.connect(this)
        awaitClose {
            flowScope.launch {
                socket.disconnect(connectionJob)
                sevenTv?.let { (stv, job) -> stv.disconnect(job) }
                hermes?.let { (hermesSocket, job) -> hermesSocket.disconnect(job) }
            }
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
                        eventSubSubscriptionTypes(config.enableRewardRedemptions).forEach { type ->
                            try {
                                createSubscription(config.helixHeaders, config.accountId, type, sessionId)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                if (type !in REWARD_REDEMPTION_SUBSCRIPTION) throw error
                                // This optional capability must not tear down otherwise valid
                                // EventSub chat subscriptions. Hermes remains the fallback.
                            }
                        }
                    }
                }

                override suspend fun onChatMessage(event: org.json.JSONObject, timestamp: String?) {
                    val chatEvent = TwitchChatEventParser.fromEventSub(event, timestamp)
                    flowScope.send(chatEvent)
                    notifySevenTvPresence(flowScope, chatEvent)
                }

                override suspend fun onChannelPointsRewardRedemption(event: org.json.JSONObject, timestamp: String?) {
                    flowScope.send(TwitchChatEventParser.fromEventSubRewardRedemption(event, timestamp))
                }

                override suspend fun onUserNotice(event: org.json.JSONObject, timestamp: String?) {
                    HappeningNowGiftParser.fromEventSub(event, timestamp)?.let { gift ->
                        flowScope.send(ChatEvent.CommunityGift(gift.toChatCommunityGift()))
                    }
                    if (!config.showUserNotices) return
                    flowScope.send(TwitchChatEventParser.fromEventSub(event, timestamp, notice = true))
                }

                override suspend fun onClearChat(event: org.json.JSONObject, timestamp: String?, notificationId: String?) {
                    val clearEvent = applyModerationDisplay(
                        TwitchChatEventParser.fromEventSubClear(event, timestamp, notificationId),
                    )
                    flowScope.send(clearEvent)
                    moderationSystemMessage(session, clearEvent)?.let { flowScope.send(it) }
                }

                override suspend fun onClearUserMessages(event: org.json.JSONObject, timestamp: String?, notificationId: String?) {
                    val clearEvent = applyModerationDisplay(
                        TwitchChatEventParser.fromEventSubClear(event, timestamp, notificationId),
                    )
                    flowScope.send(clearEvent)
                    moderationSystemMessage(session, clearEvent)?.let { flowScope.send(it) }
                }

                override suspend fun onMessageDelete(event: org.json.JSONObject, timestamp: String?) {
                    val deleteEvent = applyDeletionDisplay(
                        TwitchChatEventParser.fromEventSubClear(event, timestamp),
                    )
                    flowScope.send(deleteEvent)
                    if (shouldShowDeletionNotice(deleteEvent)) {
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
        val sevenTv = attachSevenTv(this) { event -> send(event) }
        val hermes = attachHermesRewards(this) { event -> send(event) }
        val connectionJob = socket.connect(this)
        awaitClose {
            flowScope.launch {
                socket.disconnect(connectionJob)
                sevenTv?.let { (stv, job) -> stv.disconnect(job) }
                hermes?.let { (hermesSocket, job) -> hermesSocket.disconnect(job) }
            }
        }
    }.buffer(4096, kotlinx.coroutines.channels.BufferOverflow.SUSPEND)

    private fun attachSevenTv(
        scope: kotlinx.coroutines.CoroutineScope,
        sendEvent: suspend (ChatEvent) -> Unit,
    ): Pair<STVEventApiWebSocket, Job>? {
        if (!config.enableSevenTv) return null
        val socket = STVEventApiWebSocket(
            channelId = config.channelId,
            trustManager = trustManager,
            listener = object : STVEventApiWebSocket.Listener {
                override suspend fun onUpdatePresence(sessionId: String) {
                    notifySevenTvPresence(scope, sessionId, self = true)
                }

                override suspend fun onEmoteSetUpdate(body: org.json.JSONObject) {
                    val result = STVEventApiUtils.parseEmoteSetUpdate(body, useWebp = true, channelSTVEmoteSetId = null)
                        ?: return
                    // The event payload alone does not establish ownership. The catalog
                    // repository routes the set using the actual channel set ID and keeps
                    // unknown sets isolated until an entitlement identifies them.
                    val added = (result.added + result.updated.map { it.second }).mapNotNull {
                        it.toV2Emote(com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope.PERSONAL)
                    }
                        .associateBy { it.name }
                    val removed = (result.removed + result.updated.map { it.first }).mapNotNull { it.name }.toSet()
                    sendEvent(
                        ChatEvent.DecorationUpdated(
                            ChatDecorationUpdate.EmoteSet(
                                setId = result.setId,
                                added = added,
                                removedNames = removed,
                            ),
                        ),
                    )
                }

                override suspend fun onCosmetic(body: org.json.JSONObject) {
                    when (val cosmetic = STVEventApiUtils.parseCosmetic(body, useWebp = true)) {
                        is STVEventApiUtils.Cosmetic.Paint -> sendEvent(
                            ChatEvent.DecorationUpdated(
                                ChatDecorationUpdate.Paint(cosmetic.paint.id ?: return, cosmetic.paint.toChatPaint()),
                            ),
                        )
                        is STVEventApiUtils.Cosmetic.Badge -> sendEvent(
                            cosmetic.badge.toChatBadge()?.let {
                                ChatEvent.DecorationUpdated(ChatDecorationUpdate.Badge(cosmetic.badge.id, it))
                            } ?: return,
                        )
                        null -> Unit
                    }
                }

                override suspend fun onEntitlement(body: org.json.JSONObject) {
                    when (val entitlement = STVEventApiUtils.parseEntitlement(body)) {
                        is STVEventApiUtils.Entitlement.Paint -> sendEvent(
                            ChatEvent.DecorationUpdated(ChatDecorationUpdate.User(entitlement.userId, paintId = entitlement.paintId)),
                        )
                        is STVEventApiUtils.Entitlement.Badge -> sendEvent(
                            ChatEvent.DecorationUpdated(ChatDecorationUpdate.User(entitlement.userId, badgeId = entitlement.badgeId)),
                        )
                        is STVEventApiUtils.Entitlement.EmoteSet -> sendEvent(
                            ChatEvent.DecorationUpdated(ChatDecorationUpdate.User(entitlement.userId, personalEmoteSetId = entitlement.setId)),
                        )
                        null -> Unit
                    }
                }
            },
        )
        return socket to socket.connect(scope)
    }

    private fun HappeningNowGift.toChatCommunityGift() =
        ChatCommunityGift(
            stableId = stableId,
            occurredAt = occurredAt,
            gifterDisplayName = gifterDisplayName,
            isAnonymous = isAnonymous,
            count = count,
            source = source,
        )

    private fun notifySevenTvPresence(scope: kotlinx.coroutines.CoroutineScope, event: ChatEvent) {
        val message = (event as? ChatEvent.Message)?.message ?: return
        val accountId = config.accountId ?: return
        if (message.user?.id != accountId) return
        notifySevenTvPresence(scope, sessionId = null, self = false)
    }

    private fun notifySevenTvPresence(
        scope: kotlinx.coroutines.CoroutineScope,
        sessionId: String?,
        self: Boolean,
    ) {
        val update = config.updateSevenTvPresence ?: return
        scope.launch {
            try {
                update(sessionId, self)
            } catch (_: Throwable) {
                // Presence is advisory and must not affect chat transport health.
            }
        }
    }

    private fun LegacyEmote.toV2Emote(scope: com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope): com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote? {
        val emoteName = name?.takeIf { it.isNotBlank() } ?: return null
        val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
        return com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote(
            name = emoteName,
            id = id?.takeIf { it.isNotBlank() } ?: emoteName,
            asset = ChatAssetSpec(
                key = ChatAssetKey(url),
                sourceWidth = width?.takeIf { it > 0 } ?: 56,
                sourceHeight = height?.takeIf { it > 0 } ?: 56,
                targetHeight = 28,
            ),
            provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.SEVEN_TV,
            animated = isAnimated,
            zeroWidth = isOverlayEmote,
            scope = scope,
        )
    }

    private fun attachHermesRewards(
        scope: kotlinx.coroutines.CoroutineScope,
        sendEvent: suspend (ChatEvent) -> Unit,
    ): Pair<HermesWebSocket, Job>? {
        if (!config.enableHermesRewards || config.channelId.isBlank()) return null
        val authenticated = !config.accountId.isNullOrBlank() && !config.gqlToken.isNullOrBlank()
        val socket = HermesWebSocket(
            channelId = config.channelId,
            userId = config.accountId,
            gqlClientId = config.gqlClientId,
            gqlToken = config.gqlToken,
            collectPoints = false,
            listenForPoints = authenticated,
            showRaids = false,
            showPolls = false,
            showPredictions = false,
            includeChannelTopics = true,
            trustManager = trustManager,
            listener = object : HermesWebSocket.Listener {
                override suspend fun onRewardMessage(message: org.json.JSONObject) {
                    sendEvent(
                        TwitchChatEventParser.fromPubSubReward(
                            PubSubUtils.parseRewardMessage(message),
                            config.channelId,
                        ),
                    )
                }
            },
        )
        return socket to socket.connect(scope)
    }

    private fun com.github.andreyasadchy.xtra.model.chat.NamePaint.toChatPaint() = ChatNamePaint(
        colors = colors?.toList().orEmpty(),
        imageUrl = imageUrl,
        colorPositions = colorPositions?.toList().orEmpty(),
        type = type,
        angle = angle,
        repeat = repeat == true,
        shadows = shadows.orEmpty().map { ChatNamePaintShadow(it.xOffset, it.yOffset, it.radius, it.color) },
    )

    private fun com.github.andreyasadchy.xtra.model.chat.STVBadge.toChatBadge(): ChatCatalogBadge? {
        val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
        return ChatCatalogBadge(
            name = id,
            asset = ChatAssetSpec(ChatAssetKey(url), 18, 18, 18),
            provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.SEVEN_TV,
            setId = id,
            versionId = "default",
            info = name,
        )
    }

    private fun moderationSystemMessage(session: ChatSessionKey, event: ChatEvent): ChatEvent.Message? {
        if (!config.showClearChat) return null
        if (event is ChatEvent.ClearUser && event.displayMode == ChatModerationDisplayMode.STRIKETHROUGH) return null
        val text = when (event) {
            is ChatEvent.Clear -> config.chatClearedMessage
            is ChatEvent.ClearUser -> {
                val target = event.userName ?: event.userLogin ?: event.userId ?: return null
                when (event.reason) {
                    ChatUserClearReason.TIMEOUT -> event.timeoutSeconds?.let { seconds ->
                        config.chatTimeoutMessage?.invoke(target, seconds)
                    } ?: config.chatUserMessagesClearedMessage?.invoke(target)
                    ChatUserClearReason.BAN -> config.chatBanMessage?.invoke(target)
                        ?: config.chatUserMessagesClearedMessage?.invoke(target)
                    ChatUserClearReason.MESSAGES_CLEARED -> config.chatUserMessagesClearedMessage?.invoke(target)
                    ChatUserClearReason.MESSAGE_DELETED -> config.messageDeletedMessage
                }
            }
            else -> null
        }
        val prefix = if (event is ChatEvent.ClearUser) "clear-user" else "clear"
        return systemMessage(
            session = session,
            suffix = "$prefix-${event.eventId ?: event.receivedAtMs}",
            text = text,
            timestampMs = event.receivedAtMs + 1,
        )
    }

    private fun applyModerationDisplay(event: ChatEvent): ChatEvent =
        if (event is ChatEvent.ClearUser) {
            event.copy(displayMode = config.moderationDisplayMode())
        } else {
            event
        }

    private fun applyDeletionDisplay(event: ChatEvent): ChatEvent =
        if (event is ChatEvent.Delete) {
            event.copy(
                displayMode = if (config.showClearMessages) {
                    config.moderationDisplayMode()
                } else {
                    ChatModerationDisplayMode.HIDE
                },
            )
        } else {
            event
        }

    private fun shouldShowDeletionNotice(event: ChatEvent): Boolean =
        event is ChatEvent.Delete &&
            config.showClearMessages &&
            event.displayMode != ChatModerationDisplayMode.STRIKETHROUGH

    private fun systemMessage(
        session: ChatSessionKey,
        suffix: String,
        text: String?,
        timestampMs: Long? = null,
    ): ChatEvent.Message? =
        text?.takeIf { it.isNotBlank() }?.let {
            ChatEvent.Message(
                message = ChatMessage(
                    id = ChatMessageId("system-$suffix-${session.generation}"),
                    channelId = config.channelId,
                    timestampMs = timestampMs ?: System.currentTimeMillis(),
                    user = null,
                    badges = emptyList(),
                    segments = emptyList(),
                    kind = ChatMessageKind.SYSTEM,
                    systemText = it,
                ),
            )
        }

    private companion object {
        val REWARD_REDEMPTION_SUBSCRIPTION = listOf("channel.channel_points_custom_reward_redemption.add")
    }
}

/** Preserves legacy 7TV presence semantics without creating a second Event API socket. */
internal class SevenTvPresenceReporter(
    private val channelId: String,
    private val resolveStvUserId: suspend () -> String?,
    private val sendPresence: suspend (stvUserId: String, channelId: String, sessionId: String?, self: Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var stvUserId: String? = null
    private var lastPresenceUpdate: Long? = null

    suspend fun update(sessionId: String?, self: Boolean) {
        if (channelId.isBlank() || (self && sessionId.isNullOrBlank())) return
        val timestamp = now()
        val allowed = mutex.withLock {
            if (lastPresenceUpdate?.let { timestamp - it > 10_000L } != false) {
                lastPresenceUpdate = timestamp
                true
            } else {
                false
            }
        }
        if (!allowed) return

        val userId = mutex.withLock { stvUserId } ?: runCatching { resolveStvUserId() }.getOrNull()
        if (userId.isNullOrBlank()) return
        mutex.withLock { if (stvUserId == null) stvUserId = userId }
        sendPresence(userId, channelId, sessionId, self)
    }
}
