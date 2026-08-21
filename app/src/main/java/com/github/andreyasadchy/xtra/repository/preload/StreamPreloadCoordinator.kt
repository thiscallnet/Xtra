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
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
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
) {
    private val context = context.applicationContext
    private val cache = StreamPreloadUrlCache(elapsedRealtimeMs = elapsedRealtimeMs)
    private val scheduledJobs = ConcurrentHashMap<String, Job>()
    private val dwellStarts = ConcurrentHashMap<String, Long>()
    private val viewports = ConcurrentHashMap<String, ViewportState>()

    @Volatile
    private var configuration: PlaybackPreloadConfiguration? = null

    @Volatile
    private var selectedChannelLogin: String? = null

    private val resolver = StreamPreloadResolver(
        scope = scope,
        elapsedRealtimeMs = elapsedRealtimeMs,
        canStart = ::canPreload,
        isEligible = ::isEligible,
        onResolved = ::onResolverSuccess,
        onFailed = { key, error -> debug("failed:${error::class.simpleName}", key.channelLogin) },
    )

    fun updateViewport(viewportKey: String, candidates: Collection<StreamPreloadCandidate>, scrolling: Boolean) {
        if (preloadMode() == StreamPreloadMode.OFF) {
            detachViewport(viewportKey)
            return
        }
        val now = elapsedRealtimeMs()
        val normalized = candidates
            .filter { it.channelLogin.isNotBlank() }
            .associateBy { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }
        viewports[viewportKey] = ViewportState(normalized, scrolling)
        if (scrolling) {
            normalized.keys.forEach { dwellStarts[it] = now }
        } else {
            normalized.keys.forEach { dwellStarts.putIfAbsent(it, now) }
        }
        pruneDwellStarts(now)
        if (scrolling) {
            cancelScheduledJobs()
            configuration?.fingerprint?.let { resolver.cancelObsolete(it, emptySet()) } ?: resolver.cancelAll()
        } else {
            reconcilePreloads(refreshConfiguration())
        }
    }

    fun detachViewport(viewportKey: String) {
        viewports.remove(viewportKey)
        val activeKeys = viewports.values.flatMap { it.candidates.keys }.toSet()
        scheduledJobs.keys.filter { it !in activeKeys }.forEach { key ->
            scheduledJobs.remove(key)?.cancel()
            dwellStarts.remove(key)
        }
        if (viewports.isEmpty() || preloadMode() == StreamPreloadMode.OFF) {
            cancelScheduledJobs()
            cancelBrowsingFlights()
        } else {
            reconcilePreloads(refreshConfiguration())
        }
    }

    fun setViewportScrolling(viewportKey: String, scrolling: Boolean) {
        val state = viewports[viewportKey] ?: return
        if (state.scrolling == scrolling) return
        viewports[viewportKey] = state.copy(scrolling = scrolling)
        if (scrolling) {
            val now = elapsedRealtimeMs()
            state.candidates.keys.forEach { dwellStarts[it] = now }
            cancelScheduledJobs()
            configuration?.fingerprint?.let { resolver.cancelObsolete(it, emptySet()) } ?: resolver.cancelAll()
        } else {
            reconcilePreloads(refreshConfiguration())
        }
    }

    fun onAppBackground() {
        viewports.clear()
        dwellStarts.clear()
        cancelScheduledJobs()
        cancelBrowsingFlights()
    }

    fun onAppForeground() {
        refreshConfiguration()
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
        cache.take(login, config.fingerprint)?.let {
            debug("playback_hit_url", login)
            return it
        }
        resolver.joinForPlayback(login, config.fingerprint)?.let {
            debug("playback_join_url", login)
            return it
        }
        cache.take(login, config.fingerprint)?.let {
            debug("playback_hit_url", login)
            return it
        }
        debug("playback_miss", login)
        return null
    }

    private fun reconcilePreloads(config: PlaybackPreloadConfiguration) {
        val ranked = if (canPreload() && viewports.values.none { it.scrolling }) {
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
        if (ranked.isEmpty()) {
            cancelScheduledJobs()
        } else {
            scheduleRankedCandidates(ranked, config)
        }
    }

    private fun scheduleRankedCandidates(
        ranked: List<StreamPreloadCandidate>,
        config: PlaybackPreloadConfiguration,
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

    private suspend fun preloadUrl(channelLogin: String, streamKey: String): String? {
        if (!canPreload() || !isEligible(streamKey)) return null
        val config = refreshConfiguration()
        cache.get(channelLogin, config.fingerprint)?.let {
            debug("url_cache_hit", channelLogin)
            return it
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
                enableIntegrity = config.enableIntegrity,
            )
        }
    }

    private fun onResolverSuccess(key: StreamPreloadFlightKey, url: String) {
        if (refreshConfiguration().fingerprint == key.configurationFingerprint) {
            cache.put(key.channelLogin, url, key.configurationFingerprint)
            debug("url_ready", key.channelLogin)
        } else {
            debug("url_discarded_configuration_changed", key.channelLogin)
        }
    }

    private fun currentCandidates(): List<StreamPreloadCandidate> =
        viewports.values.flatMap { it.candidates.values }

    private fun isEligible(streamKey: String): Boolean {
        if (!canPreload() || viewports.values.any { it.scrolling }) return false
        val rankedKeys = StreamPreloadPolicy.rank(currentCandidates())
            .take(StreamPreloadPolicy.MAX_URL_CANDIDATES)
            .map { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }
        return streamKey in rankedKeys
    }

    private fun pruneDwellStarts(now: Long) {
        val visible = currentCandidates().map { it.streamKey.ifBlank { it.channelLogin.trim().lowercase() } }.toSet()
        dwellStarts.entries.removeIf { (key, startedAt) ->
            key !in visible && now - startedAt > StreamPreloadPolicy.EVICTION_GRACE_MS
        }
    }

    private fun cancelScheduledJobs() {
        scheduledJobs.values.forEach(Job::cancel)
        scheduledJobs.clear()
    }

    private fun cancelBrowsingFlights() {
        cancelBrowsingFlights(resolver, configuration?.fingerprint)
    }

    private fun refreshConfiguration(): PlaybackPreloadConfiguration {
        val prefs = context.prefs()
        val next = PlaybackPreloadConfiguration(
            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                context,
                prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
            ),
            randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
            xDeviceId = prefs.getString(C.TOKEN_X_DEVICE_ID, C.DEFAULT_TOKEN_X_DEVICE_ID),
            playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, C.DEFAULT_TOKEN_PLAYER_TYPE),
            supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, C.DEFAULT_TOKEN_SUPPORTED_CODECS),
            proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
            proxyHost = prefs.httpProxyHost(),
            proxyPort = prefs.httpProxyPort(),
            proxyUser = prefs.getString(C.PROXY_USER, null),
            proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            customStreamProxyEnabled = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false),
            customStreamProxyUrl = prefs.getString(C.PLAYER_PROXY_URL, null),
        )
        if (configuration?.fingerprint != next.fingerprint) {
            cache.setConfiguration(next.fingerprint)
            resolver.cancelAll()
            debug("configuration_changed", null)
        }
        configuration = next
        return next
    }

    private fun preloadMode(): StreamPreloadMode =
        StreamPreloadMode.fromPreference(context.prefs().getString(C.STREAM_PRELOAD_MODE, StreamPreloadMode.WIFI_ONLY.preferenceValue))

    private fun canPreload(): Boolean {
        if (preloadMode() == StreamPreloadMode.OFF) return false
        val prefs = context.prefs()
        if (!StreamPreloadPolicy.allowsTwitchUrlPreload(
                customStreamProxyEnabled = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false),
                customStreamProxyUrl = prefs.getString(C.PLAYER_PROXY_URL, null),
            )
        ) return false
        if ((context as? XtraApp)?.isInForeground == false) return false
        if (streamFeedRefreshCoordinator.isPlayerFullscreen) return false
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager?.isPowerSaveMode == true) return false
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return when (preloadMode()) {
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

    private data class PlaybackPreloadConfiguration(
        val networkLibrary: String?,
        val gqlHeaders: Map<String, String>,
        val randomDeviceId: Boolean?,
        val xDeviceId: String?,
        val playerType: String?,
        val supportedCodecs: String?,
        val proxyPlaybackAccessToken: Boolean,
        val proxyHost: String?,
        val proxyPort: Int?,
        val proxyUser: String?,
        val proxyPassword: String?,
        val enableIntegrity: Boolean,
        val customStreamProxyEnabled: Boolean,
        val customStreamProxyUrl: String?,
    ) {
        val fingerprint: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = buildString {
                append(networkLibrary).append('\u0000')
                gqlHeaders.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append('\u0000') }
                append(randomDeviceId).append('\u0000')
                append(xDeviceId).append('\u0000')
                append(playerType).append('\u0000')
                append(supportedCodecs).append('\u0000')
                append(proxyPlaybackAccessToken).append('\u0000')
                append(proxyHost).append('\u0000').append(proxyPort).append('\u0000')
                append(proxyUser).append('\u0000').append(proxyPassword).append('\u0000')
                append(enableIntegrity).append('\u0000')
                append(customStreamProxyEnabled).append('\u0000').append(customStreamProxyUrl)
            }
            digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
