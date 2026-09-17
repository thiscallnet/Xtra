package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRewindTest {

    @Test
    fun liveTapSeekUsesWideSideZonesAndNarrowCenterZone() {
        assertEquals(LiveTapSeekZone.LEFT, liveTapSeekZone(0f, 1000))
        assertEquals(LiveTapSeekZone.LEFT, liveTapSeekZone(399f, 1000))
        assertEquals(LiveTapSeekZone.CENTER, liveTapSeekZone(400f, 1000))
        assertEquals(LiveTapSeekZone.CENTER, liveTapSeekZone(600f, 1000))
        assertEquals(LiveTapSeekZone.RIGHT, liveTapSeekZone(601f, 1000))
        assertEquals(LiveTapSeekZone.RIGHT, liveTapSeekZone(999f, 1000))
        assertNull(liveTapSeekZone(-1f, 1000))
        assertNull(liveTapSeekZone(1000f, 1000))
    }

    @Test
    fun doubleTapStateRequiresSameZoneAndDoesNotOverlapPairs() {
        val state = LiveTapSeekDoubleTapState()

        state.recordFirstTap(LiveTapSeekZone.LEFT, 100L)
        state.recordSecondTapDown(LiveTapSeekZone.LEFT, 200L)
        assertEquals(LiveTapSeekZone.LEFT, state.acceptSecondTap())

        state.recordFirstTap(LiveTapSeekZone.LEFT, 200L)
        state.recordSecondTapDown(LiveTapSeekZone.LEFT, 300L)
        assertNull(state.acceptSecondTap())

        state.recordFirstTap(LiveTapSeekZone.LEFT, 300L)
        state.recordSecondTapDown(LiveTapSeekZone.LEFT, 400L)
        assertEquals(LiveTapSeekZone.LEFT, state.acceptSecondTap())

        state.recordFirstTap(LiveTapSeekZone.LEFT, 500L)
        state.recordSecondTapDown(LiveTapSeekZone.RIGHT, 600L)
        assertNull(state.acceptSecondTap())
    }

    @Test
    fun doubleTapStateRejectsPairWhenTheSecondContactBecomesADifferentGesture() {
        val state = LiveTapSeekDoubleTapState()

        state.recordFirstTap(LiveTapSeekZone.RIGHT, 100L)
        state.recordSecondTapDown(LiveTapSeekZone.RIGHT, 200L)
        state.rejectSecondTap()

        assertNull(state.acceptSecondTap())
    }

    @Test
    fun selectsClosestPlausibleCurrentVod() {
        val start = 1_000_000L
        val selected = selectCurrentRecordingVod(
            candidates = listOf(
                candidate("far", start + 120_000L, 600_000L),
                candidate("current", start + 20_000L, 900_000L),
            ),
            streamStartMs = start,
            nowWallClockMs = start + 900_000L,
        )

        assertEquals("current", selected?.id)
    }

    @Test
    fun rejectsPreviousStreamEvenWhenItsDurationLooksPlausible() {
        val start = 1_000_000L
        assertNull(
            selectCurrentRecordingVod(
                candidates = listOf(candidate("previous", start - 10 * 60 * 1000L, 900_000L)),
                streamStartMs = start,
                nowWallClockMs = start + 900_000L,
            ),
        )
    }

    @Test
    fun rejectsMissingOrImplausibleDuration() {
        val start = 1_000_000L
        val candidates = listOf(
            candidate("short", start, 30_000L),
            candidate("long", start, 2_000_000L),
        )
        assertNull(selectCurrentRecordingVod(candidates, start, start + 900_000L))
    }

    @Test
    fun choosesClosestValidCandidateWhenSeveralArePlausible() {
        val start = 1_000_000L
        val selected = selectCurrentRecordingVod(
            candidates = listOf(
                candidate("second", start + 90_000L, 900_000L),
                candidate("closest", start - 10_000L, 900_000L),
            ),
            streamStartMs = start,
            nowWallClockMs = start + 900_000L,
        )
        assertEquals("closest", selected?.id)
    }

    @Test
    fun predictsUsingElapsedRealtimeOnly() {
        assertEquals(
            1_230_000L,
            predictedLiveEdgeMs(1_000_000L, 5_000L, 235_000L),
        )
        assertEquals(
            1_000_000L,
            predictedLiveEdgeMs(1_000_000L, 5_000L, 4_000L),
        )
    }

    @Test
    fun finalFifteenSecondsMeansLive() {
        assertTrue(shouldReturnToLive(985_000L, 1_000_000L))
        assertTrue(!shouldReturnToLive(984_999L, 1_000_000L))
    }

    @Test
    fun backwardLiveTapAtTenSecondsDoesNotReturnToLive() {
        val target = LiveTapSeekTarget(
            positionMs = 990_000L,
            atLiveEdge = false,
            effectiveDeltaMs = -10_000L,
        )

        assertFalse(shouldReturnToLiveAfterLiveTap(target, 1_000_000L))
    }

    @Test
    fun forwardLiveTapWithinReturnThresholdStillReturnsToLive() {
        val target = LiveTapSeekTarget(
            positionMs = 990_000L,
            atLiveEdge = false,
            effectiveDeltaMs = 10_000L,
        )

        assertTrue(shouldReturnToLiveAfterLiveTap(target, 1_000_000L))
    }

    @Test
    fun staleDiscoveryGenerationIsRejected() {
        assertTrue(isLiveRewindGenerationCurrent(4L, 4L))
        assertTrue(!isLiveRewindGenerationCurrent(3L, 4L))
    }

    @Test
    fun streamEndFreezesTheLastPredictedEdge() {
        assertEquals(1_200_000L, freezeLiveEdge(1_300_000L, 1_200_000L))
        assertEquals(1_300_000L, freezeLiveEdge(1_300_000L, null))
    }

    @Test
    fun discoveryDoesNotMarkThePhysicalSourceAsSwitching() {
        val state = LiveRewindSourceState().beginDiscovery()

        assertEquals(LiveRewindSourceTransition.NONE, state.transition)
        assertFalse(isLiveRewindSourceActiveOrSwitching(state.mode, state.transition != LiveRewindSourceTransition.NONE))
    }

    @Test
    fun successfulVodTransitionActivatesRewindMode() {
        val vod = vod("current")
        val state = LiveRewindSourceState()
            .requestVod(vod)
            .completeVod()

        assertEquals(LivePlaybackMode.Rewound(vod.id), state.mode)
        assertEquals(LiveRewindSourceTransition.NONE, state.transition)
    }

    @Test
    fun failedLiveTransitionRetainsTheOldVodState() {
        val vod = vod("old")
        val state = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound(vod.id),
            vod = vod,
            frozenEdgeMs = 42_000L,
        ).requestLive().liveTransitionFailed()

        assertEquals(LivePlaybackMode.Rewound(vod.id), state.mode)
        assertEquals(vod, state.vod)
        assertEquals(42_000L, state.frozenEdgeMs)
        assertEquals(LiveRewindSourceTransition.NONE, state.transition)
        assertTrue(state.canGoLive())
    }

    @Test
    fun failedSessionTransitionThenRetryCommitsThePendingSession() {
        val vod = vod("stream-a-vod")
        val sessionA = LiveRewindSession("stream-a", "created-a")
        val sessionB = LiveRewindSession("stream-b", "created-b")
        val rewoundA = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound(vod.id),
            vod = vod,
            committedSession = sessionA,
        )

        val pendingB = rewoundA.observeSession(sessionB)
        assertEquals(sessionA, pendingB.committedSession)
        assertEquals(sessionB, pendingB.pendingSession)
        assertEquals(vod, pendingB.vod)
        assertEquals(LivePlaybackMode.Rewound(vod.id), pendingB.mode)

        val failed = pendingB.requestLive().liveTransitionFailed()
        assertEquals(sessionA, failed.committedSession)
        assertEquals(sessionB, failed.pendingSession)
        assertEquals(vod, failed.vod)
        assertEquals(LivePlaybackMode.Rewound(vod.id), failed.mode)

        val succeeded = failed.completeLiveTransition()
        assertEquals(sessionB, succeeded.state.committedSession)
        assertNull(succeeded.state.pendingSession)
        assertNull(succeeded.state.vod)
        assertEquals(LivePlaybackMode.Live, succeeded.state.mode)
        assertTrue(succeeded.shouldDiscoverRecordingVod)

        val directRestart = rewoundA
            .observeNewSessionWhileRewound(sessionB, frozenEdgeMs = 123_000L)
            .requestLive()
            .liveTransitionFailed()
        assertEquals(sessionA, directRestart.committedSession)
        assertEquals(sessionB, directRestart.pendingSession)
        assertEquals(vod, directRestart.vod)
        assertEquals(LivePlaybackMode.Rewound(vod.id), directRestart.mode)
        assertEquals(123_000L, directRestart.frozenEdgeMs)
        assertEquals(123_000L, directRestart.streamEnded(180_000L).frozenEdgeMs)
    }

    @Test
    fun sameSessionRecoveryClearsOfflineFreezeButPendingRestartDoesNot() {
        val vod = vod("stream-a-vod")
        val sessionA = LiveRewindSession("stream-a", "created-a")
        val sessionB = LiveRewindSession("stream-b", "created-b")
        val offlineA = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound(vod.id),
            vod = vod,
            offline = true,
            frozenEdgeMs = 123_000L,
            committedSession = sessionA,
        )

        val recovered = offlineA.recoverSession(sessionA)
        assertFalse(recovered.offline)
        assertNull(recovered.frozenEdgeMs)
        assertEquals(LivePlaybackMode.Rewound(vod.id), recovered.mode)
        assertNull(recovered.pendingSession)

        val pendingRestart = offlineA.observeSession(sessionB).recoverSession(sessionB)
        assertFalse(pendingRestart.offline)
        assertEquals(123_000L, pendingRestart.frozenEdgeMs)
        assertEquals(sessionB, pendingRestart.pendingSession)
        assertEquals(LivePlaybackMode.Rewound(vod.id), pendingRestart.mode)
        assertTrue(pendingRestart.canGoLive())

        val pendingRestartOffline = pendingRestart.streamEnded(180_000L)
        assertTrue(pendingRestartOffline.offline)
        assertEquals(123_000L, pendingRestartOffline.frozenEdgeMs)
        assertEquals(sessionB, pendingRestartOffline.pendingSession)

        val pendingRestartRecovered = pendingRestartOffline.recoverSession(sessionB)
        assertFalse(pendingRestartRecovered.offline)
        assertEquals(sessionB, pendingRestartRecovered.pendingSession)
        assertEquals(sessionA, pendingRestartRecovered.committedSession)
        assertEquals(LivePlaybackMode.Rewound(vod.id), pendingRestartRecovered.mode)
        assertEquals(123_000L, pendingRestartRecovered.frozenEdgeMs)
        assertTrue(pendingRestartRecovered.canGoLive())
    }

    @Test
    fun repeatedStreamEndDoesNotMoveAnAlreadyFrozenEdge() {
        val state = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound("old"),
            vod = vod("old"),
            frozenEdgeMs = 123_000L,
        )

        assertEquals(123_000L, state.streamEnded(180_000L).streamEnded(240_000L).frozenEdgeMs)
    }

    @Test
    fun successfulLiveTransitionInvalidatesTheOldVodState() {
        val vod = vod("old")
        val state = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound(vod.id),
            vod = vod,
            frozenEdgeMs = 42_000L,
        ).requestLive().completeLive()

        assertEquals(LivePlaybackMode.Live, state.mode)
        assertNull(state.vod)
        assertNull(state.frozenEdgeMs)
    }

    @Test
    fun offlineRewindStateFreezesTheEdgeAndCannotOfferLive() {
        val state = LiveRewindSourceState(
            mode = LivePlaybackMode.Rewound("old"),
            vod = vod("old"),
        ).streamEnded(123_000L)

        assertEquals(123_000L, state.frozenEdgeMs)
        assertFalse(state.canGoLive())
    }

    @Test
    fun statusKnownSignalDetectsOfflineEvenWhenChatIsNotUsed() {
        assertTrue(shouldMarkLiveStreamOffline(statusKnown = true, streamWasLive = true, streamPresent = false))
        assertFalse(shouldMarkLiveStreamOffline(statusKnown = false, streamWasLive = true, streamPresent = false))
        assertFalse(shouldMarkLiveStreamOffline(statusKnown = true, streamWasLive = false, streamPresent = false))
    }

    @Test
    fun liveClipIsUnavailableDuringRewindOrSourceTransition() {
        assertFalse(canUseLiveSource(BasePlaybackService.STREAM, false, true))
        assertFalse(canUseLiveSource(BasePlaybackService.STREAM, true, false))
        assertTrue(canUseLiveSource(BasePlaybackService.STREAM, false, false))
        assertFalse(canUseLiveClipSource(BasePlaybackService.STREAM, false, true))
        assertFalse(canUseLiveClipSource(BasePlaybackService.STREAM, true, false))
        assertTrue(canUseLiveClipSource(BasePlaybackService.STREAM, false, false))
        assertFalse(canUseLiveClipSource(BasePlaybackService.VIDEO, false, false))
    }

    @Test
    fun lateManifestFromThePreviousSourceCannotEnterTheNewLiveGeneration() {
        assertFalse(isCurrentLiveClipSource("live-clip-2", "vod-1"))
        assertTrue(isCurrentLiveClipSource("live-clip-2", "live-clip-2"))
        assertFalse(isCurrentLiveClipSource(null, "live-clip-2"))
    }

    @Test
    fun failedTransitionRestoresThePhysicalSourceThatExistedBeforeTheAttempt() {
        val previousSource = "live-playlist"
        var physicalSource = previousSource
        physicalSource = "rewind-playlist"

        val restored = try {
            error("prepare failed after installing the rewind source")
        } catch (_: Exception) {
            restorePreviousLiveRewindSource(previousSource, { source ->
                physicalSource = source
                true
            }, onRestoreFailure = { error("unexpected restore failure") })
        }

        assertTrue(restored)
        assertEquals(previousSource, physicalSource)
    }

    @Test
    fun failedSourceRestoreStopsTheTransitionSafely() {
        var stopped = false

        val restored = restorePreviousLiveRewindSource(
            previousSource = "live-playlist",
            restore = { false },
            onRestoreFailure = { stopped = true },
        )

        assertFalse(restored)
        assertTrue(stopped)
    }

    @Test
    fun rewindTimelineUsesLiveEdgeLiveAndCurrentPositionWhenRewound() {
        assertEquals(600_000L, liveRewindTimelinePositionMs(LivePlaybackMode.Live, 600_000L, 120_000L, null))
        assertEquals(
            450_000L,
            liveRewindTimelinePositionMs(
                LivePlaybackMode.Live,
                600_000L,
                120_000L,
                null,
                playbackRequested = false,
                pausedLivePositionMs = 450_000L,
            ),
        )
        assertEquals(120_000L, liveRewindTimelinePositionMs(LivePlaybackMode.Rewound("vod"), 600_000L, 120_000L, null))
        assertEquals(300_000L, liveRewindTimelinePositionMs(LivePlaybackMode.Rewound("vod"), 600_000L, 120_000L, 300_000L))
    }

    @Test
    fun pausedLivePositionIsCapturedOnceAndClearedWhenPlaybackIsRequested() {
        assertEquals(
            600_000L,
            liveRewindPausedPositionMs(
                mode = LivePlaybackMode.Live,
                edgeMs = 600_000L,
                playbackRequested = false,
                existingPositionMs = null,
            ),
        )
        assertEquals(
            600_000L,
            liveRewindPausedPositionMs(
                mode = LivePlaybackMode.Live,
                edgeMs = 610_000L,
                playbackRequested = false,
                existingPositionMs = 600_000L,
            ),
        )
        assertNull(
            liveRewindPausedPositionMs(
                mode = LivePlaybackMode.Live,
                edgeMs = 610_000L,
                playbackRequested = true,
                existingPositionMs = 600_000L,
            ),
        )
        assertNull(
            liveRewindPausedPositionMs(
                mode = LivePlaybackMode.Rewound("vod"),
                edgeMs = 610_000L,
                playbackRequested = false,
                existingPositionMs = 600_000L,
            ),
        )
    }

    @Test
    fun resumingPausedLiveRequiresAOneShotLiveEdgeSeek() {
        assertTrue(
            shouldSeekToLiveAfterPausedLive(
                mode = LivePlaybackMode.Live,
                playbackRequested = true,
                pausedLivePositionMs = 600_000L,
            ),
        )
        assertFalse(
            shouldSeekToLiveAfterPausedLive(
                mode = LivePlaybackMode.Live,
                playbackRequested = false,
                pausedLivePositionMs = 600_000L,
            ),
        )
        assertFalse(
            shouldSeekToLiveAfterPausedLive(
                mode = LivePlaybackMode.Live,
                playbackRequested = true,
                pausedLivePositionMs = null,
            ),
        )
        assertFalse(
            shouldSeekToLiveAfterPausedLive(
                mode = LivePlaybackMode.Rewound("vod"),
                playbackRequested = true,
                pausedLivePositionMs = 600_000L,
            ),
        )
    }

    @Test
    fun pausedLiveForwardTapUsesTheSameLiveEdgeSeek() {
        assertTrue(
            shouldSeekToLiveAfterLiveTarget(
                mode = LivePlaybackMode.Live,
                returnToLive = true,
            ),
        )
        assertFalse(
            shouldSeekToLiveAfterLiveTarget(
                mode = LivePlaybackMode.Live,
                returnToLive = false,
            ),
        )
        assertFalse(
            shouldSeekToLiveAfterLiveTarget(
                mode = LivePlaybackMode.Rewound("vod"),
                returnToLive = true,
            ),
        )
    }

    @Test
    fun liveTapSeekStacksFromThePendingTarget() {
        val accumulator = LiveTapSeekAccumulator()

        assertEquals(LiveTapSeekTarget(590_000L, false, -10_000L), accumulator.addTap(600_000L, 600_000L, LiveTapSeekDirection.BACKWARD))
        assertEquals(LiveTapSeekTarget(580_000L, false, -20_000L), accumulator.addTap(600_500L, 600_500L, LiveTapSeekDirection.BACKWARD))
        assertEquals(LiveTapSeekTarget(580_000L, false, -20_000L), accumulator.takePendingTarget())
        assertFalse(accumulator.hasPendingTarget())
    }

    @Test
    fun liveTapSeekClampsAtBothEndsAndCanChangeDirection() {
        val accumulator = LiveTapSeekAccumulator()

        assertEquals(LiveTapSeekTarget(0L, false, -5_000L), accumulator.addTap(5_000L, 20_000L, LiveTapSeekDirection.BACKWARD))
        assertEquals(LiveTapSeekTarget(10_000L, false, 5_000L), accumulator.addTap(5_000L, 20_000L, LiveTapSeekDirection.FORWARD))
        assertEquals(LiveTapSeekTarget(20_000L, true, 15_000L), accumulator.addTap(15_000L, 20_000L, LiveTapSeekDirection.FORWARD))
        assertEquals(LiveTapSeekTarget(20_000L, true, 15_000L), accumulator.takePendingTarget())
    }

    @Test
    fun liveTapSeekRefreshesTheBaseWhenThePendingTargetWasAtLiveEdge() {
        val accumulator = LiveTapSeekAccumulator()

        assertEquals(
            LiveTapSeekTarget(20_000L, true, 10_000L),
            accumulator.addTap(10_000L, 20_000L, LiveTapSeekDirection.FORWARD),
        )
        assertEquals(
            LiveTapSeekTarget(20_000L, false, 10_000L),
            accumulator.addTap(10_000L, 30_000L, LiveTapSeekDirection.BACKWARD),
        )
    }

    @Test
    fun liveTapSeekFeedbackFollowsTheNetBurstDirection() {
        val target = LiveTapSeekTarget(80_000L, false, -20_000L)

        assertEquals(
            LiveTapSeekDirection.BACKWARD,
            liveTapSeekFeedbackDirection(target, LiveTapSeekDirection.FORWARD),
        )
        assertEquals(
            LiveTapSeekDirection.FORWARD,
            liveTapSeekFeedbackDirection(target.copy(atLiveEdge = true), LiveTapSeekDirection.BACKWARD),
        )
    }

    @Test
    fun changedStreamIdOrCreatedAtStartsANewSession() {
        assertTrue(hasLiveStreamSessionChanged("old", "created", "new", "created"))
        assertTrue(hasLiveStreamSessionChanged("same", "old", "same", "new"))
        assertFalse(hasLiveStreamSessionChanged("same", "created", "same", "created"))
    }

    private fun candidate(id: String, createdAtMs: Long, durationMs: Long) =
        LiveRewindVodCandidate(id, createdAtMs, durationMs, createdAtMs.toString())

    private fun vod(id: String) = LiveRewindVod(
        id = id,
        reportedDurationMs = 60_000L,
        sampledAtElapsedRealtimeMs = 1_000L,
        createdAt = "created",
    )
}
