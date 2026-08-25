package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.util.chat.ChannelPointsBalanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPointsBalanceReducerTest {
    private val reducer = ChannelPointsBalanceReducer()

    @Test
    fun liveEarnChangesBalanceBeforeGqlAndStaleSnapshotCannotRollItBack() {
        val live = reducer.applyLiveEvent(
            ChannelPointsBalanceReducer.State(balance = 1_000),
            ChannelPointsBalanceEvent(
                channelId = "channel-100",
                type = ChannelPointsBalanceEvent.Type.EARNED,
                delta = 300,
                messageId = "earn-confirmation",
            ),
            nowMs = 1_000L,
        )

        assertEquals(1_300, live.balance)
        assertTrue(live.pendingAdjustments.isNotEmpty())
        val stale = reducer.applySnapshot(live, snapshotBalance = 1_000, nowMs = 1_100L)
        assertEquals(1_300, stale.balance)

        val fresh = reducer.applySnapshot(stale, snapshotBalance = 1_300, nowMs = 1_200L)
        assertEquals(1_300, fresh.balance)
        assertTrue(fresh.pendingAdjustments.isNotEmpty())

        val confirmed = reducer.applyLiveEvent(
            fresh,
            ChannelPointsBalanceEvent(
                channelId = "channel-100",
                type = ChannelPointsBalanceEvent.Type.EARNED,
                delta = 300,
                messageId = "earn-1",
            ),
            nowMs = 1_300L,
        )
        assertEquals(1_300, confirmed.balance)
        assertTrue(confirmed.pendingAdjustments.isEmpty())
    }

    @Test
    fun localSpendIsImmediateAndHermesConfirmationIsNotSubtractedTwice() {
        val local = reducer.applyLocalSpend(
            state = ChannelPointsBalanceReducer.State(balance = 1_000),
            channelId = "channel-100",
            amount = 500,
            nowMs = 2_000L,
            transactionId = "redemption-1",
        )
        assertEquals(500, local.balance)

        val snapshotConfirmed = reducer.applySnapshot(local, snapshotBalance = 500, nowMs = 2_050L)
        val confirmed = reducer.applyLiveEvent(
            snapshotConfirmed,
            ChannelPointsBalanceEvent(
                channelId = "channel-100",
                type = ChannelPointsBalanceEvent.Type.SPENT,
                delta = 500,
                messageId = "spend-1",
            ),
            nowMs = 2_100L,
        )
        assertEquals(500, confirmed.balance)
        assertTrue(confirmed.pendingAdjustments.isEmpty())
    }

    @Test
    fun duplicateMessageAndAbsoluteBalanceAreIdempotent() {
        val event = ChannelPointsBalanceEvent(
            channelId = "channel-100",
            type = ChannelPointsBalanceEvent.Type.EARNED,
            delta = 10,
            messageId = "earn-1",
        )
        val first = reducer.applyLiveEvent(
            ChannelPointsBalanceReducer.State(balance = 100),
            event,
            nowMs = 3_000L,
        )
        val duplicate = reducer.applyLiveEvent(first, event, nowMs = 3_100L)
        assertEquals(first, duplicate)

        val authoritative = reducer.applyLiveEvent(
            duplicate,
            event.copy(absoluteBalance = 999, messageId = "earn-2"),
            nowMs = 3_200L,
        )
        assertEquals(999, authoritative.balance)
        assertTrue(authoritative.pendingAdjustments.isEmpty())
    }
}
