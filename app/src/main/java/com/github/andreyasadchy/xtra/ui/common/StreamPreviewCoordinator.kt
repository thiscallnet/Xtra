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
    private val previewLifecycle = StreamPreviewLifecycle()
    private val pendingStarts = mutableMapOf<String, Job>()
    private val dwellStarts = mutableMapOf<String, Long>()
    private val failedUntil = mutableMapOf<String, Long>()
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
        viewports.remove(viewportKey)
        scheduleSelection()
    }

    /** Scrolling is a visibility update, not a release event. */
    fun onScrolling(viewportKey: String) {
        viewports[viewportKey] = viewports[viewportKey]?.copy(scrolling = true) ?: Viewport(emptyList(), true)
        previewLifecycle.onScrolling()
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
        val login = normalize(channelLogin)
        val identity = login?.let(::livePreviewIdentity)
        if (identity == null && handoffLogin != null) {
            cancelPendingStarts()
            activePreviews.keys.toList()
                .filter { it != handoffLogin }
                .forEach(::releasePreview)
            return
        }
        if (identity == null || activePreviews[identity] == null) {
            stopPreview()
            return
        }
        handoffLogin = identity
        cancelPendingStarts()
        activePreviews.keys.toList()
            .filter { it != identity }
            .forEach(::releasePreview)
        // Preview SurfaceViews are compositor layers and can remain above the new player view.
        // Keep the warm player, but hide its old card surface for the duration of the handoff.
        activePreviews[identity]?.let(::detachPreviewSurface)
        previewLifecycle.retainOnly(identity)
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
        val active = activePreviews.values.firstOrNull { it.surface === surface }
        active?.let {
            detachPreviewSurface(it)
            val now = SystemClock.elapsedRealtime()
            previewLifecycle.markOffscreen(it.identity, now)
            lifecycleReconciler.reconcile(now, additionalDeadlines = failedUntil.values)
        }
        if (active == null) {
            surface.removeAllViews()
            surface.alpha = 0f
            surface.visibility = View.GONE
        }
    }

    fun refresh() = scheduleSelection()

    private fun requestPolicyRecheck() {
        scope.launch {
            if (StreamPreviewPolicy.allowsNetwork(context)) scheduleSelection() else stopPreview()
        }
    }

    private fun scheduleSelection() {
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
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
        val scrolling = viewports.values.any { it.scrolling }
        if (scrolling) {
            cancelPendingStarts()
            pauseActivePreviews()
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
                if (scrolling) {
                    pausePreview(active)
                } else {
                    resumePreview(active)
                }
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
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return

        val candidate = bestCandidatesByIdentity(currentCandidates())[identity] ?: return
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
        val latestCandidate = bestCandidatesByIdentity(currentCandidates())[identity] ?: return
        if (latestCandidate.visibleFraction < StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION) return
        if (isScrolling(latestCandidate)) {
            dwellStarts.remove(identity)
            return
        }
        if (activePreviews.size >= maxActivePreviews()) return
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
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
        PlayerView(context).apply {
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
        if (BuildConfig.DEBUG) Log.d("StreamPreview", "first_frame identity=${active.identity}")
    }

    private fun discardPreviewPlayer(player: ExoPlayer) {
        runCatching { player.stop() }
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
        if (active.surface !== candidate.surface) {
            if (active.surface != null) detachPreviewSurface(active)
            active.surface = candidate.surface
        }
        (active.playerView.parent as? ViewGroup)
            ?.takeIf { it !== candidate.surface }
            ?.removeView(active.playerView)
        if (active.playerView.parent !== candidate.surface) {
            candidate.surface.addView(active.playerView)
        }
        active.playerView.player = active.player
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

    private fun pauseActivePreviews() {
        activePreviews.values.forEach(::pausePreview)
    }

    private fun pausePreview(active: ActivePreview) {
        if (active.pausedForScrolling) return
        active.player.pause()
        active.pausedForScrolling = true
    }

    private fun resumePreview(active: ActivePreview) {
        if (!active.pausedForScrolling) return
        active.pausedForScrolling = false
        active.player.playWhenReady = true
        active.player.play()
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
        handoffLogin = null
        stopActivePreviews()
        previewLifecycle.clear()
    }

    private fun stopActivePreviews() {
        activePreviews.keys.toList().forEach(::releasePreview)
    }

    private fun cancelPendingStarts() {
        pendingStarts.values.forEach(Job::cancel)
        pendingStarts.clear()
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
            if (active.player !== sharedPreviewPlayer) runCatching { active.player.release() }
        }
        urlCoordinator.setPreviewActive(activePreviews.isNotEmpty())
    }

    private fun releaseSharedPreviewPlayer() {
        sharedPreviewPlayer?.let { player ->
            runCatching { player.removeListener(sharedPreviewPlayerListener) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        sharedPreviewView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.player = null
        }
        sharedPreviewView = null
        sharedPreviewPlayer = null
    }

    private fun detachPreviewSurface(active: ActivePreview) {
        active.surface?.let { surface ->
            surface.removeView(active.playerView)
            surface.alpha = 0f
            surface.visibility = View.GONE
        }
        active.playerView.player = null
        active.playerView.alpha = 0f
        active.playerView.visibility = View.GONE
        active.surface = null
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
        var pausedForScrolling: Boolean = false,
    )

    private companion object {
        const val PLAYER_FAILURE_RETRY_MS = 10_000L
    }
}
