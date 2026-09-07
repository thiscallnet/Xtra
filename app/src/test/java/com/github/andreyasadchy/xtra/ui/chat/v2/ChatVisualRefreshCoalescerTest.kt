package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatVisualRefreshCoalescer
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatVisualRefreshKind
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatObserverRegistration
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVisualRefreshCoalescerTest {
    @Test
    fun severalAssetCompletionsBeforeAFrameScheduleOneRefresh() {
        val scheduled = ArrayDeque<Runnable>()
        val refreshes = mutableListOf<ChatVisualRefreshKind>()
        var coalesced = 0
        var generation = 7L
        val coalescer = ChatVisualRefreshCoalescer(
            scheduleFrame = scheduled::add,
            currentGeneration = { generation },
            onRefresh = refreshes::add,
            onCoalesced = { coalesced++ },
        )

        coalescer.request(ChatVisualRefreshKind.DRAW, generation)
        coalescer.request(ChatVisualRefreshKind.DRAW, generation)
        coalescer.request(ChatVisualRefreshKind.LAYOUT_AND_DRAW, generation)

        assertEquals(1, scheduled.size)
        assertEquals(2, coalesced)
        scheduled.removeFirst().run()
        assertEquals(listOf(ChatVisualRefreshKind.LAYOUT_AND_DRAW), refreshes)
    }

    @Test
    fun drawOnlyAssetDoesNotBecomeLayoutRefresh() {
        val scheduled = ArrayDeque<Runnable>()
        val refreshes = mutableListOf<ChatVisualRefreshKind>()
        val coalescer = ChatVisualRefreshCoalescer(
            scheduleFrame = scheduled::add,
            currentGeneration = { 1L },
            onRefresh = refreshes::add,
        )

        coalescer.request(ChatVisualRefreshKind.DRAW, 1L)
        scheduled.removeFirst().run()

        assertEquals(listOf(ChatVisualRefreshKind.DRAW), refreshes)
    }

    @Test
    fun requestDoesNotReadBindingGenerationUntilTheUiFrame() {
        val scheduled = ArrayDeque<Runnable>()
        var generationReads = 0
        val coalescer = ChatVisualRefreshCoalescer(
            scheduleFrame = scheduled::add,
            currentGeneration = {
                generationReads++
                1L
            },
            onRefresh = {},
        )

        coalescer.request(ChatVisualRefreshKind.DRAW, 1L)

        assertEquals(0, generationReads)
        scheduled.removeFirst().run()
        assertEquals(1, generationReads)
    }

    @Test
    fun callbackFromRecycledGenerationDoesNoWork() {
        val scheduled = ArrayDeque<Runnable>()
        val refreshes = mutableListOf<ChatVisualRefreshKind>()
        var generation = 1L
        var stale = 0
        val coalescer = ChatVisualRefreshCoalescer(
            scheduleFrame = scheduled::add,
            currentGeneration = { generation },
            onRefresh = refreshes::add,
            onStale = { stale++ },
        )

        coalescer.request(ChatVisualRefreshKind.DRAW, generation)
        generation = 2L
        scheduled.removeFirst().run()

        assertTrue(refreshes.isEmpty())
        assertEquals(1, stale)
    }

    @Test
    fun retainedAssetObserverUsesTheCurrentGenerationAfterRebind() {
        val scheduled = ArrayDeque<Runnable>()
        val refreshes = mutableListOf<ChatVisualRefreshKind>()
        var generation = 10L
        var stale = 0
        val coalescer = ChatVisualRefreshCoalescer(
            scheduleFrame = scheduled::add,
            currentGeneration = { generation },
            onRefresh = refreshes::add,
        )
        val registration = ChatObserverRegistration(
            initialGeneration = generation,
            onValid = { callbackGeneration ->
                coalescer.request(ChatVisualRefreshKind.DRAW, callbackGeneration)
            },
            onStale = { stale++ },
        )

        generation = 11L
        registration.rebind(generation)
        registration.listener()
        scheduled.removeFirst().run()

        assertEquals(listOf(ChatVisualRefreshKind.DRAW), refreshes)
        assertEquals(0, stale)
    }

    @Test
    fun removedAssetObserverDoesNotRefreshTheReboundRow() {
        val refreshes = mutableListOf<Long>()
        var stale = 0
        val registration = ChatObserverRegistration(
            initialGeneration = 10L,
            onValid = refreshes::add,
            onStale = { stale++ },
        )

        registration.deactivate()
        registration.listener()

        assertTrue(refreshes.isEmpty())
        assertEquals(1, stale)
    }

    @Test
    fun retainedStagedObserverUsesTheCurrentGenerationAfterRebind() {
        val refreshes = mutableListOf<Long>()
        var stale = 0
        val registration = ChatObserverRegistration(
            initialGeneration = 20L,
            onValid = refreshes::add,
            onStale = { stale++ },
        )

        registration.rebind(21L)
        registration.listener()

        assertEquals(listOf(21L), refreshes)
        assertEquals(0, stale)
    }

    @Test
    fun stagedObserverCanBeReattachedWithoutLosingItsBindingGeneration() {
        val refreshes = mutableListOf<Long>()
        var stale = 0
        val detached = ChatObserverRegistration(
            initialGeneration = 30L,
            onValid = refreshes::add,
            onStale = { stale++ },
        )
        detached.deactivate()
        val reattached = ChatObserverRegistration(
            initialGeneration = 30L,
            onValid = refreshes::add,
            onStale = { stale++ },
        )

        // Detach removes the old repository registration. Reattach creates a new one at the
        // unchanged binding generation, so the staged row remains eligible for application.
        detached.listener()
        reattached.listener()

        assertEquals(listOf(30L), refreshes)
        assertEquals(1, stale)
    }
}
