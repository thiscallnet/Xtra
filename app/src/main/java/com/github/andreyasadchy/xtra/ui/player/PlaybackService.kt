package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.ForwardingPlayer
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
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
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
import androidx.media3.exoplayer.source.ForwardingTimeline
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
import com.github.andreyasadchy.xtra.player.hls.HiddenStreamAudioPlaylist
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsDiagnosticsSink
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
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
    private var castPlayer: CastPlayer? = null
    private var systemReplayJob: Job? = null
    private var systemReplayRollback: Pair<String?, LiveRewindPlaybackSnapshot>? = null
    private var pendingSystemAudioModeDiagnostic: Boolean? = null
    private var lastSystemAudioModeAvailability: String? = null
    private var lastSystemBehindSeconds: Long? = null
    private var adaptiveLiveController: AdaptiveLivePlaybackController? = null
    private var adaptiveLiveSpeedControl: AdaptiveLivePlaybackSpeedControl? = null
    private var adaptiveLiveSampleJob: Job? = null
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
    private var hiddenAudioJob: Job? = null
    private var hiddenAudioSource: HiddenStreamAudioPlaylist? = null
    private val screenVisibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateHiddenStreamAudio()
        }
    }
    private var backgroundVideoSuppressed = false
    private var backgroundRecoveryTimer: Timer? = null
    private var backgroundRecoveryAttempt = 0
    private var backgroundPlaybackStartedAtMs: Long? = null
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
    private var viewingStreamPreviewGeneration = 0L
    private var viewingContentType: String? = null
    private var viewingContentId: String? = null
    private var streamStartupTrace: StreamStartupTrace? = null
    private var liveRewindActive = false
    private var liveRewindVodId: String? = null
    private var liveRewindTransitioning = false
    private var lastMediaButtonSeekable: Boolean? = null
    private var liveStreamUri: String? = null
    private var livePlaybackSessionGeneration = 0L
    private var primaryQualityCatalogMasterUri: String? = null
    private var primaryQualityCatalog: List<VideoQuality>? = null
    private var primaryQualityCatalogRenditionUris: Set<String> = emptySet()
    private var primaryQualityCatalogSessionGeneration = -1L
    private var vaftHandoffJob: Job? = null
    private var vaftAlternateActive = false
    private var controlledVaftBadgeMessage: androidx.media3.exoplayer.PlayerMessage? = null
    private var controlledVaftBadgeKey: String? = null
    private var controlledQualityCatalogKey: String? = null
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
    private var trackedVaftBoundary: TrackedVaftBoundary? = null
    private var vaftBoundaryWatchJob: Job? = null
    private var vaftBoundaryWatchMarkerKey: String? = null
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
    private var vaftEntryFrameOwner: VaftEntryFrameOwner? = null
    private var vaftEntryFrameCaptureArmed = false
    private var vaftEntryFrameAttemptedBoundaryKey: String? = null
    private var vaftEntryFrameAcceptedId: String? = null
    private var vaftEntryFrameResolvedId: String? = null
    private var vaftEntryFrameCaptureAccepted = false
    private var vaftEntryFrameVisibleId: String? = null
    private var vaftEntryFrameReleaseMediaId: String? = null
    private var vaftEntryFrameReleaseGeneration = -1L
    private var vaftEntryFrameReleaseAuthorized = false
    private var vaftEntryFrameReleaseRevision = 0L
    private var vaftHandoffTargetMediaId: String? = null
    private var vaftHandoffTargetGeneration = -1L
    private var vaftHandoffTargetFrameRendered = false
    private var lastVaftBoundaryObservationKey: String? = null
    private var vaftPrimaryFirstFrameMediaId: String? = null
    private var vaftPrimaryFirstFrameSourceUri: String? = null
    private var vaftPrimaryFirstFrameElapsedMs: Long? = null

    private fun invalidateVaftOwnership() {
        controlledVaftBadgeMessage?.cancel()
        controlledVaftBadgeMessage = null
        controlledVaftBadgeKey = null
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
        vaftEntryFrameAttemptedBoundaryKey = null
        if (::xtraModule.isInitialized) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(vaftWarmupToken)
        }
        vaftWarmupToken = null
        vaftHandoffFrameCaptureFuture?.set(false)
        vaftHandoffFrameCaptureFuture = null
        vaftHandoffFrameCaptureId = null
        vaftHandoffFrameCaptureResolvedId = null
        vaftHandoffFrameCaptureAccepted = false
        clearVaftEntryFrameBridge()
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

    private fun clearVaftEntryFrameBridge() {
        val clearedId = vaftEntryFrameOwner?.requestId ?: vaftEntryFrameAcceptedId ?: vaftEntryFrameVisibleId
            ?: vaftEntryFrameResolvedId
        vaftEntryFrameOwner = null
        vaftEntryFrameCaptureArmed = false
        vaftEntryFrameAcceptedId = null
        vaftEntryFrameResolvedId = clearedId
        vaftEntryFrameCaptureAccepted = false
        vaftEntryFrameVisibleId = null
        vaftEntryFrameReleaseMediaId = null
        vaftEntryFrameReleaseGeneration = -1L
        vaftEntryFrameReleaseAuthorized = false
        vaftEntryFrameReleaseRevision++
    }

    private fun cancelVaftEntryFrameRelease() {
        vaftEntryFrameReleaseRevision++
        vaftEntryFrameReleaseMediaId = null
        vaftEntryFrameReleaseGeneration = -1L
        vaftEntryFrameReleaseAuthorized = false
    }

    private fun publishVaftPlaybackState() {
        if (BuildConfig.DEBUG) Log.d("XtraVaft", "state handoff=$vaftSourceSwitching window=${vaftCoordinatorJob?.isActive == true} alternate=$vaftAlternateActive suppressed=$vaftOutputSuppressed generation=$vaftGeneration entryCapture=${vaftEntryFrameCaptureArmed} entryVisible=${vaftEntryFrameVisibleId?.takeLast(8)} entryRelease=${vaftEntryFrameReleaseAuthorized}")
        mediaSession?.broadcastCustomCommand(SessionCommand(VAFT_PLAYBACK_STATE_CHANGED, Bundle.EMPTY), Bundle().apply {
            putBoolean(VAFT_HANDOFF, vaftSourceSwitching)
            putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true || vaftAlternateActive)
            putBoolean(VAFT_CONTROLLED_FEED, xtraModule.streamMedia3Runtime.controlledPlaylistFor(playbackPlayer?.currentMediaItem?.mediaId) != null)
            putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
            putBoolean(SUPPRESS_VAFT_OUTPUT, vaftOutputSuppressed)
            putString(VAFT_SOURCE_URI, vaftAuthoritativeUri)
            putString(VAFT_HANDOFF_FRAME_CAPTURE_ID, vaftHandoffFrameCaptureId)
            putString(VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID, vaftHandoffFrameCaptureResolvedId)
            putBoolean(VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED, vaftHandoffFrameCaptureAccepted)
            putString(
                VAFT_ENTRY_FRAME_CAPTURE_ID,
                vaftEntryFrameOwner?.requestId.takeIf { vaftEntryFrameCaptureArmed },
            )
            putString(VAFT_ENTRY_FRAME_ACCEPTED_ID, vaftEntryFrameAcceptedId)
            putString(VAFT_ENTRY_FRAME_RESOLVED_ID, vaftEntryFrameResolvedId)
            putBoolean(VAFT_ENTRY_FRAME_CAPTURE_ACCEPTED, vaftEntryFrameCaptureAccepted)
            putString(VAFT_ENTRY_FRAME_VISIBLE_ID, vaftEntryFrameVisibleId)
            putString(VAFT_ENTRY_FRAME_RELEASE_MEDIA_ID, vaftEntryFrameReleaseMediaId)
            putLong(VAFT_ENTRY_FRAME_RELEASE_GENERATION, vaftEntryFrameReleaseGeneration)
            putBoolean(VAFT_ENTRY_FRAME_RELEASE_AUTHORIZED, vaftEntryFrameReleaseAuthorized)
            putString(VAFT_HANDOFF_TARGET_MEDIA_ID, vaftHandoffTargetMediaId)
            putLong(VAFT_HANDOFF_GENERATION, vaftHandoffTargetGeneration)
            putBoolean(VAFT_HANDOFF_TARGET_FRAME_RENDERED, vaftHandoffTargetFrameRendered)
            vaftVerifiedRendition?.let { putString(VAFT_VERIFIED_RENDITION, xtraModule.json.encodeToString(it)) }
            vaftLogicalQuality?.let { putString(VAFT_LOGICAL_QUALITY, xtraModule.json.encodeToString(it)) }
            vaftCurrentPlayerType?.let { putString(VAFT_PLAYER_TYPE, it) }
        })
        mediaSession?.player?.let(::refreshMediaButtonPreferences)
    }
    private var liveStreamExtras: Bundle? = null
    private var resumptionState: PlaybackState? = null
    private var pendingPlaybackQualityState: PlaybackState? = null
    private val mediaPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == C.SETTINGS_BACKGROUND_PLAYBACK) Handler(Looper.getMainLooper()).post { updateHiddenStreamAudio() }
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
                    refreshSystemMediaMetadata()
                    refreshMediaButtonPreferences(it)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        setMediaNotificationProvider(
            LiveMediaNotificationProvider(this, DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build()),
        )
        xtraModule = (application as XtraApp).xtraModule
        lifecycleScope.launch(Dispatchers.IO) {
            ClipPreparationRepository.cleanupStale(File(cacheDir, LIVE_CLIP_DIRECTORY))
            ClipPreparationRepository.cleanupStale(File(cacheDir, VOD_CLIP_DIRECTORY))
        }
        primaryPlaybackWatchOwnerId = xtraModule.primaryPlaybackWatchState.newOwnerId()
        prefs().registerOnSharedPreferenceChangeListener(mediaPreferenceListener)
        androidx.core.content.ContextCompat.registerReceiver(this, screenVisibilityReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
                delay(60_000L)
                if (backgroundPlayback && mediaSession?.player?.currentMediaItem != null) {
                    refreshBackgroundStreamMetadata()
                }
            }
        }
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateHiddenStreamAudio()
                    updateAdaptiveLiveSampleTicker(player)
                    updateVaft(player)
                    updateViewingStats(player)
                    if (isPlaying) {
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundPlaybackStartedAtMs = SystemClock.elapsedRealtime()
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
                    systemReplayRollback?.takeIf { it.first == player.currentMediaItem?.mediaId && !isCasting() }?.let { (_, snapshot) ->
                        systemReplayRollback = null
                        setLiveRewindSessionState(active = snapshot.liveRewindActive, vodId = snapshot.liveRewindVodId)
                        restoreLiveRewindPlayback(player, snapshot)
                        Log.w("LiveRewind", "System replay failed before ready; restored live playback", error)
                        return
                    }
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
                        && player.playWhenReady && viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
                    ) {
                        scheduleBackgroundRecovery()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateHiddenStreamAudio()
                    if (playbackState == Player.STATE_READY) systemReplayRollback = null
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
                    } else if (playbackState == Player.STATE_BUFFERING && backgroundPlayback &&
                        player.playWhenReady && viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
                    ) {
                        scheduleBackgroundRecovery(delayOverrideMs = BACKGROUND_STALL_RECOVERY_DELAY_MS)
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateHiddenStreamAudio()
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
                    if (!playWhenReady) {
                        backgroundRecoveryTimer?.cancel()
                        backgroundRecoveryTimer = null
                        backgroundRecoveryAttempt = 0
                        backgroundPlaybackStartedAtMs = null
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
                    updateHiddenStreamAudio()
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
                    updateHiddenStreamAudio()
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
                    refreshSystemMediaMetadata()
                    refreshMediaButtonPreferences(player)
                    syncTwitchHlsDiagnostics(player)
                    updateAdaptiveLiveSampleTicker(player)
                }

                override fun onRenderedFirstFrame() {
                    streamStartupTrace?.markFirstFrame()
                    streamStartupTrace?.let { xtraModule.streamPreviewCoordinator.onFullscreenPlaybackFirstFrame(it.channelLogin) }
                    diagnostics.recordRenderedFirstFrame(player.currentTracks)
                    val firstFrameElapsedMs = SystemClock.elapsedRealtime()
                    val firstFrameItem = player.currentMediaItem
                    val firstFrameSourceUri = firstFrameItem?.localConfiguration?.uri?.toString()
                    if (firstFrameItem != null && firstFrameSourceUri == liveStreamUri) {
                        vaftPrimaryFirstFrameMediaId = firstFrameItem.mediaId
                        vaftPrimaryFirstFrameSourceUri = firstFrameSourceUri
                        vaftPrimaryFirstFrameElapsedMs = firstFrameElapsedMs
                    }
                    if (!vaftHandoffTargetFrameRendered && vaftHandoffTargetGeneration == vaftGeneration &&
                        player.currentMediaItem?.mediaId == vaftHandoffTargetMediaId
                    ) {
                        vaftHandoffTargetFrameRendered = true
                        publishVaftPlaybackState()
                    }
                    if (vaftEntryFrameReleaseAuthorized &&
                        vaftEntryFrameReleaseGeneration == vaftGeneration &&
                        player.currentMediaItem?.mediaId == vaftEntryFrameReleaseMediaId &&
                        !vaftOutputSuppressed
                    ) {
                        if (BuildConfig.DEBUG) Log.d("XtraVaft", "entry_frame_release first_frame media=${diagnosticToken(vaftEntryFrameReleaseMediaId)}")
                        clearVaftEntryFrameBridge()
                        publishVaftPlaybackState()
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "PlaybackLifecycle",
                            "event=rendered_first_frame pid=${Process.myPid()} player=${player.identityId()} " +
                                "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} " +
                                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                                "positionMs=${player.currentPosition} elapsedRealtimeMs=$firstFrameElapsedMs " +
                                "primaryVaftSource=${firstFrameSourceUri == liveStreamUri}",
                        )
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    diagnostics.recordRenderedVideoSize(videoSize.width, videoSize.height, player.currentTracks)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateHiddenStreamAudio()
                    diagnostics.confirmPendingRenderedVideoSizeAfterTracksChanged(tracks)
                    pendingSystemAudioModeDiagnostic?.let { expectingAudioOnly ->
                        val expectedUri = resumptionState?.playlistUrl
                        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                        val audioSelected = tracks.groups.any { group ->
                            group.type == Media3C.TRACK_TYPE_AUDIO &&
                                (0 until group.length).any(group::isTrackSelected)
                        }
                        val videoSelected = tracks.groups.any { group ->
                            group.type == Media3C.TRACK_TYPE_VIDEO &&
                                (0 until group.length).any(group::isTrackSelected)
                        }
                        val expectedTracksSelected = audioSelected &&
                            (expectingAudioOnly || videoSelected)
                        if ((expectedUri == null || currentUri == expectedUri) && expectedTracksSelected) {
                            if (BuildConfig.DEBUG) logSystemAudioModeState("tracks_ready", player)
                            pendingSystemAudioModeDiagnostic = null
                        }
                    }
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
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                if (BuildConfig.DEBUG) Log.d("HiddenStreamAudio", "event=audio_underrun bufferMs=$bufferSizeMs elapsedSinceFeedMs=$elapsedSinceLastFeedMs")
            }
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
                if (BuildConfig.DEBUG) {
                    Log.d("XtraVaftFeed", "event=decoder_input height=${format.height} width=${format.width} " +
                        "reuse=${decoderReuseEvaluation?.result} discard=${decoderReuseEvaluation?.discardReasons}")
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
                        val downstreamMediaId = runCatching {
                            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
                        }.getOrNull()
                        if (downstreamMediaId == player.currentMediaItem?.mediaId) {
                            // Chunk metadata identifies the primary route even when its media has replacement dimensions.
                            xtraModule.streamMedia3Runtime.controlledPlaylistFor(downstreamMediaId)?.selectFormat(format)
                        }
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
                if (BuildConfig.DEBUG && hiddenAudioSource != null && mediaLoadData.dataType == Media3C.DATA_TYPE_MEDIA) {
                    Log.d("HiddenStreamAudio", "event=loaded bytes=${loadEventInfo.bytesLoaded} positionMs=${player.currentPosition} bufferMs=${player.totalBufferedDuration} videoInputs=${player.videoDecoderCounters?.queuedInputBufferCount} audioInputs=${player.audioDecoderCounters?.queuedInputBufferCount}")
                }
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
        val castSessionPlayer = runCatching {
            CastPlayer.Builder(this)
                .setLocalPlayer(player)
                .setTransferCallback { sourcePlayer, targetPlayer ->
                    val metadataSource = object : ForwardingPlayer(sourcePlayer) {
                        override fun getMediaItemAt(index: Int): MediaItem {
                            val item = super.getMediaItemAt(index)
                            return if (index == sourcePlayer.currentMediaItemIndex && viewingContentType != null &&
                                prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)
                            ) {
                                item.buildUpon().setMediaMetadata(systemMediaMetadata()).build()
                            } else {
                                item
                            }
                        }
                    }
                    CastPlayer.TransferCallback.DEFAULT.transferState(metadataSource, targetPlayer)
                }
                .setRemotePlayer(
                    RemoteCastPlayer.Builder(this)
                        .setSeekBackIncrementMs(player.seekBackIncrement)
                        .setSeekForwardIncrementMs(player.seekForwardIncrement)
                        .build(),
                )
                .build()
        }.onFailure { error ->
            Log.w("PlaybackService", "Cast playback is unavailable; keeping local playback", error)
        }.getOrNull()
        castPlayer = castSessionPlayer
        val sessionPlayer = PlaybackSessionPlayer(castSessionPlayer ?: player)
        playbackSessionPlayer = sessionPlayer
        sessionPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshMediaButtonPreferences(player)
            }
        })
        castSessionPlayer?.let { configuredCastPlayer ->
            configuredCastPlayer.addListener(object : Player.Listener {
                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        invalidateVaftOwnership()
                        restoreBackgroundVideoSuppression(player)
                    }
                    refreshSystemMediaMetadata()
                    refreshMediaButtonPreferences(configuredCastPlayer)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                        events.contains(Player.EVENT_DEVICE_INFO_CHANGED)
                    ) {
                        updateViewingStats(player)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshSystemMediaMetadata()
                    refreshMediaButtonPreferences(configuredCastPlayer)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    refreshSystemMediaMetadata()
                    refreshMediaButtonPreferences(configuredCastPlayer)
                }
            })
        }
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
                                .setAvailableSessionCommands(
                                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                        .add(SessionCommand(GO_LIVE, Bundle.EMPTY))
                                        .add(SessionCommand(REPLAY_30, Bundle.EMPTY))
                                        .add(SessionCommand(TOGGLE_SYSTEM_AUDIO, Bundle.EMPTY))
                                        .build(),
                                )
                                .build()
                        }
                        val connectionResult = super.onConnect(session, controller)
                        if (!isTrustedController(controller)) {
                            return if (controller.isTrusted) {
                                MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                                    .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
                                    .setAvailableSessionCommands(
                                        connectionResult.availableSessionCommands.buildUpon()
                                            .add(SessionCommand(GO_LIVE, Bundle.EMPTY))
                                            .add(SessionCommand(REPLAY_30, Bundle.EMPTY))
                                            .add(SessionCommand(TOGGLE_SYSTEM_AUDIO, Bundle.EMPTY))
                                            .build(),
                                    )
                                    .build()
                            } else {
                                connectionResult
                            }
                        }
                        val sessionCommands = connectionResult.availableSessionCommands.buildUpon().apply {
                            add(SessionCommand(START_STREAM, Bundle.EMPTY))
                            add(SessionCommand(START_LIVE_REWIND, Bundle.EMPTY))
                            add(SessionCommand(GET_LIVE_REWIND_STATE, Bundle.EMPTY))
                            add(SessionCommand(GET_VAFT_PLAYBACK_STATE, Bundle.EMPTY))
                            add(SessionCommand(ACK_VAFT_HANDOFF_FRAME, Bundle.EMPTY))
                            add(SessionCommand(ACK_VAFT_ENTRY_FRAME, Bundle.EMPTY))
                            add(SessionCommand(UPDATE_VIEWING_METADATA, Bundle.EMPTY))
                            add(SessionCommand(GO_LIVE, Bundle.EMPTY))
                            add(SessionCommand(REPLAY_30, Bundle.EMPTY))
                            add(SessionCommand(TOGGLE_SYSTEM_AUDIO, Bundle.EMPTY))
                            add(SessionCommand(SYSTEM_QUALITY_CHANGED, Bundle.EMPTY))
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
                        if (!isTrustedController(controller) &&
                            !(customCommand.customAction in setOf(GO_LIVE, REPLAY_30, TOGGLE_SYSTEM_AUDIO) && controller.isTrusted)
                        ) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                        }
                        if (customCommand.customAction == TOGGLE_SYSTEM_AUDIO &&
                            !prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)
                        ) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                        if (isCasting() && customCommand.customAction == START_OFFLINE_VIDEO) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                        }
                        if (isCasting() && customCommand.customAction in listOf(START_STREAM, START_VIDEO, START_CLIP, START_LIVE_REWIND)) {
                            val uri = customCommand.customExtras.getString(URI)?.toUri()
                            if (uri?.scheme !in listOf("http", "https")) {
                                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                            }
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
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
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
                            START_LIVE_REWIND -> startLiveRewind(player, customCommand.customExtras)
                            REPLAY_30 -> replayFromSystemMedia()
                            TOGGLE_SYSTEM_AUDIO -> setSystemAudioOnly(!isLogicalAudioOnly())
                            GET_LIVE_REWIND_STATE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBoolean(LIVE_REWIND_ACTIVE, liveRewindActive)
                                    putBoolean(LIVE_REWIND_TRANSITIONING, liveRewindTransitioning)
                                    putString(REWIND_VIDEO_ID, liveRewindVodId)
                                }))
                            }
                            GET_VAFT_PLAYBACK_STATE -> {
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putBundle(VAFT_QUALITY_STATUS, vaftQualityStatus(player))
                                    putBoolean(VAFT_HANDOFF, vaftSourceSwitching)
                                    putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
                                    putBoolean(SUPPRESS_VAFT_OUTPUT, vaftOutputSuppressed)
                                    putString(VAFT_SOURCE_URI, vaftAuthoritativeUri)
                                    putString(SYSTEM_AUDIO_PRIMARY_SOURCE_URI, liveStreamExtras?.getString(URI))
                                    putString(VAFT_HANDOFF_FRAME_CAPTURE_ID, vaftHandoffFrameCaptureId)
                                    putString(VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID, vaftHandoffFrameCaptureResolvedId)
                                    putBoolean(VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED, vaftHandoffFrameCaptureAccepted)
                                    putString(
                                        VAFT_ENTRY_FRAME_CAPTURE_ID,
                                        vaftEntryFrameOwner?.requestId.takeIf { vaftEntryFrameCaptureArmed },
                                    )
                                    putString(VAFT_ENTRY_FRAME_ACCEPTED_ID, vaftEntryFrameAcceptedId)
                                    putString(VAFT_ENTRY_FRAME_RESOLVED_ID, vaftEntryFrameResolvedId)
                                    putBoolean(VAFT_ENTRY_FRAME_CAPTURE_ACCEPTED, vaftEntryFrameCaptureAccepted)
                                    putString(VAFT_ENTRY_FRAME_VISIBLE_ID, vaftEntryFrameVisibleId)
                                    putString(VAFT_ENTRY_FRAME_RELEASE_MEDIA_ID, vaftEntryFrameReleaseMediaId)
                                    putLong(VAFT_ENTRY_FRAME_RELEASE_GENERATION, vaftEntryFrameReleaseGeneration)
                                    putBoolean(VAFT_ENTRY_FRAME_RELEASE_AUTHORIZED, vaftEntryFrameReleaseAuthorized)
                                    putString(VAFT_HANDOFF_TARGET_MEDIA_ID, vaftHandoffTargetMediaId)
                                    putLong(VAFT_HANDOFF_GENERATION, vaftHandoffTargetGeneration)
                                    putBoolean(VAFT_HANDOFF_TARGET_FRAME_RENDERED, vaftHandoffTargetFrameRendered)
                                    putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true || vaftAlternateActive)
                                    putBoolean(VAFT_CONTROLLED_FEED, xtraModule.streamMedia3Runtime.controlledPlaylistFor(playbackPlayer?.currentMediaItem?.mediaId) != null)
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
                            ACK_VAFT_ENTRY_FRAME -> {
                                val requestId = args.getString(VAFT_ENTRY_FRAME_CAPTURE_ID)
                                val owner = vaftEntryFrameOwner
                                val ready = args.getBoolean(VAFT_ENTRY_FRAME_READY)
                                val player = playbackPlayer
            val accepted = ready && owner != null && requestId == owner.requestId &&
                vaftEntryFrameCaptureArmed && player != null &&
                isVaftEntryFrameOwnerCurrent(player, owner)
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "XtraVaft",
                                        "entry_frame_ack request=${requestId?.takeLast(8) ?: "none"} " +
                                            "ready=$ready accepted=$accepted owner=${owner?.requestId?.takeLast(8) ?: "none"} " +
                                            "ageMs=${owner?.let { SystemClock.elapsedRealtime() - it.requestedAtMs } ?: -1L}",
                                    )
                                }
                                if (requestId.isNullOrBlank() || owner?.requestId != requestId) {
                                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                                }
                                vaftEntryFrameResolvedId = requestId
                                vaftEntryFrameCaptureAccepted = accepted
                                vaftEntryFrameAcceptedId = requestId.takeIf { accepted }
                                vaftEntryFrameCaptureArmed = false
                                if (!accepted) vaftEntryFrameOwner = null
                                publishVaftPlaybackState()
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
                                setPlaybackSource(player, runtime.createHlsMediaSource(mediaItem), position)
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
                                setPlaybackSource(
                                    player,
                                    ProgressiveMediaSource.Factory(
                                        DefaultDataSource.Factory(
                                            this@PlaybackService,
                                            when {
                                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                    HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.httpExecutor.value, false, false, null, null, null) { false }
                                                }
                                                else -> {
                                                    OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                                                }
                                            }
                                        )
                                    ).createMediaSource(
                                        MediaItem.Builder().apply {
                                            setUri(uri?.toUri())
                                            setMimeType(MimeTypes.VIDEO_MP4)
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
                                updateHiddenStreamAudio()
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
                                    putBoolean(VAFT_WINDOW_ACTIVE, vaftCoordinatorJob?.isActive == true || vaftAlternateActive)
                                    putBoolean(VAFT_CONTROLLED_FEED, xtraModule.streamMedia3Runtime.controlledPlaylistFor(playbackPlayer?.currentMediaItem?.mediaId) != null)
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
                                val currentMediaItem = session.player.currentMediaItem
                                val sourceMediaId = currentMediaItem?.mediaId
                                val sourceUri = currentMediaItem?.localConfiguration?.uri?.toString()
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
                                val currentCatalog = list?.takeIf { it.isNotEmpty() }?.toList()
                                val cachedSessionMatches =
                                    primaryQualityCatalogSessionGeneration == livePlaybackSessionGeneration &&
                                        !primaryQualityCatalogMasterUri.isNullOrBlank() &&
                                        !primaryQualityCatalog.isNullOrEmpty()
                                val liveUriMatchesCachedCatalog = liveStreamUri == primaryQualityCatalogMasterUri ||
                                    liveStreamUri?.let { it in primaryQualityCatalogRenditionUris } == true
                                if (cachedSessionMatches && !liveUriMatchesCachedCatalog) {
                                    primaryQualityCatalogMasterUri = null
                                    primaryQualityCatalog = null
                                    primaryQualityCatalogRenditionUris = emptySet()
                                    primaryQualityCatalogSessionGeneration = -1L
                                }
                                val controlledPlaylist = xtraModule.streamMedia3Runtime.controlledPlaylistFor(sourceMediaId)
                                controlledPlaylist?.selectFormat(selectedControlledFormat(session.player))
                                val controlledQualitySource = controlledPlaylist != null
                                if ((controlledQualitySource || !vaftAlternateActive) && !vaftSourceSwitching &&
                                    sourceUri == liveStreamUri && currentCatalog != null
                                ) {
                                    primaryQualityCatalogMasterUri = sourceUri
                                    primaryQualityCatalog = currentCatalog
                                    primaryQualityCatalogRenditionUris = currentCatalog.mapNotNull { it.url }.toSet()
                                    primaryQualityCatalogSessionGeneration = livePlaybackSessionGeneration
                                }
                                val cachedPrimaryCatalogIsCurrent =
                                    primaryQualityCatalogSessionGeneration == livePlaybackSessionGeneration &&
                                        !primaryQualityCatalogMasterUri.isNullOrBlank() &&
                                        !primaryQualityCatalog.isNullOrEmpty() &&
                                        (liveStreamUri == primaryQualityCatalogMasterUri ||
                                            liveStreamUri?.let { it in primaryQualityCatalogRenditionUris } == true)
                                val sourceMatchesCachedPrimaryCatalog = cachedPrimaryCatalogIsCurrent &&
                                    (sourceUri == primaryQualityCatalogMasterUri ||
                                        sourceUri?.let { it in primaryQualityCatalogRenditionUris } == true)
                                val isPrimaryQualitySource = (controlledQualitySource || !vaftAlternateActive) && !vaftSourceSwitching &&
                                    (sourceUri == liveStreamUri || sourceMatchesCachedPrimaryCatalog)
                                val primaryCatalog = if (sourceMatchesCachedPrimaryCatalog && isPrimaryQualitySource) {
                                    primaryQualityCatalog
                                } else {
                                    list
                                }
                                val epochUs = if (!session.player.currentTimeline.isEmpty) {
                                    session.player.currentTimeline.getWindow(session.player.currentMediaItemIndex, Timeline.Window()).windowStartTimeMs * 1_000L +
                                        session.player.currentPosition * 1_000L
                                } else Media3C.TIME_UNSET
                                val alternateFormats = controlledPlaylist?.availableFormatsAt(epochUs)
                                // Keep the full primary ladder visible while a controlled VAFT feed
                                // resolves the selected quality. Its temporary catalog can be partial
                                // while rendition probes are still completing or recovering.
                                val alternateCatalog = alternateFormats?.mapNotNull { format ->
                                    val primary = playlist?.variants?.filter {
                                        (format.height > 0 && it.format.height >= format.height || format.height <= 0 && it.format.height <= 0) &&
                                            it.format.codecs?.substringBefore(',')?.take(4) == format.codecs?.substringBefore(',')?.take(4)
                                    }?.minWithOrNull(compareBy({ it.format.height },
                                        { kotlin.math.abs(it.format.frameRate - format.frameRate) })) ?: return@mapNotNull null
                                    VideoQuality(
                                        name = if (format.height > 0) "${format.height}p${format.frameRate.toInt().takeIf { it > 30 } ?: ""}" else PlaybackContract.AUDIO_ONLY_QUALITY,
                                        codecs = format.codecs, bitrate = format.bitrate, url = primary.url.toString(), frameRate = format.frameRate,
                                    )
                                }
                                val useControlledPrimaryCatalog = controlledQualitySource && !primaryCatalog.isNullOrEmpty()
                                val usableAlternateCatalog = alternateCatalog?.takeIf { it.isNotEmpty() }
                                val useAlternateCatalog = !useControlledPrimaryCatalog && !usableAlternateCatalog.isNullOrEmpty()
                                val catalog = when {
                                    useControlledPrimaryCatalog -> primaryCatalog
                                    useAlternateCatalog -> usableAlternateCatalog
                                    else -> primaryCatalog
                                }
                                val catalogMasterUri = if (sourceMatchesCachedPrimaryCatalog && isPrimaryQualitySource) {
                                    primaryQualityCatalogMasterUri
                                } else if (isPrimaryQualitySource && sourceUri == liveStreamUri && currentCatalog != null) {
                                    sourceUri
                                } else {
                                    null
                                }
                                if (BuildConfig.DEBUG) {
                                    val catalogOrigin = when {
                                        sourceMatchesCachedPrimaryCatalog && isPrimaryQualitySource -> "primary_cache"
                                        useControlledPrimaryCatalog -> "controlled_primary"
                                        useAlternateCatalog -> "alternate_fallback"
                                        else -> "current_manifest"
                                    }
                                    Log.d(
                                        "XtraQuality",
                                        "event=quality_catalog_emit itemToken=${diagnosticToken(sourceMediaId)} " +
                                            "sourceToken=${diagnosticToken(sourceUri)} " +
                                            "catalogUriToken=${diagnosticToken(catalogMasterUri)} " +
                                            "origin=$catalogOrigin primary=$isPrimaryQualitySource " +
                                            "count=${catalog?.size ?: 0} " +
                                            "rowsToken=${qualityCatalogRowsToken(catalog)}",
                                    )
                                }
                                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                                    putString(QUALITIES_MEDIA_ID, sourceMediaId)
                                    putString(QUALITIES_SOURCE_URI, sourceUri)
                                    putString(QUALITIES_CATALOG_URI, catalogMasterUri)
                                    putBoolean(QUALITIES_CATALOG_PRIMARY, isPrimaryQualitySource)
                                    if (BuildConfig.DEBUG) {
                                        putString(QUALITIES_ROWS_TOKEN, qualityCatalogRowsToken(catalog))
                                    }
                                    if (controlledQualitySource) {
                                        primaryCatalog?.let { putString(CONTROLLED_PRIMARY_QUALITIES, xtraModule.json.encodeToString(it)) }
                                        if (useAlternateCatalog) {
                                            val selectionFormats = catalog?.map { quality -> playlist?.variants?.find { it.url.toString() == quality.url }?.format }
                                            putStringArray(CONTROLLED_SELECTION_NAMES, selectionFormats?.map { format ->
                                                format?.let { if (it.height > 0) "${it.height}p${it.frameRate.toInt().takeIf { fps -> fps > 30 } ?: ""}" else PlaybackContract.AUDIO_ONLY_QUALITY }.toString()
                                            }?.toTypedArray())
                                            putStringArray(CONTROLLED_SELECTION_CODECS, selectionFormats?.map { it?.codecs.toString() }?.toTypedArray())
                                        }
                                    }
                                    putStringArray(NAMES, catalog?.map { it.name.toString() }?.toTypedArray())
                                    putStringArray(CODECS, catalog?.map { it.codecs.toString() }?.toTypedArray())
                                    putStringArray(BITRATES, catalog?.map { it.bitrate.toString() }?.toTypedArray())
                                    putStringArray(FRAME_RATES, catalog?.map { it.frameRate.toString() }?.toTypedArray())
                                    putStringArray(URLS, catalog?.map { it.url.toString() }?.toTypedArray())
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
                                val controlled = xtraModule.streamMedia3Runtime.controlledPlaylistFor(currentMediaItem?.mediaId)
                                val epochUs = if (!session.player.currentTimeline.isEmpty) {
                                    session.player.currentTimeline.getWindow(session.player.currentMediaItemIndex, Timeline.Window()).windowStartTimeMs * 1_000L +
                                        session.player.currentPosition * 1_000L
                                } else Media3C.TIME_UNSET
                                controlled?.selectFormat(selectedControlledFormat(session.player))
                                val replacementFormat = controlled?.formatAt(epochUs)
                                val quality = replacementFormat?.let { format ->
                                    VideoQuality(
                                        name = if (format.height > 0) "${format.height}p${format.frameRate.toInt().takeIf { it > 30 } ?: ""}" else PlaybackContract.AUDIO_ONLY_QUALITY,
                                        codecs = format.codecs, bitrate = format.bitrate, frameRate = format.frameRate,
                                    )
                                } ?: diagnostics.confirmedVideoQuality(
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
                                            quality?.frameRate?.let { putFloat(VIDEO_QUALITY_FRAME_RATE, it) }
                                        },
                                    ),
                                )
                            }
                            SAVE_PLAYBACK_QUALITY -> {
                                val extras = customCommand.customExtras
                                val selectedQuality = decodePlaybackQuality(
                                    xtraModule.json,
                                    extras.getString(PLAYBACK_QUALITY),
                                )
                                val selectedNonVideoQuality = selectedQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY ||
                                    selectedQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY
                                if (extras.getString(PLAYBACK_TYPE) == PlaybackContract.STREAM &&
                                    selectedNonVideoQuality &&
                                    (vaftEntryFrameOwner != null || vaftEntryFrameVisibleId != null)
                                ) {
                                    clearVaftEntryFrameBridge()
                                    publishVaftPlaybackState()
                                }
                                if (selectedQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY &&
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
                                if (throwable is CancellationException) throw throwable
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
        if (commandStateChanged) {
            playbackPlayer?.let(::updateAdaptiveLiveSampleTicker)
            invalidatePlaybackSessionPlayerState()
            mediaSession?.player?.let { player ->
                refreshSystemMediaMetadata()
                refreshMediaButtonPreferences(player)
            }
        }
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
                    .setTitle(if (state.type == PlaybackContract.STREAM) state.channelName ?: state.channelLogin else state.title)
                    .setArtist(if (state.type == PlaybackContract.STREAM) buildList {
                        add(getString(R.string.player_live))
                        state.gameName?.takeIf { prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_CATEGORY, true) }?.let(::add)
                        state.title?.takeIf { prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_TITLE, true) }?.let(::add)
                    }.joinToString(" · ") else state.channelName)
                    .setSubtitle(state.gameName?.takeIf { prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_CATEGORY, true) })
                    .setArtworkUri(when (prefs().getString(C.SYSTEM_MEDIA_ARTWORK_SOURCE, C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW)) {
                        C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW -> state.thumbnail?.toUri() ?: state.channelImage?.toUri()
                        C.SYSTEM_MEDIA_ARTWORK_NONE -> null
                        else -> state.channelImage?.toUri()
                    })
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
        playbackPlayer?.let(::updateAdaptiveLiveSampleTicker)

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
        mediaSession?.player?.let(::refreshMediaButtonPreferences)
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

    private fun shouldRunLiveSampleTicker(player: Player): Boolean =
        viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
            (player.isCurrentMediaItemLive && player.isPlaying || liveRewindActive && player.currentMediaItem != null)

    private fun updateAdaptiveLiveSampleTicker(player: Player) {
        val shouldSample = shouldRunLiveSampleTicker(player)
        if (!shouldSample) {
            adaptiveLiveSampleJob?.cancel()
            adaptiveLiveSampleJob = null
            return
        }
        if (adaptiveLiveSampleJob?.isActive == true) return

        adaptiveLiveSampleJob = lifecycleScope.launch {
            try {
                while (true) {
                    delay(5_000L)
                    if (!shouldRunLiveSampleTicker(player)) {
                        break
                    }
                    val behindSeconds = systemBehindLiveSeconds()
                    if (behindSeconds != lastSystemBehindSeconds) {
                        lastSystemBehindSeconds = behindSeconds
                        refreshSystemMediaMetadata()
                    }
                    if (player.isCurrentMediaItemLive && player.isPlaying) {
                        adaptiveLiveController?.onStableSample(
                            bufferedMs = player.totalBufferedDuration,
                            realtimeMs = SystemClock.elapsedRealtime(),
                        )?.let { changed ->
                            if (changed) applyAdaptiveLivePolicy()
                        }
                    }
                }
            } finally {
                if (adaptiveLiveSampleJob === currentCoroutineContext()[Job]) {
                    adaptiveLiveSampleJob = null
                }
            }
        }
    }

    private fun prepareLiveClipCommand(): ListenableFuture<SessionResult> {
        val future = SettableFuture.create<SessionResult>()
        lifecycleScope.launch {
            try {
                val prepared = prepareLiveClip().await()
                future.set(SessionResult(SessionResult.RESULT_SUCCESS, prepared.toBundle()))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
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
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
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

    private fun startLiveRewind(player: ExoPlayer, extras: Bundle, retainStartupRollback: Boolean = false): ListenableFuture<SessionResult> {
        systemReplayRollback = null
        val uri = extras.getString(URI)?.toUri()
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val vodId = extras.getString(REWIND_VIDEO_ID)
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
            mediaSession?.player ?: player,
            restoreVideo = true,
            reason = "start_live_rewind",
        )
        setLiveRewindSessionState(transitioning = true)
        return try {
            val activePlayer = setPlaybackSource(player, createVodMediaSource(uri))
            if (retainStartupRollback && previousPlayback != null) {
                systemReplayRollback = player.currentMediaItem?.mediaId to previousPlayback
            }
            activePlayer.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
            activePlayer.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
            activePlayer.prepare()
            activePlayer.playWhenReady = extras.getBoolean(PLAY_WHEN_READY, true)
            activePlayer.seekTo(extras.getLong(PLAYBACK_POSITION))
            clearLiveClipState()
            setLiveRewindSessionState(active = true, vodId = vodId)
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            systemReplayRollback = null
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

    private fun isLocalLive(): Boolean = !isCasting() &&
        viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE &&
        playbackPlayer?.currentMediaItem != null

    private fun isLogicalAudioOnly(): Boolean =
        decodePlaybackQuality(xtraModule.json, resumptionState?.quality)?.name == PlaybackContract.AUDIO_ONLY_QUALITY

    private fun canToggleSystemAudioMode(): Boolean {
        val item = playbackPlayer?.currentMediaItem ?: return false
        return isLocalLive() && !liveRewindActive && !liveRewindTransitioning &&
            prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true) &&
            !vaftSourceSwitching && vaftHandoffJob?.isActive != true &&
            vaftCoordinatorJob?.isActive != true && !vaftAlternateActive &&
            !vaftOutputSuppressed && vaftEntryFrameOwner == null &&
            vaftEntryFrameVisibleId == null && vaftHandoffFrameCaptureId == null &&
            vaftHandoffTargetMediaId == null &&
            !item.mediaId.startsWith(VAFT_SOURCE_MEDIA_ID_PREFIX) &&
            xtraModule.streamMedia3Runtime.controlledPlaylistFor(item.mediaId) == null
    }

    private fun systemBehindLiveSeconds(): Long? {
        if (!liveRewindActive) return null
        val player = mediaSession?.player ?: return null
        val duration = player.duration.takeIf { it != Media3C.TIME_UNSET && it > 0 } ?: return null
        // Keep the glanceable label stable while the growing recording advances.
        return ((duration - player.currentPosition).coerceAtLeast(0L) / 10_000L) * 10L
    }

    private fun setSystemAudioOnly(audioOnly: Boolean): ListenableFuture<SessionResult> {
        if (!canToggleSystemAudioMode() || systemReplayJob?.isActive == true) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        val player = playbackPlayer ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
        val state = resumptionState ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
        if (audioOnly == isLogicalAudioOnly()) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        val currentItem = player.currentMediaItem
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
        val currentUri = currentItem.localConfiguration?.uri?.toString()
        val primaryUri = liveStreamExtras?.getString(URI)?.takeIf { it.isNotBlank() }
        val savedQuality = if (audioOnly) {
            null
        } else {
            decodePlaybackQuality(xtraModule.json, state.previousQuality)
                ?.takeUnless { it.name == PlaybackContract.AUDIO_ONLY_QUALITY || it.name == PlaybackContract.CHAT_ONLY_QUALITY }
                ?: VideoQuality(name = PlaybackContract.AUTO_QUALITY)
        }
        val qualities = decodePlaybackQualities(xtraModule.json, state.qualities)
        val audioQuality = qualities?.firstOrNull {
            it.name.equals(PlaybackContract.AUDIO_ONLY_QUALITY, ignoreCase = true)
        } ?: decodePlaybackQuality(xtraModule.json, state.quality)
            ?.takeIf { it.name.equals(PlaybackContract.AUDIO_ONLY_QUALITY, ignoreCase = true) }
        val quality = if (audioOnly) {
            audioQuality ?: VideoQuality(name = PlaybackContract.AUDIO_ONLY_QUALITY)
        } else {
            savedQuality ?: VideoQuality(name = PlaybackContract.AUTO_QUALITY)
        }
        val sourceUri = when {
            audioOnly -> audioQuality?.url?.takeIf { it.isNotBlank() }
            quality.name.equals(PlaybackContract.AUTO_QUALITY, ignoreCase = true) -> primaryUri ?: currentUri
            else -> quality.url?.takeIf { it.isNotBlank() } ?: primaryUri ?: currentUri
        }
        val replaceSource = !sourceUri.isNullOrBlank() && sourceUri != currentUri
        val targetPlaylistUrl = if (replaceSource) sourceUri else currentUri
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        if (BuildConfig.DEBUG) logSystemAudioModeState("before", player)
        val qualityJson = xtraModule.json.encodeToString(quality)
        // Match the player UI's proxy and Audio Only track policy before changing the source.
        clearBackgroundVideoSuppression(player, restoreVideo = false, reason = "system_audio_mode")
        if (audioOnly) {
            clearVaftEntryFrameBridge()
            if (proxyMediaPlaylist) {
                proxyMediaPlaylist = false
                xtraModule.streamMedia3Runtime.setProxyMediaPlaylist(currentItem.mediaId, false)
                advanceLiveClipGeneration()
            }
        }
        vaftLogicalQuality = quality
        liveStreamExtras?.putString(PLAYBACK_QUALITY, qualityJson)
        val desired = resumptionHlsQuality(quality)
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(desired.name, desired.bitrate, desired.codecs)
        val parameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, audioOnly)
        if (!audioOnly) {
            if (!replaceSource) {
                videoQualityTrackOverride(player.currentTracks, quality)?.let(parameters::addOverride)
            }
        }
        // Publish logical state before track events so every consumer sees the same mode.
        saveResumptionState(state.copy(
            quality = qualityJson,
            previousQuality = if (audioOnly) state.quality else state.previousQuality,
            restoreQuality = audioOnly,
            playlistUrl = targetPlaylistUrl ?: state.playlistUrl,
        ))
        mediaSession?.broadcastCustomCommand(SessionCommand(SYSTEM_QUALITY_CHANGED, Bundle.EMPTY), Bundle().apply {
            putString(PLAYBACK_QUALITY, qualityJson)
            putString(PLAYBACK_PREVIOUS_QUALITY, resumptionState?.previousQuality)
            putBoolean(PLAYBACK_RESTORE_QUALITY, audioOnly)
            putString(SYSTEM_AUDIO_PRIMARY_SOURCE_URI, primaryUri ?: currentUri)
        })
        pendingSystemAudioModeDiagnostic = audioOnly
        player.trackSelectionParameters = parameters.build()
        if (replaceSource) {
            val targetSourceUri = checkNotNull(sourceUri)
            val runtime = xtraModule.streamMedia3Runtime
            val sourceInstance = runtime.newSourceInstanceMediaItem(currentItem, targetSourceUri)
            runtime.setProxyMediaPlaylist(sourceInstance.mediaId, false)
            runtime.setPrimaryPlaybackMediaItem(sourceInstance)
            player.setMediaSource(runtime.createLiveMediaSource(sourceInstance))
            player.prepare()
            player.seekTo(currentPosition)
            player.playWhenReady = playWhenReady
        }
        if (BuildConfig.DEBUG) logSystemAudioModeState("applied", player)
        refreshSystemMediaMetadata()
        mediaSession?.player?.let(::refreshMediaButtonPreferences)
        val result = SettableFuture.create<SessionResult>()
        lifecycleScope.launch {
            try {
                xtraModule.playbackPersistence.flush()
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (cancelled: CancellationException) {
                result.cancel(false)
                throw cancelled
            } catch (error: Exception) {
                Log.w(RESUMPTION_TAG, "Failed to persist audio mode", error)
                result.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return result
    }

    private fun logSystemAudioModeState(reason: String, player: Player) {
        if (!BuildConfig.DEBUG) return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()?.toUri()
        val source = uri?.let {
            val path = it.encodedPath.orEmpty()
            val safePath = if (it.host?.endsWith(".playlist.ttvnw.net", ignoreCase = true) == true &&
                path.startsWith("/v1/playlist/")) {
                "/v1/playlist/<signed-token>.m3u8"
            } else {
                path
            }
            buildString {
                append(it.scheme ?: "")
                append("://")
                append(it.authority ?: "")
                append(safePath)
                if (it.encodedQuery != null) append("?<redacted>")
            }
        } ?: "none"
        val tracks = player.currentTracks.groups.mapNotNull { group ->
            val selected = (0 until group.length).filter(group::isTrackSelected)
            if (selected.isEmpty()) return@mapNotNull null
            val type = when (group.type) {
                Media3C.TRACK_TYPE_AUDIO -> "audio"
                Media3C.TRACK_TYPE_VIDEO -> "video"
                else -> "type${group.type}"
            }
            val formats = selected.joinToString(",") { index ->
                val format = group.getTrackFormat(index)
                format.label ?: format.sampleMimeType ?: format.id ?: "unknown"
            }
            "$type=$formats"
        }.ifEmpty { listOf("none") }.joinToString(";")
        val quality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
        Log.d(
            "SystemAudioMode",
            "event=$reason source=$source logicalQuality=${quality?.name ?: "none"} " +
                "bitrate=${quality?.bitrate ?: 0} videoDisabled=${Media3C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes} " +
                "selectedTracks=$tracks",
        )
    }

    private fun replayFromSystemMedia(): ListenableFuture<SessionResult> {
        if (!isLocalLive() || liveRewindActive || liveRewindTransitioning ||
            systemReplayJob?.isActive == true || !prefs().getBoolean(C.PLAYER_LIVE_REWIND, true) ||
            !prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true) ||
            !prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true)
        ) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        val generation = livePlaybackSessionGeneration
        val channelId = viewingChannelId
        val channelLogin = viewingChannelLogin
        val streamId = viewingContentId
        val sourceId = playbackPlayer?.currentMediaItem?.mediaId
        val result = SettableFuture.create<SessionResult>()
        systemReplayJob = lifecycleScope.launch {
            try {
                val resolved = withTimeoutOrNull(15_000L) {
                    val response = xtraModule.graphQLRepository.loadQueryUsersStream(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = TwitchApiHelper.getGQLHeaders(this@PlaybackService),
                        ids = channelId?.takeIf { it.isNotBlank() }?.let(::listOf),
                        logins = if (channelId.isNullOrBlank()) channelLogin?.let(::listOf) else null,
                    )
                    if (!response.errors.isNullOrEmpty()) return@withTimeoutOrNull null
                    val stream = response.data?.users?.firstOrNull()?.stream ?: return@withTimeoutOrNull null
                    if (streamId != null && stream.id != streamId) return@withTimeoutOrNull null
                    val vod = xtraModule.graphQLRepository.findCurrentRecordingVod(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = TwitchApiHelper.getGQLHeaders(this@PlaybackService),
                        channelId = channelId,
                        channelLogin = channelLogin,
                        streamCreatedAt = stream.createdAt?.toString(),
                    ) ?: return@withTimeoutOrNull null
                    // The existing Usher path handles authorization and rendition selection.
                    val url = xtraModule.playerRepository.loadVideoPlaylistUrl(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@PlaybackService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = vod.id,
                        playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    ).first
                    vod to url
                }
                if (resolved == null || !isLocalLive() || liveRewindActive || liveRewindTransitioning ||
                    generation != livePlaybackSessionGeneration || channelLogin != viewingChannelLogin ||
                    streamId != viewingContentId || sourceId != playbackPlayer?.currentMediaItem?.mediaId
                ) {
                    result.set(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    return@launch
                }
                val (vod, url) = resolved
                val started = startLiveRewind(requireNotNull(playbackPlayer), Bundle().apply {
                    putString(URI, url)
                    putString(REWIND_VIDEO_ID, vod.id)
                    putLong(PLAYBACK_POSITION, (freezeLiveEdge(vod.predictedDurationMs(), null) - 30_000L).coerceAtLeast(0L))
                    putBoolean(PLAY_WHEN_READY, mediaSession?.player?.playWhenReady == true)
                }, retainStartupRollback = true)
                result.set(started.get())
            } catch (cancelled: CancellationException) {
                result.cancel(false)
                throw cancelled
            } catch (error: Exception) {
                Log.w("LiveRewind", "System replay could not resolve the recording", error)
                result.set(SessionResult(SessionError.ERROR_UNKNOWN))
            } finally {
                systemReplayJob = null
                mediaSession?.player?.let(::refreshMediaButtonPreferences)
            }
        }
        mediaSession?.player?.let(::refreshMediaButtonPreferences)
        return result
    }

    private fun configuredPlaybackQuality(extras: Bundle): VideoQuality? =
        decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))

    private fun matchesVerifiedRendition(format: Format, quality: VideoQuality?): Boolean {
        val verifiedQuality = quality ?: return false
        val name = verifiedQuality.name?.takeIf { it.isNotBlank() } ?: return false
        return DesiredHlsQuality(name, verifiedQuality.bitrate, verifiedQuality.codecs).matches(format)
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
        val configuredQuality = configuredPlaybackQuality(extras)
        val candidateQuality = decodePlaybackQuality(xtraModule.json, extras.getString(VAFT_VERIFIED_RENDITION))
        val desiredQuality = if (configuredQuality?.name?.equals(PlaybackContract.AUTO_QUALITY, ignoreCase = true) == true) {
            configuredQuality
        } else {
            candidateQuality ?: configuredQuality
        }
        val desired = resumptionHlsQuality(desiredQuality)
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(desired.name, desired.bitrate, desired.codecs)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            .build()
        diagnostics.resetRenderedVideoSize()
        player.volume = 0f
        val runtime = xtraModule.streamMedia3Runtime
        val source = preloadedSource?.mediaSource ?: runtime.createLiveMediaSource(item)
        runtime.setVaftEvidenceAlternateSource(item, extras.getBoolean(VAFT_ALTERNATE_ACTIVE))
        player.setMediaSource(source)
        // Keep the normal live resumption source authoritative until commit.
        player.prepare()
        player.playWhenReady = playWhenReady
        return item.mediaId
    }

    private fun restorePrimaryQualityIntent(
        player: ExoPlayer,
        primaryExtras: Bundle,
        fallback: DesiredHlsQuality,
    ) {
        val configuredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, primaryExtras.getString(PLAYBACK_QUALITY))
        val desired = configuredQuality?.let(::resumptionHlsQuality) ?: fallback
        val videoDisabledByQuality = configuredQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY ||
            configuredQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
            desired.name,
            desired.bitrate,
            desired.codecs,
        )
        val parameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
        if (configuredQuality != null) {
            parameters.setTrackTypeDisabled(
                Media3C.TRACK_TYPE_VIDEO,
                backgroundVideoSuppressed || videoDisabledByQuality,
            )
        }
        player.trackSelectionParameters = parameters.build()
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "primary_quality_restored name=${desired.name}")
        }
    }

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
        if (vaftEntryFrameVisibleId != null) {
            if (BuildConfig.DEBUG) {
                Log.d("XtraVaft", "return_frame_capture skipped entry=${vaftEntryFrameVisibleId?.takeLast(8)}")
            }
            return true
        }
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

    private fun selectedControlledFormat(player: Player): Format? {
        val groups = player.currentTracks.groups
        val group = groups.firstOrNull { it.type == Media3C.TRACK_TYPE_VIDEO && it.isSelected }
            ?: groups.firstOrNull { it.type == Media3C.TRACK_TYPE_AUDIO && it.isSelected }
        val selected = group?.let { (0 until it.length).filter(it::isTrackSelected) }.orEmpty()
        if (selected.size == 1) return group?.getTrackFormat(selected.single())
        // Adaptive route ownership comes from downstream chunk metadata, not replacement decoder dimensions.
        return null
    }

    private fun vaftQualityStatus(player: ExoPlayer): Bundle? {
        if (liveRewindActive || liveRewindTransitioning || isCasting()) return null
        val controlled = xtraModule.streamMedia3Runtime.controlledPlaylistFor(player.currentMediaItem?.mediaId)
        val window = if (!player.currentTimeline.isEmpty) {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        } else null
        val epochUs = window?.windowStartTimeMs?.takeIf { it != Media3C.TIME_UNSET }
            ?.let { it * 1_000L + player.currentPosition * 1_000L }
        val active = if (controlled != null) epochUs?.let(controlled::isAlternateAt) == true
            else vaftAlternateActive || vaftSourceSwitching || vaftOutputSuppressed || vaftCoordinatorJob?.isActive == true
        if (!active) return null
        val type = if (controlled != null) epochUs?.let(controlled::playerTypeAt) else vaftCurrentPlayerType
        val remainingMs = if (controlled != null) epochUs?.let(controlled::adWindowRemainingMsAt) else {
            val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
            trackedVaftBoundary?.let { tracked -> playlist?.let { vaftBoundaryRemainingMs(player, it, tracked) } }
        }
        return Bundle().apply {
            putString(VAFT_PLAYER_TYPE, type)
            remainingMs?.let { putLong(VAFT_AD_REMAINING_MS, it) }
            player.currentLiveOffset.takeIf { it != Media3C.TIME_UNSET && it >= 0L }
                ?.let { putLong(VAFT_LIVE_DELAY_MS, it) }
        }
    }

    private fun updateVaft(player: ExoPlayer) {
        if (isCasting()) return
        val controlled = xtraModule.streamMedia3Runtime.controlledPlaylistFor(player.currentMediaItem?.mediaId)
        if (controlled != null && !liveRewindActive && !liveRewindTransitioning) {
            if (!player.currentTimeline.isEmpty) {
                val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                controlled.selectFormat(selectedControlledFormat(player))
                val windowStartUs = window.windowStartTimeMs * 1_000L
                val durationMs = window.durationMs.takeIf { it != Media3C.TIME_UNSET } ?: 0L
                val windowEndUs = windowStartUs + durationMs * 1_000L
                val targetOffsetMs = adaptiveLiveController?.currentPolicy()?.targetOffsetMs
                    ?: LivePlaybackPolicies.forLowLatency(prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY)).targetOffsetMs
                // Media3's default position can retain the delay learned during a stall.
                // Use the verified playlist tail and the active buffer policy for live priority.
                val livePositionMs = (durationMs - targetOffsetMs).coerceAtLeast(0L)
                if (player.playWhenReady) controlled.consumeRecovery(windowStartUs, windowEndUs)?.let { recovery ->
                    if (recovery.prioritizeLive) {
                        player.seekTo(maxOf(livePositionMs, (recovery.epochUs - windowStartUs) / 1_000L))
                    } else {
                        player.seekToDefaultPosition()
                    }
                }
                if (player.isPlaying && prefs().isVaftEnabled() &&
                    prefs().getString(C.PLAYER_VAFT_PLAYBACK_PRIORITY, C.VAFT_PRIORITY_CONTINUITY) == C.VAFT_PRIORITY_LIVE &&
                    livePositionMs - player.currentPosition > 6_000L) {
                    if (BuildConfig.DEBUG) Log.d("XtraVaftFeed",
                        "event=live_priority_catch_up skippedMs=${livePositionMs - player.currentPosition}")
                    player.seekTo(livePositionMs)
                }
                val epochUs = window.windowStartTimeMs * 1_000L + player.currentPosition * 1_000L
                val alternate = controlled.isAlternateAt(epochUs)
                val catalogKey = "${player.currentMediaItem?.mediaId}:${controlled.availableFormatsAt(epochUs)}:${controlled.formatAt(epochUs)}"
                if (alternate != vaftAlternateActive || catalogKey != controlledQualityCatalogKey) {
                    controlledQualityCatalogKey = catalogKey
                    vaftAlternateActive = alternate
                    vaftAuthoritativeUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    publishVaftPlaybackState()
                }
                val next = controlled.nextChangeAfter(epochUs)
                val key = next?.let { "${player.currentMediaItem?.mediaId}:$it" }
                if (key != controlledVaftBadgeKey) {
                    controlledVaftBadgeMessage?.cancel()
                    controlledVaftBadgeKey = key
                    controlledVaftBadgeMessage = next?.let { boundary ->
                        player.createMessage { _, _ ->
                            controlledVaftBadgeKey = null
                            controlledVaftBadgeMessage = null
                            updateVaft(player)
                        }.setLooper(android.os.Looper.getMainLooper())
                            .setPosition(player.currentMediaItemIndex, (boundary - window.windowStartTimeMs * 1_000L) / 1_000L)
                            .send()
                    }
                }
            }
            return
        }
        controlledVaftBadgeMessage?.cancel()
        controlledVaftBadgeMessage = null
        controlledVaftBadgeKey = null
        controlledQualityCatalogKey = null
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
                vaftEntryFrameAttemptedBoundaryKey = null
                discardVaftPreparation()
                if (vaftEntryFrameOwner != null || vaftEntryFrameVisibleId != null) {
                    clearVaftEntryFrameBridge()
                    publishVaftPlaybackState()
                }
            }
            return
        }
        if (!isVaftEntryVideoOutputExpected() &&
            (vaftEntryFrameOwner != null || vaftEntryFrameVisibleId != null)
        ) {
            clearVaftEntryFrameBridge()
            publishVaftPlaybackState()
        }
        if (currentUri != liveStreamUri && currentUri != vaftAuthoritativeUri && !vaftSourceSwitching) {
            vaftBoundaryWatchJob?.cancel()
            vaftBoundaryWatchJob = null
            vaftBoundaryWatchMarkerKey = null
            trackedVaftBoundary = null
            discardVaftPreparation()
            if (vaftEntryFrameVisibleId == null && vaftEntryFrameOwner != null) {
                clearVaftEntryFrameBridge()
                publishVaftPlaybackState()
            }
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
            if (vaftCoordinatorJob?.isActive != true && !vaftSourceSwitching && !vaftAlternateActive &&
                vaftEntryFrameVisibleId == null && vaftEntryFrameOwner != null
            ) {
                clearVaftEntryFrameBridge()
                publishVaftPlaybackState()
            }
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
            vaftPreparedCandidate?.let { prepared ->
                maybeWarmVaftCandidate(player, activePlaylist, prepared)
            }
        } else if (trackedBoundary != null && boundaryPhase in setOf(
                VaftPlaybackBoundaryPhase.BEFORE,
                VaftPlaybackBoundaryPhase.ACTIVE,
            )
        ) {
            ensureVaftBoundaryWatcher(player, trackedBoundary.observation.markerKey)
        }
        if (currentUri == liveStreamUri && boundaryPhase == VaftPlaybackBoundaryPhase.BEFORE &&
            trackedBoundary != null && !vaftSourceSwitching && vaftCoordinatorJob?.isActive != true
        ) {
            maybeRequestVaftEntryFrameCapture(player, activePlaylist, trackedBoundary)
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
                if (suppress && !vaftAlternateActive) activateVaftEntryFrameBridge(trackedBoundary)
                if (suppress) cancelVaftEntryFrameRelease()
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
            authorizeVaftEntryFrameRelease(player)
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
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val preparationMatches = triggerMarkerKey != null && vaftPreparationMarkerKey == triggerMarkerKey &&
            vaftPreparationGeneration == vaftGeneration && vaftPreparationMediaId == currentMediaId &&
            vaftPreparationRequestId != null
        val generationBeforeStart = vaftGeneration
        val generation = if (preparationMatches) vaftGeneration else ++vaftGeneration
        val preparedCandidateAtTrigger = vaftPreparedCandidate?.takeIf { prepared ->
            prepared.requestId == vaftPreparationRequestId && prepared.markerKey == triggerMarkerKey &&
                prepared.vaftGeneration == vaftGeneration && prepared.sourceGeneration == vaftSourceGeneration &&
                prepared.playbackMediaId == currentMediaId && isVaftCandidateCurrent(player, prepared)
        }
        val preparationJobActive = vaftPreparationJob?.isActive == true
        val preparationState = when {
            preparedCandidateAtTrigger != null -> "candidate_ready"
            preparationMatches && preparationJobActive -> "inflight"
            preparationMatches -> "empty"
            else -> "none"
        }
        val preparationAgeMs = if (preparationMatches) {
            vaftPreparationStartedAtMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) } ?: -1L
        } else {
            -1L
        }
        val preparationWarmup = preparedCandidateAtTrigger?.warmup
        val preparationWarmupReady = preparationWarmup?.completion?.takeIf { it.isDone }
            ?.let { runCatching { it.get() }.getOrDefault(false) } == true
        val primaryFrameAgeMs = if (vaftPrimaryFirstFrameMediaId == currentMediaId &&
            vaftPrimaryFirstFrameSourceUri == currentUri && currentUri == liveStreamUri
        ) {
            (nowElapsedMs - (vaftPrimaryFirstFrameElapsedMs ?: nowElapsedMs)).coerceAtLeast(0L)
        } else {
            -1L
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "XtraVaft",
                "event=vaft_trigger activationToken=${diagnosticToken("$generation:$vaftSourceGeneration:${triggerMarkerKey.orEmpty()}")} " +
                    "markerToken=${diagnosticToken(triggerMarkerKey)} itemToken=${diagnosticToken(currentMediaId)} " +
                    "sourceToken=${diagnosticToken(currentUri)} phase=${boundaryPhase.name.lowercase()} " +
                    "basis=${trackedBoundary?.observation?.basis ?: "untracked"} publisherEdge=$publisherEdgeRequiresVaft " +
                    "vaftGeneration=$generation sourceGeneration=$vaftSourceGeneration " +
                    "prepState=$preparationState prepRequestToken=${diagnosticToken(vaftPreparationRequestId.takeIf { preparationMatches })} " +
                    "prepAgeMs=$preparationAgeMs prepWarmupStarted=${preparationWarmup != null} " +
                    "prepWarmupReady=$preparationWarmupReady primaryFrameAgeMs=$primaryFrameAgeMs " +
                    "elapsedRealtimeMs=$nowElapsedMs",
            )
        }
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
        promoteVaftEntryFrameOwner(trackedBoundary, player, generationBeforeStart, generation)
        backgroundRecoveryTimer?.cancel()
        backgroundRecoveryTimer = null
        activateVaftEntryFrameBridge(trackedBoundary)
        cancelVaftEntryFrameRelease()
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
                val currentPlayerUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                val clean = player.playerError == null &&
                    currentPlayerUri == vaftAuthoritativeUri &&
                    currentPlaylist != null && if (vaftAlternateActive) {
                        !TwitchVaftDetector.requiresVaft(currentPlaylist)
                    } else {
                        !primaryUnsafe
                    }
                val cleanReason = when {
                    player.playerError != null -> "player_error_${player.playerError?.errorCode ?: -1}"
                    currentPlayerUri != vaftAuthoritativeUri -> "source_mismatch"
                    currentPlaylist == null -> "playlist_unavailable"
                    vaftAlternateActive && publisherRequiresNow -> "alternate_marker"
                    !vaftAlternateActive && primaryUnsafe -> "primary_boundary"
                    else -> "clean"
                }
                if (!vaftAlternateActive && clean) {
                    vaftOutputSuppressed = false
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    authorizeVaftEntryFrameRelease(player)
                    break
                }
                if (vaftOutputSuppressed != !clean) {
                    if (!clean) cancelVaftEntryFrameRelease()
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
                        "reason=$cleanReason eligible=${eligible.joinToString() }"
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
                val candidateRequestId = preparedCandidate?.requestId ?: java.util.UUID.randomUUID().toString()
                val candidateRuntime = xtraModule.streamMedia3Runtime
                val candidateConfiguration = candidateRuntime.configurationFingerprintFor(player)
                val candidateQualityRevision = candidateRuntime.qualitySelectionPolicy.revision()
                val candidateItem = player.currentMediaItem
                val candidateLeadMs = if (currentPlaylist != null && trackedNow != null) {
                    vaftBoundaryLeadMs(player, currentPlaylist, trackedNow) ?: -1L
                } else {
                    -1L
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
                    requestId = candidateRequestId,
                    origin = if (vaftAlternateActive) "recovery" else "active_direct",
                    markerKey = triggerMarkerKey,
                    mediaId = candidateItem?.mediaId,
                    sourceUri = candidateItem?.localConfiguration?.uri?.toString(),
                    configurationFingerprint = candidateConfiguration,
                    qualityRevision = candidateQualityRevision,
                    vaftGeneration = generation,
                    sourceGeneration = vaftSourceGeneration,
                    leadMs = candidateLeadMs,
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
                    authorizeVaftEntryFrameRelease(player)
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
                    val preparedSource = preparedCandidate?.let { prepared ->
                        awaitPreparedVaftSource(
                            player,
                            prepared,
                            candidate.url,
                            generation,
                            timeoutMs = (vaftPreparationGraceDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                        )
                    } ?: VaftPreparedSourceResolution()
                    if (preparedSource.rejectionReason != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "XtraVaft",
                                "handoff candidate_rejected reason=${preparedSource.rejectionReason} playerType=${candidate.playerType}",
                            )
                        }
                        types.onHandoffFailed(candidate.playerType)
                        discardVaftPreparation()
                        tryNextPlayerTypeImmediately = true
                    } else {
                        val future = startVaftHandoff(player, extras, preparedSource.source)
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

    private fun isVaftEntryVideoOutputExpected(): Boolean {
        val quality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: liveStreamExtras?.let { decodePlaybackQuality(xtraModule.json, it.getString(PLAYBACK_QUALITY)) }
        return quality?.name != PlaybackContract.AUDIO_ONLY_QUALITY &&
            quality?.name != PlaybackContract.CHAT_ONLY_QUALITY
    }

    private fun isPrimaryHealthyForVaftPreparation(player: ExoPlayer): Boolean =
        player.playWhenReady && player.isPlaying && player.playbackState == Player.STATE_READY &&
            player.playerError == null

    private fun maybeRequestVaftEntryFrameCapture(
        player: ExoPlayer,
        playlist: HlsMediaPlaylist,
        tracked: TrackedVaftBoundary,
    ) {
        val leadMs = vaftBoundaryLeadMs(player, playlist, tracked) ?: return
        if (leadMs !in 1L..VAFT_ENTRY_FRAME_CAPTURE_LEAD_MS ||
            !isPrimaryHealthyForVaftPreparation(player) || !isVaftEntryVideoOutputExpected() ||
            player.videoSize.width <= 0 || player.videoSize.height <= 0
        ) return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        if (uri != liveStreamUri || tracked.primaryUri != uri || tracked.primaryMediaId != item.mediaId ||
            tracked.sourceGeneration != vaftSourceGeneration || trackedVaftBoundary?.observation?.markerKey != tracked.observation.markerKey ||
            vaftSourceSwitching || vaftAlternateActive || vaftCoordinatorJob?.isActive == true ||
            vaftHandoffJob?.isActive == true || liveRewindActive || liveRewindTransitioning || !prefs().isVaftEnabled()
        ) return
        if (vaftEntryFrameVisibleId != null) return

        val runtime = xtraModule.streamMedia3Runtime
        val qualityIntentRevision = runtime.qualitySelectionPolicy.revision()
        val attemptedBoundaryKey = "${vaftGeneration}:${vaftSourceGeneration}:${tracked.observation.markerKey}"
        val existing = vaftEntryFrameOwner
        if (existing != null) {
            if (existing.vaftGeneration == vaftGeneration && existing.sourceGeneration == vaftSourceGeneration &&
                existing.markerKey == tracked.observation.markerKey && existing.primaryMediaId == item.mediaId &&
                existing.primaryUri == uri && existing.qualityIntentRevision == qualityIntentRevision
            ) return
            clearVaftEntryFrameBridge()
            publishVaftPlaybackState()
        }
        if (vaftEntryFrameAttemptedBoundaryKey == attemptedBoundaryKey) return
        val owner = VaftEntryFrameOwner(
            requestId = "$vaftGeneration:$vaftSourceGeneration:${java.util.UUID.randomUUID()}",
            vaftGeneration = vaftGeneration,
            sourceGeneration = vaftSourceGeneration,
            markerKey = tracked.observation.markerKey,
            primaryMediaId = item.mediaId,
            primaryUri = uri,
            qualityIntentRevision = qualityIntentRevision,
            requestedAtMs = SystemClock.elapsedRealtime(),
        )
        vaftEntryFrameOwner = owner
        vaftEntryFrameAttemptedBoundaryKey = attemptedBoundaryKey
        vaftEntryFrameCaptureArmed = true
        vaftEntryFrameAcceptedId = null
        vaftEntryFrameResolvedId = null
        vaftEntryFrameCaptureAccepted = false
        vaftEntryFrameReleaseMediaId = null
        vaftEntryFrameReleaseGeneration = -1L
        vaftEntryFrameReleaseAuthorized = false
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "entry_frame_capture_start marker=${owner.markerKey.take(8)} leadMs=$leadMs")
        }
        publishVaftPlaybackState()
        lifecycleScope.launch {
            delay(VAFT_ENTRY_FRAME_CAPTURE_ACK_TIMEOUT_MS)
            if (vaftEntryFrameOwner == owner && vaftEntryFrameCaptureArmed) {
                vaftEntryFrameResolvedId = owner.requestId
                vaftEntryFrameCaptureAccepted = false
                vaftEntryFrameOwner = null
                vaftEntryFrameCaptureArmed = false
                if (BuildConfig.DEBUG) Log.d("XtraVaft", "entry_frame_capture_expired request=${owner.requestId.takeLast(8)}")
                publishVaftPlaybackState()
            }
        }
    }

    private fun isVaftEntryFrameOwnerCurrent(player: ExoPlayer, owner: VaftEntryFrameOwner): Boolean {
        val item = player.currentMediaItem ?: return false
        val uri = item.localConfiguration?.uri?.toString() ?: return false
        val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return false
        val tracked = trackedVaftBoundary ?: return false
        val phase = vaftPlaybackBoundaryPhase(player, playlist, tracked)
        val publisherEdgeRequiresVaft = TwitchVaftDetector.requiresVaft(playlist)
        return owner.vaftGeneration == vaftGeneration && owner.sourceGeneration == vaftSourceGeneration &&
            owner.markerKey == tracked.observation.markerKey && tracked.sourceGeneration == vaftSourceGeneration &&
            owner.primaryMediaId == item.mediaId && tracked.primaryMediaId == item.mediaId &&
            owner.primaryUri == uri && uri == tracked.primaryUri && uri == liveStreamUri &&
            owner.qualityIntentRevision == xtraModule.streamMedia3Runtime.qualitySelectionPolicy.revision() &&
            phase == VaftPlaybackBoundaryPhase.BEFORE &&
            !isPlaybackBoundaryUnsafe(phase, publisherEdgeRequiresVaft, trackedBoundary = true) &&
            !vaftSourceSwitching && !vaftAlternateActive && vaftCoordinatorJob?.isActive != true &&
            vaftHandoffJob?.isActive != true && !liveRewindActive && !liveRewindTransitioning &&
            resumptionState?.type == PlaybackContract.STREAM && prefs().isVaftEnabled() &&
            isVaftEntryVideoOutputExpected() && isPrimaryHealthyForVaftPreparation(player) &&
            player.videoSize.width > 0 && player.videoSize.height > 0
    }

    private fun promoteVaftEntryFrameOwner(
        tracked: TrackedVaftBoundary?,
        player: ExoPlayer,
        generationBeforeStart: Long,
        generation: Long,
    ) {
        val owner = vaftEntryFrameOwner ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val currentRevision = xtraModule.streamMedia3Runtime.qualitySelectionPolicy.revision()
        val belongsToBoundary = tracked != null && owner.vaftGeneration == generationBeforeStart &&
            owner.sourceGeneration == vaftSourceGeneration && tracked.sourceGeneration == vaftSourceGeneration &&
            owner.markerKey == tracked.observation.markerKey && owner.markerKey == trackedVaftBoundary?.observation?.markerKey &&
            owner.primaryMediaId == tracked.primaryMediaId && owner.primaryMediaId == item.mediaId &&
            owner.primaryUri == tracked.primaryUri && owner.primaryUri == uri && uri == liveStreamUri &&
            owner.qualityIntentRevision == currentRevision
        if (!belongsToBoundary) return
        vaftEntryFrameOwner = owner.copy(vaftGeneration = generation)
        if (BuildConfig.DEBUG && generation != generationBeforeStart) {
            Log.d("XtraVaft", "entry_frame_promoted request=${owner.requestId.takeLast(8)} generation=$generation")
        }
    }

    private fun activateVaftEntryFrameBridge(tracked: TrackedVaftBoundary?) {
        val owner = vaftEntryFrameOwner
        val matches = owner != null && tracked != null &&
            owner.vaftGeneration == vaftGeneration && owner.sourceGeneration == vaftSourceGeneration &&
            owner.markerKey == tracked.observation.markerKey && owner.sourceGeneration == tracked.sourceGeneration &&
            owner.primaryMediaId == tracked.primaryMediaId && owner.primaryUri == tracked.primaryUri &&
            owner.requestId == vaftEntryFrameAcceptedId && vaftEntryFrameCaptureAccepted &&
            owner.qualityIntentRevision == xtraModule.streamMedia3Runtime.qualitySelectionPolicy.revision()
        if (matches) {
            vaftEntryFrameVisibleId = owner.requestId
            vaftEntryFrameCaptureArmed = false
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "entry_frame_show request=${owner.requestId.takeLast(8)}")
        } else if (owner != null) {
            vaftEntryFrameResolvedId = owner.requestId
            vaftEntryFrameCaptureAccepted = false
            vaftEntryFrameAcceptedId = null
            vaftEntryFrameOwner = null
            vaftEntryFrameCaptureArmed = false
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "entry_frame_capture_rejected request=${owner.requestId.takeLast(8)} reason=boundary_started")
        }
    }

    private fun authorizeVaftEntryFrameRelease(player: ExoPlayer) {
        val visibleId = vaftEntryFrameVisibleId ?: return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val targetAlreadyRendered = vaftHandoffTargetFrameRendered &&
            vaftHandoffTargetGeneration == vaftGeneration &&
            vaftHandoffTargetMediaId == mediaId &&
            !vaftOutputSuppressed
        if (targetAlreadyRendered) {
            if (BuildConfig.DEBUG) {
                Log.d("XtraVaft", "entry_frame_release target_already_rendered request=${visibleId.takeLast(8)}")
            }
            clearVaftEntryFrameBridge()
            publishVaftPlaybackState()
            return
        }
        if (vaftEntryFrameReleaseAuthorized && vaftEntryFrameReleaseGeneration == vaftGeneration &&
            vaftEntryFrameReleaseMediaId == mediaId
        ) return
        vaftEntryFrameReleaseRevision++
        val releaseRevision = vaftEntryFrameReleaseRevision
        vaftEntryFrameReleaseMediaId = mediaId
        vaftEntryFrameReleaseGeneration = vaftGeneration
        vaftEntryFrameReleaseAuthorized = true
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "entry_frame_release_authorized request=${visibleId.takeLast(8)} media=${diagnosticToken(mediaId)}")
        }
        lifecycleScope.launch {
            delay(VAFT_ENTRY_FRAME_RELEASE_WATCHDOG_MS)
            if (vaftEntryFrameReleaseRevision == releaseRevision &&
                vaftEntryFrameVisibleId == visibleId && vaftEntryFrameReleaseAuthorized &&
                vaftEntryFrameReleaseGeneration == vaftGeneration &&
                vaftEntryFrameReleaseMediaId == player.currentMediaItem?.mediaId && !vaftOutputSuppressed
            ) {
                if (BuildConfig.DEBUG) Log.d("XtraVaft", "entry_frame_release_watchdog request=${visibleId.takeLast(8)}")
                clearVaftEntryFrameBridge()
                publishVaftPlaybackState()
            }
        }
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
                "matchSignals=${boundary.matchSignals ?: "n/a"} " +
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
        val preferredQuality = preferredVaftRendition(
            configuredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
                ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY)),
            mediaId = mediaId,
            sourceUri = sourceUri,
        )
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
                    requestId = requestId,
                    origin = "before",
                    markerKey = boundary.markerKey,
                    mediaId = mediaId,
                    sourceUri = sourceUri,
                    configurationFingerprint = configurationFingerprint,
                    qualityRevision = qualityRevision,
                    vaftGeneration = generation,
                    sourceGeneration = vaftSourceGeneration,
                    leadMs = leadMs,
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
                        Log.d(
                            "XtraVaft",
                            "event=candidate_lookup_validated requestToken=${diagnosticToken(requestId)} result=accepted " +
                                "stillOwned=true markerToken=${diagnosticToken(boundary.markerKey)}",
                        )
                        Log.d("XtraVaft", "future_candidate_ready basis=${boundary.basis} leadMs=$leadMs playerType=${candidate.playerType}")
                    }
                    if (latestPlaylist != null) maybeWarmVaftCandidate(player, latestPlaylist, result)
                } else if (BuildConfig.DEBUG) {
                    Log.d(
                        "XtraVaft",
                        "event=candidate_lookup_validated requestToken=${diagnosticToken(requestId)} result=discarded " +
                            "stillOwned=$stillOwned verifiedClean=${candidate?.verifiedClean == true} " +
                            "markerToken=${diagnosticToken(boundary.markerKey)}",
                    )
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
        val preferredQuality = preferredVaftRendition(
            configuredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
                ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY)),
            mediaId = sourceMediaId,
            sourceUri = sourceUri,
        )
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
                    requestId = requestId,
                    origin = "refresh",
                    markerKey = tracked.observation.markerKey,
                    mediaId = sourceMediaId,
                    sourceUri = sourceUri,
                    configurationFingerprint = configurationFingerprint,
                    qualityRevision = qualityRevision,
                    vaftGeneration = prepared.vaftGeneration,
                    sourceGeneration = vaftSourceGeneration,
                    leadMs = leadMs,
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
                        Log.d(
                            "XtraVaft",
                            "event=candidate_lookup_validated requestToken=${diagnosticToken(requestId)} result=accepted " +
                                "stillOwned=true markerToken=${diagnosticToken(tracked.observation.markerKey)}",
                        )
                        Log.d("XtraVaft", "future_candidate_refresh_ready basis=${tracked.observation.basis} leadMs=$leadMs playerType=${candidate.playerType}")
                    }
                    if (latestPlaylist != null) maybeWarmVaftCandidate(player, latestPlaylist, replacement)
                } else if (BuildConfig.DEBUG) {
                    Log.d(
                        "XtraVaft",
                        "event=candidate_lookup_validated requestToken=${diagnosticToken(requestId)} result=discarded " +
                            "stillOwned=false verifiedClean=${candidate?.verifiedClean == true} " +
                            "markerToken=${diagnosticToken(tracked.observation.markerKey)}",
                    )
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
        if (!isPrimaryHealthyForVaftPreparation(player)) {
            prepared.warmup?.token?.let { token ->
                xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(token)
                if (vaftWarmupToken == token) vaftWarmupToken = null
                prepared.warmup = null
                prepared.nearTriggerWarmStarted = false
                if (BuildConfig.DEBUG) {
                    Log.d("XtraVaft", "future_candidate_warm_cancel reason=primary_unhealthy bufferedMs=${player.totalBufferedDuration}")
                }
            }
            return
        }
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
        val configuredQuality = decodePlaybackQuality(xtraModule.json, resumptionState?.quality)
            ?: decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))
        val allowAdaptiveRendition = prepared.qualityIntent.isAuto &&
            configuredQuality?.name != PlaybackContract.AUDIO_ONLY_QUALITY &&
            configuredQuality?.name != PlaybackContract.CHAT_ONLY_QUALITY
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
            allowAdaptiveRendition = allowAdaptiveRendition,
        ) ?: return
        prepared.warmup = handle
        prepared.nearTriggerWarmStarted = true
        vaftWarmupToken = handle.token
        if (BuildConfig.DEBUG) {
            Log.d("XtraVaft", "future_candidate_warm_start basis=${boundary.basis} playbackLeadMs=${playbackLeadMs ?: -1L}")
        }
    }

    private fun preferredVaftRendition(
        configuredQuality: VideoQuality?,
        mediaId: String?,
        sourceUri: String?,
    ): VideoQuality? {
        if (configuredQuality == null || configuredQuality.name.equals(PlaybackContract.AUTO_QUALITY, ignoreCase = true)) {
            return diagnostics.confirmedVideoQuality(mediaId, sourceUri) ?: configuredQuality
        }
        return configuredQuality
    }

    private suspend fun loadCleanVaftCandidate(
        login: String,
        playerTypes: List<String>,
        deviceId: String?,
        preferredQuality: VideoQuality?,
        timeoutMs: Long,
        requestId: String,
        origin: String,
        markerKey: String?,
        mediaId: String?,
        sourceUri: String?,
        configurationFingerprint: String?,
        qualityRevision: Long,
        vaftGeneration: Long,
        sourceGeneration: Long,
        leadMs: Long,
        onPlayerTypeAttempt: (String) -> Unit = {},
    ): PlayerRepository.StreamPlaylistCandidate? {
        val startedAtMs = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            Log.d(
                "XtraVaft",
                "event=candidate_lookup_start requestToken=${diagnosticToken(requestId)} origin=$origin " +
                    "markerToken=${diagnosticToken(markerKey)} itemToken=${diagnosticToken(mediaId)} " +
                    "sourceToken=${diagnosticToken(sourceUri)} vaftGeneration=$vaftGeneration " +
                    "sourceGeneration=$sourceGeneration configToken=${diagnosticToken(configurationFingerprint)} " +
                    "qualityRevision=$qualityRevision leadMs=$leadMs playerTypeCount=${playerTypes.size} timeoutMs=$timeoutMs",
            )
        }
        var outcome = "unknown"
        var resultPlayerType: String? = null
        var requestCompleted = false
        return try {
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
                ).also { requestCompleted = true }
            }.also { result ->
                outcome = when {
                    result != null -> "ready"
                    requestCompleted -> "empty"
                    else -> "timeout"
                }
                resultPlayerType = result?.playerType
            }
        } catch (error: CancellationException) {
            outcome = "cancelled"
            throw error
        } catch (error: Exception) {
            outcome = "failed"
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "candidate_lookup_failed type=${error::class.simpleName}")
            null
        } finally {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "XtraVaft",
                    "event=candidate_lookup_done requestToken=${diagnosticToken(requestId)} origin=$origin " +
                        "result=$outcome durationMs=${(SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)} " +
                        "playerType=${resultPlayerType ?: "none"}",
                )
            }
        }
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
                val joinStartedAtMs = SystemClock.elapsedRealtime()
                val requestId = vaftPreparationRequestId
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "XtraVaft",
                        "event=candidate_lookup_join requestToken=${diagnosticToken(requestId)} " +
                            "markerToken=${diagnosticToken(markerKey)} itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)} " +
                            "timeoutMs=$timeoutMs",
                    )
                }
                val completed = withTimeoutOrNull(timeoutMs) {
                    job.join()
                    true
                } == true
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "XtraVaft",
                        "event=candidate_lookup_join_done requestToken=${diagnosticToken(requestId)} " +
                            "completed=$completed jobActive=${job.isActive} " +
                            "durationMs=${(SystemClock.elapsedRealtime() - joinStartedAtMs).coerceAtLeast(0L)}",
                    )
                }
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
    ): VaftPreparedSourceResolution {
        val handle = prepared.warmup ?: return VaftPreparedSourceResolution()
        if (prepared.requestId != vaftPreparationRequestId) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(handle.token)
            if (vaftWarmupToken == handle.token) vaftWarmupToken = null
            return VaftPreparedSourceResolution()
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
            return VaftPreparedSourceResolution()
        }
        if (!source.renditionCompatibleWithIntent) {
            xtraModule.streamMedia3Runtime.discardVaftCandidateWarmup(handle.token)
            if (vaftWarmupToken == handle.token) vaftWarmupToken = null
            return VaftPreparedSourceResolution(rejectionReason = "warm_rendition_mismatch")
        }
        return VaftPreparedSourceResolution(source = source)
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
        val configuredQuality = configuredPlaybackQuality(extras)
        val automaticQualityIntent = configuredQuality?.name?.equals(PlaybackContract.AUTO_QUALITY, ignoreCase = true) == true
        val verified = decodePlaybackQuality(xtraModule.json, extras.getString(VAFT_VERIFIED_RENDITION))
            .takeUnless { automaticQualityIntent }
        val previousExtras = liveStreamExtras?.let(::Bundle)?.apply {
            putString(URI, player.currentMediaItem?.localConfiguration?.uri?.toString())
            putBoolean(VAFT_ALTERNATE_ACTIVE, vaftAlternateActive)
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
                        var positionAlignmentResolved = false
                        var positionAlignmentDeadlineMs: Long? = null
                        var lastHandoffDiagnostic: String? = null
                        val targetMediaId = handoffExtras.getString(VAFT_HANDOFF_TARGET_MEDIA_ID)
                        while (generation == vaftGeneration && player.currentMediaItem?.mediaId == targetMediaId &&
                            player.currentMediaItem?.localConfiguration?.uri?.toString() == targetUri
                        ) {
                            if (player.playerError != null) return@withTimeoutOrNull false
                            if (!overrideApplied && verified != null) {
                                val manifest = player.currentManifest as? HlsManifest
                                val verifiedVariantQuality = verified.name?.let { name ->
                                    DesiredHlsQuality(name, verified.bitrate, verified.codecs)
                                }
                                val variants = manifest?.multivariantPlaylist?.variants.orEmpty()
                                val variant = variants.firstOrNull { it.url.toString() == verified.url }
                                    ?: variants.firstOrNull { candidate ->
                                        verifiedVariantQuality?.matches(candidate.format) == true
                                    }
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
                                                    (format.id == rendition.format.id || format.label == rendition.format.label ||
                                                        matchesVerifiedRendition(format, verified))
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
                            val renditionMatchesByUrl = verified?.url != null && activeVideo?.url?.toString() == verified.url
                            val renditionMatchesByQuality = activeVideo?.let {
                                matchesVerifiedRendition(it.format, verified)
                            } == true
                            val renditionConfirmed = when {
                                audioOnly || backgroundVideoSuppressed -> true
                                verified == null -> activeVideo != null
                                else -> renditionMatchesByUrl || renditionMatchesByQuality
                            }
                            if (BuildConfig.DEBUG) {
                                val diagnostic = "state=${player.playbackState} overrideApplied=$overrideApplied confirmed=$renditionConfirmed " +
                                    "activeRendition=${activeVideo?.url?.toString()?.let(::diagnosticToken)} " +
                                    "qualityMode=${if (automaticQualityIntent) "auto" else "pinned"} " +
                                    "verified=${verified?.name ?: "none"} " +
                                    "confirmation=${when {
                                        audioOnly || backgroundVideoSuppressed -> "not_required"
                                        verified == null -> "adaptive_track"
                                        renditionMatchesByUrl -> "url"
                                        renditionMatchesByQuality -> "quality_identity"
                                        activeVideo == null -> "no_active_video"
                                        else -> "mismatch"
                                    }} videoSuppressed=$backgroundVideoSuppressed"
                                if (diagnostic != lastHandoffDiagnostic) Log.d("XtraVaft", "handoff $diagnostic")
                                lastHandoffDiagnostic = diagnostic
                            }
                            if (player.playbackState == Player.STATE_READY && playlist != null) {
                                if (!positionAlignmentResolved) {
                                    val alignment = alignVaftPosition(player, handoffPosition)
                                    val aligned = alignment == "program_date_time" ||
                                        alignment == "live_offset_best_effort"
                                    if (BuildConfig.DEBUG) Log.d("XtraVaft", "handoff_position_alignment result=$alignment")
                                    if (aligned) {
                                        positionAlignmentResolved = true
                                        delay(50L)
                                        continue
                                    }
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (alignment == "not_seekable") {
                                        positionAlignmentResolved = true
                                        if (BuildConfig.DEBUG) {
                                            Log.d("XtraVaft", "handoff_position_alignment fallback=live_default reason=not_seekable")
                                        }
                                    } else {
                                        val deadlineMs = positionAlignmentDeadlineMs
                                            ?: (nowMs + VAFT_POSITION_ALIGNMENT_GRACE_MS).also {
                                                positionAlignmentDeadlineMs = it
                                            }
                                        if (nowMs >= deadlineMs) {
                                            positionAlignmentResolved = true
                                            if (BuildConfig.DEBUG) {
                                                Log.d("XtraVaft", "handoff_position_alignment fallback=live_default reason=unavailable")
                                            }
                                        } else {
                                            delay(minOf(100L, deadlineMs - nowMs))
                                            continue
                                        }
                                    }
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
                    if (returningPrimary) {
                        restorePrimaryQualityIntent(
                            player = player,
                            primaryExtras = extras,
                            fallback = previousQualityPolicy,
                        )
                    }
                    vaftAuthoritativeUri = targetUri
                    vaftCurrentPlayerType = extras.getString(VAFT_PLAYER_TYPE)
                    vaftAlternateActive = extras.getBoolean(VAFT_ALTERNATE_ACTIVE)
                    if (!vaftAlternateActive) {
                        vaftPrimaryReturnAfterElapsedMs = null
                        liveStreamUri = targetUri
                        liveStreamExtras = Bundle(extras).apply { remove(VAFT_ALTERNATE_ACTIVE); remove(VAFT_VERIFIED_RENDITION); remove(VAFT_PLAYER_TYPE) }
                    }
                    player.currentMediaItem?.let { mediaItem ->
                        xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(mediaItem)
                        xtraModule.streamMedia3Runtime.setVaftEvidenceAlternateSource(mediaItem, vaftAlternateActive)
                    }
                    vaftOutputSuppressed = false
                    player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    authorizeVaftEntryFrameRelease(player)
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
                                var rollbackPositionAlignmentResolved = false
                                var rollbackPositionAlignmentDeadlineMs: Long? = null
                                while (generation == vaftGeneration && player.currentMediaItem?.mediaId == rollbackMediaId &&
                                    player.currentMediaItem?.localConfiguration?.uri?.toString() == rollbackUri
                                ) {
                                    if (player.playerError != null) return@withTimeoutOrNull RollbackState(restored = false, clean = false)
                                    val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
                                    if (player.playbackState == Player.STATE_READY && playlist != null) {
                                        if (!rollbackPositionAlignmentResolved) {
                                            val alignment = alignVaftPosition(player, handoffPosition)
                                            val aligned = alignment == "program_date_time" ||
                                                alignment == "live_offset_best_effort"
                                            if (BuildConfig.DEBUG) Log.d("XtraVaft", "rollback_position_alignment result=$alignment")
                                            if (aligned) {
                                                rollbackPositionAlignmentResolved = true
                                                delay(50L)
                                                continue
                                            }
                                            val nowMs = SystemClock.elapsedRealtime()
                                            if (alignment == "not_seekable") {
                                                rollbackPositionAlignmentResolved = true
                                                if (BuildConfig.DEBUG) {
                                                    Log.d("XtraVaft", "rollback_position_alignment fallback=live_default reason=not_seekable")
                                                }
                                            } else {
                                                val deadlineMs = rollbackPositionAlignmentDeadlineMs
                                                    ?: (nowMs + VAFT_POSITION_ALIGNMENT_GRACE_MS).also {
                                                        rollbackPositionAlignmentDeadlineMs = it
                                                    }
                                                if (nowMs >= deadlineMs) {
                                                    rollbackPositionAlignmentResolved = true
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d("XtraVaft", "rollback_position_alignment fallback=live_default reason=unavailable")
                                                    }
                                                } else {
                                                    delay(minOf(100L, deadlineMs - nowMs))
                                                    continue
                                                }
                                            }
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
                            player.currentMediaItem?.let { mediaItem ->
                                xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(mediaItem)
                                xtraModule.streamMedia3Runtime.setVaftEvidenceAlternateSource(mediaItem, previousAlternate)
                            }
                        }
                        if (cleanRollback) {
                            player.volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                            authorizeVaftEntryFrameRelease(player)
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
        keepBackgroundPlayback: Boolean = false,
    ): ListenableFuture<SessionResult> {
        backgroundPlayback = keepBackgroundPlayback
        val uri = extras.getString(URI)?.takeIf { it.isNotBlank() }
        val channelLogin = extras.getString(CHANNEL_LOGIN)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val title = extras.getString(TITLE)
        val channelName = extras.getString(CHANNEL_NAME)
        val channelLogo = extras.getString(CHANNEL_LOGO)
        if (beginNewPlayback) {
            livePlaybackSessionGeneration++
            backgroundRecoveryAttempt = 0
            backgroundPlaybackStartedAtMs = null
            vaftPrimaryFirstFrameMediaId = null
            vaftPrimaryFirstFrameSourceUri = null
            vaftPrimaryFirstFrameElapsedMs = null
            primaryQualityCatalogMasterUri = null
            primaryQualityCatalog = null
            primaryQualityCatalogRenditionUris = emptySet()
            primaryQualityCatalogSessionGeneration = -1L
        }
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
        if (beginNewPlayback) runtime.resetVaftEvidenceSession()
        val login = channelLogin ?: "unknown"
        val startupQuality = decodePlaybackQuality(xtraModule.json, extras.getString(PLAYBACK_QUALITY))
        val desired = resumptionHlsQuality(startupQuality)
        runtime.qualitySelectionPolicy.set(desired.name, desired.bitrate, desired.codecs)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO,
                startupQuality?.name == PlaybackContract.AUDIO_ONLY_QUALITY || startupQuality?.name == PlaybackContract.CHAT_ONLY_QUALITY)
            .build()
        if (BuildConfig.DEBUG) {
            Log.d(
                "SmoothHlsQuality",
                "stream_start_quality name=${desired.name} requested=${startupQuality?.name} " +
                    "videoDisabled=${Media3C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes}",
            )
        }
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
        val activePlayer = setPlaybackSource(player, playbackSource)
        runtime.setVaftEvidenceAlternateSource(playbackMediaItem, isAlternate = false)
        runtime.setPrimaryPlaybackMediaItem(playbackMediaItem)
        activePlayer.volume = if (extras.getBoolean(SUPPRESS_VAFT_OUTPUT)) 0f else prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        activePlayer.setPlaybackSpeed(1f)
        activePlayer.prepare()
        streamStartupTrace?.prepareCalledAtMs = SystemClock.elapsedRealtime()
        activePlayer.playWhenReady = extras.getBoolean(PLAY_WHEN_READY, true)
        refreshSystemMediaMetadata()
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
                paused = !activePlayer.playWhenReady,
            ),
        )
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun isCasting(): Boolean =
        castPlayer?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private fun setPlaybackSource(
        localPlayer: ExoPlayer,
        source: MediaSource,
        positionMs: Long = Media3C.TIME_UNSET,
    ): Player {
        val activePlayer = mediaSession?.player ?: localPlayer
        if (isCasting()) {
            // A Cast receiver fetches the URI itself. Keep Xtra's custom sources
            // for local playback, and send the actual media item to the receiver.
            val item = source.mediaItem.buildUpon().apply {
                if (prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)) {
                    setMediaMetadata(systemMediaMetadata())
                }
            }.build()
            activePlayer.setMediaItem(item, positionMs)
        } else {
            localPlayer.setMediaSource(source, positionMs)
        }
        return activePlayer
    }

    private fun systemMediaMetadata(): MediaMetadata {
        val showTitle = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_TITLE, true)
        val showCategory = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_CATEGORY, true)
        val isLive = viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
        val artworkSource = prefs().getString(C.SYSTEM_MEDIA_ARTWORK_SOURCE, C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW)
        val artwork = when (artworkSource) {
            C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW -> TwitchApiHelper.getStreamThumbnail(viewingStreamPreview, 720, 405)
            C.SYSTEM_MEDIA_ARTWORK_CATEGORY -> TwitchApiHelper.getGameBoxArt(viewingCategoryImage)
            C.SYSTEM_MEDIA_ARTWORK_NONE -> null
            else -> TwitchApiHelper.getProfileImage(viewingChannelImage)
        }
        val channel = viewingChannelName?.takeIf { it.isNotBlank() } ?: viewingChannelLogin
        val title = if (isLive) channel else viewingTitle?.takeIf { it.isNotBlank() } ?: channel
        val artist = if (isLive) {
            buildList {
                add(systemBehindLiveSeconds()?.let { seconds ->
                    getString(R.string.system_media_behind_live, "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                } ?: getString(if (liveRewindActive) R.string.player_rewinding else R.string.player_live))
                viewingCategoryName?.takeIf { showCategory && it.isNotBlank() }?.let(::add)
                viewingTitle?.takeIf { showTitle && it.isNotBlank() }?.let(::add)
            }.joinToString(" · ")
        } else {
            channel
        }
        val subtitle = viewingCategoryName?.takeIf { showCategory }
        val artworkUri = artwork?.toUri()?.let { uri ->
            if (isLive && artworkSource == C.SYSTEM_MEDIA_ARTWORK_STREAM_PREVIEW) {
                uri.buildUpon()
                    .appendQueryParameter("xtra_preview_bucket", viewingStreamPreviewGeneration.toString())
                    .build()
            } else {
                uri
            }
        }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .build()
    }

    private fun refreshSystemMediaMetadata() {
        // Metadata is presentation state. Replacing a Cast queue item removes the
        // playing item and can restart playback on every thumbnail refresh.
        invalidatePlaybackSessionPlayerState()
    }

    private fun refreshMediaButtonPreferences(player: Player) {
        val activePlayer = mediaSession?.player ?: player
        lastMediaButtonSeekable = activePlayer.isCurrentMediaItemSeekable
        val session = mediaSession ?: return
        if (!prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)) {
            session.setMediaButtonPreferences(emptyList())
            return
        }
        val buttons = buildList {
            if (prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true) &&
                activePlayer.isCurrentMediaItemSeekable && (!isLocalLive() || liveRewindActive)
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
            if (isLocalLive() && !liveRewindActive && !liveRewindTransitioning &&
                prefs().getBoolean(C.PLAYER_LIVE_REWIND, true) &&
                prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true)
            ) {
                add(CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
                    .setSessionCommand(SessionCommand(REPLAY_30, Bundle.EMPTY))
                    .setDisplayName(getString(R.string.system_media_replay_30))
                    .setSlots(CommandButton.SLOT_BACK)
                    .setEnabled(systemReplayJob?.isActive != true)
                    .build())
            }
            if (liveRewindActive && prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_GO_LIVE, true)) {
                add(
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setSessionCommand(SessionCommand(GO_LIVE, Bundle.EMPTY))
                        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                        .setDisplayName(getString(R.string.seek_to_live))
                        .build(),
                )
            }
        }
        val canShowAudioModeAction = canToggleSystemAudioMode()
        val audioModeDiagnostic = if (BuildConfig.DEBUG) systemAudioModeAvailabilityDiagnostic() else null
        if (audioModeDiagnostic != null && lastSystemAudioModeAvailability != audioModeDiagnostic) {
            lastSystemAudioModeAvailability = audioModeDiagnostic
            Log.d(
                "SystemAudioMode",
                "event=media_button available=$canShowAudioModeAction $audioModeDiagnostic",
            )
            if (isLocalLive() && !canShowAudioModeAction) {
                logSystemAudioModeState("media_button_unavailable", activePlayer)
            }
        }
        val localButtons = if (canShowAudioModeAction) {
            buttons + CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(if (isLogicalAudioOnly()) R.drawable.ic_media_video else R.drawable.ic_media_audio)
                .setSessionCommand(SessionCommand(TOGGLE_SYSTEM_AUDIO, Bundle.EMPTY))
                .setDisplayName(getString(if (isLogicalAudioOnly()) R.string.system_media_video else R.string.audio_only))
                .setSlots(if (liveRewindActive) CommandButton.SLOT_BACK_SECONDARY else CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
                .setEnabled(systemReplayJob?.isActive != true)
                .build()
        } else buttons
        session.setMediaButtonPreferences(localButtons)
    }

    private fun systemAudioModeAvailabilityDiagnostic(): String {
        val item = playbackPlayer?.currentMediaItem
        val mediaId = item?.mediaId
        val controlled = xtraModule.streamMedia3Runtime.controlledPlaylistFor(mediaId) != null
        return "localLive=${isLocalLive()} rewind=$liveRewindActive rewindTransition=$liveRewindTransitioning " +
            "enabled=${prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)} handoff=$vaftSourceSwitching " +
            "handoffJob=${vaftHandoffJob?.isActive == true} coordinator=${vaftCoordinatorJob?.isActive == true} " +
            "alternate=$vaftAlternateActive suppressed=$vaftOutputSuppressed entryOwner=${vaftEntryFrameOwner != null} " +
            "entryVisible=${vaftEntryFrameVisibleId != null} frameCapture=${vaftHandoffFrameCaptureId != null} " +
            "handoffTarget=${vaftHandoffTargetMediaId != null} controlled=$controlled " +
            "sourceOwned=${mediaId?.startsWith(VAFT_SOURCE_MEDIA_ID_PREFIX) == true}"
    }

    private fun refreshMediaButtonPreferencesIfSeekabilityChanged(player: Player) {
        val activePlayer = mediaSession?.player ?: player
        if (activePlayer.isCurrentMediaItemSeekable == lastMediaButtonSeekable) return
        refreshMediaButtonPreferences(player)
    }

    private inner class PlaybackSessionPlayer(
        private val player: Player,
    ) : ForwardingSimpleBasePlayer(player) {

        fun invalidateServiceState() {
            invalidateState()
        }

        override fun getState(): State {
            val state = super.getState()
            val seekCommandAdditions = mediaSessionSeekCommandAdditions(
                systemMediaControlsEnabled = prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true),
                systemSeekButtonsEnabled = prefs().getBoolean(C.SYSTEM_MEDIA_SHOW_SEEK_BUTTONS, true),
                liveRewindActive = this@PlaybackService.liveRewindActive && !isCasting(),
                liveRewindTransitioning = this@PlaybackService.liveRewindTransitioning,
                seekable = player.isCurrentMediaItemSeekable,
            )
            val availableCommands = state.availableCommands.buildUpon().apply {
                if (viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE && !isCasting() && !liveRewindActive) {
                    remove(COMMAND_SEEK_TO_PREVIOUS)
                    remove(COMMAND_SEEK_TO_NEXT)
                    remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (seekCommandAdditions.seekToPrevious && (!isLocalLive() || liveRewindActive)) add(COMMAND_SEEK_TO_PREVIOUS)
                if (seekCommandAdditions.seekToNext && (!isLocalLive() || liveRewindActive)) add(COMMAND_SEEK_TO_NEXT)
                if (seekCommandAdditions.seekInCurrentMediaItem) add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }.build()
            return state.buildUpon().setAvailableCommands(availableCommands).apply {
                if (!state.timeline.isEmpty && viewingContentType != null &&
                    prefs().getBoolean(C.SYSTEM_MEDIA_CONTROLS_ENABLED, true)
                ) {
                    val timeline = if (liveRewindActive) {
                        // Twitch's growing recording is live HLS to the decoder,
                        // but it has a real replay position and duration. Expose
                        // those to Android so the card can scrub and animate.
                        object : ForwardingTimeline(state.timeline) {
                            override fun getWindow(
                                windowIndex: Int,
                                window: Timeline.Window,
                                defaultPositionProjectionUs: Long,
                            ): Timeline.Window =
                                super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply {
                                    liveConfiguration = null
                                }
                        }
                    } else {
                        state.timeline
                    }
                    setPlaylist(timeline, state.currentTracks, systemMediaMetadata())
                }
            }.build()
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
            if (error is CancellationException) throw error
            if (BuildConfig.DEBUG) Log.w("LiveRewind", "Failed to reconstruct live source after rewind setup failure (${error.javaClass.simpleName})")
            false
        }
    }

    private fun createVodMediaSource(uri: android.net.Uri): MediaSource =
        HlsMediaSource.Factory(
            DefaultDataSource.Factory(
                this@PlaybackService,
                when {
                    prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP) == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.httpExecutor.value, false, false, null, null, null) { false }
                    }
                    else -> {
                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null) { false }
                    }
                },
            ),
        ).apply {
            setPlaylistParserFactory(twitchHlsPlaylistParserFactory())
        }.createMediaSource(MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build())

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

    private fun scheduleBackgroundRecovery(delayOverrideMs: Long? = null) {
        val currentPlayer = playbackPlayer ?: return
        if (mediaSession?.player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (vaftCoordinatorJob?.isActive == true || vaftSourceSwitching ||
            !backgroundPlayback || !currentPlayer.playWhenReady ||
            viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
        ) return
        backgroundRecoveryTimer?.cancel()
        backgroundPlaybackStartedAtMs?.let { startedAtMs ->
            if (SystemClock.elapsedRealtime() - startedAtMs >= BACKGROUND_STABLE_PLAYBACK_RESET_MS) {
                backgroundRecoveryAttempt = 0
            }
            backgroundPlaybackStartedAtMs = null
        }
        val delay = delayOverrideMs ?: (1500L * (1L shl backgroundRecoveryAttempt.coerceAtMost(5))).coerceAtMost(30_000L)
        backgroundRecoveryAttempt = (backgroundRecoveryAttempt + 1).coerceAtMost(5)
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackRecovery",
                "event=background_recovery_queued attempt=$backgroundRecoveryAttempt delayMs=$delay " +
                    "networkValidated=${hasValidatedInternet()} " +
                    "itemToken=${diagnosticToken(currentPlayer.currentMediaItem?.mediaId)}",
            )
        }
        backgroundRecoveryTimer = Timer().apply {
            schedule(delay) {
                Handler(Looper.getMainLooper()).post {
                    backgroundRecoveryTimer = null
                    val player = playbackPlayer ?: return@post
                    if (mediaSession?.player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return@post
                    if (backgroundPlayback && vaftCoordinatorJob?.isActive != true && !vaftSourceSwitching
                        && player.playWhenReady
                        && viewingContentType == ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
                    ) {
                        if (!hasValidatedInternet()) {
                            if (BuildConfig.DEBUG) {
                                Log.d("PlaybackRecovery", "event=background_recovery_waiting_for_network")
                            }
                            scheduleBackgroundRecovery()
                            return@post
                        }
                        lifecycleScope.launch {
                            val state = resumptionState?.takeIf { it.type == PlaybackContract.STREAM }
                            val extras = liveStreamExtras?.let(::Bundle)
                            if (state == null || extras == null || player.playWhenReady != true || !backgroundPlayback) {
                                return@launch
                            }
                            val freshUrl = try {
                                resolveResumptionStreamUri(state)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (BuildConfig.DEBUG) {
                                    Log.w("PlaybackRecovery", "event=background_fresh_url_failed", error)
                                }
                                null
                            }
                            if (player.playWhenReady != true || !backgroundPlayback) return@launch
                            if (freshUrl.isNullOrBlank()) {
                                val offline = isLiveStreamConfirmedOffline(state)
                                if (BuildConfig.DEBUG) {
                                    Log.w(
                                        "PlaybackRecovery",
                                        "event=background_fresh_url_unavailable confirmedOffline=$offline " +
                                            "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)}",
                                    )
                                }
                                if (offline && resumptionState?.channelLogin == state.channelLogin &&
                                    backgroundPlayback && player.playWhenReady
                                ) {
                                    backgroundPlayback = false
                                    player.pause()
                                    resumptionState?.let { saveResumptionState(it.copy(paused = true)) }
                                    return@launch
                                }
                                scheduleBackgroundRecovery()
                                return@launch
                            }
                            extras.putString(URI, freshUrl)
                            extras.putBoolean(PLAY_WHEN_READY, true)
                            val result = try {
                                startLiveStream(
                                    player = player,
                                    extras = extras,
                                    beginNewPlayback = false,
                                    keepBackgroundPlayback = true,
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                if (BuildConfig.DEBUG) {
                                    Log.w("PlaybackRecovery", "event=background_source_start_failed", error)
                                }
                                scheduleBackgroundRecovery()
                                return@launch
                            }
                            result.addListener({
                                val started = runCatching {
                                    result.get().resultCode == SessionResult.RESULT_SUCCESS
                                }.getOrDefault(false)
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "PlaybackRecovery",
                                        "event=background_source_start result=${if (started) "started" else "failed"} " +
                                            "playWhenReady=${player.playWhenReady} " +
                                            "itemToken=${diagnosticToken(player.currentMediaItem?.mediaId)}",
                                    )
                                }
                                if (!started) scheduleBackgroundRecovery()
                            }, MoreExecutors.directExecutor())
                        }
                    }
                }
            }
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun isLiveStreamConfirmedOffline(state: PlaybackState): Boolean {
        val channelId = state.channelId?.takeIf { it.isNotBlank() }
        val channelLogin = state.channelLogin?.takeIf { it.isNotBlank() }
        if (channelId == null && channelLogin == null) return false
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(this)
        val ids = channelId?.let(::listOf)
        val logins = if (channelId == null) channelLogin?.let(::listOf) else null
        try {
            val response = xtraModule.graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                ids = ids,
                logins = logins,
            )
            if (response.errors.isNullOrEmpty()) {
                val user = response.data?.users?.firstOrNull()
                if (user != null) return user.stream == null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Use the same Helix and lightweight GraphQL fallbacks as foreground status checks.
        }

        val helixHeaders = TwitchApiHelper.getHelixHeaders(this)
        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                val response = xtraModule.helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = ids,
                    logins = logins,
                )
                return response.data.isEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep retrying unless a provider confirms that the channel is offline.
            }
        }

        if (channelLogin != null) {
            try {
                val response = xtraModule.graphQLRepository.loadViewerCount(
                    networkLibrary = networkLibrary,
                    headers = gqlHeaders,
                    channelLogin = channelLogin,
                )
                if (response.errors.isNullOrEmpty()) {
                    val user = response.data?.user
                    if (user != null) return user.stream == null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No authoritative status was available.
            }
        }
        return false
    }

    private suspend fun refreshBackgroundStreamMetadata() {
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE) return
        val channelId = viewingChannelId
        val channelLogin = viewingChannelLogin
        if (channelId.isNullOrBlank() && channelLogin.isNullOrBlank()) return
        val streamId = viewingContentId
        val generation = livePlaybackSessionGeneration
        try {
            val response = xtraModule.graphQLRepository.loadQueryUsersStream(
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                headers = TwitchApiHelper.getGQLHeaders(this),
                ids = channelId?.takeIf { it.isNotBlank() }?.let(::listOf),
                logins = if (channelId.isNullOrBlank()) channelLogin?.let(::listOf) else null,
            )
            if (!response.errors.isNullOrEmpty()) return
            val user = response.data?.users?.firstOrNull() ?: return
            val stream = user.stream ?: return
            if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
                generation != livePlaybackSessionGeneration ||
                streamId != viewingContentId || channelLogin != viewingChannelLogin ||
                (streamId != null && stream.id != streamId)
            ) return
            val player = mediaSession?.player ?: return
            handleViewingMetadataCommand(Bundle().apply {
                putString(STREAM_ID, streamId)
                putString(CHANNEL_LOGIN, channelLogin)
                user.displayName?.let { putString(CHANNEL_NAME, it) }
                user.profileImageURL?.let { putString(CHANNEL_LOGO, it) }
                stream.broadcaster?.broadcastSettings?.title?.let { putString(TITLE, it) }
                stream.previewImageURL?.let { putString(THUMBNAIL, it) }
                stream.game?.id?.let { putString(GAME_ID, it) }
                stream.game?.displayName?.let { putString(GAME_NAME, it) }
            }, player)
            if (BuildConfig.DEBUG) {
                Log.d("PlaybackLifecycle", "event=background_stream_metadata_refreshed channel=$channelLogin")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the last valid metadata when the provider is temporarily unavailable.
            if (BuildConfig.DEBUG) Log.d("PlaybackLifecycle", "Background metadata refresh failed", error)
        }
    }

    internal fun handleViewingMetadataCommand(extras: Bundle, player: Player) {
        val streamId = extras.getString(STREAM_ID)
        val channelLogin = extras.getString(CHANNEL_LOGIN)
        if (viewingContentType != ViewingPlaybackMetadata.CONTENT_TYPE_LIVE ||
            (streamId != null && streamId != viewingContentId) ||
            (channelLogin != null && !channelLogin.equals(viewingChannelLogin, ignoreCase = true))
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
        if (extras.containsKey(CHANNEL_NAME)) {
            extras.getString(CHANNEL_NAME)?.takeIf { it.isNotBlank() }?.let { viewingChannelName = it }
        }
        if (extras.containsKey(CHANNEL_LOGO)) {
            extras.getString(CHANNEL_LOGO)?.takeIf { it.isNotBlank() }?.let { viewingChannelImage = it }
        }
        if (extras.containsKey(THUMBNAIL)) {
            extras.getString(THUMBNAIL)?.takeIf { it.isNotBlank() }?.let { thumbnail ->
                viewingStreamPreview = thumbnail
                viewingStreamPreviewGeneration = System.currentTimeMillis()
            }
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
        refreshSystemMediaMetadata()
        refreshMediaButtonPreferences(player)
    }

    internal fun setViewingMetadata(
        contentType: String,
        contentId: String?,
        extras: Bundle,
        beginNewPlayback: Boolean = true,
    ) {
        val viewingContentChanged = viewingContentType != contentType || viewingContentId != contentId
        if (viewingContentChanged) {
            diagnostics.resetForNewMedia()
            viewingStreamPreviewGeneration = System.currentTimeMillis()
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
        playbackPlayer?.let(::updateAdaptiveLiveSampleTicker)
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
        val activePlayer = castPlayer ?: playbackPlayer ?: player
        val metadata = viewingMetadata() ?: return
        updatePrimaryPlaybackWatchState(activePlayer)
        xtraModule.viewingStatsRecorder.update(
            sourceId = viewingStatsSourceId,
            metadata = metadata,
            isPlaying = activePlayer.isPlaying,
            isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING,
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

    private fun updatePrimaryPlaybackWatchState(sourcePlayer: Player) {
        val player = castPlayer ?: playbackPlayer ?: sourcePlayer
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

    private fun updateHiddenStreamAudio() {
        val player = playbackPlayer
        val hidden = backgroundPlayback || !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val source = player?.takeIf {
            isLocalLive() && !liveRewindActive && !liveRewindTransitioning &&
                it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED &&
                !isLogicalAudioOnly() && Media3C.TRACK_TYPE_VIDEO !in it.trackSelectionParameters.disabledTrackTypes &&
                prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        }?.let { xtraModule.streamMedia3Runtime.hiddenAudioFor(it.currentMediaItem?.mediaId) }
        if (!hidden || source == null) {
            hiddenAudioJob?.cancel()
            hiddenAudioJob = null
            hiddenAudioSource?.setHidden(false)
            hiddenAudioSource = null
            return
        }
        if (hiddenAudioSource === source && (source.hidden || hiddenAudioJob?.isActive == true)) return
        hiddenAudioJob?.cancel()
        hiddenAudioSource?.setHidden(false)
        hiddenAudioSource = source
        if (BuildConfig.DEBUG) Log.d("HiddenStreamAudio", "event=scheduled elapsedMs=${SystemClock.elapsedRealtime()}")
        hiddenAudioJob = lifecycleScope.launch {
            delay(5_000L)
            if (hiddenAudioSource === source && player.isPlaying) source.setHidden(true)
        }
    }

    private fun suppressVideoForBackground(player: Player) {
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
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
            updateHiddenStreamAudio()
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
        unregisterReceiver(screenVisibilityReceiver)
        hiddenAudioJob?.cancel()
        hiddenAudioSource?.setHidden(false)
        prefs().unregisterOnSharedPreferenceChangeListener(mediaPreferenceListener)
        adaptiveLiveSampleJob?.cancel()
        adaptiveLiveSampleJob = null
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
        castPlayer = null
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
        private const val VAFT_SAMPLE_WARMUP_LEAD_MS = 2_000L
        private const val VAFT_ENTRY_FRAME_CAPTURE_LEAD_MS = 1_200L
        private const val VAFT_ENTRY_FRAME_CAPTURE_ACK_TIMEOUT_MS = 400L
        private const val VAFT_ENTRY_FRAME_RELEASE_WATCHDOG_MS = 2_000L
        private const val VAFT_CANDIDATE_REFRESH_LEAD_MS = 12_000L
        private const val VAFT_PRELOAD_HANDOFF_GRACE_MS = 500L
        private const val VAFT_ALTERNATE_HANDOFF_TIMEOUT_MS = 4_000L
        private const val VAFT_PRIMARY_HANDOFF_TIMEOUT_MS = 15_000L
        private const val VAFT_POSITION_ALIGNMENT_GRACE_MS = 500L
        private const val VAFT_DIFFERENT_TYPE_RETRY_YIELD_MS = 150L
        private const val VAFT_CAPTURE_ACK_TIMEOUT_MS = 400L
        private const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "xtra_media_playback"
        private const val PLAYBACK_NOTIFICATION_ID = 5201
        private const val PLAYBACK_BOOTSTRAP_NOTIFICATION_ID = 5202
        private const val RESUMPTION_STREAM_URL_TIMEOUT_MS = 8_000L
        private const val BACKGROUND_STALL_RECOVERY_DELAY_MS = 30_000L
        private const val BACKGROUND_STABLE_PLAYBACK_RESET_MS = 120_000L
        const val START_STREAM = "startStream"
        const val START_LIVE_REWIND = "startLiveRewind"
        const val GET_LIVE_REWIND_STATE = "getLiveRewindState"
        const val UPDATE_VIEWING_METADATA = "updateViewingMetadata"
        const val REPLAY_30 = "replay30"
        const val TOGGLE_SYSTEM_AUDIO = "toggleSystemAudio"
        const val SYSTEM_QUALITY_CHANGED = "systemQualityChanged"
        const val SYSTEM_AUDIO_PRIMARY_SOURCE_URI = "systemAudioPrimarySourceUri"
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
        const val CONTROLLED_PRIMARY_QUALITIES = "controlledPrimaryQualities"
        const val CONTROLLED_SELECTION_NAMES = "controlledSelectionNames"
        const val CONTROLLED_SELECTION_CODECS = "controlledSelectionCodecs"
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
        const val ACK_VAFT_ENTRY_FRAME = "ackVaftEntryFrame"
        const val VAFT_HANDOFF_FRAME_CAPTURE_ID = "vaftHandoffFrameCaptureId"
        const val VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID = "vaftHandoffFrameCaptureResolvedId"
        const val VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED = "vaftHandoffFrameCaptureAccepted"
        const val VAFT_HANDOFF_FRAME_READY = "vaftHandoffFrameReady"
        const val VAFT_ENTRY_FRAME_CAPTURE_ID = "vaftEntryFrameCaptureId"
        const val VAFT_ENTRY_FRAME_ACCEPTED_ID = "vaftEntryFrameAcceptedId"
        const val VAFT_ENTRY_FRAME_RESOLVED_ID = "vaftEntryFrameResolvedId"
        const val VAFT_ENTRY_FRAME_CAPTURE_ACCEPTED = "vaftEntryFrameCaptureAccepted"
        const val VAFT_ENTRY_FRAME_VISIBLE_ID = "vaftEntryFrameVisibleId"
        const val VAFT_ENTRY_FRAME_RELEASE_MEDIA_ID = "vaftEntryFrameReleaseMediaId"
        const val VAFT_ENTRY_FRAME_RELEASE_GENERATION = "vaftEntryFrameReleaseGeneration"
        const val VAFT_ENTRY_FRAME_RELEASE_AUTHORIZED = "vaftEntryFrameReleaseAuthorized"
        const val VAFT_ENTRY_FRAME_READY = "vaftEntryFrameReady"
        const val VAFT_HANDOFF_TARGET_MEDIA_ID = "vaftHandoffTargetMediaId"
        const val VAFT_HANDOFF_GENERATION = "vaftHandoffGeneration"
        const val VAFT_HANDOFF_TARGET_FRAME_RENDERED = "vaftHandoffTargetFrameRendered"
        const val VAFT_ALTERNATE_ACTIVE = "vaftAlternateActive"
        const val VAFT_VERIFIED_RENDITION = "vaftVerifiedRendition"
        const val VAFT_LOGICAL_QUALITY = "vaftLogicalQuality"
        const val VAFT_PLAYER_TYPE = "vaftPlayerType"
        const val GET_VAFT_PLAYBACK_STATE = "getVaftPlaybackState"
        const val VAFT_QUALITY_STATUS = "vaftQualityStatus"
        const val VAFT_AD_REMAINING_MS = "vaftAdRemainingMs"
        const val VAFT_LIVE_DELAY_MS = "vaftLiveDelayMs"
        const val VAFT_WINDOW_ACTIVE = "vaftWindowActive"
        const val VAFT_CONTROLLED_FEED = "vaftControlledFeed"
        const val VAFT_PLAYBACK_STATE_CHANGED = "vaftPlaybackStateChanged"
        const val VAFT_SOURCE_URI = "vaftSourceUri"
        const val REWIND_VIDEO_ID = "rewindVideoId"
        const val LIVE_REWIND_ACTIVE = "liveRewindActive"
        const val LIVE_REWIND_TRANSITIONING = "liveRewindTransitioning"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val DURATION = "duration"
        const val NAMES = "names"
        const val QUALITIES_MEDIA_ID = "qualitiesMediaId"
        const val QUALITIES_SOURCE_URI = "qualitiesSourceUri"
        const val QUALITIES_CATALOG_URI = "qualitiesCatalogUri"
        const val QUALITIES_CATALOG_PRIMARY = "qualitiesCatalogPrimary"
        const val QUALITIES_ROWS_TOKEN = "qualitiesRowsToken"
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
