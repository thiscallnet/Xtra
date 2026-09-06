package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.repository.ViewingStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewingStatsRecorderTest {

    private lateinit var store: FakeViewingStatsStore
    private lateinit var clock: FakeViewingStatsClock
    private lateinit var scope: CoroutineScope
    private lateinit var recorder: ViewingStatsRecorder

    @Before
    fun setUp() {
        store = FakeViewingStatsStore()
        clock = FakeViewingStatsClock()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        recorder = ViewingStatsRecorder(
            repository = store,
            clock = clock,
            checkpointIntervalMs = 60_000L,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        recorder.close()
        scope.cancel()
    }

    @Test
    fun tenMinutesGenuinelyPlayingIsRecorded() = runBlocking {
        recorder.update("player", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(10.minutes)
        recorder.update("player", metadata("channel-a"), true, false)
        recorder.release("player")
        recorder.awaitIdle()

        assertEquals(10.minutes, store.intervals.sumOf { it.watchedMs })
    }

    @Test
    fun pausedAndBufferingTimeIsExcluded() = runBlocking {
        recorder.update("player", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(5.minutes)
        recorder.update("player", metadata("channel-a"), false, false)
        clock.advance(10.minutes)
        recorder.update("player", metadata("channel-a"), false, true)
        clock.advance(5.minutes)
        recorder.update("player", metadata("channel-a"), true, false)
        clock.advance(5.minutes)
        recorder.release("player")
        recorder.awaitIdle()

        assertEquals(10.minutes, store.intervals.sumOf { it.watchedMs })
        assertEquals(1, store.sessions.size)
    }

    @Test
    fun backgroundPlaybackUsesTheSameServiceLevelClock() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(12.minutes)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(12.minutes, store.intervals.sumOf { it.watchedMs })
    }

    @Test
    fun explicitStatsPageFlushPersistsAnActiveShortFirstSession() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(30.seconds)

        recorder.flush()

        assertEquals(30.seconds, store.intervals.single().watchedMs)
        assertEquals(30.seconds, store.sessions.single().watchedMs)
    }

    @Test
    fun immediateStopPersistsBeforeThePeriodicCheckpoint() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(30.seconds)

        recorder.update("service", metadata("channel-a"), false, false)
        recorder.awaitIdle()

        assertEquals(30.seconds, store.intervals.single().watchedMs)
    }

    @Test
    fun repeatedCurrentStateUpdatesCoalesceIntoOneCheckpoint() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        repeat(100) {
            recorder.update("service", metadata("channel-a"), true, false)
        }
        clock.advance(100.seconds)
        // Capture the current reading once after the time advance. The
        // preceding identical updates are coalesced, so they intentionally
        // retain the old reading and cannot account for the elapsed time.
        recorder.update("service", metadata("channel-a"), true, false)

        recorder.awaitIdle()

        assertEquals(1, store.checkpointCalls)
        assertEquals(100.seconds, store.intervals.single().watchedMs)
    }

    @Test
    fun simultaneousSourcesShareOneCheckpointPass() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            checkpointIntervalMs = 1.hours,
            scope = localScope,
        )
        try {
            localRecorder.update("source-a", metadata("channel-a"), true, false)
            localRecorder.update("source-b", metadata("channel-b"), true, false)
            localRecorder.awaitIdle()
            clock.advance(1.minutes)

            localRecorder.flush()

            assertEquals(1, localStore.checkpointCalls)
            assertEquals(2.minutes, localStore.intervals.sumOf { it.watchedMs })
            assertEquals(setOf("channel-a", "channel-b"), localStore.intervals.map { it.channelId }.toSet())
        } finally {
            localRecorder.close()
            delay(20L)
            localScope.cancel()
        }
    }

    @Test
    fun semanticTransitionsAndStopSurviveAFullBoundedCommandPath() = runBlocking {
        store.writeDelayMs = 1L
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()

        repeat(150) { index ->
            clock.advance(1.milliseconds)
            recorder.update(
                sourceId = "service",
                metadata = metadata(if (index % 2 == 0) "channel-b" else "channel-a"),
                isPlaying = true,
                isBuffering = false,
            )
        }
        clock.advance(1.milliseconds)
        recorder.release("service")
        recorder.awaitIdle()

        assertTrue(store.intervals.isNotEmpty())
        assertEquals(151.milliseconds, store.intervals.sumOf { it.watchedMs })
        assertEquals(
            buildList {
                add("channel-a")
                repeat(150) { index ->
                    add(if (index % 2 == 0) "channel-b" else "channel-a")
                }
            },
            store.intervals.map { it.channelId },
        )
    }

    @Test
    fun semanticIngressHasBoundedPendingWorkWhenPersistenceStalls() = runBlocking {
        val localStore = FakeViewingStatsStore().also { it.writeDelayMs = 8L }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            scope = localScope,
        )
        try {
            val producers = List(4) { producer ->
                launch(Dispatchers.Default) {
                    repeat(40) { update ->
                        localRecorder.update(
                            sourceId = "source-$producer",
                            metadata = metadata("channel-${update % 2}"),
                            isPlaying = true,
                            isBuffering = false,
                        )
                    }
                }
            }
            delay(40L)

            assertTrue(localRecorder.pendingSemanticWorkForTest() <= 129)

            producers.joinAll()
            repeat(4) { localRecorder.release("source-$it") }
            localRecorder.awaitIdle()

            assertTrue(
                "max pending: ${localRecorder.maxPendingSemanticWorkForTest()}",
                localRecorder.maxPendingSemanticWorkForTest() <= 129,
            )
        } finally {
            localRecorder.close()
            delay(40L)
            localScope.cancel()
        }
    }

    @Test
    fun noActiveSourcesMeansNoPeriodicPersistence() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            checkpointIntervalMs = 10L,
            scope = localScope,
        )
        try {
            localRecorder.update("service", metadata("channel-a"), false, false)
            localRecorder.awaitIdle()
            clock.advance(10.minutes)
            delay(80L)

            assertEquals(0, localStore.checkpointCalls)
        } finally {
            localRecorder.close()
            delay(20L)
            localScope.cancel()
        }
    }

    @Test
    fun twoMinuteCadenceIsUsedForCheckpointEligibility() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            scope = localScope,
        )
        try {
            localRecorder.update("service", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()
            clock.advance(119.seconds)
            localRecorder.update("service", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()
            assertEquals(0, localStore.checkpointCalls)

            clock.advance(1.seconds)
            localRecorder.update("service", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()

            assertEquals(1, localStore.checkpointCalls)
            assertEquals(120.seconds, localStore.intervals.single().watchedMs)
        } finally {
            localRecorder.close()
            delay(20L)
            localScope.cancel()
        }
    }

    @Test
    fun sourceChurnDoesNotDelayAnotherSourcesCheckpointDeadline() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            checkpointIntervalMs = 5_000L,
            scope = localScope,
        )
        try {
            localRecorder.update("source-a", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()
            // Let the scheduler establish A's deadline without waiting for it
            // in real time. The test advances only the fake monotonic clock.
            delay(100L)

            // These B transitions continually change the active-source set.
            // A's deadline must remain anchored to its own start instead of
            // restarting after every B transition.
            repeat(8) {
                localRecorder.update("source-b", metadata("channel-b"), true, false)
                localRecorder.update("source-b", metadata("channel-b"), false, false)
            }
            clock.advance(5.seconds)
            // This source change wakes the scheduler exactly at A's original
            // deadline. It must emit the overdue timer rather than move the
            // deadline another 5 seconds into the future.
            localRecorder.update("source-b", metadata("channel-b"), true, false)
            localRecorder.awaitIdle()
            withTimeout(1.seconds) {
                while (localStore.checkpointBatches.none { "channel-a" in it }) {
                    delay(10L)
                }
            }

            assertTrue(
                "A was never included in a timer checkpoint: ${localStore.checkpointBatches}",
                localStore.checkpointBatches.any { "channel-a" in it },
            )
        } finally {
            localRecorder.close()
            delay(40L)
            localScope.cancel()
        }
    }

    @Test
    fun sharedTimerCheckpointsSourcesThatStartAfterThePreviousTick() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            checkpointIntervalMs = 40L,
            scope = localScope,
        )
        try {
            localRecorder.update("source-a", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()
            delay(20L)
            clock.advance(40.milliseconds)
            delay(60L)

            localRecorder.update("source-b", metadata("channel-b"), true, false)
            localRecorder.awaitIdle()
            clock.advance(39.milliseconds)
            delay(60L)

            assertTrue(
                "B was not included in the next shared timer checkpoint: ${localStore.checkpointBatches}",
                localStore.checkpointBatches.any { "channel-b" in it },
            )
        } finally {
            localRecorder.close()
            delay(40L)
            localScope.cancel()
        }
    }

    @Test
    fun activeSourcesUseTheConfiguredSharedCadence() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            checkpointIntervalMs = 25L,
            scope = localScope,
        )
        try {
            localRecorder.update("service", metadata("channel-a"), true, false)
            localRecorder.awaitIdle()
            delay(20L)
            clock.advance(25.milliseconds)
            delay(100L)
            assertTrue(localStore.checkpointCalls >= 1)

            val checkpointsAfterFirstTimer = localStore.checkpointCalls
            clock.advance(25.milliseconds)
            delay(100L)
            assertTrue(localStore.checkpointCalls > checkpointsAfterFirstTimer)
        } finally {
            localRecorder.close()
            delay(20L)
            localScope.cancel()
        }
    }

    @Test
    fun recorderCloseFinishesTheOpenInterval() = runBlocking {
        val localStore = FakeViewingStatsStore()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localRecorder = ViewingStatsRecorder(
            repository = localStore,
            clock = clock,
            scope = localScope,
        )
        localRecorder.update("service", metadata("channel-a"), true, false)
        localRecorder.awaitIdle()
        clock.advance(30.seconds)

        localRecorder.close()
        delay(80L)

        assertEquals(30.seconds, localStore.intervals.single().watchedMs)
        localScope.cancel()
    }

    @Test
    fun checkpointFailureDoesNotLoseTheFollowingStop() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(30.seconds)
        store.failNextCheckpoint = true

        recorder.flush()
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(30.seconds, store.intervals.single().watchedMs)
    }

    @Test
    fun semanticStopRetriesAfterItsPersistenceFails() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(30.seconds)
        store.failNextCheckpoint = true

        recorder.update("service", metadata("channel-a"), false, false)
        recorder.awaitIdle()

        assertEquals(30.seconds, store.intervals.single().watchedMs)
    }

    @Test
    fun repeatedUpdatesFromFragmentRecreationDoNotDuplicateTime() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(8.minutes)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(8.minutes, store.intervals.sumOf { it.watchedMs })
        assertEquals(1, store.sessions.size)
    }

    @Test
    fun switchingChannelClosesOneSessionAndStartsAnother() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(5.minutes)
        recorder.update("service", metadata("channel-b"), true, false)
        clock.advance(7.minutes)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(2, store.sessions.size)
        assertEquals(5.minutes, store.intervals.filter { it.channelId == "channel-a" }.sumOf { it.watchedMs })
        assertEquals(7.minutes, store.intervals.filter { it.channelId == "channel-b" }.sumOf { it.watchedMs })
    }

    @Test
    fun switchingVodContentStartsANewSession() = runBlocking {
        recorder.update("service", metadata("channel-a", "vod-1"), true, false)
        recorder.awaitIdle()
        clock.advance(4.minutes)
        recorder.update("service", metadata("channel-a", "vod-2"), true, false)
        clock.advance(6.minutes)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(2, store.sessions.size)
        assertEquals(setOf("vod-1", "vod-2"), store.sessions.map { it.contentId }.toSet())
    }

    @Test
    fun switchingCategorySplitsIntervalsButKeepsTheViewingSession() = runBlocking {
        recorder.update("service", metadata("channel-a").copy(categoryId = "1", categoryName = "League of Legends"), true, false)
        recorder.awaitIdle()
        clock.advance(2.minutes)
        recorder.update("service", metadata("channel-a").copy(categoryId = "2", categoryName = "Just Chatting"), true, false)
        clock.advance(3.minutes)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(1, store.sessions.size)
        assertEquals(2, store.intervals.size)
        assertEquals(2.minutes, store.intervals.first { it.categoryId == "1" }.watchedMs)
        assertEquals(3.minutes, store.intervals.first { it.categoryId == "2" }.watchedMs)
        assertEquals(store.intervals.map { it.sessionId }.toSet().single(), store.sessions.single().id)
    }

    @Test
    fun resetWhilePlayingStartsAZeroBasedSession() = runBlocking {
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.awaitIdle()
        clock.advance(5.minutes)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.reset()
        recorder.awaitIdle()
        clock.advance(3.minutes)
        recorder.update("service", metadata("channel-a"), true, false)
        recorder.release("service")
        recorder.awaitIdle()

        assertEquals(1, store.sessions.size)
        assertEquals(3.minutes, store.intervals.sumOf { it.watchedMs })
        assertTrue(store.sessions.single().startedAt >= clock.wallTime - 3.minutes)
    }

    private fun metadata(channelId: String, contentId: String? = "live-stream") = ViewingPlaybackMetadata(
        channelId = channelId,
        channelLogin = channelId,
        channelName = channelId,
        channelImage = null,
        contentType = if (contentId?.startsWith("vod") == true) ViewingPlaybackMetadata.CONTENT_TYPE_VOD else ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
        contentId = contentId,
    )

    private class FakeViewingStatsClock(
        var elapsed: Long = 0L,
        var wallTime: Long = 1_704_067_200_000L,
    ) : ViewingStatsClock {
        override fun elapsedRealtime(): Long = elapsed
        override fun currentTimeMillis(): Long = wallTime

        fun advance(milliseconds: Long) {
            elapsed += milliseconds
            wallTime += milliseconds
        }
    }

    private class FakeViewingStatsStore : ViewingStatsStore {
        private var nextId = 1L
        var checkpointCalls = 0
        var failNextCheckpoint = false
        var writeDelayMs = 0L
        val sessions = mutableListOf<ViewingSession>()
        val intervals = mutableListOf<ViewingInterval>()
        val checkpointBatches = mutableListOf<List<String>>()

        override suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long {
            delayIfNeeded()
            val id = nextId++
            sessions += ViewingSession(
                id = id,
                channelId = metadata.normalizedChannelId!!,
                channelLogin = metadata.channelLogin,
                channelName = metadata.channelName,
                channelImage = metadata.channelImage,
                contentType = metadata.contentType,
                contentId = metadata.contentId,
                startedAt = startedAt,
                endedAt = startedAt,
                watchedMs = 0L,
                lastCheckpointAt = startedAt,
            )
            return id
        }

        override suspend fun updateSession(session: ViewingSession) {
            delayIfNeeded()
            sessions.replaceById(session.id, session)
        }

        override suspend fun insertInterval(metadata: ViewingPlaybackMetadata, sessionId: Long, startAt: Long): Long {
            delayIfNeeded()
            val id = nextId++
            intervals += ViewingInterval(
                id = id,
                sessionId = sessionId,
                channelId = metadata.normalizedChannelId!!,
                channelLogin = metadata.channelLogin,
                channelName = metadata.channelName,
                channelImage = metadata.channelImage,
                categoryId = metadata.categoryId,
                categoryName = metadata.categoryName,
                categoryImage = metadata.categoryImage,
                contentType = metadata.contentType,
                contentId = metadata.contentId,
                streamTitle = metadata.title,
                startAt = startAt,
                endAt = startAt,
                watchedMs = 0L,
                lastCheckpointAt = startAt,
            )
            return id
        }

        override suspend fun updateInterval(interval: ViewingInterval) {
            delayIfNeeded()
            intervals.replaceById(interval.id, interval)
        }

        override suspend fun updateCheckpoints(
            intervals: List<ViewingInterval>,
            sessions: List<ViewingSession>,
        ) {
            checkpointCalls++
            checkpointBatches += intervals.map { it.channelId }
            if (failNextCheckpoint) {
                failNextCheckpoint = false
                throw IllegalStateException("injected checkpoint failure")
            }
            intervals.forEach { updateInterval(it) }
            sessions.forEach { updateSession(it) }
        }

        override suspend fun resetAll() {
            sessions.clear()
            intervals.clear()
        }

        private suspend fun delayIfNeeded() {
            if (writeDelayMs > 0L) delay(writeDelayMs)
        }

        private fun <T> MutableList<T>.replaceById(id: Long, value: T) {
            val index = indexOfFirst {
                when (it) {
                    is ViewingSession -> it.id == id
                    is ViewingInterval -> it.id == id
                    else -> false
                }
            }
            if (index >= 0) this[index] = value else add(value)
        }
    }

    private val Int.minutes: Long get() = this * 60_000L
    private val Int.seconds: Long get() = this * 1_000L
    private val Int.hours: Long get() = this * 3_600_000L
    private val Int.milliseconds: Long get() = this.toLong()
}
