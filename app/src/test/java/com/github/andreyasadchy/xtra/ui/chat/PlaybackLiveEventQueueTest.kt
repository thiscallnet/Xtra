package com.github.andreyasadchy.xtra.ui.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackLiveEventQueueTest {
    @Test
    fun buffersStoppedEventsInOrderAndDoesNotReplayConsumedEvents() = runBlocking {
        val queue = PlaybackLiveEventQueue()
        val expected = listOf(
            PlaybackLiveEvent(sequence = 1, live = true, serverTime = 100L),
            PlaybackLiveEvent(sequence = 2, live = false, serverTime = null),
            PlaybackLiveEvent(sequence = 3, live = true, serverTime = 200L),
        )

        expected.forEach { queue.send(it) }

        assertEquals(expected, queue.events.take(expected.size).toList())
        assertNull(withTimeoutOrNull(25L) { queue.events.first() })
    }
}
