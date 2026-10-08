package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.util.m3u8.VaftBoundaryObservation
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.preload.VaftPreloadedMediaSource
import com.github.andreyasadchy.xtra.repository.preload.VaftWarmupHandle

internal data class TrackedVaftBoundary(
    var observation: VaftBoundaryObservation,
    val sourceGeneration: Long,
    var primaryMediaId: String,
    val primaryUri: String,
    var playlistStartTimeUs: Long,
    var playlistMediaSequence: Long,
    var sourceRelativeClockValid: Boolean = true,
    var lastKnownPhase: VaftPlaybackBoundaryPhase = VaftPlaybackBoundaryPhase.UNKNOWN,
)

internal enum class VaftPlaybackBoundaryPhase { NONE, BEFORE, ACTIVE, AFTER, UNKNOWN }

internal data class PreparedVaftCandidate(
    val requestId: String,
    val markerKey: String,
    val vaftGeneration: Long,
    val sourceGeneration: Long,
    val playbackMediaId: String,
    val configurationFingerprint: String,
    val qualityIntent: DesiredHlsQuality,
    val qualityIntentRevision: Long,
    val candidate: PlayerRepository.StreamPlaylistCandidate,
    val preparedAtMs: Long,
    var warmup: VaftWarmupHandle? = null,
    var nearTriggerWarmStarted: Boolean = false,
    var refreshAttempted: Boolean = false,
)

internal data class VaftPreparedSourceResolution(
    val source: VaftPreloadedMediaSource? = null,
    val rejectionReason: String? = null,
)

internal data class VaftEntryFrameOwner(
    val requestId: String,
    val vaftGeneration: Long,
    val sourceGeneration: Long,
    val markerKey: String,
    val primaryMediaId: String,
    val primaryUri: String,
    val qualityIntentRevision: Long,
    val requestedAtMs: Long,
)

internal data class VaftPositionSnapshot(
    val windowStartTimeMs: Long?,
    val positionMs: Long,
    val liveOffsetMs: Long?,
)
