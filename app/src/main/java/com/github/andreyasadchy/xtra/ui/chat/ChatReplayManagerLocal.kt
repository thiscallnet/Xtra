package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ChatReplayManagerLocal(
    private val createdAt: Long?,
    private var getCurrentPosition: () -> Long?,
    private var getCurrentSpeed: () -> Float?,
    private val coroutineScope: CoroutineScope,
    private val listener: ChatReplayManager.Listener,
) {
    private var liveMessages: List<ChatMessage>? = null
    private var messages: List<VideoChatMessage>? = null
    private var startTime = 0L
    private val liveList = ArrayDeque<ChatMessage>()
    private val list = ArrayDeque<VideoChatMessage>()
    private var started = false
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun setMessages(newLiveMessages: List<ChatMessage>, newMessages: List<VideoChatMessage>, newStartTime: Long) {
        liveMessages = newLiveMessages.takeIf { it.isNotEmpty() }
        messages = newMessages
        startTime = if (liveMessages != null) {
            createdAt?.let { newStartTime - it } ?: 0L
        } else newStartTime
        if (started) {
            start()
        }
    }

    fun startLoad() {
        if (!started) {
            started = true
            if (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) {
                start()
            }
        }
    }

    fun start() {
        if (!isActive) return
        loadJob?.cancel()
        messageJob?.cancel()
        val currentPosition = getCurrentPosition() ?: 0
        lastCheckedPosition = currentPosition
        playbackSpeed = getCurrentSpeed()
        liveList.clear()
        list.clear()
        coroutineScope.launch {
            listener.clearMessages()
        }
        load(currentPosition + startTime)
    }

    fun stop() {
        loadGeneration++
        loadJob?.cancel()
        messageJob?.cancel()
        isActive = false
    }

    private fun load(position: Long) {
        if (!isActive) return
        loadJob?.cancel()
        messageJob?.cancel()
        val generation = ++loadGeneration
        val liveSnapshot = liveMessages
        val replaySnapshot = messages
        loadJob = coroutineScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val minimumOffset = max(position - 20000, 0)
                val live = liveSnapshot.orEmpty().filter { message ->
                    val offset = if (createdAt != null && message.timestamp != null) message.timestamp - createdAt else null
                    offset != null && offset >= minimumOffset
                }
                val replay = if (liveSnapshot.isNullOrEmpty()) replaySnapshot.orEmpty().filter { message ->
                    val offset = if (createdAt != null && !message.createdAt.isNullOrBlank()) {
                        Instant.parseOrNull(message.createdAt)?.toEpochMilliseconds()?.takeIf { it > 0 }?.minus(createdAt)
                    } else null
                    val messageOffset = offset ?: message.offsetSeconds?.times(1000L)
                    messageOffset != null && messageOffset >= minimumOffset
                } else emptyList()
                live to replay
            }
            if (generation != loadGeneration) return@launch
            liveList.clear()
            list.clear()
            liveList.addAll(filtered.first)
            list.addAll(filtered.second)
            startJob()
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive) {
                if (!liveMessages.isNullOrEmpty()) {
                    val message = liveList.firstOrNull() ?: break
                    val messageOffset = if (createdAt != null && message.timestamp != null) {
                        message.timestamp - createdAt
                    } else {
                        null
                    }
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
                        listener.onChatMessage(message)
                    } else {
                        if (!isActive) {
                            break
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    liveList.removeFirst()
                } else {
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
                    } else {
                        if (!isActive) {
                            break
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    list.removeFirst()
                }
            }
        }
    }

    fun updatePosition(position: Long) {
        if (started && (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) && lastCheckedPosition != position) {
            if (position - lastCheckedPosition !in 0..20000) {
                loadJob?.cancel()
                messageJob?.cancel()
                liveList.clear()
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
        if (started && (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) && playbackSpeed != speed) {
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
}
