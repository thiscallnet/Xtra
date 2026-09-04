package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.ui.chat.updatePredictionCountdownState
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun stalePredictionCountdownCannotOverwriteNewDeadline() {
        listOf(9_000L, -1L).forEach { staleRemainingMs ->
            val store = PredictionStateStore()
            val secondsLeft = MutableStateFlow<Int?>(11)
            val tokenA = Any()
            val tokenB = Any()
            var activeToken: Any? = tokenA
            val replacementReady = CountDownLatch(1)
            val releaseReplacement = CountDownLatch(1)
            val staleReady = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val replacementFuture = executor.submit<Boolean> {
                    store.withLock {
                        activeToken = tokenB
                        secondsLeft.value = 22
                        replacementReady.countDown()
                        releaseReplacement.await(2, TimeUnit.SECONDS)
                    }
                }
                assertTrue(replacementReady.await(2, TimeUnit.SECONDS))

                val staleFuture = executor.submit<Boolean> {
                    staleReady.countDown()
                    updatePredictionCountdownState(
                        predictionStateStore = store,
                        currentDeadlineToken = { activeToken },
                        deadlineToken = tokenA,
                        remainingMs = staleRemainingMs,
                        predictionSecondsLeft = secondsLeft,
                        onExpired = {
                            throw AssertionError("stale countdown must not expire the replacement")
                        },
                    )
                }
                assertTrue(staleReady.await(2, TimeUnit.SECONDS))
                releaseReplacement.countDown()

                assertFalse(staleFuture.get(2, TimeUnit.SECONDS))
                assertTrue(replacementFuture.get(2, TimeUnit.SECONDS))
                assertEquals(22, secondsLeft.value)
            } finally {
                releaseReplacement.countDown()
                executor.shutdownNow()
            }
        }
    }

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
    fun locksAtIsTheExactBettingDeadline() {
        val active = prediction("p1", "ACTIVE", 1_000L, 10).copy(locksAt = 10_000L)

        assertTrue(PredictionState.isBettingOpen(active, now = 9_999L))
        assertFalse(PredictionState.isBettingOpen(active, now = 10_000L))
        assertEquals("ACTIVE", PredictionState.normalizeLive(active, now = 9_999L).status)
        assertEquals("LOCKED", PredictionState.normalizeLive(active, now = 10_000L).status)
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
    fun newBroadcastRejectsFinalCacheFromOlderBroadcast() {
        val resolved = prediction("p1", "RESOLVED", 1_000L, 10).copy(
            endedAt = 2_000L,
            broadcastId = "old",
        )

        assertFalse(
            PredictionCache.isFresh(
                resolved,
                cacheTimestamp = 1_000L,
                now = 2_000L,
                broadcastId = "new",
            ),
        )
    }

    @Test
    fun newBroadcastRejectsFinalCacheWithoutBroadcastId() {
        val canceled = prediction("p1", "CANCELED", 1_000L, 10).copy(endedAt = 2_000L)

        assertFalse(
            PredictionCache.isFresh(
                canceled,
                cacheTimestamp = 1_000L,
                now = 2_000L,
                broadcastId = "new",
            ),
        )
    }

    @Test
    fun sameBroadcastKeepsFinalCacheAccepted() {
        val resolved = prediction("p1", "RESOLVED", 1_000L, 10).copy(
            endedAt = 2_000L,
            broadcastId = "same",
        )

        assertTrue(
            PredictionCache.isFresh(
                resolved,
                cacheTimestamp = 1_000L,
                now = 2_000L,
                broadcastId = "same",
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
    fun reschedulingUnderStoreLockCannotScheduleStalePrediction() {
        val store = PredictionStateStore()
        val first = prediction("p1", "RESOLVED", 100L, 20).copy(endedAt = 200L)
        val second = prediction("p2", "RESOLVED", 300L, 25).copy(endedAt = 400L)
        val scheduledIds = mutableListOf<String?>()
        store.restore(first) {}

        val enteredReschedule = CountDownLatch(1)
        val releaseReschedule = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rescheduleFuture = executor.submit {
                store.rescheduleFinal(
                    cancel = {
                        enteredReschedule.countDown()
                        assertTrue(releaseReschedule.await(2, TimeUnit.SECONDS))
                    },
                    schedule = { scheduledIds += it.id },
                )
            }
            assertTrue(enteredReschedule.await(2, TimeUnit.SECONDS))

            val updateFuture = executor.submit {
                store.update(second, normalize = { it }) { value ->
                    scheduledIds += value.id
                }
            }

            releaseReschedule.countDown()
            rescheduleFuture.get(2, TimeUnit.SECONDS)
            updateFuture.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("p1", "p2"), scheduledIds)
            assertEquals("p2", store.snapshot()?.id)
        } finally {
            releaseReschedule.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun freshFinalHonorsCustomGracePeriod() {
        val now = 1_000_000L
        val agedFinal = prediction("p1", "RESOLVED", 1_000L, 10).copy(endedAt = now - 40_000L)

        assertTrue(
            PredictionState.isFreshFinalForDisplay(agedFinal, now = now, graceMillis = 60_000L),
        )
        assertFalse(
            PredictionState.isFreshFinalForDisplay(agedFinal, now = now, graceMillis = 10_000L),
        )
        assertFalse(PredictionState.isFreshFinalForDisplay(agedFinal, now = now))
    }

    @Test
    fun neverDisplayDurationDoesNotMakeHistoricalFinalFresh() {
        val now = 1_000_000L
        val ancientFinal = prediction("p1", "RESOLVED", 1_000L, 10).copy(endedAt = 2_000L)
        val ongoing = prediction("p1", "LOCKED", 1_000L, 10)

        assertFalse(
            PredictionState.isFreshFinalForDisplay(
                ancientFinal,
                now = now,
                graceMillis = PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            ),
        )
        assertFalse(
            PredictionState.isFreshFinalForDisplay(
                ongoing,
                now = now,
                graceMillis = PredictionState.RESULT_DISPLAY_NEVER_MILLIS,
            ),
        )
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

    @Test
    fun partialFourOutcomeUpdateKeepsExistingOrderAndMetadata() {
        val currentOutcomes = (1..4).map { index ->
            Prediction.PredictionOutcome(
                id = "o$index",
                title = "Outcome $index",
                totalPoints = index * 10,
                totalUsers = index,
                color = "BLUE",
                badgeSetId = "predictions",
                badgeVersion = "blue-$index",
                badgeUrl = "https://example.invalid/$index",
            )
        }
        val incoming = Prediction(
            id = "p4",
            createdAt = 1_000L,
            outcomes = listOf(
                Prediction.PredictionOutcome(
                    id = "o3",
                    title = null,
                    totalPoints = 99,
                    totalUsers = null,
                    color = null,
                ),
            ),
            predictionWindowSeconds = null,
            status = "ACTIVE",
            title = "Title",
            winningOutcomeId = null,
            observedAt = 2_000L,
        )
        val current = Prediction(
            id = "p4",
            createdAt = 1_000L,
            outcomes = currentOutcomes,
            predictionWindowSeconds = null,
            status = "ACTIVE",
            title = "Title",
            winningOutcomeId = null,
            observedAt = 1_000L,
        )

        val merged = PredictionState.merge(current, incoming)

        assertEquals(listOf("o1", "o2", "o3", "o4"), merged?.outcomes?.map { it.id })
        assertEquals(99, merged?.outcomes?.get(2)?.totalPoints)
        assertEquals("predictions", merged?.outcomes?.get(2)?.badgeSetId)
        assertEquals("blue-4", merged?.outcomes?.get(3)?.badgeVersion)
        assertEquals("https://example.invalid/1", merged?.outcomes?.first()?.badgeUrl)
    }

    @Test
    fun fullFourOutcomeProgressRetainsAllUpdatedOutcomesInOrder() {
        val current = predictionWithOutcomes("p4", points = 10)
        val progress = PubSubUtils.onPredictionUpdate(
            JSONObject(
                """
                {
                  "type":"event-updated",
                  "event":{"id":"p4","title":"Title","status":"ACTIVE","outcomes":[
                    {"id":"o1","title":"One","total_points":11},
                    {"id":"o2","title":"Two","total_points":22},
                    {"id":"o3","title":"Three","total_points":33},
                    {"id":"o4","title":"Four","total_points":44}
                  ]}
                }
                """.trimIndent(),
            ),
            observedAt = 2_000L,
        )

        val merged = PredictionState.merge(current, progress)

        assertEquals(listOf("o1", "o2", "o3", "o4"), merged?.outcomes?.map { it.id })
        assertEquals(listOf(11, 22, 33, 44), merged?.outcomes?.map { it.totalPoints })
    }

    @Test
    fun cacheRoundTripRetainsFourOutcomeBadgeMetadata() {
        val source = predictionWithOutcomes("p4", points = 100)

        val restored = PredictionCache.decode(PredictionCache.encode(source))

        assertEquals(source, restored)
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

    private fun predictionWithOutcomes(id: String, points: Int) = Prediction(
        id = id,
        createdAt = 1_000L,
        outcomes = (1..4).map { index ->
            Prediction.PredictionOutcome(
                id = "o$index",
                title = listOf("One", "Two", "Three", "Four")[index - 1],
                totalPoints = points + index,
                totalUsers = index,
                color = "BLUE",
                badgeSetId = "predictions",
                badgeVersion = "blue-$index",
                badgeUrl = "https://example.invalid/$index",
            )
        },
        predictionWindowSeconds = null,
        status = "ACTIVE",
        title = "Title",
        winningOutcomeId = null,
        observedAt = 1_000L,
    )
}
