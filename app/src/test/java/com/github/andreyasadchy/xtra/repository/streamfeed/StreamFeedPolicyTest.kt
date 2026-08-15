package com.github.andreyasadchy.xtra.repository.streamfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class StreamFeedPolicyTest {

    @Test
    fun cacheIsFreshBelowTtlAndStaleAtTtl() {
        val now = 1_000_000L
        assertTrue(StreamFeedFreshnessPolicy.isFresh(now, now - StreamFeedFreshnessPolicy.LIVE_STREAM_SOFT_TTL_MS + 1))
        assertFalse(StreamFeedFreshnessPolicy.isFresh(now, now - StreamFeedFreshnessPolicy.LIVE_STREAM_SOFT_TTL_MS))
    }

    @Test
    fun futureClockDoesNotProduceNegativeAge() {
        assertTrue(StreamFeedFreshnessPolicy.isFresh(1_000L, 2_000L))
        assertEquals(0L, StreamFeedFreshnessPolicy.cacheAge(1_000L, 2_000L))
    }

    @Test
    fun playbackThresholdRequiresMeaningfulViewing() {
        assertFalse(StreamFeedFreshnessPolicy.playbackWasMeaningful(StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS - 1))
        assertTrue(StreamFeedFreshnessPolicy.playbackWasMeaningful(StreamFeedFreshnessPolicy.PLAYBACK_RETURN_THRESHOLD_MS))
    }

    @Test
    fun prewarmDelayUsesDefaultAndClamps() {
        assertEquals(
            StreamFeedFreshnessPolicy.PREWARM_MIN_DELAY_MS,
            StreamFeedFreshnessPolicy.prewarmDelayMs(1L),
        )
        assertEquals(
            StreamFeedFreshnessPolicy.PREWARM_MAX_DELAY_MS,
            StreamFeedFreshnessPolicy.prewarmDelayMs(Long.MAX_VALUE),
        )
    }

    @Test
    fun manualRefreshBypassesTtlButBackoffStillWins() {
        val now = 1_000_000L
        assertEquals(
            RefreshDecision.REFRESH,
            refreshDecision(now, now, now, null, null, force = true),
        )
        assertEquals(
            RefreshDecision.SKIP_BACKOFF,
            refreshDecision(now, now - 1_000_000L, now - 1_000_000L, null, now + 1, force = true),
        )
    }

    @Test
    fun prewarmOnlyRunsForStaleBackgroundCache() {
        assertFalse(shouldPrewarm(isForeground = true, cacheFresh = false))
        assertFalse(shouldPrewarm(isForeground = false, cacheFresh = true))
        assertTrue(shouldPrewarm(isForeground = false, cacheFresh = false))
    }

    @Test
    fun concurrentTriggersUseOneSingleFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = StreamFeedSingleFlight<Int>(scope)
        val started = CompletableDeferred<Unit>()
        val executions = AtomicInteger()

        val first = async {
            singleFlight.run("top:test") {
                executions.incrementAndGet()
                started.complete(Unit)
                delay(100)
                7
            }
        }
        started.await()
        val second = async { singleFlight.run("top:test") { error("must join") } }

        assertEquals(false, first.await().joined)
        assertEquals(true, second.await().joined)
        assertEquals(1, executions.get())
        scope.cancel()
    }
}
