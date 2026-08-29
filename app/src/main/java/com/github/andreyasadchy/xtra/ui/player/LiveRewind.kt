package com.github.andreyasadchy.xtra.ui.player

import android.os.SystemClock
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Instant

const val LIVE_EDGE_THRESHOLD_MS = 15_000L
const val LIVE_REWIND_MAX_CREATED_AT_DELTA_MS = 3 * 60 * 1000L

data class LiveRewindVod(
    val id: String,
    val reportedDurationMs: Long,
    val sampledAtElapsedRealtimeMs: Long,
    val createdAt: String,
) {
    fun predictedDurationMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        (reportedDurationMs + (nowMs - sampledAtElapsedRealtimeMs).coerceAtLeast(0L))
            .coerceAtLeast(0L)
}

data class LiveRewindVodCandidate(
    val id: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val createdAt: String,
)

fun predictedLiveEdgeMs(
    reportedDurationMs: Long,
    sampledAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Long = (reportedDurationMs + (nowElapsedRealtimeMs - sampledAtElapsedRealtimeMs).coerceAtLeast(0L))
    .coerceAtLeast(0L)

fun shouldReturnToLive(targetMs: Long, edgeMs: Long): Boolean =
    (edgeMs - targetMs.coerceIn(0L, edgeMs)) <= LIVE_EDGE_THRESHOLD_MS

fun isLiveRewindGenerationCurrent(requestGeneration: Long, currentGeneration: Long): Boolean =
    requestGeneration == currentGeneration

fun hasLiveStreamSessionChanged(
    oldId: String?,
    oldCreatedAt: String?,
    newId: String?,
    newCreatedAt: String?,
): Boolean =
    (oldId != null && newId != null && oldId != newId) ||
        (oldCreatedAt != null && newCreatedAt != null && oldCreatedAt != newCreatedAt)

fun isSameLiveStreamSession(
    expectedId: String?,
    expectedCreatedAt: String?,
    actualId: String?,
    actualCreatedAt: String?,
): Boolean =
    (expectedId == null || actualId == expectedId) &&
        (expectedCreatedAt == null || actualCreatedAt == expectedCreatedAt)

fun isLiveRewindSourceActiveOrSwitching(
    mode: LivePlaybackMode,
    physicalSourceSwitching: Boolean,
): Boolean = mode is LivePlaybackMode.Rewound || physicalSourceSwitching

fun canUseLiveSource(
    playbackType: String?,
    liveRewindActive: Boolean,
    liveRewindTransitioning: Boolean,
): Boolean = playbackType == BasePlaybackService.STREAM &&
    !liveRewindActive &&
    !liveRewindTransitioning

fun canUseLiveClipSource(
    playbackType: String?,
    liveRewindActive: Boolean,
    liveRewindTransitioning: Boolean,
): Boolean = canUseLiveSource(playbackType, liveRewindActive, liveRewindTransitioning)

fun isCurrentLiveClipSource(expectedMediaId: String?, actualMediaId: String?): Boolean =
    expectedMediaId != null && expectedMediaId == actualMediaId

fun shouldMarkLiveStreamOffline(
    statusKnown: Boolean,
    streamWasLive: Boolean,
    streamPresent: Boolean,
): Boolean = statusKnown && streamWasLive && !streamPresent

enum class LiveRewindSourceTransition {
    NONE,
    TO_VOD,
    TO_LIVE,
}

data class LiveRewindSession(
    val id: String?,
    val createdAt: String?,
)

data class LiveRewindLiveTransitionResult(
    val state: LiveRewindSourceState,
    val shouldDiscoverRecordingVod: Boolean,
)

data class LiveRewindSourceState(
    val mode: LivePlaybackMode = LivePlaybackMode.Live,
    val transition: LiveRewindSourceTransition = LiveRewindSourceTransition.NONE,
    val vod: LiveRewindVod? = null,
    val offline: Boolean = false,
    val frozenEdgeMs: Long? = null,
    val committedSession: LiveRewindSession? = null,
    val pendingSession: LiveRewindSession? = null,
) {
    fun beginDiscovery(): LiveRewindSourceState = copy(transition = LiveRewindSourceTransition.NONE)

    fun requestVod(vod: LiveRewindVod): LiveRewindSourceState = copy(
        transition = LiveRewindSourceTransition.TO_VOD,
        vod = vod,
        offline = false,
    )

    fun completeVod(): LiveRewindSourceState = copy(
        mode = LivePlaybackMode.Rewound(requireNotNull(vod).id),
        transition = LiveRewindSourceTransition.NONE,
    )

    fun requestLive(): LiveRewindSourceState = copy(transition = LiveRewindSourceTransition.TO_LIVE)

    fun observeSession(session: LiveRewindSession): LiveRewindSourceState = if (mode is LivePlaybackMode.Rewound) {
        copy(
            pendingSession = session,
            transition = LiveRewindSourceTransition.TO_LIVE,
            offline = false,
        )
    } else {
        copy(
            mode = LivePlaybackMode.Live,
            committedSession = session,
            pendingSession = null,
            transition = LiveRewindSourceTransition.NONE,
            vod = null,
            offline = false,
            frozenEdgeMs = null,
        )
    }

    fun observeNewSessionWhileRewound(
        session: LiveRewindSession,
        frozenEdgeMs: Long,
    ): LiveRewindSourceState = observeSession(session).let { state ->
        if (mode is LivePlaybackMode.Rewound) {
            state.copy(frozenEdgeMs = this.frozenEdgeMs ?: frozenEdgeMs)
        } else {
            state
        }
    }

    fun recoverSession(session: LiveRewindSession): LiveRewindSourceState {
        if (!offline) return this
        pendingSession?.let { pending ->
            if (isSameLiveStreamSession(
                    expectedId = pending.id,
                    expectedCreatedAt = pending.createdAt,
                    actualId = session.id,
                    actualCreatedAt = session.createdAt,
                )
            ) return copy(offline = false)
            return this
        }
        val committed = committedSession ?: return this
        if (!isSameLiveStreamSession(
                expectedId = committed.id,
                expectedCreatedAt = committed.createdAt,
                actualId = session.id,
                actualCreatedAt = session.createdAt,
            )
        ) return this
        return copy(offline = false, frozenEdgeMs = null)
    }

    fun completeLive(): LiveRewindSourceState = copy(
        mode = LivePlaybackMode.Live,
        transition = LiveRewindSourceTransition.NONE,
        vod = null,
        offline = false,
        frozenEdgeMs = null,
    )

    fun completeLiveTransition(): LiveRewindLiveTransitionResult {
        val pending = pendingSession
        val sessionChanged = pending != null
        return LiveRewindLiveTransitionResult(
            state = copy(
                mode = LivePlaybackMode.Live,
                committedSession = pending ?: committedSession,
                pendingSession = null,
                transition = LiveRewindSourceTransition.NONE,
                vod = if (sessionChanged) null else vod,
                offline = if (sessionChanged) false else offline,
                frozenEdgeMs = if (sessionChanged) null else frozenEdgeMs,
            ),
            shouldDiscoverRecordingVod = sessionChanged,
        )
    }

    fun liveTransitionFailed(): LiveRewindSourceState = copy(transition = LiveRewindSourceTransition.NONE)

    fun streamEnded(edgeMs: Long): LiveRewindSourceState = copy(
        offline = true,
        frozenEdgeMs = frozenEdgeMs ?: edgeMs,
    )

    fun canGoLive(): Boolean = !offline
}

fun freezeLiveEdge(predictedEdgeMs: Long, frozenEdgeMs: Long?): Long =
    frozenEdgeMs ?: predictedEdgeMs

fun selectCurrentRecordingVod(
    candidates: List<LiveRewindVodCandidate>,
    streamStartMs: Long,
    nowWallClockMs: Long,
): LiveRewindVodCandidate? {
    val streamAgeMs = (nowWallClockMs - streamStartMs).coerceAtLeast(0L)
    val durationToleranceMs = max(120_000L, streamAgeMs / 10L)
    return candidates
        .asSequence()
        .filter { candidate ->
            abs(candidate.createdAtMs - streamStartMs) <= LIVE_REWIND_MAX_CREATED_AT_DELTA_MS &&
                abs(candidate.durationMs - streamAgeMs) <= durationToleranceMs
        }
        .minWithOrNull(
            compareBy<LiveRewindVodCandidate> {
                abs(it.createdAtMs - streamStartMs)
            }.thenByDescending { it.createdAtMs },
        )
}

sealed class LivePlaybackMode {
    data object Live : LivePlaybackMode()
    data class Rewound(val vodId: String) : LivePlaybackMode()
}

/** Twitch's checked-in schema has no recording-state field on Video. */
suspend fun GraphQLRepository.findCurrentRecordingVod(
    networkLibrary: String?,
    headers: Map<String, String>,
    channelId: String?,
    channelLogin: String?,
    streamCreatedAt: String?,
): LiveRewindVod? {
    val streamStartMs = streamCreatedAt
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?.takeIf { it > 0L }
        ?: return null

    val response = loadQueryUserVideos(
        networkLibrary = networkLibrary,
        headers = headers,
        id = channelId,
        login = channelLogin.takeIf { channelId.isNullOrBlank() },
        sort = VideoSort.TIME,
        types = listOf(BroadcastType.ARCHIVE),
        first = 5,
        after = null,
    )

    val candidates = response.data?.user?.videos?.edges.orEmpty()
        .asSequence()
        .mapNotNull { edge ->
            val video = edge?.node ?: return@mapNotNull null
            val id = video.id ?: return@mapNotNull null
            val createdAt = video.createdAt?.toString() ?: return@mapNotNull null
            val createdAtMs = Instant.parseOrNull(createdAt)?.toEpochMilliseconds()
                ?.takeIf { it > 0L }
                ?: return@mapNotNull null
            val durationMs = video.lengthSeconds?.toLong()?.times(1000L)
                ?.takeIf { it > 0L }
                ?: return@mapNotNull null
            LiveRewindVodCandidate(
                id = id,
                createdAtMs = createdAtMs,
                durationMs = durationMs,
                createdAt = createdAt,
            )
        }
        .toList()
    val candidate = selectCurrentRecordingVod(
        candidates = candidates,
        streamStartMs = streamStartMs,
        nowWallClockMs = System.currentTimeMillis(),
    ) ?: return null
    return LiveRewindVod(
        id = candidate.id,
        reportedDurationMs = candidate.durationMs,
        sampledAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        createdAt = candidate.createdAt,
    )
}

fun formatBehindLive(targetMs: Long, edgeMs: Long): String {
    val behindMs = (edgeMs - targetMs).coerceAtLeast(0L)
    return if (behindMs <= LIVE_EDGE_THRESHOLD_MS) {
        "LIVE"
    } else {
        "-${android.text.format.DateUtils.formatElapsedTime(behindMs / 1000L)}"
    }
}
