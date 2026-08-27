package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

/** Cancels speculative work while preserving a promoted flight for the current configuration. */
internal fun cancelBrowsingFlights(
    resolver: StreamPreloadResolver,
    configurationFingerprint: String?,
) {
    configurationFingerprint?.let {
        resolver.cancelObsolete(configurationFingerprint = it, activeLogins = emptySet())
    } ?: resolver.cancelAll()
}

/**
 * App-scoped owner of stream resolve preloads. UI code only reports visible
 * candidates. This class owns dwell timing, request limits, cache lifetime and
 * cancellation so recycled view holders cannot create request storms.
 */
class StreamPreloadCoordinator(
    context: Context,
    private val playerRepository: PlayerRepository,
    private val streamFeedRefreshCoordinator: StreamFeedRefreshCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val mediaPreloadRuntime: StreamMedia3Runtime? = null,
) {
    private val context = context.applicationContext
    private val cache = StreamPreloadUrlCache(elapsedRealtimeMs = elapsedRealtimeMs)
    private val vodPreviewUrls = StreamPreloadUrlCache(
        maxEntries = StreamPreloadPolicy.MAX_VOD_PREVIEW_URLS,
        elapsedRealtimeMs = elapsedRealtimeMs,
    )
    private val urlOwnership = StreamPreloadUrlOwnership(cache)
    private val scheduledJobs = ConcurrentHashMap<String, Job>()
    private val dwellStarts = ConcurrentHashMap<String, Long>()
    private val viewports = ConcurrentHashMap<String, ViewportState>()
    private val mediaOperationLock = Any()
    private val mediaOperationGate = StreamMediaPreloadOperationGate()
    private var mediaReconcileJob: Job? = null

    @Volatile
    private var mediaPreloadKeepChannelLogin: String? = null

    @Volatile
    private var mediaPreloadsKnownCleared = false

    @Volatile
    private var configuration: StreamPlaybackConfiguration? = null

    @Volatile
    private var selectedChannelLogin: String? = null

    @Volatile
    private var previewActive = false

    private val resolver = StreamPreloadResolver(
        scope = scope,
        elapsedRealtimeMs = elapsedRealtimeMs,
        canStart = ::canPreload,
        isEligible = ::isEligible,
        onResolved = ::onResolverSuccess,
        onFailed = { key, error -> debug("failed:${error::class.simpleName}", key.channelLogin) },
    )

    fun updateViewport(viewportKey: String, candidates: Collection<StreamPreloadCandidate>, scrolling: Boolean) {
        if (preloadMode() == StreamPreloadMode.OFF && previewMode() == StreamPreviewMode.OFF) {
            detachViewport(viewportKey)
            return
        }
        val wasScrolling = viewports.values.any { it.scrolling }
        mediaPreloadKeepChannelLogin = null
        val now = elapsedRealtimeMs()
        val normalized = candidates
            .filter { it.channelLogin.isNotBlank() }
            .associateBy { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }
        viewports[viewportKey] = ViewportState(normalized, scrolling)
        val isScrolling = viewports.values.any { it.scrolling }
        if (isScrolling) {
            if (!wasScrolling) enterScrolling()
            return
        }
        normalized.keys.forEach { dwellStarts.putIfAbsent(it, now) }
        pruneDwellStarts(now)
        if (wasScrolling && preloadMode() == StreamPreloadMode.OFF) {
            clearMediaPreloads()
        } else if (preloadMode() != StreamPreloadMode.OFF) {
            reconcilePreloads(refreshConfiguration())
        }
    }

    fun detachViewport(viewportKey: String) {
        val wasScrolling = viewports.values.any { it.scrolling }
        viewports.remove(viewportKey)
        val isScrolling = viewports.values.any { it.scrolling }
        val activeKeys = viewports.values.flatMap { it.candidates.keys }.toSet()
        scheduledJobs.keys.filter { it !in activeKeys }.forEach { key ->
            scheduledJobs.remove(key)?.cancel()
            dwellStarts.remove(key)
        }
        if (isScrolling) {
            if (!wasScrolling) enterScrolling()
            return
        }
        if (viewports.isEmpty() || preloadMode() == StreamPreloadMode.OFF) {
            cancelScheduledJobs()
            cancelBrowsingFlights()
            clearMediaPreloads()
        } else if (wasScrolling || preloadMode() != StreamPreloadMode.OFF) {
            reconcilePreloads(refreshConfiguration())
        } else {
            clearMediaPreloads()
        }
    }

    fun setViewportScrolling(viewportKey: String, scrolling: Boolean) {
        val state = viewports[viewportKey] ?: return
        if (state.scrolling == scrolling) return
        val wasScrolling = viewports.values.any { it.scrolling }
        viewports[viewportKey] = state.copy(scrolling = scrolling)
        val isScrolling = viewports.values.any { it.scrolling }
        if (isScrolling) {
            val now = elapsedRealtimeMs()
            state.candidates.keys.forEach { dwellStarts[it] = now }
            if (!wasScrolling) enterScrolling()
        } else if (wasScrolling && preloadMode() != StreamPreloadMode.OFF) {
            reconcilePreloads(refreshConfiguration())
        } else if (wasScrolling) {
            clearMediaPreloads()
        }
    }

    fun onAppBackground() {
        viewports.clear()
        dwellStarts.clear()
        cancelScheduledJobs()
        cancelBrowsingFlights()
        clearMediaPreloads()
    }

    fun onAppForeground() {
        refreshConfiguration()
    }

    /** Keeps speculative sample loading out of the way while a preview is decoding. */
    fun setPreviewActive(active: Boolean) {
        if (previewActive == active) return
        previewActive = active
        if (active) {
            clearMediaPreloads()
        } else if (preloadMode() != StreamPreloadMode.OFF) {
            requestMediaPreloadReconcile()
        }
    }

    /** Stop browsing work when playback takes ownership, retaining only the selected flight. */
    fun onPlaybackEntered() {
        viewports.clear()
        dwellStarts.clear()
        cancelScheduledJobs()
        val selected = selectedChannelLogin
        selectedChannelLogin = null
        val config = refreshConfiguration()
        resolver.cancelAll(
            keepLogins = selected?.let(::setOf).orEmpty(),
            configurationFingerprint = config.fingerprint,
        )
        clearMediaPreloads(selected)
    }

    fun onStreamSelected(stream: Stream) {
        selectedChannelLogin = stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        selectedChannelLogin?.let { login ->
            val config = refreshConfiguration()
            if (resolver.promoteForPlayback(login, config.fingerprint)) debug("promoted", login)
        }
        debug("selected", stream.channelLogin)
    }

    suspend fun resolveForPlayback(channelLogin: String?): String? {
        val login = channelLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val config = refreshConfiguration()
        urlOwnership.forPlayback(login, config.fingerprint)?.let {
            debug("playback_hit_url", login)
            return it
        }
        resolver.joinForPlayback(login, config.fingerprint)?.let {
            debug("playback_join_url", login)
            return it
        }
        urlOwnership.forPlayback(login, config.fingerprint)?.let {
            debug("playback_hit_url", login)
            return it
        }
        debug("playback_miss", login)
        return null
    }

    /** Resolves only through the shared URL flight/cache. It never creates a second request. */
    suspend fun resolveForPreview(channelLogin: String?): String? {
        val login = channelLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val config = refreshConfiguration()
        // Preview is a reader, not the owner of the URL. Fullscreen playback must
        // still be able to consume this exact signed URL for Media3 handoff.
        urlOwnership.forPreview(login, config.fingerprint)?.let { return it }
        resolver.join(login, config.fingerprint)?.let { return it }
        if (!canResolvePreview()) return null
        val key = currentCandidates().firstOrNull { it.channelLogin.equals(login, true) }
            ?.streamKey
            ?: login
        return preloadUrl(login, key, forPreview = true)
    }

    /** Resolves a VOD playlist for a visible preview without consuming live URL ownership. */
    suspend fun resolveVodForPreview(videoId: String?): String? {
        val id = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val config = refreshConfiguration()
        if (!canResolvePreview()) return null
        vodPreviewUrls.get(id, config.fingerprint)?.let { return it }
        val url = runCatching {
            playerRepository.loadVideoPlaylistUrl(
                networkLibrary = config.networkLibrary,
                gqlHeaders = config.gqlHeaders,
                videoId = id,
                playerType = config.playerType,
                supportedCodecs = config.supportedCodecs,
            ).first
        }.getOrNull() ?: return null
        if (refreshConfiguration().fingerprint != config.fingerprint) return null
        vodPreviewUrls.put(id, url, config.fingerprint)
        return url
    }

    private fun reconcilePreloads(config: StreamPlaybackConfiguration) {
        val ranked = if (canPreload() && canResolveStream() && viewports.values.none { it.scrolling }) {
            StreamPreloadPolicy.rank(currentCandidates()).take(StreamPreloadPolicy.MAX_URL_CANDIDATES)
        } else {
            emptyList()
        }
        val activeKeys = ranked.map { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }.toSet()
        scheduledJobs.keys
            .filter { it !in activeKeys }
            .forEach { key -> scheduledJobs.remove(key)?.cancel() }
        resolver.cancelObsolete(
            configurationFingerprint = config.fingerprint,
            activeLogins = ranked.map { it.channelLogin.trim().lowercase() }.toSet(),
        )
        requestMediaPreloadReconcile()
        if (ranked.isEmpty()) {
            cancelScheduledJobs()
        } else {
            scheduleRankedCandidates(ranked, config)
        }
    }

    private fun scheduleRankedCandidates(
        ranked: List<StreamPreloadCandidate>,
        config: StreamPlaybackConfiguration,
    ) {
        ranked.forEach { candidate ->
            val key = candidate.streamKey.ifBlank { candidate.channelLogin.trim().lowercase() }
            if (scheduledJobs.containsKey(key) || cache.get(candidate.channelLogin, config.fingerprint) != null) return@forEach
            scheduledJobs[key] = scope.launch {
                try {
                    val dwellStart = dwellStarts[key] ?: elapsedRealtimeMs()
                    val remaining = StreamPreloadPolicy.URL_DWELL_MS - (elapsedRealtimeMs() - dwellStart)
                    if (remaining > 0) delay(remaining)
                    if (!isEligible(key)) return@launch
                    preloadUrl(candidate.channelLogin, key)
                } finally {
                    scheduledJobs.remove(key)
                }
            }
        }
    }

    private suspend fun preloadUrl(channelLogin: String, streamKey: String, forPreview: Boolean = false): String? {
        if (!(if (forPreview) canResolvePreview() else canResolveStream()) || !isEligible(streamKey, forPreview)) return null
        val config = refreshConfiguration()
        cache.get(channelLogin, config.fingerprint)?.let {
            debug("url_cache_hit", channelLogin)
            return it
        }
        if (config.customStreamProxyEnabled) {
            val directUrl = StreamPreloadPolicy.customStreamProxyUrl(config.customStreamProxyUrl, channelLogin)
            if (directUrl != null) {
                cache.put(channelLogin, directUrl, config.fingerprint)
                debug("custom_proxy_url_ready", channelLogin)
                return directUrl
            }
            return null
        }
        return resolver.preload(channelLogin, streamKey, config.fingerprint) {
            debug("url_start", channelLogin)
            playerRepository.loadStreamPlaylistUrl(
                context = context,
                networkLibrary = config.networkLibrary,
                gqlHeaders = config.gqlHeaders,
                channelLogin = channelLogin,
                randomDeviceId = config.randomDeviceId,
                xDeviceId = config.xDeviceId,
                playerType = config.playerType,
                supportedCodecs = config.supportedCodecs,
                proxyPlaybackAccessToken = config.proxyPlaybackAccessToken,
                proxyHost = config.proxyHost,
                proxyPort = config.proxyPort,
                proxyUser = config.proxyUser,
                proxyPassword = config.proxyPassword,
                lowLatency = config.lowLatency,
            )
        }
    }

    private fun onResolverSuccess(key: StreamPreloadFlightKey, url: String) {
        if (refreshConfiguration().fingerprint == key.configurationFingerprint) {
            cache.put(key.channelLogin, url, key.configurationFingerprint)
            debug("url_ready", key.channelLogin)
            if (preloadMode() != StreamPreloadMode.OFF) {
                requestMediaPreloadReconcile()
            }
        } else {
            debug("url_discarded_configuration_changed", key.channelLogin)
        }
    }

    private fun currentCandidates(): List<StreamPreloadCandidate> =
        viewports.values.flatMap { it.candidates.values }

    private fun requestMediaPreloadReconcile() {
        scheduleMediaOperation("media_reconcile") { runtime ->
            if (!mediaPreloadEligible()) {
                mediaPreloadsKnownCleared = true
                runtime.clearPreloads(mediaPreloadKeepChannelLogin)
                return@scheduleMediaOperation
            }

            val currentConfig = StreamPlaybackConfiguration.from(context)
            if (configuration?.fingerprint != currentConfig.fingerprint) {
                refreshConfiguration()
                return@scheduleMediaOperation
            }

            // Read the viewport and URL cache on Main immediately before handing
            // candidates to Media3. The resolver callback may have captured an
            // obsolete ranking while a scroll/background transition was queued.
            val ranked = StreamPreloadPolicy.rank(currentCandidates())
                .take(StreamPreloadPolicy.MAX_URL_CANDIDATES)
            val candidates = ranked.mapIndexedNotNull { rank, candidate ->
                cache.get(candidate.channelLogin, currentConfig.fingerprint)?.let { url ->
                    LiveMediaPreloadCandidate(
                        channelLogin = candidate.channelLogin,
                        url = url,
                        rank = rank,
                        title = candidate.title,
                        channelName = candidate.channelName,
                        channelLogo = candidate.channelLogo,
                    )
                }
            }
            if (!mediaPreloadEligible()) {
                mediaPreloadsKnownCleared = true
                runtime.clearPreloads(mediaPreloadKeepChannelLogin)
                return@scheduleMediaOperation
            }
            runtime.reconcile(candidates)
            mediaPreloadsKnownCleared = false
            mediaPreloadKeepChannelLogin = null
        }
    }

    private fun clearMediaPreloads(keepChannelLogin: String? = null) {
        val normalizedKeep = keepChannelLogin?.trim()?.lowercase()
        if (mediaPreloadsKnownCleared && mediaPreloadKeepChannelLogin == normalizedKeep) return
        mediaPreloadKeepChannelLogin = normalizedKeep
        mediaPreloadsKnownCleared = true
        debug("media_clear", mediaPreloadKeepChannelLogin)
        scheduleMediaOperation("media_clear") { runtime ->
            runtime.clearPreloads(mediaPreloadKeepChannelLogin)
        }
    }

    private fun mediaPreloadEligible(): Boolean =
        preloadMode() != StreamPreloadMode.OFF &&
            !previewActive &&
            viewports.isNotEmpty() &&
            viewports.values.none { it.scrolling } &&
            canPreload() &&
            canResolveStream()

    private fun scheduleMediaOperation(
        event: String,
        operation: (StreamMedia3Runtime) -> Unit,
    ) {
        val runtime = mediaPreloadRuntime ?: return
        synchronized(mediaOperationLock) {
            val epoch = mediaOperationGate.begin()
            mediaReconcileJob?.cancel()
            mediaReconcileJob = scope.launch(Dispatchers.Main) {
                yield()
                mediaOperationGate.runIfCurrent(epoch) {
                    runCatching { operation(runtime) }
                        .onFailure { debug("${event}_failed", it::class.simpleName) }
                }
            }
        }
    }

    private fun invalidateMediaOperations() {
        synchronized(mediaOperationLock) {
            mediaOperationGate.invalidate()
            mediaReconcileJob?.cancel()
            mediaReconcileJob = null
        }
    }

    private fun isEligible(streamKey: String): Boolean {
        return isEligible(streamKey, forPreview = false)
    }

    private fun isEligible(streamKey: String, forPreview: Boolean): Boolean {
        if (!(if (forPreview) canResolvePreview() else canPreload()) || viewports.values.any { it.scrolling }) return false
        val rankedKeys = StreamPreloadPolicy.rank(currentCandidates())
            .take(StreamPreloadPolicy.MAX_URL_CANDIDATES)
            .map { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }
        return streamKey in rankedKeys
    }

    private fun pruneDwellStarts(now: Long) {
        val visible = currentCandidates().map { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }.toSet()
        dwellStarts.entries.toList().forEach { (key, startedAt) ->
            if (key !in visible && now - startedAt > StreamPreloadPolicy.EVICTION_GRACE_MS) {
                dwellStarts.remove(key)
            }
        }
    }

    private fun cancelScheduledJobs() {
        scheduledJobs.values.forEach(Job::cancel)
        scheduledJobs.clear()
    }

    private fun cancelBrowsingFlights() {
        cancelBrowsingFlights(resolver, configuration?.fingerprint)
    }

    private fun enterScrolling() {
        debug("scroll_enter", null)
        cancelScheduledJobs()
        cancelBrowsingFlights()
        clearMediaPreloads()
    }

    private fun refreshConfiguration(): StreamPlaybackConfiguration {
        val next = StreamPlaybackConfiguration.from(context)
        if (configuration?.fingerprint != next.fingerprint) {
            cache.setConfiguration(next.fingerprint)
            vodPreviewUrls.clear()
            resolver.cancelAll()
            invalidateMediaOperations()
            mediaPreloadRuntime?.let { runtime ->
                scheduleMediaOperation("media_configuration_invalidation") { runtime.invalidateConfiguration() }
            }
            debug("configuration_changed", null)
        }
        configuration = next
        return next
    }

    private fun preloadMode(): StreamPreloadMode =
        StreamPreloadMode.fromPreference(context.prefs().getString(C.STREAM_PRELOAD_MODE, StreamPreloadMode.WIFI_ONLY.preferenceValue))

    private fun previewMode(): StreamPreviewMode =
        StreamPreviewPolicy.mode(context)

    private fun canPreload(): Boolean {
        if (!canResolveStream() && !canResolvePreview()) return false
        val prefs = context.prefs()
        if (prefs.getBoolean(C.PLAYER_STREAM_PROXY, false) && prefs.getString(C.PLAYER_PROXY_URL, null).isNullOrBlank()) return false
        if ((context as? XtraApp)?.isInForeground == false) return false
        if (streamFeedRefreshCoordinator.isPlayerFullscreen || streamFeedRefreshCoordinator.isPlayerActive) return false
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager?.isPowerSaveMode == true) return false
        return true
    }

    private fun canResolveStream(): Boolean =
        canResolveNetwork(preloadMode())

    private fun canResolvePreview(): Boolean =
        canResolveNetwork(
            when (previewMode()) {
                StreamPreviewMode.OFF -> StreamPreloadMode.OFF
                StreamPreviewMode.WIFI_ONLY -> StreamPreloadMode.WIFI_ONLY
                StreamPreviewMode.WIFI_AND_MOBILE -> StreamPreloadMode.WIFI_AND_MOBILE
            }
        )

    private fun canResolveNetwork(mode: StreamPreloadMode): Boolean {
        if (mode == StreamPreloadMode.OFF) return false
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return when (mode) {
            StreamPreloadMode.OFF -> false
            StreamPreloadMode.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            StreamPreloadMode.WIFI_AND_MOBILE -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private fun debug(event: String, channelLogin: String?) {
        if (BuildConfig.DEBUG) Log.d("StreamPreload", "$event${channelLogin?.let { " channel=$it" }.orEmpty()}")
    }

    private data class ViewportState(
        val candidates: Map<String, StreamPreloadCandidate>,
        val scrolling: Boolean,
    )

}
