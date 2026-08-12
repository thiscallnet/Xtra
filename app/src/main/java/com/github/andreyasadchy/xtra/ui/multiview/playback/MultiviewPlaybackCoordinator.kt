package com.github.andreyasadchy.xtra.ui.multiview.playback

import android.content.Context
import android.util.Log
import android.annotation.SuppressLint
import com.github.andreyasadchy.xtra.BuildConfig
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shouldAvoidTwitchAds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import okhttp3.Credentials
import okhttp3.Call
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.math.min

class MultiviewPlaybackCoordinator(
    context: Context,
    private val loadPlaylist: suspend (String) -> String,
    private val loadCleanPlaylist: suspend (String, List<String>) -> String?,
    private val onSnapshot: (String, MultiviewPlaybackSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = linkedMapOf<String, MultiviewPlayerSlot>()
    private val tileBounds = mutableMapOf<String, Pair<Int, Int>>()
    private var activeIdentity: String? = null
    private var focusedIdentity: String? = null
    private var qualityMode = MultiviewQualityMode.SMART
    private var qualityOverrides: Map<String, String> = emptyMap()
    private var foreground = true

    fun sync(
        streams: List<Stream>,
        activeIdentity: String?,
        focusedIdentity: String?,
        qualityMode: MultiviewQualityMode,
        qualityOverrides: Map<String, String>,
    ) {
        this.activeIdentity = activeIdentity
        this.focusedIdentity = focusedIdentity
        this.qualityMode = qualityMode
        this.qualityOverrides = qualityOverrides
        val desired = streams.mapNotNull { stream ->
            identityOf(stream)?.let { identity -> identity to stream }
        }.toMap()

        slots.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            slots.remove(identity)?.release()
            tileBounds.remove(identity)
        }
        desired.forEach { (identity, stream) ->
            val slot = slots[identity]
            if (slot == null) {
                val newSlot = createSlot(identity, stream)
                slots[identity] = newSlot
                start(newSlot)
            } else if (slot.stream !== stream) {
                slot.stream = stream
            }
        }
        slots.values.forEach { applyQuality(it) }
        updateAudio()
    }

    fun attach(identity: String, playerView: PlayerView) {
        slots[identity]?.let { slot ->
            if (slot.attachedView !== playerView) {
                slot.attachedView?.player = null
                slot.attachedView = playerView
                playerView.player = slot.player
            }
        }
    }

    fun detach(identity: String, playerView: PlayerView) {
        slots[identity]?.takeIf { it.attachedView === playerView }?.let { slot ->
            playerView.player = null
            slot.attachedView = null
        }
    }

    fun updateTileBounds(identity: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        tileBounds[identity] = width to height
        slots[identity]?.let(::applyQuality)
    }

    fun retry(identity: String) {
        slots[identity]?.let { slot ->
            slot.retryCount = 0
            slot.manualRetry = false
            slot.downgradeLevel = min(slot.downgradeLevel, 1)
            slot.retryJob?.cancel()
            slot.loadJob?.cancel()
            start(slot, force = true)
        }
    }

    fun onStart() {
        foreground = true
        slots.values.forEach { slot ->
            if (slot.shouldPlay) slot.player.playWhenReady = true
        }
    }

    fun onStop() {
        foreground = false
        slots.values.forEach { slot ->
            slot.shouldPlay = slot.player.playWhenReady
            slot.player.playWhenReady = false
        }
    }

    fun releaseAll() {
        scope.coroutineContext[Job]?.cancel()
        slots.values.forEach(MultiviewPlayerSlot::release)
        slots.clear()
        tileBounds.clear()
    }

    fun player(identity: String): ExoPlayer? = slots[identity]?.player

    fun availableQualities(identity: String): List<String> = slots[identity]?.availableQualities.orEmpty()

    fun currentTarget(identity: String): MultiviewQualityTarget? = slots[identity]?.target

    private fun createSlot(identity: String, stream: Stream): MultiviewPlayerSlot {
        val player = ExoPlayer.Builder(applicationContext).apply {
            setLoadControl(
                DefaultLoadControl.Builder().apply {
                    setBufferDurationsMs(
                        15000,
                        50000,
                        2000,
                        2000,
                    )
                }.build()
            )
            setAudioAttributes(
                AudioAttributes.DEFAULT,
                false,
            )
            setHandleAudioBecomingNoisy(true)
        }.build()
        return MultiviewPlayerSlot(identity, stream, player).also { slot ->
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            slot.recordRebuffer()
                            if (slot.shouldDowngrade()) {
                                slot.downgradeLevel = (slot.downgradeLevel + 1).coerceAtMost(MAX_DOWNGRADE_LEVEL)
                                applyQuality(slot)
                            }
                            publish(slot, MultiviewSlotStatus.BUFFERING)
                        }
                        Player.STATE_READY -> {
                            slot.retryCount = 0
                            slot.manualRetry = false
                            publish(slot, MultiviewSlotStatus.LIVE)
                            logSelectedFormat(slot, player.videoFormat)
                        }
                        Player.STATE_ENDED -> publish(slot, MultiviewSlotStatus.OFFLINE)
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    updateFormats(slot)
                    logSelectedFormat(slot, player.videoFormat)
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerError(slot, error)
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    debugLog("channel=${slot.identity} decoder=$decoderName initMs=$initializationDurationMs")
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    debugLog("channel=${slot.identity} droppedFrames=$droppedFrames elapsedMs=$elapsedMs")
                }

                override fun onDownstreamFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    mediaLoadData: MediaLoadData,
                ) {
                    if (mediaLoadData.trackType == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                        logSelectedFormat(slot, mediaLoadData.trackFormat)
                    }
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    Log.w(
                        TAG,
                        "channel=${slot.identity} loadError type=${mediaLoadData.dataType} canceled=$wasCanceled uri=${loadEventInfo.uri}",
                        error,
                    )
                }
            })
        }
    }

    private fun start(slot: MultiviewPlayerSlot, force: Boolean = false) {
        slot.retryJob?.cancel()
        slot.loadJob?.cancel()
        slot.target = null
        slot.effectiveQualityLabel = null
        if (force) {
            slot.player.stop()
            slot.hasMediaSource = false
            slot.reloadRequired = false
        }
        slot.loadJob = scope.launch {
            publish(slot, MultiviewSlotStatus.LOADING)
            try {
                val login = slot.stream.channelLogin?.trim()?.lowercase()
                    ?: throw IllegalArgumentException("missing channel login")
                slot.customProxy = applicationContext.prefs().getBoolean(C.PLAYER_STREAM_PROXY, false) &&
                    !applicationContext.prefs().getString(C.PLAYER_PROXY_URL, null).isNullOrBlank()
                val playlistUrl = loadPlaylistUrl(login)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                val mediaItem = MediaItem.Builder()
                    .setUri(playlistUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().apply {
                            setTargetOffsetMs(2000L)
                        }.build()
                    )
                    .setMediaMetadata(metadata(slot.stream))
                    .build()
                val source = HlsMediaSource.Factory(createDataSourceFactory())
                    .setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                    .createMediaSource(mediaItem)
                slot.player.setMediaSource(source)
                slot.hasMediaSource = true
                applyQuality(slot)
                slot.player.volume = if (slot.identity == activeIdentity) activeVolume() else 0f
                slot.player.prepare()
                slot.player.playWhenReady = foreground && slot.shouldPlay
                debugLog("channel=${slot.identity} playlistLoaded quality=${slot.target?.label} urlType=${if (slot.customProxy) "proxy" else "usher"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleLoadError(slot, error)
            }
        }
    }

    private suspend fun loadPlaylistUrl(login: String): String {
        val preferences = applicationContext.prefs()
        val proxyUrl = preferences.getString(C.PLAYER_PROXY_URL, null)
        val useCustomProxy = preferences.getBoolean(C.PLAYER_STREAM_PROXY, false) && !proxyUrl.isNullOrBlank()
        return if (useCustomProxy) {
            proxyUrl.replace("\$channel", login)
        } else if (preferences.shouldAvoidTwitchAds()) {
            loadCleanPlaylist(login, listOf(preferences.getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"))
                ?: loadPlaylist(login)
        } else {
            loadPlaylist(login)
        }
    }

    private fun handleLoadError(slot: MultiviewPlayerSlot, error: Exception) {
        Log.w(TAG, "channel=${slot.identity} playlist load failed", error)
        handleFailure(slot, error, isOffline = false)
    }

    private fun handlePlayerError(slot: MultiviewPlayerSlot, error: PlaybackException) {
        Log.w(TAG, "channel=${slot.identity} player error code=${error.errorCodeName}", error)
        val responseCode = error.cause?.let { cause ->
            generateSequence(cause) { it.cause }.mapNotNull { throwable ->
                (throwable as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            }.firstOrNull()
        }
        val behindLiveWindow = error.errorCodeName.contains("BEHIND_LIVE_WINDOW", true)
        if (behindLiveWindow) {
            slot.reloadRequired = true
        }
        val offline = responseCode == 404 || responseCode == 410
        val decoderFailure = error.errorCodeName.contains("DECODER", true)
        if (decoderFailure) {
            slot.resourceFailure = true
            slot.downgradeLevel = (slot.downgradeLevel + 1).coerceAtMost(MAX_DOWNGRADE_LEVEL)
            applyQuality(slot)
        }
        handleFailure(slot, error, offline)
    }

    private fun handleFailure(slot: MultiviewPlayerSlot, error: Throwable, isOffline: Boolean) {
        if (isOffline) {
            publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
            return
        }
        if (slot.retryCount >= MAX_RETRIES) {
            slot.manualRetry = true
            publish(slot, MultiviewSlotStatus.PLAYBACK_UNAVAILABLE, retryAvailable = true)
            return
        }
        val delayMs = RETRY_DELAYS_MS[slot.retryCount.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
        slot.retryCount++
        publish(slot, MultiviewSlotStatus.RECONNECTING, retryAvailable = true)
        slot.retryJob?.cancel()
        slot.retryJob = scope.launch {
            delay(delayMs)
            if (!isActive || slots[slot.identity] !== slot) return@launch
            try {
                if (slot.hasMediaSource && !slot.reloadRequired) {
                    slot.player.prepare()
                    slot.player.playWhenReady = foreground && slot.shouldPlay
                } else {
                    slot.reloadRequired = false
                    start(slot)
                }
            } catch (retryError: Exception) {
                handleFailure(slot, retryError, false)
            }
        }
    }

    private fun applyQuality(slot: MultiviewPlayerSlot) {
        val bounds = tileBounds[slot.identity]
        val target = MultiviewQualityPolicy.target(
            MultiviewQualityInput(
                streamCount = slots.size,
                isActive = slot.identity == activeIdentity,
                isFocused = slot.identity == focusedIdentity,
                tileWidthPx = bounds?.first ?: 0,
                tileHeightPx = bounds?.second ?: 0,
                mode = qualityMode,
                manualOverride = qualityOverrides[slot.identity],
                bufferingDowngradeLevel = slot.downgradeLevel,
                resourcePressure = slot.resourceFailure,
            )
        )
        if (slot.target == target) return
        slot.target = target
        val builder = slot.player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setMaxVideoFrameRate(target.maxFrameRate)
            .setMaxVideoBitrate(Int.MAX_VALUE)
        if (target.maxWidthPx != null && target.maxHeightPx != null) {
            builder.setMaxVideoSize(target.maxWidthPx, target.maxHeightPx)
        }
        slot.player.trackSelectionParameters = builder.build()
        onSnapshot(slot.identity, slot.snapshot(target.label))
        debugLog("channel=${slot.identity} policy mode=$qualityMode streams=${slots.size} active=${slot.identity == activeIdentity} focus=${slot.identity == focusedIdentity} target=${target.label} downgrade=${slot.downgradeLevel}")
    }

    private fun updateAudio() {
        val volume = activeVolume()
        slots.values.forEach { slot ->
            slot.player.volume = if (slot.identity == activeIdentity) volume else 0f
        }
    }

    private fun updateFormats(slot: MultiviewPlayerSlot) {
        val manifest = slot.player.currentManifest as? HlsManifest
        val formats = manifest?.multivariantPlaylist?.variants.orEmpty().mapNotNull { variant ->
            variant.format.height.takeIf { it > 0 }?.let { height ->
                MultiviewQualityPolicy.AvailableFormat(
                    height = height,
                    frameRate = variant.format.frameRate,
                    isSource = variant.format.label.equals("Source", true),
                )
            }
        }
        slot.availableQualities = MultiviewQualityPolicy.availableManualLabels(formats)
        publish(slot, slot.status)
    }

    private fun publish(
        slot: MultiviewPlayerSlot,
        status: MultiviewSlotStatus,
        retryAvailable: Boolean = false,
    ) {
        slot.status = status
        onSnapshot(slot.identity, slot.snapshot(slot.target?.label ?: "AUTO", retryAvailable))
    }

    private fun logSelectedFormat(slot: MultiviewPlayerSlot, format: Format?) {
        if (format == null || format.width <= 0 || format.height <= 0) return
        slot.effectiveQualityLabel = MultiviewQualityPolicy.effectiveFormatLabel(format.width, format.height, format.frameRate)
        onSnapshot(slot.identity, slot.snapshot(slot.effectiveQualityLabel!!))
        debugLog("channel=${slot.identity} selectedFormat=${format.width}x${format.height} fps=${format.frameRate} bitrate=${format.bitrate} codec=${format.sampleMimeType ?: format.codecs}")
    }

    private fun metadata(stream: Stream): MediaMetadata = MediaMetadata.Builder()
        .setTitle(stream.title)
        .setArtist(stream.channelName ?: stream.channelLogin)
        .build()

    private fun activeVolume(): Float {
        return (applicationContext.prefs().getInt(C.PLAYER_VOLUME, 100) / 100f).coerceIn(0f, 1f)
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    @SuppressLint("NewApi")
    private fun createDataSourceFactory(): DataSource.Factory {
        val application = applicationContext as XtraApp
        val module = application.xtraModule
        val preferences = applicationContext.prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val proxyHost = preferences.getString(C.PROXY_HOST, null)
        val proxyPort = preferences.getString(C.PROXY_PORT, null)?.toIntOrNull()
        val proxyUser = preferences.getString(C.PROXY_USER, null)
        val proxyPassword = preferences.getString(C.PROXY_PASSWORD, null)
        val proxyMultivariant = preferences.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) &&
            !proxyHost.isNullOrBlank() && proxyPort != null
        val proxyMedia = preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) &&
            !proxyHost.isNullOrBlank() && proxyPort != null
        val headers = preferences.getString(C.PLAYER_STREAM_HEADERS, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                hashMapOf<String, String>().apply {
                    json.keys().forEach { key -> put(key, json.optString(key)) }
                }
            }.getOrNull()
        }
        val multivariantProxy = selectiveProxyClient(
            enabled = proxyMultivariant,
            host = proxyHost,
            port = proxyPort,
            user = proxyUser,
            password = proxyPassword,
            regex = MULTIVARIANT_PLAYLIST_REGEX,
        )
        val mediaProxy = selectiveProxyClient(
            enabled = proxyMedia,
            host = proxyHost,
            port = proxyPort,
            user = proxyUser,
            password = proxyPassword,
            regex = MEDIA_PLAYLIST_REGEX,
        )
        val upstream = when {
            networkLibrary == C.HTTP_ENGINE && module.httpEngine.value != null -> {
                HttpEngineDataSource.Factory(
                    module.httpEngine.value,
                    module.cronetExecutor.value,
                    proxyMultivariant,
                    proxyMedia,
                    null,
                    multivariantProxy,
                    mediaProxy,
                ) { preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                    .apply { headers?.let(::setDefaultRequestProperties) }
            }
            networkLibrary == C.CRONET && module.cronetEngine.value != null -> {
                CronetDataSource.Factory(
                    module.cronetEngine.value,
                    module.cronetExecutor.value,
                    proxyMultivariant,
                    proxyMedia,
                    null,
                    multivariantProxy,
                    mediaProxy,
                ) { preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                    .apply { headers?.let(::setDefaultRequestProperties) }
            }
            else -> OkHttpDataSource.Factory(
                multivariantProxy ?: module.okHttpClient.value,
                mediaProxy,
            ) { preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                .apply { headers?.let(::setDefaultRequestProperties) }
        }
        return DefaultDataSource.Factory(applicationContext, upstream)
    }

    private fun selectiveProxyClient(
        enabled: Boolean,
        host: String?,
        port: Int?,
        user: String?,
        password: String?,
        regex: String,
    ): Call.Factory? {
        if (!enabled || host.isNullOrBlank() || port == null) return null
        return (applicationContext as XtraApp).xtraModule.okHttpClient.value.newBuilder().apply {
            proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    return if (Regex(regex).matches(uri.host.orEmpty())) {
                        listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)), Proxy.NO_PROXY)
                    } else {
                        listOf(Proxy.NO_PROXY)
                    }
                }

                override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
            })
            if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, password))
                        .build()
                }
            }
        }.build()
    }

    private fun identityOf(stream: Stream): String? {
        return stream.channelId?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase()}" }
            ?: stream.channelLogin?.trim()?.takeIf { it.isNotBlank() }?.let { "login:${it.lowercase()}" }
            ?: stream.id?.takeIf { it.isNotBlank() }?.let { "stream:${it.lowercase()}" }
    }

    class MultiviewPlayerSlot(
        val identity: String,
        var stream: Stream,
        val player: ExoPlayer,
    ) {
        var attachedView: PlayerView? = null
        var loadJob: Job? = null
        var retryJob: Job? = null
        var retryCount: Int = 0
        var downgradeLevel: Int = 0
        var resourceFailure: Boolean = false
        var customProxy: Boolean = false
        var reloadRequired: Boolean = false
        var effectiveQualityLabel: String? = null
        var manualRetry: Boolean = false
        var shouldPlay: Boolean = true
        var hasMediaSource: Boolean = false
        var status: MultiviewSlotStatus = MultiviewSlotStatus.LOADING
        var target: MultiviewQualityTarget? = null
        var availableQualities: List<String> = emptyList()
        private val rebufferTimes = ArrayDeque<Long>()

        fun recordRebuffer(now: Long = System.currentTimeMillis()) {
            rebufferTimes.addLast(now)
            while (rebufferTimes.firstOrNull()?.let { now - it > REBUFFER_WINDOW_MS } == true) {
                rebufferTimes.removeFirst()
            }
        }

        fun shouldDowngrade(): Boolean = rebufferTimes.size >= REBUFFER_THRESHOLD

        fun snapshot(qualityLabel: String, retryAvailable: Boolean = manualRetry) = MultiviewPlaybackSnapshot(
            status = status,
            qualityLabel = qualityLabel,
            availableQualities = availableQualities,
            retryCount = retryCount,
            retryAvailable = retryAvailable,
        )

        fun release() {
            loadJob?.cancel()
            retryJob?.cancel()
            attachedView?.player = null
            attachedView = null
            player.release()
        }
    }

    companion object {
        private const val TAG = "MultiviewPlayback"
        private const val MAX_RETRIES = 3
        private const val MAX_DOWNGRADE_LEVEL = 2
        private const val REBUFFER_THRESHOLD = 3
        private const val REBUFFER_WINDOW_MS = 60_000L
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L)
        private const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        private const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"
    }
}

enum class MultiviewSlotStatus {
    LOADING,
    LIVE,
    BUFFERING,
    RECONNECTING,
    OFFLINE,
    PLAYBACK_UNAVAILABLE,
}

data class MultiviewPlaybackSnapshot(
    val status: MultiviewSlotStatus,
    val qualityLabel: String,
    val availableQualities: List<String> = emptyList(),
    val retryCount: Int = 0,
    val retryAvailable: Boolean = false,
)
