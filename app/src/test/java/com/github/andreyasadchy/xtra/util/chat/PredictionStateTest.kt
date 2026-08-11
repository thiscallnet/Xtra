package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionStateTest {
    @Test
    fun parsesHermesBeginWithColoredOutcomes() {
        val prediction = PubSubUtils.onPredictionUpdate(
            JSONObject(
                """
                {"data":{"event":{"id":"p1","title":"Will Tyler win?","status":"ACTIVE",
                "created_at":"2026-08-11T10:00:00Z","started_at":"2026-08-11T10:00:00Z",
                "locks_at":"2026-08-11T10:02:00Z","prediction_window_seconds":120,
                "outcomes":[{"id":"yes","title":"Yes","color":"BLUE","total_points":2800000,"total_users":98},
                {"id":"no","title":"No","color":"PINK","total_points":2700000,"total_users":38}]}}}
                """.trimIndent(),
            ),
            eventType = "channel.prediction.begin",
            observedAt = 100L,
        )

        assertEquals("p1", prediction?.id)
        assertEquals("ACTIVE", prediction?.status)
        assertEquals(120, prediction?.predictionWindowSeconds)
        assertEquals("BLUE", prediction?.outcomes?.first()?.color)
        assertEquals(2_800_000, prediction?.outcomes?.first()?.totalPoints)
        assertEquals(98, prediction?.outcomes?.first()?.totalUsers)
        assertTrue(prediction?.lockedAt != null)
    }

    @Test
    fun parsesHelixFlatCompletionAndMissingOptionalValuesStayNull() {
        val completed = PubSubUtils.onPredictionUpdate(
            JSONObject(
                """
                {"id":"p1","title":"Who wins?","status":"RESOLVED",
                "created_at":"2026-08-11T10:00:00Z","locked_at":"2026-08-11T10:02:00Z",
                "ended_at":"2026-08-11T10:04:00Z","prediction_window":120,
                "winning_outcome_id":"yes","outcomes":[{"id":"yes","title":"Yes","color":"BLUE","channel_points":100,"users":2}]}
                """.trimIndent(),
            ),
            observedAt = 200L,
        )
        val incomplete = PubSubUtils.onPredictionUpdate(
            JSONObject("""{"id":"p2","title":"No votes","status":"ACTIVE","outcomes":[{"id":"a","title":"A"}]}"""),
            observedAt = 300L,
        )

        assertEquals("RESOLVED", completed?.status)
        assertEquals(100, completed?.outcomes?.single()?.totalPoints)
        assertEquals(2, completed?.outcomes?.single()?.totalUsers)
        assertEquals("yes", completed?.winningOutcomeId)
        assertNull(incomplete?.outcomes?.single()?.totalPoints)
        assertNull(incomplete?.outcomes?.single()?.totalUsers)
        assertNull(incomplete?.predictionWindowSeconds)
    }

    @Test
    fun terminalPredictionRejectsStaleProgress() {
        val ended = prediction("p1", "RESOLVED", 200L, 10).copy(endedAt = 300L)
        val stale = prediction("p1", "ACTIVE", 100L, 5)

        assertSame(ended, PredictionState.merge(ended, stale))
    }

    @Test
    fun newerPredictionReplacesPrevious() {
        val old = prediction("old", "RESOLVED", 100L, 10).copy(startedAt = 1_000L)
        val newer = prediction("new", "ACTIVE", 200L, 0).copy(startedAt = 2_000L)

        assertEquals("new", PredictionState.merge(old, newer)?.id)
    }

    @Test
    fun cachedActivePredictionBecomesLockedAfterWindow() {
        val source = prediction("p1", "ACTIVE", 100L, 10).copy(
            createdAt = 500L,
            startedAt = 500L,
            predictionWindowSeconds = 60,
        )
        val restored = PredictionCache.decode(PredictionCache.encode(source))
        val normalized = restored?.let { PredictionState.normalizeCached(it, now = 61_000L) }

        assertEquals(source, restored)
        assertEquals("LOCKED", normalized?.status)
        assertFalse(PredictionState.isActive(normalized, now = 61_000L))
    }

    @Test
    fun cachedTerminalPredictionRemainsAvailable() {
        val source = prediction("p1", "RESOLVED", 100L, 10)
        val restored = PredictionCache.decode(PredictionCache.encode(source))

        assertEquals(source, restored)
        assertFalse(PredictionState.isActive(restored, now = 10_000L))
    }

    private fun prediction(id: String, status: String, observedAt: Long, points: Int) = Prediction(
        id = id,
        createdAt = observedAt,
        outcomes = listOf(
            Prediction.PredictionOutcome("a", "A", points, 1, "BLUE"),
            Prediction.PredictionOutcome("b", "B", points, 1, "PINK"),
        ),
        predictionWindowSeconds = null,
        status = status,
        title = "Title",
        winningOutcomeId = null,
        observedAt = observedAt,
    )
}
