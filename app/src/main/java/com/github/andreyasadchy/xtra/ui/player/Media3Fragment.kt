package com.github.andreyasadchy.xtra.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C as Media3C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.common.diagnosticToken
import com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
import com.github.andreyasadchy.xtra.ui.common.logVideoSurfaceBinding
import com.github.andreyasadchy.xtra.ui.common.logVideoTracks
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.clip.ClipEditorDialogFragment
import com.github.andreyasadchy.xtra.ui.player.clip.ClipEditorRestorationState
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPreparationRepository
import com.github.andreyasadchy.xtra.ui.player.clip.ClipSizeEstimator
import com.github.andreyasadchy.xtra.ui.player.clip.LiveClipBufferManager
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isVaftEnabled
import com.github.andreyasadchy.xtra.util.isTelevision
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class Media3Fragment : Media3PlayerFragment(), PlaybackVideoInfoHost, ClipEditorDialogFragment.Host {

    private sealed class VaftFrozenFrameOwner(val requestId: String) {
        class Entry(requestId: String) : VaftFrozenFrameOwner(requestId)
        class Return(requestId: String) : VaftFrozenFrameOwner(requestId)
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var clipPreparationJob: Job? = null
    private var clipPreparationSnackbar: Snackbar? = null
    private var livePlaybackBeforeClipEditor: Boolean? = null
    private var playbackPositionBeforeClipEditor: Long? = null
    private var playbackBeforeClipEditor: Boolean? = null
    private var vodClipEditorOpen = false
    private var liveClipDirectoryPath: String? = null
    private var liveSurfaceRestoreListener: Player.Listener? = null
    private var liveSurfaceRestoreTimeout: Runnable? = null
    private var foregroundVideoRestoreController: Player? = null
    private var foregroundVideoRestoreListener: Player.Listener? = null
    private var clipEditorCoverTimeout: Runnable? = null
    private var clipStatusGeneration = 0L
    private var clipStatusRequestInFlight = false
    private var clipStatusQueued = false
    private var clipControlSupported: Boolean? = null
    private var vodClipMediaItemId: String? = null
    private var vodClipSegmentDurationsUs = IntArray(0)
    private var vodClipSegmentByteRanges = LongArray(0)
    private var vodClipBitrate: Int? = null
    private enum class LiveRewindFallbackStage { NONE, DIRECT_SOURCE, USHER }
    private var liveRewindFallbackVod: LiveRewindVod? = null
    private var liveRewindPrimaryUrl: String? = null
    private var liveRewindFallbackPositionMs = 0L
    private var liveRewindPrimaryReady = false
    private var liveRewindFallbackStage = LiveRewindFallbackStage.NONE
    private var liveRewindFallbackSourceStarted = false
    private var liveRewindDirectPlaylistMode = false
    private var liveRewindDirectQualityGeneration = 0L
    private var pendingDirectQualityCatalogSourceUri: String? = null
    private var liveRewindFallbackPreferredQuality: SourceSwitchQualityIdentity? = null
    private var liveRewindFallbackQualityMetadata: List<VideoQuality>? = null
    private var videoOutputCover: View? = null
    private var vaftFrozenFrameView: ImageView? = null
    private var vaftFrozenFrameBitmap: Bitmap? = null
    private var vaftFrozenFrameOwner: VaftFrozenFrameOwner? = null
    private var vaftFrameCaptureRequestId: String? = null
    private var vaftFrozenFrameRequestId: String? = null
    private var vaftEntryFrameCaptureRequestId: String? = null
    private var vaftEntryFrameAttemptedId: String? = null
    private var vaftEntryFrameAwaitingAckId: String? = null
    private val vaftEntryFrozenFrameId: String?
        get() = (vaftFrozenFrameOwner as? VaftFrozenFrameOwner.Entry)?.requestId
    private var vaftEntryFrameAcceptedId: String? = null
    private var vaftEntryFrameVisibleId: String? = null
    private var vaftHandoffTargetMediaId: String? = null
    private var vaftHandoffGeneration = -1L
    private var vaftHandoffTargetFrameRendered = false
    private val player: MediaController?
        get() = controllerFuture?.let {
            if (it.isDone && !it.isCancelled) {
                runCatching { it.get() }.getOrNull()
            } else {
                null
            }
        }
    private var playerListener: Player.Listener? = null
    private val mediaControllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackService.SYSTEM_QUALITY_CHANGED && isAdded && view != null) {
                viewModel.restoredQualityBootstrapConsumed = true
                invalidateResumeQualityConfirmation("system_audio_mode")
                clearResumeAppliedQualityTarget()
                invalidateQualityRequest()
                pendingSourceSwitchQuality.clear()
                viewModel.quality = decodePlaybackQuality(xtraModule.json, args.getString(PlaybackService.PLAYBACK_QUALITY))
                viewModel.previousQuality = decodePlaybackQuality(xtraModule.json, args.getString(PlaybackService.PLAYBACK_PREVIOUS_QUALITY))
                viewModel.restoreQuality = args.getBoolean(PlaybackService.PLAYBACK_RESTORE_QUALITY)
                args.getString(PlaybackService.SYSTEM_AUDIO_PRIMARY_SOURCE_URI)?.let { uri ->
                    viewModel.playlistUrl = uri.toUri()
                }
                setVideoOutputVisible(viewModel.quality?.name != AUDIO_ONLY_QUALITY && !viewModel.hidden)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            // A pending rewind-state query also happens during ordinary live
            // source changes. It must not discard the handoff completion event.
            if (command.customAction == PlaybackService.VAFT_PLAYBACK_STATE_CHANGED && videoType == STREAM && isAdded && view != null &&
                !isLiveRewindSourceOwned()) {
                if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui handoff=${args.getBoolean(PlaybackService.VAFT_HANDOFF)} alternate=${args.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE)} suppressed=${args.getBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT)}")
                val wasAlternate = viewModel.usingAlternateStream
                val wasWindowActive = viewModel.vaftWindowActive
                val previousRendition = viewModel.vaftVerifiedRendition
                val wasHandoff = vaftHandoffInProgress
                val handoffActive = args.getBoolean(PlaybackService.VAFT_HANDOFF)
                val controlledFeed = args.getBoolean(PlaybackService.VAFT_CONTROLLED_FEED)
                viewModel.controlledVaftFeed = controlledFeed
                viewModel.controlledVaftActive = controlledFeed && args.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE)
                if (controlledFeed) {
                    viewModel.updateQualities = true
                    requestQualities()
                    requestCurrentVideoQuality(controller)
                }
                val windowActive = args.getBoolean(PlaybackService.VAFT_WINDOW_ACTIVE) && !controlledFeed
                val alternateActive = args.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE) && !controlledFeed
                vaftHandoffInProgress = handoffActive
                vaftHandoffTargetMediaId = args.getString(PlaybackService.VAFT_HANDOFF_TARGET_MEDIA_ID)
                vaftHandoffGeneration = args.getLong(PlaybackService.VAFT_HANDOFF_GENERATION, -1L)
                vaftHandoffTargetFrameRendered = args.getBoolean(PlaybackService.VAFT_HANDOFF_TARGET_FRAME_RENDERED)
                if (vaftHandoffInProgress && !wasHandoff) {
                    invalidateQualityRequest()
                    clearQualityCatalog()
                    viewModel.updateQualities = true
                    refreshOpenQualityDialog(loading = true)
                }
                viewModel.vaftWindowActive = windowActive
                viewModel.vaftLogicalQuality = decodePlaybackQuality(xtraModule.json, args.getString(PlaybackService.VAFT_LOGICAL_QUALITY))
                val vaftOwned = windowActive || handoffActive || alternateActive
                if (vaftOwned && !viewModel.vaftQualityState.isActive) {
                    supersedeAutomaticRecoveryForSourceTransition()
                    viewModel.vaftQualityState.begin(viewModel.vaftLogicalQuality ?: viewModel.quality)
                }
                viewModel.usingAlternateStream = alternateActive
                viewModel.vaftVerifiedRendition = decodePlaybackQuality(xtraModule.json, args.getString(PlaybackService.VAFT_VERIFIED_RENDITION))
                val captureId = args.getString(PlaybackService.VAFT_HANDOFF_FRAME_CAPTURE_ID)
                reconcileVaftFrameCaptureResult(args)
                viewModel.vaftRequired = args.getBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT)
                updateVaftEntryFrameState(controller, args)
                if (captureId != vaftFrameCaptureRequestId && vaftFrameCaptureRequestId != null) {
                    vaftFrameCaptureRequestId = null
                }
                if (!captureId.isNullOrBlank()) {
                    viewModel.hidden = false
                    setVideoOutputVisible(true)
                    requestVaftFrozenFrame(controller, captureId)
                } else if (viewModel.vaftRequired) {
                    suppressVaftPlayback()
                } else {
                    restoreVaftPlayback()
                }
                if (vaftHandoffTargetMediaId == null && !vaftHandoffInProgress && vaftEntryFrozenFrameId == null) {
                    clearVaftFrozenFrame()
                } else {
                    clearVaftFrozenFrameAfterTargetFrame(controller)
                }
                if (!viewModel.vaftRequired && !viewModel.usingAlternateStream &&
                    (wasAlternate || (wasWindowActive && !viewModel.vaftWindowActive))) {
                    args.getString(PlaybackService.VAFT_SOURCE_URI)?.let { uri ->
                        viewModel.playlistUrl = uri.toUri()
                        viewModel.vaftQualityState.expectPrimaryReturn(uri)
                    }
                }
                if (!vaftOwned && viewModel.vaftQualityState.isActive &&
                    !viewModel.vaftQualityState.isAwaitingPrimaryReturn
                ) {
                    viewModel.vaftQualityState.clear()
                }
                if (!vaftHandoffInProgress) {
                    if (wasAlternate != viewModel.usingAlternateStream || previousRendition != viewModel.vaftVerifiedRendition ||
                        (wasWindowActive && !viewModel.vaftWindowActive) || viewModel.vaftQualityState.isAwaitingPrimaryReturn) {
                        invalidateQualityRequest()
                        viewModel.updateQualities = true
                        requestQualities()
                    }
                }
                setQualityText()
            }
            if (command.customAction == PlaybackService.VIDEO_INPUT_FORMAT_CHANGED) {
                if (viewModel.controlledVaftFeed) {
                    // Decoder input can be ahead of the segment currently being presented.
                    requestCurrentVideoQuality(controller)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                val qualityUri = args.getString(PlaybackService.VIDEO_QUALITY_URI)
                val currentUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
                val qualityName = args.getString(PlaybackService.VIDEO_QUALITY_NAME)
                if ((qualityUri == null || qualityUri == currentUri) && !qualityName.isNullOrBlank()) {
                    updateConfirmedVideoQuality(
                        VideoQuality(
                            name = qualityName,
                            codecs = args.getString(PlaybackService.VIDEO_QUALITY_CODECS),
                            bitrate = args.getInt(PlaybackService.VIDEO_QUALITY_BITRATE)
                                .takeIf { args.containsKey(PlaybackService.VIDEO_QUALITY_BITRATE) },
                            frameRate = args.getFloat(PlaybackService.VIDEO_QUALITY_FRAME_RATE)
                                .takeIf { args.containsKey(PlaybackService.VIDEO_QUALITY_FRAME_RATE) },
                        ),
                        controller,
                        sourceConfirmed = qualityUri != null && qualityUri == currentUri,
                    )
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    private var streamRecoveryJob: Job? = null
    private var streamRecoveryRequestId = 0L
    private var endedLiveRecoveryJob: Job? = null
    private var endedLiveRecoveryGeneration = 0L
    private var liveStatusEventGeneration = 0L
    private var liveStallWatchdogJob: Job? = null
    private var liveRewindStateSyncJob: Job? = null
    private var liveRewindStateSyncGeneration = 0L
    private val liveRecoveryState = LivePlaybackStallRecoveryState()
    private var recoveringBehindLiveWindow = false
    private var vaftHandoffInProgress = false

    private fun vaftOwnsPlayback(): Boolean =
        viewModel.vaftWindowActive || viewModel.vaftRequired || viewModel.usingAlternateStream || vaftHandoffInProgress

    protected override fun isQualityCatalogCurrent(): Boolean {
        if (videoType != STREAM) return true
        val item = player?.currentMediaItem ?: return false
        val sourceUri = item.localConfiguration?.uri?.toString() ?: return false
        val identity = viewModel.streamQualityCatalogIdentity ?: return false
        return identity.mediaId == item.mediaId && identity.sourceUri == sourceUri
    }

    protected override fun clearQualityCatalog() {
        pendingDirectQualityCatalogSourceUri = null
        super.clearQualityCatalog()
    }

    protected override fun bindQualityCatalogToCurrentSource(sourceUri: String?) {
        if (sourceUri.isNullOrBlank()) {
            clearQualityCatalog()
            return
        }
        val item = player?.currentMediaItem
        val currentSourceUri = item?.localConfiguration?.uri?.toString()
        if (item == null || currentSourceUri.isNullOrBlank() || currentSourceUri != sourceUri) {
            viewModel.streamQualityCatalogIdentity = null
            pendingDirectQualityCatalogSourceUri = sourceUri
            return
        }
        pendingDirectQualityCatalogSourceUri = null
        viewModel.streamQualityCatalogIdentity = StreamQualityCatalogIdentity(
            mediaId = item.mediaId,
            sourceUri = currentSourceUri,
            isPrimary = false,
            catalogUri = null,
        )
    }

    protected override fun refreshOpenQualityDialog(loading: Boolean) {
        super.refreshOpenQualityDialog(loading)
        if (!BuildConfig.DEBUG) return
        val dialog = childFragmentManager.findFragmentByTag("closeOnPip") as? RadioButtonDialogFragment ?: return
        if (!dialog.isAdded) return
        val item = player?.currentMediaItem
        val currentUri = item?.localConfiguration?.uri?.toString()
        val identity = viewModel.streamQualityCatalogIdentity
        val currentCatalog = isQualityCatalogCurrent()
        val visibleQualities = viewModel.qualities.takeIf { !loading && currentCatalog }
        Log.d(
            "XtraQuality",
            "event=quality_picker_render itemToken=${diagnosticToken(item?.mediaId)} " +
                "sourceToken=${diagnosticToken(currentUri)} " +
                "catalogItemToken=${diagnosticToken(identity?.mediaId)} " +
                "catalogSourceToken=${diagnosticToken(identity?.sourceUri)} " +
                "catalogUriToken=${diagnosticToken(identity?.catalogUri)} " +
                "primary=${identity?.isPrimary?.toString() ?: "unknown"} " +
                "visibleCount=${visibleQualities?.size ?: 0} " +
                "visibleRowsToken=${qualityCatalogRowsToken(visibleQualities)} " +
                "loading=${loading || !currentCatalog || visibleQualities.isNullOrEmpty()}",
        )
    }

    protected override suspend fun qualityMenuHeader(): RadioButtonDialogFragment.Header? {
        if (videoType != STREAM || isLiveRewindSourceOwned()) return null
        val controller = player ?: return null
        val result = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_VAFT_PLAYBACK_STATE, Bundle.EMPTY), Bundle.EMPTY,
        ).awaitFuture()
        check(result.resultCode == SessionResult.RESULT_SUCCESS)
        val status = result.extras.getBundle(PlaybackService.VAFT_QUALITY_STATUS) ?: return null
        val source = when (status.getString(PlaybackService.VAFT_PLAYER_TYPE)) {
            "site" -> getString(R.string.vaft_source_site)
            "popout" -> getString(R.string.vaft_source_popout)
            "mobile_web" -> getString(R.string.vaft_source_mobile)
            "embed" -> getString(R.string.vaft_source_embed)
            "autoplay" -> getString(R.string.vaft_source_autoplay)
            else -> getString(R.string.vaft_source_resolving)
        }
        val remaining = if (status.containsKey(PlaybackService.VAFT_AD_REMAINING_MS)) {
            val seconds = (status.getLong(PlaybackService.VAFT_AD_REMAINING_MS).coerceAtLeast(0L) + 999L) / 1_000L
            getString(R.string.vaft_ad_remaining, seconds)
        } else getString(R.string.vaft_ad_duration_unknown)
        val priority = getString(if (requireContext().prefs().getString(C.PLAYER_VAFT_PLAYBACK_PRIORITY,
                C.VAFT_PRIORITY_CONTINUITY) == C.VAFT_PRIORITY_LIVE) {
            R.string.settings_vaft_priority_live
        } else R.string.settings_vaft_priority_continuity)
        val playback = if (status.containsKey(PlaybackService.VAFT_LIVE_DELAY_MS)) {
            getString(R.string.vaft_playback_delay, priority, status.getLong(PlaybackService.VAFT_LIVE_DELAY_MS) / 1_000L)
        } else priority
        return RadioButtonDialogFragment.Header(getString(R.string.vaft_quality_status_title),
            getString(R.string.vaft_quality_status_body, source, remaining, playback))
    }

    protected override fun qualityPickerSelectionCandidates(): List<QualityPickerCandidate> {
        if (viewModel.controlledVaftFeed) {
            // The primary ladder represents requested qualities. A temporary VAFT
            // fallback must not replace the checked choice; the HUD reports its
            // decoded rendition. Use that rendition only if the request has no row.
            val requestedQuality = viewModel.quality ?: pendingSourceSwitchQuality.peek()?.let {
                VideoQuality(name = it.name, codecs = it.codecs, bitrate = it.bitrate)
            }
            return listOfNotNull(requestedQuality, confirmedVideoQualityForCurrentSource())
                .map(::QualityPickerCandidate)
        }
        val identity = viewModel.vaftQualityState.identityForPrimaryReturn?.let {
            VideoQuality(name = it.name, codecs = it.codecs, bitrate = it.bitrate)
        }
        val activeVaftOwnership = videoType == STREAM && vaftOwnsPlayback()
        val awaitingPrimaryReturn = videoType == STREAM && viewModel.vaftQualityState.isAwaitingPrimaryReturn
        return if (activeVaftOwnership || awaitingPrimaryReturn) {
            listOfNotNull(identity, viewModel.vaftLogicalQuality, viewModel.quality)
                .map(::QualityPickerCandidate)
        } else {
            listOfNotNull(viewModel.quality, pendingSourceSwitchQuality.peek()?.let {
                VideoQuality(name = it.name, codecs = it.codecs, bitrate = it.bitrate)
            }).map(::QualityPickerCandidate)
        }
    }

    private var qualityRetryJob: Job? = null
    private var qualityRetryAttempts = 0
    private var qualityRequestInFlight = false
    private var qualityRequestQueued = false
    private var qualityRequestGeneration = 0
    private var resumeQualityGeneration = 0L
    private var resumeQualityController: MediaController? = null
    private var resumeQualityMediaId: String? = null
    private var resumeQualityConfirmationPending = false
    private var resumeQualityConfirmationAvailable = false
    private var resumeConfirmedQuality: VideoQuality? = null
    private var deferredAutomaticQuality: VideoQuality? = null
    private var resumeQualityConfirmationTimeoutJob: Job? = null
    private var qualityRequestDeferred = false
    private var vodStartSequence = 0
    private var pendingAudioOnlySourceSwitch = false
    private var pendingPlaybackPrepareAfterChatOnly = false
    private val pendingQualityCallbacks = mutableListOf<() -> Unit>()
    private var pendingAudioOnlyRequest = false
    private val pendingSourceSwitchQuality = SourceSwitchQualityState()
    private var nativeCues: List<Cue> = emptyList()
    private var shownLiveCaptionError: String? = null
    private var renderedPositionSecond = Long.MIN_VALUE
    private var renderedDurationMs = Long.MIN_VALUE
    private var renderedPlaybackChrome: PlaybackChromeState? = null
    private val liveBufferHealthTrend = LiveBufferHealthTrend()
    private var lastLiveBufferHealthDiagnosticMs = 0L
    private var hasEstablishedLiveBufferHealth = false
    private var lastLiveBufferHealthOffsetMs: Long? = null
    private var renderedLiveBufferHealthReading: LiveBufferHealthTrend.Reading? = null
    private val liveBufferHealthWindow = Timeline.Window()
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }
    private var lastSurfaceViewAttachedAtMs: Long? = null
    private var lastSurfaceViewDetachedAtMs: Long? = null
    private var lastSurfaceCreatedAtMs: Long? = null
    private var lastSurfaceDestroyedAtMs: Long? = null
    private var lastPlayerOutputAttachedAtMs: Long? = null
    private var lastPlayerOutputDetachedAtMs: Long? = null
    private var lastMediaItemTransitionAtMs: Long? = null
    private var lastPrepareStartedAtMs: Long? = null
    private var lastObservedPlaybackState = Player.STATE_IDLE
    private var videoOutputBindingGeneration = 0L
    private var surfaceHolderGeneration = 0L
    private val surfacePixelSampledItemTokens = mutableSetOf<String>()
    private val videoOutputOwner = VideoOutputOwner<Player, SurfaceView>(
        attachTarget = { currentPlayer, target ->
            currentPlayer.setVideoSurfaceView(target)
            if (BuildConfig.DEBUG) {
                lastPlayerOutputAttachedAtMs = SystemClock.elapsedRealtime()
                videoOutputBindingGeneration += 1L
                Log.d(
                    "PlaybackLifecycle",
                    "event=video_output_attach_actual generation=$videoOutputBindingGeneration " +
                        "itemToken=${diagnosticToken(currentPlayer.currentMediaItem?.mediaId)}",
                )
            }
        },
        detachTarget = { currentPlayer, target ->
            currentPlayer.clearVideoSurfaceView(target)
            if (BuildConfig.DEBUG) {
                lastPlayerOutputDetachedAtMs = SystemClock.elapsedRealtime()
                videoOutputBindingGeneration += 1L
                Log.d(
                    "PlaybackLifecycle",
                    "event=video_output_detach_actual generation=$videoOutputBindingGeneration " +
                        "itemToken=${diagnosticToken(currentPlayer.currentMediaItem?.mediaId)}",
                )
            }
        },
    )

    private val videoOutputView: SurfaceView
        get() = binding.playerSurface

    private fun configureVideoOutputView() {
        binding.playerSurface.visibility = View.VISIBLE
        if (BuildConfig.DEBUG) {
            binding.playerSurface.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    lastSurfaceViewAttachedAtMs = SystemClock.elapsedRealtime()
                    logSurfaceLifecycle(
                        "surface_view_attached",
                        binding.playerSurface.isAttachedToWindow,
                        binding.playerSurface.holder.surface.isValid,
                    )
                }

                override fun onViewDetachedFromWindow(view: View) {
                    lastSurfaceViewDetachedAtMs = SystemClock.elapsedRealtime()
                    logSurfaceLifecycle(
                        "surface_view_detached",
                        binding.playerSurface.isAttachedToWindow,
                        binding.playerSurface.holder.surface.isValid,
                    )
                }
            })
            binding.playerSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    lastSurfaceCreatedAtMs = SystemClock.elapsedRealtime()
                    surfaceHolderGeneration += 1L
                    logSurfaceLifecycle(
                        "surface_holder_created",
                        binding.playerSurface.isAttachedToWindow,
                        holder.surface.isValid,
                    )
                }

                override fun surfaceChanged(
                    holder: android.view.SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    lastSurfaceDestroyedAtMs = SystemClock.elapsedRealtime()
                    logSurfaceLifecycle(
                        "surface_holder_destroyed",
                        binding.playerSurface.isAttachedToWindow,
                        holder.surface.isValid,
                    )
                }
            })
        }
        binding.playerSurface.setOnTouchListener { _, event ->
            forwardVideoSurfaceTouch(binding.playerSurface, binding.dragView, event)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            binding.playerSurface.setSurfaceLifecycle(
                SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT,
            )
        }
    }

    private fun setVideoOutputVisible(visible: Boolean) {
        if (visible) {
            val currentPlayer = player
            val needsAttach = videoOutputView.visibility != View.VISIBLE ||
                (currentPlayer != null && videoOutputOwner.attachedPlayer() !== currentPlayer)
            if (needsAttach) videoOutputCover?.visibility = View.VISIBLE
            videoOutputView.visibility = View.VISIBLE
            currentPlayer?.let(::attachVideoOutput)
        } else {
            // SurfaceView owns an independently composed surface. Hide the last
            // video frame and release the player's target before making the view
            // GONE so Audio Only cannot leave a retained frame on screen.
            videoOutputCover?.visibility = View.VISIBLE
            detachVideoOutput()
            videoOutputView.visibility = View.GONE
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "VideoSurface",
                "set_output_visible visible=$visible view=${videoOutputView.visibility} " +
                    "cover=${videoOutputCover?.visibility} ownerBound=${videoOutputOwner.attachedPlayer() != null}",
            )
        }
        // PlayerHudLayout derives the fixed timeline position from the
        // rendered video output. Audio mode hides that output, so the HUD can
        // temporarily measure against the full aspect-ratio container. When
        // video is restored, force the HUD to measure again after the output
        // becomes visible or the timeline can remain below the video edge.
        refreshPlayerHudLayout()
    }

    private fun hideVideoOutputCover() {
        if (videoOutputView.visibility != View.VISIBLE ||
            viewModel.hidden ||
            viewModel.quality?.name == AUDIO_ONLY_QUALITY ||
            viewModel.quality?.name == CHAT_ONLY_QUALITY
        ) return
        videoOutputCover?.visibility = View.GONE
    }

    private fun requestVaftFrozenFrame(controller: MediaController, requestId: String) {
        if (vaftFrameCaptureRequestId == requestId) return
        vaftFrameCaptureRequestId = requestId
        val surfaceView = videoOutputView
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !surfaceView.isAttachedToWindow ||
            surfaceView.width <= 0 || surfaceView.height <= 0 || !surfaceView.holder.surface.isValid
        ) {
            finishVaftFrameCapture(controller, requestId, null)
            return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            finishVaftFrameCapture(controller, requestId, null)
            return
        }
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        finishVaftFrameCapture(controller, requestId, bitmap, result)
                    } else {
                        bitmap.recycle()
                        finishVaftFrameCapture(controller, requestId, null, result)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: RuntimeException) {
            bitmap.recycle()
            finishVaftFrameCapture(controller, requestId, null)
        }
    }

    private fun finishVaftFrameCapture(
        controller: MediaController,
        requestId: String,
        bitmap: Bitmap?,
        result: Int? = null,
    ) {
        if (vaftFrameCaptureRequestId != requestId || !isAdded || view == null) {
            bitmap?.recycle()
            return
        }
        vaftFrameCaptureRequestId = null
        if (bitmap == null) {
            clearVaftFrozenFrame()
            viewModel.vaftRequired = true
            suppressVaftPlayback()
            acknowledgeVaftFrameCapture(controller, requestId, ready = false)
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui_return_frame result=${result ?: -1} ready=false")
            return
        }

        if (vaftEntryFrozenFrameId != null) {
            bitmap.recycle()
            acknowledgeVaftFrameCapture(controller, requestId, ready = false)
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui_return_frame result=${result ?: PixelCopy.SUCCESS} ready=false reason=entry_frame_owned")
            return
        }

        clearVaftFrozenFrame()
        vaftFrozenFrameRequestId = requestId
        vaftFrozenFrameOwner = VaftFrozenFrameOwner.Return(requestId)
        vaftFrozenFrameBitmap = bitmap
        vaftFrozenFrameView?.apply {
            setImageBitmap(bitmap)
            visibility = View.VISIBLE
            bringToFront()
        }
        binding.root.postOnAnimation {
            val requestGeneration = requestId.substringBefore(':').toLongOrNull()
            if (requestGeneration != vaftHandoffGeneration ||
                vaftHandoffTargetMediaId.isNullOrBlank() ||
                vaftFrozenFrameBitmap !== bitmap || !isAdded || view == null
            ) {
                if (vaftFrozenFrameBitmap === bitmap) clearVaftFrozenFrame() else bitmap.recycle()
                return@postOnAnimation
            }
            viewModel.vaftRequired = true
            suppressVaftPlayback()
            acknowledgeVaftFrameCapture(controller, requestId, ready = true)
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui_return_frame result=${result ?: PixelCopy.SUCCESS} ready=true")
        }
    }

    private fun acknowledgeVaftFrameCapture(controller: MediaController, requestId: String, ready: Boolean) {
        val future = controller.sendCustomCommand(
            SessionCommand(PlaybackService.ACK_VAFT_HANDOFF_FRAME, Bundle.EMPTY),
            Bundle().apply {
                putString(PlaybackService.VAFT_HANDOFF_FRAME_CAPTURE_ID, requestId)
                putBoolean(PlaybackService.VAFT_HANDOFF_FRAME_READY, ready)
            },
        )
        future.addListener({
            val resultCode = runCatching { future.get().resultCode }.getOrNull()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "XtraVaft",
                    "ui_return_frame_ack request=${requestId.takeLast(8)} ready=$ready result=$resultCode",
                )
            }
        }, MoreExecutors.directExecutor())
    }

    private fun reconcileVaftFrameCaptureResult(extras: Bundle) {
        val resolvedId = extras.getString(PlaybackService.VAFT_HANDOFF_FRAME_CAPTURE_RESOLVED_ID) ?: return
        if (vaftFrameCaptureRequestId == resolvedId) vaftFrameCaptureRequestId = null
        if (vaftFrozenFrameRequestId == resolvedId &&
            !extras.getBoolean(PlaybackService.VAFT_HANDOFF_FRAME_CAPTURE_ACCEPTED)
        ) {
            clearVaftFrozenFrame()
        }
    }

    private fun updateVaftEntryFrameState(controller: MediaController, extras: Bundle) {
        val captureId = extras.getString(PlaybackService.VAFT_ENTRY_FRAME_CAPTURE_ID)
        val acceptedId = extras.getString(PlaybackService.VAFT_ENTRY_FRAME_ACCEPTED_ID)
        val resolvedId = extras.getString(PlaybackService.VAFT_ENTRY_FRAME_RESOLVED_ID)
        val accepted = extras.getBoolean(PlaybackService.VAFT_ENTRY_FRAME_CAPTURE_ACCEPTED)
        val visibleId = extras.getString(PlaybackService.VAFT_ENTRY_FRAME_VISIBLE_ID)
        val previousVisibleId = vaftEntryFrameVisibleId

        vaftEntryFrameAcceptedId = acceptedId
        vaftEntryFrameVisibleId = visibleId
        if (resolvedId == vaftEntryFrameCaptureRequestId) vaftEntryFrameCaptureRequestId = null
        if (resolvedId == vaftEntryFrameAwaitingAckId) vaftEntryFrameAwaitingAckId = null

        val localFrameId = vaftEntryFrozenFrameId
        if (localFrameId != null) {
            val rejected = resolvedId == localFrameId && !accepted
            val superseded = captureId != null && captureId != localFrameId && visibleId != localFrameId
            val noLongerVisible = previousVisibleId == localFrameId && visibleId != localFrameId
            if (rejected || superseded || noLongerVisible) {
                clearVaftEntryFrozenFrame(localFrameId)
            } else if (visibleId == localFrameId && acceptedId == localFrameId && accepted) {
                vaftFrozenFrameView?.apply {
                    visibility = View.VISIBLE
                    bringToFront()
                }
            } else {
                vaftFrozenFrameView?.visibility = View.GONE
            }
        }

        if (!captureId.isNullOrBlank() && captureId != vaftEntryFrameAttemptedId &&
            captureId != vaftEntryFrameCaptureRequestId &&
            captureId != vaftEntryFrameAwaitingAckId && captureId != vaftEntryFrozenFrameId
        ) {
            requestVaftEntryFrame(controller, captureId)
        }
    }

    private fun requestVaftEntryFrame(controller: MediaController, requestId: String) {
        vaftEntryFrozenFrameId?.let { clearVaftEntryFrozenFrame(it) }
        clearVaftFrozenFrame()
        vaftEntryFrameAttemptedId = requestId
        vaftEntryFrameCaptureRequestId = requestId
        val surfaceView = videoOutputView
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !surfaceView.isAttachedToWindow ||
            surfaceView.visibility != View.VISIBLE || surfaceView.width <= 0 || surfaceView.height <= 0 ||
            !surfaceView.holder.surface.isValid || videoOutputOwner.attachedPlayer() !== controller
        ) {
            finishVaftEntryFrameCapture(controller, requestId, null)
            return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            finishVaftEntryFrameCapture(controller, requestId, null)
            return
        }
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        finishVaftEntryFrameCapture(controller, requestId, bitmap)
                    } else {
                        bitmap.recycle()
                        finishVaftEntryFrameCapture(controller, requestId, null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: RuntimeException) {
            bitmap.recycle()
            finishVaftEntryFrameCapture(controller, requestId, null)
        }
    }

    private fun finishVaftEntryFrameCapture(
        controller: MediaController,
        requestId: String,
        bitmap: Bitmap?,
    ) {
        if (vaftEntryFrameCaptureRequestId != requestId || !isAdded || view == null) {
            bitmap?.recycle()
            return
        }
        vaftEntryFrameCaptureRequestId = null
        vaftEntryFrameAwaitingAckId = requestId
        if (bitmap == null) {
            acknowledgeVaftEntryFrameCapture(controller, requestId, ready = false)
            if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui_entry_frame ready=false request=${requestId.takeLast(8)}")
            return
        }

        if (vaftFrozenFrameOwner is VaftFrozenFrameOwner.Return) {
            bitmap.recycle()
            acknowledgeVaftEntryFrameCapture(controller, requestId, ready = false)
            return
        }

        vaftFrozenFrameBitmap = bitmap
        vaftFrozenFrameOwner = VaftFrozenFrameOwner.Entry(requestId)
        vaftFrozenFrameView?.apply {
            setImageBitmap(bitmap)
            visibility = View.GONE
        }
        acknowledgeVaftEntryFrameCapture(controller, requestId, ready = true)
        if (BuildConfig.DEBUG) Log.d("XtraVaft", "ui_entry_frame ready=true request=${requestId.takeLast(8)}")
    }

    private fun acknowledgeVaftEntryFrameCapture(
        controller: MediaController,
        requestId: String,
        ready: Boolean,
    ) {
        val future = controller.sendCustomCommand(
            SessionCommand(PlaybackService.ACK_VAFT_ENTRY_FRAME, Bundle.EMPTY),
            Bundle().apply {
                putString(PlaybackService.VAFT_ENTRY_FRAME_CAPTURE_ID, requestId)
                putBoolean(PlaybackService.VAFT_ENTRY_FRAME_READY, ready)
            },
        )
        future.addListener({
            val resultCode = runCatching { future.get().resultCode }.getOrNull()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "XtraVaft",
                    "ui_entry_frame_ack request=${requestId.takeLast(8)} ready=$ready result=$resultCode",
                )
            }
            if (resultCode != SessionResult.RESULT_SUCCESS && vaftEntryFrameAwaitingAckId == requestId) {
                vaftEntryFrameAwaitingAckId = null
                if (vaftEntryFrameAcceptedId != requestId) clearVaftEntryFrozenFrame(requestId)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun clearVaftFrozenFrameAfterTargetFrame(controller: MediaController) {
        if (vaftEntryFrozenFrameId != null) return
        val targetMediaId = vaftHandoffTargetMediaId ?: return
        if (!vaftHandoffInProgress && !viewModel.hidden && vaftHandoffTargetFrameRendered &&
            vaftHandoffGeneration >= 0L && controller.currentMediaItem?.mediaId == targetMediaId
        ) {
            clearVaftFrozenFrame()
        }
    }

    private fun clearVaftFrozenFrame() {
        if (vaftFrozenFrameOwner is VaftFrozenFrameOwner.Entry) return
        clearVaftFrameBitmap()
        vaftFrozenFrameRequestId = null
    }

    private fun clearVaftEntryFrozenFrame(requestId: String? = null) {
        val owner = vaftFrozenFrameOwner as? VaftFrozenFrameOwner.Entry ?: return
        if (requestId != null && requestId != owner.requestId) return
        vaftFrozenFrameRequestId = null
        clearVaftFrameBitmap()
    }

    private fun clearVaftFrameBitmap() {
        vaftFrozenFrameView?.apply {
            setImageDrawable(null)
            visibility = View.GONE
        }
        vaftFrozenFrameBitmap?.takeUnless { it.isRecycled }?.recycle()
        vaftFrozenFrameBitmap = null
        vaftFrozenFrameOwner = null
        vaftFrozenFrameRequestId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveClipDirectoryPath = savedInstanceState?.getString(STATE_CLIP_DIRECTORY)
        livePlaybackBeforeClipEditor = savedInstanceState
            ?.takeIf { it.containsKey(STATE_CLIP_PLAYING) }
            ?.getBoolean(STATE_CLIP_PLAYING)
        playbackPositionBeforeClipEditor = savedInstanceState?.getLong(STATE_CLIP_POSITION)
            ?.takeIf { savedInstanceState.containsKey(STATE_CLIP_POSITION) }
        playbackBeforeClipEditor = savedInstanceState?.getBoolean(STATE_CLIP_VOD_PLAYING)
            ?.takeIf { savedInstanceState.containsKey(STATE_CLIP_VOD_PLAYING) }
        vodClipEditorOpen = savedInstanceState?.getBoolean(STATE_CLIP_VOD_OPEN, false) == true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val outputCover = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        videoOutputCover = outputCover
        binding.aspectRatioFrameLayout.addView(outputCover)
        val frozenFrameView = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        vaftFrozenFrameView = frozenFrameView
        binding.aspectRatioFrameLayout.addView(frozenFrameView)
        configureVideoOutputView()
        childFragmentManager.setFragmentResultListener(
            ClipEditorDialogFragment.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result -> closeClipEditor(result.getString(ClipEditorDialogFragment.RESULT_DIRECTORY)) }
        childFragmentManager.setFragmentResultListener(
            ClipEditorDialogFragment.PREVIEW_READY_KEY,
            viewLifecycleOwner,
        ) { _, _ -> hideClipEditorTransitionCover() }
        (childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) as? ClipEditorDialogFragment)?.let { editor ->
            vodClipEditorOpen = editor.isVodSource
            binding.clipEditorContainer.visibility = View.VISIBLE
            binding.clipEditorTransitionCover.visibility = View.VISIBLE
            scheduleClipEditorCoverFallback()
        }
        configureClipControl()
        if (BuildConfig.DEBUG) {
            Log.d(
                "VideoSurface",
                "renderer=${videoOutputView.javaClass.simpleName}",
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    xtraModule.liveCaptionManager.state.collect { state ->
                        val nextCaptionText = if (videoType == STREAM && state.enabled) state.text else ""
                        updateLiveCaption(nextCaptionText, state.lineShiftToken)
                        if (videoType == STREAM) {
                            binding.playerControls.liveCaptions.setImageResource(
                                if (state.enabled) {
                                    androidx.media3.ui.R.drawable.exo_ic_subtitle_on
                                } else {
                                    androidx.media3.ui.R.drawable.exo_ic_subtitle_off
                                },
                            )
                            binding.playerControls.liveCaptions.contentDescription = getString(
                                if (state.enabled) R.string.disable_live_captions else R.string.enable_live_captions,
                            )
                        }
                        if (state.error.isNullOrBlank()) {
                            shownLiveCaptionError = null
                        } else if (state.error != shownLiveCaptionError) {
                            shownLiveCaptionError = state.error
                            Snackbar.make(
                                binding.root,
                                getString(R.string.live_captions_error, state.error),
                                Snackbar.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

        binding.playerControls.liveCaptions.apply {
            visibility = if (videoType == STREAM) View.VISIBLE else View.GONE
            setOnClickListener {
                showController(force = true)
                toggleLiveCaptions()
            }
            setOnLongClickListener {
                showController(force = true)
                openLiveCaptionSettings()
                true
            }
        }

        logVideoSurfaceBinding("on_view_created", player, videoOutputView)
    }

    override fun onViewingMetadataChanged(
        title: String?,
        gameId: String?,
        gameName: String?,
        thumbnail: String?,
        channelName: String?,
        channelImage: String?,
    ) {
        if (videoType != STREAM) return
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.UPDATE_VIEWING_METADATA,
                Bundle().apply {
                    putString(PlaybackService.STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                    putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                    // Category identity is a pair. Keep an incomplete refresh
                    // from combining a new name with an old ID (or vice versa).
                    if (gameId != null && gameName != null) {
                        putString(PlaybackService.GAME_ID, gameId)
                        putString(PlaybackService.GAME_NAME, gameName)
                    }
                    title?.let { putString(PlaybackService.TITLE, it) }
                    thumbnail?.let { putString(PlaybackService.THUMBNAIL, it) }
                    channelName?.let { putString(PlaybackService.CHANNEL_NAME, it) }
                    channelImage?.let { putString(PlaybackService.CHANNEL_LOGO, it) }
                },
            ),
            Bundle.EMPTY,
        )
    }

    private suspend fun synchronizeVaftPlaybackState(controller: MediaController): Boolean {
        if (!isAdded || view == null || controllerFuture?.isCancelled == true) return false
        val state = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_VAFT_PLAYBACK_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        ).awaitFuture()
        if (state.resultCode != SessionResult.RESULT_SUCCESS) return false
        val extras = state.extras
        extras.getString(PlaybackService.SYSTEM_AUDIO_PRIMARY_SOURCE_URI)?.let { uri ->
            viewModel.playlistUrl = uri.toUri()
        }
        val wasAlternate = viewModel.usingAlternateStream
        val wasWindowActive = viewModel.vaftWindowActive
        val controlledFeed = extras.getBoolean(PlaybackService.VAFT_CONTROLLED_FEED)
        viewModel.controlledVaftFeed = controlledFeed
        viewModel.controlledVaftActive = controlledFeed && extras.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE)
        val windowActive = extras.getBoolean(PlaybackService.VAFT_WINDOW_ACTIVE) && !controlledFeed
        val handoffActive = extras.getBoolean(PlaybackService.VAFT_HANDOFF)
        val alternateActive = extras.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE) && !controlledFeed
        viewModel.vaftWindowActive = windowActive
        vaftHandoffInProgress = handoffActive
        vaftHandoffTargetMediaId = extras.getString(PlaybackService.VAFT_HANDOFF_TARGET_MEDIA_ID)
        vaftHandoffGeneration = extras.getLong(PlaybackService.VAFT_HANDOFF_GENERATION, -1L)
        vaftHandoffTargetFrameRendered = extras.getBoolean(PlaybackService.VAFT_HANDOFF_TARGET_FRAME_RENDERED)
        reconcileVaftFrameCaptureResult(extras)
        viewModel.vaftLogicalQuality = decodePlaybackQuality(xtraModule.json, extras.getString(PlaybackService.VAFT_LOGICAL_QUALITY))
        viewModel.vaftVerifiedRendition = decodePlaybackQuality(
            xtraModule.json,
            extras.getString(PlaybackService.VAFT_VERIFIED_RENDITION),
        )
        val vaftOwned = windowActive || handoffActive || alternateActive
        if (vaftOwned && !viewModel.vaftQualityState.isActive) {
            supersedeAutomaticRecoveryForSourceTransition()
            viewModel.vaftQualityState.begin(viewModel.vaftLogicalQuality ?: viewModel.quality)
        }
        viewModel.usingAlternateStream = alternateActive
        if (!extras.getBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT) && !alternateActive &&
            (wasAlternate || (wasWindowActive && !windowActive))
        ) {
            extras.getString(PlaybackService.VAFT_SOURCE_URI)?.let { uri ->
                viewModel.playlistUrl = uri.toUri()
                viewModel.vaftQualityState.expectPrimaryReturn(uri)
            }
        }
        if (!vaftOwned && viewModel.vaftQualityState.isActive &&
            !viewModel.vaftQualityState.isAwaitingPrimaryReturn
        ) {
            viewModel.vaftQualityState.clear()
        }
        val captureId = extras.getString(PlaybackService.VAFT_HANDOFF_FRAME_CAPTURE_ID)
        viewModel.hidden = extras.getBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT) ||
            (vaftHandoffInProgress && !alternateActive && captureId.isNullOrBlank())
        viewModel.vaftRequired = viewModel.hidden
        setVideoOutputVisible(
            !viewModel.hidden && viewModel.quality?.name != AUDIO_ONLY_QUALITY &&
                viewModel.quality?.name != CHAT_ONLY_QUALITY,
        )
        updateVaftEntryFrameState(controller, extras)
        if (!captureId.isNullOrBlank()) requestVaftFrozenFrame(controller, captureId)
        if (vaftHandoffTargetMediaId == null && !vaftHandoffInProgress && vaftEntryFrozenFrameId == null) {
            clearVaftFrozenFrame()
        }
        else clearVaftFrozenFrameAfterTargetFrame(controller)
        return true
    }

    override fun onStart() {
        super.onStart()
        logVideoSurfaceBinding("on_start", player, videoOutputView)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        val future = MediaController.Builder(
            requireContext(),
            SessionToken(
                requireContext(),
                ComponentName(requireContext(), PlaybackService::class.java)
            )
        ).setListener(mediaControllerListener).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || future.isCancelled) {
                return@addListener
            }
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null || view == null || !isAdded) {
                controllerFuture = null
                MediaController.releaseFuture(future)
                return@addListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                if (controllerFuture !== future || view == null || !isAdded) return@launch
                val reconnectingStreamSession = videoType == STREAM && controller.currentMediaItem != null
                if (reconnectingStreamSession &&
                    viewModel.quality?.name != AUDIO_ONLY_QUALITY &&
                    viewModel.quality?.name != CHAT_ONLY_QUALITY
                ) {
                    // VAFT synchronization can show/attach the SurfaceView. Arm
                    // before it so its first rendered frame cannot outrun the
                    // listener that removes the reconnect cover.
                    armForegroundVideoRestore(controller)
                }
                if (reconnectingStreamSession && !synchronizeVaftPlaybackState(controller)) {
                    clearForegroundVideoRestore()
                    return@launch
                }
                if (reconnectingStreamSession) beginLiveRewindStateSync()
                // Install the new output while background playback still owns
                // the disabled video track. The service can then restore video
                // directly onto this Surface instead of racing the attachment.
                attachVideoOutput(controller)
                val foregroundTransition = try {
                    controller.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.SET_BACKGROUND_PLAYBACK,
                            Bundle().apply { putBoolean(PlaybackService.BACKGROUND_PLAYBACK, false) },
                        ),
                        Bundle.EMPTY,
                    ).awaitFuture()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("PlaybackResumption", "Failed to restore foreground playback state", error)
                    null
                }
                if (controllerFuture !== future || view == null || !isAdded) return@launch
                if (foregroundTransition?.resultCode != SessionResult.RESULT_SUCCESS) {
                    clearForegroundVideoRestore()
                    Log.e("PlaybackResumption", "Foreground playback restore command failed")
                    if (videoType == STREAM) {
                        clearPlayerError()
                        scheduleStreamRecovery(trigger = "foreground_restore_failed")
                    } else {
                        showPlayerError(R.string.player_error) { restartPlayer() }
                    }
                    return@launch
                }
                if (reconnectingStreamSession) {
                    synchronizeLiveRewindStateBeforeSessionRestore(controller)
                }
                logVideoSurfaceBinding("controller_connected", controller, videoOutputView)
                val attachingRestoredSession = requireArguments().getBoolean(KEY_RESTORED_PLAYBACK) &&
                    controller.currentMediaItem != null
                val reconcilingExistingStream = videoType == STREAM &&
                    controller.currentMediaItem != null &&
                    viewModel.quality?.name != AUDIO_ONLY_QUALITY &&
                    viewModel.quality?.name != CHAT_ONLY_QUALITY
                val resumeQualityRequestId = if (reconcilingExistingStream) {
                    beginResumeQualityConfirmation(controller)
                } else {
                    invalidateResumeQualityConfirmation("session_not_restored")
                    null
                }
                qualityRequestDeferred = false
                if (attachingRestoredSession) {
                    attachToExistingPlaybackSession(controller)
                    if (BuildConfig.DEBUG) {
                        Log.d("PlaybackResumption", "attached activity to active Media3 session type=$videoType")
                    }
                }
                val listener = object : Player.Listener {

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (BuildConfig.DEBUG) {
                            lastMediaItemTransitionAtMs = SystemClock.elapsedRealtime()
                            Log.d(
                                "PlaybackLifecycle",
                                "event=media_item_transition reason=$reason " +
                                    "itemToken=${diagnosticToken(mediaItem?.mediaId)}",
                            )
                        }
                        bindPendingResumeAppliedQuality(mediaItem)
                        if (mediaItem == null ||
                            mediaItem.mediaId != viewModel.resumeAppliedVideoQualityMediaId
                        ) {
                            clearResumeAppliedQualityTarget()
                        }
                        if (resumeQualityController === controller &&
                            mediaItem?.mediaId != resumeQualityMediaId
                        ) {
                            invalidateResumeQualityConfirmation(
                                "media_item_transition",
                                drainDeferredQualityRequest = true,
                            )
                        }
                        val mediaItemUri = mediaItem?.localConfiguration?.uri?.toString()
                        var pendingDirectQualityCatalogBound = false
                        pendingDirectQualityCatalogSourceUri?.let { pendingSourceUri ->
                            pendingDirectQualityCatalogSourceUri = null
                            if (mediaItem != null && mediaItemUri == pendingSourceUri) {
                                viewModel.streamQualityCatalogIdentity = StreamQualityCatalogIdentity(
                                    mediaId = mediaItem.mediaId,
                                    sourceUri = pendingSourceUri,
                                    isPrimary = false,
                                    catalogUri = null,
                                )
                                pendingDirectQualityCatalogBound = true
                            } else {
                                clearQualityCatalog()
                            }
                        }
                        if (mediaItem?.mediaId != viewModel.confirmedVideoQualityMediaId ||
                            mediaItemUri != viewModel.confirmedVideoQualitySourceUri
                        ) {
                            viewModel.confirmedVideoQuality = null
                            viewModel.confirmedVideoQualityMediaId = null
                            viewModel.confirmedVideoQualitySourceUri = null
                            setQualityText()
                        }
                        if (videoType == STREAM) {
                            invalidateQualityRequest()
                            if (isLiveRewindActiveOrSwitching()) {
                                refreshOpenQualityDialog(loading = !pendingDirectQualityCatalogBound)
                            } else {
                                clearQualityCatalog()
                                viewModel.updateQualities = true
                                refreshOpenQualityDialog(loading = true)
                                if (mediaItem != null) requestQualities()
                            }
                        }
                        resetLiveBufferHealth()
                        updateProgress()
                        refreshClipAvailability(resetAvailability = true)
                        applyPendingAudioOnlySourceSwitch()
                        applyPendingPlaybackPrepareAfterChatOnly()
                        if (isLiveRewindStateSyncPending() && mediaItem != null && videoType == STREAM) {
                            requestLiveRewindStateSync(controller, "media_item_transition")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (BuildConfig.DEBUG) {
                            if (playbackState == Player.STATE_BUFFERING &&
                                lastObservedPlaybackState == Player.STATE_IDLE
                            ) {
                                lastPrepareStartedAtMs = SystemClock.elapsedRealtime()
                            }
                            lastObservedPlaybackState = playbackState
                        }
                        if (BuildConfig.DEBUG && playbackState == Player.STATE_READY) {
                            player?.let { logPlaybackTimeline("state_ready", it) }
                        } else if (BuildConfig.DEBUG && playbackState == Player.STATE_ENDED) {
                            player?.let { logPlaybackTimeline("state_ended", it) }
                        }
                        if (playbackState == Player.STATE_READY) {
                            clearPendingVodStartupPositionIfSettled(controller)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            val handledByLiveRewind = onLiveRewindPlaybackError()
                            if (!handledByLiveRewind && player?.playWhenReady == true) {
                                verifyEndedLivePlayback(trigger = "playback_ended")
                            }
                        }
                        if (playbackState == Player.STATE_READY) {
                            if (
                                liveRewindFallbackVod != null &&
                                player?.currentMediaItem?.localConfiguration?.uri?.toString() ==
                                    liveRewindPrimaryUrl
                            ) {
                                liveRewindPrimaryReady = true
                                onLiveRewindPlaybackReady()
                            }
                            if (isLiveRewindStateSyncPending() && videoType == STREAM) {
                                requestLiveRewindStateSync(controller, "playback_ready")
                            }
                            recoveringBehindLiveWindow = false
                            updateLiveStallWatchdog(isBuffering = false)
                            clearPlayerError()
                            restoreClipEditorIfNeeded()
                        } else if (playbackState == Player.STATE_BUFFERING) {
                            updateLiveStallWatchdog(isBuffering = true)
                        }
                        renderPlaybackChrome()
                        val showPlayButton = shouldShowPlaybackPlayButton(player)
                        setPipActions(!showPlayButton)
                        updateProgress()
                        controllerAutoHide = !BuildConfig.DEBUG && !requireContext().isTelevision() && !showPlayButton
                        if (useController) {
                            showController(show = videoType != STREAM || showPlayButton)
                        }
                        if (playbackState == Player.STATE_READY) refreshClipAvailability()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "PlaybackLifecycle",
                                "event=play_when_ready playWhenReady=$playWhenReady reason=$reason " +
                                    "state=${player?.playbackState} " +
                                    "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)}",
                            )
                        }
                        if (!playWhenReady) {
                            cancelLiveStallRecovery(resetBudget = true)
                        } else if (videoType == STREAM && player?.currentMediaItem != null &&
                            (player?.playbackState == Player.STATE_IDLE || player?.playerError != null)
                        ) {
                            scheduleStreamRecovery(trigger = "play_resumed")
                        } else if (player?.playbackState == Player.STATE_BUFFERING) {
                            updateLiveStallWatchdog(isBuffering = true)
                        }
                        renderPlaybackChrome()
                        val showPlayButton = shouldShowPlaybackPlayButton(player)
                        setPipActions(!showPlayButton)
                        updateProgress()
                        controllerAutoHide = !BuildConfig.DEBUG && !requireContext().isTelevision() && !showPlayButton
                        if (useController) {
                            showController(show = videoType != STREAM || showPlayButton)
                        }
                    }

                    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "PlaybackLifecycle",
                                "event=available_commands_changed " +
                                    "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)} " +
                                    "seekInItemAvailable=${availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} " +
                                    "seekBackAvailable=${availableCommands.contains(Player.COMMAND_SEEK_BACK)} " +
                                    "seekForwardAvailable=${availableCommands.contains(Player.COMMAND_SEEK_FORWARD)} " +
                                    "seekToNextAvailable=${availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)}",
                            )
                        }
                        if (videoType == STREAM && controller.currentMediaItem != null) {
                            requestLiveRewindStateSync(controller, "available_commands_changed")
                        }
                        renderPlaybackChrome()
                        val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                        updateDurationIfNeeded(duration)
                        updateProgress()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize != VideoSize.UNKNOWN && player?.let { it.playbackState != Player.STATE_IDLE } == true) {
                            val aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                            binding.aspectRatioFrameLayout.setAspectRatio(aspectRatio)
                        }
                        refreshPlayerHudLayout()
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        nativeCues = cueGroup.cues
                        renderSubtitleOverlay()
                    }

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (videoType == VIDEO && reason == Player.DISCONTINUITY_REASON_SEEK) {
                            val pendingPosition = viewModel.pendingVodStartupPositionMs
                            if (pendingPosition != null &&
                                !vodStartupPositionsAreClose(newPosition.positionMs, pendingPosition)
                            ) {
                                viewModel.pendingVodStartupPositionMs = null
                            }
                        }
                        if (BuildConfig.DEBUG && reason == Player.DISCONTINUITY_REASON_SEEK) {
                            player?.let { currentPlayer ->
                                Log.d(
                                    "PlaybackLifecycle",
                                    "event=position_seek reason=$reason " +
                                        "oldItemToken=${diagnosticToken(oldPosition.mediaItem?.mediaId)} " +
                                        "newItemToken=${diagnosticToken(newPosition.mediaItem?.mediaId)} " +
                                        "oldPositionMs=${oldPosition.positionMs} newPositionMs=${newPosition.positionMs} " +
                                        "live=${currentPlayer.isCurrentMediaItemLive} " +
                                        "dynamic=${currentPlayer.isCurrentMediaItemDynamic} " +
                                        "seekable=${currentPlayer.isCurrentMediaItemSeekable} " +
                                        "playWhenReady=${currentPlayer.playWhenReady}",
                                )
                            }
                        }
                        val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                        updateDurationIfNeeded(duration)
                        updateProgress()
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            chatFragment?.updatePosition(newPosition.positionMs)
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        chatFragment?.updateSpeed(playbackParameters.speed)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && videoType == STREAM && !isLiveRewindActiveOrSwitching()) {
                            liveRecoveryState.onPlaybackStarted(
                                liveRecoveryState.currentGeneration(),
                                nowMs = SystemClock.elapsedRealtime(),
                            )
                            cancelLiveStallRecovery(resetBudget = false)
                        }
                        updateProgress()
                        if (isAdded && view != null) {
                            requireView().keepScreenOn = isPlaying && canEnterPictureInPicture()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        refreshClipAvailability()
                        logVideoTracks(
                            reason = "Media3Fragment.onTracksChanged",
                            player = player,
                        )
                        if (!tracks.isEmpty && !viewModel.loaded.value) {
                            viewModel.loaded.value = true
                            toggleSubtitles(requireContext().prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                        }
                        setSubtitlesButton()
                        if (!tracks.isEmpty) {
                            if (viewModel.qualities.isNullOrEmpty() || viewModel.updateQualities) {
                                requestQualities()
                            }
                            if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null
                                && viewModel.quality?.name != AUDIO_ONLY_QUALITY
                                && !viewModel.hidden) {
                                viewModel.quality?.let {
                                    reapplyQualityAutomatically(it, source = "tracks")
                                }
                            }
                            chatFragment?.startReplayChatLoad()
                        }
                    }

                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        if (BuildConfig.DEBUG) {
                            player?.let { logPlaybackTimeline("timeline_changed", it, reason) }
                        }
                        refreshClipAvailability()
                        val duration = player?.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0
                        updateDurationIfNeeded(duration)
                        updateProgress()
                        applyPendingAudioOnlySourceSwitch()
                        applyPendingPlaybackPrepareAfterChatOnly()
                        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                            viewModel.updateQualities = viewModel.quality?.name != AUDIO_ONLY_QUALITY
                        }
                        if (viewModel.qualities.isNullOrEmpty() || viewModel.updateQualities) {
                            requestQualities()
                        }
                        if (videoType == STREAM && !isLiveRewindActiveOrSwitching() && !vaftHandoffInProgress) {
                            val vaftEnabled = requireContext().prefs().isVaftEnabled()
                            val suppressVaft = vaftEnabled
                            val useProxy = requireContext().prefs().httpProxyHost() != null
                                    && requireContext().prefs().httpProxyPort() != null
                            if (suppressVaft || useProxy) {
                                val requestedItem = player?.currentMediaItem?.mediaId
                                player?.sendCustomCommand(
                                    SessionCommand(PlaybackService.CHECK_VAFT, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )?.let { result ->
                                    result.addListener({
                                        if (!isAdded || view == null || isLiveRewindActiveOrSwitching() || vaftHandoffInProgress ||
                                            requestedItem != player?.currentMediaItem?.mediaId) {
                                            return@addListener
                                        }
                                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                            val vaftRequired = result.get().extras.getBoolean(PlaybackService.RESULT)
                                            val oldValue = viewModel.vaftRequired
                                            viewModel.vaftRequired = vaftRequired
                                            setQualityText()
                                            if (vaftEnabled) {
                                                val extras = result.get().extras
                                                val wasAlternate = viewModel.usingAlternateStream
                                                val controlledFeed = extras.getBoolean(PlaybackService.VAFT_CONTROLLED_FEED)
                                                viewModel.controlledVaftFeed = controlledFeed
                                                viewModel.controlledVaftActive = controlledFeed && extras.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE)
                                                val windowActive = extras.getBoolean(PlaybackService.VAFT_WINDOW_ACTIVE) && !controlledFeed
                                                val alternate = extras.getBoolean(PlaybackService.VAFT_ALTERNATE_ACTIVE) && !controlledFeed
                                                viewModel.vaftWindowActive = windowActive
                                                viewModel.vaftLogicalQuality = decodePlaybackQuality(xtraModule.json, extras.getString(PlaybackService.VAFT_LOGICAL_QUALITY))
                                                if (windowActive && !viewModel.vaftQualityState.isActive) {
                                                    supersedeAutomaticRecoveryForSourceTransition()
                                                    viewModel.vaftQualityState.begin(viewModel.vaftLogicalQuality ?: viewModel.quality)
                                                }
                                                viewModel.usingAlternateStream = alternate
                                                viewModel.vaftVerifiedRendition = decodePlaybackQuality(xtraModule.json, extras.getString(PlaybackService.VAFT_VERIFIED_RENDITION))
                                                if (extras.getBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT)) suppressVaftPlayback() else restoreVaftPlayback()
                                                if (wasAlternate && !alternate) {
                                                    player?.currentMediaItem?.localConfiguration?.uri?.toString()?.let(viewModel.vaftQualityState::expectPrimaryReturn)
                                                    invalidateQualityRequest()
                                                    viewModel.updateQualities = true
                                                    requestQualities()
                                                }
                                                setQualityText()
                                            } else if (vaftRequired && !oldValue) {
                                                fallbackFromVaft(useProxy, suppressVaft)
                                            } else if (!vaftRequired) {
                                                restoreVaftPlayback()
                                            }
                                        }
                                    }, ContextCompat.getMainExecutor(requireContext()))
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(tag, "Player error", error)
                        val decoderFailure = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        if (BuildConfig.DEBUG) {
                            val controller = player
                            val videoSize = controller?.videoSize
                            val surface = view?.findViewById<SurfaceView>(R.id.playerSurface)
                            Log.w(
                                "PlaybackRecovery",
                                "event=player_error_context errorCode=${error.errorCode} " +
                                    "itemToken=${diagnosticToken(controller?.currentMediaItem?.mediaId)} " +
                                    "quality=${viewModel.confirmedVideoQuality?.name ?: viewModel.quality?.name ?: "unknown"} " +
                                    "videoWidth=${videoSize?.width ?: 0} videoHeight=${videoSize?.height ?: 0} " +
                                    "live=${controller?.isCurrentMediaItemLive} " +
                                    "dynamic=${controller?.isCurrentMediaItemDynamic} " +
                                    "seekable=${controller?.isCurrentMediaItemSeekable} " +
                                    "surfaceAttached=${surface?.isAttachedToWindow} " +
                                    "surfaceValid=${surface?.holder?.surface?.isValid} " +
                                    "isResumed=$isResumed isMinimized=${!isMaximized} " +
                                    "msSinceViewAttach=${elapsedSince(lastSurfaceViewAttachedAtMs)} " +
                                    "msSinceViewDetach=${elapsedSince(lastSurfaceViewDetachedAtMs)} " +
                                    "msSinceSurfaceCreated=${elapsedSince(lastSurfaceCreatedAtMs)} " +
                                    "msSinceSurfaceDestroyed=${elapsedSince(lastSurfaceDestroyedAtMs)} " +
                                    "msSinceOutputAttached=${elapsedSince(lastPlayerOutputAttachedAtMs)} " +
                                    "msSinceOutputDetached=${elapsedSince(lastPlayerOutputDetachedAtMs)} " +
                                    "msSinceMediaItemTransition=${elapsedSince(lastMediaItemTransitionAtMs)} " +
                                    "msSincePrepareStarted=${elapsedSince(lastPrepareStartedAtMs)}",
                            )
                            if (videoType == STREAM) {
                                Log.d(
                                    "PlaybackRecovery",
                                    "event=player_error_recovery_gate vaftWindowActive=${viewModel.vaftWindowActive} " +
                                        "vaftRequired=${viewModel.vaftRequired} " +
                                        "usingAlternateStream=${viewModel.usingAlternateStream} " +
                                        "vaftHandoffInProgress=$vaftHandoffInProgress " +
                                        "liveRewindActive=${isLiveRewindActiveOrSwitching()} " +
                                        "playWhenReady=${controller?.playWhenReady} " +
                                        "itemToken=${diagnosticToken(controller?.currentMediaItem?.mediaId)}",
                                )
                            }
                        }
                        viewModel.pendingVideoQuality = null
                        if (onLiveRewindPlaybackError(error)) return
                        if (isLiveRewindActiveOrSwitching()) return
                        if (
                            videoType == STREAM
                            && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                            && !recoveringBehindLiveWindow
                        ) {
                            recoveringBehindLiveWindow = true
                            Log.i(tag, "Recovering live stream from a behind-live-window error")
                            clearPlayerError()
                            player?.let { currentPlayer ->
                                currentPlayer.seekToDefaultPosition()
                                currentPlayer.prepare()
                            }
                            return
                        }
                        when (videoType) {
                            STREAM -> {
                                clearPlayerError()
                                val result = player?.sendCustomCommand(
                                    SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )
                                if (result == null) {
                                    scheduleStreamRecovery(trigger = "player_error", decoderFailure = decoderFailure)
                                } else {
                                    result.addListener({
                                        if (!isAdded || view == null) return@addListener
                                        val response = runCatching { result.get() }.getOrNull()
                                        if (response?.resultCode != SessionResult.RESULT_SUCCESS) {
                                            scheduleStreamRecovery(
                                                trigger = "player_error_status_unavailable",
                                                decoderFailure = decoderFailure,
                                            )
                                            return@addListener
                                        }
                                        val responseCode = response.extras.getInt(PlaybackService.RESULT)
                                        if (viewModel.useCustomProxy && responseCode >= 400 && hasValidatedInternet()) {
                                            viewModel.useCustomProxy = false
                                        }
                                        if (responseCode == 404 && hasValidatedInternet()) {
                                            verifyEndedLivePlayback(trigger = "player_error_404")
                                        } else {
                                            scheduleStreamRecovery(
                                                trigger = "player_error",
                                                decoderFailure = decoderFailure,
                                            )
                                        }
                                    }, ContextCompat.getMainExecutor(requireContext()))
                                }
                            }
                            VIDEO -> {
                                player?.sendCustomCommand(
                                    SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )?.let { result ->
                                    result.addListener({
                                        if (!isAdded || view == null) {
                                            return@addListener
                                        }
                                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                            val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                                            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                            val isNetworkAvailable = networkCapabilities != null
                                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                            if (isNetworkAvailable) {
                                                when {
                                                    viewModel.shouldRetry && responseCode != 0 -> {
                                                        viewModel.shouldRetry = false
                                                        clearPlayerError()
                                                        playVideo(true, player?.currentPosition)
                                                    }
                                                    responseCode == 403 -> {
                                                        showPlayerError(R.string.video_subscribers_only)
                                                    }
                                                    else -> {
                                                        showPlayerError(R.string.player_error) { restartPlayer() }
                                                        viewLifecycleOwner.lifecycleScope.launch {
                                                            delay(1500.milliseconds)
                                                            try {
                                                                player?.prepare()
                                                            } catch (e: Exception) {
                                                                if (e is CancellationException) throw e
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                showPlayerError(R.string.connection_error) { restartPlayer() }
                                            }
                                        }
                                    }, ContextCompat.getMainExecutor(requireContext()))
                                }
                            }
                            else -> {
                                showPlayerError(R.string.player_error) {
                                    player?.let {
                                        try {
                                            it.prepare()
                                            it.playWhenReady = true
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (videoType == STREAM && !viewModel.liveFirstFrameRendered) {
                            viewModel.liveFirstFrameRendered = true
                            renderPlaybackChrome()
                        }
                        logVideoSurfaceBinding("first_frame", controller, videoOutputView)
                        sampleRenderedSurfaceFrame(controller, videoOutputView)
                        if (liveSurfaceRestoreListener != null) finishLiveSurfaceRestore(controller)
                        else hideVideoOutputCover()
                        clearVaftFrozenFrameAfterTargetFrame(controller)
                        refreshPlayerHudLayout()
                    }
                }
                val audioOnly = viewModel.quality?.name == AUDIO_ONLY_QUALITY
                val chatOnly = viewModel.quality?.name == CHAT_ONLY_QUALITY
                val videoSuppressed = viewModel.hidden || audioOnly || chatOnly
                if (videoSuppressed) {
                    controller.trackSelectionParameters = controller.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, true)
                        .build()
                    setVideoOutputVisible(false)
                    if (chatOnly) controller.stop()
                }
                if (!videoSuppressed) {
                    setVideoOutputVisible(true)
                    attachVideoOutput(controller)
                }
                controller.addListener(listener)
                playerListener = listener
                if (controller.playbackState == Player.STATE_ENDED && !onLiveRewindPlaybackError()) {
                    verifyEndedLivePlayback(trigger = "session_attached")
                }
                if (attachingRestoredSession) {
                    // A listener added after the controller is already prepared
                    // does not receive its initial tracks callback.
                    listener.onTracksChanged(controller.currentTracks)
                }
                if (audioOnly) {
                    viewModel.quality?.let { changeQuality(it, persistSavedQuality = false) }
                }
                configureClipControl()
                refreshClipAvailability()
                restoreClipEditorIfNeeded()
                // Queue quality discovery behind the already-armed resume gate.
                // Its fresh URLs are reconciled after current-item quality is known.
                if (controller.currentMediaItem != null) {
                    requestQualities()
                }
                resumeQualityRequestId?.let { requestId ->
                    scheduleResumeQualityConfirmationTimeout(requestId, controller)
                }
                requestCurrentVideoQuality(controller) { actualQuality, sourceConfirmed ->
                    resumeQualityRequestId?.let { requestId ->
                        completeResumeQualityConfirmation(
                            requestId,
                            controller,
                            actualQuality,
                            sourceConfirmed,
                        )
                    }
                }
                tryStartPendingAudioOnly()
                if (controller.currentMediaItem != null && controller.playbackState == Player.STATE_IDLE) {
                    if (!chatOnly) controller.prepare()
                }
                if (viewModel.restoreQuality) {
                    viewModel.restoreQuality = false
                    changeQuality(viewModel.previousQuality)
                }
                player?.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.GET_SLEEP_TIMER, Bundle.EMPTY
                    ), Bundle.EMPTY
                )?.let { result ->
                    result.addListener({
                        if (!isAdded || view == null) {
                            return@addListener
                        }
                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                            val endTime = result.get().extras.getLong(PlaybackService.RESULT)
                            if (endTime > 0L) {
                                val duration = endTime - System.currentTimeMillis()
                                if (duration > 0L) {
                                    (activity as? MainActivity)?.setSleepTimer(duration)
                                } else {
                                    minimize()
                                    (activity as? MainActivity)?.closePlayer() ?: close()
                                }
                            }
                        }
                    }, ContextCompat.getMainExecutor(requireContext()))
                }
                if (viewModel.resume) {
                    viewModel.resume = false
                    player?.let { player ->
                        if (player.playbackState != Player.STATE_ENDED) {
                            player.playWhenReady = true
                        }
                    }
                }
                player?.let { player ->
                    if (viewModel.loaded.value && player.currentMediaItem == null) {
                        viewModel.started = false
                    }
                    if (viewModel.started && player.currentMediaItem != null) {
                        chatFragment?.startReplayChatLoad()
                    }
                    if (canEnterPictureInPicture()) {
                        requireView().keepScreenOn = player.isPlaying
                    }
                    if (videoType == VIDEO || videoType == CLIP || videoType == OFFLINE_VIDEO) {
                        val duration = player.duration.takeIf { it != Media3C.TIME_UNSET } ?: 0L
                        updateDurationIfNeeded(duration)
                    }
                    updateProgress()
                    renderPlaybackChrome()
                }
                if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                    startPlayer()
                }
                player?.let { player ->
                    setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun scheduleStreamRecovery(trigger: String, decoderFailure: Boolean = false) {
        if (!isStreamRecoveryWanted() || streamRecoveryJob?.isActive == true) return
        clearPlayerError()
        val attempt = liveRecoveryState.claimErrorRecovery(
            nowMs = SystemClock.elapsedRealtime(),
        ) ?: return
        queueStreamRecovery(attempt, trigger, decoderFailure)
    }

    private fun queueStreamRecovery(attempt: Int, trigger: String, decoderFailure: Boolean = false) {
        if (!isStreamRecoveryWanted() || streamRecoveryJob?.isActive == true) return
        liveStallWatchdogJob?.cancel()
        liveStallWatchdogJob = null
        val requestId = streamRecoveryRequestId
        streamRecoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            var nextAttempt = attempt
            var useDecoderFailureDelay = decoderFailure
            try {
                while (isStreamRecoveryWanted(requestId)) {
                    val delayMs = LivePlaybackStallRecoveryState.recoveryDelayMs(
                        nextAttempt,
                        decoderFailure = useDecoderFailureDelay,
                    )
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "PlaybackRecovery",
                            "event=queued cause=$trigger attempt=$nextAttempt decoderFailure=$useDecoderFailureDelay delayMs=$delayMs " +
                                "networkValidated=${hasValidatedInternet()} " +
                                "state=${player?.playbackState} playWhenReady=${player?.playWhenReady} " +
                                "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)}",
                        )
                    }
                    delay(delayMs)
                    var lastOfflineLogAtMs = 0L
                    while (isStreamRecoveryWanted(requestId) && !hasValidatedInternet()) {
                        val nowMs = SystemClock.elapsedRealtime()
                        if (BuildConfig.DEBUG && nowMs - lastOfflineLogAtMs >= NETWORK_RECOVERY_LOG_INTERVAL_MS) {
                            Log.d(
                                "PlaybackRecovery",
                                "event=waiting_for_network attempt=$nextAttempt " +
                                    "state=${player?.playbackState} playWhenReady=${player?.playWhenReady} " +
                                    "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)}",
                            )
                            lastOfflineLogAtMs = nowMs
                        }
                        delay(NETWORK_RECOVERY_POLL_MS)
                    }
                    if (!isStreamRecoveryWanted(requestId)) break

                    val result = try {
                        recoverLiveStreamAutomatically(trigger, nextAttempt, requestId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (BuildConfig.DEBUG) {
                            Log.w("PlaybackRecovery", "event=attempt_failed attempt=$nextAttempt", error)
                        }
                        AutomaticLiveRecoveryResult.RETRY
                    }
                    when (result) {
                        AutomaticLiveRecoveryResult.SOURCE_STARTED -> {
                            liveRecoveryState.finishRecoveryAttempt(sourceStarted = true)
                            updateLiveStallWatchdog(isBuffering = player?.playbackState == Player.STATE_BUFFERING)
                            return@launch
                        }
                        AutomaticLiveRecoveryResult.STREAM_ENDED -> {
                            liveRecoveryState.finishRecoveryAttempt(sourceStarted = false)
                            return@launch
                        }
                        AutomaticLiveRecoveryResult.RETRY -> {
                            liveRecoveryState.finishRecoveryAttempt(sourceStarted = false)
                            useDecoderFailureDelay = false
                            nextAttempt = liveRecoveryState.claimErrorRecovery(
                                nowMs = SystemClock.elapsedRealtime(),
                            ) ?: return@launch
                        }
                        AutomaticLiveRecoveryResult.CANCELLED -> {
                            liveRecoveryState.finishRecoveryAttempt(sourceStarted = false)
                            return@launch
                        }
                    }
                }
            } finally {
                if (streamRecoveryRequestId == requestId) streamRecoveryJob = null
            }
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val context = context ?: return false
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isStreamRecoveryWanted(requestId: Long = streamRecoveryRequestId): Boolean =
        requestId == streamRecoveryRequestId &&
            isAdded && view != null && videoType == STREAM &&
            player?.currentMediaItem != null && player?.playWhenReady == true &&
            // VAFT may own the active source during normal playback; it must not disable recovery.
            !isLiveRewindActiveOrSwitching()

    private enum class AutomaticLiveRecoveryResult {
        SOURCE_STARTED,
        RETRY,
        STREAM_ENDED,
        CANCELLED,
    }

    private fun logPlaybackTimeline(event: String, controller: Player, reason: Int = -1) {
        if (!BuildConfig.DEBUG) return
        val timeline = controller.currentTimeline
        val window = if (timeline.isEmpty) null else timeline.getWindow(0, Timeline.Window())
        val manifest = window?.manifest
        val hlsManifest = manifest as? HlsManifest
        Log.d(
            "PlaybackLifecycle",
            "event=$event reason=$reason itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                "timelineEmpty=${timeline.isEmpty} timelineWindows=${timeline.windowCount} " +
                "currentItemLive=${controller.isCurrentMediaItemLive} " +
                "currentItemDynamic=${controller.isCurrentMediaItemDynamic} " +
                "currentItemSeekable=${controller.isCurrentMediaItemSeekable} " +
                "windowPlaceholder=${window?.isPlaceholder} manifest=${manifest?.javaClass?.simpleName ?: "none"} " +
                "hlsEndTag=${hlsManifest?.mediaPlaylist?.hasEndTag ?: "n/a"} " +
                "windowLive=${window?.isLive} windowDynamic=${window?.isDynamic} " +
                "windowSeekable=${window?.isSeekable} windowDurationMs=${window?.durationMs} " +
                "windowDefaultPositionMs=${window?.defaultPositionMs ?: Media3C.TIME_UNSET} " +
                "liveTargetMs=${window?.liveConfiguration?.targetOffsetMs ?: Media3C.TIME_UNSET} " +
                "playWhenReady=${controller.playWhenReady} state=${controller.playbackState}",
        )
    }

    private fun verifyEndedLivePlayback(trigger: String) {
        val controller = player ?: return
        val mediaItem = controller.currentMediaItem ?: return
        if (!isAdded || view == null || videoType != STREAM ||
            isLiveRewindActiveOrSwitching() || !controller.playWhenReady ||
            (controller.playbackState != Player.STATE_ENDED && controller.playerError == null) ||
            endedLiveRecoveryJob?.isActive == true || streamRecoveryJob?.isActive == true
        ) {
            return
        }

        val requestGeneration = ++endedLiveRecoveryGeneration
        val sourceGeneration = liveRecoveryState.currentGeneration()
        val mediaId = mediaItem.mediaId
        val previousStream = viewModel.stream.value
        val previousStatusKnown = viewModel.streamStatusKnown.value
        if (BuildConfig.DEBUG) {
            Log.d(
                "EndedLiveRecovery",
                "event=ended trigger=$trigger generation=$requestGeneration " +
                    "itemToken=${diagnosticToken(mediaId)} playWhenReady=true " +
                    "attempts=${liveRecoveryState.endedRecoveryAttempts()}",
            )
        }
        endedLiveRecoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val status = try {
                viewModel.refreshLiveStatusNow(
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FreshLiveStatus.Unknown
            }
            if (requestGeneration != endedLiveRecoveryGeneration ||
                sourceGeneration != liveRecoveryState.currentGeneration() ||
                player !== controller ||
                controller.currentMediaItem?.mediaId != mediaId ||
                (controller.playbackState != Player.STATE_ENDED && controller.playerError == null) ||
                !controller.playWhenReady || !isAdded || view == null
            ) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "EndedLiveRecovery",
                        "event=check_finished decision=stale generation=$requestGeneration " +
                            "itemToken=${diagnosticToken(mediaId)}",
                    )
                }
                return@launch
            }

            when (status) {
                is FreshLiveStatus.Live -> {
                    val newSession = hasLiveStreamSessionChanged(
                        oldId = previousStream?.id,
                        oldCreatedAt = previousStream?.createdAt,
                        newId = status.stream.id,
                        newCreatedAt = status.stream.createdAt,
                    ) || (previousStream == null && previousStatusKnown)
                    if (newSession) liveRecoveryState.resetEndedRecoveryBudget()
                    val attempt = liveRecoveryState.claimEndedRecovery(
                        sourceGeneration = sourceGeneration,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "EndedLiveRecovery",
                            "event=check_finished status=live decision=${if (attempt == null) "budget_exhausted" else "recover"} " +
                                "generation=$requestGeneration attempt=${attempt ?: "none"} " +
                                "streamSessionToken=${diagnosticToken(status.stream.id ?: status.stream.createdAt)} " +
                                "itemToken=${diagnosticToken(mediaId)}",
                        )
                    }
                    if (attempt == null) {
                        scheduleStreamRecovery(trigger = "ended_live_retry")
                    } else {
                        queueStreamRecovery(attempt, trigger = "ended_live")
                    }
                }
                FreshLiveStatus.Offline -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "EndedLiveRecovery",
                            "event=check_finished status=offline decision=no_recovery " +
                                "generation=$requestGeneration itemToken=${diagnosticToken(mediaId)}",
                        )
                    }
                    clearPlayerError()
                    showPlayerError(R.string.stream_ended)
                }
                FreshLiveStatus.Unknown -> {
                    if (BuildConfig.DEBUG) {
                        Log.w(
                            "EndedLiveRecovery",
                            "event=check_finished status=unknown decision=wait_for_network " +
                                "generation=$requestGeneration itemToken=${diagnosticToken(mediaId)}",
                        )
                    }
                    scheduleStreamRecovery(trigger = "ended_live_status_unknown")
                }
            }
        }
    }

    private fun cancelEndedLiveRecovery(reason: String) {
        endedLiveRecoveryGeneration++
        endedLiveRecoveryJob?.cancel()
        endedLiveRecoveryJob = null
        if (BuildConfig.DEBUG) {
            Log.d("EndedLiveRecovery", "event=cancel reason=$reason generation=$endedLiveRecoveryGeneration")
        }
    }

    private fun updateLiveStallWatchdog(isBuffering: Boolean) {
        val sourceGeneration = liveRecoveryState.currentGeneration()
        val shouldWatch = liveRecoveryState.onBufferingChanged(
            sourceGeneration,
            isBuffering = isBuffering && videoType == STREAM && player?.playWhenReady == true &&
                !isLiveRewindActiveOrSwitching(),
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (!shouldWatch) {
            liveStallWatchdogJob?.cancel()
            liveStallWatchdogJob = null
            return
        }
        if (liveStallWatchdogJob?.isActive == true) return
        liveStallWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(LivePlaybackStallRecoveryState.DEFAULT_STALL_TIMEOUT_MS)
            liveStallWatchdogJob = null
            val attempt = liveRecoveryState.claimStalledRecovery(
                sourceGeneration,
                SystemClock.elapsedRealtime(),
            ) ?: return@launch
            queueStreamRecovery(attempt, trigger = "stall_watchdog")
        }
    }

    private fun cancelLiveStallRecovery(resetBudget: Boolean) {
        liveStallWatchdogJob?.cancel()
        liveStallWatchdogJob = null
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        if (resetBudget) {
            streamRecoveryRequestId++
            liveRecoveryState.beginUserGeneration()
        } else {
            liveRecoveryState.onBufferingChanged(
                liveRecoveryState.currentGeneration(),
                isBuffering = false,
                nowMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    private fun supersedeAutomaticRecoveryForSourceTransition() {
        cancelEndedLiveRecovery(reason = "source_transition")
        cancelLiveStallRecovery(resetBudget = true)
        pendingSourceSwitchQuality.clear()
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    private fun suppressVaftPlayback() {
        val wasHidden = viewModel.hidden
        viewModel.hidden = true
        player?.let { player ->
            if (viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                // VAFT verification is service-owned. Disabling its video tracks
                // here can undo the service's selection and strand the handoff.
                if (!requireContext().prefs().isVaftEnabled()) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, true).build()
                }
                setVideoOutputVisible(false)
            }
            if (!requireContext().prefs().isVaftEnabled()) player.volume = 0f
        }
        if (!wasHidden) Snackbar.make(binding.playerBackground, R.string.waiting_vaft, Snackbar.LENGTH_LONG).show()
    }

    private fun restoreVaftPlayback(quality: VideoQuality? = viewModel.quality) {
        if (viewModel.hidden) {
            viewModel.hidden = false
            player?.let { player ->
                    if (quality?.name != AUDIO_ONLY_QUALITY && quality?.name != CHAT_ONLY_QUALITY) {
                        if (!requireContext().prefs().isVaftEnabled()) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, false).build()
                        }
                        setVideoOutputVisible(true)
                    }
                    if (!requireContext().prefs().isVaftEnabled()) {
                        player.volume = requireContext().prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    }
            }
        }
    }

    private fun fallbackFromVaft(useProxy: Boolean, suppressVaft: Boolean) {
        if (viewModel.usingProxy) {
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.TOGGLE_PROXY, Bundle().apply {
                        putBoolean(PlaybackService.USING_PROXY, false)
                    }
                ), Bundle.EMPTY
            )
            viewModel.usingProxy = false
            viewModel.stopProxy = true
            return
        }
        val playlist = viewModel.quality?.url
        if (!viewModel.stopProxy && !playlist.isNullOrBlank() && useProxy) {
            player?.sendCustomCommand(
                SessionCommand(
                    PlaybackService.TOGGLE_PROXY, Bundle().apply {
                        putBoolean(PlaybackService.USING_PROXY, true)
                    }
                ), Bundle.EMPTY
            )
            viewModel.usingProxy = true
            viewLifecycleOwner.lifecycleScope.launch {
                for (i in 0 until 10) {
                    delay(10.seconds)
                    if (!viewModel.checkPlaylist(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), playlist)) {
                        break
                    }
                }
                player?.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.TOGGLE_PROXY, Bundle().apply {
                            putBoolean(PlaybackService.USING_PROXY, false)
                        }
                    ), Bundle.EMPTY
                )
                viewModel.usingProxy = false
            }
        } else if (suppressVaft) {
            suppressVaftPlayback()
        }
    }

    override fun startStream(url: String?) {
        startStreamInternal(
            url, null,
            preserveQuality = viewModel.quality != null || pendingSourceSwitchQuality.peek() != null,
        )
    }

    override fun onStreamBecameLive(eventSequence: Long?) {
        liveStatusEventGeneration++
        if (videoType == STREAM) clearPlayerError()
        val controller = player
        val state = controller?.playbackState
        val playWhenReady = controller?.playWhenReady == true
        val decision = when {
            videoType != STREAM -> "ignore_non_stream"
            controller?.currentMediaItem == null -> {
                restartPlayer()
                "start_missing_source"
            }
            state == Player.STATE_ENDED && playWhenReady -> {
                verifyEndedLivePlayback(trigger = "stream_up")
                "verify_ended"
            }
            playWhenReady && (state == Player.STATE_IDLE || controller.playerError != null) -> {
                scheduleStreamRecovery(trigger = "stream_up")
                "recover_idle"
            }
            else -> "keep_source"
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=stream_up sequence=${eventSequence ?: "none"} state=${state ?: "none"} " +
                    "playWhenReady=$playWhenReady decision=$decision " +
                    "itemToken=${diagnosticToken(controller?.currentMediaItem?.mediaId)}",
            )
        }
    }

    override fun onStreamBecameOffline(eventSequence: Long?) {
        val requestGeneration = ++liveStatusEventGeneration
        if (videoType != STREAM) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackLifecycle",
                    "event=stream_down sequence=${eventSequence ?: "none"} decision=ignore_non_stream",
                )
            }
            return
        }
        val mediaId = player?.currentMediaItem?.mediaId
        viewLifecycleOwner.lifecycleScope.launch {
            val status = viewModel.refreshLiveStatusNow(
                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            )
            val decision = when {
                requestGeneration != liveStatusEventGeneration ||
                    player?.currentMediaItem?.mediaId != mediaId -> "stale"
                status === FreshLiveStatus.Offline -> {
                    onLiveStreamWentOffline()
                    if (player?.playWhenReady == true) {
                        clearPlayerError()
                        showPlayerError(R.string.stream_ended)
                    }
                    "confirm_offline"
                }
                status is FreshLiveStatus.Live -> "retain_live"
                else -> "keep_last_known"
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackLifecycle",
                    "event=stream_down sequence=${eventSequence ?: "none"} " +
                        "status=${when (status) {
                            is FreshLiveStatus.Live -> "live"
                            FreshLiveStatus.Offline -> "offline"
                            FreshLiveStatus.Unknown -> "unknown"
                        }} decision=$decision generation=$requestGeneration " +
                        "itemToken=${diagnosticToken(mediaId)}",
                )
            }
        }
    }

    override fun onStreamQualityReset() {
        invalidateResumeQualityConfirmation("stream_quality_reset")
        clearResumeAppliedQualityTarget()
        viewModel.quality = viewModel.vaftQualityState.identityForPrimaryReturn?.toQuality()
            ?: viewModel.quality ?: pendingSourceSwitchQuality.peek()?.toQuality()
        pendingSourceSwitchQuality.capture(viewModel.quality)
        viewModel.vaftQualityState.clear()
    }

    private fun startStreamInternal(
        url: String?,
        playWhenReady: Boolean?,
        preserveQuality: Boolean = false,
        automaticRecovery: Boolean = false,
    ): ListenableFuture<SessionResult>? {
        val startupQuality = if (preserveQuality) {
            viewModel.vaftQualityState.identityForPrimaryReturn?.toQuality()
                ?: viewModel.quality ?: pendingSourceSwitchQuality.peek()?.toQuality()
        } else if (requireArguments().getBoolean(KEY_RESTORED_PLAYBACK)) {
            decodePlaybackQuality(xtraModule.json, requireArguments().getString(KEY_RESTORED_QUALITY))
        } else null
        viewModel.vaftQualityState.clear()
        if (videoType == STREAM) {
            clearPausedLivePositionForSourceReplacement(
                playbackRequested = playWhenReady ?: (player?.playWhenReady == true),
            )
            if (!preserveQuality && !requireArguments().getBoolean(KEY_RESTORED_PLAYBACK)) {
                viewModel.restoredQualityBootstrapConsumed = false
            }
            if (automaticRecovery) {
                liveRecoveryState.beginRecoveryGeneration()
            } else {
                streamRecoveryRequestId++
                cancelEndedLiveRecovery(reason = "user_source_start")
                liveRecoveryState.beginUserGeneration()
            }
        }
        clearPlayerError()
        resetProgressRenderState()
        if (preserveQuality) {
            // Another recovery can start before a catalog restores the selection.
            // Keep its pending request across every failed source attempt.
            pendingSourceSwitchQuality.capture(startupQuality)
        } else {
            pendingSourceSwitchQuality.clear()
        }
        viewModel.usingAlternateStream = false
        viewModel.controlledVaftFeed = false
        viewModel.controlledVaftActive = false
        viewModel.resetVaftController()
        viewModel.vaftRequired = false
        clearQualityCatalog()
        restoreVaftPlayback()
        viewModel.quality = null
        viewModel.pendingVideoQuality = null
        viewModel.confirmedVideoQualityMediaId = null
        viewModel.confirmedVideoQualitySourceUri = null
        if (!preserveQuality) viewModel.confirmedVideoQuality = null
        viewModel.updateQualities = true
        setQualityText()
        return sendStreamToService(url, playWhenReady, startupQuality ?: VideoQuality(startupQualityName()))
    }

    private suspend fun recoverLiveStreamAutomatically(
        trigger: String,
        attempt: Int,
        requestId: Long,
    ): AutomaticLiveRecoveryResult {
        if (!isStreamRecoveryWanted(requestId)) return AutomaticLiveRecoveryResult.CANCELLED
        val controller = player ?: return AutomaticLiveRecoveryResult.CANCELLED
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackRecovery",
                "event=begin cause=$trigger attempt=$attempt state=${controller.playbackState} " +
                    "playerErrorCode=${controller.playerError?.errorCode ?: "none"} " +
                    "networkValidated=${hasValidatedInternet()} " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
            )
        }
        val login = requireArguments().getString(KEY_CHANNEL_LOGIN)
            ?: return AutomaticLiveRecoveryResult.RETRY
        val oldQualities = viewModel.qualities
        val oldQualityCatalogIdentity = viewModel.streamQualityCatalogIdentity
        val oldQuality = viewModel.quality
        val oldUpdateQualities = viewModel.updateQualities
        val proxyUrl = requireContext().prefs().getString(C.PLAYER_PROXY_URL, "")
        val url = if (viewModel.useCustomProxy && !proxyUrl.isNullOrBlank()) {
            proxyUrl.replace("\$channel", login)
        } else {
            try {
                viewModel.loadFreshStreamPlaylistUrl(login)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (!isStreamRecoveryWanted(requestId)) return AutomaticLiveRecoveryResult.CANCELLED
        if (url.isNullOrBlank()) {
            val status = try {
                viewModel.refreshLiveStatusNow(
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = login,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FreshLiveStatus.Unknown
            }
            if (BuildConfig.DEBUG) {
                Log.w(
                    "PlaybackRecovery",
                    "event=finish cause=$trigger attempt=$attempt result=fresh_url_unavailable " +
                        "liveStatus=${when (status) {
                            is FreshLiveStatus.Live -> "live"
                            FreshLiveStatus.Offline -> "offline"
                            FreshLiveStatus.Unknown -> "unknown"
                        }} " +
                        "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
                )
            }
            if (status === FreshLiveStatus.Offline && isStreamRecoveryWanted(requestId)) {
                clearPlayerError()
                showPlayerError(R.string.stream_ended)
                return AutomaticLiveRecoveryResult.STREAM_ENDED
            }
            return AutomaticLiveRecoveryResult.RETRY
        }
        if (!isStreamRecoveryWanted(requestId)) return AutomaticLiveRecoveryResult.CANCELLED
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackRecovery",
                "event=source_start cause=$trigger attempt=$attempt " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
            )
        }
        val result = startStreamInternal(
            url = url,
            playWhenReady = true,
            preserveQuality = true,
            automaticRecovery = true,
        )
        val success = try {
            result != null && withContext(Dispatchers.IO) {
                result.get().resultCode == SessionResult.RESULT_SUCCESS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackRecovery",
                "event=finish cause=$trigger attempt=$attempt result=${if (success) "started" else "start_failed"} " +
                    "state=${player?.playbackState} playWhenReady=${player?.playWhenReady} " +
                    "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)}",
            )
        }
        if (!success) {
            restoreQualityAfterSourceSwitchFailure(
                qualities = oldQualities,
                quality = oldQuality,
                updateQualities = oldUpdateQualities,
                identity = oldQualityCatalogIdentity,
            )
            clearPlayerError()
            return AutomaticLiveRecoveryResult.RETRY
        }
        return AutomaticLiveRecoveryResult.SOURCE_STARTED
    }

    private fun sendStreamToService(url: String?, playWhenReady: Boolean? = null, startupQuality: VideoQuality): ListenableFuture<SessionResult>? {
        invalidateResumeQualityConfirmation("stream_source_start")
        clearResumeAppliedQualityTarget()
        invalidateQualityRequest()
        viewModel.playlistUrl = null
        viewModel.primaryQualityCatalogUri = null
        val requestedPlayWhenReady = playWhenReady ?: if (requireArguments().getBoolean(KEY_RESTORED_PLAYBACK)) {
            !requireArguments().getBoolean(KEY_RESTORED_PAUSED)
        } else {
            null
        }
        return player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_STREAM, Bundle().apply {
                    putString(PlaybackService.URI, url)
                    putString(PlaybackService.PLAYBACK_QUALITY, xtraModule.json.encodeToString(startupQuality))
                    putBoolean(PlaybackService.SUPPRESS_VAFT_OUTPUT, viewModel.hidden)
                    requestedPlayWhenReady?.let { putBoolean(PlaybackService.PLAY_WHEN_READY, it) }
                    putString(PlaybackService.STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                    putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                    putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                    putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                    putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                    putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                    putString(PlaybackService.THUMBNAIL, requireArguments().getString(KEY_THUMBNAIL))
                    putBoolean(PlaybackService.URL_WARM, viewModel.streamUrlWarm.value)
                    requireArguments().getLong(KEY_TAP_ELAPSED_MS, -1L).takeIf { it > 0L }?.let {
                        putLong(PlaybackService.TAP_ELAPSED_MS, it)
                    }
                    viewModel.streamUrlAvailableElapsedMs?.let {
                        putLong(PlaybackService.URL_AVAILABLE_ELAPSED_MS, it)
                    }
                    putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                    putString(PlaybackService.GAME_SLUG, requireArguments().getString(KEY_GAME_SLUG))
                    putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                    putString(PlaybackService.CREATED_AT, requireArguments().getString(KEY_STARTED_AT))
                    val viewerCount = requireArguments().getInt(KEY_VIEWER_COUNT, -1)
                    if (viewerCount >= 0) putInt(PlaybackService.VIEWER_COUNT, viewerCount)
                }
            ), Bundle.EMPTY
        )
    }

    override fun startVideo(url: String?, playbackPosition: Long?, multivariantPlaylist: Boolean) {
        val preserveRestoredQualitySnapshot =
            videoType == PlaybackContract.VIDEO &&
                multivariantPlaylist &&
                requireArguments().getBoolean(KEY_RESTORED_PLAYBACK) &&
                !requireArguments().getString(KEY_VIDEO_ID).isNullOrBlank() &&
                viewModel.qualities.isNullOrEmpty() &&
                viewModel.quality == null
        if (
            multivariantPlaylist &&
            !requireArguments().getString(KEY_VIDEO_ID).isNullOrBlank() &&
            viewModel.playlistUrl == null &&
            !url.isNullOrBlank()
        ) {
            viewModel.playlistUrl = url.toUri()
        }
        val restoredStartupQualityName = if (preserveRestoredQualitySnapshot) {
            decodePlaybackQuality(
                xtraModule.json,
                requireArguments().getString(KEY_RESTORED_QUALITY),
            )?.name
        } else {
            null
        }
        val videoId = requireArguments().getString(KEY_VIDEO_ID)
        if (BuildConfig.DEBUG && videoType == PlaybackContract.VIDEO && !videoId.isNullOrBlank()) {
            vodStartSequence++
            val reason = if (multivariantPlaylist) "playlist_result" else "preview_retry"
            val qualityName = restoredStartupQualityName ?: viewModel.quality?.name ?: "unresolved"
            Log.d(
                "PlaybackResumption",
                "VODStart seq=$vodStartSequence reason=$reason videoId=$videoId " +
                    "quality=$qualityName multivariant=$multivariantPlaylist " +
                    "restoredPreserved=$preserveRestoredQualitySnapshot",
            )
        }
        if (preserveRestoredQualitySnapshot && BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "startup_vod_quality saved=${restoredStartupQualityName ?: AUTO_QUALITY} preserved=true",
            )
        }
        startVideoInternal(
            url,
            playbackPosition,
            playWhenReady = null,
            useRestoredQualitySnapshot = preserveRestoredQualitySnapshot,
            restoredStartupQualityName = restoredStartupQualityName,
        )
    }

    private fun startVideoInternal(
        url: String?,
        playbackPosition: Long?,
        playWhenReady: Boolean?,
        useRestoredQualitySnapshot: Boolean = false,
        restoredStartupQualityName: String? = null,
    ) {
        if (videoType != STREAM) viewModel.restoredQualityBootstrapConsumed = false
        clearPlayerError()
        resetProgressRenderState()
        invalidateQualityRequest()
        player?.let { player ->
            val effectiveQualityName = restoredStartupQualityName ?: viewModel.quality?.name
            val audioOnly = effectiveQualityName == AUDIO_ONLY_QUALITY
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, audioOnly)
            }.build()
            setVideoOutputVisible(!audioOnly)
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_VIDEO, Bundle().apply {
                        putString(PlaybackService.URI, url)
                        putLong(PlaybackService.PLAYBACK_POSITION, playbackPosition ?: 0)
                        putLong(PlaybackService.VIDEO_ID, requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull() ?: 0)
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_SLUG, requireArguments().getString(KEY_GAME_SLUG))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                        putString(PlaybackService.THUMBNAIL, requireArguments().getString(KEY_THUMBNAIL))
                        putString(PlaybackService.CREATED_AT, requireArguments().getString(KEY_CREATED_AT))
                        putInt(PlaybackService.DURATION_SECONDS, requireArguments().getInt(KEY_DURATION_SECONDS))
                        putString(PlaybackService.VIDEO_TYPE, requireArguments().getString(KEY_VIDEO_TYPE))
                        putString(PlaybackService.VIDEO_ANIMATED_PREVIEW, requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW))
                        addPlaybackQualitySnapshot(useRestoredSnapshot = useRestoredQualitySnapshot)
                        if (playWhenReady != null) {
                            putBoolean(PlaybackService.PLAY_WHEN_READY, playWhenReady)
                        } else {
                            addRestoredPlayWhenReady()
                        }
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun startClip(url: String?) {
        clearPlayerError()
        resetProgressRenderState()
        invalidateQualityRequest()
        player?.let { player ->
            if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                setVideoOutputVisible(false)
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                setVideoOutputVisible(true)
            }
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_CLIP, Bundle().apply {
                        putString(PlaybackService.URI, url)
                        putString(PlaybackService.CLIP_ID, requireArguments().getString(KEY_CLIP_ID))
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.THUMBNAIL, requireArguments().getString(KEY_THUMBNAIL))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_SLUG, requireArguments().getString(KEY_GAME_SLUG))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                        putString(PlaybackService.CREATED_AT, requireArguments().getString(KEY_CREATED_AT))
                        putInt(PlaybackService.DURATION_SECONDS, requireArguments().getInt(KEY_DURATION_SECONDS))
                        putString(PlaybackService.VIDEO_ID_STRING, requireArguments().getString(KEY_VIDEO_ID))
                        putInt(PlaybackService.VIDEO_OFFSET_SECONDS, requireArguments().getInt(KEY_VIDEO_OFFSET_SECONDS, -1))
                        putString(PlaybackService.VIDEO_CREATED_AT, requireArguments().getString(KEY_VIDEO_CREATED_AT))
                        putString(PlaybackService.VIDEO_ANIMATED_PREVIEW, requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW))
                        addPlaybackQualitySnapshot()
                        addRestoredPlayWhenReady()
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun startOfflineVideo(url: String?, position: Long) {
        clearPlayerError()
        resetProgressRenderState()
        invalidateQualityRequest()
        player?.let { player ->
            if (viewModel.quality?.name == AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
                setVideoOutputVisible(false)
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                }.build()
                setVideoOutputVisible(true)
            }
            player.sendCustomCommand(
                SessionCommand(
                    PlaybackService.START_OFFLINE_VIDEO, Bundle().apply {
                        putString(PlaybackService.URI, url)
                        putInt(PlaybackService.VIDEO_ID, requireArguments().getInt(KEY_OFFLINE_VIDEO_ID))
                        putLong(PlaybackService.PLAYBACK_POSITION, position)
                        putString(PlaybackService.CHANNEL_ID, requireArguments().getString(KEY_CHANNEL_ID))
                        putString(PlaybackService.CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                        putString(PlaybackService.TITLE, requireArguments().getString(KEY_TITLE))
                        putString(PlaybackService.CHANNEL_NAME, requireArguments().getString(KEY_CHANNEL_NAME))
                        putString(PlaybackService.CHANNEL_LOGO, requireArguments().getString(KEY_CHANNEL_IMAGE))
                        putString(PlaybackService.GAME_ID, requireArguments().getString(KEY_GAME_ID))
                        putString(PlaybackService.GAME_SLUG, requireArguments().getString(KEY_GAME_SLUG))
                        putString(PlaybackService.GAME_NAME, requireArguments().getString(KEY_GAME_NAME))
                        putString(PlaybackService.THUMBNAIL, requireArguments().getString(KEY_THUMBNAIL))
                        putString(PlaybackService.CREATED_AT, requireArguments().getString(KEY_CREATED_AT))
                        putString(PlaybackService.VIDEO_CREATED_AT, requireArguments().getString(KEY_VIDEO_CREATED_AT))
                        putString(PlaybackService.CLIP_ID, requireArguments().getString(KEY_CLIP_ID))
                        addPlaybackQualitySnapshot()
                        addRestoredPlayWhenReady()
                    }
                ), Bundle.EMPTY
            )
        }
    }

    override fun getCurrentPosition() = player?.currentPosition

    override fun isPlaybackRequested(): Boolean = player?.let { currentPlayer ->
        currentPlayer.playWhenReady && currentPlayer.playbackState != Player.STATE_ENDED
    } == true

    override fun getCurrentSpeed() = player?.playbackParameters?.speed

    override fun getCurrentVolume() = player?.volume

    override fun playPause() {
        Util.handlePlayPauseButtonAction(player)
    }

    override fun rewind() {
        val controller = player ?: return
        val seekStepMs = transportSeekIncrementMs(LiveTapSeekDirection.BACKWARD)
        if (handleLiveTransportSeek(LiveTapSeekDirection.BACKWARD, seekStepMs)) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackLifecycle",
                    "event=live_transport_seek_request direction=back stepMs=$seekStepMs " +
                        "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                        "positionMs=${controller.currentPosition} playWhenReady=${controller.playWhenReady}",
                )
            }
            return
        }
        if (handleTransportSeekFallback(controller, LiveTapSeekDirection.BACKWARD, seekStepMs)) return
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=transport_seek_request direction=back " +
                    "commandAvailable=${controller.availableCommands.contains(Player.COMMAND_SEEK_BACK)} " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                    "positionMs=${controller.currentPosition} playWhenReady=${controller.playWhenReady}",
            )
        }
        controller.seekBack()
    }

    override fun fastForward() {
        val controller = player ?: return
        val seekStepMs = transportSeekIncrementMs(LiveTapSeekDirection.FORWARD)
        if (handleLiveTransportSeek(LiveTapSeekDirection.FORWARD, seekStepMs)) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackLifecycle",
                    "event=live_transport_seek_request direction=forward stepMs=$seekStepMs " +
                        "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                        "positionMs=${controller.currentPosition} playWhenReady=${controller.playWhenReady}",
                )
            }
            return
        }
        if (handleTransportSeekFallback(controller, LiveTapSeekDirection.FORWARD, seekStepMs)) return
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=transport_seek_request direction=forward " +
                    "commandAvailable=${controller.availableCommands.contains(Player.COMMAND_SEEK_FORWARD)} " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                    "positionMs=${controller.currentPosition} playWhenReady=${controller.playWhenReady}",
            )
        }
        controller.seekForward()
    }

    override fun seek(position: Long) {
        if (isLiveRewindSourceOwned()) liveRewindFallbackPositionMs = position
        player?.seekTo(position)
    }

    override fun seekToLivePosition() {
        seekToLivePosition(origin = "user_button")
    }

    override fun seekToLivePosition(origin: String) {
        val controller = player ?: return
        val stateBefore = controller.playbackState
        val mediaId = controller.currentMediaItem?.mediaId
        cancelEndedLiveRecovery(reason = "seek_to_live_$origin")
        cancelLiveStallRecovery(resetBudget = false)
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=seek_default_live action=begin origin=$origin state=$stateBefore " +
                    "playWhenReady=${controller.playWhenReady} itemToken=${diagnosticToken(mediaId)}",
            )
        }
        controller.playWhenReady = true
        controller.seekToDefaultPosition()
        val stateAfterSeek = controller.playbackState
        val prepared = controller.currentMediaItem != null &&
            (stateAfterSeek == Player.STATE_IDLE || stateAfterSeek == Player.STATE_ENDED)
        if (prepared) controller.prepare()
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackLifecycle",
                "event=seek_default_live action=complete origin=$origin stateBefore=$stateBefore " +
                    "stateAfter=${controller.playbackState} prepared=$prepared " +
                    "playWhenReady=${controller.playWhenReady} " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
            )
        }
    }

    override suspend fun startLiveRewind(vod: LiveRewindVod, positionMs: Long): Boolean {
        val preferredQuality = viewModel.quality?.let { quality ->
            quality.name?.let { SourceSwitchQualityIdentity(it, quality.codecs, quality.bitrate) }
        } ?: pendingSourceSwitchQuality.peek()
        val qualityMetadata = viewModel.qualities?.toList()
        clearLiveRewindFallbackState()
        liveRewindFallbackVod = vod
        liveRewindFallbackPositionMs = positionMs
        liveRewindFallbackPreferredQuality = preferredQuality
        liveRewindFallbackQualityMetadata = qualityMetadata

        val directRendition = liveRewindFallbackRendition()
        val directUrl = liveRewindDirectPlaylistUrl(vod.animatedPreviewUrl, directRendition)
        if (directUrl != null) {
            liveRewindPrimaryUrl = directUrl
            liveRewindPrimaryReady = false
            liveRewindFallbackStage = LiveRewindFallbackStage.NONE
            liveRewindFallbackSourceStarted = false
            val started = startLiveRewindDirectSource(
                vod = vod,
                url = directUrl,
                positionMs = positionMs,
                preferredQualityName = liveRewindDirectQualityName(directUrl),
            )
            if (started) {
                val qualityName = parseTwitchDirectVideoUrl(directUrl)?.qualityName ?: "unknown"
                Log.i("LiveRewind", "Started preview-derived direct VOD playlist quality=$qualityName")
                return true
            }
            Log.i("LiveRewind", "Direct VOD source could not start; trying the Usher playlist")
        }

        val started = startLiveRewindUsherSource(vod, positionMs)
        if (!started) clearLiveRewindFallbackState()
        return started
    }

    private suspend fun startLiveRewindDirectSource(
        vod: LiveRewindVod,
        url: String,
        positionMs: Long,
        preferredQualityName: String?,
    ): Boolean = startLiveRewindSource(vod.id, url, positionMs) {
        liveRewindFallbackSourceStarted = true
        configureLiveRewindDirectSource(url, preferredQualityName)
    }

    private fun configureLiveRewindDirectSource(url: String, preferredQualityName: String?) {
        liveRewindDirectPlaylistMode = true
        configureDirectVideoQualities(
            videoUrl = url,
            currentUri = url,
            preserveCurrentQuality = true,
            qualityMetadata = liveRewindFallbackQualityMetadata,
            preferredQualityName = preferredQualityName,
        )
        pendingSourceSwitchQuality.clear()
    }

    private suspend fun startLiveRewindUsherSource(
        vod: LiveRewindVod,
        positionMs: Long,
    ): Boolean {
        liveRewindFallbackStage = LiveRewindFallbackStage.USHER
        liveRewindPrimaryReady = false
        liveRewindFallbackSourceStarted = false
        val url = try {
            viewModel.loadRewindVideoPlaylistUrl(vod.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: return false

        if (!isAdded || view == null || liveRewindFallbackVod?.id != vod.id) return false
        liveRewindPrimaryUrl = url
        return startLiveRewindSource(vod.id, url, positionMs) {
            liveRewindFallbackSourceStarted = true
            liveRewindDirectPlaylistMode = false
        }
    }

    private suspend fun startLiveRewindSource(
        vodId: String,
        url: String,
        positionMs: Long,
        fallbackStage: LiveRewindFallbackStage = liveRewindFallbackStage,
        onSourceStarted: (() -> Unit)? = null,
    ): Boolean {
        val controller = player ?: return false
        val oldPrimaryUrl = liveRewindPrimaryUrl
        val oldPositionMs = liveRewindFallbackPositionMs
        val oldPrimaryReady = liveRewindPrimaryReady
        val oldFallbackStage = liveRewindFallbackStage
        val oldSourceStarted = liveRewindFallbackSourceStarted
        liveRewindPrimaryUrl = url
        liveRewindFallbackPositionMs = positionMs
        liveRewindPrimaryReady = false
        liveRewindFallbackStage = fallbackStage
        liveRewindFallbackSourceStarted = false
        supersedeAutomaticRecoveryForSourceTransition()
        invalidateQualityRequest()
        val oldQualities = viewModel.qualities
        val oldQualityCatalogIdentity = viewModel.streamQualityCatalogIdentity
        val oldQuality = viewModel.quality
        val oldUpdateQualities = viewModel.updateQualities
        pendingSourceSwitchQuality.capture(viewModel.quality)
        viewModel.vaftQualityState.clear()
        viewModel.playlistUrl = null
        viewModel.usingAlternateStream = false
        viewModel.controlledVaftFeed = false
        viewModel.controlledVaftActive = false
        viewModel.resetVaftController()
        viewModel.vaftRequired = false
        clearQualityCatalog()
        viewModel.quality = null
        viewModel.pendingVideoQuality = null
        viewModel.updateQualities = true
        val result = controller.sendCustomCommand(
            SessionCommand(
                PlaybackService.START_LIVE_REWIND,
                Bundle().apply {
                    putString(PlaybackService.URI, url)
                    putLong(PlaybackService.PLAYBACK_POSITION, positionMs)
                    putBoolean(PlaybackService.PLAY_WHEN_READY, controller.playWhenReady)
                    putString(PlaybackService.REWIND_VIDEO_ID, vodId)
                },
            ),
            Bundle.EMPTY,
        )
        val success = try {
            withContext(Dispatchers.IO) {
                result.get().resultCode == SessionResult.RESULT_SUCCESS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (success) {
            liveRewindFallbackSourceStarted = true
            onSourceStarted?.invoke()
            vaftHandoffInProgress = false
            restoreVaftPlayback(oldQuality)
        } else {
            liveRewindPrimaryUrl = oldPrimaryUrl
            liveRewindFallbackPositionMs = oldPositionMs
            liveRewindPrimaryReady = oldPrimaryReady
            liveRewindFallbackStage = oldFallbackStage
            liveRewindFallbackSourceStarted = oldSourceStarted
            invalidateQualityRequest()
            restoreQualityAfterSourceSwitchFailure(
                qualities = oldQualities,
                quality = oldQuality,
                updateQualities = oldUpdateQualities,
                identity = oldQualityCatalogIdentity,
            )
        }
        return success
    }

    override fun onLiveRewindPlaybackError(error: PlaybackException?): Boolean {
        val vod = liveRewindFallbackVod
        val primaryUrl = liveRewindPrimaryUrl
        if (
            error != null && vod != null &&
            isLiveRewindActiveOrSwitching()
        ) {
            if (
                !liveRewindFallbackSourceStarted && isLiveRewindSourceTransitioning() &&
                player?.currentMediaItem?.localConfiguration?.uri?.toString() != primaryUrl
            ) return true
            if (liveRewindFallbackStage == LiveRewindFallbackStage.USHER) {
                returnToLiveAfterRewindFailure()
                return true
            }
            if (liveRewindFallbackStage == LiveRewindFallbackStage.DIRECT_SOURCE) {
                if (
                    !liveRewindPrimaryReady && primaryUrl != null &&
                    isFailedLiveRewindPrimaryManifest(error, primaryUrl)
                ) {
                    val targetPositionMs = liveRewindFallbackPositionMs
                    liveRewindFallbackStage = LiveRewindFallbackStage.USHER
                    liveRewindPrimaryReady = false
                    liveRewindFallbackSourceStarted = false
                    retryLiveRewindSource(
                        vod = vod,
                        positionMs = targetPositionMs,
                        startSource = {
                            isAdded && view != null && liveRewindFallbackVod?.id == vod.id &&
                                startLiveRewindUsherSource(vod, targetPositionMs)
                        },
                        onFailure = { returnToLiveAfterRewindFailure() },
                    )
                    return true
                }
                returnToLiveAfterRewindFailure()
                return true
            }

            if (
                !liveRewindPrimaryReady && primaryUrl != null &&
                isFailedLiveRewindPrimaryManifest(error, primaryUrl)
            ) {
                val sourceUrl = liveRewindDirectPlaylistUrl(vod.animatedPreviewUrl, "chunked")
                val targetPositionMs = liveRewindFallbackPositionMs
                if (sourceUrl != null && sourceUrl != primaryUrl) {
                    liveRewindFallbackStage = LiveRewindFallbackStage.DIRECT_SOURCE
                    liveRewindPrimaryUrl = sourceUrl
                    liveRewindPrimaryReady = false
                    liveRewindFallbackSourceStarted = false
                    retryLiveRewindSource(
                        vod = vod,
                        positionMs = targetPositionMs,
                        startSource = {
                            if (!isAdded || view == null || liveRewindFallbackVod?.id != vod.id) {
                                false
                            } else {
                                val started = startLiveRewindDirectSource(
                                    vod = vod,
                                    url = sourceUrl,
                                    positionMs = targetPositionMs,
                                    preferredQualityName = liveRewindDirectQualityName(sourceUrl),
                                )
                                if (started) {
                                    if (sourceUrl != primaryUrl) {
                                        removeUnavailableDirectQuality(primaryUrl)
                                    }
                                    val qualityName = parseTwitchDirectVideoUrl(sourceUrl)?.qualityName ?: "unknown"
                                    Log.i(
                                        "LiveRewind",
                                        "Direct VOD rendition failed; started source playlist quality=$qualityName",
                                    )
                                }
                                started
                            }
                        },
                        onFailure = { returnToLiveAfterRewindFailure() },
                    )
                } else {
                    liveRewindFallbackStage = LiveRewindFallbackStage.USHER
                    liveRewindPrimaryReady = false
                    liveRewindFallbackSourceStarted = false
                    retryLiveRewindSource(
                        vod = vod,
                        positionMs = targetPositionMs,
                        startSource = {
                            isAdded && view != null && liveRewindFallbackVod?.id == vod.id &&
                                startLiveRewindUsherSource(vod, targetPositionMs)
                        },
                        onFailure = { returnToLiveAfterRewindFailure() },
                    )
                }
                return true
            }
        }
        return super.onLiveRewindPlaybackError(error)
    }

    private fun liveRewindFallbackRendition(): String {
        val preferredQuality = liveRewindFallbackPreferredQuality ?: return "chunked"
        val metadata = liveRewindFallbackQualityMetadata?.firstOrNull { quality ->
            quality.name.equals(preferredQuality.name, ignoreCase = true) &&
                (preferredQuality.codecs == null || quality.codecs.equals(preferredQuality.codecs, ignoreCase = true)) &&
                (preferredQuality.bitrate == null || quality.bitrate == preferredQuality.bitrate)
        }
        return resolveTwitchDirectRendition(preferredQuality.name, metadata?.frameRate) ?: "chunked"
    }

    private fun liveRewindDirectPlaylistUrl(animatedPreviewUrl: String?, rendition: String): String? =
        animatedPreviewUrl
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val key = if (rendition == "chunked") "source" else rendition
                TwitchApiHelper.getVideoUrlsFromPreview(it, null, listOf(rendition))[key]
            }
            ?.takeIf { parseTwitchDirectVideoUrl(it) != null }

    private fun liveRewindDirectQualityName(url: String): String? {
        val rendition = parseTwitchDirectVideoUrl(url)?.rendition ?: return null
        val preferredName = liveRewindFallbackPreferredQuality?.name
        if (rendition == "chunked" && preferredName.equals(AUTO_QUALITY, ignoreCase = true)) {
            return preferredName
        }
        if (rendition == "chunked" && preferredName.equals(SOURCE_QUALITY, ignoreCase = true)) {
            return preferredName
        }
        if (rendition != "chunked" && rendition == liveRewindFallbackRendition()) return preferredName
        return when {
            liveRewindFallbackQualityMetadata?.any {
                it.name.equals(AUTO_QUALITY, ignoreCase = true)
            } == true -> AUTO_QUALITY
            liveRewindFallbackQualityMetadata?.any {
                it.name.equals(SOURCE_QUALITY, ignoreCase = true)
            } == true -> SOURCE_QUALITY
            else -> null
        }
    }

    private fun removeUnavailableDirectQuality(url: String) {
        viewModel.qualities = viewModel.qualities?.filterNot { it.url == url }
        refreshOpenQualityDialog()
    }

    private fun isFailedLiveRewindPrimaryManifest(error: PlaybackException, primaryUrl: String): Boolean {
        if (
            player?.currentMediaItem?.localConfiguration?.uri?.toString() == primaryUrl &&
            (
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                )
        ) return true

        val expected = primaryUrl.toUri()
        return generateSequence(error as Throwable?) { it.cause }
            .take(12)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { cause ->
                (cause.responseCode == 403 || cause.responseCode == 404 || cause.responseCode == 410) &&
                    cause.dataSpec.uri.host.equals(expected.host, ignoreCase = true) &&
                    cause.dataSpec.uri.path == expected.path
            }
    }

    override suspend fun returnToLivePlayback(): Boolean {
        invalidateLiveRewindDirectQualitySwitch()
        val controller = player ?: return false
        val wasPlaying = controller.playWhenReady
        val oldQualities = viewModel.qualities
        val oldQualityCatalogIdentity = viewModel.streamQualityCatalogIdentity
        val oldQuality = viewModel.quality
        val oldUpdateQualities = viewModel.updateQualities
        val login = requireArguments().getString(KEY_CHANNEL_LOGIN) ?: run {
            logLiveReturnResult("missing_channel_login", controller)
            return false
        }
        val proxyUrl = requireContext().prefs().getString(C.PLAYER_PROXY_URL, "")
        val url = if (viewModel.useCustomProxy && !proxyUrl.isNullOrBlank()) {
            proxyUrl.replace("\$channel", login)
        } else {
            try {
                viewModel.loadFreshStreamPlaylistUrl(login)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        } ?: run {
            logLiveReturnResult("fresh_url_unavailable", controller)
            return false
        }
        val result = startStreamInternal(url, wasPlaying, preserveQuality = true) ?: run {
            logLiveReturnResult("source_start_unavailable", controller)
            restoreQualityAfterSourceSwitchFailure(
                qualities = oldQualities,
                quality = oldQuality,
                updateQualities = oldUpdateQualities,
                identity = oldQualityCatalogIdentity,
            )
            return false
        }
        val success = try {
            withContext(Dispatchers.IO) {
                result.get().resultCode == SessionResult.RESULT_SUCCESS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        logLiveReturnResult(if (success) "source_started" else "source_start_failed", controller)
        if (success) {
            clearLiveRewindFallbackState()
        } else {
            restoreQualityAfterSourceSwitchFailure(
                qualities = oldQualities,
                quality = oldQuality,
                updateQualities = oldUpdateQualities,
                identity = oldQualityCatalogIdentity,
            )
        }
        return success
    }

    private fun clearLiveRewindFallbackState() {
        invalidateLiveRewindDirectQualitySwitch()
        liveRewindFallbackVod = null
        liveRewindPrimaryUrl = null
        liveRewindFallbackPositionMs = 0L
        liveRewindPrimaryReady = false
        liveRewindFallbackStage = LiveRewindFallbackStage.NONE
        liveRewindFallbackSourceStarted = false
        liveRewindDirectPlaylistMode = false
        liveRewindFallbackPreferredQuality = null
        liveRewindFallbackQualityMetadata = null
    }

    private fun invalidateLiveRewindDirectQualitySwitch() {
        liveRewindDirectQualityGeneration++
    }

    override fun onLiveRewindReturningToLive() {
        invalidateLiveRewindDirectQualitySwitch()
    }

    private fun logLiveReturnResult(result: String, controller: MediaController) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            "LiveRewind",
            "event=return_to_live result=$result state=${controller.playbackState} " +
                "playWhenReady=${controller.playWhenReady} " +
                "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
        )
    }

    private fun restoreQualityAfterSourceSwitchFailure(
        qualities: List<VideoQuality>?,
        quality: VideoQuality?,
        updateQualities: Boolean,
        identity: StreamQualityCatalogIdentity?,
    ) {
        val item = player?.currentMediaItem
        val currentSourceUri = item?.localConfiguration?.uri?.toString()
        val identityMatchesCurrentSource = identity != null && item != null &&
            identity.mediaId == item.mediaId && identity.sourceUri == currentSourceUri
        val needsCurrentStreamCatalog = videoType == STREAM && !identityMatchesCurrentSource
        if (needsCurrentStreamCatalog) {
            clearQualityCatalog()
        } else {
            viewModel.qualities = qualities
            viewModel.streamQualityCatalogIdentity = identity
        }
        viewModel.quality = quality
        viewModel.updateQualities = updateQualities || needsCurrentStreamCatalog
        pendingSourceSwitchQuality.clear()
        viewModel.vaftQualityState.clear()
        setQualityText()
        if (needsCurrentStreamCatalog) {
            refreshOpenQualityDialog(loading = true)
            if (item != null) requestQualities()
        }
    }

    private suspend fun synchronizeLiveRewindStateBeforeSessionRestore(controller: MediaController) {
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        val generation = ++liveRewindStateSyncGeneration
        liveRewindStateSyncJob?.cancel()
        val serviceState = readLiveRewindServiceState(controller)
        if (!isCurrentLiveRewindStateSync(controller, mediaId, generation)) return
        val resolved = serviceState?.let(::applyLiveRewindServiceState) == true
        logLiveRewindStateSync("controller_connected", serviceState, resolved, controller)
        if (!resolved) {
            scheduleLiveRewindStateSyncPolling(
                controller = controller,
                mediaId = mediaId,
                generation = generation,
                reason = "controller_connected",
            )
        }
    }

    private fun requestLiveRewindStateSync(controller: MediaController, reason: String) {
        if (videoType != STREAM) return
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        beginLiveRewindStateSync()
        liveRewindStateSyncJob?.cancel()
        val generation = ++liveRewindStateSyncGeneration
        scheduleLiveRewindStateSyncPolling(controller, mediaId, generation, reason)
    }

    private fun cancelLiveRewindStateSync() {
        liveRewindStateSyncGeneration++
        liveRewindStateSyncJob?.cancel()
        liveRewindStateSyncJob = null
    }

    private fun scheduleLiveRewindStateSyncPolling(
        controller: MediaController,
        mediaId: String,
        generation: Long,
        reason: String,
    ) {
        liveRewindStateSyncJob?.cancel()
        liveRewindStateSyncJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(LIVE_REWIND_STATE_SYNC_POLL_ATTEMPTS) { attempt ->
                delay(LIVE_REWIND_STATE_SYNC_POLL_INTERVAL_MS)
                if (!isCurrentLiveRewindStateSync(controller, mediaId, generation)) return@launch
                val serviceState = readLiveRewindServiceState(controller)
                if (!isCurrentLiveRewindStateSync(controller, mediaId, generation)) return@launch
                val resolved = serviceState?.let(::applyLiveRewindServiceState) == true
                logLiveRewindStateSync(reason, serviceState, resolved, controller)
                if (resolved) return@launch
                if (attempt == LIVE_REWIND_STATE_SYNC_POLL_ATTEMPTS - 1 && BuildConfig.DEBUG) {
                    Log.w(
                        "LiveRewind",
                        "event=state_sync_unresolved attempts=${LIVE_REWIND_STATE_SYNC_POLL_ATTEMPTS} " +
                            "itemToken=${diagnosticToken(mediaId)}",
                    )
                }
            }
        }
    }

    private fun isCurrentLiveRewindStateSync(
        controller: MediaController,
        mediaId: String,
        generation: Long,
    ): Boolean = liveRewindStateSyncGeneration == generation &&
        player === controller &&
        controller.currentMediaItem?.mediaId == mediaId &&
        isAdded && view != null

    private suspend fun readLiveRewindServiceState(controller: MediaController): LiveRewindServiceState? = try {
        val result = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_LIVE_REWIND_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        ).awaitFuture()
        result.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }?.extras?.let { extras ->
            LiveRewindServiceState(
                active = extras.getBoolean(PlaybackService.LIVE_REWIND_ACTIVE),
                transitioning = extras.getBoolean(PlaybackService.LIVE_REWIND_TRANSITIONING),
                vodId = extras.getString(PlaybackService.REWIND_VIDEO_ID),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) Log.w("LiveRewind", "Failed to synchronize service rewind state", error)
        null
    }

    private fun logLiveRewindStateSync(
        reason: String,
        state: LiveRewindServiceState?,
        resolved: Boolean,
        controller: MediaController,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            "LiveRewind",
            "event=state_sync reason=$reason result=${if (state == null) "unavailable" else "received"} " +
                "active=${state?.active ?: false} transitioning=${state?.transitioning ?: false} " +
                "vodToken=${diagnosticToken(state?.vodId)} resolved=$resolved " +
                "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
        )
    }

    override suspend fun getLiveRewindVodId(): String? = player?.let { controller ->
        readLiveRewindServiceState(controller)?.takeIf { it.active }?.vodId
    }

    override fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        player?.volume = volume
    }

    override fun updateProgress() {
        val currentPlayer = player
        updateLiveBufferHealth(
            currentPlayer,
            shouldShow = videoType == PlaybackContract.STREAM &&
                !isLiveRewindActiveOrSwitching() && currentPlayer?.playWhenReady == true,
        )
        if (isLiveRewindAvailable()) {
            updateLiveRewindProgress()
            return
        }
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = player?.currentPosition ?: 0
                updatePositionTextIfNeeded(currentPosition)
                progressBar.setPosition(currentPosition)
                progressBar.setBufferedPosition(player?.bufferedPosition ?: 0)
                root.removeCallbacks(updateProgressAction)
                player?.let { player ->
                    if (player.playWhenReady && (player.isPlaying || player.playbackState == Player.STATE_BUFFERING)) {
                        val speed = player.playbackParameters.speed
                        val delay = if (speed > 0f) {
                            (progressBar.preferredUpdateDelay / speed).toLong().coerceIn(200L..1000L)
                        } else {
                            1000
                        }
                        root.postDelayed(updateProgressAction, delay)
                    }
                }
            }
        }
    }

    private fun updateLiveBufferHealth(
        currentPlayer: Player?,
        shouldShow: Boolean,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        val livePlayer = currentPlayer?.takeIf {
            shouldShow && it.playWhenReady && it.isCurrentMediaItemLive &&
                it.videoSize.width > 0 && it.videoSize.height > 0
        }
        val state = livePlayer?.playbackState
        // These endpoints are needed only for the live buffer HUD. MediaController extrapolates
        // position, so use the current window to keep its displayed live offset counting down.
        val bufferMs = livePlayer?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) }
        val currentOffsetMs = livePlayer?.let { playback ->
            val timeline = playback.currentTimeline
            val window = if (!timeline.isEmpty) {
                timeline.getWindow(playback.currentMediaItemIndex, liveBufferHealthWindow)
            } else {
                null
            }
            if (window != null && window.windowStartTimeMs != Media3C.TIME_UNSET) {
                (window.currentUnixTimeMs - window.windowStartTimeMs - playback.currentPosition).coerceAtLeast(0L)
            } else playback.currentLiveOffset.takeIf { it != Media3C.TIME_UNSET && it >= 0L }
        }
        if (livePlayer != null && state == Player.STATE_READY) {
            if (bufferMs != null && currentOffsetMs != null) {
                hasEstablishedLiveBufferHealth = true
                lastLiveBufferHealthOffsetMs = currentOffsetMs
            } else {
                hasEstablishedLiveBufferHealth = false
                lastLiveBufferHealthOffsetMs = null
            }
        }
        val allowBuffering = livePlayer != null && hasEstablishedLiveBufferHealth && state == Player.STATE_BUFFERING
        val reading = if (livePlayer != null && (state == Player.STATE_READY || allowBuffering)) {
            val offsetMs = currentOffsetMs ?: lastLiveBufferHealthOffsetMs.takeIf { allowBuffering }
            if (offsetMs != null && bufferMs != null) {
                lastLiveBufferHealthOffsetMs = currentOffsetMs ?: lastLiveBufferHealthOffsetMs
                liveBufferHealthTrend.update(bufferMs, offsetMs, nowMs)
            } else {
                liveBufferHealthTrend.update(null, null, nowMs)
            }
        } else {
            resetLiveBufferHealth()
            null
        }

        val healthView = binding.playerControls.bufferHealthGroup
        val wasVisible = healthView.isVisible
        if (reading == null) {
            if (healthView.visibility != View.GONE) healthView.visibility = View.GONE
            renderedLiveBufferHealthReading = null
        } else {
            if (healthView.visibility != View.VISIBLE) healthView.visibility = View.VISIBLE
            if (BuildConfig.DEBUG && nowMs - lastLiveBufferHealthDiagnosticMs >= 1_000L) {
                lastLiveBufferHealthDiagnosticMs = nowMs
                Log.d("XtraBufferHealth", "alternate=${viewModel.controlledVaftActive} " +
                    "positionMs=${currentPlayer?.currentPosition} bufferedPositionMs=${currentPlayer?.bufferedPosition} " +
                    "bufferMs=$bufferMs cachedBufferMs=${currentPlayer?.totalBufferedDuration} " +
                    "offsetMs=$currentOffsetMs display=${reading.bufferSeconds}/${reading.liveOffsetSeconds}")
            }
            if (reading != renderedLiveBufferHealthReading) {
                renderedLiveBufferHealthReading = reading
                val trendSuffix = if (reading.isDecreasing) "↓" else ""
                healthView.text = "${reading.bufferSeconds}s$trendSuffix / ${reading.liveOffsetSeconds}s"
                val buffered = resources.getQuantityString(
                    R.plurals.player_buffered_seconds,
                    reading.bufferSeconds,
                    reading.bufferSeconds,
                )
                val trendDescription = if (reading.isDecreasing) {
                    getString(R.string.player_buffer_decreasing)
                } else {
                    ""
                }
                val behindLive = resources.getQuantityString(
                    R.plurals.player_live_behind_seconds,
                    reading.liveOffsetSeconds,
                    reading.liveOffsetSeconds,
                )
                healthView.contentDescription = getString(
                    R.string.player_buffer_health_description,
                    buffered,
                    trendDescription,
                    behindLive,
                )
            }
        }
        if (healthView.isVisible != wasVisible) {
            binding.playerControls.root.refreshAvailabilityIfChanged()
        }
    }

    private fun resetLiveBufferHealth() {
        hasEstablishedLiveBufferHealth = false
        lastLiveBufferHealthOffsetMs = null
        liveBufferHealthTrend.reset()
        renderedLiveBufferHealthReading = null
        if (view != null) binding.playerControls.bufferHealthGroup.visibility = View.GONE
    }

    private data class PlaybackChromeState(
        val showPlayIcon: Boolean,
        val buffering: Boolean,
        val canPause: Boolean,
    )

    private fun shouldShowPlaybackPlayButton(player: Player?): Boolean {
        if (videoType == STREAM && player?.playWhenReady == true && !player.isPlaying) return false
        return Util.shouldShowPlayButton(player)
    }

    private fun updatePositionTextIfNeeded(positionMs: Long) {
        val positionSecond = positionMs / 1_000L
        if (positionSecond == renderedPositionSecond) {
            return
        }
        renderedPositionSecond = positionSecond
        val formattedPosition = DateUtils.formatElapsedTime(positionSecond)
        binding.playerControls.position.text = formattedPosition
        binding.playerControls.position.contentDescription = getString(
            R.string.player_position,
            formattedPosition,
        )
    }

    private fun updateDurationIfNeeded(durationMs: Long) {
        // Live rewind uses stream-relative positions and its own predicted edge.
        // HLS timeline refreshes must not replace that duration with the player
        // window duration, including when the rewind renderer's state is unchanged.
        if (videoType == STREAM) return
        val showTimeLabels =
            (videoType == VIDEO || videoType == CLIP || videoType == OFFLINE_VIDEO) && durationMs > 0L
        binding.playerControls.position.visibility = if (showTimeLabels) View.VISIBLE else View.GONE
        binding.playerControls.duration.visibility = if (showTimeLabels) View.VISIBLE else View.GONE
        if (durationMs == renderedDurationMs) {
            return
        }
        renderedDurationMs = durationMs
        binding.playerControls.progressBar.setDuration(durationMs)
        val formattedDuration = DateUtils.formatElapsedTime(durationMs / 1000)
        binding.playerControls.duration.text = formattedDuration
        binding.playerControls.duration.contentDescription = getString(
            R.string.player_duration,
            formattedDuration,
        )
    }

    private fun resetProgressRenderState() {
        renderedPositionSecond = Long.MIN_VALUE
        renderedDurationMs = Long.MIN_VALUE
    }

    private fun renderPlaybackChrome() {
        val player = player ?: return
        val showPlayButton = shouldShowPlaybackPlayButton(player)
        val buffering = player.playbackState == Player.STATE_BUFFERING &&
            (videoType != STREAM || !viewModel.liveFirstFrameRendered)
        val canPause = !(videoType == STREAM && !requireContext().isTelevision() && !requireContext().prefs().getBoolean(C.PLAYER_PAUSE, true))
        val state = PlaybackChromeState(
            showPlayIcon = showPlayButton,
            buffering = buffering,
            canPause = canPause,
        )
        if (state == renderedPlaybackChrome) {
            return
        }
        renderedPlaybackChrome = state
        binding.bufferingIndicator.isVisible = buffering
        binding.playerControls.playPause.contentDescription = getString(
            if (showPlayButton) R.string.player_play else R.string.player_pause_action,
        )
        if (showPlayButton) {
            binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
            binding.playerControls.playPause.visibility = View.VISIBLE
        } else {
            binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
            if (!canPause) {
                binding.playerControls.playPause.visibility = View.GONE
            }
        }
    }

    override fun toggleAudioCompressor() {
        player?.sendCustomCommand(
            SessionCommand(
                PlaybackService.TOGGLE_DYNAMICS_PROCESSING,
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (!isAdded || view == null) {
                    return@addListener
                }
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val state = result.get().extras.getBoolean(PlaybackService.RESULT)
                    if (state) {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    override fun setSubtitlesButton() {
        with(binding.playerControls) {
            val textTracks = player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            if (videoType != STREAM && textTracks != null) {
                subtitles.visibility = View.VISIBLE
                subtitles.contentDescription = getString(
                    if (textTracks.isSelected) R.string.hide_subtitles else R.string.show_subtitles,
                )
                if (textTracks.isSelected) {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on)
                    subtitles.setOnClickListener {
                        showController(force = true)
                        toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                    }
                } else {
                    subtitles.setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_off)
                    subtitles.setOnClickListener {
                        showController(force = true)
                        toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                    }
                }
                subtitles.setOnLongClickListener {
                    showController(force = true)
                    openCaptionSettings()
                    true
                }
            } else {
                subtitles.setOnClickListener(null)
                subtitles.setOnLongClickListener(null)
                subtitles.visibility = View.GONE
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(textTracks)
            binding.playerControls.root.refreshAvailability()
        }
    }

    override fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }

    override fun showPlaylistTags(mediaPlaylist: Boolean) {
        player?.sendCustomCommand(
            SessionCommand(
                if (mediaPlaylist) {
                    PlaybackService.GET_MEDIA_PLAYLIST
                } else {
                    PlaybackService.GET_MULTIVARIANT_PLAYLIST
                },
                Bundle.EMPTY
            ), Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (!isAdded || view == null) {
                    return@addListener
                }
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val tags = result.get().extras.getStringArray(PlaybackService.RESULT)?.joinToString("\n")
                    if (!tags.isNullOrBlank()) {
                        requireContext().getAlertDialogBuilder().apply {
                            setView(NestedScrollView(context).apply {
                                addView(HorizontalScrollView(context).apply {
                                    addView(TextView(context).apply {
                                        text = tags
                                        textSize = 12F
                                        setTextIsSelectable(true)
                                    })
                                })
                            })
                            setNegativeButton(R.string.copy_clip) { _, _ ->
                                val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("label", tags))
                            }
                            setPositiveButton(android.R.string.ok, null)
                        }.show()
                    }
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    override fun showVideoInfoDialog() {
        if (childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag("videoInfo") != null
        ) {
            return
        }
        VideoInfoDialogFragment().show(childFragmentManager, "videoInfo")
    }

    override fun requestVideoInfo(
        onInfo: (PlaybackVideoInfo, PlaybackVideoViewMetrics) -> Unit,
    ) {
        val currentPlayer = player ?: return
        currentPlayer.sendCustomCommand(
            SessionCommand(PlaybackService.GET_VIDEO_INFO, Bundle.EMPTY),
            Bundle.EMPTY,
        ).let { result ->
            result.addListener({
                val sessionResult = runCatching { result.get() }.getOrNull()
                if (!isAdded || view == null ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    sessionResult?.resultCode != SessionResult.RESULT_SUCCESS
                ) {
                    return@addListener
                }
                onInfo(
                    PlaybackVideoInfo.fromBundle(sessionResult.extras),
                    PlaybackVideoViewMetrics(
                        viewportWidth = videoOutputView.width.takeIf { it > 0 },
                        viewportHeight = videoOutputView.height.takeIf { it > 0 },
                        renderSurface = videoOutputView.javaClass.simpleName,
                    ),
                )
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    private fun beginResumeQualityConfirmation(controller: MediaController): Long? {
        val mediaId = controller.currentMediaItem?.mediaId ?: return null
        val generation = ++resumeQualityGeneration
        resumeQualityController = controller
        resumeQualityMediaId = mediaId
        resumeQualityConfirmationPending = true
        resumeQualityConfirmationAvailable = false
        resumeConfirmedQuality = null
        deferredAutomaticQuality = null
        resumeQualityConfirmationTimeoutJob?.cancel()
        resumeQualityConfirmationTimeoutJob = null
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "resume_quality_gate action=begin generation=$generation " +
                    "itemToken=${diagnosticToken(mediaId)}",
            )
        }
        return generation
    }

    private fun invalidateResumeQualityConfirmation(
        reason: String,
        drainDeferredQualityRequest: Boolean = false,
    ) {
        val hadResumeGate = resumeQualityController != null
        val generation = resumeQualityGeneration
        val mediaId = resumeQualityMediaId
        val shouldDrainQualityRequest = drainDeferredQualityRequest && qualityRequestDeferred
        qualityRequestDeferred = false
        resumeQualityConfirmationTimeoutJob?.cancel()
        resumeQualityConfirmationTimeoutJob = null
        resumeQualityGeneration++
        resumeQualityController = null
        resumeQualityMediaId = null
        resumeQualityConfirmationPending = false
        resumeQualityConfirmationAvailable = false
        resumeConfirmedQuality = null
        deferredAutomaticQuality = null
        if (BuildConfig.DEBUG && hadResumeGate) {
            Log.d(
                "PlaybackResumption",
                "resume_quality_gate action=invalidate reason=$reason generation=$generation " +
                    "itemToken=${diagnosticToken(mediaId)}",
            )
        }
        if (shouldDrainQualityRequest && isAdded && view != null &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            requestQualities()
        }
    }

    private fun scheduleResumeQualityConfirmationTimeout(
        generation: Long,
        controller: MediaController,
    ) {
        resumeQualityConfirmationTimeoutJob?.cancel()
        resumeQualityConfirmationTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(RESUME_QUALITY_CONFIRMATION_TIMEOUT_MS)
            if (generation != resumeQualityGeneration ||
                resumeQualityController !== controller ||
                !resumeQualityConfirmationPending
            ) {
                return@launch
            }
            if (controller.currentMediaItem?.mediaId != resumeQualityMediaId) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "PlaybackResumption",
                        "quality_confirmation action=drop_stale_timeout generation=$generation " +
                            "itemToken=${diagnosticToken(resumeQualityMediaId)}",
                    )
                }
                invalidateResumeQualityConfirmation(
                    "stale_confirmation_timeout",
                    drainDeferredQualityRequest = true,
                )
                return@launch
            }
            val deferred = deferredAutomaticQuality
            val drainQualityRequest = qualityRequestDeferred
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "quality_confirmation generation=$generation result=timeout " +
                        "itemToken=${diagnosticToken(resumeQualityMediaId)}",
                )
            }
            invalidateResumeQualityConfirmation("confirmation_timeout")
            if (drainQualityRequest) {
                deferredAutomaticQuality = deferred
                requestQualities()
            } else {
                deferred?.let { reapplyQualityAutomatically(it, source = "confirmation_timeout") }
            }
        }
    }

    private fun positionForVideoSourceReplacement(player: Player): Long {
        if (videoType == VIDEO) {
            viewModel.pendingVodStartupPositionMs?.let { return it }
        }
        return player.currentPosition
    }

    private fun installQualitySourceAtPosition(
        player: Player,
        mediaItem: MediaItem,
        position: Long,
    ) {
        if (videoType == VIDEO) {
            player.setMediaItem(mediaItem, position)
        } else {
            player.setMediaItem(mediaItem)
        }
        xtraModule.streamMedia3Runtime.setPrimaryPlaybackMediaItem(mediaItem)
        player.prepare()
        if (videoType != VIDEO) {
            player.seekTo(position)
        }
    }

    private fun clearPendingVodStartupPositionIfSettled(player: Player) {
        val pendingPosition = viewModel.pendingVodStartupPositionMs ?: return
        if (videoType != VIDEO) return

        if (vodStartupPositionsAreClose(player.currentPosition, pendingPosition)) {
            viewModel.pendingVodStartupPositionMs = null
        }
    }

    private fun vodStartupPositionsAreClose(left: Long, right: Long): Boolean {
        val distance = if (left >= right) left - right else right - left
        return distance <= VOD_START_POSITION_SETTLED_TOLERANCE_MS
    }

    private fun clearResumeAppliedQualityTarget() {
        viewModel.resumeAppliedVideoQuality = null
        viewModel.resumeAppliedVideoQualityMediaId = null
        viewModel.pendingResumeAppliedVideoQuality = null
        viewModel.pendingResumeAppliedSourceMediaId = null
    }

    private fun bindPendingResumeAppliedQuality(mediaItem: MediaItem?) {
        val target = viewModel.pendingResumeAppliedVideoQuality ?: return
        val sourceMediaId = viewModel.pendingResumeAppliedSourceMediaId
        if (mediaItem == null) {
            clearResumeAppliedQualityTarget()
            return
        }
        if (mediaItem.mediaId == sourceMediaId) return
        viewModel.resumeAppliedVideoQuality = target
        viewModel.resumeAppliedVideoQualityMediaId = mediaItem.mediaId
        viewModel.pendingResumeAppliedVideoQuality = null
        viewModel.pendingResumeAppliedSourceMediaId = null
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "resume_applied_quality itemToken=${diagnosticToken(mediaItem.mediaId)} " +
                    "targetName=${target.name} action=bound_after_source_switch",
            )
        }
    }

    private fun sameLogicalVideoQuality(left: VideoQuality, right: VideoQuality): Boolean {
        if (!left.name.equals(right.name, ignoreCase = true)) return false
        if (left.bitrate != null && right.bitrate != null && left.bitrate != right.bitrate) return false
        val leftCodecs = left.codecs?.takeIf(String::isNotBlank) ?: return true
        val rightCodecs = right.codecs?.takeIf(String::isNotBlank) ?: return true
        return leftCodecs.split(',').map(String::trim).any { wanted ->
            rightCodecs.split(',').map(String::trim).any { it.equals(wanted, ignoreCase = true) }
        }
    }

    private fun applyConfirmedResumeQualityMismatch(
        quality: VideoQuality,
        controller: MediaController,
    ) {
        val sourceMediaId = controller.currentMediaItem?.mediaId
        val currentUri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
        val willReplaceSource = !quality.url.isNullOrBlank() && currentUri != quality.url
        invalidateResumeQualityConfirmation("confirmed_quality_mismatch")
        clearResumeAppliedQualityTarget()
        if (sourceMediaId != null) {
            viewModel.pendingResumeAppliedVideoQuality = quality
            viewModel.pendingResumeAppliedSourceMediaId = sourceMediaId
        }
        changeQuality(quality, persistSavedQuality = false)
        if (!willReplaceSource && sourceMediaId != null) {
            viewModel.resumeAppliedVideoQuality = quality
            viewModel.resumeAppliedVideoQualityMediaId = sourceMediaId
            viewModel.pendingResumeAppliedVideoQuality = null
            viewModel.pendingResumeAppliedSourceMediaId = null
        } else {
            bindPendingResumeAppliedQuality(controller.currentMediaItem)
        }
    }

    private fun completeResumeQualityConfirmation(
        generation: Long,
        controller: MediaController,
        actualQuality: VideoQuality?,
        sourceConfirmed: Boolean,
    ) {
        val expectedMediaId = resumeQualityMediaId
        if (generation != resumeQualityGeneration ||
            resumeQualityController !== controller ||
            player !== controller ||
            expectedMediaId == null ||
            controller.currentMediaItem?.mediaId != expectedMediaId
        ) {
            if (generation == resumeQualityGeneration) {
                invalidateResumeQualityConfirmation(
                    "stale_confirmation",
                    drainDeferredQualityRequest = true,
                )
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "quality_confirmation action=drop_stale generation=$generation " +
                        "itemToken=${diagnosticToken(expectedMediaId)}",
                )
            }
            return
        }

        val confirmed = actualQuality.takeIf { sourceConfirmed }
        resumeQualityConfirmationPending = false
        resumeQualityConfirmationAvailable = confirmed != null
        resumeConfirmedQuality = confirmed
        resumeQualityConfirmationTimeoutJob?.cancel()
        resumeQualityConfirmationTimeoutJob = null
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "quality_confirmation generation=$generation itemToken=${diagnosticToken(expectedMediaId)} " +
                    "result=${if (confirmed != null) "success" else "unavailable"} " +
                    "confirmedName=${confirmed?.name ?: "none"}",
            )
        }

        val deferred = deferredAutomaticQuality
        val drainQualityRequest = qualityRequestDeferred
        qualityRequestDeferred = false
        if (drainQualityRequest) {
            // Prefer the refreshed rendition URLs, then reconcile once. Keep the
            // latest track callback as a fallback if discovery returns no list.
            requestQualities()
        } else {
            deferredAutomaticQuality = null
            deferred?.let { reapplyQualityAutomatically(it, source = "confirmation") }
        }
    }

    private fun reapplyQualityAutomatically(quality: VideoQuality, source: String) {
        if (viewModel.vaftQualityState.isAwaitingPrimaryReturn) {
            requestQualities()
            return
        }
        val controller = player
        val currentMediaId = controller?.currentMediaItem?.mediaId
        val appliesToCurrentResume = videoType == STREAM &&
            controller != null &&
            resumeQualityController === controller &&
            resumeQualityMediaId != null &&
            resumeQualityMediaId == currentMediaId
        if (appliesToCurrentResume && resumeQualityConfirmationPending) {
            deferredAutomaticQuality = quality
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "automatic_quality target=${quality.name} source=$source action=deferred " +
                        "generation=$resumeQualityGeneration itemToken=${diagnosticToken(currentMediaId)}",
                )
            }
            return
        }

        deferredAutomaticQuality = null

        if (appliesToCurrentResume && resumeQualityConfirmationAvailable) {
            val confirmed = resumeConfirmedQuality
            val concreteVideoQuality = quality.name != AUTO_QUALITY &&
                quality.name != SOURCE_QUALITY &&
                quality.name != AUDIO_ONLY_QUALITY &&
                quality.name != CHAT_ONLY_QUALITY
            val matches = concreteVideoQuality && confirmed != null &&
                requestedVideoQualityMatches(quality, confirmed, controller)
            val sourceReplacement = concreteVideoQuality && confirmed != null &&
                !quality.url.isNullOrBlank() &&
                controller.currentMediaItem?.localConfiguration?.uri?.toString() != quality.url
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "resume_quality_reconcile target=${quality.name} " +
                        "confirmed=${confirmed?.name ?: "none"} " +
                        "action=${when {
                            matches -> "keep_current_source"
                            concreteVideoQuality && confirmed != null && sourceReplacement -> "replace_source"
                            concreteVideoQuality && confirmed != null -> "keep_current_source"
                            else -> "apply_existing_behavior"
                        }} " +
                        "sourceReplacement=$sourceReplacement " +
                        "generation=$resumeQualityGeneration itemToken=${diagnosticToken(currentMediaId)}",
                )
            }
            if (matches) {
                changeQuality(quality, persistSavedQuality = false)
            } else if (concreteVideoQuality && confirmed != null) {
                applyConfirmedResumeQualityMismatch(quality, controller)
            } else {
                invalidateResumeQualityConfirmation("automatic_quality_reconcile")
                changeQuality(quality, persistSavedQuality = false)
            }
            return
        }

        if (appliesToCurrentResume && BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "resume_quality_reconcile target=${quality.name} confirmed=none " +
                    "action=apply_existing_behavior generation=$resumeQualityGeneration " +
                    "itemToken=${diagnosticToken(currentMediaId)}",
            )
        }
        if (appliesToCurrentResume) {
            invalidateResumeQualityConfirmation("confirmation_unavailable")
        }
        changeQuality(quality, persistSavedQuality = false)
    }

    private fun requestCurrentVideoQuality(
        controller: MediaController,
        onComplete: (VideoQuality?, Boolean) -> Unit = { _, _ -> },
    ) {
        val requestedMediaId = controller.currentMediaItem?.mediaId
        val request = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_VIDEO_QUALITY, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        request.addListener({
            val result = runCatching { request.get() }.getOrNull()
            if (!isAdded || view == null || result?.resultCode != SessionResult.RESULT_SUCCESS) {
                onComplete(null, false)
                return@addListener
            }
            val qualityUri = result.extras.getString(PlaybackService.VIDEO_QUALITY_URI)
            val currentMediaItem = controller.currentMediaItem
            val currentUri = currentMediaItem?.localConfiguration?.uri?.toString()
            if (requestedMediaId != currentMediaItem?.mediaId ||
                (qualityUri != null && qualityUri != currentUri)
            ) {
                onComplete(null, false)
                return@addListener
            }
            val name = result.extras.getString(PlaybackService.VIDEO_QUALITY_NAME)
                ?.takeIf(String::isNotBlank) ?: run {
                    onComplete(null, false)
                    return@addListener
                }
            val actualQuality = VideoQuality(
                name = name,
                codecs = result.extras.getString(PlaybackService.VIDEO_QUALITY_CODECS),
                bitrate = result.extras.getInt(PlaybackService.VIDEO_QUALITY_BITRATE)
                    .takeIf { result.extras.containsKey(PlaybackService.VIDEO_QUALITY_BITRATE) },
                frameRate = result.extras.getFloat(PlaybackService.VIDEO_QUALITY_FRAME_RATE)
                    .takeIf { result.extras.containsKey(PlaybackService.VIDEO_QUALITY_FRAME_RATE) },
            )
            val sourceConfirmed = requestedMediaId != null && qualityUri != null && qualityUri == currentUri
            updateConfirmedVideoQuality(
                actualQuality,
                controller,
                sourceConfirmed = sourceConfirmed,
            )
            onComplete(actualQuality, sourceConfirmed)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun confirmedVideoQualityForCurrentSource(): VideoQuality? {
        val currentItem = player?.currentMediaItem ?: return null
        val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return null
        return viewModel.confirmedVideoQuality?.takeIf {
            viewModel.confirmedVideoQualityMediaId == currentItem.mediaId &&
                viewModel.confirmedVideoQualitySourceUri == currentUri
        }
    }

    private fun updateConfirmedVideoQuality(
        actualQuality: VideoQuality,
        controller: MediaController,
        sourceConfirmed: Boolean,
    ) {
        val currentMediaId = controller.currentMediaItem?.mediaId
        val appliedQuality = viewModel.resumeAppliedVideoQuality
        if (sourceConfirmed &&
            currentMediaId == viewModel.resumeAppliedVideoQualityMediaId &&
            appliedQuality != null &&
            !sameLogicalVideoQuality(appliedQuality, actualQuality)
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "resume_applied_quality action=decoder_mismatch " +
                        "itemToken=${diagnosticToken(currentMediaId)} " +
                        "targetName=${appliedQuality.name} actualName=${actualQuality.name}",
                )
            }
            clearResumeAppliedQualityTarget()
        }
        viewModel.confirmedVideoQuality = actualQuality
        viewModel.confirmedVideoQualityMediaId = currentMediaId.takeIf { sourceConfirmed }
        viewModel.confirmedVideoQualitySourceUri = controller.currentMediaItem
            ?.localConfiguration?.uri?.toString()?.takeIf { sourceConfirmed }
        viewModel.pendingVideoQuality?.let { requested ->
            if (requestedVideoQualityMatches(requested, actualQuality, controller)) {
                viewModel.pendingVideoQuality = null
            }
        }
        setQualityText()
        if (viewModel.controlledVaftFeed) refreshOpenQualityDialog()
    }

    private fun hasConfirmedCurrentLiveQuality(
        requestedQuality: VideoQuality,
        currentPlayer: Player,
    ): Boolean {
        if (videoType != STREAM ||
            requestedQuality.name == AUTO_QUALITY ||
            requestedQuality.name == AUDIO_ONLY_QUALITY ||
            requestedQuality.name == CHAT_ONLY_QUALITY ||
            currentPlayer.playbackState == Player.STATE_IDLE ||
            currentPlayer.playbackState == Player.STATE_ENDED
        ) {
            return false
        }
        val currentMediaItem = currentPlayer.currentMediaItem ?: return false
        val confirmedQuality = viewModel.confirmedVideoQuality
        if (confirmedQuality != null && viewModel.confirmedVideoQualityMediaId == currentMediaItem.mediaId &&
            viewModel.confirmedVideoQualitySourceUri == currentMediaItem.localConfiguration?.uri?.toString() &&
            requestedVideoQualityMatches(requestedQuality, confirmedQuality, currentPlayer)) return true
        if (currentMediaItem.localConfiguration?.uri?.toString() == viewModel.primaryQualityCatalogUri &&
            hasUnambiguousSelectedMasterQuality(requestedQuality, currentPlayer)
        ) return true
        // The service already selected the committed VAFT rendition. Its decoder
        // callback can arrive after the quality-list response. Use the selected
        // manifest track to avoid replacing the same source a second time.
        if (!currentMediaItem.mediaId.startsWith("vaft-source:") ||
            currentPlayer.playbackState != Player.STATE_READY || requestedQuality.name == SOURCE_QUALITY) return false
        val desired = DesiredHlsQuality(requestedQuality.name ?: return false, requestedQuality.bitrate, requestedQuality.codecs)
        return currentPlayer.currentTracks.groups.any { group ->
            group.type == Media3C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                val format = group.getTrackFormat(index)
                group.isTrackSelected(index) && format.label.equals(requestedQuality.name, ignoreCase = true) &&
                    desired.matches(format)
            }
        }
    }

    private fun hasUnambiguousSelectedMasterQuality(
        requested: VideoQuality,
        currentPlayer: Player,
    ): Boolean {
        val requestedName = requested.name?.takeIf(String::isNotBlank) ?: return false
        val qualityCatalog = viewModel.qualities?.filter { !it.url.isNullOrBlank() } ?: return false
        val matchingCatalogEntries = qualityCatalog.filter { candidate ->
            candidate.name.equals(requestedName, ignoreCase = true) &&
                videoCodecFamiliesCompatible(requested.codecs, candidate.codecs) &&
                frameRatesAgree(requested.frameRate, candidate.frameRate)
        }
        val selectedFormats = currentPlayer.currentTracks.groups.asSequence()
            .filter { it.type == Media3C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSelected)
                    .map { index -> group.getTrackFormat(index) }
            }
            .toList()
        if (selectedFormats.size != 1) return false

        val selectedFormat = selectedFormats.single()
        val expectedHeight = requestedName.substringBefore('p', "").toIntOrNull()
        val selectedFrameRate = selectedFormat.frameRate.takeIf { it > 0f }
        val requestedBitrate = requested.bitrate?.takeIf { it > 0 }
        val selectedBitrate = selectedFormat.bitrate.takeIf { it > 0 }
        val selectedBitrateExceedsRequest = requestedBitrate != null && selectedBitrate != null &&
            selectedBitrate > requestedBitrate
        if (!selectedFormat.label.equals(requestedName, ignoreCase = true) ||
            (expectedHeight != null && selectedFormat.height != expectedHeight) ||
            !videoCodecFamiliesCompatible(requested.codecs, selectedFormat.codecs) ||
            !frameRatesAgree(requested.frameRate, selectedFrameRate) ||
            selectedBitrateExceedsRequest
        ) return false

        val identifiedCatalogEntry = when (matchingCatalogEntries.size) {
            1 -> matchingCatalogEntries.single()
            else -> matchingCatalogEntries.filter { candidate ->
                candidate.bitrate != null && selectedFormat.bitrate > 0 &&
                    candidate.bitrate == selectedFormat.bitrate
            }.singleOrNull()
        } ?: return false
        return identifiedCatalogEntry.url == requested.url
    }

    private fun frameRatesAgree(first: Float?, second: Float?): Boolean =
        first == null || second == null || kotlin.math.abs(first - second) < 1f

    private fun videoCodecFamiliesCompatible(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return true
        fun families(codecs: String): Set<String> = codecs.split(',')
            .map(String::trim)
            .mapNotNull { codec ->
                when {
                    codec.startsWith("avc1", ignoreCase = true) || codec.startsWith("avc3", ignoreCase = true) ->
                        codec.take(4).lowercase()
                    codec.startsWith("hvc1", ignoreCase = true) || codec.startsWith("hev1", ignoreCase = true) ->
                        codec.take(4).lowercase()
                    codec.startsWith("av01", ignoreCase = true) -> codec.take(4).lowercase()
                    codec.startsWith("vp08", ignoreCase = true) || codec.startsWith("vp09", ignoreCase = true) ->
                        codec.take(4).lowercase()
                    else -> null
                }
            }
            .toSet()
        val firstFamilies = families(first)
        val secondFamilies = families(second)
        return firstFamilies.isEmpty() || secondFamilies.isEmpty() || firstFamilies.any(secondFamilies::contains)
    }

    private fun requestedVideoQualityMatches(
        requested: VideoQuality,
        actual: VideoQuality,
        currentPlayer: Player,
    ): Boolean {
        if (requested.name == AUTO_QUALITY) return true
        if (requested.name == SOURCE_QUALITY) {
            val requestedUrl = requested.url ?: return false
            if (currentPlayer.currentMediaItem?.localConfiguration?.uri?.toString() != requestedUrl) return false
        } else if (!actual.name.equals(requested.name, ignoreCase = true)) {
            return false
        }
        if (requested.bitrate != null && actual.bitrate != null && actual.bitrate > requested.bitrate) return false
        val requestedCodecs = requested.codecs?.takeIf(String::isNotBlank) ?: return true
        val actualCodecs = actual.codecs?.takeIf(String::isNotBlank) ?: return true
        return requestedCodecs.split(',').map(String::trim).any { wanted ->
            actualCodecs.split(',').map(String::trim).any { it.equals(wanted, ignoreCase = true) }
        }
    }

    override fun changeQuality(selectedQuality: VideoQuality?, persistSavedQuality: Boolean) {
        val requestedQuality = selectedQuality
        if (videoType == STREAM && (requestedQuality?.name == AUDIO_ONLY_QUALITY ||
            requestedQuality?.name == CHAT_ONLY_QUALITY)
        ) {
            vaftEntryFrameCaptureRequestId = null
            vaftEntryFrameAwaitingAckId = null
            clearVaftEntryFrozenFrame()
        }
        val serviceOwnsVaftSource = videoType == STREAM &&
            player?.currentMediaItem?.mediaId?.startsWith(PlaybackService.VAFT_SOURCE_MEDIA_ID_PREFIX) == true
        val vaftOwnsPrimarySource = videoType == STREAM && !viewModel.controlledVaftFeed &&
            requestedQuality?.name != CHAT_ONLY_QUALITY && (
            (vaftOwnsPlayback() && !viewModel.usingAlternateStream) ||
                (!persistSavedQuality && serviceOwnsVaftSource)
            )
        if (vaftOwnsPrimarySource) {
            if (persistSavedQuality && requestedQuality != null) {
                viewModel.restoredQualityBootstrapConsumed = true
                invalidateResumeQualityConfirmation("quality_deferred_for_vaft_handoff")
                clearResumeAppliedQualityTarget()
                viewModel.vaftLogicalQuality = requestedQuality
                viewModel.vaftQualityState.rememberExplicitSelection(requestedQuality)
                viewModel.quality = requestedQuality
                viewModel.pendingVideoQuality = requestedQuality.takeUnless { it.name == AUDIO_ONLY_QUALITY }
                if (requestedQuality.name == AUDIO_ONLY_QUALITY) {
                    player?.let { controller ->
                        controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            .build()
                    }
                }
                persistPlaybackQuality(requestedQuality)
                setQualityText()
            }
            return
        }
        val currentUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (
            liveRewindDirectPlaylistMode && isLiveRewindRecording() &&
            requestedQuality?.name != CHAT_ONLY_QUALITY &&
            requestedQuality?.url != null && requestedQuality.url != currentUri &&
            parseTwitchDirectVideoUrl(requestedQuality.url) != null
        ) {
            startLiveRewindDirectQualitySwitch(requestedQuality, persistSavedQuality)
            return
        }
        val selectedQuality = requestedQuality
        if (videoType == STREAM && persistSavedQuality) {
            viewModel.restoredQualityBootstrapConsumed = true
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackResumption",
                    "user_quality target=${selectedQuality?.name ?: "none"} " +
                        "generation=$resumeQualityGeneration",
                )
            }
            invalidateResumeQualityConfirmation("explicit_quality_choice")
            clearResumeAppliedQualityTarget()
        }
        val previousQuality = viewModel.quality
        val qualityChanged = previousQuality?.let {
            it.name != selectedQuality?.name || it.url != selectedQuality?.url
        } ?: (selectedQuality != null)
        if (videoType == STREAM && persistSavedQuality && requestedQuality != null &&
            (viewModel.usingAlternateStream || viewModel.vaftQualityState.isAwaitingPrimaryReturn)
        ) {
            viewModel.vaftQualityState.rememberExplicitSelection(requestedQuality)
            viewModel.vaftLogicalQuality = requestedQuality
        }
        val currentPlayer = player
        if (BuildConfig.DEBUG) {
            Log.d(
                "VideoSurface",
                "change_quality name=${selectedQuality?.name} hasPlayer=${currentPlayer != null} " +
                    "hasMediaItem=${currentPlayer?.currentMediaItem != null}",
            )
        }
        if (videoType == STREAM && persistSavedQuality && qualityChanged) {
            cancelLiveStallRecovery(resetBudget = true)
            pendingSourceSwitchQuality.clear()
        }
        if (qualityChanged) viewModel.previousQuality = previousQuality
        when {
            selectedQuality == null || selectedQuality.name == CHAT_ONLY_QUALITY ->
                pendingPlaybackPrepareAfterChatOnly = false
            previousQuality?.name == CHAT_ONLY_QUALITY ->
                pendingPlaybackPrepareAfterChatOnly = true
        }
        viewModel.quality = selectedQuality
        viewModel.pendingVideoQuality = selectedQuality
        if (selectedQuality?.name == AUDIO_ONLY_QUALITY || selectedQuality?.name == CHAT_ONLY_QUALITY) {
            viewModel.pendingVideoQuality = null
        }
        if (videoType == STREAM && currentPlayer?.currentMediaItem == null) {
            val policyQuality = selectedQuality?.takeUnless {
                it.name == AUDIO_ONLY_QUALITY || it.name == CHAT_ONLY_QUALITY
            }
            val policyName = policyQuality?.name ?: AUTO_QUALITY
            xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
                policyName,
                policyQuality?.bitrate,
                policyQuality?.codecs,
            )
            if (BuildConfig.DEBUG) {
                Log.d(
                    "SmoothHlsQuality",
                    "live_quality_policy name=$policyName hasMediaItem=false",
                )
            }
        }
        when (selectedQuality?.name) {
            AUDIO_ONLY_QUALITY -> {
                pendingAudioOnlySourceSwitch = !selectedQuality.url.isNullOrBlank() &&
                    currentPlayer?.currentMediaItem == null
                setVideoOutputVisible(false)
                currentPlayer?.let { controller ->
                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        .build()
                    if (viewModel.usingProxy) {
                        controller.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.TOGGLE_PROXY,
                                Bundle().apply { putBoolean(PlaybackService.USING_PROXY, false) },
                            ),
                            Bundle.EMPTY,
                        )
                        viewModel.usingProxy = false
                    }
                }
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "VideoSurface",
                        "audio_only_apply hasMediaItem=${currentPlayer?.currentMediaItem != null} " +
                            "ownerBound=${videoOutputOwner.attachedPlayer() != null} " +
                            "surface=${videoOutputView.visibility}",
                    )
                }
            }
            CHAT_ONLY_QUALITY -> {
                pendingAudioOnlySourceSwitch = false
                setVideoOutputVisible(false)
                currentPlayer?.let { controller ->
                    if (viewModel.usingProxy) {
                        controller.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.TOGGLE_PROXY,
                                Bundle().apply { putBoolean(PlaybackService.USING_PROXY, false) },
                            ),
                            Bundle.EMPTY,
                        )
                        viewModel.usingProxy = false
                    }
                    controller.stop()
                }
            }
            null -> pendingAudioOnlySourceSwitch = false
            else -> {
                pendingAudioOnlySourceSwitch = false
                setVideoOutputVisible(true)
                currentPlayer?.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    .build()
            }
        }
        viewModel.quality?.let { quality ->
            player?.let { player ->
                val qualityUrl = quality.url?.takeIf { it.isNotBlank() }
                val restartUrl = when {
                    quality.name == CHAT_ONLY_QUALITY -> null
                    qualityUrl != null -> qualityUrl
                    quality.name == AUTO_QUALITY &&
                        previousQuality != null && previousQuality.name != AUTO_QUALITY ->
                        viewModel.playlistUrl?.toString()
                    else -> null
                }
                val vodWithoutControllerItem =
                    videoType == PlaybackContract.VIDEO &&
                        !restartUrl.isNullOrBlank() &&
                        player.currentMediaItem == null
                if (vodWithoutControllerItem) {
                    pendingAudioOnlySourceSwitch = false
                    val position = positionForVideoSourceReplacement(player)
                    val playWhenReady = player.playWhenReady
                    player.sendCustomCommand(
                        SessionCommand(PlaybackService.RESET_VIDEO_INFO_SIZE, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "VideoSurface",
                            "vod_quality_restart name=${quality.name} " +
                                "videoId=${requireArguments().getString(KEY_VIDEO_ID)} " +
                                "positionMs=$position playWhenReady=$playWhenReady",
                        )
                    }
                    startVideoInternal(
                        url = restartUrl,
                        playbackPosition = position,
                        playWhenReady = playWhenReady,
                    )
                } else player.currentMediaItem?.let { mediaItem ->
                    if (videoType == STREAM && (viewModel.controlledVaftFeed || isLiveRewindRecording() || viewModel.usingAlternateStream ||
                        (quality.url != null && quality.url == viewModel.vaftVerifiedRendition?.url &&
                            mediaItem.localConfiguration?.uri == viewModel.playlistUrl))) {
                        applyRecordingQuality(player, quality)
                    } else when (quality.name) {
                        AUTO_QUALITY -> {
                            xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
                                quality.name,
                                quality.bitrate,
                                quality.codecs,
                            )
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                            viewModel.playlistUrl?.let { uri ->
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                                }.build()
                                if (mediaItem.localConfiguration?.uri != uri) {
                                    val position = positionForVideoSourceReplacement(player)
                                    player.sendCustomCommand(
                                        SessionCommand(PlaybackService.RESET_VIDEO_INFO_SIZE, Bundle.EMPTY),
                                        Bundle.EMPTY,
                                    )
                                    val sourceInstance = xtraModule.streamMedia3Runtime
                                        .newSourceInstanceMediaItem(mediaItem, uri.toString())
                                    installQualitySourceAtPosition(player, sourceInstance, position)
                                }
                                if (videoType != PlaybackContract.VIDEO) {
                                    viewModel.playlistUrl = null
                                }
                            }
                            setVideoOutputVisible(true)
                        }
                        AUDIO_ONLY_QUALITY -> {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            setVideoOutputVisible(false)
                            quality.url?.let { audioUrl ->
                                pendingAudioOnlySourceSwitch = false
                                if (mediaItem.localConfiguration?.uri?.toString() != audioUrl) {
                                    val position = positionForVideoSourceReplacement(player)
                                    if (viewModel.playlistUrl == null &&
                                        viewModel.qualities?.find { it.name == AUTO_QUALITY } != null
                                    ) {
                                        viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                    }
                                    player.sendCustomCommand(
                                        SessionCommand(PlaybackService.RESET_VIDEO_INFO_SIZE, Bundle.EMPTY),
                                        Bundle.EMPTY,
                                    )
                                    val sourceInstance = xtraModule.streamMedia3Runtime
                                        .newSourceInstanceMediaItem(mediaItem, audioUrl)
                                    installQualitySourceAtPosition(player, sourceInstance, position)
                                }
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            setVideoOutputVisible(false)
                        }
                        else -> {
                            if (viewModel.qualities?.find { it.name == AUTO_QUALITY } != null) {
                                xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
                                    quality.name,
                                    quality.bitrate,
                                    quality.codecs,
                                )
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                                }.build()
                                val qualityUri = quality.url
                                player.currentMediaItem?.let { mediaItem ->
                                    val currentUri = mediaItem.localConfiguration?.uri?.toString()
                                    val uriMatches = currentUri == qualityUri
                                    val confirmedMatches = !persistSavedQuality &&
                                        currentUri != null && hasConfirmedCurrentLiveQuality(quality, player)
                                    val appliedTargetMatches = !persistSavedQuality &&
                                        viewModel.resumeAppliedVideoQualityMediaId == mediaItem.mediaId &&
                                        viewModel.resumeAppliedVideoQuality?.let {
                                            sameLogicalVideoQuality(quality, it)
                                        } == true
                                    val serviceOwnsVaftSource = mediaItem.mediaId.startsWith(
                                        PlaybackService.VAFT_SOURCE_MEDIA_ID_PREFIX,
                                    )
                                    val sourceReplacement = !qualityUri.isNullOrBlank() &&
                                        !uriMatches && !confirmedMatches && !appliedTargetMatches &&
                                        (persistSavedQuality || !serviceOwnsVaftSource)
                                    if (BuildConfig.DEBUG && videoType == STREAM && !persistSavedQuality) {
                                        Log.d(
                                            "PlaybackResumption",
                                            "event=quality_reconcile targetName=${quality.name} " +
                                                "confirmedName=${viewModel.confirmedVideoQuality?.name ?: "none"} " +
                                                "itemToken=${diagnosticToken(mediaItem.mediaId)} " +
                                                "currentUriHash=${diagnosticToken(currentUri)} " +
                                                "targetUriHash=${diagnosticToken(qualityUri)} " +
                                                "uriExactMatch=$uriMatches confirmedMatchesTarget=$confirmedMatches " +
                                                "appliedTargetMatches=$appliedTargetMatches " +
                                                "sourceReplacement=$sourceReplacement " +
                                                "reason=${when {
                                                    !sourceReplacement && confirmedMatches -> "confirmed_current_item_quality"
                                                    !persistSavedQuality && serviceOwnsVaftSource -> "vaft_source_owned_by_service"
                                                    !sourceReplacement -> "uri_match"
                                                    else -> "quality_diff_or_unconfirmed"
                                                }}",
                                        )
                                    }
                                    if (sourceReplacement) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "PlaybackResumption",
                                                "event=quality_source_replace oldItemToken=${diagnosticToken(mediaItem.mediaId)} " +
                                                    "targetName=${quality.name}",
                                            )
                                        }
                                        if (viewModel.playlistUrl == null) {
                                            viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                        }
                                        val position = positionForVideoSourceReplacement(player)
                                        player.sendCustomCommand(
                                            SessionCommand(PlaybackService.RESET_VIDEO_INFO_SIZE, Bundle.EMPTY),
                                            Bundle.EMPTY,
                                        )
                                        val sourceInstance = xtraModule.streamMedia3Runtime
                                            .newSourceInstanceMediaItem(mediaItem, qualityUri)
                                        installQualitySourceAtPosition(player, sourceInstance, position)
                                    } else if (qualityUri.isNullOrBlank()) {
                                        videoQualityTrackOverride(player.currentTracks, quality)?.let { override ->
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setOverrideForType(override)
                                                .build()
                                        }
                                    }
                                }
                                setVideoOutputVisible(true)
                            } else {
                                player.currentMediaItem?.let { mediaItem ->
                                    val currentUri = mediaItem.localConfiguration?.uri?.toString()
                                    val uriMatches = currentUri == quality.url
                                    val confirmedMatches = !persistSavedQuality &&
                                        currentUri != null && hasConfirmedCurrentLiveQuality(quality, player)
                                    val appliedTargetMatches = !persistSavedQuality &&
                                        viewModel.resumeAppliedVideoQualityMediaId == mediaItem.mediaId &&
                                        viewModel.resumeAppliedVideoQuality?.let {
                                            sameLogicalVideoQuality(quality, it)
                                        } == true
                                    val serviceOwnsVaftSource = mediaItem.mediaId.startsWith(
                                        PlaybackService.VAFT_SOURCE_MEDIA_ID_PREFIX,
                                    )
                                    val sourceReplacement = !quality.url.isNullOrBlank() &&
                                        !uriMatches && !confirmedMatches && !appliedTargetMatches &&
                                        (persistSavedQuality || !serviceOwnsVaftSource)
                                    if (BuildConfig.DEBUG && videoType == STREAM && !persistSavedQuality) {
                                        Log.d(
                                            "PlaybackResumption",
                                            "event=quality_reconcile targetName=${quality.name} " +
                                                "confirmedName=${viewModel.confirmedVideoQuality?.name ?: "none"} " +
                                                "itemToken=${diagnosticToken(mediaItem.mediaId)} " +
                                                "currentUriHash=${diagnosticToken(currentUri)} " +
                                                "targetUriHash=${diagnosticToken(quality.url)} " +
                                                "uriExactMatch=$uriMatches confirmedMatchesTarget=$confirmedMatches " +
                                                "appliedTargetMatches=$appliedTargetMatches " +
                                                "sourceReplacement=$sourceReplacement",
                                        )
                                    }
                                    if (sourceReplacement) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "PlaybackResumption",
                                                "event=quality_source_replace oldItemToken=${diagnosticToken(mediaItem.mediaId)} " +
                                                    "targetName=${quality.name}",
                                            )
                                        }
                                        val position = positionForVideoSourceReplacement(player)
                                        player.sendCustomCommand(
                                            SessionCommand(PlaybackService.RESET_VIDEO_INFO_SIZE, Bundle.EMPTY),
                                            Bundle.EMPTY,
                                        )
                                        val sourceInstance = xtraModule.streamMedia3Runtime
                                            .newSourceInstanceMediaItem(mediaItem, quality.url)
                                        installQualitySourceAtPosition(player, sourceInstance, position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                                setVideoOutputVisible(true)
                            }
                        }
                    }
                }
            }
        }
        requestedQuality?.takeIf { persistSavedQuality }?.let { quality ->
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val profile = PlayerQualityNetworkProfile.from(networkCapabilities)
            if (profile.qualityPreference(requireContext().prefs())?.substringBefore(" ") == "saved") {
                requireContext().prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
            }
        }
        applyPendingPlaybackPrepareAfterChatOnly()
        if (viewModel.hidden) suppressVaftPlayback()
        if (videoType == STREAM && persistSavedQuality && player?.isPlaying == true) {
            liveRecoveryState.onPlaybackStarted(
                liveRecoveryState.currentGeneration(),
                nowMs = SystemClock.elapsedRealtime(),
            )
        }
        persistPlaybackQuality(requestedQuality.takeIf { persistSavedQuality })
    }

    private fun startLiveRewindDirectQualitySwitch(
        quality: VideoQuality,
        persistSavedQuality: Boolean,
    ) {
        val vod = liveRewindFallbackVod ?: return
        val controller = player ?: return
        val targetUrl = quality.url?.takeIf { parseTwitchDirectVideoUrl(it) != null } ?: return
        val positionMs = controller.currentPosition.coerceAtLeast(0L)
        val previousQuality = viewModel.quality
        val generation = ++liveRewindDirectQualityGeneration
        var sourceSwitchAttempted = false

        retryLiveRewindSource(
            vod = vod,
            positionMs = positionMs,
            startSource = {
                val networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val validPlaylist = viewModel.isPlayableMediaPlaylist(networkLibrary, targetUrl)
                if (
                    generation != liveRewindDirectQualityGeneration || !liveRewindDirectPlaylistMode ||
                    liveRewindFallbackVod?.id != vod.id || player !== controller || !isAdded || view == null
                ) {
                    false
                } else if (!validPlaylist) {
                    false
                } else {
                    val switchPositionMs = controller.currentPosition.coerceAtLeast(0L)
                    updateLiveRewindTransitionPosition(switchPositionMs)
                    sourceSwitchAttempted = true
                    startLiveRewindSource(vod.id, targetUrl, switchPositionMs, LiveRewindFallbackStage.NONE) {
                        if (generation == liveRewindDirectQualityGeneration && liveRewindFallbackVod?.id == vod.id) {
                            liveRewindFallbackSourceStarted = true
                            configureDirectVideoQualities(
                                videoUrl = targetUrl,
                                currentUri = targetUrl,
                                preserveCurrentQuality = true,
                                qualityMetadata = liveRewindFallbackQualityMetadata,
                                preferredQualityName = quality.name,
                            )
                            pendingSourceSwitchQuality.clear()
                            viewModel.previousQuality = previousQuality
                            if (persistSavedQuality) {
                                viewModel.restoredQualityBootstrapConsumed = true
                                invalidateResumeQualityConfirmation("explicit_quality_choice")
                                clearResumeAppliedQualityTarget()
                                persistDirectRewindQuality(quality)
                            }
                        }
                    }
                }
            },
            onFailure = {
                if (generation == liveRewindDirectQualityGeneration) {
                    if (sourceSwitchAttempted) {
                        // The service cannot roll back a failed replacement of an active rewind.
                        returnToLiveAfterRewindFailure()
                        return@retryLiveRewindSource
                    }
                    viewModel.quality = previousQuality
                    viewModel.pendingVideoQuality = previousQuality?.takeUnless {
                        it.name == AUDIO_ONLY_QUALITY || it.name == CHAT_ONLY_QUALITY
                    }
                    setQualityText()
                    refreshOpenQualityDialog()
                    Snackbar.make(binding.playerBackground, R.string.connection_error, Snackbar.LENGTH_LONG).show()
                }
            },
            refreshReplayChat = false,
        )
    }

    private fun persistDirectRewindQuality(quality: VideoQuality) {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val profile = PlayerQualityNetworkProfile.from(networkCapabilities)
        if (profile.qualityPreference(requireContext().prefs())?.substringBefore(" ") == "saved") {
            requireContext().prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
        }
        persistPlaybackQuality(viewModel.quality)
    }

    private var controlledQualitySelections: Map<String, VideoQuality> = emptyMap()

    private fun applyRecordingQuality(controller: Player, quality: VideoQuality) {
        val selectionQuality = if (viewModel.controlledVaftFeed) controlledQualitySelections[quality.url] ?: quality else quality
        if (viewModel.controlledVaftFeed) {
            xtraModule.streamMedia3Runtime.controlledPlaylistFor(controller.currentMediaItem?.mediaId)
                ?.setAlternateQualityLimit(quality.name?.substringBefore('p')?.toIntOrNull(), quality.frameRate)
        }
        val audioOnly = quality.name == AUDIO_ONLY_QUALITY
        val chatOnly = quality.name == CHAT_ONLY_QUALITY
        xtraModule.streamMedia3Runtime.qualitySelectionPolicy.set(
            selectionQuality.name.takeUnless { audioOnly || chatOnly },
            selectionQuality.bitrate,
            selectionQuality.codecs,
        )
        val override = if (!audioOnly && !chatOnly && quality.name != AUTO_QUALITY) {
            videoQualityTrackOverride(controller.currentTracks, selectionQuality, allowExceedsCapabilities = videoType == STREAM)
        } else null
        if (BuildConfig.DEBUG && viewModel.controlledVaftFeed) {
            val tracks = controller.currentTracks.groups.asSequence()
                .filter { it.type == Media3C.TRACK_TYPE_VIDEO }
                .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it) } }
                .joinToString(prefix = "[", postfix = "]") { format ->
                    "${format.height}p${format.frameRate.toInt()}:${format.codecs?.substringBefore(',') ?: "?"}"
                }
            Log.d(
                "XtraQuality",
                "event=controlled_quality_apply requested=${quality.name} mapped=${selectionQuality.name} " +
                    "trackOverride=${override != null} tracks=$tracks",
            )
        }
        if (!audioOnly && !chatOnly && quality.name != AUTO_QUALITY && override == null) {
            viewModel.pendingVideoQuality = quality
        }
        controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon().apply {
            setTrackTypeDisabled(Media3C.TRACK_TYPE_VIDEO, audioOnly || chatOnly)
            clearOverridesOfType(Media3C.TRACK_TYPE_VIDEO)
            if (!audioOnly && !chatOnly) {
                override?.let(::setOverrideForType)
            }
        }.build()
        setVideoOutputVisible(!audioOnly && !chatOnly)
        if (BuildConfig.DEBUG) {
            Log.d(
                "PlaybackResumption",
                "event=replay_quality_in_place targetName=${quality.name} " +
                    "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)} " +
                    "positionMs=${controller.currentPosition} playWhenReady=${controller.playWhenReady}",
            )
        }
    }

    private fun persistPlaybackQuality(explicitQuality: VideoQuality? = null) {
        val controller = player ?: return
        if (videoType == STREAM && !isQualityCatalogCurrent()) return
        if (viewModel.qualities.isNullOrEmpty() || viewModel.quality == null) return
        if (!controller.isConnected) return

        controller.sendCustomCommand(
            SessionCommand(PlaybackService.SAVE_PLAYBACK_QUALITY, Bundle().apply {
                putString(PlaybackService.PLAYBACK_TYPE, videoType)
                explicitQuality?.let { putString(PlaybackService.VAFT_LOGICAL_QUALITY, xtraModule.json.encodeToString(it)) }
                putString(PlaybackService.PLAYBACK_STREAM_ID, requireArguments().getString(KEY_STREAM_ID))
                putString(PlaybackService.PLAYBACK_CHANNEL_LOGIN, requireArguments().getString(KEY_CHANNEL_LOGIN))
                putString(PlaybackService.PLAYBACK_VIDEO_ID_STRING, requireArguments().getString(KEY_VIDEO_ID))
                putString(PlaybackService.PLAYBACK_CLIP_ID_STRING, requireArguments().getString(KEY_CLIP_ID))
                putInt(
                    PlaybackService.PLAYBACK_OFFLINE_VIDEO_ID,
                    requireArguments().getInt(KEY_OFFLINE_VIDEO_ID),
                )
                addPlaybackQualitySnapshot()
            }),
            Bundle.EMPTY,
        )
    }

    private fun Bundle.addPlaybackQualitySnapshot(useRestoredSnapshot: Boolean = false) {
        if (useRestoredSnapshot) {
            putString(
                PlaybackService.PLAYBACK_QUALITIES,
                requireArguments().getString(KEY_RESTORED_QUALITIES),
            )
            putString(
                PlaybackService.PLAYBACK_QUALITY,
                requireArguments().getString(KEY_RESTORED_QUALITY),
            )
            putString(
                PlaybackService.PLAYBACK_PREVIOUS_QUALITY,
                requireArguments().getString(KEY_RESTORED_PREVIOUS_QUALITY),
            )
            putBoolean(
                PlaybackService.PLAYBACK_RESTORE_QUALITY,
                requireArguments().getBoolean(KEY_RESTORED_QUALITY_RESTORE),
            )
        } else {
            putString(
                PlaybackService.PLAYBACK_QUALITIES,
                encodePlaybackQualities(xtraModule.json, viewModel.qualities),
            )
            putString(
                PlaybackService.PLAYBACK_QUALITY,
                encodePlaybackQuality(xtraModule.json, viewModel.quality),
            )
            putString(
                PlaybackService.PLAYBACK_PREVIOUS_QUALITY,
                encodePlaybackQuality(xtraModule.json, viewModel.previousQuality),
            )
            putBoolean(PlaybackService.PLAYBACK_RESTORE_QUALITY, viewModel.restoreQuality)
        }
        val contentUrl = requireArguments().getString(KEY_URL)
        val canonicalContentUrl = if (
            videoType == PlaybackContract.VIDEO && requireArguments().getString(KEY_VIDEO_ID).isNullOrBlank()
        ) {
            contentUrl?.let(::canonicalizeTwitchDirectVideoUrl)
        } else {
            contentUrl
        }
        putString(PlaybackService.PLAYBACK_CONTENT_URL, canonicalContentUrl)
    }

    private fun Bundle.addRestoredPlayWhenReady() {
        if (requireArguments().getBoolean(KEY_RESTORED_PLAYBACK)) {
            putBoolean(
                PlaybackService.PLAY_WHEN_READY,
                !requireArguments().getBoolean(KEY_RESTORED_PAUSED),
            )
        }
    }

    private fun applyPendingPlaybackPrepareAfterChatOnly() {
        if (!pendingPlaybackPrepareAfterChatOnly) return
        if (viewModel.quality?.name == CHAT_ONLY_QUALITY) {
            pendingPlaybackPrepareAfterChatOnly = false
            return
        }
        val currentPlayer = player ?: return
        if (currentPlayer.currentMediaItem == null) return
        pendingPlaybackPrepareAfterChatOnly = false
        if (currentPlayer.playbackState == Player.STATE_IDLE) currentPlayer.prepare()
    }

    private fun applyPendingAudioOnlySourceSwitch() {
        if (!pendingAudioOnlySourceSwitch) return
        val quality = viewModel.quality?.takeIf { it.name == AUDIO_ONLY_QUALITY } ?: run {
            pendingAudioOnlySourceSwitch = false
            return
        }
        if (quality.url.isNullOrBlank() || player?.currentMediaItem == null) return
        pendingAudioOnlySourceSwitch = false
        changeQuality(quality, persistSavedQuality = false)
    }

    override fun startAudioOnly() {
        viewModel.restoredQualityBootstrapConsumed = true
        invalidateResumeQualityConfirmation("audio_only_selected")
        clearResumeAppliedQualityTarget()
        if (videoType == STREAM && viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
            cancelLiveStallRecovery(resetBudget = true)
            pendingSourceSwitchQuality.clear()
        }
        player?.let { player ->
            if (player.isConnected) {
                savePosition()
                if (viewModel.usingProxy) {
                    player.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.TOGGLE_PROXY, Bundle().apply {
                                putBoolean(PlaybackService.USING_PROXY, false)
                            }
                        ), Bundle.EMPTY
                    )
                    viewModel.usingProxy = false
                }
                if (viewModel.quality?.name != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = viewModel.qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    viewModel.quality?.let {
                        pendingAudioOnlySourceSwitch = !it.url.isNullOrBlank() &&
                            player.currentMediaItem == null
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        }.build()
                        setVideoOutputVisible(false)
                        persistPlaybackQuality()
                    }
                }
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_BACKGROUND_PLAYBACK,
                        Bundle().apply { putBoolean(PlaybackService.BACKGROUND_PLAYBACK, true) }
                    ), Bundle.EMPTY
                )
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                            putLong(PlaybackService.DURATION, (activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        }
                    ), Bundle.EMPTY
                )
            }
        }
        releaseController()
    }

    fun requestAudioOnly() {
        pendingAudioOnlyRequest = true
        tryStartPendingAudioOnly()
    }

    private fun tryStartPendingAudioOnly() {
        if (!pendingAudioOnlyRequest || player?.isConnected != true) return
        if (getQualities().isNullOrEmpty()) {
            ensureQualities(::tryStartPendingAudioOnly)
            return
        }
        pendingAudioOnlyRequest = false
        startAudioOnly()
    }

    override fun downloadVideo() {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.GET_DURATION, Bundle.EMPTY),
            Bundle.EMPTY
        )?.let { result ->
            result.addListener({
                if (!isAdded || view == null) {
                    return@addListener
                }
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val totalDuration = result.get().extras.getLong(PlaybackService.RESULT)
                    val qualities = viewModel.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newVideoInstance(
                        id = requireArguments().getString(KEY_VIDEO_ID),
                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                        channelImage = requireArguments().getString(KEY_CHANNEL_IMAGE),
                        gameId = requireArguments().getString(KEY_GAME_ID),
                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                        gameName = requireArguments().getString(KEY_GAME_NAME),
                        title = requireArguments().getString(KEY_TITLE),
                        thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                        createdAt = requireArguments().getString(KEY_CREATED_AT),
                        durationSeconds = requireArguments().getInt(KEY_DURATION_SECONDS),
                        type = requireArguments().getString(KEY_VIDEO_TYPE),
                        animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
                        totalDuration = totalDuration,
                        currentPosition = getCurrentPosition(),
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    override fun close() {
        viewModel.pendingVodStartupPositionMs = null
        releaseV2ChatSession()
        savePosition()
        val controller = player
        clipPreparationJob?.cancel()
        clipPreparationJob = null
        clipPreparationSnackbar?.dismiss()
        clipPreparationSnackbar = null
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CANCEL_LIVE_CLIP_PREPARATION, Bundle.EMPTY), Bundle.EMPTY,
        )
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CANCEL_VOD_CLIP_PREPARATION, Bundle.EMPTY), Bundle.EMPTY,
        )
        liveClipDirectoryPath?.let(::releaseLiveClip)
        liveClipDirectoryPath = null
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CLEAR_VOD_CLIP_SOURCE, Bundle.EMPTY), Bundle.EMPTY,
        )
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CLEAR_PLAYBACK_RESUMPTION, Bundle.EMPTY), Bundle.EMPTY,
        )
        controller?.pause()
        controller?.stop()
        if (controller?.mediaItemCount ?: 0 > 0) {
            controller?.removeMediaItem(0)
        }
        releaseController(controller)
    }

    private fun releaseController(controller: MediaController? = player) {
        clearForegroundVideoRestore()
        invalidateResumeQualityConfirmation("controller_released")
        qualityRequestGeneration++
        qualityRequestInFlight = false
        qualityRequestQueued = false
        streamRecoveryJob?.cancel()
        streamRecoveryJob = null
        liveStallWatchdogJob?.cancel()
        liveStallWatchdogJob = null
        liveRecoveryState.beginUserGeneration()
        recoveringBehindLiveWindow = false
        if (view != null) {
            detachVideoOutput()
        }
        playerListener?.let { controller?.removeListener(it) }
        playerListener = null
        val future = controllerFuture
        controllerFuture = null
        future?.let { MediaController.releaseFuture(it) }
    }

    override fun onLiveRewindSourceSettled() {
        qualityRequestDeferred = false
        if (liveRewindPrimaryReady) onLiveRewindPlaybackReady()
        if (liveRewindDirectPlaylistMode) {
            val controller = player
            viewModel.quality?.let { quality -> controller?.let { applyRecordingQuality(it, quality) } }
        } else {
            requestQualities()
        }
        if (isLiveRewindStateSyncPending()) {
            player?.let { requestLiveRewindStateSync(it, "rewind_source_settled") }
        }
    }

    override fun ensureQualities(onReady: () -> Unit) {
        if (getQualities().isNullOrEmpty()) {
            requestQualities(onReady)
        } else {
            onReady()
        }
    }

    private fun requestQualities(onReady: (() -> Unit)? = null) {
        onReady?.let { pendingQualityCallbacks += it }
        if (isLiveRewindSourceTransitioning()) {
            qualityRequestDeferred = true
            return
        }
        if (vaftHandoffInProgress) {
            qualityRequestDeferred = true
            return
        }
        if (resumeQualityConfirmationPending) {
            qualityRequestDeferred = true
            return
        }
        if (qualityRequestInFlight) {
            qualityRequestQueued = true
            return
        }

        val currentPlayer = player ?: run {
            // Keep the request pending until the controller is connected. A quality
            // tap during service startup must not be lost.
            return
        }
        qualityRequestInFlight = true
        val requestGeneration = qualityRequestGeneration
        val requestedPlayer = currentPlayer
        val requestedMediaId = currentPlayer.currentMediaItem?.mediaId
        val requestedSourceUri = currentPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        val initialQualityRequest = viewModel.qualities.isNullOrEmpty()
        val result = currentPlayer.sendCustomCommand(
            SessionCommand(PlaybackService.GET_QUALITIES, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        result.addListener({
            // The command future is not lifecycle-bound. A controller can be
            // released, or the fragment view can be destroyed, before the
            // service responds. Do not let an old response touch a new view.
            if (requestGeneration != qualityRequestGeneration ||
                requestedPlayer !== player ||
                !isAdded ||
                view == null
            ) {
                return@addListener
            }
            val response = runCatching { result.get() }.getOrNull()
            val currentMediaItem = requestedPlayer.currentMediaItem
            val currentMediaId = currentMediaItem?.mediaId
            val currentSourceUri = currentMediaItem?.localConfiguration?.uri?.toString()
            val responseMediaId = response?.extras?.getString(PlaybackService.QUALITIES_MEDIA_ID)
            val responseSourceUri = response?.extras?.getString(PlaybackService.QUALITIES_SOURCE_URI)
            val staleStreamCatalog = videoType == STREAM && response?.resultCode == SessionResult.RESULT_SUCCESS && (
                requestedMediaId == null || requestedSourceUri == null ||
                    requestedMediaId != currentMediaId || requestedSourceUri != currentSourceUri ||
                    responseMediaId != requestedMediaId || responseSourceUri != requestedSourceUri
            )
            if (staleStreamCatalog) {
                if (BuildConfig.DEBUG) {
                    Log.w(
                        "XtraQuality",
                        "event=quality_catalog_reject " +
                            "requestedItemToken=${diagnosticToken(requestedMediaId)} " +
                            "responseItemToken=${diagnosticToken(responseMediaId)} " +
                            "currentItemToken=${diagnosticToken(currentMediaId)} " +
                            "requestedSourceToken=${diagnosticToken(requestedSourceUri)} " +
                            "responseSourceToken=${diagnosticToken(responseSourceUri)} " +
                            "currentSourceToken=${diagnosticToken(currentSourceUri)}",
                    )
                }
                qualityRequestInFlight = false
                clearQualityCatalog()
                viewModel.updateQualities = true
                refreshOpenQualityDialog(loading = true)
                if (drainQueuedQualityRequest()) return@addListener
                scheduleQualityRequestRetry()
                return@addListener
            }
            val returningFromVaft =
                videoType == STREAM &&
                    !viewModel.usingAlternateStream &&
                    viewModel.vaftQualityState.isAwaitingPrimaryReturn
            if (response?.resultCode == SessionResult.RESULT_SUCCESS) {
                val extras = response.extras
                if (videoType == STREAM && extras.getBoolean(PlaybackService.QUALITIES_CATALOG_PRIMARY)) {
                    viewModel.primaryQualityCatalogUri = extras.getString(PlaybackService.QUALITIES_CATALOG_URI)
                }
                if (
                    returningFromVaft &&
                    !viewModel.vaftQualityState.matchesPrimaryReturn(
                        extras.getString(PlaybackService.QUALITIES_SOURCE_URI),
                    )
                ) {
                    qualityRequestInFlight = false
                    if (drainQueuedQualityRequest()) return@addListener
                    scheduleStaleQualitySourceRetry()
                    return@addListener
                }
                val names = extras.getStringArray(PlaybackService.NAMES)
                val codecs = extras.getStringArray(PlaybackService.CODECS)
                val bitrates = extras.getStringArray(PlaybackService.BITRATES)
                val frameRates = extras.getStringArray(PlaybackService.FRAME_RATES)
                val urls = extras.getStringArray(PlaybackService.URLS)
                val selectionNames = extras.getStringArray(PlaybackService.CONTROLLED_SELECTION_NAMES)
                val selectionCodecs = extras.getStringArray(PlaybackService.CONTROLLED_SELECTION_CODECS)
                controlledQualitySelections = urls?.mapIndexedNotNull { index, url ->
                    selectionNames?.getOrNull(index)?.takeUnless { it == "null" }?.let { name ->
                        url to VideoQuality(name = name, codecs = selectionCodecs?.getOrNull(index), url = url)
                    }
                }?.toMap().orEmpty()
                val list = if (names != null && codecs != null && bitrates != null && urls != null) {
                    names.mapIndexed { index, name ->
                        VideoQuality(
                            name,
                            codecs.getOrNull(index).takeIf { it != "null" },
                            bitrates.getOrNull(index).takeIf { it != "null" }?.toIntOrNull(),
                            urls.getOrNull(index),
                            frameRates?.getOrNull(index).takeIf { it != "null" }?.toFloatOrNull(),
                        )
                    }
                } else {
                    null
                }
                if (!list.isNullOrEmpty()) {
                    val catalogPrimary = extras.getBoolean(PlaybackService.QUALITIES_CATALOG_PRIMARY)
                    val catalogUri = extras.getString(PlaybackService.QUALITIES_CATALOG_URI)
                    if (videoType == STREAM && responseMediaId != null && responseSourceUri != null) {
                        viewModel.streamQualityCatalogIdentity = StreamQualityCatalogIdentity(
                            mediaId = responseMediaId,
                            sourceUri = responseSourceUri,
                            isPrimary = catalogPrimary,
                            catalogUri = catalogUri,
                        )
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "XtraQuality",
                                "event=quality_catalog_apply " +
                                    "requestedItemToken=${diagnosticToken(requestedMediaId)} " +
                                    "responseItemToken=${diagnosticToken(responseMediaId)} " +
                                    "currentItemToken=${diagnosticToken(currentMediaId)} " +
                                    "requestedSourceToken=${diagnosticToken(requestedSourceUri)} " +
                                    "responseSourceToken=${diagnosticToken(responseSourceUri)} " +
                                    "currentSourceToken=${diagnosticToken(currentSourceUri)} " +
                                    "catalogUriToken=${diagnosticToken(catalogUri)} primary=$catalogPrimary " +
                                    "count=${list.size} emittedRowsToken=${extras.getString(PlaybackService.QUALITIES_ROWS_TOKEN) ?: "none"} " +
                                    "receivedRowsToken=${qualityCatalogRowsToken(list)}",
                            )
                        }
                    } else {
                        viewModel.streamQualityCatalogIdentity = null
                    }
                    val currentSelection = viewModel.quality
                    val displayQualities = playerQualityOptions(list)
                    viewModel.qualities = displayQualities
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "XtraQuality",
                            "event=quality_picker_catalog count=${displayQualities.size} " +
                                "visibleRowsToken=${qualityCatalogRowsToken(displayQualities)}",
                        )
                    }
                    viewModel.updateQualities = false
                    // A source switch clears the UI quality while the new
                    // playlist is loading. On later refreshes, keep the
                    // current selection too, otherwise setDefaultQuality()
                    // can silently put the player back on Auto.
                    val pendingQualityToRestore = pendingSourceSwitchQuality.consume()
                    val qualityToRestore = if (returningFromVaft) {
                        viewModel.vaftQualityState.identityForPrimaryReturn
                    } else {
                        pendingQualityToRestore
                    }
                    val primaryDefaults = extras.getString(PlaybackService.CONTROLLED_PRIMARY_QUALITIES)?.let {
                        decodePlaybackQualities(xtraModule.json, it)
                    }
                    val selectionQualities = if (viewModel.controlledVaftFeed && !primaryDefaults.isNullOrEmpty()) {
                        playerQualityOptions(primaryDefaults)
                    } else displayQualities
                    val restoredQuality = when {
                        qualityToRestore != null ->
                            qualityToRestore.resolveExact(selectionQualities) ?: qualityToRestore.toQuality()
                        viewModel.controlledVaftFeed && currentSelection != null ->
                            resolvePlaybackQuality(selectionQualities, currentSelection) ?: currentSelection
                        viewModel.usingAlternateStream ->
                            resolvePlaybackQuality(viewModel.qualities, currentSelection) ?: viewModel.vaftVerifiedRendition
                        currentSelection != null ->
                            resolvePlaybackQuality(viewModel.qualities, currentSelection) ?: currentSelection
                        else -> {
                            viewModel.qualities = selectionQualities
                            try {
                                setDefaultQuality()
                            } finally {
                                viewModel.qualities = displayQualities
                            }
                            viewModel.quality
                        }
                    }
                    if (qualityToRestore != null || currentSelection != null || restoredQuality != null) {
                        viewModel.restoredQualityBootstrapConsumed = true
                    }
                    restoredQuality?.let { viewModel.quality = it }
                    if (
                        BuildConfig.DEBUG &&
                        initialQualityRequest &&
                        requireArguments().getBoolean(KEY_RESTORED_PLAYBACK)
                    ) {
                        val savedQuality = decodePlaybackQuality(
                            xtraModule.json,
                            requireArguments().getString(KEY_RESTORED_QUALITY),
                        )?.name ?: AUTO_QUALITY
                        Log.d(
                            "PlaybackResumption",
                            "quality_resolved saved=$savedQuality resolved=${restoredQuality?.name ?: "unresolved"}",
                        )
                    }
                    if (returningFromVaft) {
                        viewModel.vaftQualityState.clear()
                    }
                    changePlayerMode()
                    if (!viewModel.controlledVaftFeed || initialQualityRequest) {
                        (restoredQuality ?: viewModel.quality)?.let {
                            reapplyQualityAutomatically(it, source = "qualities")
                        }
                    }
                    setQualityText()
                    qualityRetryAttempts = 0
                    refreshOpenQualityDialog()
                    qualityRetryJob?.cancel()
                    qualityRetryJob = null
                }
            }
            qualityRequestInFlight = false
            if (!resumeQualityConfirmationPending) {
                deferredAutomaticQuality?.let { deferred ->
                    deferredAutomaticQuality = null
                    reapplyQualityAutomatically(deferred, source = "qualities_unavailable")
                }
            }
            // The service can answer before the HLS multivariant playlist is
            // available. Keep callbacks pending; onTracksChanged/onTimelineChanged
            // will retry and only a non-empty list may open the dialog.
            val hasQualities = !viewModel.qualities.isNullOrEmpty()
            if (hasQualities) {
                val callbacks = pendingQualityCallbacks.toList()
                pendingQualityCallbacks.clear()
                callbacks.forEach { it() }
            }
            val queuedRefreshStarted = drainQueuedQualityRequest()
            if (!hasQualities && !queuedRefreshStarted) {
                if (pendingQualityCallbacks.isNotEmpty() || viewModel.updateQualities) {
                    scheduleQualityRequestRetry()
                } else if (returningFromVaft) {
                    scheduleStaleQualitySourceRetry()
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun drainQueuedQualityRequest(): Boolean {
        if (!qualityRequestQueued) return false
        qualityRequestQueued = false
        qualityRetryJob?.cancel()
        qualityRetryJob = null
        if (view != null && player != null) {
            requestQualities()
            return true
        }
        return false
    }

    private fun scheduleStaleQualitySourceRetry() {
        scheduleQualityRequestRetry {
            viewModel.vaftQualityState.isAwaitingPrimaryReturn
        }
    }

    private fun scheduleQualityRequestRetry(shouldRetry: () -> Boolean = { true }) {
        if (qualityRetryAttempts >= MAX_QUALITY_RETRY_ATTEMPTS || !isAdded || view == null) return
        qualityRetryAttempts++
        qualityRetryJob?.cancel()
        qualityRetryJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(QUALITY_RETRY_DELAY_MS)
            qualityRetryJob = null
            if (view != null && player != null && shouldRetry()) {
                requestQualities()
            }
        }
    }

    private fun invalidateQualityRequest() {
        qualityRequestGeneration++
        qualityRequestInFlight = false
        qualityRequestQueued = false
        qualityRetryJob?.cancel()
        qualityRetryJob = null
        qualityRetryAttempts = 0
    }

    private fun configureClipControl(resetAvailability: Boolean = true) {
        if (view == null) return
        val supported = videoType == STREAM || videoType == VIDEO
        val blockedByRewind = videoType == STREAM && isLiveRewindActiveOrSwitching()
        val availableControl = supported && !blockedByRewind
        with(binding.playerControls.clip) {
            if (clipControlSupported != availableControl) {
                clipControlSupported = availableControl
                visibility = if (availableControl) View.VISIBLE else View.GONE
                isEnabled = false
                if (availableControl) {
                    setOnClickListener {
                        showController(force = true)
                        prepareLiveClip()
                    }
                } else {
                    setOnClickListener(null)
                }
            }
            if (resetAvailability || !availableControl) {
                if (isEnabled) isEnabled = false
                clipStatusGeneration++
                clipStatusRequestInFlight = false
                clipStatusQueued = false
            }
        }
    }

    private fun refreshClipAvailability(resetAvailability: Boolean = false) {
        if (view == null) return
        configureClipControl(resetAvailability)
        if ((videoType != STREAM && videoType != VIDEO) || isLiveRewindActiveOrSwitching()) return
        val controller = player ?: return
        if (clipStatusRequestInFlight) {
            clipStatusQueued = true
            return
        }
        clipStatusRequestInFlight = true
        val generation = ++clipStatusGeneration
        val result = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_CLIP_STATUS, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        result.addListener({
            if (generation != clipStatusGeneration || player !== controller || view == null) return@addListener
            clipStatusRequestInFlight = false
            val response = runCatching { result.get() }.getOrNull()
            val available = response?.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }?.extras?.let { extras ->
                if (videoType == STREAM) extras.getBoolean(PlaybackService.LIVE_CLIP_AVAILABLE)
                else extras.getBoolean(PlaybackService.VOD_CLIP_AVAILABLE)
            } == true
            if (binding.playerControls.clip.isEnabled != available) {
                binding.playerControls.clip.isEnabled = available
            }
            if (clipStatusQueued) {
                clipStatusQueued = false
                refreshClipAvailability()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun prepareLiveClip() {
        if (videoType == VIDEO) {
            openVodClipEditor()
            return
        }
        val controller = player ?: return
        if (videoType != STREAM || isLiveRewindActiveOrSwitching() || clipPreparationJob?.isActive == true ||
            childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) != null
        ) return
        binding.playerControls.clip.isEnabled = false
        clipPreparationSnackbar?.dismiss()
        clipPreparationSnackbar = Snackbar.make(
            binding.playerBackground,
            R.string.clip_editor_exporting,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.cancel) {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.CANCEL_LIVE_CLIP_PREPARATION, Bundle.EMPTY), Bundle.EMPTY,
            )
            clipPreparationJob?.cancel()
        }.also { it.show() }
        clipPreparationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = controller.sendCustomCommand(
                    SessionCommand(PlaybackService.PREPARE_LIVE_CLIP, Bundle.EMPTY), Bundle.EMPTY,
                ).awaitFuture()
                check(result.resultCode == SessionResult.RESULT_SUCCESS) { "Live clip preparation failed" }
                val directory = result.extras.getString(PlaybackService.CLIP_DIRECTORY)
                    ?: error("Prepared clip directory is missing")
                val playlist = result.extras.getString(PlaybackService.CLIP_PLAYLIST)
                    ?: error("Prepared clip playlist is missing")
                val boundaries = result.extras.getLongArray(PlaybackService.CLIP_BOUNDARIES_US)
                    ?: error("Prepared clip timeline is missing")
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
                if (view == null || !isAdded) {
                    releaseLiveClip(directory)
                } else {
                    openClipEditor(directory, playlist, boundaries)
                }
            } catch (_: CancellationException) {
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
            } catch (_: Throwable) {
                clipPreparationSnackbar?.dismiss()
                clipPreparationSnackbar = null
                if (view != null) {
                    Snackbar.make(binding.playerBackground, R.string.player_clip_prepare_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                clipPreparationJob = null
                refreshClipAvailability()
            }
        }
    }

    private fun openVodClipEditor() {
        if (childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) != null || !canOpenClipEditor()) return
        val controller = player ?: return
        val request = controller.sendCustomCommand(
            SessionCommand(PlaybackService.GET_VOD_CLIP_DESCRIPTOR, Bundle.EMPTY), Bundle.EMPTY,
        )
        request.addListener({
            if (player !== controller || view == null || !isAdded) return@addListener
            val response = runCatching { request.get() }.getOrNull()
            if (response?.resultCode != SessionResult.RESULT_SUCCESS || !readVodClipDescriptor(response.extras)) {
                Snackbar.make(binding.playerBackground, R.string.player_clip_prepare_failed, Snackbar.LENGTH_LONG).show()
                return@addListener
            }
            showVodClipEditor(response.extras)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun readVodClipDescriptor(extras: Bundle): Boolean {
        val mediaItemId = extras.getString(PlaybackService.VOD_CLIP_MEDIA_ITEM_ID) ?: return false
        val durations = extras.getIntArray(PlaybackService.VOD_CLIP_SEGMENT_DURATIONS_US) ?: return false
        val ranges = extras.getLongArray(PlaybackService.VOD_CLIP_SEGMENT_BYTE_RANGES) ?: return false
        if (durations.isEmpty() || ranges.size != durations.size) return false
        vodClipMediaItemId = mediaItemId
        vodClipSegmentDurationsUs = durations
        vodClipSegmentByteRanges = ranges
        vodClipBitrate = extras.getInt(PlaybackService.VOD_CLIP_BITRATE)
            .takeIf { extras.containsKey(PlaybackService.VOD_CLIP_BITRATE) }
        return true
    }

    private fun showVodClipEditor(extras: Bundle) {
        if (!canOpenClipEditor()) return
        val previewUri = extras.getString(PlaybackService.VOD_CLIP_PREVIEW_URI) ?: return
        playbackPositionBeforeClipEditor = player?.currentPosition
        playbackBeforeClipEditor = player?.playWhenReady == true
        vodClipEditorOpen = true
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        binding.clipEditorContainer.visibility = View.VISIBLE
        scheduleClipEditorCoverFallback()
        pauseLiveClipPlayback()
        try {
            childFragmentManager.beginTransaction()
                .replace(
                    R.id.clipEditorContainer,
                    ClipEditorDialogFragment.newVodInstance(
                        previewUri = previewUri,
                        segmentDurationsUs = vodClipSegmentDurationsUs,
                        initialPositionUs = extras.getLong(PlaybackService.VOD_CLIP_INITIAL_POSITION_US),
                        bitrateBitsPerSecond = vodClipBitrate,
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                    ),
                    CLIP_EDITOR_TAG,
                )
                .commitNow()
        } catch (_: IllegalStateException) {
            binding.clipEditorContainer.visibility = View.GONE
            clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
            clipEditorCoverTimeout = null
            restoreLiveClipPlayback()
        }
    }

    private fun openClipEditor(directory: String, playlist: String, boundariesUs: LongArray) {
        if (childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) != null || !canOpenClipEditor()) {
            releaseLiveClip(directory)
            return
        }
        livePlaybackBeforeClipEditor = player?.playWhenReady == true
        liveClipDirectoryPath = directory
        vodClipEditorOpen = false
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        binding.clipEditorContainer.visibility = View.VISIBLE
        scheduleClipEditorCoverFallback()
        pauseLiveClipPlayback()
        try {
            childFragmentManager.beginTransaction()
                .replace(
                    R.id.clipEditorContainer,
                    ClipEditorDialogFragment.newInstance(
                        playlistPath = playlist,
                        directoryPath = directory,
                        boundariesUs = boundariesUs,
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                    ),
                    CLIP_EDITOR_TAG,
                )
                .commitNow()
        } catch (_: IllegalStateException) {
            releaseLiveClip(directory)
            liveClipDirectoryPath = null
            binding.clipEditorContainer.visibility = View.GONE
            clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
            clipEditorCoverTimeout = null
            restoreLiveClipPlayback()
        }
    }

    private fun canOpenClipEditor(): Boolean = view != null && isAdded &&
        viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !childFragmentManager.isStateSaved

    private fun pauseLiveClipPlayback() {
        val controller = player
        controller?.playWhenReady = false
        controller?.pause()
        setVideoOutputVisible(false)
        detachVideoOutput()
        binding.playerLayout.visibility = View.GONE
    }

    private fun isClipEditorVisible(): Boolean = view != null && binding.clipEditorContainer.isVisible

    private fun closeClipEditor(directoryPath: String?) {
        if (!isClipEditorVisible() && liveClipDirectoryPath == null && !vodClipEditorOpen) return
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        binding.clipEditorContainer.visibility = View.GONE
        clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
        clipEditorCoverTimeout = null
        if (vodClipEditorOpen) {
            player?.sendCustomCommand(
                SessionCommand(PlaybackService.CLEAR_VOD_CLIP_SOURCE, Bundle.EMPTY), Bundle.EMPTY,
            )
            liveClipDirectoryPath = null
        } else {
            val directory = directoryPath ?: liveClipDirectoryPath
            directory?.let(::releaseLiveClip)
            liveClipDirectoryPath = null
        }
        restoreLiveClipPlayback()
    }

    private fun restoreClipEditorIfNeeded() {
        val editor = childFragmentManager.findFragmentByTag(CLIP_EDITOR_TAG) as? ClipEditorDialogFragment ?: return
        if (editor.isVodSource && videoType == VIDEO) {
            val controller = player ?: return
            if (controller.playbackState != Player.STATE_READY) return
            val request = controller.sendCustomCommand(
                SessionCommand(PlaybackService.GET_VOD_CLIP_DESCRIPTOR, Bundle.EMPTY), Bundle.EMPTY,
            )
            request.addListener({
                if (player !== controller || view == null) return@addListener
                val descriptor = runCatching { request.get() }.getOrNull()
                if (descriptor?.resultCode == SessionResult.RESULT_SUCCESS && readVodClipDescriptor(descriptor.extras)) {
                    vodClipEditorOpen = true
                    binding.clipEditorContainer.visibility = View.VISIBLE
                    binding.clipEditorTransitionCover.visibility = View.VISIBLE
                    scheduleClipEditorCoverFallback()
                } else {
                    childFragmentManager.beginTransaction().remove(editor).commitAllowingStateLoss()
                    vodClipEditorOpen = false
                    binding.clipEditorContainer.visibility = View.GONE
                }
            }, ContextCompat.getMainExecutor(requireContext()))
            return
        }
        val restoration = ClipEditorRestorationState(
            savedDirectoryPath = liveClipDirectoryPath,
            childDirectoryPath = editor.preparedDirectoryPath,
        )
        restoration.staleParentDirectoryPath?.let(::releaseLiveClip)
        val directory = restoration.directoryPath
        val valid = videoType == STREAM && !isLiveRewindActiveOrSwitching() && restoration.shouldRestoreEditor &&
            directory != null && File(directory).isDirectory && File(directory, "clip.json").isFile
        if (!valid) {
            restoration.orphanDirectoryPath?.let(::releaseLiveClip)
            childFragmentManager.beginTransaction().remove(editor).commitAllowingStateLoss()
            liveClipDirectoryPath = null
            livePlaybackBeforeClipEditor = null
            binding.clipEditorContainer.visibility = View.GONE
            binding.clipEditorTransitionCover.visibility = View.GONE
            return
        }
        liveClipDirectoryPath = directory
        binding.clipEditorContainer.visibility = View.VISIBLE
        binding.clipEditorTransitionCover.visibility = View.VISIBLE
        scheduleClipEditorCoverFallback()
    }

    private fun scheduleClipEditorCoverFallback() {
        clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
        val timeout = Runnable {
            if (binding.clipEditorContainer.visibility == View.VISIBLE) {
                binding.clipEditorTransitionCover.visibility = View.GONE
            }
        }
        clipEditorCoverTimeout = timeout
        binding.root.postDelayed(timeout, CLIP_EDITOR_COVER_TIMEOUT_MS)
    }

    private fun hideClipEditorTransitionCover() {
        if (!isClipEditorVisible()) return
        clipEditorCoverTimeout?.let(binding.root::removeCallbacks)
        clipEditorCoverTimeout = null
        binding.clipEditorTransitionCover.visibility = View.GONE
    }

    private fun restoreLiveClipPlayback() {
        val controller = player
        if (controller == null) {
            livePlaybackBeforeClipEditor = null
            playbackPositionBeforeClipEditor = null
            playbackBeforeClipEditor = null
            vodClipEditorOpen = false
            binding.clipEditorTransitionCover.visibility = View.GONE
            return
        }
        liveSurfaceRestoreTimeout?.let(binding.root::removeCallbacks)
        liveSurfaceRestoreListener?.let(controller::removeListener)
        val isVod = vodClipEditorOpen
        val shouldResume = if (isVod) playbackBeforeClipEditor == true else livePlaybackBeforeClipEditor == true
        val firstFrameListener = object : Player.Listener {
            override fun onRenderedFirstFrame() = finishLiveSurfaceRestore(controller)
        }
        liveSurfaceRestoreListener = firstFrameListener
        controller.addListener(firstFrameListener)
        binding.playerLayout.visibility = View.VISIBLE
        videoOutputCover?.visibility = View.VISIBLE
        setVideoOutputVisible(true)
        attachVideoOutput(controller)
        if (isVod) {
            playbackPositionBeforeClipEditor?.let(controller::seekTo)
            controller.playWhenReady = shouldResume
        } else if (shouldResume) {
            controller.seekToDefaultPosition()
            controller.playWhenReady = true
        } else {
            controller.playWhenReady = false
        }
        val timeout = Runnable { finishLiveSurfaceRestore(controller) }
        liveSurfaceRestoreTimeout = timeout
        binding.root.postDelayed(timeout, LIVE_SURFACE_RESTORE_TIMEOUT_MS)
    }

    private fun finishLiveSurfaceRestore(controller: Player) {
        liveSurfaceRestoreTimeout?.let(binding.root::removeCallbacks)
        liveSurfaceRestoreTimeout = null
        liveSurfaceRestoreListener?.let(controller::removeListener)
        liveSurfaceRestoreListener = null
        hideVideoOutputCover()
        livePlaybackBeforeClipEditor = null
        playbackPositionBeforeClipEditor = null
        playbackBeforeClipEditor = null
        vodClipEditorOpen = false
        binding.clipEditorTransitionCover.visibility = View.GONE
    }

    override suspend fun prepareVodClip(
        startIndex: Int,
        endIndexExclusive: Int,
    ): ClipPreparationRepository.PreparedLiveClip {
        val result = (player ?: error("Playback service is unavailable")).sendCustomCommand(
            SessionCommand(PlaybackService.PREPARE_VOD_CLIP, Bundle().apply {
                putInt(PlaybackService.CLIP_START_INDEX, startIndex)
                putInt(PlaybackService.CLIP_END_INDEX, endIndexExclusive)
            }), Bundle.EMPTY,
        ).awaitFuture()
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { "VOD clip preparation failed" }
        val directory = result.extras.getString(PlaybackService.CLIP_DIRECTORY)
            ?: error("Prepared clip directory is missing")
        return ClipPreparationRepository.PreparedLiveClip.read(File(directory))
    }

    override fun cancelVodClipPreparation() {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.CANCEL_VOD_CLIP_PREPARATION, Bundle.EMPTY), Bundle.EMPTY,
        )
    }

    override fun estimateVodClipBytes(
        startIndex: Int,
        endIndexExclusive: Int,
        selectedDurationUs: Long,
    ): Long? {
        if (startIndex < 0 || endIndexExclusive <= startIndex || endIndexExclusive > vodClipSegmentDurationsUs.size) return null
        val byteRanges = vodClipSegmentByteRanges.slice(startIndex until endIndexExclusive)
        if (byteRanges.all { it != Media3C.LENGTH_UNSET.toLong() }) return byteRanges.sum()
        val bitrate = vodClipBitrate?.takeIf { it > 0 } ?: return null
        return (selectedDurationUs.toDouble() / 1_000_000.0 * bitrate / 8.0).toLong()
    }

    override fun releaseVodClip(directoryPath: String) {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.RELEASE_VOD_CLIP, Bundle().apply {
                putString(PlaybackService.CLIP_DIRECTORY, directoryPath)
            }), Bundle.EMPTY,
        )
    }

    override fun createVodClipPreviewMediaSource(uri: String): androidx.media3.exoplayer.source.MediaSource {
        val mediaItemId = requireNotNull(vodClipMediaItemId) { "VOD clip source is no longer available" }
        val factory = requireNotNull(xtraModule.streamMedia3Runtime.primaryPlaybackClipDataSourceFactory(mediaItemId)) {
            "VOD HLS data source is unavailable"
        }
        val mediaItem = MediaItem.Builder().setUri(uri.toUri()).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        return HlsMediaSource.Factory(factory)
            .setPlaylistParserFactory(TwitchHlsPlaylistParserFactory(lowLatencyEnabled = false))
            .createMediaSource(mediaItem)
    }

    private fun releaseLiveClip(directory: String) {
        player?.sendCustomCommand(
            SessionCommand(PlaybackService.RELEASE_LIVE_CLIP, Bundle().apply {
                putString(PlaybackService.CLIP_DIRECTORY, directory)
            }), Bundle.EMPTY,
        )
    }

    private fun sendClipCleanup(action: String) {
        player?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }

    private suspend fun <T> ListenableFuture<T>.awaitFuture(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                val value = get()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
        continuation.invokeOnCancellation { cancel(true) }
    }

    override fun onStop() {
        cancelLiveRewindStateSync()
        logVideoSurfaceBinding("on_stop", player, view?.let { videoOutputView })
        super.onStop()
        invalidateResumeQualityConfirmation("fragment_on_stop")
        qualityRequestDeferred = false
        invalidateQualityRequest()
        if (isClipEditorVisible()) {
            player?.pause()
            return
        }
        val isInPIPMode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
            else -> false
        }
        player?.let { player ->
            if (player.isConnected) {
                savePosition()
                if (shouldApplyBackgroundPlaybackTransition(isInPIPMode)) {
                    if (viewModel.usingProxy) {
                        player.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.TOGGLE_PROXY, Bundle().apply {
                                    putBoolean(PlaybackService.USING_PROXY, false)
                                }
                            ), Bundle.EMPTY
                        )
                        viewModel.usingProxy = false
                    }
                    var suppressVideoInBackground = false
                    if (requireContext().prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)) {
                        val keepLiveStreamTrack = videoType == STREAM &&
                            player.currentMediaItem?.liveConfiguration?.targetOffsetMs
                                ?.let { it != Media3C.TIME_UNSET } == true
                        suppressVideoInBackground = !keepLiveStreamTrack &&
                            shouldDisableVideoForBackground(
                                backgroundPlaybackEnabled = true,
                                isInPictureInPicture = isInPIPMode,
                                playWhenReady = player.playWhenReady,
                                playbackState = player.playbackState,
                                hasMediaItem = player.currentMediaItem != null,
                                audioOnly = viewModel.quality?.name == AUDIO_ONLY_QUALITY,
                                chatOnly = viewModel.quality?.name == CHAT_ONLY_QUALITY,
                                videoAlreadySuppressed = viewModel.hidden,
                            )
                        if (keepLiveStreamTrack || suppressVideoInBackground) {
                            // Keep live HLS track selection stable across the background cycle.
                            // Hiding the surface avoids forcing video track selection on return.
                            setVideoOutputVisible(false)
                        }
                    } else {
                        viewModel.resume = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                        player.pause()
                    }
                    player.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.SET_BACKGROUND_PLAYBACK,
                            Bundle().apply {
                                putBoolean(PlaybackService.BACKGROUND_PLAYBACK, true)
                                putBoolean(
                                    PlaybackService.SUPPRESS_VIDEO_IN_BACKGROUND,
                                    suppressVideoInBackground,
                                )
                            },
                        ), Bundle.EMPTY
                    )
                }
                player.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                            putLong(PlaybackService.DURATION, (activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                        }
                    ), Bundle.EMPTY
                )
            }
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        if (isClipEditorVisible()) {
            player?.pause()
            return
        }
        if (!isInPIPMode) {
            releaseController()
        }
    }

    override fun onNetworkRestored() {
        if (!isResumed) return
        if (videoType == STREAM) {
            val controller = player
            if (isLiveRewindActiveOrSwitching()) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "PlaybackRecovery",
                        "event=network_restored decision=retain_rewind_source " +
                            "state=${controller?.playbackState} playWhenReady=${controller?.playWhenReady} " +
                            "itemToken=${diagnosticToken(controller?.currentMediaItem?.mediaId)}",
                    )
                }
                return
            }
            val endedNeedsVerification = controller?.playWhenReady == true &&
                controller.currentMediaItem != null &&
                (controller.playbackState == Player.STATE_ENDED || controller.playerError != null)
            if (endedNeedsVerification) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "PlaybackRecovery",
                        "event=network_restored decision=verify_ended_live " +
                            "state=${controller.playbackState} playWhenReady=${controller.playWhenReady} " +
                            "itemToken=${diagnosticToken(controller.currentMediaItem?.mediaId)}",
                    )
                }
                verifyEndedLivePlayback(trigger = "network_restored")
                return
            }
            val needsRecovery = controller?.playWhenReady == true &&
                controller.currentMediaItem != null &&
                (controller.playbackState == Player.STATE_IDLE || controller.playerError != null ||
                    (viewModel.liveFirstFrameRendered && !controller.isPlaying))
            if (BuildConfig.DEBUG) {
                Log.d(
                    "PlaybackRecovery",
                    "event=network_restored decision=${if (needsRecovery) "recover" else "keep_source"} " +
                        "state=${controller?.playbackState} playWhenReady=${controller?.playWhenReady} " +
                        "playerErrorCode=${controller?.playerError?.errorCode ?: "none"} " +
                        "itemToken=${diagnosticToken(controller?.currentMediaItem?.mediaId)}",
                )
            }
            if (needsRecovery) {
                scheduleStreamRecovery(trigger = "network_restored")
            }
        } else {
            player?.prepare()
        }
    }

    override fun onNetworkLost() {
        // ExoPlayer keeps the media timeline and retries loading as connectivity returns.
        // Stopping here discards that state and makes a temporary network loss look like a
        // user stop, especially when the fragment is about to be backgrounded.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CLIP_DIRECTORY, liveClipDirectoryPath)
        livePlaybackBeforeClipEditor?.let { outState.putBoolean(STATE_CLIP_PLAYING, it) }
        playbackPositionBeforeClipEditor?.let { outState.putLong(STATE_CLIP_POSITION, it) }
        playbackBeforeClipEditor?.let { outState.putBoolean(STATE_CLIP_VOD_PLAYING, it) }
        outState.putBoolean(STATE_CLIP_VOD_OPEN, vodClipEditorOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        cancelLiveRewindStateSync()
        cancelLiveStallRecovery(resetBudget = true)
        pendingSourceSwitchQuality.clear()
        invalidateResumeQualityConfirmation("fragment_view_destroyed")
        qualityRequestDeferred = false
        qualityRequestGeneration++
        qualityRequestInFlight = false
        qualityRequestQueued = false
        nativeCues = emptyList()
        shownLiveCaptionError = null
        qualityRetryJob?.cancel()
        qualityRetryJob = null
        qualityRetryAttempts = 0
        pendingQualityCallbacks.clear()
        clipStatusGeneration++
        clipStatusRequestInFlight = false
        clipStatusQueued = false
        clipControlSupported = null
        clipPreparationSnackbar?.dismiss()
        clipPreparationSnackbar = null
        if (clipPreparationJob?.isActive == true) {
            player?.sendCustomCommand(
                SessionCommand(PlaybackService.CANCEL_LIVE_CLIP_PREPARATION, Bundle.EMPTY), Bundle.EMPTY,
            )
        }
        clipPreparationJob?.cancel()
        clipPreparationJob = null
        clipEditorCoverTimeout?.let { binding.root.removeCallbacks(it) }
        clipEditorCoverTimeout = null
        liveSurfaceRestoreTimeout?.let { binding.root.removeCallbacks(it) }
        liveSurfaceRestoreTimeout = null
        liveSurfaceRestoreListener?.let { listener -> player?.removeListener(listener) }
        liveSurfaceRestoreListener = null
        clearForegroundVideoRestore()
        vaftFrameCaptureRequestId = null
        vaftFrozenFrameRequestId = null
        vaftEntryFrameCaptureRequestId = null
        vaftEntryFrameAttemptedId = null
        vaftEntryFrameAwaitingAckId = null
        clearVaftEntryFrozenFrame()
        clearVaftFrozenFrame()
        vaftFrozenFrameView = null
        videoOutputCover = null
        resetProgressRenderState()
        renderedPlaybackChrome = null
        renderedLiveBufferHealthReading = null
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        binding.liveCaptionView.clearCaption()
        logVideoSurfaceBinding("on_destroy_view", player, view?.let { videoOutputView })
        detachVideoOutput()
        super.onDestroyView()
    }

    private fun renderSubtitleOverlay() {
        // Live captions have their own fixed-size view. Keeping SubtitleView owned by
        // native cues prevents every partial result from changing the cue window geometry.
        binding.subtitleView.setCues(nativeCues)
    }

    /** Updates only the text inside the fixed caption container. */
    private fun updateLiveCaption(text: String, lineShiftToken: Long) {
        binding.liveCaptionView.submitCaption(text, lineShiftToken)
    }

    private fun attachVideoOutput(currentPlayer: Player) {
        videoOutputOwner.attach(currentPlayer, videoOutputView)
        logVideoSurfaceBinding("attach_attempt", currentPlayer, videoOutputView)
    }

    private fun detachVideoOutput() {
        val currentPlayer = videoOutputOwner.attachedPlayer() ?: return
        logVideoSurfaceBinding("detach_attempt", currentPlayer, videoOutputView)
        videoOutputOwner.clear()
    }

    private fun armForegroundVideoRestore(controller: Player) {
        clearForegroundVideoRestore()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (foregroundVideoRestoreListener !== this) return
                if (viewModel.hidden ||
                    viewModel.quality?.name == AUDIO_ONLY_QUALITY ||
                    viewModel.quality?.name == CHAT_ONLY_QUALITY
                ) {
                    clearForegroundVideoRestore()
                    return
                }
                if (videoOutputView.visibility != View.VISIBLE) return

                clearForegroundVideoRestore()
                hideVideoOutputCover()
                refreshPlayerHudLayout()
            }
        }
        foregroundVideoRestoreController = controller
        foregroundVideoRestoreListener = listener
        controller.addListener(listener)
        videoOutputCover?.visibility = View.VISIBLE
    }

    private fun clearForegroundVideoRestore() {
        val controller = foregroundVideoRestoreController
        val listener = foregroundVideoRestoreListener
        foregroundVideoRestoreController = null
        foregroundVideoRestoreListener = null
        if (controller != null && listener != null) controller.removeListener(listener)
    }

    private fun sampleRenderedSurfaceFrame(controller: Player, surfaceView: SurfaceView) {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            !surfaceView.isAttachedToWindow || surfaceView.width <= 0 || surfaceView.height <= 0
        ) {
            return
        }
        val itemToken = diagnosticToken(controller.currentMediaItem?.mediaId)
        if (itemToken in surfacePixelSampledItemTokens) return

        val surface = surfaceView.holder.surface
        if (!surface.isValid) {
            Log.d("PlaybackLifecycle", "event=surface_pixel_sample itemToken=$itemToken result=surface_invalid")
            return
        }
        surfacePixelSampledItemTokens += itemToken

        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    var lumaSum = 0L
                    var nonBlackPixels = 0
                    for (y in 0 until bitmap.height) {
                        for (x in 0 until bitmap.width) {
                            val pixel = bitmap.getPixel(x, y)
                            val luma = (android.graphics.Color.red(pixel) * 2126 +
                                android.graphics.Color.green(pixel) * 7152 +
                                android.graphics.Color.blue(pixel) * 722) / 10_000
                            lumaSum += luma
                            if (luma > 8) nonBlackPixels++
                        }
                    }
                    Log.d(
                        "PlaybackLifecycle",
                        "event=surface_pixel_sample itemToken=$itemToken result=$result " +
                            "sourceWidth=${surfaceView.width} sourceHeight=${surfaceView.height} " +
                            "sampleWidth=${bitmap.width} sampleHeight=${bitmap.height} " +
                            "nonBlackPixels=$nonBlackPixels " +
                            "averageLuma=${lumaSum / (bitmap.width * bitmap.height)}",
                    )
                } else {
                    Log.d("PlaybackLifecycle", "event=surface_pixel_sample itemToken=$itemToken result=$result")
                }
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) {
            bitmap.recycle()
            Log.d("PlaybackLifecycle", "event=surface_pixel_sample itemToken=$itemToken result=request_failed")
        }
    }

    private fun logSurfaceLifecycle(event: String, surfaceAttached: Boolean, surfaceValid: Boolean) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            "PlaybackLifecycle",
            "event=$event holderGeneration=$surfaceHolderGeneration " +
                "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)} " +
                "surfaceAttached=$surfaceAttached surfaceValid=$surfaceValid " +
                "outputBindingGeneration=$videoOutputBindingGeneration",
        )
    }

    private fun elapsedSince(eventTimeMs: Long?): String = eventTimeMs?.let {
        (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L).toString()
    } ?: "never"

    companion object {
        private const val CLIP_EDITOR_TAG = "liveClipEditor"
        private const val STATE_CLIP_DIRECTORY = "liveClipDirectory"
        private const val STATE_CLIP_PLAYING = "liveClipPlaying"
        private const val STATE_CLIP_POSITION = "clipPlaybackPosition"
        private const val STATE_CLIP_VOD_PLAYING = "vodClipPlaying"
        private const val STATE_CLIP_VOD_OPEN = "vodClipOpen"
        private const val LIVE_SURFACE_RESTORE_TIMEOUT_MS = 4_000L
        private const val LIVE_REWIND_STATE_SYNC_POLL_ATTEMPTS = 32
        private const val LIVE_REWIND_STATE_SYNC_POLL_INTERVAL_MS = 200L
        private const val CLIP_EDITOR_COVER_TIMEOUT_MS = 5_000L
        private const val QUALITY_RETRY_DELAY_MS = 500L
        private const val RESUME_QUALITY_CONFIRMATION_TIMEOUT_MS = 5_000L
        private const val VOD_START_POSITION_SETTLED_TOLERANCE_MS = 2_000L
        private const val MAX_QUALITY_RETRY_ATTEMPTS = 10
        private const val NETWORK_RECOVERY_POLL_MS = 3_000L
        private const val NETWORK_RECOVERY_LOG_INTERVAL_MS = 15_000L

        fun newInstance(item: Stream, tapElapsedMs: Long? = null): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getStreamArguments(item, tapElapsedMs)
            }
        }

        fun newInstance(
            item: Video,
            offset: Long?,
            ignoreSavedPosition: Boolean,
            videoUrl: String? = null,
        ): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition, videoUrl)
            }
        }

        fun newInstance(item: Clip): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): Media3Fragment {
            return Media3Fragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }

        fun newInstance(state: PlaybackState, offlineVideo: OfflineVideo? = null): Media3Fragment? {
            val type = state.type ?: return null
            if (type !in setOf(STREAM, VIDEO, CLIP, OFFLINE_VIDEO)) return null
            if (type == OFFLINE_VIDEO && offlineVideo == null) return null

            return Media3Fragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_TYPE, type)
                    putString(KEY_STREAM_ID, state.streamId)
                    putString(KEY_VIDEO_ID, state.videoId)
                    putString(KEY_CLIP_ID, state.clipId)
                    putInt(KEY_OFFLINE_VIDEO_ID, state.offlineVideoId ?: 0)
                    putString(KEY_CHANNEL_ID, state.channelId ?: offlineVideo?.channelId)
                    putString(KEY_CHANNEL_LOGIN, state.channelLogin ?: offlineVideo?.channelLogin)
                    putString(KEY_CHANNEL_NAME, state.channelName ?: offlineVideo?.channelName)
                    putString(KEY_CHANNEL_IMAGE, state.channelImage ?: offlineVideo?.channelLogo)
                    putString(KEY_GAME_ID, state.gameId ?: offlineVideo?.gameId)
                    putString(KEY_GAME_SLUG, state.gameSlug ?: offlineVideo?.gameSlug)
                    putString(KEY_GAME_NAME, state.gameName ?: offlineVideo?.gameName)
                    putString(KEY_TITLE, state.title ?: offlineVideo?.name)
                    putString(KEY_THUMBNAIL, state.thumbnail ?: offlineVideo?.thumbnail)
                    putString(KEY_CREATED_AT, state.createdAt ?: offlineVideo?.uploadDate?.toString())
                    putInt(KEY_VIEWER_COUNT, state.viewerCount ?: -1)
                    putInt(KEY_DURATION_SECONDS, state.durationSeconds ?: offlineVideo?.duration?.div(1000L)?.toInt() ?: 0)
                    putString(KEY_VIDEO_TYPE, state.videoType ?: offlineVideo?.type)
                    putString(KEY_VIDEO_ANIMATED_PREVIEW, state.videoAnimatedPreviewURL)
                    putString(KEY_VIDEO_CREATED_AT, state.videoCreatedAt ?: offlineVideo?.videoCreatedAt)
                    putInt(KEY_VIDEO_OFFSET_SECONDS, state.videoOffsetSeconds ?: -1)
                    putString(KEY_PROFILE_IMAGE_URL, state.channelImage ?: offlineVideo?.channelLogo)
                    putString(
                        KEY_URL,
                        if (type == VIDEO) {
                            state.videoUrl ?: state.playlistUrl
                        } else {
                            offlineVideo?.url ?: state.playlistUrl ?: state.videoUrl
                        },
                    )
                    putString(KEY_CHAT_URL, offlineVideo?.chatUrl)
                    putLong(KEY_OFFSET, state.position ?: -1L)
                    putBoolean(KEY_IGNORE_SAVED_POSITION, true)
                    putLong(KEY_RESTORED_POSITION, state.position ?: 0L)
                    putBoolean(KEY_RESTORED_PLAYBACK, true)
                    putBoolean(KEY_RESTORED_PAUSED, state.paused)
                    putString(KEY_RESTORED_QUALITIES, state.qualities)
                    putString(KEY_RESTORED_QUALITY, state.quality)
                    putString(KEY_RESTORED_PREVIOUS_QUALITY, state.previousQuality)
                    putBoolean(KEY_RESTORED_QUALITY_RESTORE, state.restoreQuality)
                    putString(KEY_STARTED_AT, state.createdAt)
                }
            }
        }
    }
}
