package com.github.andreyasadchy.xtra.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.preload.StreamMedia3Runtime
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewCoordinatorPolicy
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewDwellPolicy
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewLifecycle
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewLifecycleReconciler
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewMode
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewPolicy
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewQuality
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewSelectionCandidate
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewSelectionPolicy
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class StreamPreviewCandidate(
    val streamKey: String,
    val channelLogin: String,
    val visibleFraction: Float,
    val centerProximity: Float,
    val title: String? = null,
    val channelName: String? = null,
    val channelLogo: String? = null,
    val videoId: String? = null,
    val surface: FrameLayout,
) {
    /** Live previews share a channel slot; VOD previews get their own slot. */
    val previewIdentity: String?
        get() = videoId?.trim()?.takeIf { it.isNotEmpty() }?.let { "vod:$it" }
            ?: channelLogin.trim().lowercase().takeIf { it.isNotEmpty() }?.let { "live:$it" }
}

internal fun revealPreviewSurface(playerView: PlayerView, surface: View?) {
    playerView.alpha = 1f
    playerView.visibility = View.VISIBLE
    surface?.let {
        it.alpha = 1f
        it.visibility = View.VISIBLE
    }
}

/** App-scoped owner of a small pool of muted browsing preview players. */
@UnstableApi
class StreamPreviewCoordinator(
    context: Context,
    private val mediaRuntime: StreamMedia3Runtime,
    private val urlCoordinator: StreamPreloadCoordinator,
    private val streamFeedRefreshCoordinator: StreamFeedRefreshCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val context = context.applicationContext
    private val preferences = context.prefs()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val viewports = linkedMapOf<String, Viewport>()
    private val activePreviews = linkedMapOf<String, ActivePreview>()
    private var sharedPreviewPlayer: ExoPlayer? = null
    private var sharedPreviewView: PlayerView? = null
    private val previewTargetGenerations = PreviewTargetGeneration<FrameLayout>()
    private val previewLifecycle = StreamPreviewLifecycle()
    private val pendingStarts = mutableMapOf<String, Job>()
    private val dwellStarts = mutableMapOf<String, Long>()
    private val failedUntil = mutableMapOf<String, Long>()
    private var selectionJob: Job? = null
    private var selectionPending = false
    private var previewsPausedForScroll = false
    private var pagerScrolling = false
    private var pagerResumePending = false
    private var pagerResumeJob: Job? = null
    private var handoffLogin: String? = null
    private val lifecycleReconciler = StreamPreviewLifecycleReconciler(
        lifecycle = previewLifecycle,
        schedule = ::scheduleLifecycleReconciliation,
        onExpired = ::scheduleSelection,
    )
    private val sharedPreviewPlayerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            activePreviews.values.firstOrNull { it.player === sharedPreviewPlayer }?.let(::handleFirstFrame)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            activePreviews.values.firstOrNull { it.player === sharedPreviewPlayer }?.let { active ->
                handlePreviewFailure(active.identity, active.player)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = requestPolicyRecheck()
        override fun onLost(network: Network) = requestPolicyRecheck()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = requestPolicyRecheck()
    }
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) requestPolicyRecheck()
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in setOf(C.STREAM_PREVIEW_MODE, C.STREAM_PREVIEW_MULTIPLE, C.STREAM_PREVIEW_QUALITY, C.STREAM_PREVIEW_DELAY)) return@OnSharedPreferenceChangeListener
        scope.launch {
            if (key == C.STREAM_PREVIEW_MODE && StreamPreviewPolicy.mode(context) == StreamPreviewMode.OFF) {
                stopPreview()
                releaseSharedPreviewPlayer()
            } else {
                if (key == C.STREAM_PREVIEW_QUALITY) applyPreviewQuality()
                scheduleSelection()
            }
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            } else {
                connectivityManager?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            }
            ContextCompat.registerReceiver(
                context,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun updateViewport(viewportKey: String, candidates: Collection<StreamPreviewCandidate>, scrolling: Boolean) {
        viewports[viewportKey] = Viewport(candidates.toList(), scrolling)
        scheduleSelection()
    }

    fun detachViewport(viewportKey: String) {
        val viewport = viewports.remove(viewportKey)
        viewport?.candidates
            ?.map { it.surface }
            ?.distinct()
            ?.forEach(::detachSurface)
        scheduleSelection()
    }

    /** Scrolling is a visibility update, not a release event. */
    fun onScrolling(viewportKey: String) {
        viewports[viewportKey] = viewports[viewportKey]?.copy(scrolling = true) ?: Viewport(emptyList(), true)
        if (!previewsPausedForScroll) {
            activePreviews.values.forEach { it.player.playWhenReady = false }
            previewsPausedForScroll = true
        }
        previewLifecycle.onScrolling()
        scheduleSelection()
    }

    /**
     * A pager moves complete preview surfaces horizontally. Keep the player state warm, but hide
     * its TextureView while the page animation is running; TextureView draw synchronization can
     * otherwise block the UI thread for multiple frames.
     */
    fun onPagerScrollStateChanged(scrolling: Boolean) {
        if (pagerScrolling == scrolling) return
        pagerScrolling = scrolling
        if (scrolling) {
            pagerResumeJob?.cancel()
            pagerResumeJob = null
            pagerResumePending = false
            activePreviews.values.forEach { active ->
                active.player.playWhenReady = false
                suspendPreviewSurface(active)
            }
            previewsPausedForScroll = true
            previewLifecycle.onScrolling()
            cancelPendingStarts()
        } else {
            pagerResumePending = true
            pagerResumeJob = scope.launch {
                delay(PAGER_PREVIEW_RESUME_DELAY_MS)
                pagerResumePending = false
                pagerResumeJob = null
                if (!pagerScrolling) scheduleSelection()
            }
        }
        scheduleSelection()
    }

    fun onAppBackground() {
        viewports.clear()
        stopPreview()
        releaseSharedPreviewPlayer()
    }

    fun onAppForeground() = scheduleSelection()

    fun onFullscreenPlaybackStarted() {
        onFullscreenPlaybackStarted(null)
    }

    fun onFullscreenPlaybackStarted(channelLogin: String?) {
        // Full-screen playback and browsing previews are mutually exclusive. Keeping a warm
        // preview player here is unsafe: its PlayerView can be reattached by a pending viewport
        // reconciliation while the full-screen player is being added, producing a small stale
        // video over the new player's opaque handoff cover. Releasing every preview also avoids
        // decoding and composing muted browsing video while the user is watching the stream.
        stopPreview()
        hideViewportSurfaces()
    }

    fun onFullscreenPlaybackFirstFrame(channelLogin: String?) {
        val login = normalize(channelLogin)
        if (handoffLogin != null && (login == null || handoffLogin == livePreviewIdentity(login))) {
            val handoff = handoffLogin
            handoffLogin = null
            if (handoff != null) releasePreview(handoff)
        }
    }

    fun onFullscreenPlaybackFailed() {
        stopPreview()
    }

    fun onPlaybackReturned() {
        stopPreview()
    }

    fun isPreviewing(channelLogin: String): Boolean =
        normalize(channelLogin)?.let(::livePreviewIdentity)?.let(activePreviews::containsKey) == true

    fun isPreviewActive(): Boolean = activePreviews.isNotEmpty()

    /** Unbinds only the view. The player remains reusable during the offscreen grace period. */
    fun detachSurface(surface: FrameLayout) {
        previewTargetGenerations.invalidate(surface)
        viewports.entries.forEach { (key, viewport) ->
            val remainingCandidates = viewport.candidates.filterNot { it.surface === surface }
            if (remainingCandidates.size != viewport.candidates.size) {
                viewports[key] = viewport.copy(candidates = remainingCandidates)
            }
        }
        val active = activePreviews.values.firstOrNull { it.surface === surface }
        active?.let {
            detachPreviewSurface(it)
            val now = SystemClock.elapsedRealtime()
            previewLifecycle.markOffscreen(it.identity, now)
            lifecycleReconciler.reconcile(now, additionalDeadlines = failedUntil.values)
        }
        if (active == null) {
            clearPreviewSurface(surface)
        }
    }

    fun refresh() = scheduleSelection()

    private fun requestPolicyRecheck() {
        scope.launch {
            if (StreamPreviewPolicy.allowsNetwork(context)) scheduleSelection() else stopPreview()
        }
    }

    private fun scheduleSelection() {
        selectionPending = true
        if (selectionJob?.isActive == true) return
        selectionJob = scope.launch {
            yield()
            try {
                while (selectionPending) {
                    selectionPending = false
                    reconcileSelection()
                }
            } finally {
                selectionJob = null
            }
        }
    }

    private fun reconcileSelection() {
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                isPlayerActive = streamFeedRefreshCoordinator.isPlayerActive,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) {
            cancelPendingStarts()
            lifecycleReconciler.cancel()
            if (handoffLogin == null) {
                stopActivePreviews()
            } else {
                activePreviews.keys.toList()
                    .filter { it != handoffLogin }
                    .forEach(::releasePreview)
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val candidates = currentCandidates()
        val candidateIdentities = candidates.mapNotNullTo(mutableSetOf(), StreamPreviewCandidate::previewIdentity)
        dwellStarts.keys.retainAll(candidateIdentities)
        failedUntil.entries.toList().forEach { (identity, retryAt) ->
            if (retryAt <= now) failedUntil.remove(identity)
        }

        val reasonablyVisible = candidates
            .filter { it.visibleFraction >= StreamPreviewSelectionPolicy.STOP_VISIBLE_FRACTION }
            .mapNotNull { it.previewIdentity }
        val scrolling = pagerScrolling || viewports.values.any { it.scrolling }
        if (pagerScrolling || pagerResumePending) {
            cancelPendingStarts()
            return
        }
        if (!scrolling && previewsPausedForScroll) {
            activePreviews.values.forEach { it.player.playWhenReady = true }
            previewsPausedForScroll = false
        }
        if (scrolling) {
            // A gesture changes which cards are visible, not whether an existing
            // preview should play. The lifecycle grace period handles cards that
            // remain offscreen after the gesture settles.
            cancelPendingStarts()
        }
        previewLifecycle.observeVisible(reasonablyVisible, now, scrolling = scrolling)
        previewLifecycle.expire(now)
        lifecycleReconciler.reconcile(now, additionalDeadlines = failedUntil.values)
        activePreviews.keys.toList()
            .filter { it !in previewLifecycle.activeIdentities() && it != handoffLogin }
            .forEach(::releasePreview)

        val activeIdentities = activePreviews.keys.toSet()
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = candidates.mapIndexed { index, candidate ->
                StreamPreviewSelectionCandidate(
                    identity = candidate.previewIdentity!!,
                    visibleFraction = candidate.visibleFraction,
                    centerProximity = candidate.centerProximity,
                    order = index,
                )
            },
            activeIdentities = activeIdentities,
            maxActivePreviews = maxActivePreviews(),
        )
        val selectedSet = selected.toSet()
        val bestCandidates = bestCandidatesByIdentity(candidates)

        StreamPreviewCoordinatorPolicy.displacedActiveIdentities(
            bestCandidateVisibility = bestCandidates.mapValues { (_, candidate) -> candidate.visibleFraction },
            activeIdentities = activePreviews.keys,
            selectedIdentities = selectedSet,
            handoffIdentity = handoffLogin,
        ).forEach(::releasePreview)

        val maxActive = maxActivePreviews()
        if (activePreviews.size > maxActive) {
            activePreviews.keys.toList()
                .filter { it !in selectedSet && it != handoffLogin }
                .take(activePreviews.size - maxActive)
                .forEach(::releasePreview)
        }

        pendingStarts.keys.toList()
            .filter { identity ->
                StreamPreviewCoordinatorPolicy.shouldCancelPendingStart(
                    identity = identity,
                    selectedIdentities = selectedSet,
                    activeIdentities = activePreviews.keys,
                )
            }
            .forEach { identity ->
                pendingStarts.remove(identity)?.cancel()
                if (identity !in activePreviews) dwellStarts.remove(identity)
            }

        selected.forEach { identity ->
            val candidate = bestCandidates[identity] ?: return@forEach
            val active = activePreviews[identity]
            if (active != null) {
                attachSurfaceIfNeeded(active, candidate)
            } else {
                if (!scrolling) scheduleStart(candidate, now)
            }
        }
    }

    private fun scheduleStart(candidate: StreamPreviewCandidate, now: Long) {
        val identity = candidate.previewIdentity ?: return
        if (activePreviews.containsKey(identity) || pendingStarts[identity]?.isActive == true) return
        pendingStarts.remove(identity)
        if (failedUntil[identity]?.let { it > now } == true) return
        val startedAt = StreamPreviewDwellPolicy.startAt(
            existingStartMs = dwellStarts[identity],
            nowMs = now,
            isScrolling = isScrolling(candidate),
        ) ?: run {
            dwellStarts.remove(identity)
            return
        }
        dwellStarts[identity] = startedAt
        val remainingDelay = StreamPreviewDwellPolicy.remainingDelay(
            startedAtMs = startedAt,
            nowMs = now,
            delayMs = StreamPreviewPolicy.delay(context).delayMs,
        )
        lateinit var startJob: Job
        startJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (remainingDelay > 0L) delay(remainingDelay)
                startPreview(identity)
            } finally {
                if (pendingStarts[identity] === startJob) pendingStarts.remove(identity)
            }
        }
        pendingStarts[identity] = startJob
        startJob.start()
    }

    private suspend fun startPreview(identity: String) {
        if (activePreviews.containsKey(identity) || activePreviews.size >= maxActivePreviews()) return
        if (failedUntil[identity]?.let { it > SystemClock.elapsedRealtime() } == true) return
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                isPlayerActive = streamFeedRefreshCoordinator.isPlayerActive,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return

        val candidate = bestCandidatesByIdentity(currentCandidates())[identity] ?: return
        val target = candidate.surface
        val targetGeneration = previewTargetGenerations.capture(target)
        if (candidate.visibleFraction < StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION) return
        if (isScrolling(candidate)) {
            dwellStarts.remove(identity)
            return
        }
        val url = if (candidate.videoId.isNullOrBlank()) {
            urlCoordinator.resolveForPreview(candidate.channelLogin)
        } else {
            urlCoordinator.resolveVodForPreview(candidate.videoId)
        } ?: return
        if (!previewTargetGenerations.isCurrent(target, targetGeneration)) return
        val latestCandidate = bestCandidatesByIdentity(currentCandidates())[identity] ?: return
        if (latestCandidate.surface !== target) return
        if (latestCandidate.visibleFraction < StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION) return
        if (isScrolling(latestCandidate)) {
            dwellStarts.remove(identity)
            return
        }
        if (activePreviews.size >= maxActivePreviews()) return
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                isPlayerActive = streamFeedRefreshCoordinator.isPlayerActive,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return

        var player: ExoPlayer? = null
        try {
            val builtPlayer = if (maxActivePreviews() == 1 || activePreviews.isEmpty()) {
                ensureSharedPreviewPlayer()
            } else {
                mediaRuntime.buildPreviewPlayer(context, previewTrackSelectionParameters())
            }
            player = builtPlayer
            val playerView = if (builtPlayer === sharedPreviewPlayer) {
                ensureSharedPreviewView()
            } else {
                createPreviewView()
            }
            val mediaItem = if (latestCandidate.videoId.isNullOrBlank()) {
                mediaRuntime.createLiveMediaItem(
                    channelLogin = latestCandidate.channelLogin,
                    url = url,
                    title = latestCandidate.title,
                    channelName = latestCandidate.channelName,
                    channelLogo = latestCandidate.channelLogo,
                )
            } else {
                mediaRuntime.createVodMediaItem(
                    videoId = latestCandidate.videoId,
                    url = url,
                    title = latestCandidate.title,
                    channelName = latestCandidate.channelName,
                    channelLogo = latestCandidate.channelLogo,
                )
            }
            val active = ActivePreview(identity = identity, player = player, playerView = playerView)
            activePreviews[identity] = active
            urlCoordinator.setPreviewActive(true)
            previewLifecycle.track(identity, SystemClock.elapsedRealtime())
            builtPlayer.repeatMode = if (latestCandidate.videoId.isNullOrBlank()) {
                Player.REPEAT_MODE_OFF
            } else {
                Player.REPEAT_MODE_ONE
            }
            attachSurfaceIfNeeded(active, latestCandidate)
            if (builtPlayer !== sharedPreviewPlayer) {
                builtPlayer.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = handleFirstFrame(active)

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (BuildConfig.DEBUG) Log.d("StreamPreview", "failed identity=$identity type=${error.errorCodeName}")
                        handlePreviewFailure(identity, builtPlayer)
                    }
                })
            }
            builtPlayer.setMediaItem(mediaItem)
            builtPlayer.volume = 0f
            builtPlayer.prepare()
            builtPlayer.playWhenReady = true
            if (BuildConfig.DEBUG) Log.d("StreamPreview", "started identity=$identity quality=${StreamPreviewPolicy.quality(context)}")
        } catch (cancellation: CancellationException) {
            player?.let(::discardPreviewPlayer)
            throw cancellation
        } catch (error: Throwable) {
            player?.let(::discardPreviewPlayer)
            markPreviewFailure(identity)
        }
    }

    private fun ensureSharedPreviewPlayer(): ExoPlayer =
        sharedPreviewPlayer ?: mediaRuntime.buildPreviewPlayer(
            context,
            previewTrackSelectionParameters(),
        ).also { player ->
            player.addListener(sharedPreviewPlayerListener)
            sharedPreviewPlayer = player
        }

    private fun ensureSharedPreviewView(): PlayerView =
        sharedPreviewView ?: createPreviewView().also { sharedPreviewView = it }

    private fun createPreviewView(): PlayerView =
        (LayoutInflater.from(context).inflate(R.layout.view_stream_preview, null, false) as PlayerView).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

    private fun handleFirstFrame(active: ActivePreview) {
        if (activePreviews[active.identity]?.player !== active.player) return
        active.firstFrameRendered = true
        revealPreviewSurface(active.playerView, active.surface)
        logVideoSurfaceBinding("preview_first_frame", active.player, active.playerView, active.playerView.player)
        if (BuildConfig.DEBUG) Log.d("StreamPreview", "first_frame identity=${active.identity}")
    }

    private fun discardPreviewPlayer(player: ExoPlayer) {
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        if (player !== sharedPreviewPlayer) runCatching { player.release() }
    }

    private fun handlePreviewFailure(identity: String, player: ExoPlayer) {
        if (activePreviews[identity]?.player !== player) return
        markPreviewFailure(identity)
        scheduleSelection()
    }

    private fun markPreviewFailure(identity: String) {
        failedUntil[identity] = SystemClock.elapsedRealtime() + PLAYER_FAILURE_RETRY_MS
        releasePreview(identity)
        lifecycleReconciler.reconcile(SystemClock.elapsedRealtime(), additionalDeadlines = failedUntil.values)
    }

    private fun attachSurfaceIfNeeded(active: ActivePreview, candidate: StreamPreviewCandidate) {
        val surfaceChanged = active.surface !== candidate.surface
        if (surfaceChanged) {
            // A rebound TextureView has no decoded frame yet. Keep the card thumbnail
            // visible until Media3 confirms that the new output surface has a frame.
            active.firstFrameRendered = false
            if (active.surface != null) detachPreviewSurface(active)
            active.surface = candidate.surface
        }
        (active.playerView.parent as? ViewGroup)
            ?.takeIf { it !== candidate.surface }
            ?.removeView(active.playerView)
        if (active.playerView.parent !== candidate.surface) {
            candidate.surface.addView(active.playerView)
        }
        attachPreviewPlayer(active)
        active.playerView.alpha = if (active.firstFrameRendered) 1f else 0f
        active.playerView.visibility = View.VISIBLE
        candidate.surface.alpha = 1f
        candidate.surface.visibility = View.VISIBLE
    }

    private fun applyPreviewQuality() {
        val parameters = previewTrackSelectionParameters()
        activePreviews.values.forEach { active ->
            runCatching { active.player.setTrackSelectionParameters(parameters) }
        }
    }

    private fun previewTrackSelectionParameters(): TrackSelectionParameters =
        TrackSelectionParameters.Builder(context).apply {
            setTrackTypeDisabled(Media3C.TRACK_TYPE_AUDIO, true)
            when (StreamPreviewPolicy.quality(context)) {
                StreamPreviewQuality.P360 -> setMaxVideoSize(640, 360)
                StreamPreviewQuality.P480 -> setMaxVideoSize(854, 480)
                StreamPreviewQuality.AUTO -> Unit
            }
        }.build()

    private fun currentCandidates(): List<StreamPreviewCandidate> =
        viewports.values.flatMap { it.candidates }
            .mapNotNull { candidate -> candidate.takeIf { it.previewIdentity != null } }

    private fun maxActivePreviews(): Int =
        if (StreamPreviewPolicy.allowsMultiplePreviews(context)) {
            StreamPreviewSelectionPolicy.MAX_ACTIVE_PREVIEWS
        } else {
            1
        }

    private fun bestCandidatesByIdentity(candidates: Collection<StreamPreviewCandidate>): Map<String, StreamPreviewCandidate> =
        candidates
            .mapIndexed { index, candidate -> RankedCandidate(candidate, index) }
            .mapNotNull { it.takeIf { ranked -> ranked.candidate.previewIdentity != null } }
            .groupBy { it.candidate.previewIdentity!! }
            .mapValues { (_, sameIdentity) ->
                sameIdentity.minWithOrNull(
                    compareByDescending<RankedCandidate> { it.candidate.visibleFraction.coerceIn(0f, 1f) }
                        .thenByDescending { it.candidate.centerProximity.coerceIn(0f, 1f) }
                        .thenBy { it.order },
                )!!.candidate
            }

    private fun isScrolling(candidate: StreamPreviewCandidate): Boolean =
        viewports.values.any { viewport -> viewport.scrolling && viewport.candidates.any { it === candidate } }

    private fun stopPreview() {
        cancelPendingStarts()
        lifecycleReconciler.cancel()
        previewsPausedForScroll = false
        pagerScrolling = false
        pagerResumeJob?.cancel()
        pagerResumeJob = null
        pagerResumePending = false
        handoffLogin = null
        stopActivePreviews()
        previewLifecycle.clear()
    }

    /** Remove orphaned preview views as well as previews still owned by the coordinator. */
    private fun hideViewportSurfaces() {
        viewports.values
            .flatMap { it.candidates }
            .map { it.surface }
            .distinct()
            .forEach(::clearPreviewSurface)
    }

    private fun clearPreviewSurface(surface: FrameLayout) {
        previewTargetGenerations.invalidate(surface)
        for (index in surface.childCount - 1 downTo 0) {
            val child = surface.getChildAt(index)
            if (child is PlayerView && child.player != null) {
                logVideoSurfaceBinding("preview_detach", child.player, child, child.player)
                child.player = null
            }
            surface.removeViewAt(index)
        }
        surface.alpha = 0f
        surface.visibility = View.GONE
    }

    private fun stopActivePreviews() {
        activePreviews.keys.toList().forEach(::releasePreview)
    }

    private fun cancelPendingStarts() {
        val jobs = pendingStarts.values.toList()
        pendingStarts.clear()
        // Cancellation runs the job's finally block synchronously on the main
        // thread. Clear the map before canceling so that block cannot mutate a
        // LinkedHashMap iterator that is still in progress.
        jobs.forEach(Job::cancel)
        dwellStarts.clear()
    }

    private fun scheduleLifecycleReconciliation(delayMs: Long, callback: () -> Unit): () -> Unit {
        val job = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            callback()
        }
        return { job.cancel() }
    }

    private fun releasePreview(login: String) {
        pendingStarts.remove(login)?.cancel()
        dwellStarts.remove(login)
        previewLifecycle.failed(login)
        activePreviews.remove(login)?.let { active ->
            detachPreviewSurface(active)
            runCatching { active.player.stop() }
            runCatching { active.player.clearMediaItems() }
            if (active.player !== sharedPreviewPlayer) runCatching { active.player.release() }
        }
        urlCoordinator.setPreviewActive(activePreviews.isNotEmpty())
    }

    private fun releaseSharedPreviewPlayer() {
        sharedPreviewView?.let { view ->
            if (view.player != null) {
                logVideoSurfaceBinding("preview_detach", sharedPreviewPlayer, view, view.player)
                view.player = null
            }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        sharedPreviewPlayer?.let { player ->
            runCatching { player.removeListener(sharedPreviewPlayerListener) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        sharedPreviewView = null
        sharedPreviewPlayer = null
    }

    private fun detachPreviewSurface(active: ActivePreview) {
        if (active.playerView.player != null) {
            logVideoSurfaceBinding("preview_detach", active.player, active.playerView, active.playerView.player)
            active.playerView.player = null
        }
        active.surface?.let { surface ->
            surface.removeView(active.playerView)
            surface.alpha = 0f
            surface.visibility = View.GONE
        }
        active.playerView.alpha = 0f
        active.playerView.visibility = View.GONE
        active.surface = null
    }

    private fun suspendPreviewSurface(active: ActivePreview) {
        detachPreviewSurface(active)
    }

    private fun attachPreviewPlayer(active: ActivePreview) {
        if (active.playerView.player !== active.player) {
            logVideoSurfaceBinding("preview_attach", active.player, active.playerView, active.playerView.player)
            active.playerView.player = active.player
        }
        if (BuildConfig.DEBUG) {
            val attachedTargets = activePreviews.values.count {
                it.player === active.player && it.playerView.player === active.player
            }
            check(attachedTargets <= 1) {
                "Player ${active.player.identityId()} is attached to $attachedTargets preview PlayerViews"
            }
        }
    }

    private fun normalize(login: String?): String? =
        login?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun livePreviewIdentity(login: String): String = "live:$login"

    private data class Viewport(
        val candidates: List<StreamPreviewCandidate>,
        val scrolling: Boolean,
    )

    private data class RankedCandidate(
        val candidate: StreamPreviewCandidate,
        val order: Int,
    )

    private data class ActivePreview(
        val identity: String,
        val player: ExoPlayer,
        val playerView: PlayerView,
        var surface: FrameLayout? = null,
        var firstFrameRendered: Boolean = false,
    )

    private companion object {
        const val PAGER_PREVIEW_RESUME_DELAY_MS = 100L
        const val PLAYER_FAILURE_RETRY_MS = 10_000L
    }
}
