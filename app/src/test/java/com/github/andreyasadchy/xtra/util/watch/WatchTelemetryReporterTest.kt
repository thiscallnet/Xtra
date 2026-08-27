package com.github.andreyasadchy.xtra.util.watch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTelemetryReporterTest {
    @Test
    fun playingFor59SecondsDoesNotSendAMinute() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(59_000)
        runCurrent()

        assertTrue(sent.isEmpty())
        reporter.stop()
    }

    @Test
    fun playingFor60SecondsSendsExactlyOneMinute() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, sent.size)
        reporter.stop()
    }

    @Test
    fun playingFor120SecondsSendsExactlyTwoMinutes() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(2, sent.size)
        reporter.stop()
    }

    @Test
    fun bufferingTimeDoesNotCount() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(30_000)
        reporter.setActuallyPlaying(false)
        advanceTimeBy(30_000)
        reporter.setActuallyPlaying(true)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, sent.size)
        reporter.stop()
    }

    @Test
    fun pausedTimeDoesNotCount() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(false)
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(sent.isEmpty())
        reporter.stop()
    }

    @Test
    fun startingTheSamePlaybackTwiceDoesNotDuplicateReporter() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)
        val session = session()

        reporter.start(session)
        reporter.start(session.copy(sessionId = "different-session-id"))
        reporter.setActuallyPlaying(true)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, sent.size)
        assertEquals(session.sessionId, sent.single().sessionId)
        reporter.stop()
    }

    @Test
    fun switchingChannelStopsTheOldSession() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)
        val oldSession = session()
        val newSession = oldSession.copy(channelId = "new-channel", sessionId = "new-session")

        reporter.start(oldSession)
        reporter.setActuallyPlaying(true)
        advanceTimeBy(30_000)
        reporter.start(newSession)
        reporter.setActuallyPlaying(true)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(newSession), sent)
        reporter.stop()
    }

    @Test
    fun changingBroadcastStartsANewLogicalSession() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)
        val oldSession = session()
        val newSession = oldSession.copy(streamId = "stream-2", sessionId = "new-session")

        reporter.start(oldSession)
        reporter.setActuallyPlaying(true)
        advanceTimeBy(30_000)
        reporter.start(newSession)
        reporter.setActuallyPlaying(true)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(newSession), sent)
        reporter.stop()
    }

    @Test
    fun anonymousViewingDoesNotSendTelemetry() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = WatchTelemetryReporter(
            scope = backgroundScope,
            sendMinuteWatched = { sent += it; true },
            isAuthenticated = { false },
            elapsedRealtime = { testScheduler.currentTime },
        )

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(sent.isEmpty())
        reporter.stop()
    }

    @Test
    fun failedTelemetryResultDoesNotCountOrLogSuccess() = runTest {
        var attempts = 0
        val logs = mutableListOf<String>()
        val reporter = WatchTelemetryReporter(
            scope = backgroundScope,
            sendMinuteWatched = {
                attempts++
                false
            },
            elapsedRealtime = { testScheduler.currentTime },
            log = logs::add,
        )

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(logs.none { it.contains("minute watched #") })
        assertEquals(2, logs.count { it.contains("minute send failed") })
        reporter.stop()
    }

    @Test
    fun stoppingReporterCancelsItsTimer() = runTest {
        val sent = mutableListOf<TwitchWatchSession>()
        val reporter = reporter(sent)

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(30_000)
        reporter.stop()
        advanceTimeBy(90_000)
        runCurrent()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun failedTelemetryDoesNotStopTheReporter() = runTest {
        var attempts = 0
        val reporter = WatchTelemetryReporter(
            scope = backgroundScope,
            sendMinuteWatched = {
                attempts++
                error("network unavailable")
            },
            elapsedRealtime = { testScheduler.currentTime },
        )

        reporter.start(session())
        reporter.setActuallyPlaying(true)
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(2, attempts)
        reporter.stop()
    }

    private fun TestScope.reporter(sent: MutableList<TwitchWatchSession>) = WatchTelemetryReporter(
        scope = backgroundScope,
        sendMinuteWatched = { sent += it; true },
        elapsedRealtime = { testScheduler.currentTime },
    )

    private fun session() = TwitchWatchSession(
        channelId = "channel-1",
        channelLogin = "channel",
        streamId = "stream-1",
        userId = "user-1",
        sessionId = "session-1",
    )
}
