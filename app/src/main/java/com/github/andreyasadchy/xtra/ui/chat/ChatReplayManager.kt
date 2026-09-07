package com.github.andreyasadchy.xtra.ui.chat

import android.util.Log
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.model.gql.video.nextCursor
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal data class ChatReplayPage(
    val messages: List<VideoChatMessage>,
    val hasNextPage: Boolean,
    val nextCursor: String?,
    val boundaryOffsetSeconds: Int? = null,
)

internal interface ChatReplayPageLoader {
    suspend fun loadGraphQl(request: ChatReplayPageRequest): ChatReplayPage
    suspend fun loadLegacy(request: ChatReplayPageRequest): ChatReplayPage
}

class ChatReplayManager internal constructor(
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
    private val pageLoaderOverride: ChatReplayPageLoader? = null,
) {
    private val pagination = ChatReplayPagination()
    private val list = mutableListOf<VideoChatMessage>()
    private var started = false
    private var isLoading = false
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var loadGeneration = 0L
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    private companion object {
        const val TAG = "ChatReplayManager"
    }

    fun start() {
        if (!started) {
            started = true
            val currentPosition = getCurrentPosition() ?: 0
            lastCheckedPosition = currentPosition
            playbackSpeed = getCurrentSpeed()
            pagination.reset()
            list.clear()
            coroutineScope.launch {
                listener.clearMessages()
            }
            load(currentPosition + startTime)
        }
    }

    fun stop() {
        loadGeneration++
        loadJob?.cancel()
        messageJob?.cancel()
        isLoading = false
        isActive = false
    }

    private fun load(position: Long? = null) {
        val generation = ++loadGeneration
        isLoading = true
        loadJob = coroutineScope.launch {
            try {
                val offsetSeconds = position?.div(1000)?.toInt()
                val request = pagination.request(offsetSeconds)
                val loadedPage = try {
                    ChatReplayPageWithRequest(
                        request = request,
                        page = withContext(Dispatchers.IO) {
                            pageLoaderOverride?.loadGraphQl(request) ?: loadGraphQlPage(request)
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation != loadGeneration || !this@ChatReplayManager.isActive) return@launch
                    if (request is ChatReplayPageRequest.Cursor) {
                        pagination.onCursorFailure()
                    }
                    val fallbackRequest = pagination.request(offsetSeconds)
                    ChatReplayPageWithRequest(
                        request = fallbackRequest,
                        page = withContext(Dispatchers.IO) {
                            pageLoaderOverride?.loadLegacy(fallbackRequest)
                                ?: loadLegacyPage(fallbackRequest)
                        },
                    )
                }

                if (generation != loadGeneration || !this@ChatReplayManager.isActive) return@launch
                messageJob?.cancel()
                appendPage(loadedPage.request, loadedPage.page)
                isLoading = false
                startJob()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed page will be retried by the next playback-triggered load.
            } finally {
                if (generation == loadGeneration) {
                    isLoading = false
                }
            }
        }
    }

    private data class ChatReplayPageWithRequest(
        val request: ChatReplayPageRequest,
        val page: ChatReplayPage,
    )

    private suspend fun loadGraphQlPage(request: ChatReplayPageRequest): ChatReplayPage {
        val response = when (request) {
            ChatReplayPageRequest.Initial -> graphQLRepository.loadQueryVideoComments(
                networkLibrary,
                gqlHeaders,
                videoId,
            )
            is ChatReplayPageRequest.Offset -> graphQLRepository.loadQueryVideoComments(
                networkLibrary,
                gqlHeaders,
                videoId,
                offset = request.seconds,
            )
            is ChatReplayPageRequest.Cursor -> graphQLRepository.loadQueryVideoComments(
                networkLibrary,
                gqlHeaders,
                videoId,
                cursor = request.value,
            )
        }
        val comments = response.data!!.video!!.comments!!
        val edges = comments.edges.orEmpty()
        return ChatReplayPage(
            messages = edges.mapNotNull { comment ->
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
                            fullMsg = null,
                        )
                    }
                }
            },
            hasNextPage = comments.pageInfo?.hasNextPage == true,
            nextCursor = edges.asReversed().firstNotNullOfOrNull { edge ->
                edge?.cursor?.takeIf(String::isNotBlank)
            },
            boundaryOffsetSeconds = edges.asReversed().firstNotNullOfOrNull { edge ->
                edge?.node?.contentOffsetSeconds
            },
        )
    }

    private suspend fun loadLegacyPage(request: ChatReplayPageRequest): ChatReplayPage {
        val response = when (request) {
            ChatReplayPageRequest.Initial -> graphQLRepository.loadVideoMessages(
                networkLibrary,
                gqlHeaders,
                videoId,
            )
            is ChatReplayPageRequest.Offset -> graphQLRepository.loadVideoMessages(
                networkLibrary,
                gqlHeaders,
                videoId,
                offset = request.seconds,
            )
            is ChatReplayPageRequest.Cursor -> graphQLRepository.loadVideoMessages(
                networkLibrary,
                gqlHeaders,
                videoId,
                cursor = request.value,
            )
        }
        val comments = response.data?.video?.comments ?: return ChatReplayPage(emptyList(), false, null)
        val edges = comments.edges.orEmpty()
        return ChatReplayPage(
            messages = edges.mapNotNull { comment ->
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
                            fullMsg = json.encodeToString(item),
                        )
                    }
                }
            },
            hasNextPage = comments.pageInfo?.hasNextPage == true,
            nextCursor = comments.nextCursor,
            boundaryOffsetSeconds = edges.asReversed().firstNotNullOfOrNull { edge ->
                edge?.node?.contentOffsetSeconds
            },
        )
    }

    private fun appendPage(request: ChatReplayPageRequest, page: ChatReplayPage) {
        list.addAll(
            pagination.acceptPage(
                request = request,
                messages = page.messages,
                hasNextPage = page.hasNextPage,
                nextCursor = page.nextCursor,
                messageId = VideoChatMessage::id,
                contentOffsetSeconds = VideoChatMessage::offsetSeconds,
                pageBoundaryOffsetSeconds = page.boundaryOffsetSeconds,
                onForcedBoundaryEscape = { fromSeconds, toSeconds ->
                    Log.w(
                        TAG,
                        "Replay offset boundary made no progress at $fromSeconds; " +
                            "escaping to $toSeconds",
                    )
                },
            )
        )
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive) {
                val message = list.firstOrNull()
                if (message == null) {
                    if (!isLoading && pagination.hasMorePages) {
                        load()
                    }
                    break
                }
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
                            fullMsg = message.fullMsg
                        )
                    )
                    if (list.size <= 25 && pagination.hasMorePages && !isLoading) {
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
                pagination.resetWindow()
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
