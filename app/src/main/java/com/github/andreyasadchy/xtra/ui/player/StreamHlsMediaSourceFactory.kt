package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsField
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFieldKey
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsPhase
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.ui.common.diagnosticToken
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsDiagnosticsSink
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistContinuityStore
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistDiagnostics
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.player.hls.ProbedPlaylistDataSource
import com.github.andreyasadchy.xtra.player.hls.ControlledVaftPlaylist
import com.github.andreyasadchy.xtra.player.hls.HiddenStreamAudioPlaylist
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.StreamRequestObservation
import com.github.andreyasadchy.xtra.player.lowlatency.StreamRequestRouteTracker
import com.github.andreyasadchy.xtra.player.lowlatency.TwitchHlsRequestRules.MEDIA_PLAYLIST_REGEX
import com.github.andreyasadchy.xtra.player.lowlatency.TwitchHlsRequestRules.MULTIVARIANT_PLAYLIST_REGEX
import com.github.andreyasadchy.xtra.repository.preload.StreamPlaybackConfiguration
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpProxyAddress
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import com.github.andreyasadchy.xtra.util.m3u8.VaftSegmentEvidenceRecorder
import com.github.andreyasadchy.xtra.util.NetworkUtils.proxyCandidates
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isVaftEnabled
import okhttp3.Credentials
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Per-source state used by the live playlist proxy callback. */
class StreamProxyState {
    constructor(onProxyPlaylistObservation: (StreamRequestObservation) -> Unit = {}) {
        this.onProxyPlaylistObservation = onProxyPlaylistObservation
    }

    private val onProxyPlaylistObservation: (StreamRequestObservation) -> Unit
    val requestRouteTracker = StreamRequestRouteTracker()
    @Volatile
    private var primaryPlayback = false

    @Volatile
    private var vaftAlternateEvidenceSource = false

    private var lastReportedProgramDateTimeShiftMs: Long? = null

    @Volatile
    var proxyMediaPlaylist: Boolean = false

    @Volatile
    var twitchHlsDiagnostics: TwitchHlsPlaylistDiagnostics? = null

    @Volatile
    var lastMediaPlaylistBaseUri: String? = null

    @Volatile
    var lastMediaPlaylistClean: Boolean? = null

    @Volatile
    var controlledPlaylist: ControlledVaftPlaylist? = null

    var hiddenAudioPlaylist: HiddenStreamAudioPlaylist? = null

    fun recordRequestObservation(observation: StreamRequestObservation) {
        if (!primaryPlayback) return
        if (observation.requestType.endsWith("playlist", ignoreCase = true)) {
            onProxyPlaylistObservation(observation)
        }
    }

    @Synchronized
    fun shouldReportProgramDateTimeShift(
        shiftMs: Long?,
        thresholdMs: Long,
        diagnosticsEnabled: Boolean,
    ): Boolean {
        if (shiftMs == null || shiftMs > -thresholdMs && shiftMs < thresholdMs) {
            lastReportedProgramDateTimeShiftMs = null
            return false
        }
        if (!diagnosticsEnabled) return false

        val lastReportedShiftMs = lastReportedProgramDateTimeShiftMs
        if (lastReportedShiftMs == null ||
            kotlin.math.abs(shiftMs - lastReportedShiftMs) >= thresholdMs
        ) {
            lastReportedProgramDateTimeShiftMs = shiftMs
            return true
        }
        return false
    }

    fun setPrimaryPlayback(isPrimary: Boolean) {
        primaryPlayback = isPrimary
    }

    fun setVaftAlternateEvidenceSource(isAlternate: Boolean) {
        vaftAlternateEvidenceSource = isAlternate
    }

    fun recordVaftSegmentEvidence(
        playlist: HlsMediaPlaylist,
        sourceIdentity: String,
        isLiveSource: Boolean,
    ) {
        if (primaryPlayback && isLiveSource && !vaftAlternateEvidenceSource) {
            VaftSegmentEvidenceRecorder.record(playlist, sourceIdentity)
        }
    }

}

/**
 * The single HLS source factory for live Twitch playback, media preloading, and previews.
 * A new DataSource.Factory is made for each MediaItem so its proxy state is not shared by
 * recycled cards or unrelated playback sessions.
 */
