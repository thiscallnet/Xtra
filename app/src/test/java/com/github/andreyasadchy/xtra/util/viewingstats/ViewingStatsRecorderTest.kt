package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.repository.ViewingStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
        val sessions = mutableListOf<ViewingSession>()
        val intervals = mutableListOf<ViewingInterval>()

        override suspend fun insertSession(metadata: ViewingPlaybackMetadata, startedAt: Long): Long {
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
            sessions.replaceById(session.id, session)
        }

        override suspend fun insertInterval(metadata: ViewingPlaybackMetadata, sessionId: Long, startAt: Long): Long {
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
            intervals.replaceById(interval.id, interval)
        }

        override suspend fun resetAll() {
            sessions.clear()
            intervals.clear()
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
}
