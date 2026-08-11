package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        assertTrue(prediction?.locksAt != null)
        assertNull(prediction?.lockedAt)
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
        assertTrue(incomplete?.outcomes?.single()?.totalPoints == null)
        assertTrue(incomplete?.outcomes?.single()?.totalUsers == null)
        assertTrue(incomplete?.predictionWindowSeconds == null)
    }

    @Test
    fun activeTimerExpirationDerivesLockedAndKeepsPredictionOngoing() {
        val active = prediction("p1", "ACTIVE", observedAt = 1_000L, points = 10).copy(
            startedAt = 10_000L,
            predictionWindowSeconds = 60,
        )

        val locked = PredictionState.normalizeLive(active, now = 70_000L)

        assertEquals("LOCKED", locked.status)
        assertTrue(PredictionState.isOngoing(locked))
        assertFalse(PredictionState.isBettingOpen(locked, now = 70_000L))
    }

    @Test
    fun explicitLockedEventWinsOverActiveSnapshot() {
        val active = prediction("p1", "ACTIVE", 100L, 10)
        val locked = prediction("p1", "LOCKED", 90L, 11).copy(lockedAt = 200L)

        val merged = PredictionState.merge(active, locked)

        assertEquals("LOCKED", merged?.status)
        assertEquals(11, merged?.outcomes?.first()?.totalPoints)
        assertTrue(PredictionState.isOngoing(merged))
    }

    @Test
    fun incompleteLiveHermesActiveSnapshotRemainsActiveButCachedSnapshotFailsClosed() {
        val active = PubSubUtils.onPredictionUpdate(
            JSONObject(
                """
                {"data":{"event":{"id":"p1","title":"Incomplete timing","status":"ACTIVE",
                "outcomes":[{"id":"a","title":"A"},{"id":"b","title":"B"}]}}}
                """.trimIndent(),
            ),
            eventType = "channel.prediction.begin",
            observedAt = 1_000L,
        )

        assertEquals("ACTIVE", active?.status)
        assertEquals("ACTIVE", PredictionState.normalizeLive(active!!, now = 70_000L).status)
        assertEquals("LOCKED", PredictionState.normalizeCached(active, now = 70_000L).status)
    }

    @Test
    fun delayedActiveCannotReopenLocked() {
        val locked = prediction("p1", "LOCKED", 100L, 20)
        val delayedActive = prediction("p1", "ACTIVE", 200L, 5)

        val merged = PredictionState.merge(locked, delayedActive)

        assertEquals("LOCKED", merged?.status)
        assertFalse(PredictionState.isBettingOpen(merged, now = 1_000L))
    }

    @Test
    fun lockedTransitionsToResolved() {
        val locked = prediction("p1", "LOCKED", 100L, 20)
        val resolved = prediction("p1", "RESOLVED", 90L, 22).copy(
            endedAt = 300L,
            winningOutcomeId = "a",
        )

        val merged = PredictionState.merge(locked, resolved)

        assertEquals("RESOLVED", merged?.status)
        assertTrue(PredictionState.isFinal(merged))
        assertFalse(PredictionState.isOngoing(merged))
        assertEquals("a", merged?.winningOutcomeId)
    }

    @Test
    fun lockedTransitionsToCanceled() {
        val locked = prediction("p1", "LOCKED", 100L, 20)
        val canceled = prediction("p1", "CANCELED", 90L, 20).copy(endedAt = 300L)

        val merged = PredictionState.merge(locked, canceled)

        assertEquals("CANCELED", merged?.status)
        assertTrue(PredictionState.isFinal(merged))
        assertFalse(PredictionState.isOngoing(merged))
    }

    @Test
    fun finalStateRejectsDelayedActiveAndLocked() {
        val resolved = prediction("p1", "RESOLVED", 200L, 30).copy(endedAt = 300L)
        val staleActive = prediction("p1", "ACTIVE", 400L, 5)
        val staleLocked = prediction("p1", "LOCKED", 500L, 5)

        assertSame(resolved, PredictionState.merge(resolved, staleActive))
        assertSame(resolved, PredictionState.merge(resolved, staleLocked))
    }

    @Test
    fun cachedLockedPredictionRemainsVisibleOnReopen() {
        val source = prediction("p1", "LOCKED", 1_000L, 10).copy(
            locksAt = 9_000L,
            lockedAt = 10_000L,
            broadcastId = "broadcast-1",
        )

        val restored = PredictionCache.decode(PredictionCache.encode(source))

        assertEquals(source, restored)
        assertTrue(PredictionState.isOngoing(restored))
        assertFalse(PredictionState.isBettingOpen(restored, now = 20_000L))
    }

    @Test
    fun unresolvedCacheExpiresAfterItsTwentyFourHourLifetime() {
        val locked = prediction("p1", "LOCKED", 1_000L, 10).copy(lockedAt = 1_000L)

        assertTrue(
            PredictionCache.isFresh(
                locked,
                cacheTimestamp = 1_000L,
                now = 1_000L + 23L * 60L * 60L * 1_000L,
            ),
        )
        assertFalse(
            PredictionCache.isFresh(
                locked,
                cacheTimestamp = 1_000L,
                now = 1_000L + 24L * 60L * 60L * 1_000L + 1L,
            ),
        )
    }

    @Test
    fun newBroadcastInvalidatesOlderUnresolvedCache() {
        val locked = prediction("p1", "LOCKED", 1_000L, 10).copy(broadcastId = "old")

        assertFalse(
            PredictionCache.isFresh(
                locked,
                cacheTimestamp = 1_000L,
                now = 2_000L,
                broadcastId = "new",
            ),
        )
    }

    @Test
    fun knownNewBroadcastRejectsUnresolvedCacheWithoutBroadcastId() {
        val locked = prediction("p1", "LOCKED", 1_000L, 10)

        assertFalse(
            PredictionCache.isFresh(
                locked,
                cacheTimestamp = 1_000L,
                now = 2_000L,
                broadcastId = "new",
            ),
        )
    }

    @Test
    fun serializedUpdatesCannotPublishDelayedActiveOrLockedAfterResolved() {
        listOf("ACTIVE", "LOCKED").forEach { delayedStatus ->
            val store = PredictionStateStore()
            val locked = prediction("p1", "LOCKED", 100L, 20)
            val delayed = prediction("p1", delayedStatus, 200L, 5)
            val resolved = prediction("p1", "RESOLVED", 300L, 25).copy(endedAt = 400L)
            store.restore(locked) {}

            val enteredApply = CountDownLatch(1)
            val releaseApply = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val delayedFuture = executor.submit {
                    store.update(delayed, normalize = { it }) {
                        enteredApply.countDown()
                        releaseApply.await(2, TimeUnit.SECONDS)
                    }
                }
                assertTrue(enteredApply.await(2, TimeUnit.SECONDS))
                val resolvedFuture = executor.submit {
                    store.update(resolved, normalize = { it }) {}
                }
                releaseApply.countDown()
                delayedFuture.get(2, TimeUnit.SECONDS)
                resolvedFuture.get(2, TimeUnit.SECONDS)
                assertEquals("RESOLVED", store.snapshot()?.status)
            } finally {
                releaseApply.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun finalStateIsNotOngoingAndLockedStateIsNotFinal() {
        val locked = prediction("p1", "LOCKED", 1_000L, 10)
        val resolved = prediction("p1", "RESOLVED", 2_000L, 10)

        assertTrue(PredictionState.isOngoing(locked))
        assertFalse(PredictionState.isFinal(locked))
        assertFalse(PredictionState.isOngoing(resolved))
        assertTrue(PredictionState.isFinal(resolved))
    }

    @Test
    fun pendingTransitionsRemainVisibleButCannotAcceptBets() {
        listOf("CANCEL_PENDING", "RESOLVE_PENDING").forEach { status ->
            val pending = prediction("p1", status, 1_000L, 10)

            assertTrue(PredictionState.isOngoing(pending))
            assertFalse(PredictionState.isBettingOpen(pending, now = 2_000L))
            assertFalse(PredictionState.isFinal(pending))
        }
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
