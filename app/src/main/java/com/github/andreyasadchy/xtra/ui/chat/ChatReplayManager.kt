package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.model.gql.video.nextCursor
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal fun VideoChatMessage.replayTimestampMs(vodStartTimestampMs: Long?): Long? =
    createdAt?.let { value ->
        Instant.parseOrNull(value)?.toEpochMilliseconds()?.takeIf { it > 0L }
    } ?: vodStartTimestampMs?.let { start ->
        offsetSeconds?.toLong()?.times(1000L)?.let { offset -> start + offset }
    }

class ChatReplayManager(
    private val networkLibrary: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val json: Json,
    private val videoId: String,
    private val createdAt: Long?,
    private val startTime: Long,
    private var getCurrentPosition: () -> Long?,
    private var getCurrentSpeed: () -> Float?,
    private val coroutineScope: CoroutineScope,
    private val listener: Listener,
) {
    private var cursor: String? = null
    private val list = mutableListOf<VideoChatMessage>()
    private var started = false
    private var isLoading = false
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun start() {
        if (!started) {
            started = true
            val currentPosition = getCurrentPosition() ?: 0
            lastCheckedPosition = currentPosition
            playbackSpeed = getCurrentSpeed()
            list.clear()
            coroutineScope.launch {
                listener.clearMessages()
            }
            load(currentPosition + startTime)
        }
    }

    fun stop() {
        loadGeneration++
        isLoading = false
        loadJob?.cancel()
        messageJob?.cancel()
        isActive = false
    }

    private data class ReplayPage(val messages: List<VideoChatMessage>, val nextCursor: String?)

    private fun load(position: Long? = null) {
        if (!isActive) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        val requestedCursor = cursor
        isLoading = true
        // All replay state belongs to the caller's UI scope. Repository calls move network I/O
        // to their own dispatcher, so they cannot race the message ticker or a seek.
        loadJob = coroutineScope.launch {
            var retryDelayMs = 1_000L
            try {
                while (isActive && generation == loadGeneration) {
                    try {
                        val page = loadPage(position, requestedCursor)
                        currentCoroutineContext().ensureActive()
                        if (generation != loadGeneration) return@launch
                        check(requestedCursor == null || page.nextCursor != requestedCursor) {
                            "Replay pagination did not advance"
                        }
                        messageJob?.cancel()
                        list.addAll(page.messages)
                        cursor = page.nextCursor
                        isLoading = false
                        if (list.isEmpty() && !cursor.isNullOrBlank()) load() else startJob()
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2L).coerceAtMost(30_000L)
                    }
                }
            } finally {
                if (generation == loadGeneration) isLoading = false
            }
        }
    }

    private suspend fun loadPage(position: Long?, requestedCursor: String?): ReplayPage = withContext(Dispatchers.Default) {
        try {
            val response = if (position != null) {
                graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, offset = position.div(1000).toInt())
            } else {
                graphQLRepository.loadQueryVideoComments(networkLibrary, gqlHeaders, videoId, cursor = requestedCursor)
            }
            val comments = response.data!!.video!!.comments!!
            val messages = comments.edges!!.mapNotNull { comment ->
                comment?.node.let { item ->
                    item?.message?.let { message ->
                        val chatMessage = StringBuilder()
                        val emotes = message.fragments?.mapNotNull { fragment ->
                            fragment?.text?.let { text ->
                                fragment.emote?.emoteID?.let { id ->
                                    TwitchEmote(
                                        id = id,
                                        begin = chatMessage.codePointCount(0, chatMessage.length),
                                        end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex
                                    )
                                }.also { chatMessage.append(text) }
                            }
                        }
                        val badges = message.userBadges?.mapNotNull { badge ->
                            badge?.setID?.let { setId ->
                                badge.version?.let { version ->
                                    Badge(
                                        setId = setId,
                                        version = version,
                                    )
                                }
                            }
                        }
                        VideoChatMessage(
                            id = item.id,
                            offsetSeconds = item.contentOffsetSeconds,
                            createdAt = item.createdAt?.toString(),
                            userId = item.commenter?.id,
                            userLogin = item.commenter?.login,
                            userName = item.commenter?.displayName,
                            message = chatMessage.toString(),
                            color = message.userColor,
                            emotes = emotes,
                            badges = badges,
                            fullMsg = null
                        )
                    }
                }
            }
            val nextCursor = if (comments.pageInfo?.hasNextPage != false) {
                comments.edges.orEmpty().asReversed()
                    .firstNotNullOfOrNull { it?.cursor?.takeIf(String::isNotBlank) }
            } else null
            ReplayPage(messages, nextCursor)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val response = if (position != null) {
                graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, offset = position.div(1000).toInt())
            } else {
                graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, cursor = requestedCursor)
            }
            val comments = response.data?.video?.comments
                ?: error("Replay comments were unavailable")
            val messages = comments.edges.orEmpty().mapNotNull { comment ->
                comment?.node?.let { item ->
                    item.message?.let { message ->
                        val chatMessage = StringBuilder()
                        val emotes = message.fragments?.mapNotNull { fragment ->
                            fragment.text?.let { text ->
                                fragment.emote?.emoteID?.let { id ->
                                    TwitchEmote(
                                        id = id,
                                        begin = chatMessage.codePointCount(0, chatMessage.length),
                                        end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex
                                    )
                                }.also { chatMessage.append(text) }
                            }
                        }
                        val badges = message.userBadges?.mapNotNull { badge ->
                            badge.setID?.let { setId ->
                                badge.version?.let { version ->
                                    Badge(
                                        setId = setId,
                                        version = version,
                                    )
                                }
                            }
                        }
                        VideoChatMessage(
                            id = item.id,
                            offsetSeconds = item.contentOffsetSeconds,
                            createdAt = item.createdAt,
                            userId = item.commenter?.id,
                            userLogin = item.commenter?.login,
                            userName = item.commenter?.displayName,
                            message = chatMessage.toString(),
                            color = message.userColor,
                            emotes = emotes,
                            badges = badges,
                            fullMsg = json.encodeToString(item)
                        )
                    }
                }
            }
            ReplayPage(messages, if (comments.pageInfo?.hasNextPage != false) comments.nextCursor else null)
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive) {
                val message = list.firstOrNull() ?: break
                val messageOffset = if (createdAt != null && !message.createdAt.isNullOrBlank()) {
                    Instant.parseOrNull(message.createdAt)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.minus(createdAt)
                } else {
                    null
                } ?: message.offsetSeconds?.times(1000L)
                if (messageOffset != null) {
                    var currentPosition: Long
                    while (
                        (getCurrentPosition() ?: 0).let { position ->
                            lastCheckedPosition = position
                            currentPosition = position + startTime
                            currentPosition < messageOffset
                        }
                    ) {
                        delay(max((messageOffset - currentPosition).div(playbackSpeed ?: 1f).toLong(), 0).milliseconds)
                    }
                    if (!isActive) {
                        break
                    }
                    listener.onChatMessage(
                        ChatMessage(
                            type = ChatMessage.USER_MESSAGE,
                            id = message.id,
                            userId = message.userId,
                            userLogin = message.userLogin,
                            userName = message.userName,
                            message = message.message,
                            color = message.color,
                            emotes = message.emotes,
                            badges = message.badges,
                            bits = 0,
                            timestamp = message.replayTimestampMs(createdAt),
                            fullMsg = message.fullMsg
                        )
                    )
                    if (list.size <= 25 && !cursor.isNullOrBlank() && !isLoading) {
                        load()
                    }
                } else {
                    if (!isActive) {
                        break
                    }
                }
                list.remove(message)
            }
        }
    }

    fun updatePosition(position: Long) {
        if (started && lastCheckedPosition != position) {
            if (position - lastCheckedPosition !in 0..20000) {
                loadJob?.cancel()
                messageJob?.cancel()
                list.clear()
                coroutineScope.launch {
                    listener.clearMessages()
                }
                load(position + startTime)
            } else {
                messageJob?.cancel()
                startJob()
            }
            lastCheckedPosition = position
        }
    }

    fun updateSpeed(speed: Float) {
        if (started && playbackSpeed != speed) {
            playbackSpeed = speed
            messageJob?.cancel()
            startJob()
        }
    }

    fun rebindPositionProviders(
        getCurrentPosition: () -> Long?,
        getCurrentSpeed: () -> Float?,
    ) {
        this.getCurrentPosition = getCurrentPosition
        this.getCurrentSpeed = getCurrentSpeed
        if (started && isActive) {
            messageJob?.cancel()
            startJob()
        }
    }

    interface Listener {
        suspend fun onChatMessage(message: ChatMessage) {}
        suspend fun clearMessages() {}
        suspend fun getIntegrityToken() {}
    }
}
