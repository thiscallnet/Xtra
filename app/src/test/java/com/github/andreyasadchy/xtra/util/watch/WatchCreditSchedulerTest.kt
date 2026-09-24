package com.github.andreyasadchy.xtra.util.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections

class WatchCreditSchedulerTest {
    @Test
    fun pausePreservesTheRemainingFirstMinuteDelay() {
        val clock = WatchCreditClock(nextSecondsOffset = { 42 })
        val session = session()

        clock.update(input(session), nowElapsedMillis = 0L)
        assertEquals(42_000L, clock.remainingActiveMillis(0L))

        clock.update(input(session, isPlaying = false), nowElapsedMillis = 25_000L)
        assertNull(clock.remainingActiveMillis(25_000L))

        clock.update(input(session), nowElapsedMillis = 100_000L)
        assertNull(clock.takeMinuteIfDue(116_999L))
        assertEquals(WatchCreditMinute(1, 42), clock.takeMinuteIfDue(117_000L))
        assertEquals(60_000L, clock.remainingActiveMillis(117_000L))
    }

    @Test
    fun bufferingFreezesCreditAndEachLaterMinuteUsesSixtyActiveSeconds() {
        val clock = WatchCreditClock(nextSecondsOffset = { 0 })
        val session = session()

        clock.update(input(session), nowElapsedMillis = 0L)
        assertEquals(WatchCreditMinute(1, 0), clock.takeMinuteIfDue(0L))

        clock.update(input(session, isBuffering = true), nowElapsedMillis = 10_000L)
        clock.update(input(session), nowElapsedMillis = 90_000L)
        assertNull(clock.takeMinuteIfDue(139_999L))
        assertEquals(WatchCreditMinute(2, 0), clock.takeMinuteIfDue(140_000L))
    }

    @Test
    fun aNewPlaybackSessionStartsAtMinuteOneWithANewOffset() {
        val offsets = listOf(37, 12).iterator()
        val clock = WatchCreditClock(nextSecondsOffset = { offsets.next() })
        val first = session()

        clock.update(input(first), nowElapsedMillis = 0L)
        assertEquals(WatchCreditMinute(1, 37), clock.takeMinuteIfDue(37_000L))
        assertEquals(WatchCreditMinute(2, 37), clock.takeMinuteIfDue(97_000L))

        val next = first.copy(playbackSessionId = 101L)
        clock.update(input(next), nowElapsedMillis = 97_001L)
        assertEquals(12, clock.secondsOffset)
        assertEquals(0, clock.minutesLogged)
        assertTrue(clock.remainingActiveMillis(97_001L)!! > 0L)
    }

    @Test
    fun vodLiveRewindAndDropsOfflineStatesResetTheClock() {
        val clock = WatchCreditClock(nextSecondsOffset = { 10 })
        val session = session()

        clock.update(input(session), nowElapsedMillis = 0L)
        clock.update(input(session, liveEligible = false), nowElapsedMillis = 5_000L)
        assertNull(clock.session)

        clock.update(input(session, liveEligible = true), nowElapsedMillis = 10_000L)
        clock.update(input(session, dropsStreamLive = false), nowElapsedMillis = 15_000L)
        assertNull(clock.session)

        clock.update(input(session, dropsStreamLive = true), nowElapsedMillis = 20_000L)
        assertEquals(0, clock.minutesLogged)
        assertEquals(10, clock.secondsOffset)
    }

    @Test
    fun aSlowSendDoesNotBlockPauseUpdatesOrShiftTheNextDeadline() = runBlocking {
        val session = session()
        val scheduleInput = MutableStateFlow(input(session))
        val firstRequestStarted = CompletableDeferred<Unit>()
        val firstRequestGate = CompletableDeferred<Unit>()
        val thirdRequestStarted = CompletableDeferred<Unit>()
        val fourthRequestStarted = CompletableDeferred<Unit>()
        val sentMinutes = Collections.synchronizedList(mutableListOf<Int>())
        val scheduler = WatchCreditScheduler(
            scope = this,
            input = scheduleInput,
            onMinuteWatched = { _, minute, _, _ ->
                sentMinutes += minute.minutesLogged
                if (minute.minutesLogged == 1) {
                    firstRequestStarted.complete(Unit)
                    firstRequestGate.await()
                } else if (minute.minutesLogged == 3) {
                    thirdRequestStarted.complete(Unit)
                } else if (minute.minutesLogged == 4) {
                    fourthRequestStarted.complete(Unit)
                }
            },
            nextSecondsOffset = { 0 },
            elapsedRealtime = { System.nanoTime() / 1_000_000L },
            repeatIntervalMillis = 600L,
        ).start()

        try {
            withTimeout(2_000L) { firstRequestStarted.await() }
            delay(1_600L)
            scheduleInput.value = input(session, isPlaying = false)
            delay(800L)
            assertEquals(listOf(1), sentMinutes)

            firstRequestGate.complete(Unit)
            withTimeout(1_000L) { thirdRequestStarted.await() }
            assertEquals(listOf(1, 2, 3), sentMinutes)

            scheduleInput.value = input(session)
            delay(100L)
            assertEquals(listOf(1, 2, 3), sentMinutes)
            withTimeout(250L) { fourthRequestStarted.await() }
            assertEquals(listOf(1, 2, 3, 4), sentMinutes)
        } finally {
            firstRequestGate.complete(Unit)
            scheduler.cancelAndJoin()
        }
    }

    private fun session(playbackSessionId: Long = 100L) = WatchCreditSession(
        broadcastId = "broadcast-1",
        channelId = "channel-1",
        channelLogin = "channel",
        userId = "user-1",
        playbackSessionId = playbackSessionId,
    )

    private fun input(
        session: WatchCreditSession,
        liveEligible: Boolean = true,
        dropsStreamLive: Boolean? = true,
        isPlaying: Boolean = true,
        isBuffering: Boolean = false,
    ) = WatchCreditScheduleInput(
        session = session,
        liveEligible = liveEligible,
        dropsStreamLive = dropsStreamLive,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
    )
}
