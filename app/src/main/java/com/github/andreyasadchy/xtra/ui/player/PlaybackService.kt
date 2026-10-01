package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.C as Media3C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsDiagnosticsSink
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.common.diagnosticToken
import com.github.andreyasadchy.xtra.ui.common.identityId
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPreparationRepository
import com.github.andreyasadchy.xtra.ui.player.clip.ClipSizeEstimator
import com.github.andreyasadchy.xtra.ui.player.clip.ClipSnapshot
import com.github.andreyasadchy.xtra.ui.player.clip.HlsClipSnapshotMapper
import com.github.andreyasadchy.xtra.ui.player.clip.LiveClipBufferManager
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackController
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackSpeedControl
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import com.github.andreyasadchy.xtra.util.m3u8.VaftBoundaryObservation
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isVaftEnabled
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.preload.VaftPreloadedMediaSource
import com.github.andreyasadchy.xtra.repository.preload.VaftWarmupHandle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.Timer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    lateinit var xtraModule: XtraModule

    private var mediaSession: MediaSession? = null
    private var bootstrapForegroundActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playbackPlayer: ExoPlayer? = null
    private var playbackSessionPlayer: PlaybackSessionPlayer? = null
    private var adaptiveLiveController: AdaptiveLivePlaybackController? = null
    private var adaptiveLiveSpeedControl: AdaptiveLivePlaybackSpeedControl? = null
    private val liveClipBufferManager = LiveClipBufferManager()
    private var liveClipDataSourceFactory: DataSource.Factory? = null
    private var liveClipMediaItemId: String? = null
    private var liveClipMediaItemUri: String? = null
    private var liveClipPreparation: Deferred<ClipPreparationRepository.PreparedLiveClip>? = null
    private var vodClipDataSourceFactory: DataSource.Factory? = null
    private var vodClipMediaItemId: String? = null
    private var vodClipMediaItemUri: String? = null
    private var vodClipSnapshot: ClipSnapshot? = null
    private var vodClipPreparation: Deferred<ClipPreparationRepository.PreparedLiveClip>? = null
    private val diagnostics = PlaybackVideoDiagnosticsStore()
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var backgroundPlayback = false
    private var backgroundVideoSuppressed = false
    private var backgroundRecoveryTimer: Timer? = null
    private var backgroundRecoveryAttempt = 0
    private var proxyMediaPlaylist = false
    private var videoId: Long? = null
    private var offlineVideoId: Int? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private val viewingStatsSourceId = "playback-service:primary"
    private var primaryPlaybackWatchOwnerId: Long? = null
    private var primaryPlaybackWatchGeneration: Long? = null
    private var primaryPlaybackWatchReleased = false
    private var viewingChannelId: String? = null
    private var viewingChannelLogin: String? = null
    private var viewingChannelName: String? = null
    private var viewingChannelImage: String? = null
    private var viewingCategoryId: String? = null
    private var viewingCategoryName: String? = null
    private var viewingCategoryImage: String? = null
    private var viewingTitle: String? = null
    private var viewingStreamPreview: String? = null
    private var viewingContentType: String? = null
    private var viewingContentId: String? = null
    private var streamStartupTrace: StreamStartupTrace? = null
    private var liveRewindActive = false
    private var liveRewindVodId: String? = null
    private var liveRewindTransitioning = false
    private var lastMediaButtonSeekable: Boolean? = null
    private var liveStreamUri: String? = null
    private var vaftHandoffJob: Job? = null
    private var vaftAlternateActive = false
    private var vaftOutputSuppressed = false
    private var vaftCoordinatorJob: Job? = null
    private var vaftGeneration = 0L
    private var vaftLogicalQuality: com.github.andreyasadchy.xtra.model.VideoQuality? = null
    private var vaftVerifiedRendition: com.github.andreyasadchy.xtra.model.VideoQuality? = null
    private var vaftSourceSwitching = false
    private var vaftHandoffPreviousTracks: TrackSelectionParameters? = null
    private var vaftHandoffPreviousMediaItem: MediaItem? = null
    private var vaftHandoffPreviousPositionMs: Long? = null
    private var vaftAuthoritativeUri: String? = null
    private var vaftCurrentPlayerType: String? = null
    private var vaftPrimaryReturnAfterElapsedMs: Long? = null
    private var vaftWarmupToken: String? = null
    private var vaftSourceGeneration = 0L
    private data class TrackedVaftBoundary(
        var observation: VaftBoundaryObservation,
        val sourceGeneration: Long,
        var primaryMediaId: String,
        val primaryUri: String,
        var playlistStartTimeUs: Long,
        var playlistMediaSequence: Long,
        var sourceRelativeClockValid: Boolean = true,
        var lastKnownPhase: VaftPlaybackBoundaryPhase = VaftPlaybackBoundaryPhase.UNKNOWN,
    )
    private enum class VaftPlaybackBoundaryPhase { NONE, BEFORE, ACTIVE, AFTER, UNKNOWN }
    private var trackedVaftBoundary: TrackedVaftBoundary? = null
    private var vaftBoundaryWatchJob: Job? = null
    private var vaftBoundaryWatchMarkerKey: String? = null
    private data class PreparedVaftCandidate(
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
    private var vaftPreparationJob: Job? = null
    private var vaftPreparationMarkerKey: String? = null
    private var vaftPreparationGeneration = -1L
    private var vaftPreparationMediaId: String? = null
    private var vaftPreparationRequestId: String? = null
    private var vaftPreparationStartedAtMs: Long? = null
    private var vaftPreparationGraceDeadlineMs = 0L
    private var vaftPreparedCandidate: PreparedVaftCandidate? = null
    private var vaftCandidateRefreshRequestId: String? = null
    private var vaftHandoffFrameCaptureId: String? = null
    private var vaftHandoffFrameCaptureFuture: SettableFuture<Boolean>? = null
    private var vaftHandoffFrameCaptureResolvedId: String? = null
    private var vaftHandoffFrameCaptureAccepted = false
    private var vaftHandoffTargetMediaId: String? = null
    private var vaftHandoffTargetGeneration = -1L
    private var vaftHandoffTargetFrameRendered = false
    private var lastVaftBoundaryObservationKey: String? = null

    private fun invalidateVaftOwnership() {
        vaftGeneration++
        vaftSourceGeneration++
        vaftBoundaryWatchJob?.cancel()
        vaftBoundaryWatchJob = null
        vaftBoundaryWatchMarkerKey = null
        trackedVaftBoundary = null
        vaftPreparationRequestId = null
        vaftCandidateRefreshRequestId = null
        vaftCoordinatorJob?.cancel()
        vaftHandoffJob?.cancel()
        vaftPreparationJob?.cancel()
        vaftPreparationJob = null
        vaftPreparationMarkerKey = null
        vaftPreparationGeneration = -1L
        vaftPreparationMediaId = null
        vaftPreparationStartedAtMs = null
        vaftPreparationGraceDeadlineMs = 0L
        vaftPreparedCandidate = null
        lastVaftBoundaryObservationKey = null
        if (::xtraModule.isInitialized) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(vaftWarmupToken)
        }
        vaftWarmupToken = null
        vaftHandoffFrameCaptureFuture?.set(false)
        vaftHandoffFrameCaptureFuture = null
        vaftHandoffFrameCaptureId = null
        vaftHandoffFrameCaptureResolvedId = null
        vaftHandoffFrameCaptureAccepted = false
        vaftHandoffTargetMediaId = null
        vaftHandoffTargetGeneration = -1L
        vaftHandoffTargetFrameRendered = false
        vaftCoordinatorJob = null
        vaftHandoffJob = null
        vaftSourceSwitching = false
        vaftHandoffPreviousTracks = null
        vaftHandoffPreviousMediaItem = null
        vaftHandoffPreviousPositionMs = null
        vaftAlternateActive = false
        vaftOutputSuppressed = false
        vaftLogicalQuality = null
        vaftVerifiedRendition = null
        vaftCurrentPlayerType = null
        vaftAuthoritativeUri = null
        vaftPrimaryReturnAfterElapsedMs = null
        publishVaftPlaybackState()
    }

    private fun publishVaftPlaybackState() {
        if (BuildConfig.DEBUG) Log.d("XtraVaft", "state handoff=$vaftSourceSwitching window=${vaftCoordinatorJob?.isActive == true} alternate=$vaftAlternateActive suppressed=$vaftOutputSuppressed generation=$vaftGeneration")
        mediaSession?.broadcastCustomCommand(SessionCommand(VAFT_PLAYBACK_STATE_CHANGED, Bundle.EMPTY), Bundle().apply {
            putBoolean(VAFT_HANDOFF, vaftSourceSwitching)
            putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true)
            putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
            putBoolean(SUPPRESS_VAFT_OUTPUT, vaftOutputSuppressed)
            putString(VAFT_SOURCE_URI, vaftAuthoritativeUri)
            putString(VAFT_HANDOFF_FRAME_CAPTURE_ID, vaftHandoffFrameCaptureId)
            putString(VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID, vaftHandoffFrameCaptureResolvedId)
            putBoolean(VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED, vaftHandoffFrameCaptureAccepted)
            putString(VAFT_HANDOFF_TARGET_MEDIA_ID, vaftHandoffTargetMediaId)
            putLong(VAFT_HANDOFF_GENERATION, vaftHandoffTargetGeneration)
            putBoolean(VAFT_HANDOFF_TARGET_FRAME_RENDERED, vaftHandoffTargetFrameRendered)
            vaftVerifiedRendition?.let { putString(VAFT_VERIFIED_RENDITION, xtraModule.json.encodeToString(it)) }
            vaftLogicalQuality?.let { putString(VAFT_LOGICAL_QUALITY, xtraModule.json.encodeToString(it)) }
            vaftCurrentPlayerType?.let { putString(VAFT_PLAYER_TYPE, it) }
        })
    }
    private var liveStreamExtras: Bundle? = null
    private var resumptionState: PlaybackState? = null
    private var pendingPlaybackQualityState: PlaybackState? = null
    private val mediaPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == C.SYSTEM_MEDIA_CONTROLS_ENABLED ||
            key == C.SYSTEM_MEDIA_ARTWORK_SOURCE ||
            key == C.SYSTEM_MEDIA_SHOW_TITLE ||
            key == C.SYSTEM_MEDIA_SHOW_CATEGORY ||
            key == C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS ||
            key == C.SYSTEM_MEDIA_SHOW_GO_LIVE
        ) {
            Handler(Looper.getMainLooper()).post {
                invalidatePlaybackSessionPlayerState()
                playbackPlayer?.let {
                    refreshCurrentMediaItemMetadata(it)
                    refreshMediaButtonPreferences(it)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build(),
        )
        xtraModule = (application as XtraApp).xtraModule
        lifecycleScope.launch(Dispatchers.IO) {
            ClipPreparationRepository.cleanupStale(File(cacheDir, LIVE_CLIP_DIRECTORY))
            ClipPreparationRepository.cleanupStale(File(cacheDir, VOD_CLIP_DIRECTORY))
        }
        primaryPlaybackWatchOwnerId = xtraModule.primaryPlaybackWatchState.newOwnerId()
        prefs().registerOnSharedPreferenceChangeListener(mediaPreferenceListener)
        val initialLivePolicy = LivePlaybackPolicies.forLowLatency(
            prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
        )
        adaptiveLiveSpeedControl = AdaptiveLivePlaybackSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder().build(),
        )
        val player = xtraModule.streamMedia3Runtime.buildPlaybackPlayer(this) {
            controller, loadControl ->
            adaptiveLiveController = controller
            loadControl.setOnPolicyChangedListener(::applyAdaptiveLivePolicy)
            controller.reset(initialLivePolicy, enabled = false)
            setLivePlaybackSpeedControl(adaptiveLiveSpeedControl!!)
            setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
            setHandleAudioBecomingNoisy(true)
            setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
            setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
        }
        applyAdaptiveLivePolicy()
        playbackPlayer = player
        lifecycleScope.launch {
            while (true) {
                delay(5_000L)
                if (viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                    player.isCurrentMediaItemLive && player.isPlaying
                ) {
                    adaptiveLiveController?.onStableSample(
                        bufferedMs = player.totalBufferedDuration,
                        realtimeMs = SystemClock.elapsedRealtime(),
                    )?.let { changed ->
                        if (changed) applyAdaptiveLivePolicy()
                    }
                }
            }
        }
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateVaft(player)
                    updateViewingStats(player)
                    if (isPlaying) {
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                        val seekablePlaybackType = resumptionState?.type
                        if (savePositionTimer == null &&
                            (seekablePlaybackType == PlaybackContract.VIDEO ||
                                seekablePlaybackType == PlaybackContract.CLIP ||
                                seekablePlaybackType == PlaybackContract.OFFLINE_VIDEO)
                        ) {
                            savePositionTimer = Timer().apply {
                                scheduleAtFixedRate(30000, 30000) {
                                    Handler(Looper.getMainLooper()).post {
                                        updateSavedPosition()
                                    }
                                }
                            }
                        }
                    } else {
                        savePositionTimer?.cancel()
                        savePositionTimer = null
                        updateSavedPosition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (BuildConfig.DEBUG) {
                        Log.w(
                            "PlaybackRecovery",
                            "event=player_error origin=service errorCode=${error.errorCode} " +
                                "background=$backgroundPlayback state=${player.playbackState} " +
                                "playWhenReady=${player.playWhenReady} " +
                                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)}",
                        )
                    }
                    streamStartupTrace?.let { xtraModule.streamPreviewCoordinator.onFullscreenPlaybackFailed() }
                    if (backgroundPlayback && vaftCoordinatorJob?.isActive != true
                        && prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player.playWhenReady
                    ) {
                        scheduleBackgroundRecovery()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateVaft(player)
                    updateViewingStats(player)
                    if (BuildConfig.DEBUG) {
                        val positionMs = player.currentPosition
                        val bufferedPositionMs = player.bufferedPosition
                        val liveOffsetMs = player.currentLiveOffset
                            .takeIf { it != Media3C.TIME_UNSET }
                        Log.d(
                            "PlaybackLifecycle",
                            "event=playback_state pid=${Process.myPid()} player=${player.identityId()} " +
                                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} state=$playbackState " +
                                "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                                "positionMs=$positionMs bufferedPositionMs=$bufferedPositionMs " +
                                "bufferAheadMs=${(bufferedPositionMs - positionMs).coerceAtLeast(0L)} " +
                                "liveOffsetMs=${liveOffsetMs ?: -1L} " +
                                "videoSize=${player.videoSize.width}x${player.videoSize.height}",
                        )
                    }
                    if (playbackState == Player.STATE_READY) {
                        streamStartupTrace?.markReady()
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "PlaybackLifecycle",
                            "event=play_when_ready pid=${Process.myPid()} player=${player.identityId()} " +
                                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} " +
                                "value=$playWhenReady reason=$reason " +
                                "suppression=${player.playbackSuppressionReason} state=${player.playbackState}",
                        )
                    }
                    resumptionState?.let { state ->
                        saveResumptionState(
                            state.copy(position = player.currentPosition, paused = !playWhenReady),
                        )
                    }
                    updateVaft(player)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    updateVaft(player)
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    updateVaft(player)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "PlaybackLifecycle",
                            "event=media_item_transition pid=${Process.myPid()} player=${player.identityId()} " +
                                "itemToken=${diagnosticToken(mediaItem?.mediaId)} reason=$reason " +
                                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                                "positionMs=${player.currentPosition} bufferedPositionMs=${player.bufferedPosition}",
                        )
                    }
                    val livePolicy = LivePlaybackPolicies.forLowLatency(
                        prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                    )
                    adaptiveLiveController?.reset(
                        initialPolicy = livePolicy,
                        enabled = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                            player.isCurrentMediaItemLive && livePolicy.lowLatency,
                    )
                    applyAdaptiveLivePolicy()
                    diagnostics.resetForNewMedia(
                        preserveConfirmedVideoQuality =
                            viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                    )
                    if (mediaItem != null) {
                        diagnostics.update {
                            it.copy(
                                contentProtocol = when (viewingContentType) {
                                    ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                    ViewingPlaybackMetadata.CONTENT_TYPE_VOD -> "HLS"
                                    ViewingPlaybackMetadata.CONTENT_TYPE_CLIP -> "Progressive"
                                    else -> null
                                },
                                isLiveContent = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                                lowLatencyRequested = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                                    prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                            )
                        }
                    }
                    xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(mediaItem)
                    if (viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                        canUseLiveSource(PlaybackContract.STREAM, liveRewindActive, liveRewindTransitioning) &&
                        mediaItem != null
                    ) {
                        updateLiveClipSource(mediaItem)
                    } else if (viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_VOD && mediaItem != null) {
                        val mediaUri = mediaItem.localConfiguration?.uri?.toString()
                        if (vodClipMediaItemId != mediaItem.mediaId || vodClipMediaItemUri != mediaUri) {
                            clearVodClipSource()
                        }
                        vodClipMediaItemId = mediaItem.mediaId
                        vodClipMediaItemUri = mediaUri
                        vodClipDataSourceFactory = xtraModule.streamMedia3Runtime.clipDataSourceFactory(mediaItem.mediaId)
                    } else {
                        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE) {
                            clearLiveClipState()
                        }
                        clearVodClipSource()
                        vodClipDataSourceFactory = null
                        vodClipMediaItemId = null
                        vodClipMediaItemUri = null
                    }
                    refreshCurrentMediaItemMetadata(player)
                    refreshMediaButtonPreferences(player)
                    syncTwitchHlsDiagnostics(player)
                }

                override fun onRenderedFirstFrame() {
                    streamStartupTrace?.markFirstFrame()
                    streamStartupTrace?.let { xtraModule.streamPreviewCoordinator.onFullscreenPlaybackFirstFrame(it.channelLogin) }
                    diagnostics.recordRenderedFirstFrame(player.currentTracks)
                    if (!vaftHandoffTargetFrameRendered && vaftHandoffTargetGeneration == vaftGeneration &&
                        player.currentMediaItem?.mediaId == vaftHandoffTargetMediaId
                    ) {
                        vaftHandoffTargetFrameRendered = true
                        publishVaftPlaybackState()
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "PlaybackLifecycle",
                            "event=rendered_first_frame pid=${Process.myPid()} player=${player.identityId()} " +
                                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} " +
                                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                                "positionMs=${player.currentPosition}",
                        )
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    diagnostics.recordRenderedVideoSize(videoSize.width, videoSize.height, player.currentTracks)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    diagnostics.confirmPendingRenderedVideoSizeAfterTracksChanged(tracks)
                    if (BuildConfig.DEBUG) {
                        val formats = tracks.groups.filter { it.type == Media3C.TRACK_TYPE_VIDEO }.flatMap { group ->
                            (0 until group.length).map { index ->
                                val format = group.getTrackFormat(index)
                                "${format.label}:${format.width}x${format.height}@${format.frameRate}:support=${group.getTrackSupport(index)}:selected=${group.isTrackSelected(index)}"
                            }
                        }
                        Log.d("SmoothHlsQuality", "tracks=$formats")
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updateVaft(player)
                    syncVodClipSource()
                    captureLiveClipManifest()
                    refreshMediaButtonPreferencesIfSeekabilityChanged(player)
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        it.release()
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }
            }
        )
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val classification = classifyVideoDecoder(decoderName)
                diagnostics.update {
                    it.copy(
                        videoDecoderName = decoderName,
                        videoDecoderHardwareAccelerated = classification.hardwareAccelerated,
                    )
                }
                if (BuildConfig.PERF_DIAGNOSTICS) {
                    Log.i(
                        PERF_TAG,
                        "primary videoDecoder=$decoderName " +
                            "hardware=${classification.hardwareAccelerated} " +
                            "initMs=$initializationDurationMs",
                    )
                }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                val inputMediaItem = runCatching {
                    eventTime.timeline
                        .getWindow(eventTime.windowIndex, Timeline.Window())
                        .mediaItem
                }.getOrNull()
                val inputMediaId = inputMediaItem?.mediaId
                val inputUri = inputMediaItem?.localConfiguration?.uri?.toString()
                val currentMediaItem = player.currentMediaItem
                val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
                if (inputMediaId == null || inputUri == null ||
                    inputMediaId != currentMediaItem?.mediaId || inputUri != currentUri
                ) {
                    return
                }
                diagnostics.recordVideoInputFormat(format, inputMediaId, inputUri)
                val quality = diagnostics.confirmedVideoQuality(inputMediaId, inputUri) ?: return
                mediaSession?.broadcastCustomCommand(
                    SessionCommand(VIDEO_INPUT_FORMAT_CHANGED, Bundle.EMPTY),
                    Bundle().apply {
                        putString(VIDEO_QUALITY_URI, inputUri)
                        putString(VIDEO_QUALITY_NAME, quality.name)
                        putString(VIDEO_QUALITY_CODECS, quality.codecs)
                        quality.bitrate?.let { putInt(VIDEO_QUALITY_BITRATE, it) }
                        quality.frameRate?.let { putFloat(VIDEO_QUALITY_FRAME_RATE, it) }
                    },
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                diagnostics.recordDroppedVideoFrames(droppedFrames)
                if (BuildConfig.PERF_DIAGNOSTICS) {
                    Log.i(PERF_TAG, "primary droppedFrames=$droppedFrames elapsedMs=$elapsedMs")
                }
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                val format = mediaLoadData.trackFormat ?: return
                when (mediaLoadData.trackType) {
                    Media3C.TRACK_TYPE_VIDEO -> {
                        diagnostics.update {
                            it.copy(
                                selectedVideoWidth = format.width.takeIf { value -> value > 0 },
                                selectedVideoHeight = format.height.takeIf { value -> value > 0 },
                                videoFrameRate = format.frameRate.takeIf { value -> value > 0f },
                                videoBitrate = firstPositiveBitrate(
                                    format.averageBitrate,
                                    format.peakBitrate,
                                    format.bitrate,
                                ),
                                videoCodec = format.codecs,
                                videoMimeType = format.sampleMimeType,
                            )
                        }
                    }
                    Media3C.TRACK_TYPE_AUDIO -> diagnostics.update {
                        it.copy(
                            audioCodec = format.codecs,
                            audioMimeType = format.sampleMimeType,
                        )
                    }
                }
                if (BuildConfig.PERF_DIAGNOSTICS && mediaLoadData.trackType == Media3C.TRACK_TYPE_VIDEO) {
                    Log.i(
                        PERF_TAG,
                        "primary videoFormat=${format.width}x${format.height} " +
                            "fps=${format.frameRate} bitrate=${format.bitrate} " +
                            "mime=${format.sampleMimeType} codecs=${format.codecs}",
                    )
                }
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                diagnostics.update {
                    it.copy(
                        bandwidthEstimateBitsPerSecond = bitrateEstimate.takeIf { value -> value > 0L },
                    )
                }
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                diagnostics.recordLoad(mediaLoadData.dataType, loadEventInfo.bytesLoaded)
                if (BuildConfig.DEBUG && vaftSourceSwitching) {
                    Log.d("XtraVaft", "handoff load type=${mediaLoadData.dataType} durationMs=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded}")
                }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                diagnostics.update {
                    it.copy(
                        audioCodec = format.codecs,
                        audioMimeType = format.sampleMimeType,
                    )
                }
            }
        })
        val sessionPlayer = PlaybackSessionPlayer(player)
        playbackSessionPlayer = sessionPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer).apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService,
                    REQUEST_CODE_RESUME,
                    Intent(this@PlaybackService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_PLAYER
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            setCallback(
                object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                        if (session.isMediaNotificationController(controller)) {
                            val basePlayerCommands = if (controller.isTrusted) {
                                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                            } else {
                                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS
                            }
                            val playerCommands = basePlayerCommands.buildUpon().apply {
                                mediaNotificationControllerSeekCommandAdditions(isMediaNotificationController = true)
                                    .forEach { command -> add(command) }
                            }.build()
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "PlaybackLifecycle",
                                    "event=media_notification_controller_connected trusted=${controller.isTrusted} " +
                                        "getTimeline=${playerCommands.contains(Player.COMMAND_GET_TIMELINE)} " +
                                        "getCurrentMediaItem=${playerCommands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)} " +
                                        "getMetadata=${playerCommands.contains(Player.COMMAND_GET_METADATA)} " +
                                        "seekPrevious=${playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)} " +
                                        "seekNext=${playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT)} " +
                                        "playerTimeline=${session.player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)} " +
                                        "playerCurrentMediaItem=${session.player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)}",
                                )
                            }
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                                .setAvailablePlayerCommands(playerCommands)
                                .build()
                        }
                        val connectionResult = super.onConnect(session, controller)
                        if (!isTrustedController(controller)) return connectionResult
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(START_LIVE_REWIND, Bundle.EMPTY))
                            add(SessionCommand(GET_LIVE_REWIND_STATE, Bundle.EMPTY))
                            add(SessionCommand(GET_VAFT_PLAYBACK_STATE, Bundle.EMPTY))
                            add(SessionCommand(ACK_VAFT_HANDOFF_FRAME, Bundle.EMPTY))
                            add(SessionCommand(UPDATE_VIEWING_METADATA, Bundle.EMPTY))
                            if (liveRewindActive) add(SessionCommand(GO_LIVE, Bundle.EMPTY))
                            add(SessionCommand(START_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(START_CLIP, Bundle.EMPTY))
                            add(SessionCommand(START_OFFLINE_VIDEO, Bundle.EMPTY))
                            add(SessionCommand(CLEAR_PLAYBACK_RESUMPTION, Bundle.EMPTY))
                            add(SessionCommand(GET_CLIP_STATUS, Bundle.EMPTY))
                            add(SessionCommand(PREPARE_LIVE_CLIP, Bundle.EMPTY))
                            add(SessionCommand(CANCEL_LIVE_CLIP_PREPARATION, Bundle.EMPTY))
                            add(SessionCommand(RELEASE_LIVE_CLIP, Bundle.EMPTY))
                            add(SessionCommand(GET_VOD_CLIP_DESCRIPTOR, Bundle.EMPTY))
                            add(SessionCommand(ESTIMATE_VOD_CLIP_SIZE, Bundle.EMPTY))
                            add(SessionCommand(PREPARE_VOD_CLIP, Bundle.EMPTY))
                            add(SessionCommand(CANCEL_VOD_CLIP_PREPARATION, Bundle.EMPTY))
                            add(SessionCommand(RELEASE_VOD_CLIP, Bundle.EMPTY))
                            add(SessionCommand(CLEAR_VOD_CLIP_SOURCE, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_DYNAMICS_PROCESSING, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_PROXY, Bundle.EMPTY))
                            add(SessionCommand(SET_BACKGROUND_PLAYBACK, Bundle.EMPTY))
                            add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(GET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(CHECK_VAFT, Bundle.EMPTY))
                            add(SessionCommand(GET_QUALITIES, Bundle.EMPTY))
                            add(SessionCommand(GET_DURATION, Bundle.EMPTY))
                            add(SessionCommand(GET_ERROR_CODE, Bundle.EMPTY))
                            add(SessionCommand(GET_MEDIA_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_MULTIVARIANT_PLAYLIST, Bundle.EMPTY))
                            add(SessionCommand(GET_VIDEO_INFO, Bundle.EMPTY))
                            add(SessionCommand(GET_VIDEO_QUALITY, Bundle.EMPTY))
                            add(SessionCommand(SAVE_PLAYBACK_QUALITY, Bundle.EMPTY))
                            add(SessionCommand(RESET_VIDEO_INFO_SIZE, Bundle.EMPTY))
                            add(SessionCommand(VIDEO_INPUT_FORMAT_CHANGED, Bundle.EMPTY))
                        }.build()
                        val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                            .addAll(player.availableCommands)
                            .apply {
                                add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                                if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                                    add(Player.COMMAND_SET_VIDEO_SURFACE)
                                }
                                if (player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                                    add(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                                }
                                if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) {
                                    add(Player.COMMAND_GET_TRACKS)
                                }
                            }
                            .build()
                        return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
                    }

                    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                        if (!isTrustedController(controller)) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                        }
                        if (customCommand.customAction in listOf(START_VIDEO, START_CLIP, START_OFFLINE_VIDEO, CLEAR_PLAYBACK_RESUMPTION)) {
                            invalidateVaftOwnership()
                        }
                        return when (customCommand.customAction) {
                            UPDATE_VIEWING_METADATA -> {
                                handleViewingMetadataCommand(customCommand.customExtras, session.player)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            GO_LIVE -> {
                                if (!liveRewindActive) {
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                }
                                val extras = liveStreamExtras?.let(::Bundle)
                                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "go_live",
                                )
                                setLiveRewindSessionState(transitioning = true)
                                val result = startLiveStream(player, extras, beginNewPlayback = false)
                                result.addListener({
                                    val succeeded = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                                    setLiveRewindSessionState(
                                        active = if (succeeded) false else liveRewindActive,
                                        vodId = if (succeeded) null else liveRewindVodId,
                                        transitioning = false,
                                    )
                                    updatePrimaryPlaybackWatchState(player)
                                    refreshMediaButtonPreferences(player)
                                }, MoreExecutors.directExecutor())
                                result
                            }
                            START_STREAM -> {
                                if (customCommand.customExtras.getBoolean(VAFT_HANDOFF)) {
                                    return startVaftHandoff(player, customCommand.customExtras)
                                }
                                if (BuildConfig.DEBUG && (vaftAlternateActive || vaftCoordinatorJob?.isActive == true)) {
                                    Log.d("XtraVaft", "ownership invalidated by stream start command")
                                }
                                invalidateVaftOwnership()
                                vaftOutputSuppressed = customCommand.customExtras.getBoolean(SUPPRESS_VAFT_OUTPUT)
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "start_stream",
                                )
                                setLiveRewindSessionState(transitioning = true)
                                val result = try {
                                    startLiveStream(player, customCommand.customExtras)
                                } catch (_: Exception) {
                                    setLiveRewindSessionState(transitioning = false)
                                    updatePrimaryPlaybackWatchState(player)
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                                }
                                result.addListener({
                                    val succeeded = runCatching {
                                        result.get().resultCode == SessionResult.RESULT_SUCCESS
                                    }.getOrDefault(false)
                                    setLiveRewindSessionState(
                                        active = if (succeeded) false else liveRewindActive,
                                        vodId = if (succeeded) null else liveRewindVodId,
                                        transitioning = false,
                                    )
                                    updatePrimaryPlaybackWatchState(player)
                                }, MoreExecutors.directExecutor())
                                return result
                            }
                            START_LIVE_REWIND -> {
                                val uri = customCommand.customExtras.getString(URI)?.toUri()
                                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                val vodId = customCommand.customExtras.getString(REWIND_VIDEO_ID)
                                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                val handoffInFlight = vaftSourceSwitching || vaftHandoffJob?.isActive == true
                                val vaftPlaybackOwned = handoffInFlight || vaftCoordinatorJob?.isActive == true
                                val hasLiveReturnState =
                                    !liveStreamExtras?.getString(URI).isNullOrBlank() &&
                                        !liveStreamUri.isNullOrBlank()
                                val replacingActiveRewind =
                                    viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                                        liveRewindActive &&
                                        liveRewindVodId == vodId &&
                                        !liveRewindTransitioning &&
                                        hasLiveReturnState
                                val previousPlayback = if (replacingActiveRewind) {
                                    null
                                } else {
                                    snapshotLiveRewindPlayback(
                                        player,
                                        sourceUriOverride = vaftAuthoritativeUri.takeIf { handoffInFlight },
                                        trackSelectionParametersOverride = vaftHandoffPreviousTracks.takeIf { handoffInFlight },
                                        mediaItemOverride = vaftHandoffPreviousMediaItem.takeIf { handoffInFlight },
                                        positionMsOverride = vaftHandoffPreviousPositionMs.takeIf { handoffInFlight },
                                        volumeOverride = if (vaftPlaybackOwned) {
                                            prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                        } else {
                                            null
                                        },
                                    )
                                }
                                if (!replacingActiveRewind && previousPlayback == null) {
                                    if (BuildConfig.DEBUG) Log.d("LiveRewind", "Cannot rewind without an active live source to restore")
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                }
                                if (handoffInFlight && BuildConfig.DEBUG) {
                                    Log.d("LiveRewind", "Rewind supersedes an uncommitted VAFT source candidate")
                                }
                                invalidateVaftOwnership()
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "start_live_rewind",
                                )
                                setLiveRewindSessionState(transitioning = true)
                                try {
                                    player.setMediaSource(createVodMediaSource(uri))
                                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                    player.seekTo(customCommand.customExtras.getLong(PLAYBACK_POSITION))
                                    clearLiveClipState()
                                    setLiveRewindSessionState(active = true, vodId = vodId)
                                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                                } catch (_: Exception) {
                                    if (replacingActiveRewind) {
                                        // Keep the same logical rewind session; the fragment will force a fresh live source.
                                        setLiveRewindSessionState(active = true, vodId = vodId)
                                    } else {
                                        val rollbackPlayback = requireNotNull(previousPlayback)
                                        setLiveRewindSessionState(
                                            active = rollbackPlayback.liveRewindActive,
                                            vodId = rollbackPlayback.liveRewindVodId,
                                        )
                                        restoreLiveRewindPlayback(player, rollbackPlayback)
                                    }
                                    Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                                } finally {
                                    setLiveRewindSessionState(transitioning = false)
                                    if (!liveRewindActive && player.isCurrentMediaItemLive) {
                                        player.currentMediaItem?.let(::updateLiveClipSource)
                                    }
                                    updatePrimaryPlaybackWatchState(player)
                                    refreshMediaButtonPreferences(player)
                                }
                            }
                            GET_LIVE_REWIND_STATE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(LIVE_REWIND_ACTIVE, liveRewindActive)
                                    putBoolean(LIVE_REWIND_TRANSITIONING, liveRewindTransitioning)
                                    putString(REWIND_VIDEO_ID, liveRewindVodId)
                                }))
                            }
                            GET_VAFT_PLAYBACK_STATE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(VAFT_HANDOFF, vaftSourceSwitching)
                                    putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
                                    putBoolean(SUPPRESS_VAFT_OUTPUT, vaftOutputSuppressed)
                                    putString(VAFT_SOURCE_URI, vaftAuthoritativeUri)
                                    putString(VAFT_HANDOFF_FRAME_CAPTURE_ID, vaftHandoffFrameCaptureId)
                                    putString(VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID, vaftHandoffFrameCaptureResolvedId)
                                    putBoolean(VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED, vaftHandoffFrameCaptureAccepted)
                                    putString(VAFT_HANDOFF_TARGET_MEDIA_ID, vaftHandoffTargetMediaId)
                                    putLong(VAFT_HANDOFF_GENERATION, vaftHandoffTargetGeneration)
                                    putBoolean(VAFT_HANDOFF_TARGET_FRAME_RENDERED, vaftHandoffTargetFrameRendered)
                                    putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true)
                                    vaftVerifiedRendition?.let { putString(VAFT_VERIFIED_RENDITION, xtraModule.json.encodeToString(it)) }
                                    vaftLogicalQuality?.let { putString(VAFT_LOGICAL_QUALITY, xtraModule.json.encodeToString(it)) }
                                    vaftCurrentPlayerType?.let { putString(VAFT_PLAYER_TYPE, it) }
                                }))
                            }
                            ACK_VAFT_HANDOFF_FRAME -> {
                                val requestId = args.getString(VAFT_HANDOFF_FRAME_CAPTURE_ID)
                                val expectedId = vaftHandoffFrameCaptureId
                                val captureFuture = vaftHandoffFrameCaptureFuture
                                val ready = args.getBoolean(VAFT_HANDOFF_FRAME_READY)
                                val ackResult = when {
                                    !vaftSourceSwitching -> "not_switching"
                                    requestId.isNullOrBlank() -> "missing_request"
                                    requestId != expectedId -> "wrong_request"
                                    captureFuture == null -> "no_future"
                                    else -> "accepted"
                                }
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "XtraVaft",
                                        "return_frame_ack request=${requestId?.takeLast(8) ?: "none"} " +
                                            "expected=${expectedId?.takeLast(8) ?: "none"} switching=$vaftSourceSwitching " +
                                            "futurePresent=${captureFuture != null} ready=$ready result=$ackResult",
                                    )
                                }
                                if (ackResult != "accepted") {
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                }
                                captureFuture?.set(ready)
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            GET_CLIP_STATUS -> {
                                val status = liveClipStatus()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(LIVE_CLIP_AVAILABLE, status?.available == true)
                                    putLong(LIVE_CLIP_DURATION_US, status?.durationUs ?: 0L)
                                    putBoolean(VOD_CLIP_AVAILABLE, canCreateVodClip())
                                }))
                            }
                            PREPARE_LIVE_CLIP -> prepareLiveClipCommand()
                            CANCEL_LIVE_CLIP_PREPARATION -> {
                                cancelLiveClipPreparation()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            RELEASE_LIVE_CLIP -> {
                                releaseLiveClip(customCommand.customExtras.getString(CLIP_DIRECTORY))
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            GET_VOD_CLIP_DESCRIPTOR -> {
                                val descriptor = createVodClipDescriptor()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(VOD_CLIP_AVAILABLE, descriptor != null)
                                    descriptor?.let { putVodClipDescriptor(it) }
                                }))
                            }
                            ESTIMATE_VOD_CLIP_SIZE -> {
                                val size = estimateVodClipBytes(
                                    customCommand.customExtras.getInt(CLIP_START_INDEX),
                                    customCommand.customExtras.getInt(CLIP_END_INDEX),
                                    customCommand.customExtras.getLong(CLIP_DURATION_US),
                                )
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    size?.let { putLong(CLIP_ESTIMATED_BYTES, it) }
                                }))
                            }
                            PREPARE_VOD_CLIP -> prepareVodClipCommand(customCommand.customExtras)
                            CANCEL_VOD_CLIP_PREPARATION -> {
                                cancelVodClipPreparation()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            RELEASE_VOD_CLIP -> {
                                releaseVodClip(customCommand.customExtras.getString(CLIP_DIRECTORY))
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CLEAR_VOD_CLIP_SOURCE -> {
                                clearVodClipSource()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CLEAR_PLAYBACK_RESUMPTION -> {
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = false,
                                    reason = "playback_cleared",
                                )
                                clearPlaybackResumptionState()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_VIDEO -> {
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "start_video",
                                )
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getLong(VIDEO_ID).takeIf { it != 0L }
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_VOD,
                                    newId?.toString(),
                                    customCommand.customExtras,
                                )
                                val position = if (videoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = newId
                                offlineVideoId = null
                                clearLiveClipState()
                                clearVodClipSource()
                                vodClipDataSourceFactory = null
                                vodClipMediaItemId = null
                                vodClipMediaItemUri = null
                                val runtime = xtraModule.streamMedia3Runtime
                                val requestedQuality = decodePlaybackQuality(
                                    xtraModule.json,
                                    customCommand.customExtras.getString(PLAYBACK_QUALITY),
                                )
                                val desiredQuality = resumptionHlsQuality(requestedQuality)
                                runtime.qualitySelectionPolicy.set(
                                    name = desiredQuality.name,
                                    bitrate = desiredQuality.bitrate,
                                    codecs = desiredQuality.codecs,
                                )
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "PlaybackResumption",
                                        "start_video_quality name=${desiredQuality.name}",
                                    )
                                }
                                val contentIdentity = "xtra-vod:${newId ?: "unknown"}"
                                val mediaItem = if (uri.isNullOrBlank()) {
                                    MediaItem.Builder().apply {
                                        setMediaId(runtime.newSourceInstanceId(contentIdentity))
                                        setMimeType(MimeTypes.APPLICATION_M3U8)
                                        setMediaMetadata(
                                            MediaMetadata.Builder().apply {
                                                setTitle(title)
                                                setArtist(channelName)
                                                setArtworkUri(channelLogo?.toUri())
                                            }.build()
                                        )
                                    }.build()
                                } else {
                                    runtime.createVodMediaItem(
                                        videoId = newId?.toString() ?: "unknown",
                                        url = uri,
                                        title = title,
                                        channelName = channelName,
                                        channelLogo = channelLogo,
                                    )
                                }
                                player.setMediaSource(runtime.createHlsMediaSource(mediaItem), position)
                                runtime.setPrimaryPlaybackMediaItem(mediaItem)
                                vodClipMediaItemId = mediaItem.mediaId
                                vodClipMediaItemUri = mediaItem.localConfiguration?.uri?.toString()
                                vodClipDataSourceFactory = runtime.clipDataSourceFactory(mediaItem.mediaId)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                session.player.prepare()
                                saveResumptionState(
                                    PlaybackState(
                                        type = PlaybackContract.VIDEO,
                                        videoId = newId?.toString(),
                                        channelId = customCommand.customExtras.getString(CHANNEL_ID),
                                        channelLogin = customCommand.customExtras.getString(CHANNEL_LOGIN),
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        gameId = customCommand.customExtras.getString(GAME_ID),
                                        gameSlug = customCommand.customExtras.getString(GAME_SLUG),
                                        gameName = customCommand.customExtras.getString(GAME_NAME),
                                        title = title,
                                        thumbnail = customCommand.customExtras.getString(THUMBNAIL),
                                        createdAt = customCommand.customExtras.getString(CREATED_AT),
                                        durationSeconds = customCommand.customExtras.getInt(DURATION_SECONDS).takeIf { it != 0 },
                                        videoType = customCommand.customExtras.getString(VIDEO_TYPE),
                                        videoAnimatedPreviewURL = customCommand.customExtras.getString(VIDEO_ANIMATED_PREVIEW),
                                        videoUrl = customCommand.customExtras.getString(PLAYBACK_CONTENT_URL)
                                            ?.let(::canonicalizeTwitchDirectVideoUrl)
                                            ?: uri?.let(::canonicalizeTwitchDirectVideoUrl),
                                        playlistUrl = uri,
                                        position = position,
                                        paused = !session.player.playWhenReady,
                                        qualities = customCommand.customExtras.getString(PLAYBACK_QUALITIES),
                                        quality = customCommand.customExtras.getString(PLAYBACK_QUALITY),
                                        previousQuality = customCommand.customExtras.getString(PLAYBACK_PREVIOUS_QUALITY),
                                        restoreQuality = customCommand.customExtras.getBoolean(PLAYBACK_RESTORE_QUALITY),
                                    ),
                                )
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_CLIP -> {
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "start_clip",
                                )
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_CLIP,
                                    customCommand.customExtras.getString(CLIP_ID),
                                    customCommand.customExtras,
                                )
                                videoId = null
                                offlineVideoId = null
                                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                player.setMediaSource(
                                    ProgressiveMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                    CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                                                }
                                                else -> {
                                                    OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                                                }
                                            }
                                        )
                                    ).createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMediaMetadata(
                                                MediaMetadata.Builder().apply {
                                                    setTitle(title)
                                                    setArtist(channelName)
                                                    setArtworkUri(channelLogo?.toUri())
                                                }.build()
                                            )
                                        }.build()
                                    )
                                )
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                saveResumptionState(
                                    PlaybackState(
                                        type = PlaybackContract.CLIP,
                                        clipId = customCommand.customExtras.getString(CLIP_ID),
                                        channelId = customCommand.customExtras.getString(CHANNEL_ID),
                                        channelLogin = customCommand.customExtras.getString(CHANNEL_LOGIN),
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        gameId = customCommand.customExtras.getString(GAME_ID),
                                        gameSlug = customCommand.customExtras.getString(GAME_SLUG),
                                        gameName = customCommand.customExtras.getString(GAME_NAME),
                                        title = title,
                                        thumbnail = customCommand.customExtras.getString(THUMBNAIL),
                                        createdAt = customCommand.customExtras.getString(CREATED_AT),
                                        durationSeconds = customCommand.customExtras.getInt(DURATION_SECONDS).takeIf { it != 0 },
                                        videoId = customCommand.customExtras.getString(VIDEO_ID_STRING),
                                        videoOffsetSeconds = customCommand.customExtras.getInt(VIDEO_OFFSET_SECONDS).takeIf { it != -1 },
                                        videoCreatedAt = customCommand.customExtras.getString(VIDEO_CREATED_AT),
                                        videoAnimatedPreviewURL = customCommand.customExtras.getString(VIDEO_ANIMATED_PREVIEW),
                                        playlistUrl = uri,
                                        paused = !session.player.playWhenReady,
                                        qualities = customCommand.customExtras.getString(PLAYBACK_QUALITIES),
                                        quality = customCommand.customExtras.getString(PLAYBACK_QUALITY),
                                        previousQuality = customCommand.customExtras.getString(PLAYBACK_PREVIOUS_QUALITY),
                                        restoreQuality = customCommand.customExtras.getBoolean(PLAYBACK_RESTORE_QUALITY),
                                    ),
                                )
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            START_OFFLINE_VIDEO -> {
                                clearBackgroundVideoSuppression(
                                    session.player,
                                    restoreVideo = true,
                                    reason = "start_offline_video",
                                )
                                backgroundPlayback = false
                                val uri = customCommand.customExtras.getString(URI)
                                val title = customCommand.customExtras.getString(TITLE)
                                val channelName = customCommand.customExtras.getString(CHANNEL_NAME)
                                val channelLogo = customCommand.customExtras.getString(CHANNEL_LOGO)
                                val newId = customCommand.customExtras.getInt(VIDEO_ID).takeIf { it != 0 }
                                setViewingMetadata(
                                    ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO,
                                    newId?.toString(),
                                    customCommand.customExtras,
                                )
                                val position = if (offlineVideoId == newId && session.player.currentMediaItem != null) {
                                    session.player.currentPosition
                                } else {
                                    customCommand.customExtras.getLong(PLAYBACK_POSITION)
                                }
                                videoId = null
                                offlineVideoId = newId
                                session.player.setMediaItem(
                                    MediaItem.Builder().apply {
                                        setUri(uri)
                                        setMediaMetadata(
                                            MediaMetadata.Builder().apply {
                                                setTitle(title)
                                                setArtist(channelName)
                                                setArtworkUri(channelLogo?.toUri())
                                            }.build()
                                        )
                                    }.build()
                                )
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                session.player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                session.player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                session.player.prepare()
                                session.player.playWhenReady = customCommand.customExtras.getBoolean(PLAY_WHEN_READY, true)
                                session.player.seekTo(position)
                                saveResumptionState(
                                    PlaybackState(
                                        type = PlaybackContract.OFFLINE_VIDEO,
                                        offlineVideoId = newId,
                                        clipId = customCommand.customExtras.getString(CLIP_ID),
                                        channelId = customCommand.customExtras.getString(CHANNEL_ID),
                                        channelLogin = customCommand.customExtras.getString(CHANNEL_LOGIN),
                                        channelName = channelName,
                                        channelImage = channelLogo,
                                        gameId = customCommand.customExtras.getString(GAME_ID),
                                        gameSlug = customCommand.customExtras.getString(GAME_SLUG),
                                        gameName = customCommand.customExtras.getString(GAME_NAME),
                                        title = title,
                                        thumbnail = customCommand.customExtras.getString(THUMBNAIL),
                                        createdAt = customCommand.customExtras.getString(CREATED_AT),
                                        videoCreatedAt = customCommand.customExtras.getString(VIDEO_CREATED_AT),
                                        playlistUrl = uri,
                                        position = position,
                                        paused = !session.player.playWhenReady,
                                        qualities = customCommand.customExtras.getString(PLAYBACK_QUALITIES),
                                        quality = customCommand.customExtras.getString(PLAYBACK_QUALITY),
                                        previousQuality = customCommand.customExtras.getString(PLAYBACK_PREVIOUS_QUALITY),
                                        restoreQuality = customCommand.customExtras.getBoolean(PLAYBACK_RESTORE_QUALITY),
                                    ),
                                )
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            TOGGLE_DYNAMICS_PROCESSING -> {
                                if (dynamicsProcessing?.enabled == true) {
                                    dynamicsProcessing?.enabled = false
                                } else {
                                    if (dynamicsProcessing == null) {
                                        reinitializeDynamicsProcessing(player.audioSessionId)
                                    } else {
                                        dynamicsProcessing?.enabled = true
                                    }
                                }
                                val enabled = dynamicsProcessing?.enabled == true
                                prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(RESULT, enabled)
                                }))
                            }
                            TOGGLE_PROXY -> {
                                proxyMediaPlaylist = customCommand.customExtras.getBoolean(USING_PROXY)
                                xtraModule.streamMedia3Runtime.setProxyMediaPlaylist(
                                    session.player.currentMediaItem?.mediaId,
                                    proxyMediaPlaylist,
                                )
                                if (viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE && !liveRewindActive) {
                                    advanceLiveClipGeneration()
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            SET_BACKGROUND_PLAYBACK -> {
                                backgroundPlayback = customCommand.customExtras.getBoolean(BACKGROUND_PLAYBACK)
                                if (!backgroundPlayback) {
                                    restoreBackgroundVideoSuppression(session.player)
                                    backgroundRecoveryTimer?.cancel()
                                    backgroundRecoveryTimer = null
                                    backgroundRecoveryAttempt = 0
                                } else if (customCommand.customExtras.getBoolean(SUPPRESS_VIDEO_IN_BACKGROUND)) {
                                    suppressVideoForBackground(session.player)
                                }
                                Futures.immediateFuture(
                                    SessionResult(
                                        SessionResult.RESULT_SUCCESS,
                                        Bundle().apply {
                                            putBoolean(BACKGROUND_VIDEO_SUPPRESSED, backgroundVideoSuppressed)
                                        },
                                    ),
                                )
                            }
                            SET_SLEEP_TIMER -> {
                                val duration = customCommand.customExtras.getLong(DURATION)
                                val endTime = sleepTimerEndTime
                                sleepTimer?.cancel()
                                sleepTimer = null
                                sleepTimerEndTime = 0L
                                if (duration > 0L) {
                                    sleepTimer = Timer().apply {
                                        schedule(duration) {
                                            Handler(Looper.getMainLooper()).post {
                                                savePosition()
                                                runAfterPlaybackPersistence {
                                                    releasePrimaryPlaybackWatchState()
                                                    mediaSession?.player?.let { currentPlayer ->
                                                        clearBackgroundVideoSuppression(
                                                            currentPlayer,
                                                            restoreVideo = false,
                                                            reason = "sleep_timer_stop",
                                                        )
                                                    }
                                                    mediaSession?.player?.clearMediaItems()
                                                    xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
                                                    pauseAllPlayersAndStopSelf()
                                                }
                                            }
                                        }
                                    }
                                    sleepTimerEndTime = System.currentTimeMillis() + duration
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, endTime)
                                }))
                            }
                            GET_SLEEP_TIMER -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, sleepTimerEndTime)
                                }))
                            }
                            CHECK_VAFT -> {
                                if (vaftSourceSwitching) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                                val currentPlayer = playbackPlayer
                                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                                val playlist = (currentPlayer.currentManifest as? HlsManifest)?.mediaPlaylist
                                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                                val publisherEdgeRequiresVaft = TwitchVaftDetector.requiresVaft(playlist)
                                val currentUri = currentPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
                                val tracked = reconcileTrackedVaftBoundary(
                                    currentPlayer,
                                    playlist,
                                    currentUri,
                                    publisherEdgeRequiresVaft,
                                )
                                val phase = tracked?.let {
                                    vaftPlaybackBoundaryPhase(currentPlayer, playlist, it)
                                } ?: vaftUntrackedBoundaryPhase(currentPlayer, playlist, publisherEdgeRequiresVaft)
                                val vaftSegment = if (vaftAlternateActive) {
                                    publisherEdgeRequiresVaft
                                } else {
                                    isPlaybackBoundaryUnsafe(phase, publisherEdgeRequiresVaft, tracked != null)
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(RESULT, vaftSegment)
                                    putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true)
                                    putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
                                    putBoolean(SUPPRESS_VAFT_OUTPUT, vaftOutputSuppressed)
                                    putString(VAFT_SOURCE_URI, vaftAuthoritativeUri)
                                    vaftVerifiedRendition?.let { putString(VAFT_VERIFIED_RENDITION, xtraModule.json.encodeToString(it)) }
                                    vaftLogicalQuality?.let { putString(VAFT_LOGICAL_QUALITY, xtraModule.json.encodeToString(it)) }
                                    vaftCurrentPlayerType?.let { putString(VAFT_PLAYER_TYPE, it) }
                                }))
                            }
                            GET_QUALITIES -> {
                                if (vaftHandoffJob?.isActive == true) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                                val sourceUri = session.player.currentMediaItem?.localConfiguration?.uri?.toString()
                                val playlist = (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist
                                val list = playlist?.variants?.mapNotNull { variant ->
                                    val name = variant.format.label?.takeIf { it.isNotBlank() }
                                        ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
                                    if (name != null) {
                                        VideoQuality(
                                            name,
                                            variant.format.codecs,
                                            variant.format.bitrate,
                                            variant.url.toString(),
                                            variant.format.frameRate.takeIf { it > 0f },
                                        )
                                    } else null
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putString(QUALITIES_SOURCE_URI, sourceUri)
                                    putStringArray(NAMES, list?.map { it.name.toString() }?.toTypedArray())
                                    putStringArray(CODECS, list?.map { it.codecs.toString() }?.toTypedArray())
                                    putStringArray(BITRATES, list?.map { it.bitrate.toString() }?.toTypedArray())
                                    putStringArray(FRAME_RATES, list?.map { it.frameRate.toString() }?.toTypedArray())
                                    putStringArray(URLS, list?.map { it.url.toString() }?.toTypedArray())
                                }))
                            }
                            GET_DURATION -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putLong(RESULT, (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000) ?: 0)
                                }))
                            }
                            GET_ERROR_CODE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putInt(RESULT, (session.player.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0)
                                }))
                            }
                            GET_MEDIA_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putStringArray(RESULT, (session.player.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray())
                                }))
                            }
                            GET_MULTIVARIANT_PLAYLIST -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putStringArray(RESULT, (session.player.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray())
                                }))
                            }
                            GET_VIDEO_INFO -> {
                                Futures.immediateFuture(
                                    SessionResult(
                                        SessionResult.RESULT_SUCCESS,
                                        videoDiagnosticsSnapshot(session.player).toBundle(),
                                    )
                                )
                            }
                            GET_VIDEO_QUALITY -> {
                                val currentMediaItem = session.player.currentMediaItem
                                val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
                                val quality = diagnostics.confirmedVideoQuality(
                                    currentMediaItem?.mediaId,
                                    currentUri,
                                )
                                Futures.immediateFuture(
                                    SessionResult(
                                        SessionResult.RESULT_SUCCESS,
                                        Bundle().apply {
                                            currentUri?.let { putString(VIDEO_QUALITY_URI, it) }
                                            quality?.name?.let { putString(VIDEO_QUALITY_NAME, it) }
                                            quality?.codecs?.let { putString(VIDEO_QUALITY_CODECS, it) }
                                            quality?.bitrate?.let { putInt(VIDEO_QUALITY_BITRATE, it) }
                                        },
                                    ),
                                )
                            }
                            SAVE_PLAYBACK_QUALITY -> {
                                val extras = customCommand.customExtras
                                if (decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))?.name == PlaybackContract.CHAT_ONLY_QUALITY &&
                                    vaftCoordinatorJob?.isActive == true) {
                                    invalidateVaftOwnership()
                                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                                }
                                if (vaftCoordinatorJob?.isActive == true && extras.getString(PLAYBACK_TYPE) == PlaybackContract.STREAM) {
                                    extras.getString(VAFT_LOGICAL_QUALITY)?.let { logicalJson ->
                                        vaftLogicalQuality = decodePlaybackQuality(xtraModule.json, logicalJson)
                                        liveStreamExtras?.putString(PLAYBACK_QUALITY, logicalJson)
                                        resumptionState?.let { saveResumptionState(it.copy(quality = logicalJson)) }
                                    }
                                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                                }
                                val selectedQualityJson = extras.getString(PLAYBACK_QUALITY)
                                if (extras.getString(PLAYBACK_TYPE) == PlaybackContract.STREAM && !liveRewindActive) {
                                    liveStreamExtras?.putString(PLAYBACK_QUALITY, selectedQualityJson)
                                }
                                val selectedQuality = decodePlaybackQuality(xtraModule.json, selectedQualityJson)
                                if (selectedQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY ||
                                    selectedQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY
                                ) {
                                    clearBackgroundVideoSuppression(
                                        session.player,
                                        restoreVideo = false,
                                        reason = "explicit_video_suppression",
                                    )
                                }
                                val qualityState = PlaybackState(
                                    type = extras.getString(PLAYBACK_TYPE),
                                    streamId = extras.getString(PLAYBACK_STREAM_ID),
                                    channelLogin = extras.getString(PLAYBACK_CHANNEL_LOGIN),
                                    videoId = extras.getString(PLAYBACK_VIDEO_ID_STRING),
                                    clipId = extras.getString(PLAYBACK_CLIP_ID_STRING),
                                    offlineVideoId = extras.getInt(PLAYBACK_OFFLINE_VIDEO_ID).takeIf { it != 0 },
                                    videoUrl = extras.getString(PLAYBACK_CONTENT_URL),
                                    qualities = extras.getString(PLAYBACK_QUALITIES),
                                    quality = selectedQualityJson,
                                    previousQuality = extras.getString(PLAYBACK_PREVIOUS_QUALITY),
                                    restoreQuality = extras.getBoolean(PLAYBACK_RESTORE_QUALITY),
                                    playlistUrl = selectedQuality?.url,
                                )
                                val state = resumptionState
                                val matchingState = state?.takeIf {
                                    playbackQualityStateMatches(it, qualityState)
                                }
                                if (matchingState != null) {
                                    pendingPlaybackQualityState = null
                                    saveResumptionState(
                                        matchingState.copy(
                                            qualities = qualityState.qualities,
                                            quality = qualityState.quality,
                                            previousQuality = qualityState.previousQuality,
                                            restoreQuality = qualityState.restoreQuality,
                                            videoUrl = qualityState.videoUrl
                                                ?.let(::canonicalizeTwitchDirectVideoUrl)
                                                ?: state.videoUrl,
                                            playlistUrl = if (matchingState.type == PlaybackContract.STREAM && (liveRewindActive || liveRewindTransitioning)) {
                                                state.playlistUrl
                                            } else qualityState.playlistUrl
                                                ?: session.player.currentMediaItem?.localConfiguration?.uri?.toString()
                                                ?: state.playlistUrl,
                                        ),
                                    )
                                } else {
                                    pendingPlaybackQualityState = qualityState
                                }
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "PlaybackResumption",
                                        "quality_snapshot queued=${matchingState == null} " +
                                            "type=${qualityState.type} name=${selectedQuality?.name}",
                                    )
                                }
                                val completion = SettableFuture.create<SessionResult>()
                                lifecycleScope.launch {
                                    try {
                                        xtraModule.playbackPersistence.flush()
                                        if (BuildConfig.DEBUG && matchingState != null) {
                                            Log.d(
                                                "PlaybackResumption",
                                                "quality_snapshot_flush_complete type=${qualityState.type} " +
                                                    "name=${selectedQuality?.name}",
                                            )
                                        }
                                        completion.set(SessionResult(SessionResult.RESULT_SUCCESS))
                                    } catch (e: CancellationException) {
                                        completion.cancel(false)
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w("PlaybackResumption", "Failed to persist playback quality", e)
                                        if (!completion.isCancelled) {
                                            completion.set(SessionResult(SessionError.ERROR_UNKNOWN))
                                        }
                                    }
                                }
                                completion
                            }
                            RESET_VIDEO_INFO_SIZE -> {
                                diagnostics.resetRenderedVideoSize()
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            else -> super.onCustomCommand(session, controller, customCommand, args)
                        }
                    }

                    override fun onPlaybackResumption(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        isForPlay: Boolean,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        if (BuildConfig.DEBUG) {
                            Log.d(RESUMPTION_TAG, "entered isForPlay=$isForPlay")
                        }
                        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val savedState = xtraModule.playbackPersistence
                                    .getPlaybackStatesAndWait()
                                    .firstOrNull()
                                val mediaItem = if (savedState == null) {
                                    null
                                } else {
                                    createResumptionMediaItem(savedState, isForPlay)
                                }
                                val resumptionPosition = savedState?.let {
                                    resolveResumptionPosition(it)
                                }
                                val itemStartPosition = resumptionPosition
                                    ?: if (savedState?.type == PlaybackContract.STREAM) {
                                        Media3C.TIME_UNSET
                                    } else {
                                        0L
                                    }
                                if (savedState != null &&
                                    shouldRestoreServiceState(isForPlay, mediaItem != null)
                                ) {
                                    withContext(Dispatchers.Main.immediate) {
                                        restoreServiceStateForResumption(
                                            state = savedState,
                                            position = resumptionPosition ?: 0L,
                                            mediaItem = mediaItem,
                                            isForPlay = isForPlay,
                                            player = session.player,
                                        )
                                    }
                                }
                                result.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        mediaItem?.let { listOf(it) } ?: emptyList(),
                                        0,
                                        itemStartPosition,
                                    ),
                                )
                                if (isForPlay && mediaItem == null) {
                                    mainHandler.post(::abortBootstrapPlaybackStart)
                                }
                            } catch (throwable: Throwable) {
                                result.setException(throwable)
                                if (isForPlay) {
                                    mainHandler.post(::abortBootstrapPlaybackStart)
                                }
                            }
                        }
                        return result
                    }
                }
            )
        }.build()
    }

    private fun invalidatePlaybackSessionPlayerState() {
        val player = playbackPlayer ?: return
        val sessionPlayer = playbackSessionPlayer ?: return
        val invalidate = Runnable {
            if (playbackSessionPlayer !== sessionPlayer) return@Runnable
            sessionPlayer.invalidateServiceState()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackLifecycle",
                    "event=session_player_state_invalidated liveRewindActive=$liveRewindActive " +
                        "liveRewindTransitioning=$liveRewindTransitioning " +
                        "seekable=${player.isCurrentMediaItemSeekable} " +
                        "seekInItemAvailable=${player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}",
                )
            }
        }
        if (Looper.myLooper() == player.applicationLooper) {
            invalidate.run()
        } else {
            Handler(player.applicationLooper).post(invalidate)
        }
    }

    private fun setLiveRewindSessionState(
        active: Boolean = liveRewindActive,
        vodId: String? = liveRewindVodId,
        transitioning: Boolean = liveRewindTransitioning,
    ) {
        val commandStateChanged = liveRewindActive != active || liveRewindTransitioning != transitioning
        liveRewindActive = active
        liveRewindVodId = vodId
        liveRewindTransitioning = transitioning
        if (commandStateChanged) invalidatePlaybackSessionPlayerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !bootstrapForegroundActive) {
            ensurePlaybackNotificationChannel()
            val notification = NotificationCompat.Builder(this, PLAYBACK_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.resuming_playback))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        REQUEST_CODE_RESUME,
                        Intent(this, MainActivity::class.java).apply {
                            this.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PLAYBACK_BOOTSTRAP_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(PLAYBACK_BOOTSTRAP_NOTIFICATION_ID, notification)
            }
            bootstrapForegroundActive = true
            if (BuildConfig.DEBUG) Log.d(RESUMPTION_TAG, "bootstrap foreground started")
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun createResumptionMediaItem(
        state: PlaybackState,
        isForPlay: Boolean,
    ): MediaItem? {
        // Media browsers ask for resumption metadata while drawing system
        // controls. Keep that query local; only a Play request may resolve a
        // fresh, short-lived Twitch HLS URL.
        val uri = when {
            state.type == PlaybackContract.STREAM && isForPlay ->
                withTimeoutOrNull(RESUMPTION_STREAM_URL_TIMEOUT_MS) {
                    resolveResumptionStreamUri(state)
                } ?: state.playlistUrl
            state.type == PlaybackContract.STREAM -> state.playlistUrl
            else -> state.playlistUrl ?: state.videoUrl
        }?.takeIf { it.isNotBlank() }
        val metadataOnlyLiveItem = state.type == PlaybackContract.STREAM && !isForPlay
        if (uri == null && !metadataOnlyLiveItem) return null
        val sourceIdentity = when (state.type) {
            PlaybackContract.STREAM -> state.channelLogin?.let { "stream:$it" }
            PlaybackContract.VIDEO -> state.videoId?.let { "video:$it" }
            PlaybackContract.CLIP -> state.clipId?.let { "clip:$it" }
            else -> state.offlineVideoId?.let { "offline:$it" }
        }.orEmpty()
        val mediaId = if (state.type == PlaybackContract.STREAM || state.type == PlaybackContract.VIDEO) {
            xtraModule.streamMedia3Runtime.newSourceInstanceId(sourceIdentity)
        } else {
            sourceIdentity
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .apply { uri?.let { setUri(it.toUri()) } }
            .apply {
                if (state.type == PlaybackContract.STREAM) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(
                                LivePlaybackPolicies.forLowLatency(
                                    prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
                                ).targetOffsetMs,
                            )
                            .build(),
                    )
                } else if (state.type == PlaybackContract.VIDEO) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title)
                    .setArtist(state.channelName)
                    .setArtworkUri(state.channelImage?.toUri())
                    .build(),
            )
            .build()
    }

    private suspend fun resolveResumptionStreamUri(state: PlaybackState): String? {
        if (state.type != PlaybackContract.STREAM) return null
        val channelLogin = state.channelLogin ?: return null
        return runCatching {
            xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = this,
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(
                    this,
                    prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
                ),
                channelLogin = channelLogin,
                randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyHost = prefs().httpProxyHost(),
                proxyPort = prefs().httpProxyPort(),
                proxyUser = prefs().getString(C.PROXY_USER, null),
                proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
            )
        }.getOrNull()
    }

    /**
     * Restores the service-owned state that normal START_* commands establish.
     * This is deliberately called only for an actual playback resumption.
     */
    private suspend fun resolveResumptionPosition(state: PlaybackState): Long? {
        return when (state.type) {
            PlaybackContract.STREAM -> null
            PlaybackContract.VIDEO -> state.videoId?.toLongOrNull()
                ?.let { xtraModule.playerRepository.getVideoPosition(it)?.position }
                ?: state.position
            PlaybackContract.OFFLINE_VIDEO -> state.offlineVideoId
                ?.let { xtraModule.offlineVideosRepository.getById(it)?.lastWatchPosition }
                ?: state.position
            else -> state.position
        }
    }

    private fun restoreServiceStateForResumption(
        state: PlaybackState,
        position: Long,
        mediaItem: MediaItem?,
        isForPlay: Boolean,
        player: Player,
    ) {
        finishViewingStats()

        videoId = null
        offlineVideoId = null
        when (state.type) {
            PlaybackContract.VIDEO -> {
                videoId = state.videoId?.toLongOrNull()
            }
            PlaybackContract.OFFLINE_VIDEO -> {
                offlineVideoId = state.offlineVideoId
            }
        }
        lastSavedPosition = position
        player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        val restoredQuality = decodePlaybackQuality(xtraModule.json, state.quality)
        val resumptionQuality = resumptionHlsQuality(restoredQuality)
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
            name = resumptionQuality.name,
            bitrate = resumptionQuality.bitrate,
            codecs = resumptionQuality.codecs,
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "restored type=${state.type} quality=${resumptionQuality.name}",
            )
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(
                Media3C.TRACK_TYPE_VIDEO,
                restoredQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY ||
                    restoredQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY,
            )
            .build()
        player.setPlaybackSpeed(
            resumptionPlaybackSpeed(
                playbackType = state.type,
                configuredSpeed = prefs().getFloat(C.PLAYER_SPEED, 1f),
            ),
        )

        viewingChannelId = state.channelId
        viewingChannelLogin = state.channelLogin
        viewingChannelName = state.channelName
        viewingChannelImage = state.channelImage
        viewingCategoryId = state.gameId
        viewingCategoryName = state.gameName
        viewingCategoryImage = null
        viewingTitle = state.title
        viewingStreamPreview = state.thumbnail
        viewingContentType = when (state.type) {
            PlaybackContract.STREAM -> ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
            PlaybackContract.VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_VOD
            PlaybackContract.CLIP -> ViewingPlaybackMetadata.CONTENT_TYPE_CLIP
            PlaybackContract.OFFLINE_VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO
            else -> null
        }
        viewingContentId = when (state.type) {
            PlaybackContract.STREAM -> state.streamId
            PlaybackContract.VIDEO -> state.videoId
            PlaybackContract.CLIP -> state.clipId
            PlaybackContract.OFFLINE_VIDEO -> state.offlineVideoId?.toString()
            else -> null
        }

        if (state.type == PlaybackContract.STREAM) {
            val uri = mediaItem?.localConfiguration?.uri?.toString()
                ?: state.playlistUrl
            liveStreamUri = uri
            liveStreamExtras = Bundle().apply {
                putString(URI, uri)
                putString(STREAM_ID, state.streamId)
                putString(CHANNEL_ID, state.channelId)
                putString(CHANNEL_LOGIN, state.channelLogin)
                putString(CHANNEL_NAME, state.channelName)
                putString(CHANNEL_LOGO, state.channelImage)
                putString(GAME_ID, state.gameId)
                putString(GAME_SLUG, state.gameSlug)
                putString(GAME_NAME, state.gameName)
                putString(THUMBNAIL, state.thumbnail)
                putString(TITLE, state.title)
                putBoolean(PLAY_WHEN_READY, isForPlay || !state.paused)
            }
            setLiveRewindSessionState(active = false, vodId = null, transitioning = false)
        }
        beginPrimaryPlaybackWatchState()

        saveResumptionState(
            state.copy(
                playlistUrl = mediaItem?.localConfiguration?.uri?.toString() ?: state.playlistUrl,
                position = position,
                paused = !isForPlay && state.paused,
            ),
        )

        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "restored type=${state.type} videoId=$videoId offlineVideoId=$offlineVideoId " +
                    "position=${state.position ?: 0L}",
            )
        }
    }

    private fun saveResumptionState(state: PlaybackState) {
        val pendingQuality = pendingPlaybackQualityState
        val stateWithQuality = if (pendingQuality != null && playbackQualityStateMatches(state, pendingQuality)) {
            pendingPlaybackQualityState = null
            val stateHasQualitySnapshot = !state.qualities.isNullOrBlank() &&
                !state.quality.isNullOrBlank()
            if (stateHasQualitySnapshot) {
                state
            } else {
                state.copy(
                    qualities = pendingQuality.qualities,
                    quality = pendingQuality.quality,
                    previousQuality = pendingQuality.previousQuality,
                    restoreQuality = pendingQuality.restoreQuality,
                    videoUrl = pendingQuality.videoUrl
                        ?.let(::canonicalizeTwitchDirectVideoUrl)
                        ?: state.videoUrl,
                    playlistUrl = if (state.type == PlaybackContract.STREAM && (liveRewindActive || liveRewindTransitioning)) {
                        state.playlistUrl
                    } else pendingQuality.playlistUrl ?: state.playlistUrl,
                )
            }
        } else {
            state
        }
        // Live positions belong to the current HLS window. They are not valid
        // start positions after a later stream resumption.
        val durableState = if (stateWithQuality.type == PlaybackContract.STREAM) {
            stateWithQuality.copy(position = null)
        } else {
            stateWithQuality
        }
        resumptionState = durableState
        xtraModule.playbackPersistence.savePlaybackState(durableState)
    }

    private fun playbackQualityStateMatches(state: PlaybackState, qualityState: PlaybackState): Boolean {
        if (state.type != qualityState.type) return false
        return when (qualityState.type) {
            PlaybackContract.STREAM -> {
                (qualityState.streamId.isNullOrBlank() || state.streamId == qualityState.streamId) &&
                    (qualityState.channelLogin.isNullOrBlank() ||
                        state.channelLogin.equals(qualityState.channelLogin, ignoreCase = true))
            }
            PlaybackContract.VIDEO -> {
                if (!qualityState.videoId.isNullOrBlank()) {
                    state.videoId == qualityState.videoId
                } else {
                    val requestedVideoIdentity = directVideoIdentity(qualityState.videoUrl)
                    state.videoId.isNullOrBlank() && requestedVideoIdentity != null &&
                        directVideoIdentity(state.videoUrl) == requestedVideoIdentity
                }
            }
            PlaybackContract.CLIP -> state.clipId == qualityState.clipId
            PlaybackContract.OFFLINE_VIDEO -> state.offlineVideoId == qualityState.offlineVideoId
            else -> false
        }
    }

    private fun directVideoIdentity(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val playlistIndex = url.lastIndexOf("/index-dvr.m3u8")
        if (playlistIndex < 0) return url
        val variantStart = url.lastIndexOf('/', playlistIndex - 1)
        val variant = url.substring(variantStart + 1, playlistIndex)
        return if (variant == "chunked" || variant == "audio" || variant.matches(Regex("\\d+p\\d+"))) {
            url.substring(0, variantStart)
        } else {
            url
        }
    }

    private fun clearPlaybackResumptionState() {
        resumptionState = null
        pendingPlaybackQualityState = null
        xtraModule.playbackPersistence.deletePlaybackStates()
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, null).apply {
                for (channelIdx in 0 until channelCount) {
                    for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                        setMbcBandByChannelIndex(
                            channelIdx,
                            bandIdx,
                            getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                attackTime = 0f
                                releaseTime = 0.25f
                                ratio = 1.6f
                                threshold = -50f
                                kneeWidth = 40f
                                preGain = 0f
                                postGain = 10f
                            }
                        )
                    }
                }
                enabled = true
            }
        }
    }

    private fun applyAdaptiveLivePolicy() {
        val targetOffsetMs = adaptiveLiveController?.currentPolicy()?.targetOffsetMs ?: return
        adaptiveLiveSpeedControl?.setAdaptiveTargetLiveOffsetUs(targetOffsetMs * 1_000L)
    }

    private fun prepareLiveClipCommand(): ListenableFuture<SessionResult> {
        val future = SettableFuture.create<SessionResult>()
        lifecycleScope.launch {
            try {
                val prepared = prepareLiveClip().await()
                future.set(SessionResult(SessionResult.RESULT_SUCCESS, prepared.toBundle()))
            } catch (_: Throwable) {
                if (!future.isCancelled) future.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    private fun prepareVodClipCommand(args: Bundle): ListenableFuture<SessionResult> {
        val startIndex = args.getInt(CLIP_START_INDEX)
        val endIndexExclusive = args.getInt(CLIP_END_INDEX)
        val future = SettableFuture.create<SessionResult>()
        lifecycleScope.launch {
            try {
                val prepared = prepareVodClip(startIndex, endIndexExclusive).await()
                future.set(SessionResult(SessionResult.RESULT_SUCCESS, prepared.toBundle()))
            } catch (_: Throwable) {
                if (!future.isCancelled) future.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    private fun ClipPreparationRepository.PreparedLiveClip.toBundle(): Bundle = Bundle().apply {
        putString(CLIP_DIRECTORY, directory.absolutePath)
        putString(CLIP_PLAYLIST, playlist.absolutePath)
        putLongArray(CLIP_BOUNDARIES_US, boundariesUs)
    }

    private fun Bundle.putVodClipDescriptor(descriptor: VodClipDescriptor) {
        putString(VOD_CLIP_MEDIA_ITEM_ID, descriptor.mediaItemId)
        putString(VOD_CLIP_PREVIEW_URI, descriptor.previewUri)
        putIntArray(VOD_CLIP_SEGMENT_DURATIONS_US, descriptor.segmentDurationsUs)
        putLongArray(VOD_CLIP_SEGMENT_BYTE_RANGES, descriptor.segmentByteRangeLengths)
        putLong(VOD_CLIP_INITIAL_POSITION_US, descriptor.initialPositionUs)
        descriptor.bitrateBitsPerSecond?.let { putInt(VOD_CLIP_BITRATE, it) }
    }

    fun liveClipStatus(): LiveClipBufferManager.Status? {
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            liveRewindActive || liveRewindTransitioning || liveClipDataSourceFactory == null ||
            playbackPlayer?.currentMediaItem?.mediaId != liveClipMediaItemId
        ) return null
        return liveClipBufferManager.status(configureLiveClipBuffer())
    }

    fun prepareLiveClip(): Deferred<ClipPreparationRepository.PreparedLiveClip> {
        check(liveClipStatus() != null) { "Live clipping is unavailable for the current source" }
        liveClipPreparation?.takeUnless { it.isCompleted }?.let { return it }
        val snapshot = liveClipBufferManager.snapshot(configureLiveClipBuffer())
        val dataSourceFactory = liveClipDataSourceFactory
        val preparation = lifecycleScope.async(Dispatchers.IO) {
            check(snapshot != null && dataSourceFactory != null) {
                "There is not enough live video available for a clip"
            }
            check(snapshot.durationUs >= LiveClipBufferManager.MIN_CLIP_BUFFER_US) {
                "There is not enough live video available for a clip"
            }
            check(!snapshot.drmInitDataPresent) { "Clipping DRM-protected streams is not supported" }
            ClipPreparationRepository(
                dataSourceFactory = dataSourceFactory,
                rootDirectory = File(cacheDir, LIVE_CLIP_DIRECTORY),
            ).prepare(snapshot)
        }
        liveClipPreparation = preparation
        preparation.invokeOnCompletion { if (liveClipPreparation === preparation) liveClipPreparation = null }
        return preparation
    }

    fun cancelLiveClipPreparation() {
        liveClipPreparation?.cancel()
        liveClipPreparation = null
    }

    fun releaseLiveClip(directoryPath: String?) {
        releaseClipDirectory(LIVE_CLIP_DIRECTORY, directoryPath)
    }

    data class VodClipDescriptor(
        val mediaItemId: String,
        val previewUri: String,
        val segmentDurationsUs: IntArray,
        val segmentByteRangeLengths: LongArray,
        val initialPositionUs: Long,
        val bitrateBitsPerSecond: Int?,
    )

    fun canCreateVodClip(): Boolean {
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_VOD || vodClipDataSourceFactory == null) return false
        val mediaItem = playbackPlayer?.currentMediaItem ?: return false
        if (mediaItem.mediaId != vodClipMediaItemId ||
            mediaItem.localConfiguration?.uri?.toString() != vodClipMediaItemUri
        ) return false
        val playlist = (playbackPlayer?.currentManifest as? HlsManifest)?.mediaPlaylist ?: return false
        if (playlist.protectionSchemes != null || playlist.segments.any { it.drmInitData != null }) return false
        return playlist.segments.any { it.durationUs > 0L } && playbackPlayer?.currentMediaItem?.localConfiguration?.uri != null
    }

    fun createVodClipDescriptor(): VodClipDescriptor? {
        if (!canCreateVodClip()) return null
        val currentPlayer = playbackPlayer ?: return null
        val manifest = currentPlayer.currentManifest as? HlsManifest ?: return null
        val snapshot = HlsClipSnapshotMapper.fromManifest(manifest, generation = 0L)
        if (snapshot.segments.isEmpty() || snapshot.drmInitDataPresent) return null
        val mediaItem = currentPlayer.currentMediaItem ?: return null
        val previewUri = mediaItem.localConfiguration?.uri?.toString() ?: return null
        val segmentDurationsUs = IntArray(snapshot.segments.size) { index ->
            val durationUs = snapshot.segments[index].durationUs
            require(durationUs in 1L..Int.MAX_VALUE.toLong()) { "Unsupported HLS segment duration: $durationUs" }
            durationUs.toInt()
        }
        vodClipSnapshot = snapshot
        return VodClipDescriptor(
            mediaItemId = mediaItem.mediaId,
            previewUri = previewUri,
            segmentDurationsUs = segmentDurationsUs,
            segmentByteRangeLengths = LongArray(snapshot.segments.size) { snapshot.segments[it].byteRangeLength },
            initialPositionUs = currentPlayer.currentPosition * 1_000L,
            bitrateBitsPerSecond = diagnostics.snapshot().videoBitrate,
        )
    }

    fun estimateVodClipBytes(startIndex: Int, endIndexExclusive: Int, selectedDurationUs: Long): Long? {
        val snapshot = vodClipSnapshot ?: return null
        return ClipSizeEstimator.estimateBytes(
            selectedDurationUs = selectedDurationUs,
            segments = snapshot.segments,
            startIndex = startIndex,
            endIndexExclusive = endIndexExclusive,
            bitrateBitsPerSecond = diagnostics.snapshot().videoBitrate,
        )
    }

    fun prepareVodClip(startIndex: Int, endIndexExclusive: Int): Deferred<ClipPreparationRepository.PreparedLiveClip> {
        vodClipPreparation?.takeUnless { it.isCompleted }?.let { return it }
        val source = requireNotNull(vodClipSnapshot) { "VOD clip source is no longer available" }
        val factory = requireNotNull(vodClipDataSourceFactory) { "VOD HLS data source is unavailable" }
        require(startIndex in source.segments.indices)
        require(endIndexExclusive in (startIndex + 1)..source.segments.size)
        val selectedSegments = source.segments.subList(startIndex, endIndexExclusive)
        check(selectedSegments.none { it.hasGap }) { "The selected VOD range contains an unavailable HLS segment" }
        val selected = ClipSnapshot(source.generation, source.renditionId, selectedSegments.toList())
        check(!selected.drmInitDataPresent) { "DRM-protected VOD clipping is not supported" }
        val preparation = lifecycleScope.async(Dispatchers.IO) {
            val availableBytes = StatFs(cacheDir.absolutePath).availableBytes
            val estimatedBytes = ClipSizeEstimator.estimateBytes(
                selectedDurationUs = selected.segments.sumOf { it.durationUs },
                segments = selected.segments,
                startIndex = 0,
                endIndexExclusive = selected.segments.size,
                bitrateBitsPerSecond = diagnostics.snapshot().videoBitrate,
            )
            val requiredBytes = estimatedBytes?.let { it.coerceAtMost((Long.MAX_VALUE - VOD_STORAGE_SAFETY_BYTES) / 2L) * 2L + VOD_STORAGE_SAFETY_BYTES }
            check(requiredBytes == null || requiredBytes <= availableBytes) { "Not enough temporary storage to create this clip" }
            val maxPreparedBytes = ((availableBytes - VOD_STORAGE_SAFETY_BYTES).coerceAtLeast(0L) / 2L)
            check(maxPreparedBytes > 0L) { "Not enough temporary storage to create this clip" }
            ClipPreparationRepository(
                dataSourceFactory = factory,
                rootDirectory = File(cacheDir, VOD_CLIP_DIRECTORY),
                maxBytes = maxPreparedBytes,
            ).prepare(selected)
        }
        vodClipPreparation = preparation
        preparation.invokeOnCompletion { if (vodClipPreparation === preparation) vodClipPreparation = null }
        return preparation
    }

    fun cancelVodClipPreparation() {
        vodClipPreparation?.cancel()
        vodClipPreparation = null
    }

    fun releaseVodClip(directoryPath: String?) {
        releaseClipDirectory(VOD_CLIP_DIRECTORY, directoryPath)
    }

    fun clearVodClipSource() {
        cancelVodClipPreparation()
        vodClipSnapshot = null
    }

    private fun updateLiveClipSource(mediaItem: MediaItem) {
        val uri = mediaItem.localConfiguration?.uri?.toString()
        if (liveClipMediaItemId != mediaItem.mediaId || liveClipMediaItemUri != uri) {
            advanceLiveClipGeneration()
            liveClipMediaItemId = mediaItem.mediaId
            liveClipMediaItemUri = uri
        }
        liveClipDataSourceFactory = xtraModule.streamMedia3Runtime.clipDataSourceFactory(mediaItem.mediaId)
    }

    private fun syncVodClipSource() {
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_VOD) return
        val mediaItem = playbackPlayer?.currentMediaItem ?: return
        val uri = mediaItem.localConfiguration?.uri?.toString()
        if (vodClipMediaItemId == mediaItem.mediaId && vodClipMediaItemUri == uri) return
        clearVodClipSource()
        vodClipMediaItemId = mediaItem.mediaId
        vodClipMediaItemUri = uri
        vodClipDataSourceFactory = xtraModule.streamMedia3Runtime.clipDataSourceFactory(mediaItem.mediaId)
    }

    private fun captureLiveClipManifest() {
        val player = playbackPlayer ?: return
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            liveRewindActive || liveRewindTransitioning ||
            player.currentMediaItem?.mediaId != liveClipMediaItemId || !player.isCurrentMediaItemLive
        ) return
        (player.currentManifest as? HlsManifest)?.let { manifest ->
            configureLiveClipBuffer()
            liveClipBufferManager.capture(manifest)
        }
    }

    private fun configureLiveClipBuffer(): Long {
        val maxDurationSeconds = prefs().getString(C.CLIP_MAX_DURATION_SECONDS, LiveClipBufferManager.DEFAULT_CLIP_DURATION_SECONDS.toString())
            ?.toIntOrNull()
            ?.coerceIn(LiveClipBufferManager.MIN_CLIP_DURATION_SECONDS, LiveClipBufferManager.MAX_CLIP_DURATION_SECONDS)
            ?: LiveClipBufferManager.DEFAULT_CLIP_DURATION_SECONDS
        val maxDurationUs = maxDurationSeconds * 1_000_000L
        liveClipBufferManager.setRetentionUs(maxDurationUs + LiveClipBufferManager.RETENTION_MARGIN_US)
        return maxDurationUs
    }

    private fun clearLiveClipState() {
        cancelLiveClipPreparation()
        liveClipDataSourceFactory = null
        liveClipMediaItemId = null
        liveClipMediaItemUri = null
        liveClipBufferManager.reset()
    }

    private fun advanceLiveClipGeneration() {
        cancelLiveClipPreparation()
        liveClipBufferManager.startNewGeneration()
    }

    private fun releaseClipDirectory(rootName: String, directoryPath: String?) {
        if (directoryPath.isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val root = File(cacheDir, rootName).canonicalFile
                val target = File(directoryPath).canonicalFile
                if (target.parentFile == root) target.deleteRecursively()
            }
        }
    }

    private fun videoCodecsCompatible(actual: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return true
        val expectedVideo = expected.split(',').filter { it.startsWith("avc", true) || it.startsWith("hvc", true) || it.startsWith("hev", true) || it.startsWith("av01", true) || it.startsWith("vp", true) }
        val actualVideo = actual.split(',').filter { it.startsWith("avc", true) || it.startsWith("hvc", true) || it.startsWith("hev", true) || it.startsWith("av01", true) || it.startsWith("vp", true) }
        return expectedVideo.isEmpty() || actualVideo.isEmpty() || expectedVideo.any { wanted -> actualVideo.any { it.startsWith(wanted.take(4), true) } }
    }

    private fun replaceVaftSource(
        player: ExoPlayer,
        extras: Bundle,
        preloadedSource: VaftPreloadedMediaSource? = null,
    ): String? {
        val uri = extras.getString(URI) ?: return null
        val current = player.currentMediaItem ?: return null
        val playWhenReady = player.playWhenReady
        val item = preloadedSource?.mediaItem ?: current.buildUpon().setUri(uri)
            .setMediaId(
                extras.getString(VAFT_HANDOFF_TARGET_MEDIA_ID)
                    ?: "$VAFT_SOURCE_MEDIA_ID_PREFIX${java.util.UUID.randomUUID()}",
            )
            .build()
        trackedVaftBoundary?.takeIf { uri == it.primaryUri && uri == liveStreamUri }?.let { tracked ->
            tracked.primaryMediaId = item.mediaId
            tracked.sourceRelativeClockValid = false
        }
        vaftHandoffTargetMediaId = item.mediaId
        vaftHandoffTargetGeneration = vaftGeneration
        vaftHandoffTargetFrameRendered = false
        publishVaftPlaybackState()
        val candidateQuality = decodePlaybackQuality(xtraModule.json, extras.getString(VAFT_VERIFIED_RENDITION))
        val desired = resumptionHlsQuality(candidateQuality)
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(desired.name, desired.bitrate, desired.codecs)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            .build()
        diagnostics.resetRenderedVideoSize()
        player.volume = 0f
        player.setMediaSource(
            preloadedSource?.mediaSource ?: xtraModule.streamMedia3Runtime.createLiveMediaSource(item),
        )
        // Keep the normal live resumption source authoritative until commit.
        player.prepare()
        player.playWhenReady = playWhenReady
        return item.mediaId
    }

    private data class VaftPositionSnapshot(
        val windowStartTimeMs: Long?,
        val positionMs: Long,
        val liveOffsetMs: Long?,
    )

    private fun snapshotVaftPosition(player: ExoPlayer): VaftPositionSnapshot {
        val window = Timeline.Window()
        val windowStartTimeMs = if (!player.currentTimeline.isEmpty) {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, window).windowStartTimeMs
                .takeIf { it != Media3C.TIME_UNSET }
        } else {
            null
        }
        return VaftPositionSnapshot(
            windowStartTimeMs = windowStartTimeMs,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            liveOffsetMs = player.currentLiveOffset.takeIf { it != Media3C.TIME_UNSET },
        )
    }

    private fun alignVaftPosition(player: ExoPlayer, source: VaftPositionSnapshot): String {
        if (player.currentTimeline.isEmpty || player.currentMediaItemIndex !in 0 until player.currentTimeline.windowCount) {
            return "unavailable"
        }
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (!window.isSeekable) return "not_seekable"
        val targetPositionMs = (if (source.windowStartTimeMs != null && window.windowStartTimeMs != Media3C.TIME_UNSET) {
            val sourceEpochMs = source.windowStartTimeMs + source.positionMs
            val position = sourceEpochMs - window.windowStartTimeMs
            if (position < 0L || (window.durationMs != Media3C.TIME_UNSET && position > window.durationMs)) {
                null
            } else {
                position
            }
        } else if (source.liveOffsetMs != null && player.currentLiveOffset != Media3C.TIME_UNSET) {
            val position = player.currentPosition + player.currentLiveOffset - source.liveOffsetMs
            if (position < 0L || (window.durationMs != Media3C.TIME_UNSET && position > window.durationMs)) {
                null
            } else {
                position
            }
        } else {
            null
        }) ?: return "unavailable"

        val mode = if (source.windowStartTimeMs != null && window.windowStartTimeMs != Media3C.TIME_UNSET) {
            "program_date_time"
        } else {
            "live_offset_best_effort"
        }
        val deltaMs = targetPositionMs - player.currentPosition
        player.seekTo(player.currentMediaItemIndex, targetPositionMs)
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "position_alignment mode=$mode targetPositionMs=$targetPositionMs deltaMs=$deltaMs")
        }
        return mode
    }

    private suspend fun awaitReturnFrameCapture(
        player: ExoPlayer,
        generation: Long,
        outgoingUri: String?,
    ): Boolean {
        val outgoingItem = player.currentMediaItem
        val outgoingPlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !vaftAlternateActive ||
            outgoingUri.isNullOrBlank() || outgoingItem?.localConfiguration?.uri?.toString() != outgoingUri ||
            outgoingUri != vaftAuthoritativeUri || outgoingPlaylist == null ||
            TwitchVaftDetector.requiresVaft(outgoingPlaylist) || player.videoSize.width <= 0 || player.videoSize.height <= 0
        ) {
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "return_frame_capture unavailable sourceClean=${outgoingPlaylist?.let { !TwitchVaftDetector.requiresVaft(it) }}")
            return false
        }

        val requestId = "$generation:${java.util.UUID.randomUUID()}"
        val captureResult = SettableFuture.create<Boolean>()
        vaftHandoffFrameCaptureId = requestId
        vaftHandoffFrameCaptureFuture = captureResult
        vaftHandoffFrameCaptureResolvedId = null
        vaftHandoffFrameCaptureAccepted = false
        vaftOutputSuppressed = false
        publishVaftPlaybackState()
        val captured = withTimeoutOrNull(VAFT_CAPTURE_ACK_TIMEOUT_MS) {
            runCatching { captureResult.awaitVaftFuture() }.getOrDefault(false)
        } == true
        val currentPlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        val stillCleanOutgoing = generation == vaftGeneration &&
            player.currentMediaItem?.mediaId == outgoingItem.mediaId &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == outgoingUri &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == vaftAuthoritativeUri &&
            currentPlaylist != null && !TwitchVaftDetector.requiresVaft(currentPlaylist)
        vaftOutputSuppressed = true
        player.volume = 0f
        vaftHandoffFrameCaptureResolvedId = requestId
        vaftHandoffFrameCaptureAccepted = captured && stillCleanOutgoing
        vaftHandoffFrameCaptureId = null
        vaftHandoffFrameCaptureFuture = null
        publishVaftPlaybackState()
        val ready = vaftHandoffFrameCaptureAccepted
        if (BuildConfig.DEBUG) Log.d("XtraVaft", "return_frame_capture ready=$ready sourceClean=$stillCleanOutgoing")
        return ready
    }

    private fun currentVaftSourceTimeUs(player: ExoPlayer, playlist: HlsMediaPlaylist): Long? {
        val sourceStartTimeUs = playlist.startTimeUs.takeIf { it != Media3C.TIME_UNSET } ?: return null
        return sourceStartTimeUs + player.currentPosition.coerceAtLeast(0L) * 1_000L
    }

    private fun currentVaftEpochTimeUs(player: ExoPlayer, playlist: HlsMediaPlaylist): Long? {
        if (!playlist.hasProgramDateTime) return null
        val window = Timeline.Window()
        val windowStartTimeMs = if (!player.currentTimeline.isEmpty &&
            player.currentMediaItemIndex in 0 until player.currentTimeline.windowCount
        ) {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, window).windowStartTimeMs
                .takeIf { it != Media3C.TIME_UNSET }
        } else {
            null
        }
        val epochStartTimeUs = windowStartTimeMs?.times(1_000L)
            ?: playlist.startTimeUs.takeIf { playlist.hasProgramDateTime && it != Media3C.TIME_UNSET }
            ?: return null
        return epochStartTimeUs + player.currentPosition.coerceAtLeast(0L) * 1_000L
    }

    private fun currentVaftMediaSequence(player: ExoPlayer, playlist: HlsMediaPlaylist): Long? {
        val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
        val segments = playlist.segments
        if (segments.isEmpty()) return null
        if (positionUs < segments.first().relativeStartTimeUs) return playlist.mediaSequence
        val index = segments.indexOfLast { it.relativeStartTimeUs <= positionUs }
        if (index < 0) return null
        val segment = segments[index]
        val segmentEndUs = segment.relativeStartTimeUs + segment.durationUs
        if (positionUs >= segmentEndUs) {
            if (index == segments.lastIndex && positionUs <= playlist.durationUs) {
                return playlist.mediaSequence + segments.size
            }
            return null
        }
        return playlist.mediaSequence + index
    }

    private fun classifyVaftBoundary(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        boundary: VaftBoundaryObservation,
        allowSourceRelativeClock: Boolean,
        allowMediaSequence: Boolean,
        snapshotStartTimeUs: Long? = null,
        snapshotMediaSequence: Long? = null,
    ): VaftPlaybackBoundaryPhase {
        val epochTimeUs = currentVaftEpochTimeUs(player, playlist)
        if (epochTimeUs != null && boundary.epochStartTimeUs != null) {
            return when {
                epochTimeUs < boundary.epochStartTimeUs -> VaftPlaybackBoundaryPhase.BEFORE
                boundary.epochEndTimeUs != null && epochTimeUs >= boundary.epochEndTimeUs -> VaftPlaybackBoundaryPhase.AFTER
                else -> VaftPlaybackBoundaryPhase.ACTIVE
            }
        }
        val sourceTimeUs = currentVaftSourceTimeUs(player, playlist)
        if (allowSourceRelativeClock && sourceTimeUs != null && boundary.sourceStartTimeUs != null) {
            return when {
                sourceTimeUs < boundary.sourceStartTimeUs -> VaftPlaybackBoundaryPhase.BEFORE
                boundary.sourceEndTimeUs != null && sourceTimeUs >= boundary.sourceEndTimeUs -> VaftPlaybackBoundaryPhase.AFTER
                else -> VaftPlaybackBoundaryPhase.ACTIVE
            }
        }
        val mediaSequence = currentVaftMediaSequence(player, playlist)
        if (allowMediaSequence && mediaSequence != null && boundary.sourceMediaSequenceStart != null) {
            return when {
                mediaSequence < boundary.sourceMediaSequenceStart -> VaftPlaybackBoundaryPhase.BEFORE
                boundary.sourceMediaSequenceEnd != null && mediaSequence >= boundary.sourceMediaSequenceEnd -> VaftPlaybackBoundaryPhase.AFTER
                else -> VaftPlaybackBoundaryPhase.ACTIVE
            }
        }
        if (snapshotStartTimeUs == playlist.startTimeUs &&
            snapshotMediaSequence == playlist.mediaSequence && boundary.relativeStartTimeUs != null
        ) {
            val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
            return when {
                positionUs < boundary.relativeStartTimeUs -> VaftPlaybackBoundaryPhase.BEFORE
                boundary.relativeEndTimeUs != null && positionUs >= boundary.relativeEndTimeUs -> VaftPlaybackBoundaryPhase.AFTER
                else -> VaftPlaybackBoundaryPhase.ACTIVE
            }
        }
        return VaftPlaybackBoundaryPhase.UNKNOWN
    }

    private fun vaftPlaybackBoundaryPhase(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        tracked: TrackedVaftBoundary,
    ): VaftPlaybackBoundaryPhase {
        val boundary = tracked.observation
        val onPrimarySource = player.currentMediaItem?.localConfiguration?.uri?.toString() == tracked.primaryUri
        val phase = classifyVaftBoundary(
            player = player,
            playlist = playlist,
            boundary = boundary,
            allowSourceRelativeClock = onPrimarySource && tracked.sourceRelativeClockValid,
            allowMediaSequence = onPrimarySource,
            snapshotStartTimeUs = tracked.playlistStartTimeUs,
            snapshotMediaSequence = tracked.playlistMediaSequence,
        )
        if (phase != VaftPlaybackBoundaryPhase.UNKNOWN) tracked.lastKnownPhase = phase
        if (phase == VaftPlaybackBoundaryPhase.UNKNOWN && tracked.lastKnownPhase == VaftPlaybackBoundaryPhase.ACTIVE) {
            return VaftPlaybackBoundaryPhase.ACTIVE
        }
        return phase
    }

    private fun reconcileTrackedVaftBoundary(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        currentUri: String?,
        publisherEdgeRequiresVaft: Boolean,
    ): TrackedVaftBoundary? {
        val primaryUri = liveStreamUri ?: return null
        val currentItem = player.currentMediaItem
        val currentMediaId = currentItem?.mediaId
        val sourceCanDiscover = currentUri == primaryUri
        val existing = trackedVaftBoundary?.takeIf {
            it.sourceGeneration == vaftSourceGeneration && it.primaryUri == primaryUri
        }
        if (existing != null && sourceCanDiscover && !vaftAlternateActive && !vaftSourceSwitching &&
            currentMediaId != null && currentMediaId != existing.primaryMediaId
        ) {
            if (currentMediaId == vaftHandoffTargetMediaId) {
                existing.primaryMediaId = currentMediaId
                existing.sourceRelativeClockValid = false
            } else {
                trackedVaftBoundary = null
                vaftSourceGeneration++
                discardVaftPreparation()
            }
        } else if (existing != null) {
            val latestObservation = if (sourceCanDiscover) {
                TwitchVaftDetector.visibleBoundaries(playlist)
                    .firstOrNull { it.markerKey == existing.observation.markerKey }
            } else {
                null
            }
            if (latestObservation != null) {
                existing.observation = latestObservation
                existing.playlistStartTimeUs = playlist.startTimeUs
                existing.playlistMediaSequence = playlist.mediaSequence
                existing.sourceRelativeClockValid = latestObservation.sourceStartTimeUs != null
            }
            if (vaftPlaybackBoundaryPhase(player, playlist, existing) != VaftPlaybackBoundaryPhase.AFTER) {
                trackedVaftBoundary = existing
                return existing
            }
            trackedVaftBoundary = null
        }

        if (!sourceCanDiscover || currentMediaId == null) return null
        val visible = TwitchVaftDetector.visibleBoundaries(playlist)
        val visiblePhases = visible.map { boundary ->
            boundary to classifyVaftBoundary(
                player,
                playlist,
                boundary,
                allowSourceRelativeClock = true,
                allowMediaSequence = true,
                snapshotStartTimeUs = playlist.startTimeUs,
                snapshotMediaSequence = playlist.mediaSequence,
            )
        }
        val next = visiblePhases.firstOrNull { it.second == VaftPlaybackBoundaryPhase.ACTIVE }?.first
            ?: visiblePhases.firstOrNull { it.second == VaftPlaybackBoundaryPhase.BEFORE }?.first
            ?: visiblePhases.firstOrNull {
                publisherEdgeRequiresVaft && it.second == VaftPlaybackBoundaryPhase.UNKNOWN
            }?.first
            ?: if (publisherEdgeRequiresVaft) {
            TwitchVaftDetector.activeBoundary(playlist)
        } else {
            null
        }
        if (next == null) return null
        val phase = classifyVaftBoundary(
            player,
            playlist,
            next,
            allowSourceRelativeClock = true,
            allowMediaSequence = true,
            snapshotStartTimeUs = playlist.startTimeUs,
            snapshotMediaSequence = playlist.mediaSequence,
        )
        val tracked = TrackedVaftBoundary(
            observation = next,
            sourceGeneration = vaftSourceGeneration,
            primaryMediaId = currentMediaId,
            primaryUri = primaryUri,
            playlistStartTimeUs = playlist.startTimeUs,
            playlistMediaSequence = playlist.mediaSequence,
            lastKnownPhase = phase,
        )
        trackedVaftBoundary = tracked
        return tracked
    }

    private fun vaftBoundaryLeadMs(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        tracked: TrackedVaftBoundary,
    ): Long? {
        val boundary = tracked.observation
        val epochTimeUs = currentVaftEpochTimeUs(player, playlist)
        if (epochTimeUs != null && boundary.epochStartTimeUs != null) {
            return (boundary.epochStartTimeUs - epochTimeUs) / 1_000L
        }
        val onPrimarySource = player.currentMediaItem?.localConfiguration?.uri?.toString() == tracked.primaryUri
        val sourceTimeUs = currentVaftSourceTimeUs(player, playlist)
        if (onPrimarySource && tracked.sourceRelativeClockValid && sourceTimeUs != null && boundary.sourceStartTimeUs != null) {
            return (boundary.sourceStartTimeUs - sourceTimeUs) / 1_000L
        }
        val currentSequence = currentVaftMediaSequence(player, playlist)
        val targetSequence = boundary.sourceMediaSequenceStart
        if (onPrimarySource && currentSequence != null && targetSequence != null && currentSequence < targetSequence &&
            targetSequence in playlist.mediaSequence..(playlist.mediaSequence + playlist.segments.size)
        ) {
            val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
            val targetIndex = (targetSequence - playlist.mediaSequence).toInt()
            val targetPositionUs = playlist.segments.getOrNull(targetIndex)?.relativeStartTimeUs ?: playlist.durationUs
            return (targetPositionUs - positionUs) / 1_000L
        }
        if (playlist.startTimeUs == tracked.playlistStartTimeUs && playlist.mediaSequence == tracked.playlistMediaSequence &&
            boundary.relativeStartTimeUs != null
        ) {
            val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
            return (boundary.relativeStartTimeUs - positionUs) / 1_000L
        }
        return null
    }

    private fun vaftBoundaryRemainingMs(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        tracked: TrackedVaftBoundary,
    ): Long? {
        val boundary = tracked.observation
        val epochTimeUs = currentVaftEpochTimeUs(player, playlist)
        if (epochTimeUs != null && boundary.epochEndTimeUs != null) {
            return ((boundary.epochEndTimeUs - epochTimeUs) / 1_000L).coerceAtLeast(0L)
        }
        val onPrimarySource = player.currentMediaItem?.localConfiguration?.uri?.toString() == tracked.primaryUri
        val sourceTimeUs = currentVaftSourceTimeUs(player, playlist)
        if (onPrimarySource && tracked.sourceRelativeClockValid && sourceTimeUs != null && boundary.sourceEndTimeUs != null) {
            return ((boundary.sourceEndTimeUs - sourceTimeUs) / 1_000L).coerceAtLeast(0L)
        }
        val currentSequence = currentVaftMediaSequence(player, playlist)
        val endSequence = boundary.sourceMediaSequenceEnd
        if (onPrimarySource && currentSequence != null && endSequence != null &&
            endSequence in (playlist.mediaSequence + 1)..(playlist.mediaSequence + playlist.segments.size)
        ) {
            val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
            val endIndex = (endSequence - playlist.mediaSequence).toInt()
            val endPositionUs = playlist.segments.getOrNull(endIndex)?.relativeStartTimeUs ?: playlist.durationUs
            return ((endPositionUs - positionUs) / 1_000L).coerceAtLeast(0L)
        }
        if (playlist.startTimeUs == tracked.playlistStartTimeUs && playlist.mediaSequence == tracked.playlistMediaSequence &&
            boundary.relativeEndTimeUs != null
        ) {
            val positionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
            return ((boundary.relativeEndTimeUs - positionUs) / 1_000L).coerceAtLeast(0L)
        }
        return null
    }

    private fun isPlaybackBoundaryUnsafe(
        phase: VaftPlaybackBoundaryPhase,
        publisherEdgeRequiresVaft: Boolean,
        trackedBoundary: Boolean = false,
    ): Boolean = phase == VaftPlaybackBoundaryPhase.ACTIVE ||
        (phase == VaftPlaybackBoundaryPhase.UNKNOWN && (publisherEdgeRequiresVaft || trackedBoundary))

    private fun vaftUntrackedBoundaryPhase(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        publisherEdgeRequiresVaft: Boolean,
    ): VaftPlaybackBoundaryPhase {
        if (!publisherEdgeRequiresVaft) return VaftPlaybackBoundaryPhase.NONE
        val boundaries = TwitchVaftDetector.visibleBoundaries(playlist)
        if (boundaries.isEmpty()) return VaftPlaybackBoundaryPhase.UNKNOWN
        val phases = boundaries.map { boundary ->
            classifyVaftBoundary(
                player,
                playlist,
                boundary,
                allowSourceRelativeClock = true,
                allowMediaSequence = true,
                snapshotStartTimeUs = playlist.startTimeUs,
                snapshotMediaSequence = playlist.mediaSequence,
            )
        }
        return when {
            VaftPlaybackBoundaryPhase.ACTIVE in phases -> VaftPlaybackBoundaryPhase.ACTIVE
            VaftPlaybackBoundaryPhase.UNKNOWN in phases -> VaftPlaybackBoundaryPhase.UNKNOWN
            VaftPlaybackBoundaryPhase.BEFORE in phases -> VaftPlaybackBoundaryPhase.BEFORE
            else -> VaftPlaybackBoundaryPhase.AFTER
        }
    }

    private fun ensureVaftBoundaryWatcher(player: ExoPlayer, markerKey: String) {
        if (vaftBoundaryWatchJob?.isActive == true && vaftBoundaryWatchMarkerKey == markerKey) return
        vaftBoundaryWatchJob?.cancel()
        vaftBoundaryWatchJob = null
        vaftBoundaryWatchMarkerKey = markerKey
        val sourceGeneration = vaftSourceGeneration
        vaftBoundaryWatchJob = lifecycleScope.launch {
            val thisJob = currentCoroutineContext()[Job] ?: return@launch
            try {
                while (sourceGeneration == vaftSourceGeneration && player.playWhenReady) {
                    val tracked = trackedVaftBoundary ?: break
                    if (tracked.observation.markerKey != markerKey) break
                    val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist ?: break
                    val publisherEdgeRequiresVaft = TwitchVaftDetector.requiresVaft(playlist)
                    val phase = vaftPlaybackBoundaryPhase(player, playlist, tracked)
                    if (phase == VaftPlaybackBoundaryPhase.AFTER) {
                        updateVaft(player)
                        break
                    }
                    if (phase !in setOf(VaftPlaybackBoundaryPhase.BEFORE, VaftPlaybackBoundaryPhase.ACTIVE)) break
                    val leadMs = vaftBoundaryLeadMs(player, playlist, tracked)
                    val baseDelayMs = when {
                        !player.isPlaying -> 500L
                        leadMs != null && leadMs <= 2_000L -> 100L
                        leadMs != null && leadMs <= 15_000L -> 250L
                        else -> 1_000L
                    }
                    val playbackSpeed = player.playbackParameters.speed
                        .takeIf { it.isFinite() && it > 0f }
                        ?.coerceIn(0.25f, 4f) ?: 1f
                    delay((baseDelayMs / playbackSpeed).toLong().coerceIn(25L, 1_000L))
                    if (!player.playWhenReady) break
                    updateVaft(player)
                }
            } finally {
                if (vaftBoundaryWatchJob === thisJob) {
                    vaftBoundaryWatchJob = null
                    vaftBoundaryWatchMarkerKey = null
                }
            }
        }
    }

    private fun updateVaft(player: ExoPlayer) {
        val activePlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val enabled = resumptionState?.type == PlaybackContract.STREAM && prefs().isVaftEnabled() &&
            !liveRewindActive && !liveRewindTransitioning && !liveStreamUri.isNullOrBlank()
        if (!enabled || activePlaylist == null) {
            vaftBoundaryWatchJob?.cancel()
            vaftBoundaryWatchJob = null
            vaftBoundaryWatchMarkerKey = null
            if (!enabled) {
                trackedVaftBoundary = null
                discardVaftPreparation()
            }
            return
        }
        if (currentUri != liveStreamUri && currentUri != vaftAuthoritativeUri && !vaftSourceSwitching) {
            vaftBoundaryWatchJob?.cancel()
            vaftBoundaryWatchJob = null
            vaftBoundaryWatchMarkerKey = null
            trackedVaftBoundary = null
            discardVaftPreparation()
            return
        }
        val publisherEdgeRequiresVaft = TwitchVaftDetector.requiresVaft(activePlaylist)
        var trackedBoundary = reconcileTrackedVaftBoundary(
            player,
            activePlaylist,
            currentUri,
            publisherEdgeRequiresVaft,
        )
        var boundaryPhase = trackedBoundary?.let {
            vaftPlaybackBoundaryPhase(player, activePlaylist, it)
        } ?: vaftUntrackedBoundaryPhase(player, activePlaylist, publisherEdgeRequiresVaft)
        if (boundaryPhase == VaftPlaybackBoundaryPhase.AFTER) {
            trackedBoundary = null
            trackedVaftBoundary = null
            discardVaftPreparation()
            boundaryPhase = VaftPlaybackBoundaryPhase.AFTER
        }
        logVaftBoundaryObservation(player, activePlaylist, publisherEdgeRequiresVaft, trackedBoundary, boundaryPhase)
        if (!player.playWhenReady) {
            vaftBoundaryWatchJob?.cancel()
            vaftBoundaryWatchJob = null
            vaftBoundaryWatchMarkerKey = null
        } else if (trackedBoundary != null && boundaryPhase in setOf(
                VaftPlaybackBoundaryPhase.BEFORE,
                VaftPlaybackBoundaryPhase.ACTIVE,
            )
        ) {
            ensureVaftBoundaryWatcher(player, trackedBoundary.observation.markerKey)
        }
        if (currentUri == liveStreamUri && player.playWhenReady &&
            boundaryPhase == VaftPlaybackBoundaryPhase.BEFORE && !vaftSourceSwitching &&
            vaftHandoffJob?.isActive != true && vaftCoordinatorJob?.isActive != true
        ) {
            prepareVaftCandidateAhead(player, activePlaylist)
            vaftPreparedCandidate?.let { prepared ->
                maybeRefreshVaftCandidate(player, activePlaylist, trackedBoundary, prepared)
                maybeWarmVaftCandidate(player, activePlaylist, prepared)
            }
        }
        if (vaftCoordinatorJob?.isActive == true && !vaftAlternateActive && !vaftSourceSwitching &&
            currentUri == vaftAuthoritativeUri && boundaryPhase == VaftPlaybackBoundaryPhase.ACTIVE
        ) {
            trackedBoundary?.let { extendVaftPrimaryReturnHold(vaftBoundaryRemainingMs(player, activePlaylist, it)) }
        }
        if (vaftCoordinatorJob?.isActive == true && !vaftSourceSwitching &&
            currentUri == vaftAuthoritativeUri) {
            val suppress = if (vaftAlternateActive) {
                TwitchVaftDetector.requiresVaft(activePlaylist)
            } else {
                isPlaybackBoundaryUnsafe(boundaryPhase, publisherEdgeRequiresVaft, trackedBoundary != null)
            }
            if (suppress != vaftOutputSuppressed) {
                vaftOutputSuppressed = suppress
                player.volume = if (suppress) 0f else prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                publishVaftPlaybackState()
            }
        }
        if (vaftCoordinatorJob?.isActive == true && !vaftSourceSwitching && !vaftAlternateActive &&
            currentUri == vaftAuthoritativeUri &&
            player.playbackState == Player.STATE_READY &&
            !isPlaybackBoundaryUnsafe(boundaryPhase, publisherEdgeRequiresVaft, trackedBoundary != null)
        ) {
            vaftGeneration++
            vaftCoordinatorJob?.cancel()
            vaftCoordinatorJob = null
            discardVaftPreparation()
            vaftOutputSuppressed = false
            player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
            publishVaftPlaybackState()
            return
        }
        if (vaftCoordinatorJob?.isActive == true || vaftSourceSwitching || liveRewindActive || liveRewindTransitioning ||
            resumptionState?.type != PlaybackContract.STREAM || !prefs().isVaftEnabled()) return
        if (currentUri != liveStreamUri) return
        val boundaryRequiresVaft = boundaryPhase == VaftPlaybackBoundaryPhase.ACTIVE ||
            (boundaryPhase == VaftPlaybackBoundaryPhase.UNKNOWN &&
                (publisherEdgeRequiresVaft || trackedBoundary != null))
        if (!boundaryRequiresVaft) return
        val playlist = activePlaylist
        if (currentUri == null || currentUri != liveStreamUri) {
            discardVaftPreparation()
            return
        }
        val primaryExtras = liveStreamExtras?.let(::Bundle) ?: return
        val login = primaryExtras.getString(CHANNEL_LOGIN) ?: return
        val triggerMarkerKey = trackedBoundary?.observation?.markerKey
            ?: TwitchVaftDetector.activeBoundary(playlist)?.markerKey
        val currentMediaId = player.currentMediaItem?.mediaId
        val preparationMatches = triggerMarkerKey != null && vaftPreparationMarkerKey == triggerMarkerKey &&
            vaftPreparationGeneration == vaftGeneration && vaftPreparationMediaId == currentMediaId &&
            vaftPreparationRequestId != null
        val generation = if (preparationMatches) vaftGeneration else ++vaftGeneration
        if (!preparationMatches) {
            discardVaftPreparation()
        } else {
            vaftPreparationGraceDeadlineMs = SystemClock.elapsedRealtime() + VAFT_PRELOAD_HANDOFF_GRACE_MS
        }
        val types = TwitchVaftController { SystemClock.elapsedRealtime() }
        val primaryType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        val deviceId = if (prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true)) {
            java.util.UUID.randomUUID().toString().replace("-", "")
        } else prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason")
        vaftLogicalQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, primaryExtras.getString(PLAYBACK_QUALITY))
        vaftAuthoritativeUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        trackedBoundary?.let { extendVaftPrimaryReturnHold(vaftBoundaryRemainingMs(player, playlist, it)) }
        vaftVerifiedRendition = diagnostics.confirmedVideoQuality(player.currentMediaItem?.mediaId, vaftAuthoritativeUri)
        backgroundRecoveryTimer?.cancel()
        backgroundRecoveryTimer = null
        vaftOutputSuppressed = true
        player.volume = 0f
        publishVaftPlaybackState()
        vaftCoordinatorJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            publishVaftPlaybackState()
            var lastVaftLoopDiagnostic: String? = null
            while (generation == vaftGeneration && !liveRewindActive && !liveRewindTransitioning && prefs().isVaftEnabled()) {
                val currentPlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                val publisherRequiresNow = currentPlaylist?.let(TwitchVaftDetector::requiresVaft) == true
                val trackedNow = trackedVaftBoundary
                val phaseNow = if (trackedNow != null && currentPlaylist != null) {
                    vaftPlaybackBoundaryPhase(player, currentPlaylist, trackedNow)
                } else if (currentPlaylist != null) {
                    vaftUntrackedBoundaryPhase(player, currentPlaylist, publisherRequiresNow)
                } else {
                    VaftPlaybackBoundaryPhase.NONE
                }
                val primaryUnsafe = isPlaybackBoundaryUnsafe(phaseNow, publisherRequiresNow, trackedNow != null)
                val clean = player.playerError == null &&
                    player.currentMediaItem?.localConfiguration?.uri?.toString() == vaftAuthoritativeUri &&
                    currentPlaylist != null && if (vaftAlternateActive) {
                        !TwitchVaftDetector.requiresVaft(currentPlaylist)
                    } else {
                        !primaryUnsafe
                    }
                if (!vaftAlternateActive && clean) {
                    vaftOutputSuppressed = false
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    break
                }
                if (vaftOutputSuppressed != !clean) {
                    vaftOutputSuppressed = !clean
                    player.volume = if (clean) prefs().getInt(C.PLAYER_VOLUME, 100) / 100f else 0f
                    publishVaftPlaybackState()
                }
                val waitForPrimaryMs = (vaftPrimaryReturnAfterElapsedMs ?: 0L) - SystemClock.elapsedRealtime()
                if (vaftAlternateActive && clean) {
                    if (waitForPrimaryMs > 0L) {
                        delay(minOf(waitForPrimaryMs, TwitchVaftController.RETRY_COOLDOWN_MS))
                        continue
                    }
                }
                val primaryEligible = if (!vaftAlternateActive || waitForPrimaryMs <= 0L) {
                    listOf(primaryType).filter { types.canAttemptPlayerType(it) }
                } else {
                    emptyList()
                }
                val eligible = if (vaftAlternateActive && clean) primaryEligible else if (vaftAlternateActive) {
                    primaryEligible + types.playerTypesForVaft(vaftCurrentPlayerType).filter { it != primaryType }
                } else types.playerTypesForVaft(primaryType)
                if (BuildConfig.DEBUG) {
                    val diagnostic = "alternate=$vaftAlternateActive clean=$clean holdRemainingMs=${waitForPrimaryMs.coerceAtLeast(0L)} " +
                        "eligible=${eligible.joinToString() }"
                    if (diagnostic != lastVaftLoopDiagnostic) {
                        Log.d("XtraVaft", "recovery $diagnostic")
                        lastVaftLoopDiagnostic = diagnostic
                    }
                }
                val preparedCandidate = if (preparationMatches) {
                    awaitPreparedVaftCandidate(
                        player,
                        triggerMarkerKey,
                        generation,
                        timeoutMs = (vaftPreparationGraceDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                    )
                } else {
                    null
                }
                preparedCandidate?.let { prepared ->
                    (player.currentManifest as? HlsManifest)?.mediaPlaylist?.let { latestPlaylist ->
                        maybeWarmVaftCandidate(player, latestPlaylist, prepared)
                    }
                }
                val candidate = preparedCandidate?.candidate ?: loadCleanVaftCandidate(
                    login = login,
                    playerTypes = eligible,
                    deviceId = deviceId,
                    preferredQuality = vaftLogicalQuality?.takeUnless { it.name.equals("Auto", true) }
                        ?: diagnostics.confirmedVideoQuality(player.currentMediaItem?.mediaId, vaftAuthoritativeUri),
                    timeoutMs = 55_000L,
                    onPlayerTypeAttempt = types::onPlayerTypeAttemptStarted,
                )
                if (generation != vaftGeneration) break
                val latest = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                val latestPublisherRequires = latest?.let(TwitchVaftDetector::requiresVaft) == true
                val latestTracked = trackedVaftBoundary
                val latestPhase = if (latest != null && latestTracked != null) {
                    vaftPlaybackBoundaryPhase(player, latest, latestTracked)
                } else if (latest != null) {
                    vaftUntrackedBoundaryPhase(player, latest, latestPublisherRequires)
                } else {
                    VaftPlaybackBoundaryPhase.NONE
                }
                if (!vaftAlternateActive && player.currentMediaItem?.localConfiguration?.uri?.toString() == vaftAuthoritativeUri &&
                    player.playerError == null && latest != null &&
                    !isPlaybackBoundaryUnsafe(latestPhase, latestPublisherRequires, latestTracked != null)
                ) {
                    vaftOutputSuppressed = false
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    break
                }
                var tryNextPlayerTypeImmediately = false
                if (candidate != null) {
                    val returningPrimary = candidate.playerType == primaryType
                    if (returningPrimary && !vaftAlternateActive) continue
                    if (!returningPrimary && vaftAlternateActive && candidate.playerType == vaftCurrentPlayerType) continue
                    val extras = Bundle(primaryExtras).apply {
                        putString(URI, candidate.url)
                        putBoolean(VAFT_ALTERNATE_ACTIVE, !returningPrimary)
                        putString(VAFT_PLAYER_TYPE, candidate.playerType)
                        candidate.verifiedRendition?.let { putString(VAFT_VERIFIED_RENDITION, xtraModule.json.encodeToString(it)) }
                    }
                    val preloadedSource = preparedCandidate?.let { prepared ->
                        awaitPreparedVaftSource(
                            player,
                            prepared,
                            candidate.url,
                            generation,
                            timeoutMs = (vaftPreparationGraceDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                        )
                    }
                    val future = startVaftHandoff(player, extras, preloadedSource)
                    vaftPreparedCandidate = null
                    val committed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                        future.addListener({
                            if (continuation.isActive) continuation.resumeWith(runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS })
                        }, MoreExecutors.directExecutor())
                    }
                    if (generation != vaftGeneration) break
                    if (!committed) {
                        types.onHandoffFailed(candidate.playerType)
                        tryNextPlayerTypeImmediately = true
                    }
                    if (committed && returningPrimary) break
                }
                delay(
                    if (tryNextPlayerTypeImmediately) VAFT_DIFFERENT_TYPE_RETRY_YIELD_MS
                    else TwitchVaftController.RETRY_COOLDOWN_MS,
                )
            }
            if (generation == vaftGeneration) vaftCoordinatorJob = null
            publishVaftPlaybackState()
        }
        vaftCoordinatorJob?.start()
    }

    private fun logVaftBoundaryObservation(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        publisherEdgeRequiresVaft: Boolean,
        tracked: TrackedVaftBoundary?,
        phase: VaftPlaybackBoundaryPhase,
    ) {
        if (!BuildConfig.DEBUG) return
        val currentPositionUs = player.currentPosition.coerceAtLeast(0L) * 1_000L
        val boundary = tracked?.observation ?: if (publisherEdgeRequiresVaft) {
            TwitchVaftDetector.activeBoundary(playlist)
        } else {
            TwitchVaftDetector.firstVisibleBoundary(playlist, afterPositionUs = currentPositionUs)
        }
        if (boundary == null) {
            lastVaftBoundaryObservationKey = null
            if (publisherEdgeRequiresVaft) Log.d("XtraVaft", "boundary phase=unknown publisherEdge=true marker=unmapped")
            return
        }
        val leadMs = tracked?.let { vaftBoundaryLeadMs(player, playlist, it) }
            ?: boundary.relativeStartTimeUs?.let { (it - currentPositionUs) / 1_000L }
        val phaseName = phase.name.lowercase()
        val observationKey = "${boundary.markerKey}:$phaseName:$publisherEdgeRequiresVaft"
        if (observationKey == lastVaftBoundaryObservationKey) return
        lastVaftBoundaryObservationKey = observationKey

        val window = Timeline.Window()
        val windowStartTimeMs = if (!player.currentTimeline.isEmpty) {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, window).windowStartTimeMs
                .takeIf { it != Media3C.TIME_UNSET }
        } else {
            null
        }
        val tailStartUs = playlist.segments.lastOrNull()?.relativeStartTimeUs ?: Media3C.TIME_UNSET
        val liveOffsetMs = player.currentLiveOffset.takeIf { it != Media3C.TIME_UNSET }
        Log.d(
            "XtraVaft",
            "boundary phase=$phaseName publisherEdge=$publisherEdgeRequiresVaft basis=${boundary.basis} marker=${diagnosticToken(boundary.markerKey)} " +
                "boundaryWindowUs=${boundary.relativeStartTimeUs ?: -1L} boundaryEpochUs=${boundary.epochStartTimeUs ?: -1L} " +
                "boundaryEndEpochUs=${boundary.epochEndTimeUs ?: -1L} sourceTimeUs=${currentVaftSourceTimeUs(player, playlist) ?: -1L} " +
                "currentPositionUs=$currentPositionUs bufferedPositionUs=${player.bufferedPosition * 1_000L} " +
                "liveOffsetMs=${liveOffsetMs ?: -1L} playlistTailStartUs=$tailStartUs " +
                "playlistDurationUs=${playlist.durationUs} hasPdt=${playlist.hasProgramDateTime} " +
                "windowStartTimeMs=${windowStartTimeMs ?: -1L} leadMs=${leadMs ?: -1L} playWhenReady=${player.playWhenReady}",
        )
    }

    private fun prepareVaftCandidateAhead(player: ExoPlayer, playlist: HlsMediaPlaylist) {
        val currentItem = player.currentMediaItem ?: run {
            discardVaftPreparation()
            return
        }
        val sourceUri = currentItem.localConfiguration?.uri?.toString()
        if (sourceUri.isNullOrBlank() || sourceUri != liveStreamUri || vaftAlternateActive ||
            vaftSourceSwitching || vaftHandoffJob?.isActive == true || liveRewindActive || liveRewindTransitioning ||
            resumptionState?.type != PlaybackContract.STREAM
        ) {
            discardVaftPreparation()
            return
        }
        val tracked = trackedVaftBoundary?.takeIf {
            it.sourceGeneration == vaftSourceGeneration && it.primaryUri == sourceUri
        }
        if (tracked == null || vaftPlaybackBoundaryPhase(
                player,
                playlist,
                tracked,
            ) != VaftPlaybackBoundaryPhase.BEFORE
        ) {
            discardVaftPreparation()
            return
        }
        val boundary = tracked.observation
        val leadMs = vaftBoundaryLeadMs(player, playlist, tracked) ?: return
        if (leadMs !in 1L..VAFT_PREPARE_LOOKAHEAD_MS) {
            if (vaftPreparationMarkerKey != null && vaftPreparationMarkerKey != boundary.markerKey) {
                discardVaftPreparation()
            }
            return
        }
        val extras = liveStreamExtras ?: run {
            discardVaftPreparation()
            return
        }
        val login = extras.getString(CHANNEL_LOGIN)?.takeIf { it.isNotBlank() } ?: run {
            discardVaftPreparation()
            return
        }
        val mediaId = currentItem.mediaId
        val runtime = xtraModule.streamMedia3Runtime
        val configurationFingerprint = runtime.configurationFingerprintFor(player) ?: run {
            discardVaftPreparation()
            return
        }
        val qualityIntent = runtime.qualitySelectionPolicy.snapshot()
        val qualityRevision = runtime.qualitySelectionPolicy.revision()
        val prepared = vaftPreparedCandidate
        if (prepared != null && prepared.markerKey == boundary.markerKey &&
            prepared.requestId == vaftPreparationRequestId &&
            prepared.vaftGeneration == vaftGeneration && prepared.sourceGeneration == vaftSourceGeneration &&
            prepared.playbackMediaId == mediaId &&
            prepared.configurationFingerprint == configurationFingerprint &&
            prepared.qualityIntent == qualityIntent && prepared.qualityIntentRevision == qualityRevision &&
            SystemClock.elapsedRealtime() - prepared.preparedAtMs <= VAFT_PREPARED_CANDIDATE_MAX_AGE_MS
        ) {
            maybeWarmVaftCandidate(player, playlist, prepared)
            return
        }
        if (vaftPreparationMarkerKey == boundary.markerKey && vaftPreparationGeneration == vaftGeneration &&
            vaftPreparationMediaId == mediaId && vaftPreparationRequestId != null && vaftPreparationJob?.isActive == true
        ) return
        val lastAttemptAtMs = vaftPreparationStartedAtMs
        if (vaftPreparationMarkerKey == boundary.markerKey && vaftPreparationGeneration == vaftGeneration &&
            vaftPreparationMediaId == mediaId && vaftPreparationJob?.isActive != true && vaftPreparedCandidate == null &&
            lastAttemptAtMs != null && SystemClock.elapsedRealtime() - lastAttemptAtMs < VAFT_PREPARE_RETRY_COOLDOWN_MS
        ) return

        discardVaftPreparation()
        val generation = vaftGeneration
        val primaryType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        val playerTypes = TwitchVaftController { SystemClock.elapsedRealtime() }.playerTypesForVaft(primaryType)
        if (playerTypes.isEmpty()) return
        val deviceId = if (prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true)) {
            java.util.UUID.randomUUID().toString().replace("-", "")
        } else {
            prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason")
        }
        val preferredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))
            ?: diagnostics.confirmedVideoQuality(mediaId, sourceUri)
        vaftPreparationMarkerKey = boundary.markerKey
        vaftPreparationGeneration = generation
        vaftPreparationMediaId = mediaId
        val requestId = java.util.UUID.randomUUID().toString()
        vaftPreparationRequestId = requestId
        vaftPreparationStartedAtMs = SystemClock.elapsedRealtime()
        vaftPreparationJob = lifecycleScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val candidate = loadCleanVaftCandidate(
                    login = login,
                    playerTypes = playerTypes,
                    deviceId = deviceId,
                    preferredQuality = preferredQuality,
                    timeoutMs = 12_000L,
                )
                val latestPlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                val latestPublisherRequires = latestPlaylist?.let(TwitchVaftDetector::requiresVaft) == true
                val latestTracked = trackedVaftBoundary
                val latestPhase = if (latestPlaylist != null && latestTracked != null) {
                    vaftPlaybackBoundaryPhase(player, latestPlaylist, latestTracked)
                } else if (latestPublisherRequires) {
                    VaftPlaybackBoundaryPhase.UNKNOWN
                } else {
                    VaftPlaybackBoundaryPhase.NONE
                }
                val stillOwned = generation == vaftGeneration && player.currentMediaItem?.mediaId == mediaId &&
                    player.currentMediaItem?.localConfiguration?.uri?.toString() == sourceUri && sourceUri == liveStreamUri &&
                    latestTracked != null && latestTracked.observation.markerKey == boundary.markerKey &&
                    latestTracked.sourceGeneration == vaftSourceGeneration && latestPhase != VaftPlaybackBoundaryPhase.AFTER &&
                    vaftPreparationMarkerKey == boundary.markerKey && vaftPreparationGeneration == generation &&
                    vaftPreparationMediaId == mediaId && vaftPreparationRequestId == requestId &&
                    configurationFingerprint == runtime.configurationFingerprintFor(player) &&
                    qualityIntent == runtime.qualitySelectionPolicy.snapshot() &&
                    qualityRevision == runtime.qualitySelectionPolicy.revision()
                if (candidate != null && candidate.verifiedClean && stillOwned) {
                    val result = PreparedVaftCandidate(
                        requestId = requestId,
                        markerKey = boundary.markerKey,
                        vaftGeneration = generation,
                        sourceGeneration = vaftSourceGeneration,
                        playbackMediaId = mediaId,
                        configurationFingerprint = configurationFingerprint,
                        qualityIntent = qualityIntent,
                        qualityIntentRevision = qualityRevision,
                        candidate = candidate,
                        preparedAtMs = SystemClock.elapsedRealtime(),
                    )
                    vaftPreparedCandidate = result
                    if (BuildConfig.DEBUG) {
                        Log.d("XtraVaft", "future_candidate_ready basis=${boundary.basis} leadMs=$leadMs playerType=${candidate.playerType}")
                    }
                    if (latestPlaylist != null) maybeWarmVaftCandidate(player, latestPlaylist, result)
                } else if (BuildConfig.DEBUG) {
                    Log.d("XtraVaft", "future_candidate_discarded basis=${boundary.basis} leadMs=$leadMs owned=$stillOwned clean=${candidate?.verifiedClean == true}")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.d("XtraVaft", "future_candidate_failed type=${error::class.simpleName}")
            } finally {
                if (vaftPreparationJob === thisJob) vaftPreparationJob = null
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "future_candidate_start basis=${boundary.basis} leadMs=$leadMs playerTypes=${playerTypes.size}")
        }
    }

    private fun maybeRefreshVaftCandidate(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        tracked: TrackedVaftBoundary?,
        prepared: PreparedVaftCandidate,
    ) {
        tracked ?: return
        val leadMs = vaftBoundaryLeadMs(player, playlist, tracked) ?: return
        if (prepared.refreshAttempted || leadMs !in 1L..VAFT_CANDIDATE_REFRESH_LEAD_MS ||
            vaftPreparationJob?.isActive == true || !isVaftCandidateCurrent(player, prepared)
        ) return
        val extras = liveStreamExtras ?: return
        val login = extras.getString(CHANNEL_LOGIN)?.takeIf { it.isNotBlank() } ?: return
        val sourceUri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val sourceMediaId = player.currentMediaItem?.mediaId ?: return
        val runtime = xtraModule.streamMedia3Runtime
        val configurationFingerprint = runtime.configurationFingerprintFor(player) ?: return
        val qualityIntent = runtime.qualitySelectionPolicy.snapshot()
        val qualityRevision = runtime.qualitySelectionPolicy.revision()
        val primaryType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        val playerTypes = TwitchVaftController { SystemClock.elapsedRealtime() }.playerTypesForVaft(primaryType)
        if (playerTypes.isEmpty()) return
        val deviceId = if (prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true)) {
            java.util.UUID.randomUUID().toString().replace("-", "")
        } else {
            prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason")
        }
        val preferredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))
            ?: diagnostics.confirmedVideoQuality(sourceMediaId, sourceUri)
        val requestId = java.util.UUID.randomUUID().toString()
        prepared.refreshAttempted = true
        vaftCandidateRefreshRequestId = requestId
        vaftPreparationJob = lifecycleScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val candidate = loadCleanVaftCandidate(
                    login = login,
                    playerTypes = playerTypes,
                    deviceId = deviceId,
                    preferredQuality = preferredQuality,
                    timeoutMs = 9_000L,
                )
                val latestPlaylist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                val latestTracked = trackedVaftBoundary
                val publisherEdgeRequires = latestPlaylist?.let(TwitchVaftDetector::requiresVaft) == true
                val latestPhase = if (latestPlaylist != null && latestTracked != null) {
                    vaftPlaybackBoundaryPhase(player, latestPlaylist, latestTracked)
                } else if (latestPlaylist != null) {
                    vaftUntrackedBoundaryPhase(player, latestPlaylist, publisherEdgeRequires)
                } else {
                    VaftPlaybackBoundaryPhase.NONE
                }
                val stillOwned = candidate?.verifiedClean == true &&
                    requestId == vaftCandidateRefreshRequestId &&
                    latestTracked != null && latestTracked.observation.markerKey == tracked.observation.markerKey &&
                    latestTracked.sourceGeneration == vaftSourceGeneration &&
                    latestPhase != VaftPlaybackBoundaryPhase.AFTER &&
                    vaftGeneration == prepared.vaftGeneration && prepared.sourceGeneration == vaftSourceGeneration &&
                    sourceUri == liveStreamUri &&
                    player.currentMediaItem?.mediaId == sourceMediaId &&
                    player.currentMediaItem?.localConfiguration?.uri?.toString() == sourceUri &&
                    configurationFingerprint == runtime.configurationFingerprintFor(player) &&
                    qualityIntent == runtime.qualitySelectionPolicy.snapshot() &&
                    qualityRevision == runtime.qualitySelectionPolicy.revision()
                if (stillOwned) {
                    prepared.warmup?.token?.let(runtime::discardVaftCandidateWarmup)
                    val replacement = PreparedVaftCandidate(
                        requestId = requestId,
                        markerKey = tracked.observation.markerKey,
                        vaftGeneration = prepared.vaftGeneration,
                        sourceGeneration = vaftSourceGeneration,
                        playbackMediaId = sourceMediaId,
                        configurationFingerprint = configurationFingerprint,
                        qualityIntent = qualityIntent,
                        qualityIntentRevision = qualityRevision,
                        candidate = candidate,
                        preparedAtMs = SystemClock.elapsedRealtime(),
                        refreshAttempted = true,
                    )
                    vaftPreparedCandidate = replacement
                    vaftPreparationRequestId = requestId
                    vaftCandidateRefreshRequestId = null
                    if (BuildConfig.DEBUG) {
                        Log.d("XtraVaft", "future_candidate_refresh_ready basis=${tracked.observation.basis} leadMs=$leadMs playerType=${candidate.playerType}")
                    }
                    if (latestPlaylist != null) maybeWarmVaftCandidate(player, latestPlaylist, replacement)
                } else if (BuildConfig.DEBUG) {
                    Log.d("XtraVaft", "future_candidate_refresh_discarded basis=${tracked.observation.basis} leadMs=$leadMs")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.d("XtraVaft", "future_candidate_refresh_failed type=${error::class.simpleName}")
            } finally {
                if (vaftCandidateRefreshRequestId == requestId) vaftCandidateRefreshRequestId = null
                if (vaftPreparationJob === thisJob) vaftPreparationJob = null
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "future_candidate_refresh_start basis=${tracked.observation.basis} leadMs=$leadMs")
        }
    }

    private fun maybeWarmVaftCandidate(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        prepared: PreparedVaftCandidate,
    ) {
        if (!player.playWhenReady) return
        val tracked = trackedVaftBoundary?.takeIf {
            it.observation.markerKey == prepared.markerKey &&
                it.sourceGeneration == vaftSourceGeneration && it.primaryUri == liveStreamUri
        } ?: return
        if (!isVaftCandidateCurrent(player, prepared)) return
        if (prepared.nearTriggerWarmStarted) return
        val publisherEdgeRequiresVaft = TwitchVaftDetector.requiresVaft(playlist)
        val phase = vaftPlaybackBoundaryPhase(player, playlist, tracked)
        val playbackLeadMs = vaftBoundaryLeadMs(player, playlist, tracked)
        val readyToWarm = phase == VaftPlaybackBoundaryPhase.BEFORE && playbackLeadMs != null &&
            playbackLeadMs in 1L..VAFT_SAMPLE_WARMUP_LEAD_MS
        val justTriggered = (phase == VaftPlaybackBoundaryPhase.ACTIVE ||
            (phase == VaftPlaybackBoundaryPhase.UNKNOWN && publisherEdgeRequiresVaft)) &&
            SystemClock.elapsedRealtime() <= vaftPreparationGraceDeadlineMs
        if (readyToWarm || justTriggered) {
            startVaftCandidateWarmup(player, tracked.observation, prepared, playbackLeadMs)
        }
    }

    private fun isVaftCandidateCurrent(player: ExoPlayer, prepared: PreparedVaftCandidate): Boolean {
        val runtime = xtraModule.streamMedia3Runtime
        return prepared.requestId == vaftPreparationRequestId && prepared.markerKey == vaftPreparationMarkerKey &&
            prepared.vaftGeneration == vaftGeneration && prepared.sourceGeneration == vaftSourceGeneration &&
            prepared.playbackMediaId == player.currentMediaItem?.mediaId &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == liveStreamUri && liveStreamUri != null &&
            !vaftAlternateActive && !vaftSourceSwitching && vaftHandoffJob?.isActive != true &&
            !liveRewindActive && !liveRewindTransitioning && resumptionState?.type == PlaybackContract.STREAM &&
            prefs().isVaftEnabled() && prepared.configurationFingerprint == runtime.configurationFingerprintFor(player) &&
            prepared.qualityIntent == runtime.qualitySelectionPolicy.snapshot() &&
            prepared.qualityIntentRevision == runtime.qualitySelectionPolicy.revision() &&
            SystemClock.elapsedRealtime() - prepared.preparedAtMs in 0L..VAFT_PREPARED_CANDIDATE_MAX_AGE_MS
    }

    private fun startVaftCandidateWarmup(
        player: ExoPlayer,
        boundary: VaftBoundaryObservation,
        prepared: PreparedVaftCandidate,
        playbackLeadMs: Long?,
    ) {
        if (prepared.nearTriggerWarmStarted || prepared.warmup != null || !isVaftCandidateCurrent(player, prepared)) return
        val runtime = xtraModule.streamMedia3Runtime
        val extras = liveStreamExtras ?: return
        val handle = runtime.beginVaftCandidateWarmup(
            playbackPlayer = player,
            vaftGeneration = prepared.vaftGeneration,
            channelLogin = extras.getString(CHANNEL_LOGIN) ?: return,
            url = prepared.candidate.url,
            playerType = prepared.candidate.playerType,
            title = extras.getString(TITLE),
            channelName = extras.getString(CHANNEL_NAME),
            channelLogo = extras.getString(CHANNEL_LOGO),
            qualityIntent = prepared.qualityIntent,
            verifiedRenditionUrl = prepared.candidate.verifiedRendition?.url,
        ) ?: return
        prepared.warmup = handle
        prepared.nearTriggerWarmStarted = true
        vaftWarmupToken = handle.token
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "future_candidate_warm_start basis=${boundary.basis} playbackLeadMs=${playbackLeadMs ?: -1L}")
        }
    }

    private suspend fun loadCleanVaftCandidate(
        login: String,
        playerTypes: List<String>,
        deviceId: String?,
        preferredQuality: VideoQuality?,
        timeoutMs: Long,
        onPlayerTypeAttempt: (String) -> Unit = {},
    ): PlayerRepository.StreamPlaylistCandidate? = try {
        withTimeoutOrNull(timeoutMs) {
            xtraModule.playerRepository.loadCleanStreamPlaylistUrl(
                context = this@PlaybackService,
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(
                    this@PlaybackService,
                    prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
                ),
                channelLogin = login,
                randomDeviceId = false,
                xDeviceId = deviceId,
                playerTypes = playerTypes,
                supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyHost = prefs().httpProxyHost(),
                proxyPort = prefs().httpProxyPort(),
                proxyUser = prefs().getString(C.PROXY_USER, null),
                proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                requireVerifiedClean = true,
                preferredQuality = preferredQuality,
                onPlayerTypeAttempt = onPlayerTypeAttempt,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.d("XtraVaft", "candidate_lookup_failed type=${error::class.simpleName}")
        null
    }

    private suspend fun awaitPreparedVaftCandidate(
        player: ExoPlayer,
        markerKey: String,
        generation: Long,
        timeoutMs: Long,
    ): PreparedVaftCandidate? {
        if (vaftPreparedCandidate == null && vaftPreparationMarkerKey == markerKey &&
            vaftPreparationGeneration == generation && vaftPreparationMediaId == player.currentMediaItem?.mediaId &&
            vaftPreparationRequestId != null && timeoutMs > 0L
        ) {
            vaftPreparationJob?.takeIf { it.isActive }?.let { job ->
                withTimeoutOrNull(timeoutMs) { job.join() }
            }
        }
        val prepared = vaftPreparedCandidate
        val runtime = xtraModule.streamMedia3Runtime
        val isCurrent = prepared != null && prepared.markerKey == markerKey &&
            prepared.requestId == vaftPreparationRequestId &&
            prepared.vaftGeneration == generation && prepared.sourceGeneration == vaftSourceGeneration &&
            prepared.playbackMediaId == player.currentMediaItem?.mediaId &&
            prepared.configurationFingerprint == runtime.configurationFingerprintFor(player) &&
            prepared.qualityIntent == runtime.qualitySelectionPolicy.snapshot() &&
            prepared.qualityIntentRevision == runtime.qualitySelectionPolicy.revision() &&
            SystemClock.elapsedRealtime() - prepared.preparedAtMs in 0L..VAFT_PREPARED_CANDIDATE_MAX_AGE_MS
        if (isCurrent) return prepared

        discardVaftPreparation()
        return null
    }

    private fun discardVaftPreparation() {
        vaftPreparationRequestId = null
        vaftCandidateRefreshRequestId = null
        vaftPreparationJob?.cancel()
        vaftPreparationJob = null
        if (::xtraModule.isInitialized) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(vaftWarmupToken)
        }
        vaftWarmupToken = null
        vaftPreparedCandidate = null
        vaftPreparationMarkerKey = null
        vaftPreparationGeneration = -1L
        vaftPreparationMediaId = null
        vaftPreparationStartedAtMs = null
        vaftPreparationGraceDeadlineMs = 0L
    }

    private suspend fun awaitPreparedVaftSource(
        player: ExoPlayer,
        prepared: PreparedVaftCandidate,
        expectedUrl: String,
        generation: Long,
        timeoutMs: Long,
    ): VaftPreloadedMediaSource? {
        val handle = prepared.warmup ?: return null
        if (prepared.requestId != vaftPreparationRequestId) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(handle.token)
            if (vaftWarmupToken == handle.token) vaftWarmupToken = null
            return null
        }
        val warmCompleted = if (handle.completion.isDone) {
            runCatching { handle.completion.get() }.getOrDefault(false)
        } else if (timeoutMs > 0L) {
            withTimeoutOrNull(timeoutMs) {
                runCatching { handle.completion.awaitVaftFuture() }.getOrDefault(false)
            } == true
        } else {
            false
        }
        val source = if (warmCompleted) {
            xtraModule.streamMedia3Runtime.adoptVaftCandidateWarmup(
                playbackPlayer = player,
                token = handle.token,
                vaftGeneration = generation,
                expectedUrl = expectedUrl,
            )
        } else {
            null
        }
        if (source == null) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(handle.token)
            if (vaftWarmupToken == handle.token) vaftWarmupToken = null
        }
        return source
    }

    private suspend fun <T> ListenableFuture<T>.awaitVaftFuture(): T =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            addListener({
                if (continuation.isActive) {
                    continuation.resumeWith(runCatching { get() })
                }
            }, MoreExecutors.directExecutor())
        }

    private fun extendVaftPrimaryReturnHold(rangeRemainingMs: Long?) {
        val knownRangeRemainingMs = rangeRemainingMs ?: 0L
        // A marker can arrive late in its declared window, so a short remaining duration still gets a useful hold.
        val holdMs = maxOf(VAFT_PRIMARY_RETURN_MIN_HOLD_MS, knownRangeRemainingMs)
        val deadlineMs = SystemClock.elapsedRealtime() + holdMs
        val previousDeadlineMs = vaftPrimaryReturnAfterElapsedMs
        if (previousDeadlineMs == null || deadlineMs > previousDeadlineMs) {
            vaftPrimaryReturnAfterElapsedMs = deadlineMs
            if (BuildConfig.DEBUG) {
                Log.d("XtraVaft", "primary return held remainingMs=$holdMs rangeRemainingMs=$knownRangeRemainingMs")
            }
        }
    }

    private fun isVaftTargetPlaylistClean(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        returningPrimary: Boolean,
    ): Boolean {
        val targetContainsUnsafeMarker = TwitchVaftDetector.visibleBoundaries(playlist).any { boundary ->
            classifyVaftBoundary(
                player,
                playlist,
                boundary,
                allowSourceRelativeClock = true,
                allowMediaSequence = true,
                snapshotStartTimeUs = playlist.startTimeUs,
                snapshotMediaSequence = playlist.mediaSequence,
            ) in setOf(VaftPlaybackBoundaryPhase.ACTIVE, VaftPlaybackBoundaryPhase.UNKNOWN)
        }
        if (targetContainsUnsafeMarker) return false
        if (!returningPrimary) return !TwitchVaftDetector.requiresVaft(playlist)
        val tracked = trackedVaftBoundary
        if (tracked != null) {
            val phase = vaftPlaybackBoundaryPhase(
                player,
                playlist,
                tracked,
            )
            if (phase == VaftPlaybackBoundaryPhase.ACTIVE || phase == VaftPlaybackBoundaryPhase.UNKNOWN) return false
        } else {
            val phase = vaftUntrackedBoundaryPhase(
                player,
                playlist,
                TwitchVaftDetector.requiresVaft(playlist),
            )
            if (phase == VaftPlaybackBoundaryPhase.ACTIVE || phase == VaftPlaybackBoundaryPhase.UNKNOWN) return false
        }
        return true
    }

    /** The service owns verification and rollback even when the UI controller disconnects. */
    private fun startVaftHandoff(
        player: ExoPlayer,
        extras: Bundle,
        preloadedSource: VaftPreloadedMediaSource? = null,
    ): ListenableFuture<SessionResult> {
        if (vaftHandoffJob?.isActive == true) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
        }
        val targetUri = extras.getString(URI) ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val verified = decodePlaybackQuality(xtraModule.json, extras.getString(VAFT_VERIFIED_RENDITION))
        val previousExtras = liveStreamExtras?.let(::Bundle)?.apply {
            putString(URI, player.currentMediaItem?.localConfiguration?.uri?.toString())
            putBoolean(SUPPRESS_VAFT_OUTPUT, true)
        }
        val previousAlternate = vaftAlternateActive
        val returningPrimary = previousAlternate && !extras.getBoolean(VAFT_ALTERNATE_ACTIVE)
        val previousTracks = player.trackSelectionParameters
        val previousQualityPolicy = xtraModule.streamMedia3Runtime.qualitySelectionPolicy.snapshot()
        val generation = vaftGeneration
        val handoffExtras = Bundle(extras).apply {
            putString(
                VAFT_HANDOFF_TARGET_MEDIA_ID,
                preloadedSource?.mediaItem?.mediaId ?: "$VAFT_SOURCE_MEDIA_ID_PREFIX${java.util.UUID.randomUUID()}",
            )
        }
        val result = SettableFuture.create<SessionResult>()
        vaftOutputSuppressed = !returningPrimary
        vaftSourceSwitching = true
        vaftHandoffTargetMediaId = handoffExtras.getString(VAFT_HANDOFF_TARGET_MEDIA_ID)
        vaftHandoffTargetGeneration = generation
        vaftHandoffTargetFrameRendered = false
        vaftHandoffFrameCaptureResolvedId = null
        vaftHandoffFrameCaptureAccepted = false
        vaftHandoffPreviousTracks = previousTracks
        vaftHandoffPreviousMediaItem = player.currentMediaItem
        vaftHandoffPreviousPositionMs = player.currentPosition
        if (!returningPrimary) player.volume = 0f
        publishVaftPlaybackState()
        vaftHandoffJob = lifecycleScope.launch {
            var committedRendition = verified
            var handoffPosition = snapshotVaftPosition(player)
            try {
                if (returningPrimary) {
                    awaitReturnFrameCapture(
                        player = player,
                        generation = generation,
                        outgoingUri = player.currentMediaItem?.localConfiguration?.uri?.toString(),
                    )
                    if (generation != vaftGeneration) {
                        result.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                        return@launch
                    }
                    vaftOutputSuppressed = true
                    player.volume = 0f
                    publishVaftPlaybackState()
                }
                vaftHandoffPreviousPositionMs = player.currentPosition
                handoffPosition = snapshotVaftPosition(player)
                val success = try {
                    withTimeoutOrNull(
                        if (returningPrimary) VAFT_PRIMARY_HANDOFF_TIMEOUT_MS
                        else VAFT_ALTERNATE_HANDOFF_TIMEOUT_MS,
                    ) {
                        replaceVaftSource(player, handoffExtras, preloadedSource)
                        val audioOnly = verified?.name == PlaybackContract.AUDIO_ONLY_QUALITY
                        if (audioOnly) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, true)
                                .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
                                .build()
                        }
                        var overrideApplied = verified == null || audioOnly
                        var positionAligned = false
                        var lastHandoffDiagnostic: String? = null
                        val targetMediaId = handoffExtras.getString(VAFT_HANDOFF_TARGET_MEDIA_ID)
                        while (generation == vaftGeneration && player.currentMediaItem?.mediaId == targetMediaId &&
                            player.currentMediaItem?.localConfiguration?.uri?.toString() == targetUri
                        ) {
                            if (player.playerError != null) return@withTimeoutOrNull false
                            if (!overrideApplied && verified != null) {
                                val manifest = player.currentManifest as? HlsManifest
                                val variant = manifest?.multivariantPlaylist?.variants
                                    ?.firstOrNull { it.url.toString() == verified.url }
                                // HLS format ids identify the exact probed variant. Playlist labels
                                // and decoder dimension labels are not interchangeable.
                                val override = variant?.let { rendition ->
                                    player.currentTracks.groups.asSequence()
                                        .filter { it.type == Media3C.TRACK_TYPE_VIDEO }
                                        .firstNotNullOfOrNull { group ->
                                            (0 until group.length).firstOrNull { index ->
                                                val format = group.getTrackFormat(index)
                                                val sameVariant = format.height == rendition.format.height &&
                                                    format.width == rendition.format.width &&
                                                    (format.frameRate <= 0 || rendition.format.frameRate <= 0 ||
                                                        kotlin.math.abs(format.frameRate - rendition.format.frameRate) < 1f) &&
                                                    (format.id == rendition.format.id || format.label == rendition.format.label)
                                                group.isTrackSupported(index) && sameVariant &&
                                                    videoCodecsCompatible(format.codecs, rendition.format.codecs)
                                            }?.let { androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, it) }
                                        }
                                }
                                if (override != null) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                        if (!backgroundVideoSuppressed) setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, false)
                                        clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
                                        setOverrideForType(override)
                                    }.build()
                                    overrideApplied = true
                                }
                            }
                            val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                            // Background audio can load the audio playlist without creating a
                            // video decoder. The video variant was inspected before this handoff.
                            val manifest = player.currentManifest as? HlsManifest
                            val activeVideo = manifest?.multivariantPlaylist?.variants?.firstOrNull {
                                it.url.toString() == playlist?.baseUri && it.format.height > 0
                            }
                            // The loaded playlist is the final authority. A clean playable
                            // rendition must not stay black because track labels changed.
                            val renditionConfirmed = when {
                                audioOnly || backgroundVideoSuppressed -> true
                                verified == null -> activeVideo != null
                                else -> activeVideo?.url?.toString() == verified.url
                            }
                            if (BuildConfig.DEBUG) {
                                val diagnostic = "state=${player.playbackState} overrideApplied=$overrideApplied confirmed=$renditionConfirmed " +
                                    "activeRendition=${activeVideo?.url?.toString()?.let(::diagnosticToken)} " +
                                    "videoSuppressed=$backgroundVideoSuppressed"
                                if (diagnostic != lastHandoffDiagnostic) Log.d("XtraVaft", "handoff $diagnostic")
                                lastHandoffDiagnostic = diagnostic
                            }
                            if (player.playbackState == Player.STATE_READY && playlist != null) {
                                if (!positionAligned) {
                                    val alignment = alignVaftPosition(player, handoffPosition)
                                    positionAligned = alignment == "program_date_time" ||
                                        alignment == "live_offset_best_effort"
                                    if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff_position_alignment result=$alignment")
                                    delay(if (positionAligned) 50L else 100L)
                                    continue
                                }
                                if (renditionConfirmed && isVaftTargetPlaylistClean(player, playlist, returningPrimary)) {
                                    activeVideo?.let { variant ->
                                        val format = variant.format
                                        committedRendition = com.github.andreyasadchy.xtra.model.VideoQuality(
                                            format.label ?: verified?.name, format.codecs,
                                            format.bitrate.takeIf { it > 0 }, variant.url.toString(),
                                            format.frameRate.takeIf { it > 0 })
                                    }
                                    return@withTimeoutOrNull true
                                }
                                if (renditionConfirmed && !returningPrimary) {
                                    if (TwitchVaftDetector.requiresVaft(playlist)) {
                                        if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff candidate_rejected reason=target_tail_guard")
                                        return@withTimeoutOrNull false
                                    }
                                    val activeTargetBoundary = TwitchVaftDetector.visibleBoundaries(playlist).any { boundary ->
                                        classifyVaftBoundary(
                                            player = player,
                                            playlist = playlist,
                                            boundary = boundary,
                                            allowSourceRelativeClock = true,
                                            allowMediaSequence = true,
                                            snapshotStartTimeUs = playlist.startTimeUs,
                                            snapshotMediaSequence = playlist.mediaSequence,
                                        ) == VaftPlaybackBoundaryPhase.ACTIVE
                                    }
                                    if (activeTargetBoundary) {
                                        if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff candidate_rejected reason=target_active_boundary")
                                        return@withTimeoutOrNull false
                                    }
                                }
                            }
                            delay(100L)
                        }
                        false
                    } == true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (BuildConfig.DEBUG) Log.w("XtraVaft", "handoff candidate failed; rolling back", error)
                    false
                }
                if (success && generation == vaftGeneration) {
                    vaftVerifiedRendition = committedRendition
                    vaftAuthoritativeUri = targetUri
                    vaftCurrentPlayerType = extras.getString(VAFT_PLAYER_TYPE)
                    vaftAlternateActive = extras.getBoolean(VAFT_ALTERNATE_ACTIVE)
                    if (!vaftAlternateActive) {
                        vaftPrimaryReturnAfterElapsedMs = null
                        liveStreamUri = targetUri
                        liveStreamExtras = Bundle(extras).apply { remove(VAFT_ALTERNATE_ACTIVE); remove(VAFT_VERIFIED_RENDITION); remove(VAFT_PLAYER_TYPE) }
                    }
                    player.currentMediaItem?.let(xtraModule.streamMedia3Runtime::setPrimaryPlaybackMediaItem)
                    vaftOutputSuppressed = false
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    setLiveRewindSessionState(active = false, vodId = null, transitioning = false)
                    updatePrimaryPlaybackWatchState(player)
                    if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff commit source=${diagnosticToken(targetUri)} alternate=$vaftAlternateActive playWhenReady=${player.playWhenReady}")
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } else {
                    if (generation == vaftGeneration && previousExtras != null && player.currentMediaItem?.localConfiguration?.uri?.toString() == targetUri) {
                        previousExtras.putBoolean(PLAY_WHEN_READY, player.playWhenReady)
                        data class RollbackState(val restored: Boolean, val clean: Boolean)
                        val rollbackState = try {
                            replaceVaftSource(player, previousExtras)
                            xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
                                previousQualityPolicy.name, previousQualityPolicy.bitrate, previousQualityPolicy.codecs)
                            player.trackSelectionParameters = previousTracks
                            withTimeoutOrNull(15_000L) {
                                val rollbackUri = previousExtras.getString(URI)
                                val rollbackMediaId = vaftHandoffTargetMediaId
                                var rollbackPositionAligned = false
                                while (generation == vaftGeneration && player.currentMediaItem?.mediaId == rollbackMediaId &&
                                    player.currentMediaItem?.localConfiguration?.uri?.toString() == rollbackUri
                                ) {
                                    if (player.playerError != null) return@withTimeoutOrNull RollbackState(restored = false, clean = false)
                                    val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                                    if (player.playbackState == Player.STATE_READY && playlist != null) {
                                        if (!rollbackPositionAligned) {
                                            val alignment = alignVaftPosition(player, handoffPosition)
                                            rollbackPositionAligned = alignment == "program_date_time" ||
                                                alignment == "live_offset_best_effort"
                                            if (BuildConfig.DEBUG) Log.d("XtraVaft", "rollback_position_alignment result=$alignment")
                                            delay(if (rollbackPositionAligned) 50L else 100L)
                                            continue
                                        }
                                        if (isVaftTargetPlaylistClean(player, playlist, !previousAlternate)) {
                                            return@withTimeoutOrNull RollbackState(restored = true, clean = true)
                                        }
                                        if (!previousAlternate) {
                                            val publisherRequiresVaft = TwitchVaftDetector.requiresVaft(playlist)
                                            val tracked = trackedVaftBoundary
                                            val phase = tracked?.let {
                                                vaftPlaybackBoundaryPhase(player, playlist, it)
                                            } ?: vaftUntrackedBoundaryPhase(player, playlist, publisherRequiresVaft)
                                            if (isPlaybackBoundaryUnsafe(phase, publisherRequiresVaft, tracked != null)) {
                                                return@withTimeoutOrNull RollbackState(restored = true, clean = false)
                                            }
                                        }
                                    }
                                    delay(100L)
                                }
                                RollbackState(restored = false, clean = false)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (BuildConfig.DEBUG) Log.w("XtraVaft", "handoff rollback failed", error)
                            null
                        }
                        vaftAlternateActive = previousAlternate
                        val rollbackRestored = rollbackState?.restored == true
                        val cleanRollback = rollbackState?.clean == true
                        vaftOutputSuppressed = !cleanRollback
                        if (rollbackRestored) {
                            player.currentMediaItem?.let(xtraModule.streamMedia3Runtime::setPrimaryPlaybackMediaItem)
                        }
                        if (cleanRollback) {
                            player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                        } else {
                            player.volume = 0f
                        }
                        if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff rollback restored=$rollbackRestored clean=$cleanRollback alternate=$previousAlternate playWhenReady=${player.playWhenReady}")
                    }
                    result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            } catch (error: CancellationException) {
                result.set(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                throw error
            } finally {
                if (generation == vaftGeneration) {
                    vaftSourceSwitching = false
                    vaftHandoffPreviousTracks = null
                    vaftHandoffPreviousMediaItem = null
                    vaftHandoffPreviousPositionMs = null
                    discardVaftPreparation()
                    publishVaftPlaybackState()
                }
            }
        }
        // Cancelling a UI future deliberately does not cancel service source ownership.
        return result
    }

    private fun startLiveStream(
        player: ExoPlayer,
        extras: Bundle,
        beginNewPlayback: Boolean = true,
    ): ListenableFuture<SessionResult> {
        backgroundPlayback = false
        val uri = extras.getString(URI)?.takeIf { it.isNotBlank() }
        val channelLogin = extras.getString(CHANNEL_LOGIN)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val title = extras.getString(TITLE)
        val channelName = extras.getString(CHANNEL_NAME)
        val channelLogo = extras.getString(CHANNEL_LOGO)
        liveStreamUri = uri
        liveStreamExtras = Bundle(extras)
        setViewingMetadata(
            ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
            extras.getString(STREAM_ID),
            extras,
            beginNewPlayback,
        )
        videoId = null
        offlineVideoId = null
        if (uri == null) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }

        val initialLivePolicy = LivePlaybackPolicies.forLowLatency(
            prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY),
        )
        adaptiveLiveController?.reset(initialLivePolicy, initialLivePolicy.lowLatency)
        applyAdaptiveLivePolicy()
        clearLiveClipState()
        clearVodClipSource()
        vodClipDataSourceFactory = null
        vodClipMediaItemId = null
        vodClipMediaItemUri = null

        val streamStartElapsedMs = SystemClock.elapsedRealtime()
        val runtime = xtraModule.streamMedia3Runtime
        val login = channelLogin ?: "unknown"
        val startupQuality = decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))
        val desired = resumptionHlsQuality(startupQuality)
        runtime.qualitySelectionPolicy.set(desired.name, desired.bitrate, desired.codecs)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO,
                startupQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY || startupQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY)
            .build()
        if (BuildConfig.DEBUG) Log.d("SmoothHlsQuality", "stream_start_quality name=${desired.name}")
        val previewAlreadyPlaying = channelLogin?.let { xtraModule.streamPreviewCoordinator.isPreviewing(it) } == true
        val mediaItem = runtime.createLiveMediaItem(login, uri, title, channelName, channelLogo)
            .buildUpon()
            .apply {
                if (prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)) {
                    setMediaMetadata(systemMediaMetadata())
                }
            }
            .build()
        val preloaded = channelLogin?.let { runtime.getPreloadedMediaSource(it, uri) }
        val urlWarm = extras.getBoolean(URL_WARM, false) || preloaded != null
        proxyMediaPlaylist = false
        runtime.setProxyMediaPlaylist(mediaItem.mediaId, false)
        streamStartupTrace = StreamStartupTrace(
            channelLogin = login,
            tappedAtMs = extras.getLong(TAP_ELAPSED_MS, -1L).takeIf { it > 0L } ?: streamStartElapsedMs,
            streamStartElapsedMs = streamStartElapsedMs,
            urlAvailableElapsedMs = extras.getLong(URL_AVAILABLE_ELAPSED_MS, -1L).takeIf { it > 0L },
            tapSource = if (extras.getLong(TAP_ELAPSED_MS, -1L) > 0L) "card" else "service",
            mediaLabel = when {
                previewAlreadyPlaying -> "PREVIEW_ALREADY_PLAYING"
                preloaded == null && extras.getBoolean(URL_WARM, false) -> "URL_WARM"
                preloaded == null -> "COLD"
                preloaded.targetStage == androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus.STAGE_SPECIFIED_RANGE_LOADED -> "SAMPLES_WARM"
                preloaded.targetStage == androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus.STAGE_TRACKS_SELECTED -> "TRACKS_SELECTED"
                else -> "SOURCE_PREPARED"
            },
            mediaAgeMs = preloaded?.mediaAgeMs,
            urlWarm = urlWarm,
            previewAlreadyPlaying = previewAlreadyPlaying,
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$login url=${if (urlWarm) "warm" else "cold"} media=${streamStartupTrace?.mediaLabel} " +
                    "mediaAgeMs=${preloaded?.mediaAgeMs ?: -1} preview=$previewAlreadyPlaying " +
                    "tapSource=${streamStartupTrace?.tapSource} tapToUrlAvailableMs=${streamStartupTrace?.tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${streamStartupTrace?.tapToStartStreamMs() ?: -1}",
            )
        }
        diagnostics.resetRenderedVideoSize()
        val playbackMediaItem = preloaded?.mediaItem ?: mediaItem
        val playbackSource = preloaded?.mediaSource ?: runtime.createLiveMediaSource(mediaItem)
        updateLiveClipSource(playbackMediaItem)
        player.setMediaSource(playbackSource)
        runtime.setPrimaryPlaybackMediaItem(playbackMediaItem)
        player.volume = if (extras.getBoolean(SUPPRESS_VAFT_OUTPUT)) 0f else prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        player.setPlaybackSpeed(1f)
        player.prepare()
        streamStartupTrace?.prepareCalledAtMs = SystemClock.elapsedRealtime()
        player.playWhenReady = extras.getBoolean(PLAY_WHEN_READY, true)
        refreshCurrentMediaItemMetadata(player)
        refreshMediaButtonPreferences(player)
        saveResumptionState(
            PlaybackState(
                type = PlaybackContract.STREAM,
                streamId = extras.getString(STREAM_ID),
                channelId = extras.getString(CHANNEL_ID),
                channelLogin = login,
                channelName = channelName,
                channelImage = channelLogo,
                gameId = extras.getString(GAME_ID),
                gameSlug = extras.getString(GAME_SLUG),
                gameName = extras.getString(GAME_NAME),
                title = title,
                thumbnail = extras.getString(THUMBNAIL),
                createdAt = extras.getString(CREATED_AT),
                viewerCount = extras.getInt(VIEWER_COUNT).takeIf { extras.containsKey(VIEWER_COUNT) },
                playlistUrl = uri,
                quality = extras.getString(PLAYBACK_QUALITY),
                paused = !player.playWhenReady,
            ),
        )
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun systemMediaMetadata(): MediaMetadata {
        val showTitle = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_TITLE, true)
        val showCategory = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_CATEGORY, true)
        val isLive = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
        val artwork = when (prefs().getString(C.SYSTEM_MEDIA_ARTWORK_SOURCE, C.SYSTEM_MEDIA_ARTWORK_STREAMER_AVATAR)) {
            C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW -> TwitchApiHelper.getStreamThumbnail(viewingStreamPreview, 720, 405)
            C.SYSTEM_MEDIA_ARTWORK_CATEGORY -> TwitchApiHelper.getGameBoxArt(viewingCategoryImage)
            C.SYSTEM_MEDIA_ARTWORK_NONE -> null
            else -> TwitchApiHelper.getProfileImage(viewingChannelImage)
        }
        val channel = viewingChannelName ?: viewingChannelLogin
        val title = if (isLive) channel else viewingTitle ?: channel
        val artist = if (isLive) viewingCategoryName?.takeIf { showCategory } else channel
        val subtitle = if (isLive) viewingTitle?.takeIf { showTitle } else viewingCategoryName?.takeIf { showCategory }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setSubtitle(subtitle)
            .setArtworkUri(artwork?.toUri())
            .build()
    }

    private fun refreshCurrentMediaItemMetadata(player: Player) {
        if (mediaSession == null || player.currentMediaItem == null) return
        if (!prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)) return
        val item = player.currentMediaItem ?: return
        val metadata = systemMediaMetadata()
        val currentMetadata = item.mediaMetadata
        if (currentMetadata.title == metadata.title &&
            currentMetadata.artist == metadata.artist &&
            currentMetadata.subtitle == metadata.subtitle &&
            currentMetadata.artworkUri == metadata.artworkUri
        ) return
        val index = player.currentMediaItemIndex
        if (index >= 0) {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    private fun refreshMediaButtonPreferences(player: Player) {
        lastMediaButtonSeekable = player.isCurrentMediaItemSeekable
        val session = mediaSession ?: return
        if (!prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)) {
            session.setMediaButtonPreferences(emptyList())
            return
        }
        val buttons = buildList {
            if (prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true) &&
                player.isCurrentMediaItemSeekable
            ) {
                add(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .setDisplayName(getString(R.string.settings_system_media_seek_back))
                        .build(),
                )
                add(
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                        .setDisplayName(getString(R.string.settings_system_media_seek_forward))
                        .build(),
                )
            }
            if (liveRewindActive && prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_GO_LIVE, true)) {
                add(
                    CommandButton.Builder()
                        .setSessionCommand(SessionCommand(GO_LIVE, Bundle.EMPTY))
                        .setIconResId(R.drawable.notification_icon)
                        .setDisplayName(getString(R.string.seek_to_live))
                        .build(),
                )
            }
        }
        session.setMediaButtonPreferences(buttons)
    }

    private fun refreshMediaButtonPreferencesIfSeekabilityChanged(player: Player) {
        if (player.isCurrentMediaItemSeekable == lastMediaButtonSeekable) return
        refreshMediaButtonPreferences(player)
    }

    private inner class PlaybackSessionPlayer(
        private val player: ExoPlayer,
    ) : ForwardingSimpleBasePlayer(player) {

        fun invalidateServiceState() {
            invalidateState()
        }

        override fun getState(): State {
            val state = super.getState()
            val seekCommandAdditions = mediaSessionSeekCommandAdditions(
                systemMediaControlsEnabled = prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true),
                systemSeekButtonsEnabled = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true),
                liveRewindActive = this@PlaybackService.liveRewindActive,
                liveRewindTransitioning = this@PlaybackService.liveRewindTransitioning,
                seekable = player.isCurrentMediaItemSeekable,
            )
            val availableCommands = state.availableCommands.buildUpon().apply {
                if (seekCommandAdditions.seekToPrevious) add(COMMAND_SEEK_TO_PREVIOUS)
                if (seekCommandAdditions.seekToNext) add(COMMAND_SEEK_TO_NEXT)
                if (seekCommandAdditions.seekInCurrentMediaItem) add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }.build()
            return state.buildUpon().setAvailableCommands(availableCommands).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
            when (seekCommand) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    player.seekForward()
                    Futures.immediateVoidFuture()
                }
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    player.seekBack()
                    Futures.immediateVoidFuture()
                }
                else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
    }

    private class StreamStartupTrace(
        val channelLogin: String,
        private val tappedAtMs: Long,
        val mediaLabel: String,
        private val mediaAgeMs: Long?,
        private val urlWarm: Boolean,
        private val previewAlreadyPlaying: Boolean,
        private val streamStartElapsedMs: Long,
        private val urlAvailableElapsedMs: Long?,
        val tapSource: String,
    ) {
        var prepareCalledAtMs: Long? = null
        private var readyLogged = false
        private var firstFrameLogged = false

        fun tapToUrlAvailableMs(): Long? = urlAvailableElapsedMs?.minus(tappedAtMs)

        fun tapToStartStreamMs(): Long = streamStartElapsedMs - tappedAtMs

        fun markReady() {
            if (readyLogged || !BuildConfig.DEBUG) return
            readyLogged = true
            val now = SystemClock.elapsedRealtime()
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToReadyMs=${now - tappedAtMs} " +
                    "prepareToReadyMs=${prepareCalledAtMs?.let { now - it } ?: -1}",
            )
        }

        fun markFirstFrame() {
            if (firstFrameLogged || !BuildConfig.DEBUG) return
            firstFrameLogged = true
            val now = SystemClock.elapsedRealtime()
            Log.d(
                "StreamStartup",
                "StreamStartup channel=$channelLogin url=${if (urlWarm) "warm" else "cold"} media=$mediaLabel mediaAgeMs=${mediaAgeMs ?: -1} " +
                    "preview=$previewAlreadyPlaying tapSource=$tapSource tapToUrlAvailableMs=${tapToUrlAvailableMs() ?: -1} " +
                    "tapToStartStreamMs=${tapToStartStreamMs()} tapToFirstFrameMs=${now - tappedAtMs}",
            )
        }
    }

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() && controller.packageName == packageName

    private fun snapshotLiveRewindPlayback(
        player: ExoPlayer,
        sourceUriOverride: String? = null,
        trackSelectionParametersOverride: TrackSelectionParameters? = null,
        mediaItemOverride: MediaItem? = null,
        positionMsOverride: Long? = null,
        volumeOverride: Float? = null,
    ): LiveRewindPlaybackSnapshot? {
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            liveRewindActive ||
            (!player.isCurrentMediaItemLive && mediaItemOverride == null)
        ) return null
        val mediaItem = mediaItemOverride ?: player.currentMediaItem ?: return null
        val extras = liveStreamExtras?.let(::Bundle) ?: return null
        val uri = sourceUriOverride?.takeIf { it.isNotBlank() }
            ?: mediaItem.localConfiguration?.uri?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: liveStreamUri?.takeIf { it.isNotBlank() }
            ?: return null
        extras.putString(URI, uri)
        extras.putBoolean(PLAY_WHEN_READY, player.playWhenReady)
        return LiveRewindPlaybackSnapshot(
            mediaItem = mediaItem,
            liveStreamExtras = extras,
            positionMs = positionMsOverride ?: player.currentPosition,
            playWhenReady = player.playWhenReady,
            volume = volumeOverride ?: player.volume,
            playbackSpeed = player.playbackParameters.speed,
            trackSelectionParameters = trackSelectionParametersOverride ?: player.trackSelectionParameters,
            proxyMediaPlaylist = proxyMediaPlaylist,
            liveRewindActive = liveRewindActive,
            liveRewindVodId = liveRewindVodId,
        )
    }

    private fun restoreLiveRewindPlayback(
        player: ExoPlayer,
        snapshot: LiveRewindPlaybackSnapshot,
    ): Boolean {
        return try {
            val uri = snapshot.liveStreamExtras.getString(URI)?.takeIf { it.isNotBlank() }
                ?: return false
            val runtime = xtraModule.streamMedia3Runtime
            val mediaItem = runtime.newSourceInstanceMediaItem(snapshot.mediaItem, uri)
            val mediaSource = runtime.createLiveMediaSource(mediaItem)
            runtime.setProxyMediaPlaylist(mediaItem.mediaId, snapshot.proxyMediaPlaylist)
            player.setMediaSource(mediaSource)
            runtime.setPrimaryPlaybackMediaItem(mediaItem)
            player.trackSelectionParameters = snapshot.trackSelectionParameters
            player.volume = snapshot.volume
            player.setPlaybackSpeed(snapshot.playbackSpeed)
            player.prepare()
            player.seekTo(snapshot.positionMs)
            player.playWhenReady = snapshot.playWhenReady
            liveStreamUri = uri
            liveStreamExtras = Bundle(snapshot.liveStreamExtras)
            proxyMediaPlaylist = snapshot.proxyMediaPlaylist
            setLiveRewindSessionState(active = snapshot.liveRewindActive, vodId = snapshot.liveRewindVodId)
            updateLiveClipSource(mediaItem)
            true
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) Log.w("LiveRewind", "Failed to reconstruct live source after rewind setup failure (${error.javaClass.simpleName})")
            false
        }
    }

    private data class LiveRewindPlaybackSnapshot(
        val mediaItem: MediaItem,
        val liveStreamExtras: Bundle,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val volume: Float,
        val playbackSpeed: Float,
        val trackSelectionParameters: TrackSelectionParameters,
        val proxyMediaPlaylist: Boolean,
        val liveRewindActive: Boolean,
        val liveRewindVodId: String?,
    )

    private fun createVodMediaSource(uri: android.net.Uri): MediaSource =
        HlsMediaSource.Factory(
            DefaultDataSource.Factory(
                this@PlaybackService,
                when {
                    prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                    }
                    prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, false, false, null, null, null) { false }
                    }
                    else -> {
                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                    }
                },
            ),
        ).apply {
            setPlaylistParserFactory(twitchHlsPlaylistParserFactory())
        }.createMediaSource(MediaItem.Builder().setUri(uri).build())

    private fun twitchHlsPlaylistParserFactory(): TwitchHlsPlaylistParserFactory =
        TwitchHlsPlaylistParserFactory(
            lowLatencyEnabled = false,
            diagnostics = TwitchHlsDiagnosticsSink { hlsDiagnostics, parsed ->
                if (parsed is androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist) {
                    diagnostics.recordTwitchHlsPlaylist(hlsDiagnostics, parsed)
                }
            },
        )

    private fun syncTwitchHlsDiagnostics(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        xtraModule.streamMedia3Runtime.hlsDiagnosticsFor(mediaId)?.let {
            diagnostics.recordTwitchHlsDiagnostics(it)
        }
    }

    private fun videoDiagnosticsSnapshot(player: Player): PlaybackVideoInfo {
        syncTwitchHlsDiagnostics(player)
        val mediaItem = player.currentMediaItem
        val request = mediaItem?.mediaId?.let(xtraModule.streamMedia3Runtime::proxyPlaylistObservationFor)
        return diagnostics.snapshot(player).copy(
            currentPlaybackUrl = mediaItem?.localConfiguration?.uri?.toString(),
            lastStreamRequestType = request?.requestType,
            appProxyRoute = request?.route?.name,
            appProxyServer = request?.proxyServer,
        )
    }

    private fun savePosition() {
        mediaSession?.player?.let { player ->
            resumptionState?.let { state ->
                saveResumptionState(state.copy(position = player.currentPosition, paused = !player.playWhenReady))
            }
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                videoId?.let {
                    xtraModule.playbackPersistence.saveVideoPosition(VideoPosition(it, player.currentPosition))
                    xtraModule.playbackPersistence.saveVideoHistoryPosition(it.toLong(), player.currentPosition)
                } ?:
                offlineVideoId?.let {
                    xtraModule.playbackPersistence.saveOfflineVideoPosition(it, player.currentPosition)
                }
            }
        }
    }

    private fun runAfterPlaybackPersistence(action: () -> Unit) {
        lifecycleScope.launch {
            xtraModule.playbackPersistence.flush()
            action()
        }
    }

    private fun updateSavedPosition() {
        mediaSession?.player?.let { player ->
            resumptionState?.let { state ->
                saveResumptionState(state.copy(position = player.currentPosition, paused = !player.playWhenReady))
            }
            if (!player.currentTracks.isEmpty && prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                val currentPosition = player.currentPosition
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    videoId?.let {
                        xtraModule.playbackPersistence.saveVideoPosition(VideoPosition(it, currentPosition))
                        xtraModule.playbackPersistence.saveVideoHistoryPosition(it.toLong(), currentPosition)
                    } ?:
                    offlineVideoId?.let {
                        xtraModule.playbackPersistence.saveOfflineVideoPosition(it, currentPosition)
                    }
                }
            }
        }
    }

    private fun scheduleBackgroundRecovery() {
        if (vaftCoordinatorJob?.isActive == true || vaftSourceSwitching) return
        if (!prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)) {
            return
        }
        backgroundRecoveryTimer?.cancel()
        val delay = (500L shl backgroundRecoveryAttempt.coerceAtMost(4)).coerceAtMost(8000L)
        backgroundRecoveryAttempt = (backgroundRecoveryAttempt + 1).coerceAtMost(4)
        backgroundRecoveryTimer = Timer().apply {
            schedule(delay) {
                Handler(Looper.getMainLooper()).post {
                    backgroundRecoveryTimer = null
                    val player = mediaSession?.player
                    if (backgroundPlayback && vaftCoordinatorJob?.isActive != true && !vaftSourceSwitching
                        && prefs().getBoolean(C.PLAYER_AUTO_RECOVER_STREAMS, true)
                        && player?.playWhenReady == true
                        && player.playerError != null
                    ) {
                        player.prepare()
                    }
                }
            }
        }
    }

    internal fun handleViewingMetadataCommand(extras: Bundle, player: Player) {
        val streamId = extras.getString(STREAM_ID)
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            (streamId != null && streamId != viewingContentId)
        ) {
            return
        }

        // UPDATE_VIEWING_METADATA is a patch. A category is only updated when
        // both identity fields are supplied so an incomplete refresh cannot
        // create an inconsistent ID/name pair.
        val nextCategory = mergeViewingCategoryPatch(
            currentId = viewingCategoryId,
            currentName = viewingCategoryName,
            patchId = extras.getString(GAME_ID),
            patchName = extras.getString(GAME_NAME),
        )
        viewingCategoryId = nextCategory.id
        viewingCategoryName = nextCategory.name
        if (extras.containsKey(GAME_IMAGE)) {
            viewingCategoryImage = extras.getString(GAME_IMAGE)
        }
        if (extras.containsKey(TITLE)) {
            viewingTitle = extras.getString(TITLE)
        }
        resumptionState?.takeIf { it.type == PlaybackContract.STREAM }?.let { state ->
            saveResumptionState(
                state.copy(
                    gameId = viewingCategoryId,
                    gameName = viewingCategoryName,
                    title = viewingTitle,
                    thumbnail = viewingStreamPreview,
                ),
            )
        }
        updateViewingStats(player)
        refreshCurrentMediaItemMetadata(player)
        refreshMediaButtonPreferences(player)
    }

    internal fun setViewingMetadata(
        contentType: String,
        contentId: String?,
        extras: Bundle,
        beginNewPlayback: Boolean = true,
    ) {
        if (viewingContentType != contentType || viewingContentId != contentId) {
            diagnostics.resetForNewMedia()
        }
        finishViewingStats()
        viewingChannelId = extras.getString(CHANNEL_ID)
        viewingChannelLogin = extras.getString(CHANNEL_LOGIN)
        viewingChannelName = extras.getString(CHANNEL_NAME)
        viewingChannelImage = extras.getString(CHANNEL_LOGO)
        viewingCategoryId = extras.getString(GAME_ID)
        viewingCategoryName = extras.getString(GAME_NAME)
        viewingCategoryImage = extras.getString(GAME_IMAGE)
        viewingTitle = extras.getString(TITLE)
        viewingStreamPreview = extras.getString(THUMBNAIL)
        viewingContentType = contentType
        viewingContentId = contentId
        if (beginNewPlayback) beginPrimaryPlaybackWatchState()
    }

    private fun finishViewingStats() {
        if (::xtraModule.isInitialized) {
            xtraModule.viewingStatsRecorder.update(
                sourceId = viewingStatsSourceId,
                metadata = viewingMetadata(),
                isPlaying = false,
                isBuffering = false,
            )
        }
    }

    private fun updateViewingStats(player: Player) {
        val metadata = viewingMetadata() ?: return
        updatePrimaryPlaybackWatchState(player)
        xtraModule.viewingStatsRecorder.update(
            sourceId = viewingStatsSourceId,
            metadata = metadata,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun viewingMetadata(): ViewingPlaybackMetadata? {
        val contentType = viewingContentType ?: return null
        return ViewingPlaybackMetadata(
            channelId = viewingChannelId,
            channelLogin = viewingChannelLogin,
            channelName = viewingChannelName,
            channelImage = viewingChannelImage,
            categoryId = viewingCategoryId,
            categoryName = viewingCategoryName,
            categoryImage = viewingCategoryImage,
            contentType = contentType,
            contentId = viewingContentId,
            title = viewingTitle,
        )
    }

    private fun beginPrimaryPlaybackWatchState() {
        val metadata = viewingMetadata() ?: return
        primaryPlaybackWatchReleased = false
        val store = xtraModule.primaryPlaybackWatchState
        val ownerId = primaryPlaybackWatchOwnerId ?: store.newOwnerId().also {
            primaryPlaybackWatchOwnerId = it
        }
        primaryPlaybackWatchGeneration = store.begin(
            ownerId = ownerId,
            metadata = metadata,
            liveEligible = isLivePlaybackEligible(metadata),
        )
    }

    private fun updatePrimaryPlaybackWatchState(player: Player) {
        val metadata = viewingMetadata() ?: return
        if (primaryPlaybackWatchReleased) return
        val store = xtraModule.primaryPlaybackWatchState
        val ownerId = primaryPlaybackWatchOwnerId ?: store.newOwnerId().also {
            primaryPlaybackWatchOwnerId = it
        }
        val liveEligible = isLivePlaybackEligible(metadata)
        val generation = primaryPlaybackWatchGeneration
        if (generation != null) {
            primaryPlaybackWatchGeneration = store.update(
                ownerId = ownerId,
                generation = generation,
                metadata = metadata,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                liveEligible = liveEligible,
            )
        } else if (store.state.value == null) {
            primaryPlaybackWatchGeneration = store.begin(
                ownerId = ownerId,
                metadata = metadata,
                liveEligible = liveEligible,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }
    }

    private fun isLivePlaybackEligible(metadata: ViewingPlaybackMetadata): Boolean =
        metadata.contentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
                !liveRewindActive && !liveRewindTransitioning

    private fun releasePrimaryPlaybackWatchState() {
        val ownerId = primaryPlaybackWatchOwnerId
        val generation = primaryPlaybackWatchGeneration
        if (ownerId != null && generation != null) {
            xtraModule.primaryPlaybackWatchState.release(ownerId, generation)
        }
        primaryPlaybackWatchGeneration = null
        primaryPlaybackWatchReleased = true
    }

    private fun suppressVideoForBackground(player: Player) {
        if (backgroundVideoSuppressed) {
            logBackgroundVideoState("disable_already_owned", player, owned = true)
            return
        }
        val trackSelectionParameters = player.trackSelectionParameters
        val videoAlreadyDisabled = Media3C.TRACK_TYPE_VIDEO in trackSelectionParameters.disabledTrackTypes
        if (player.currentMediaItem == null || videoAlreadyDisabled) {
            logBackgroundVideoState(
                if (videoAlreadyDisabled) "disable_skipped_already_disabled" else "disable_skipped_no_media",
                player,
                owned = false,
            )
            return
        }
        player.trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, true)
            .build()
        backgroundVideoSuppressed = true
        logBackgroundVideoState("disable", player, owned = true)
    }

    private fun restoreBackgroundVideoSuppression(player: Player) {
        val wasOwned = backgroundVideoSuppressed
        val videoWasDisabled = Media3C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes
        if (wasOwned && player.currentMediaItem != null && videoWasDisabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        backgroundVideoSuppressed = false
        logBackgroundVideoState("foreground_restore", player, owned = wasOwned)
    }

    private fun clearBackgroundVideoSuppression(
        player: Player,
        restoreVideo: Boolean,
        reason: String,
    ) {
        val wasOwned = backgroundVideoSuppressed
        if (!wasOwned) return
        val videoWasDisabled = Media3C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes
        if (restoreVideo && player.currentMediaItem != null && videoWasDisabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        backgroundVideoSuppressed = false
        logBackgroundVideoState(reason, player, owned = wasOwned)
    }

    private fun logBackgroundVideoState(action: String, player: Player, owned: Boolean) {
        if (!BuildConfig.DEBUG) return
        val selectedVideoTrack = player.currentTracks.groups.any { group ->
            group.type == Media3C.TRACK_TYPE_VIDEO && group.isSelected
        }
        Log.d(
            "BackgroundVideo",
            "background_video action=$action owned=$owned pid=${Process.myPid()} player=${player.identityId()} " +
                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} state=${player.playbackState} " +
                "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                "positionMs=${player.currentPosition} bufferedPositionMs=${player.bufferedPosition} " +
                "disabledTrackTypes=${player.trackSelectionParameters.disabledTrackTypes} " +
                "selectedVideoTrack=$selectedVideoTrack hasMediaItem=${player.currentMediaItem != null}",
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> {
        val player = session.player
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=media_notification_update startInForegroundRequired=$startInForegroundRequired " +
                    "bootstrapForegroundActive=$bootstrapForegroundActive ongoing=${isPlaybackOngoing()} " +
                    "mediaItemCount=${player.mediaItemCount} playWhenReady=${player.playWhenReady} " +
                    "playbackState=${player.playbackState}",
            )
        }
        if (bootstrapForegroundActive && !startInForegroundRequired) {
            if (BuildConfig.DEBUG) Log.d(RESUMPTION_TAG, "deferred transient idle notification update")
            return Futures.immediateVoidFuture()
        }

        val future = super.onUpdateNotificationAsync(session, startInForegroundRequired)
        if (BuildConfig.DEBUG) {
            Futures.addCallback(
                future,
                object : FutureCallback<Void?> {
                    override fun onSuccess(result: Void?) {
                        Log.d(
                            "PlaybackLifecycle",
                            "event=media_notification_update_complete " +
                                "startInForegroundRequired=$startInForegroundRequired " +
                                "ongoingAfter=${isPlaybackOngoing()}",
                        )
                    }

                    override fun onFailure(t: Throwable) {
                        Log.e(
                            "PlaybackLifecycle",
                            "event=media_notification_update_failed " +
                                "startInForegroundRequired=$startInForegroundRequired " +
                                "error=${t.javaClass.simpleName}",
                        )
                    }
                },
                MoreExecutors.directExecutor(),
            )
        }
        if (bootstrapForegroundActive && startInForegroundRequired) {
            Futures.addCallback(
                future,
                object : FutureCallback<Void?> {
                    override fun onSuccess(result: Void?) {
                        mainHandler.post {
                            if (bootstrapForegroundActive && isPlaybackOngoing()) {
                                bootstrapForegroundActive = false
                                getSystemService(NotificationManager::class.java)
                                    .cancel(PLAYBACK_BOOTSTRAP_NOTIFICATION_ID)
                                if (BuildConfig.DEBUG) {
                                    Log.d(RESUMPTION_TAG, "Media3 playback notification promoted")
                                }
                            }
                        }
                    }

                    override fun onFailure(t: Throwable) {
                        mainHandler.post(::abortBootstrapPlaybackStart)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        }
        return future
    }

    private fun ensurePlaybackNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(PLAYBACK_NOTIFICATION_CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    PLAYBACK_NOTIFICATION_CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.playback_notification_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    private fun abortBootstrapPlaybackStart() {
        if (!bootstrapForegroundActive) return
        bootstrapForegroundActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(PLAYBACK_BOOTSTRAP_NOTIFICATION_ID)
        if (BuildConfig.DEBUG) Log.d(RESUMPTION_TAG, "aborted media-button playback start")
        pauseAllPlayersAndStopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val player = mediaSession?.player
        val keepPlayback = player?.playWhenReady == true
                && player.playbackState != Player.STATE_ENDED
                && prefs().getBoolean(C.PLAYER_KEEP_PLAYING_AFTER_TASK_REMOVED, true)
                && prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        if (keepPlayback) {
            backgroundPlayback = true
            return
        }
        releasePrimaryPlaybackWatchState()
        clearPlaybackResumptionState()
        player?.let {
            clearBackgroundVideoSuppression(
                it,
                restoreVideo = false,
                reason = "task_removed_stop",
            )
        }
        player?.clearMediaItems()
        xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(null)
        runAfterPlaybackPersistence {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        prefs().unregisterOnSharedPreferenceChangeListener(mediaPreferenceListener)
        clearLiveClipState()
        clearVodClipSource()
        if (::xtraModule.isInitialized) {
            releasePrimaryPlaybackWatchState()
            xtraModule.viewingStatsRecorder.release(viewingStatsSourceId)
        }
        backgroundVideoSuppressed = false
        backgroundRecoveryTimer?.cancel()
        backgroundRecoveryTimer = null
        sleepTimer?.cancel()
        savePositionTimer?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        if (::xtraModule.isInitialized) xtraModule.streamMedia3Runtime.releasePlaybackPlayer(playbackPlayer)
        playbackPlayer = null
        playbackSessionPlayer = null
        adaptiveLiveController = null
        adaptiveLiveSpeedControl = null
        super.onDestroy()
    }

    companion object {
        private const val PERF_TAG = "PlaybackPerf"
        private const val RESUMPTION_TAG = "PlaybackResumption"
        private const val VAFT_PRIMARY_RETURN_MIN_HOLD_MS = 30_000L
        const val VAFT_SOURCE_MEDIA_ID_PREFIX = "vaft-source:"
        private const val VAFT_PREPARED_CANDIDATE_MAX_AGE_MS = 15_000L
        private const val VAFT_PREPARE_LOOKAHEAD_MS = 30_000L
        private const val VAFT_PREPARE_RETRY_COOLDOWN_MS = 5_000L
        private const val VAFT_SAMPLE_WARMUP_LEAD_MS = 700L
        private const val VAFT_CANDIDATE_REFRESH_LEAD_MS = 12_000L
        private const val VAFT_PRELOAD_HANDOFF_GRACE_MS = 500L
        private const val VAFT_ALTERNATE_HANDOFF_TIMEOUT_MS = 4_000L
        private const val VAFT_PRIMARY_HANDOFF_TIMEOUT_MS = 15_000L
        private const val VAFT_DIFFERENT_TYPE_RETRY_YIELD_MS = 150L
        private const val VAFT_CAPTURE_ACK_TIMEOUT_MS = 400L
        private const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "xtra_media_playback"
        private const val PLAYBACK_NOTIFICATION_ID = 5201
        private const val PLAYBACK_BOOTSTRAP_NOTIFICATION_ID = 5202
        private const val RESUMPTION_STREAM_URL_TIMEOUT_MS = 8_000L
        const val START_STREAM = "startStream"
        const val START_LIVE_REWIND = "startLiveRewind"
        const val GET_LIVE_REWIND_STATE = "getLiveRewindState"
        const val UPDATE_VIEWING_METADATA = "updateViewingMetadata"
        const val GO_LIVE = "goLive"
        const val START_VIDEO = "startVideo"
        const val START_CLIP = "startClip"
        const val START_OFFLINE_VIDEO = "startOfflineVideo"
        const val CLEAR_PLAYBACK_RESUMPTION = "clearPlaybackResumption"
        const val GET_CLIP_STATUS = "getClipStatus"
        const val PREPARE_LIVE_CLIP = "prepareLiveClip"
        const val CANCEL_LIVE_CLIP_PREPARATION = "cancelLiveClipPreparation"
        const val RELEASE_LIVE_CLIP = "releaseLiveClip"
        const val GET_VOD_CLIP_DESCRIPTOR = "getVodClipDescriptor"
        const val ESTIMATE_VOD_CLIP_SIZE = "estimateVodClipSize"
        const val PREPARE_VOD_CLIP = "prepareVodClip"
        const val CANCEL_VOD_CLIP_PREPARATION = "cancelVodClipPreparation"
        const val RELEASE_VOD_CLIP = "releaseVodClip"
        const val CLEAR_VOD_CLIP_SOURCE = "clearVodClipSource"
        const val TOGGLE_DYNAMICS_PROCESSING = "toggleDynamicsProcessing"
        const val TOGGLE_PROXY = "toggleProxy"
        const val SET_BACKGROUND_PLAYBACK = "setBackgroundPlayback"
        const val SUPPRESS_VIDEO_IN_BACKGROUND = "suppressVideoInBackground"
        private const val BACKGROUND_VIDEO_SUPPRESSED = "backgroundVideoSuppressed"
        const val SET_SLEEP_TIMER = "setSleepTimer"
        const val GET_SLEEP_TIMER = "getSleepTimer"
        const val CHECK_VAFT = "checkVaft"
        const val GET_QUALITIES = "getQualities"
        const val GET_DURATION = "getDuration"
        const val GET_ERROR_CODE = "getErrorCode"
        const val GET_MEDIA_PLAYLIST = "getMediaPlaylist"
        const val GET_MULTIVARIANT_PLAYLIST = "getMultivariantPlaylist"
        const val GET_VIDEO_INFO = "getVideoInfo"
        const val GET_VIDEO_QUALITY = "getVideoQuality"
        const val SAVE_PLAYBACK_QUALITY = "savePlaybackQuality"
        const val RESET_VIDEO_INFO_SIZE = "resetVideoInfoSize"
        const val VIDEO_INPUT_FORMAT_CHANGED = "videoInputFormatChanged"
        const val VIDEO_QUALITY_NAME = "videoQualityName"
        const val VIDEO_QUALITY_URI = "videoQualityUri"
        const val VIDEO_QUALITY_CODECS = "videoQualityCodecs"
        const val VIDEO_QUALITY_BITRATE = "videoQualityBitrate"
        const val VIDEO_QUALITY_FRAME_RATE = "videoQualityFrameRate"
        const val PLAYBACK_QUALITIES = "playbackQualities"
        const val PLAYBACK_QUALITY = "playbackQuality"
        const val PLAYBACK_PREVIOUS_QUALITY = "playbackPreviousQuality"
        const val PLAYBACK_RESTORE_QUALITY = "playbackRestoreQuality"
        const val PLAYBACK_TYPE = "playbackType"
        const val PLAYBACK_STREAM_ID = "playbackStreamId"
        const val PLAYBACK_CHANNEL_LOGIN = "playbackChannelLogin"
        const val PLAYBACK_VIDEO_ID_STRING = "playbackVideoId"
        const val PLAYBACK_CLIP_ID_STRING = "playbackClipId"
        const val PLAYBACK_OFFLINE_VIDEO_ID = "playbackOfflineVideoId"
        const val PLAYBACK_CONTENT_URL = "playbackContentUrl"

        const val RESULT = "result"
        const val URI = "uri"
        const val URL_WARM = "urlWarm"
        const val TAP_ELAPSED_MS = "tapElapsedMs"
        const val URL_AVAILABLE_ELAPSED_MS = "urlAvailableElapsedMs"
        const val STREAM_ID = "streamId"
        const val VIDEO_ID = "videoId"
        const val VIDEO_ID_STRING = "videoIdString"
        const val CLIP_ID = "clipId"
        const val PLAYBACK_POSITION = "playbackPosition"
        const val TITLE = "title"
        const val CHANNEL_ID = "channelId"
        const val CHANNEL_LOGIN = "channelLogin"
        const val CHANNEL_NAME = "channelName"
        const val CHANNEL_LOGO = "channelLogo"
        const val THUMBNAIL = "thumbnail"
        const val GAME_ID = "gameId"
        const val GAME_SLUG = "gameSlug"
        const val GAME_NAME = "gameName"
        const val GAME_IMAGE = "gameImage"
        const val CREATED_AT = "createdAt"
        const val VIEWER_COUNT = "viewerCount"
        const val DURATION_SECONDS = "durationSeconds"
        const val VIDEO_TYPE = "videoType"
        const val VIDEO_OFFSET_SECONDS = "videoOffsetSeconds"
        const val VIDEO_CREATED_AT = "videoCreatedAt"
        const val VIDEO_ANIMATED_PREVIEW = "videoAnimatedPreview"
        const val USING_PROXY = "usingProxy"
        const val PLAY_WHEN_READY = "playWhenReady"
        const val SUPPRESS_VAFT_OUTPUT = "suppressVaftOutput"
        const val VAFT_HANDOFF = "vaftHandoff"
        const val ACK_VAFT_HANDOFF_FRAME = "ackVaftHandoffFrame"
        const val VAFT_HANDOFF_FRAME_CAPTURE_ID = "vaftHandoffFrameCaptureId"
        const val VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID = "vaftHandoffFrameCaptureResolvedId"
        const val VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED = "vaftHandoffFrameCaptureAccepted"
        const val VAFT_HANDOFF_FRAME_READY = "vaftHandoffFrameReady"
        const val VAFT_HANDOFF_TARGET_MEDIA_ID = "vaftHandoffTargetMediaId"
        const val VAFT_HANDOFF_GENERATION = "vaftHandoffGeneration"
        const val VAFT_HANDOFF_TARGET_FRAME_RENDERED = "vaftHandoffTargetFrameRendered"
        const val VAFT_ALTERNATE_ACTIVE = "vaftAlternateActive"
        const val VAFT_VERIFIED_RENDITION = "vaftVerifiedRendition"
        const val VAFT_LOGICAL_QUALITY = "vaftLogicalQuality"
        const val VAFT_PLAYER_TYPE = "vaftPlayerType"
        const val GET_VAFT_PLAYBACK_STATE = "getVaftPlaybackState"
        const val VAFT_WINDOW_ACTIVE = "vaftWindowActive"
        const val VAFT_PLAYBACK_STATE_CHANGED = "vaftPlaybackStateChanged"
        const val VAFT_SOURCE_URI = "vaftSourceUri"
        const val REWIND_VIDEO_ID = "rewindVideoId"
        const val LIVE_REWIND_ACTIVE = "liveRewindActive"
        const val LIVE_REWIND_TRANSITIONING = "liveRewindTransitioning"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val QUALITIES_SOURCE_URI = "qualitiesSourceUri"
        const val CODECS = "codecs"
        const val BITRATES = "bitrates"
        const val FRAME_RATES = "frameRates"
        const val URLS = "urls"
        const val LIVE_CLIP_AVAILABLE = "liveClipAvailable"
        const val LIVE_CLIP_DURATION_US = "liveClipDurationUs"
        const val VOD_CLIP_AVAILABLE = "vodClipAvailable"
        const val CLIP_DIRECTORY = "clipDirectory"
        const val CLIP_PLAYLIST = "clipPlaylist"
        const val CLIP_BOUNDARIES_US = "clipBoundariesUs"
        const val CLIP_START_INDEX = "clipStartIndex"
        const val CLIP_END_INDEX = "clipEndIndex"
        const val CLIP_DURATION_US = "clipDurationUs"
        const val CLIP_ESTIMATED_BYTES = "clipEstimatedBytes"
        const val VOD_CLIP_MEDIA_ITEM_ID = "vodClipMediaItemId"
        const val VOD_CLIP_PREVIEW_URI = "vodClipPreviewUri"
        const val VOD_CLIP_SEGMENT_DURATIONS_US = "vodClipSegmentDurationsUs"
        const val VOD_CLIP_SEGMENT_BYTE_RANGES = "vodClipSegmentByteRanges"
        const val VOD_CLIP_INITIAL_POSITION_US = "vodClipInitialPositionUs"
        const val VOD_CLIP_BITRATE = "vodClipBitrate"

        private const val LIVE_CLIP_DIRECTORY = "live-clips"
        private const val VOD_CLIP_DIRECTORY = "vod-clips"
        private const val VOD_STORAGE_SAFETY_BYTES = 128L * 1024L * 1024L

        const val REQUEST_CODE_RESUME = 2
    }
}
