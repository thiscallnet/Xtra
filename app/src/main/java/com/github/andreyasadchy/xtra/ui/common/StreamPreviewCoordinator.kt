package com.github.andreyasadchy.xtra.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.media3.common.C as Media3C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.repository.preload.StreamMedia3Runtime
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewPolicy
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewMode
import com.github.andreyasadchy.xtra.repository.preload.StreamPreviewQuality
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
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

/** App-scoped owner of the only browsing preview player and decoder. */
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
    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    private var active: ActivePreview? = null
    private var pendingLogin: String? = null
    private var handoffLogin: String? = null
    private val dwellStarts = mutableMapOf<String, Long>()
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
        if (key !in setOf(C.STREAM_PREVIEW_MODE, C.STREAM_PREVIEW_QUALITY, C.STREAM_PREVIEW_DELAY)) return@OnSharedPreferenceChangeListener
        scope.launch {
            if (key == C.STREAM_PREVIEW_MODE && StreamPreviewPolicy.mode(context) == StreamPreviewMode.OFF) {
                stopPreview()
            } else {
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
        if (scrolling || !StreamPreviewPolicy.allowsNetwork(context)) {
            // Keep the latest candidates while policy temporarily blocks
            // playback. Connectivity/power callbacks can then resume the
            // preview without waiting for another layout publication.
            viewports[viewportKey] = Viewport(candidates.toList(), scrolling)
            stopPreview()
            return
        }
        viewports[viewportKey] = Viewport(candidates.toList(), scrolling = false)
        val visibleLogins = candidates.map { it.channelLogin.trim().lowercase() }.toSet()
        dwellStarts.keys.retainAll(visibleLogins)
        scheduleSelection()
    }

    fun detachViewport(viewportKey: String) {
        viewports.remove(viewportKey)
        if (active?.candidate?.viewportKey == viewportKey) stopPreview()
        scheduleSelection()
    }

    fun onScrolling(viewportKey: String) {
        viewports[viewportKey] = viewports[viewportKey]?.copy(scrolling = true) ?: Viewport(emptyList(), true)
        stopPreview()
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
        val login = channelLogin?.trim()?.lowercase()
        if (login == null || active?.candidate?.candidate?.channelLogin?.trim()?.lowercase() != login) {
            stopPreview()
            return
        }
        selectionJob?.cancel()
        selectionJob = null
        pendingLogin = null
        handoffLogin = login
    }

    fun onFullscreenPlaybackFirstFrame(channelLogin: String?) {
        val login = channelLogin?.trim()?.lowercase()
        if (handoffLogin != null && (login == null || handoffLogin == login)) {
            handoffLogin = null
            releaseActivePreview()
        }
    }

    fun onFullscreenPlaybackFailed() {
        handoffLogin = null
        stopPreview()
    }

    fun onPlaybackReturned() {
        stopPreview()
    }

    fun isPreviewing(channelLogin: String): Boolean =
        active?.candidate?.candidate?.channelLogin?.equals(channelLogin, ignoreCase = true) == true

    fun detachSurface(surface: PlayerView) {
        if (active?.candidate?.candidate?.surface === surface) stopPreview()
        surface.player = null
        surface.alpha = 0f
        surface.visibility = View.GONE
    }

    fun refresh() = scheduleSelection()

    private fun requestPolicyRecheck() {
        scope.launch {
            if (StreamPreviewPolicy.allowsNetwork(context)) scheduleSelection()
            else stopPreview()
        }
    }

    private fun scheduleSelection() {
        selectionJob?.cancel()
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
        )
        ) {
            if (handoffLogin != null) {
                // Keep the exact preview surface alive during the same-stream
                // fullscreen handoff, but cancel any competing selection.
                pendingLogin = null
            } else {
                stopPreview()
            }
            return
        }
        val allCandidates = viewports.values.flatMap { it.candidates }
        if (active != null && allCandidates.none {
                it.channelLogin.trim().equals(active?.candidate?.candidate?.channelLogin, ignoreCase = true)
            }) {
            stopPreview()
        }
        val winner = strongestCandidate() ?: run {
            stopPreview()
            return
        }
        val current = active?.candidate?.candidate
        if (current != null && current.channelLogin.equals(winner.channelLogin, ignoreCase = true)) {
            attachSurfaceIfNeeded(active!!, winner)
            return
        }
        if (current != null) {
            val currentScore = current.score()
            if (winner.score() < currentScore + StreamPreviewPolicy.HYSTERESIS_SCORE) return
        }
        val login = winner.channelLogin.trim().lowercase()
        val startedAt = dwellStarts.getOrPut(login) { SystemClock.elapsedRealtime() }
        pendingLogin = login
        val delayMs = (StreamPreviewPolicy.delay(context).delayMs - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
        val generation = ++selectionGeneration
        selectionJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val latest = strongestCandidate() ?: return@launch
            if (generation != selectionGeneration || latest.channelLogin.trim().lowercase() != login) return@launch
            startPreview(latest, generation)
        }
    }

    private fun strongestCandidate(): StreamPreviewCandidate? =
        viewports.values
            .filterNot { it.scrolling }
            .flatMap { viewport -> viewport.candidates.map { it to viewport } }
            .filter { (candidate, _) -> candidate.visibleFraction >= StreamPreviewPolicy.MIN_VISIBLE_FRACTION }
            .maxByOrNull { (candidate, _) -> candidate.score() }
            ?.first

    private suspend fun startPreview(candidate: StreamPreviewCandidate, generation: Long) {
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return
        releaseActivePreview()
        val login = candidate.channelLogin.trim().lowercase()
        if (pendingLogin != login || generation != selectionGeneration) return
        val url = urlCoordinator.resolveForPreview(login) ?: return
        if (!StreamPreviewPolicy.canStartPreview(
                isPlayerFullscreen = streamFeedRefreshCoordinator.isPlayerFullscreen,
                networkAllowed = StreamPreviewPolicy.allowsNetwork(context),
                handoffPending = handoffLogin != null,
            )
        ) return
        val latest = strongestCandidate() ?: return
        if (latest.channelLogin.trim().lowercase() != login || generation != selectionGeneration) return

        val trackParameters = TrackSelectionParameters.Builder(context).apply {
            setTrackTypeDisabled(Media3C.TRACK_TYPE_AUDIO, true)
            when (StreamPreviewPolicy.quality(context)) {
                StreamPreviewQuality.P360 -> setMaxVideoSize(640, 360)
                StreamPreviewQuality.P480 -> setMaxVideoSize(854, 480)
                StreamPreviewQuality.AUTO -> Unit
            }
        }.build()
        val player = mediaRuntime.buildPreviewPlayer(context, trackParameters)
        val mediaItem = mediaRuntime.createLiveMediaItem(
            channelLogin = login,
            url = url,
            title = candidate.title,
            channelName = candidate.channelName,
            channelLogo = candidate.channelLogo,
        )
        val activePreview = ActivePreview(
            candidate = CandidateWithViewport(candidate, findViewportKey(candidate)),
            player = player,
        )
        active = activePreview
        candidate.surface.player = player
        candidate.surface.alpha = 0f
        candidate.surface.visibility = View.VISIBLE
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (active?.player === player) {
                    active?.firstFrameRendered = true
                    candidate.surface.alpha = 1f
                    candidate.surface.visibility = View.VISIBLE
                    if (BuildConfig.DEBUG) Log.d("StreamPreview", "first_frame channel=$login")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (BuildConfig.DEBUG) Log.d("StreamPreview", "failed channel=$login type=${error.errorCodeName}")
                if (active?.player === player) stopPreview()
            }
        })
        player.setMediaItem(mediaItem)
        player.volume = 0f
        player.prepare()
        player.playWhenReady = true
        if (BuildConfig.DEBUG) Log.d("StreamPreview", "started channel=$login quality=${StreamPreviewPolicy.quality(context)}")
    }

    private fun attachSurfaceIfNeeded(activePreview: ActivePreview, candidate: StreamPreviewCandidate) {
        if (activePreview.candidate.candidate.surface !== candidate.surface) {
            activePreview.candidate.candidate.surface.player = null
            activePreview.candidate.candidate.surface.alpha = 0f
            activePreview.candidate.candidate.surface.visibility = View.GONE
            candidate.surface.player = activePreview.player
            candidate.surface.alpha = if (activePreview.firstFrameRendered) 1f else 0f
            candidate.surface.visibility = View.VISIBLE
            activePreview.candidate = CandidateWithViewport(candidate, findViewportKey(candidate))
        }
    }

    private fun findViewportKey(candidate: StreamPreviewCandidate): String =
        viewports.entries.firstOrNull { (_, viewport) -> viewport.candidates.any { it === candidate } }?.key.orEmpty()

    private fun stopPreview() {
        ++selectionGeneration
        pendingLogin = null
        handoffLogin = null
        selectionJob?.cancel()
        selectionJob = null
        releaseActivePreview()
    }

    private fun releaseActivePreview() {
        active?.let { current ->
            current.candidate.candidate.surface.player = null
            current.candidate.candidate.surface.alpha = 0f
            current.candidate.candidate.surface.visibility = View.GONE
            current.player.stop()
            current.player.release()
        }
        active = null
    }

    private data class Viewport(
        val candidates: List<StreamPreviewCandidate>,
        val scrolling: Boolean,
    )

    private data class CandidateWithViewport(
        val candidate: StreamPreviewCandidate,
        val viewportKey: String,
    )

    private data class ActivePreview(
        var candidate: CandidateWithViewport,
        val player: androidx.media3.exoplayer.ExoPlayer,
        var firstFrameRendered: Boolean = false,
    )

    private fun StreamPreviewCandidate.score(): Float =
        0.65f * visibleFraction.coerceIn(0f, 1f) + 0.35f * centerProximity.coerceIn(0f, 1f)
}
