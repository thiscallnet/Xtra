package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatAdapterTest {

    @Test
    fun renderCacheMissCannotBecomeBindable() {
        assertThrows(IllegalStateException::class.java) {
            requireReadyRender(null)
        }
    }

    @Test
    fun onlyContiguousReadyMessagesCanBePublished() {
        assertEquals(listOf("A"), contiguousReadyPrefix(listOf("A", "B", "C"), setOf("A", "C")))
        assertEquals(listOf("A", "B", "C"), contiguousReadyPrefix(listOf("A", "B", "C"), setOf("A", "B", "C")))
    }

    @Test
    fun contentUpdatesUsePublishedIdentityPositionAfterLogicalTrim() {
        val a = Any()
        val b = Any()
        val c = Any()
        val d = Any()
        val e = Any()
        val f = Any()
        val visible = listOf(a, b, c, d, e)

        assertEquals(2, identityPosition(visible, c))
        assertEquals(-1, identityPosition(visible, f))
    }

    @Test
    fun staleRenderRequestCannotPromoteWithoutCurrentKeyCache() {
        assertEquals(false, renderRequestCanBecomeReady("K0", "K1", true))
        assertEquals(false, renderRequestCanBecomeReady("K0", "K0", false))
        assertEquals(true, renderRequestCanBecomeReady("K1", "K1", true))
    }

    @Test
    fun terminalAppendAppliesTrimOnlyAtPublicationAndKeepsDroppedEntriesOrdered() {
        val visible = (1..600).toMutableList()

        val publication = applyTerminalAppend(
            visible,
            listOf(
                TerminalAppendEntry(601, ready = false, trimBeforePublish = 1),
                TerminalAppendEntry(602, ready = true),
            ),
        )

        assertEquals(1, publication.removed)
        assertEquals(listOf(602), publication.inserted)
        assertEquals(600, visible.size)
        assertEquals(2, visible.first())
        assertEquals(602, visible.last())
    }

    @Test
    fun terminalAppendAggregatesTrimAcrossCoalescedPublication() {
        val visible = (1..5).toMutableList()

        applyTerminalAppend(
            visible,
            listOf(
                TerminalAppendEntry(6, ready = true, trimBeforePublish = 1),
                TerminalAppendEntry(7, ready = true, trimBeforePublish = 1),
            ),
        )

        assertEquals(listOf(3, 4, 5, 6, 7), visible)
    }

    @Test
    fun stagedHistoryPublishesBeforeLaterTrim() {
        val visible = mutableListOf<Int>()

        applyTerminalAppend(
            visible,
            (1..600).map { TerminalAppendEntry(it, ready = true) },
        )
        applyTerminalAppend(
            visible,
            listOf(TerminalAppendEntry(601, ready = true, trimBeforePublish = 1)),
        )

        assertEquals(600, visible.size)
        assertEquals(2, visible.first())
        assertEquals(601, visible.last())
    }

    @Test
    fun pendingPublicationsIsGlobalAcrossAllPublicationTypes() {
        assertEquals(true, hasPendingPublications(true, false, false))
        assertEquals(true, hasPendingPublications(false, true, false))
        assertEquals(true, hasPendingPublications(false, false, true))
        assertEquals(false, hasPendingPublications(false, false, false))
    }

    @Test
    fun prependBarrierPreventsTrimFromUsingTheWrongVisibleHistory() {
        val queue = ChatPublicationQueue<Int>()
        val prepend = (1..100).map { PublicationEntry(it, 0, state = PublicationState.READY) }
        val append = PublicationEntry(601, 0, trimBeforePublish = 1, state = PublicationState.READY)

        queue.enqueuePrepend(prepend)
        queue.enqueueAppend(listOf(append))

        assertNull(queue.takeReadyAppendSegment())
        assertEquals((1..100).toList(), queue.takeReadyPrepend()!!.map { it.value })
        assertEquals(listOf(append), queue.takeReadyAppendSegment())
    }

    @Test
    fun prependThenTrimmedAppendProducesTheAuthoritativeLogicalHistory() {
        val queue = ChatPublicationQueue<Int>()
        val visible = (101..600).toMutableList()
        val prepend = (1..100).map { PublicationEntry(it, 0, state = PublicationState.READY) }
        val append = PublicationEntry(601, 0, trimBeforePublish = 1, state = PublicationState.READY)
        queue.enqueuePrepend(prepend)
        queue.enqueueAppend(listOf(append))

        val history = queue.takeReadyPrepend()!!.map { it.value }
        visible.addAll(0, history)
        val live = queue.takeReadyAppendSegment()!!
        applyTerminalAppend(
            visible,
            live.map { TerminalAppendEntry(it.value, it.state == PublicationState.READY, it.trimBeforePublish) },
        )

        assertEquals((2..601).toList(), visible)
    }

    @Test
    fun multiplePrependsRemainAheadOfAllAppends() {
        val queue = ChatPublicationQueue<Int>()
        val first = listOf(PublicationEntry(1, 0, state = PublicationState.READY))
        val second = listOf(PublicationEntry(2, 0, state = PublicationState.READY))
        val append = PublicationEntry(3, 0, state = PublicationState.READY)
        queue.enqueuePrepend(first)
        queue.enqueuePrepend(second)
        queue.enqueueAppend(listOf(append))

        assertEquals(listOf(1), queue.takeReadyPrepend()!!.map { it.value })
        assertNull(queue.takeReadyAppendSegment())
        assertEquals(listOf(2), queue.takeReadyPrepend()!!.map { it.value })
        assertEquals(listOf(append), queue.takeReadyAppendSegment())
    }

    @Test
    fun replacementClearsOldStagedMutationsButKeepsLaterAppendBehindIt() {
        val queue = ChatPublicationQueue<Int>()
        val oldAppend = PublicationEntry(100, 0, state = PublicationState.READY)
        val replacement = PublicationEntry(200, 1, state = PublicationState.READY)
        val laterAppend = PublicationEntry(201, 1, state = PublicationState.READY)
        queue.enqueueAppend(listOf(oldAppend))
        queue.beginReplacement(listOf(replacement))
        queue.enqueueAppend(listOf(laterAppend))

        assertNull(queue.takeReadyAppendSegment())
        assertEquals(listOf(200), queue.takeReadyReplacement()!!.map { it.value })
        assertEquals(listOf(201), queue.takeReadyAppendSegment()!!.map { it.value })
    }

    @Test
    fun replacementPrependAppendChainRemainsOrdered() {
        val queue = ChatPublicationQueue<Int>()
        val replacement = PublicationEntry(100, 1, state = PublicationState.READY)
        val prepend = listOf(PublicationEntry(99, 1, state = PublicationState.READY))
        val append = PublicationEntry(101, 1, state = PublicationState.READY)
        queue.beginReplacement(listOf(replacement))
        queue.enqueuePrepend(prepend)
        queue.enqueueAppend(listOf(append))

        assertNull(queue.takeReadyAppendSegment())
        assertNull(queue.takeReadyPrepend())
        assertEquals(listOf(100), queue.takeReadyReplacement()!!.map { it.value })
        assertEquals(listOf(99), queue.takeReadyPrepend()!!.map { it.value })
        assertEquals(listOf(101), queue.takeReadyAppendSegment()!!.map { it.value })
    }

    @Test
    fun clearCancelsAllStagedPublicationTypes() {
        val queue = ChatPublicationQueue<Int>()
        queue.enqueuePrepend(listOf(PublicationEntry(1, 0)))
        queue.enqueueAppend(listOf(PublicationEntry(2, 0)))
        queue.beginReplacement(listOf(PublicationEntry(3, 1)))
        queue.clear()

        assertEquals(false, queue.hasPendingPublications())
        assertNull(queue.takeReadyReplacement())
        assertNull(queue.takeReadyPrepend())
        assertNull(queue.takeReadyAppendSegment())
    }

    @Test
    fun trimSkipsSyntheticDivider() {
        val divider = ChatMessage(type = ChatMessage.NEW_MESSAGE_DIVIDER)
        assertEquals(2, adapterRowsToRemoveForTrim(listOf(divider, ChatMessage(), ChatMessage()), 1))
    }

    @Test
    fun dividerReconstructionRequiresUnconsumedSnapshotBoundary() {
        assertEquals(true, shouldReconstructNewMessageDivider(3, false))
        assertEquals(false, shouldReconstructNewMessageDivider(3, true))
        assertEquals(false, shouldReconstructNewMessageDivider(null, false))
    }

}
