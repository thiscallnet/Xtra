package com.github.andreyasadchy.xtra.ui.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal data class PlaybackLiveEvent(
    val sequence: Long,
    val live: Boolean,
    val serverTime: Long?,
)

/** Buffers live status transitions until the single active chat collector can consume them. */
internal class PlaybackLiveEventQueue {
    private val channel = Channel<PlaybackLiveEvent>(Channel.BUFFERED)
    val events: Flow<PlaybackLiveEvent> = channel.receiveAsFlow()

    suspend fun send(event: PlaybackLiveEvent) = channel.send(event)
}
