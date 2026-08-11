package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Poll
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PollStateTest {
    @Test
    fun parsesHermesBeginWithChannelPointVoting() {
        val poll = PubSubUtils.onPollUpdate(
            JSONObject(
                """
                {"data":{"poll":{"id":"p1","title":"Pick one","status":"ACTIVE",
                "started_at":"2026-08-11T10:00:00Z","ends_at":"2026-08-11T10:10:00Z",
                "channel_points_voting":{"is_enabled":true,"amount_per_vote":100},
                "choices":[{"id":"a","title":"A","votes":{"total":3,"channel_points_votes":2}}]}}}
                """.trimIndent(),
            ),
            eventType = "channel.poll.begin",
            observedAt = 100L,
        )

        assertEquals("p1", poll?.id)
        assertEquals("ACTIVE", poll?.status)
        assertEquals(100, poll?.channelPointsPerVote)
        assertEquals(3, poll?.choices?.single()?.totalVotes)
        assertEquals(2, poll?.choices?.single()?.channelPointsVotes)
        assertTrue(poll?.startedAt != null)
        assertTrue(poll?.endsAt != null)
    }

    @Test
    fun parsesProgressAndHelixFlatCompletion() {
        val progress = PubSubUtils.onPollUpdate(
            JSONObject("""{"id":"p1","title":"Pick","choices":[{"id":"a","title":"A","votes":4}],"status":"ACTIVE","votes":4}"""),
            observedAt = 200L,
        )
        val completed = PubSubUtils.onPollUpdate(
            JSONObject("""{"id":"p1","title":"Pick","status":"COMPLETED","started_at":"2026-08-11T10:00:00Z","ended_at":"2026-08-11T10:05:00Z","duration":"300s","choices":[{"id":"a","title":"A","votes":4,"channel_points_votes":3}],"channel_points_voting_enabled":true,"channel_points_per_vote":50}"""),
            observedAt = 300L,
        )

        assertEquals(4, progress?.totalVotes)
        assertEquals("COMPLETED", completed?.status)
        assertEquals(300, completed?.durationSeconds)
        assertEquals(3, completed?.choices?.single()?.channelPointsVotes)
        assertEquals(50, completed?.channelPointsPerVote)
    }

    @Test
    fun parsesTerminatedAndMissingOptionalNumbers() {
        val terminated = PubSubUtils.onPollUpdate(
            JSONObject("""{"id":"p2","title":"Nope","status":"TERMINATED","choices":[{"id":"a","title":"A"}] }"""),
            observedAt = 100L,
        )

        assertEquals("TERMINATED", terminated?.status)
        assertNull(terminated?.totalVotes)
        assertNull(terminated?.choices?.single()?.totalVotes)
        assertNull(terminated?.choices?.single()?.channelPointsVotes)
    }

    @Test
    fun terminalStateRejectsStaleProgress() {
        val ended = poll("p1", "COMPLETED", 200L, 10)
        val stale = poll("p1", "ACTIVE", 100L, 5)

        assertSame(ended, PollState.merge(ended, stale))
    }

    @Test
    fun newerPollReplacesPrevious() {
        val old = poll("old", "COMPLETED", 100L, 10).copy(startedAt = 1_000L)
        val newer = poll("new", "ACTIVE", 200L, 0).copy(startedAt = 2_000L)

        assertEquals("new", PollState.merge(old, newer)?.id)
    }

    @Test
    fun cachedPollRoundTripsAndExpiredActiveIsNotActive() {
        val source = poll("p1", "ACTIVE", 100L, 3).copy(
            endsAt = 1_000L,
            startedAt = 500L,
            channelPointsVotingEnabled = true,
            channelPointsPerVote = 25,
        )
        val restored = PollCache.decode(PollCache.encode(source))
        val expired = restored?.let { PollState.normalizeCached(it, now = 2_000L) }

        assertEquals(source, restored)
        assertEquals("COMPLETED", expired?.status)
        assertFalse(PollState.isActive(expired, now = 2_000L))
    }

    @Test
    fun reconnectWelcomeDoesNotCreateSubscriptionsAgain() {
        val policy = EventSubReconnectState()

        assertTrue(policy.shouldCreateSubscriptions(isReplacement = false))
        assertFalse(policy.shouldCreateSubscriptions(isReplacement = true))
    }

    private fun poll(id: String, status: String, observedAt: Long, votes: Int) = Poll(
        id = id,
        title = "Title",
        status = status,
        choices = listOf(Poll.PollChoice("a", "A", votes)),
        totalVotes = votes,
        remainingMilliseconds = null,
        observedAt = observedAt,
    )
}
