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
import androidx.core.content.ContextCompat
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.repository.preload.StreamMedia3Runtime
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
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
    val surface: PlayerView,
)

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
    }

    fun onAppForeground() = scheduleSelection()

    fun onFullscreenPlaybackStarted() {
        onFullscreenPlaybackStarted(null)
    }

    fun onFullscreenPlaybackStarted(channelLogin: String?) {
        val login = normalize(channelLogin)
        if (login == null && handoffLogin != null) {
            cancelPendingStarts()
            activePreviews.keys.toList()
                .filter { it != handoffLogin }
                .forEach(::releasePreview)
            return
        }
        if (login == null || activePreviews[login] == null) {
            stopPreview()
            return
        }
        handoffLogin = login
        cancelPendingStarts()
        activePreviews.keys.toList()
            .filter { it != login }
            .forEach(::releasePreview)
        previewLifecycle.retainOnly(login)
    }

    fun onFullscreenPlaybackFirstFrame(channelLogin: String?) {
        val login = normalize(channelLogin)
        if (handoffLogin != null && (login == null || handoffLogin == login)) {
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
        activePreviews.containsKey(normalize(channelLogin))

    /** Unbinds only the view. The player remains reusable during the offscreen grace period. */
    fun detachSurface(surface: PlayerView) {
        activePreviews.values
            .firstOrNull { it.surface === surface }
            ?.surface = null
        surface.player = null
        surface.alpha = 0f
        surface.visibility = View.GONE
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
        val candidateIdentities = candidates.mapTo(mutableSetOf()) { normalize(it.channelLogin)!! }
        dwellStarts.keys.retainAll(candidateIdentities)
        failedUntil.entries.toList().forEach { (identity, retryAt) ->
            if (retryAt <= now) failedUntil.remove(identity)
        }

        val reasonablyVisible = candidates
            .filter { it.visibleFraction >= StreamPreviewSelectionPolicy.STOP_VISIBLE_FRACTION }
            .map { normalize(it.channelLogin)!! }
        previewLifecycle.observeVisible(reasonablyVisible, now)
        previewLifecycle.expire(now)
        lifecycleReconciler.reconcile(now)
        activePreviews.keys.toList()
            .filter { it !in previewLifecycle.activeIdentities() && it != handoffLogin }
            .forEach(::releasePreview)

        val activeIdentities = activePreviews.keys.toSet()
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = candidates.mapIndexed { index, candidate ->
                StreamPreviewSelectionCandidate(
                    identity = candidate.channelLogin,
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

        val maxActive = maxActivePreviews()
        if (activePreviews.size > maxActive) {
            activePreviews.keys.toList()
                .filter { it !in selectedSet && it != handoffLogin }
                .take(activePreviews.size - maxActive)
                .forEach(::releasePreview)
        }

        pendingStarts.keys.toList()
            .filter { it !in selectedSet || it in activePreviews }
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
                scheduleStart(candidate, now)
            }
        }
    }

    private fun scheduleStart(candidate: StreamPreviewCandidate, now: Long) {
        val login = normalize(candidate.channelLogin) ?: return
        if (activePreviews.containsKey(login) || pendingStarts.containsKey(login)) return
        if (failedUntil[login]?.let { it > now } == true) return
        val startedAt = StreamPreviewDwellPolicy.startAt(
            existingStartMs = dwellStarts[login],
            nowMs = now,
            isScrolling = isScrolling(candidate),
        ) ?: run {
            dwellStarts.remove(login)
            return
        }
        dwellStarts[login] = startedAt
        val remainingDelay = StreamPreviewDwellPolicy.remainingDelay(
            startedAtMs = startedAt,
            nowMs = now,
            delayMs = StreamPreviewPolicy.delay(context).delayMs,
        )
        pendingStarts[login] = scope.launch {
            if (remainingDelay > 0L) delay(remainingDelay)
            pendingStarts.remove(login)
            startPreview(login)
        }
    }

    private suspend fun startPreview(login: String) {
        if (activePreviews.containsKey(login) || activePreviews.size >= maxActivePreviews()) return
        if (failedUntil[login]?.let { it > SystemClock.elapsedRealtime() } == true) return
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return

        val candidate = bestCandidatesByIdentity(currentCandidates())[login] ?: return
        if (candidate.visibleFraction < StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION) return
        if (isScrolling(candidate)) {
            dwellStarts.remove(login)
            return
        }
        val url = urlCoordinator.resolveForPreview(login) ?: return
        val latestCandidate = bestCandidatesByIdentity(currentCandidates())[login] ?: return
        if (latestCandidate.visibleFraction < StreamPreviewSelectionPolicy.START_VISIBLE_FRACTION) return
        if (isScrolling(latestCandidate)) {
            dwellStarts.remove(login)
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
            val builtPlayer = mediaRuntime.buildPreviewPlayer(context, previewTrackSelectionParameters())
            player = builtPlayer
            val mediaItem = mediaRuntime.createLiveMediaItem(
                channelLogin = login,
                url = url,
                title = latestCandidate.title,
                channelName = latestCandidate.channelName,
                channelLogo = latestCandidate.channelLogo,
            )
            val active = ActivePreview(identity = login, player = player)
            activePreviews[login] = active
            previewLifecycle.track(login, SystemClock.elapsedRealtime())
            attachSurfaceIfNeeded(active, latestCandidate)
            builtPlayer.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    val current = activePreviews[login]
                    if (current?.player === builtPlayer) {
                        current.firstFrameRendered = true
                        current.surface?.let { surface ->
                            surface.alpha = 1f
                            surface.visibility = View.VISIBLE
                        }
                        if (BuildConfig.DEBUG) Log.d("StreamPreview", "first_frame channel=$login")
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (BuildConfig.DEBUG) Log.d("StreamPreview", "failed channel=$login type=${error.errorCodeName}")
                    handlePreviewFailure(login, builtPlayer)
                }
            })
            builtPlayer.setMediaItem(mediaItem)
            builtPlayer.volume = 0f
            builtPlayer.prepare()
            builtPlayer.playWhenReady = true
            if (BuildConfig.DEBUG) Log.d("StreamPreview", "started channel=$login quality=${StreamPreviewPolicy.quality(context)}")
        } catch (cancellation: CancellationException) {
            player?.let { runCatching { it.release() } }
            throw cancellation
        } catch (error: Throwable) {
            player?.let { runCatching { it.release() } }
            markPreviewFailure(login)
        }
    }

    private fun handlePreviewFailure(login: String, player: ExoPlayer) {
        if (activePreviews[login]?.player !== player) return
        markPreviewFailure(login)
        scheduleSelection()
    }

    private fun markPreviewFailure(login: String) {
        failedUntil[login] = SystemClock.elapsedRealtime() + PLAYER_FAILURE_RETRY_MS
        releasePreview(login)
    }

    private fun attachSurfaceIfNeeded(active: ActivePreview, candidate: StreamPreviewCandidate) {
        if (active.surface !== candidate.surface) {
            active.surface?.let { oldSurface ->
                oldSurface.player = null
                oldSurface.alpha = 0f
                oldSurface.visibility = View.GONE
            }
            active.surface = candidate.surface
        }
        candidate.surface.player = active.player
        candidate.surface.alpha = if (active.firstFrameRendered) 1f else 0f
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
            .mapNotNull { candidate -> candidate.takeIf { normalize(it.channelLogin) != null } }

    private fun maxActivePreviews(): Int =
        if (StreamPreviewPolicy.allowsMultiplePreviews(context)) {
            StreamPreviewSelectionPolicy.MAX_ACTIVE_PREVIEWS
        } else {
            1
        }

    private fun bestCandidatesByIdentity(candidates: Collection<StreamPreviewCandidate>): Map<String, StreamPreviewCandidate> =
        candidates
            .mapIndexed { index, candidate -> RankedCandidate(candidate, index) }
            .groupBy { normalize(it.candidate.channelLogin)!! }
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
            active.surface?.let { surface ->
                surface.player = null
                surface.alpha = 0f
                surface.visibility = View.GONE
            }
            runCatching { active.player.stop() }
            runCatching { active.player.release() }
        }
    }

    private fun normalize(login: String?): String? =
        login?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

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
        var surface: PlayerView? = null,
        var firstFrameRendered: Boolean = false,
    )

    private companion object {
        const val PLAYER_FAILURE_RETRY_MS = 10_000L
    }
}