@OptIn(UnstableApi::class)
class StreamHlsMediaSourceFactory(
    private val context: Context,
    private val xtraModule: XtraModule,
    private val configuration: StreamPlaybackConfiguration,
    private val proxyStates: MutableMap<String, StreamProxyState> = ConcurrentHashMap(),
    private val onProxyPlaylistObservation: (String, StreamRequestObservation) -> Unit = { _, _ -> },
) : MediaSource.Factory {

    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(6)
    private val sourceDataSourceFactories = ConcurrentHashMap<String, DataSource.Factory>()
    private val timelineContinuityStoresByMediaId = ConcurrentHashMap<String, TwitchHlsPlaylistContinuityStore>()
    private val renditionFormats = ConcurrentHashMap<String, androidx.media3.common.Format>()
    private val defaultMediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context, dataSourceFactory(StreamProxyState(), streamSource = false))
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (!StreamMediaSourceRouting.isHls(
                mimeType = mediaItem.localConfiguration?.mimeType,
                uriPath = mediaItem.localConfiguration?.uri?.path,
            )
        ) {
            return defaultMediaSourceFactory.createMediaSource(mediaItem)
        }
        val state = proxyStates.getOrPut(mediaItem.mediaId) {
            StreamProxyState { observation -> onProxyPlaylistObservation(mediaItem.mediaId, observation) }
        }
        val timelineContinuityStore = timelineContinuityStoresByMediaId.getOrPut(mediaItem.mediaId) {
            TwitchHlsPlaylistContinuityStore()
        }
        val streamSource = mediaItem.liveConfiguration.targetOffsetMs != androidx.media3.common.C.TIME_UNSET
        val networkFactory = dataSourceFactory(state, streamSource)
        val hiddenAudio = if (streamSource) HiddenStreamAudioPlaylist(networkFactory) else null
        state.hiddenAudioPlaylist = hiddenAudio
        val channel = mediaItem.mediaId.takeIf { it.startsWith("xtra-live:") }?.split(':')?.getOrNull(2)
        val controlled = if (streamSource && context.prefs().isVaftEnabled() && !channel.isNullOrBlank()) {
            ControlledVaftPlaylist(context, xtraModule, networkFactory, channel, renditionFormats, configuration.lowLatency)
        } else null
        state.controlledPlaylist = controlled
        val handoffFactory = if (mediaItem.mediaId.startsWith("vaft-source:")) {
            DataSource.Factory { ProbedPlaylistDataSource(networkFactory.createDataSource()) }
        } else networkFactory
        val sourceDataSourceFactory = DefaultDataSource.Factory(
            context,
            if (hiddenAudio != null) DataSource.Factory { hiddenAudio.dataSource(handoffFactory.createDataSource()) } else handoffFactory,
        ).also { sourceDataSourceFactories[mediaItem.mediaId] = it }
        val lowLatencyEnabled = configuration.lowLatency &&
            streamSource
        return HlsMediaSource.Factory(sourceDataSourceFactory).apply {
            setPlaylistParserFactory(
                TwitchHlsPlaylistParserFactory(
                    lowLatencyEnabled = lowLatencyEnabled,
                    continuityStore = timelineContinuityStore,
                    transform = { uri, parsed ->
                        hiddenAudio?.observe(uri, parsed)
                        val published = controlled?.transform(uri, parsed) ?: parsed
                        hiddenAudio?.transform(published) ?: published
                    },
                    diagnostics = TwitchHlsDiagnosticsSink { diagnostics, parsed ->
                        val programDateTimeShiftMs = diagnostics.programDateTimeShiftMs
                        if (state.shouldReportProgramDateTimeShift(
                                shiftMs = programDateTimeShiftMs,
                                thresholdMs = PROGRAM_DATE_TIME_SHIFT_DIAGNOSTIC_THRESHOLD_MS,
                                diagnosticsEnabled = xtraModule.diagnosticsLogger.isEnabled,
                            )
                        ) {
                            programDateTimeShiftMs?.let { shiftMs ->
                                xtraModule.diagnosticsLogger.event(
                                    category = DiagnosticsCategory.PLAYBACK,
                                    severity = DiagnosticsSeverity.WARN,
                                    transport = DiagnosticsTransport.PLAYER,
                                    operation = "hls_timeline",
                                    event = "pdt_rebased",
                                    phase = DiagnosticsPhase.EVENT,
                                    code = "program_date_time_shift",
                                    fields = buildList {
                                        add(DiagnosticsField(
                                            DiagnosticsFieldKey.PROGRAM_DATE_TIME_SHIFT_MS,
                                            shiftMs.toString(),
                                        ))
                                        diagnostics.mediaSequence?.let { sequence ->
                                            add(DiagnosticsField(DiagnosticsFieldKey.MEDIA_SEQUENCE, sequence.toString()))
                                        }
                                        add(DiagnosticsField(
                                            DiagnosticsFieldKey.SOURCE_REASON,
                                            diagnostics.programDateTimeShiftSource ?: "overlap_continuity",
                                        ))
                                    },
                                )
                            }
                        }
                        if (parsed is androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist) {
                            if (BuildConfig.DEBUG) {
                                state.recordVaftSegmentEvidence(
                                    playlist = parsed,
                                    sourceIdentity = mediaItem.mediaId,
                                    isLiveSource = streamSource,
                                )
                            }
                            state.twitchHlsDiagnostics = diagnostics
                            state.lastMediaPlaylistBaseUri = parsed.baseUri
                            state.lastMediaPlaylistClean = !TwitchVaftDetector.requiresVaft(parsed)
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    "TwitchLLSource",
                                    "itemToken=${diagnosticToken(mediaItem.mediaId)} " +
                                        "parseIndex=${diagnostics.parseIndex} " +
                                        "rawEndTag=${diagnostics.rawHasEndTag} " +
                                        "adaptedEndTag=${diagnostics.adaptedHasEndTag} " +
                                        "preRestoreEndTag=${diagnostics.parsedBeforeInterstitialRestoreHasEndTag} " +
                                        "restoredInterstitials=${diagnostics.restoredInterstitialCount} " +
                                        "parsedEndTag=${diagnostics.parsedHasEndTag} " +
                                        "hasIndependentSegments=${diagnostics.parsedHasIndependentSegments} " +
                                        "hasProgramDateTime=${diagnostics.parsedHasProgramDateTime} " +
                                        "preciseStart=${diagnostics.parsedPreciseStart} " +
                                        "mediaSequence=${diagnostics.mediaSequence}",
                                )
                            }
                        }
                    },
                ),
            )
            setLoadErrorHandlingPolicy(if (controlled == null) loadErrorHandlingPolicy else object : LoadErrorHandlingPolicy by loadErrorHandlingPolicy {
                override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxOf(100, loadErrorHandlingPolicy.getMinimumLoadableRetryCount(dataType))

                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (info.exception is ControlledVaftPlaylist.WaitingForVerifiedMediaException) 200L
                    else if (info.errorCount > loadErrorHandlingPolicy.getMinimumLoadableRetryCount(info.mediaLoadData.dataType)) androidx.media3.common.C.TIME_UNSET
                    else loadErrorHandlingPolicy.getRetryDelayMsFor(info)
            })
            drmSessionManagerProvider?.let(::setDrmSessionManagerProvider)
        }.createMediaSource(mediaItem)
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drmSessionManagerProvider = provider
        defaultMediaSourceFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        loadErrorHandlingPolicy = policy
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray =
        (HlsMediaSource.Factory(
            DefaultDataSource.Factory(context, dataSourceFactory(StreamProxyState(), streamSource = false)),
        )
            .getSupportedTypes() + defaultMediaSourceFactory.getSupportedTypes()).distinct().toIntArray()

    private companion object {
        const val PROGRAM_DATE_TIME_SHIFT_DIAGNOSTIC_THRESHOLD_MS = 1_000L
    }

    fun createLiveMediaItem(
        mediaId: String,
        uri: String,
        title: String?,
        channelName: String?,
        channelLogo: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setLiveConfiguration(
            configuration.livePlaybackSettings.liveConfiguration(
                configuration.livePlaybackSettings.policies()
                    .forMode(configuration.livePlaybackSettings.mode),
            ),
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: channelName)
                .setArtist(channelName)
                .setArtworkUri(channelLogo?.toUri())
                .build()
        )
        .build()

    fun createVodMediaItem(
        mediaId: String,
        uri: String,
        title: String?,
        channelName: String?,
        channelLogo: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri.toUri())
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: channelName)
                .setArtist(channelName)
                .setArtworkUri(channelLogo?.toUri())
                .build()
        )
        .build()

    fun stateFor(mediaId: String): StreamProxyState = proxyStates.getOrPut(mediaId) { StreamProxyState() }

    fun findState(mediaId: String): StreamProxyState? = proxyStates[mediaId]

    /** Returns a segment downloader bound to the same headers, network engine, and proxy state as this source. */
    fun clipDataSourceFactory(mediaId: String): DataSource.Factory? =
        sourceDataSourceFactories[mediaId]

    fun releaseMediaItem(mediaId: String) {
        sourceDataSourceFactories.remove(mediaId)
        timelineContinuityStoresByMediaId.remove(mediaId)
        proxyStates.remove(mediaId)?.let {
            it.controlledPlaylist?.close()
            it.hiddenAudioPlaylist?.close()
        }
    }

    fun hlsDiagnosticsFor(mediaId: String): TwitchHlsPlaylistDiagnostics? =
        proxyStates[mediaId]?.twitchHlsDiagnostics

    @Synchronized
    fun bindLiveTimelineContinuity(
        mediaId: String,
        continuityStore: TwitchHlsPlaylistContinuityStore,
    ) {
        val sourceStore = timelineContinuityStoresByMediaId[mediaId]
        if (sourceStore !== continuityStore) sourceStore?.forwardTo(continuityStore)
        timelineContinuityStoresByMediaId[mediaId] = continuityStore
    }

    @SuppressLint("NewApi")
    private fun dataSourceFactory(state: StreamProxyState, streamSource: Boolean): DataSource.Factory {
        val useHttpEngine = configuration.networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null
        val firstRequest = if (BuildConfig.DEBUG) java.util.concurrent.atomic.AtomicBoolean() else null
        val observeRequest = java.util.function.Consumer<StreamRequestObservation> { observation ->
            state.recordRequestObservation(observation)
            if (firstRequest?.compareAndSet(false, true) == true) {
                val backend = if (useHttpEngine) C.HTTP_ENGINE else C.OKHTTP
                Log.i("PlaybackNetwork", "source_backend=$backend stream=$streamSource request_type=${observation.requestType} route=${observation.route}")
            }
        }
        val proxyServers = configuration.httpProxyServers.takeIf { streamSource }.orEmpty()
        val proxyUser = configuration.proxyUser.takeIf { streamSource && proxyServers.isNotEmpty() }
        val proxyPassword = configuration.proxyPassword.takeIf { streamSource && proxyServers.isNotEmpty() }
        val proxyMultivariantPlaylist = streamSource && configuration.proxyMultivariantPlaylist &&
            proxyServers.isNotEmpty()
        val proxyMediaPlaylist = streamSource && proxyServers.isNotEmpty()

        val upstreamFactory: DataSource.Factory = when {
            useHttpEngine ->
                createHttpEngineFactory(
                    state,
                    proxyServers,
                    proxyUser,
                    proxyPassword,
                    configuration.allowDirectProxyFallback,
                    proxyMultivariantPlaylist,
                    proxyMediaPlaylist,
                    observeRequest,
                )
            else -> createOkHttpFactory(
                state,
                proxyServers,
                proxyUser,
                proxyPassword,
                configuration.allowDirectProxyFallback,
                proxyMultivariantPlaylist,
                proxyMediaPlaylist,
                observeRequest,
            )
        }
        return upstreamFactory.apply {
            if (streamSource && configuration.streamHeaders.isNotEmpty()) {
                when (this) {
                    is HttpEngineDataSource.Factory -> setDefaultRequestProperties(configuration.streamHeaders)
                    is OkHttpDataSource.Factory -> setDefaultRequestProperties(configuration.streamHeaders)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createHttpEngineFactory(
        state: StreamProxyState,
        proxyServers: List<HttpProxyAddress>,
        proxyUser: String?,
        proxyPassword: String?,
        allowDirectFallback: Boolean,
        proxyMultivariantPlaylist: Boolean,
        proxyMediaPlaylist: Boolean,
        observeRequest: java.util.function.Consumer<StreamRequestObservation>,
    ): DataSource.Factory {
        val proxyClient = if ((proxyMultivariantPlaylist || proxyMediaPlaylist) && proxyServers.isNotEmpty()) {
            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                listOf(android.util.Pair("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)))
            } else emptyList()
            try {
                val builder = HttpEngine.Builder(context)
                builder.setProxyOptions(
                    ProxyOptions.fromProxyList(
                        proxyServers.map { server ->
                            android.net.http.Proxy.createHttpProxy(
                                android.net.http.Proxy.SCHEME_HTTP,
                                server.host,
                                server.port,
                                xtraModule.httpExecutor.value,
                                object : android.net.http.Proxy.HttpConnectCallback {
                                    override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                        request.proceed(proxyHeaders)
                                    }

                                    override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int =
                                        android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                },
                            )
                        },
                        if (allowDirectFallback) {
                            ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT
                        } else {
                            ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                        },
                    )
                ).build()
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
        } else null
        val multivariantProxy = if (proxyMultivariantPlaylist && proxyClient == null) {
            proxyOkHttpClient(proxyServers, proxyUser, proxyPassword, MULTIVARIANT_PLAYLIST_REGEX, state.requestRouteTracker, allowDirectFallback)
        } else null
        val mediaProxy = if (proxyMediaPlaylist && proxyClient == null) {
            proxyOkHttpClient(proxyServers, proxyUser, proxyPassword, MEDIA_PLAYLIST_REGEX, state.requestRouteTracker, allowDirectFallback)
        } else null
        return HttpEngineDataSource.Factory(
            xtraModule.httpEngine.value,
            xtraModule.httpExecutor.value,
            proxyMultivariantPlaylist,
            proxyMediaPlaylist,
            proxyClient,
            multivariantProxy,
            mediaProxy,
            state.requestRouteTracker,
            observeRequest,
        ) { state.proxyMediaPlaylist }
    }

    private fun createOkHttpFactory(
        state: StreamProxyState,
        proxyServers: List<HttpProxyAddress>,
        proxyUser: String?,
        proxyPassword: String?,
        allowDirectFallback: Boolean,
        proxyMultivariantPlaylist: Boolean,
        proxyMediaPlaylist: Boolean,
        observeRequest: java.util.function.Consumer<StreamRequestObservation>,
    ): DataSource.Factory {
        val multivariantProxy = if (proxyMultivariantPlaylist) {
            proxyOkHttpClient(proxyServers, proxyUser, proxyPassword, MULTIVARIANT_PLAYLIST_REGEX, state.requestRouteTracker, allowDirectFallback)
        } else null
        val mediaProxy = if (proxyMediaPlaylist) {
            proxyOkHttpClient(proxyServers, proxyUser, proxyPassword, MEDIA_PLAYLIST_REGEX, state.requestRouteTracker, allowDirectFallback)
        } else null
        return OkHttpDataSource.Factory(
            state.requestRouteTracker.instrument(multivariantProxy ?: xtraModule.okHttpClient.value),
            mediaProxy?.let(state.requestRouteTracker::instrument),
            state.requestRouteTracker,
            observeRequest,
        ) { state.proxyMediaPlaylist }
    }

    private fun proxyOkHttpClient(
        proxyServers: List<HttpProxyAddress>,
        proxyUser: String?,
        proxyPassword: String?,
        hostPattern: String,
        routeTracker: StreamRequestRouteTracker? = null,
        allowDirectFallback: Boolean,
    ): okhttp3.OkHttpClient? {
        if (proxyServers.isEmpty()) return null
        return xtraModule.okHttpClient.value.newBuilder().apply {
            proxySelector(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> = if (Regex(hostPattern).matches(uri.host.orEmpty())) {
                        proxyCandidates(proxyServers.map(HttpProxyAddress::toProxy), allowDirectFallback)
                    } else listOf(Proxy.NO_PROXY)

                    override fun connectFailed(uri: java.net.URI, sa: SocketAddress, e: IOException) = Unit
                }
            )
            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                }
            }
        }.build().let { client -> routeTracker?.instrument(client) ?: client }
    }
}

internal object StreamMediaSourceRouting {
    fun isHls(mimeType: String?, uriPath: String?): Boolean =
        mimeType == MimeTypes.APPLICATION_M3U8 || uriPath?.endsWith(".m3u8", ignoreCase = true) == true
}
