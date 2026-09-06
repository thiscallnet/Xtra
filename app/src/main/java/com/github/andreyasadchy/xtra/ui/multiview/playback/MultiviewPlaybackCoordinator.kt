package com.github.andreyasadchy.xtra.ui.multiview.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.annotation.SuppressLint
import com.github.andreyasadchy.xtra.BuildConfig
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
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
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.common.logVideoSurfaceBinding
import com.github.andreyasadchy.xtra.ui.player.TwitchAdController
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils.proxyCandidates
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.m3u8.TwitchAdDetector
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
import com.github.andreyasadchy.xtra.repository.PlayerRepository

@OptIn(UnstableApi::class)
class MultiviewPlaybackCoordinator(
    context: Context,
    private val loadPlaylist: suspend (String, Boolean) -> String,
    private val loadCleanPlaylist: suspend (String, List<String>, Boolean, Boolean) -> PlayerRepository.StreamPlaylistCandidate?,
    private val onSnapshot: (String, MultiviewPlaybackSnapshot) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewingStatsRecorder by lazy {
        (applicationContext as? XtraApp)?.xtraModule?.viewingStatsRecorder
    }
    private val slots = linkedMapOf<String, MultiviewPlayerSlot>()
    private val tileBounds = mutableMapOf<String, Pair<Int, Int>>()
    private var activeIdentity: String? = null
    private var focusedIdentity: String? = null
    private var qualityMode = MultiviewQualityMode.AUTO
    private var qualityOverrides: Map<String, String> = emptyMap()
    private var audioVolumes: Map<String, Float> = emptyMap()
    private var foreground = true
    private var backgroundPlayback = false
    private var lifecycleStarted = false
    private var backgroundServicePrepared = false

    fun sync(
        streams: List<Stream>,
        activeIdentity: String?,
        focusedIdentity: String?,
        qualityMode: MultiviewQualityMode,
        qualityOverrides: Map<String, String>,
        audioVolumes: Map<String, Float> = emptyMap(),
    ) {
        this.activeIdentity = activeIdentity
        this.focusedIdentity = focusedIdentity
        this.qualityMode = qualityMode
        this.qualityOverrides = qualityOverrides
        this.audioVolumes = audioVolumes
        val desired = streams.mapNotNull { stream ->
            identityOf(stream)?.let { identity -> identity to stream }
        }.toMap()

        slots.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            slots.remove(identity)?.let { slot ->
                releaseViewingStats(slot)
                slot.release()
            }
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
                updateViewingStats(slot)
            }
        }
        slots.values.forEach { applyQuality(it) }
        updateAudio()
        if (lifecycleStarted) maintainBackgroundPlaybackService()
    }

    fun attach(identity: String, playerView: PlayerView) {
        slots[identity]?.let { slot ->
            if (slot.attachedView !== playerView) {
                slot.attachedView?.let { oldView ->
                    logVideoSurfaceBinding("multiview_detach", slot.player, oldView, oldView.player)
                    oldView.player = null
                }
                slot.attachedView = playerView
            }
            if (playerView.player !== slot.player) {
                logVideoSurfaceBinding("multiview_attach", slot.player, playerView, playerView.player)
                playerView.player = slot.player
            }
        }
    }

    fun detach(identity: String, playerView: PlayerView) {
        slots[identity]?.takeIf { it.attachedView === playerView }?.let { slot ->
            logVideoSurfaceBinding("multiview_detach", slot.player, playerView, playerView.player)
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
            slot.retainDowngradeAtMost(1)
            slot.retryJob?.cancel()
            slot.loadJob?.cancel()
            start(slot, force = true)
        }
    }

    private fun shouldPlayWhenReady(slot: MultiviewPlayerSlot): Boolean {
        return (slot.shouldPlay && foreground) ||
            (slot.shouldPlay && backgroundPlayback && configuredVolume(slot) > 0f)
    }

    fun onStart() {
        lifecycleStarted = true
        foreground = true
        backgroundPlayback = false
        slots.values.forEach { slot ->
            restoreBackgroundVideo(slot)
            slot.attachedView?.let { attach(slot.identity, it) }
            slot.player.playWhenReady = shouldPlayWhenReady(slot)
        }
        maintainBackgroundPlaybackService()
    }

    fun onStop(allowBackground: Boolean = true, inPictureInPicture: Boolean = false) {
        if (inPictureInPicture) {
            lifecycleStarted = true
            foreground = true
            backgroundPlayback = false
            slots.values.forEach { slot ->
                slot.player.playWhenReady = shouldPlayWhenReady(slot)
            }
            return
        }
        lifecycleStarted = false
        foreground = false
        val keepAudioInBackground = allowBackground && applicationContext.prefs()
            .getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true)
        val canPlayInBackground = keepAudioInBackground && backgroundServicePrepared
        backgroundPlayback = canPlayInBackground
        slots.values.forEach { slot ->
            // onStop may be called once by the explicit external-player handoff
            // and again by Fragment lifecycle dispatch. Preserve the original
            // intent instead of turning a playing tile into a paused tile on
            // the second callback.
            slot.shouldPlay = slot.shouldPlay || slot.player.playWhenReady
            suppressBackgroundVideo(slot)
            val shouldPlayInBackground = shouldPlayWhenReady(slot)
            slot.player.playWhenReady = shouldPlayInBackground
            updateViewingStats(slot, forceNotPlaying = !shouldPlayInBackground)
        }
        if (!canPlayInBackground) {
            backgroundServicePrepared = false
            MultiviewBackgroundPlaybackService.stop(applicationContext)
        }
    }

    fun releaseAll() {
        scope.coroutineContext[Job]?.cancel()
        slots.values.forEach { slot ->
            releaseViewingStats(slot)
            slot.release()
        }
        slots.clear()
        tileBounds.clear()
        lifecycleStarted = false
        backgroundPlayback = false
        backgroundServicePrepared = false
        MultiviewBackgroundPlaybackService.stop(applicationContext)
    }

    fun player(identity: String): ExoPlayer? = slots[identity]?.player

    /**
     * Applies metadata received while a tile keeps playing. Updating the
     * mutable Stream snapshot is enough to make the next recorder update use
     * the new attribution without restarting the tile or its viewing session.
     */
    fun updateStreamMetadata(
        identity: String,
        title: String?,
        categoryId: String?,
        categoryName: String?,
    ) {
        val slot = slots[identity] ?: return
        val stream = slot.stream
        val nextTitle = title ?: stream.title
        val nextCategory = mergeViewingCategoryPatch(
            currentId = stream.gameId,
            currentName = stream.gameName,
            patchId = categoryId,
            patchName = categoryName,
        )
        val nextCategoryId = nextCategory.id
        val nextCategoryName = nextCategory.name
        if (stream.title == nextTitle &&
            stream.gameId == nextCategoryId &&
            stream.gameName == nextCategoryName
        ) {
            return
        }
        stream.title = nextTitle
        stream.gameId = nextCategoryId
        stream.gameName = nextCategoryName
        updateViewingStats(slot)
    }

    fun updateStreamMetadataForChannel(
        channelId: String?,
        channelLogin: String?,
        title: String?,
        categoryId: String?,
        categoryName: String?,
    ) {
        val slot = slots.values.firstOrNull { slot ->
            (channelId != null && slot.stream.channelId == channelId) ||
                (channelId == null && channelLogin != null &&
                    slot.stream.channelLogin.equals(channelLogin, ignoreCase = true))
        } ?: return
        updateStreamMetadata(slot.identity, title, categoryId, categoryName)
    }

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
                    updateViewingStats(slot)
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            slot.stableRecoveryJob?.cancel()
                            if (slot.recordRebuffer()) {
                                applyQuality(slot)
                            }
                            publish(slot, MultiviewSlotStatus.BUFFERING)
                        }
                        Player.STATE_READY -> {
                            slot.markReady()
                            slot.retryCount = 0
                            slot.manualRetry = false
                            publish(slot, MultiviewSlotStatus.LIVE)
                            logSelectedFormat(slot, player.videoFormat)
                            scheduleStableQualityRecovery(slot)
                        }
                        Player.STATE_ENDED -> publish(slot, MultiviewSlotStatus.OFFLINE)
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateViewingStats(slot)
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    updateFormats(slot)
                    val preferredHeight = slot.target?.preferredHeightPx
                    val selectedFormat = player.videoFormat
                    preferredHeight?.let { height ->
                        val preferredSelection = findPreferredVideoSelection(
                            player,
                            height,
                            slot.target?.preferredFrameRate ?: slot.target?.maxFrameRate ?: 60,
                        )
                        if (preferredSelection != null &&
                            !selectedFormatMatches(selectedFormat, preferredSelection.format)
                        ) {
                            applyQuality(slot, force = true)
                        }
                    }
                    logSelectedFormat(slot, player.videoFormat)
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerError(slot, error)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    inspectAdState(slot)
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
                    extractResponseCode(error)?.let { responseCode ->
                        slot.lastResponseCode = responseCode
                        slot.lastResponseWasMaster = isMasterPlaylist(slot, loadEventInfo.uri.toString())
                        if (slot.lastResponseWasMaster) {
                            slot.lastMasterResponseCode = responseCode
                        }
                    }
                    Log.w(
                        TAG,
                        "channel=${slot.identity} loadError type=${mediaLoadData.dataType} canceled=$wasCanceled uri=${loadEventInfo.uri}",
                        error,
                    )
                }
            })
        }
    }

    private data class PlaylistSelection(
        val url: String,
        val playerType: String?,
        val customProxy: Boolean = false,
        val alternate: Boolean = false,
    )

    private fun start(
        slot: MultiviewPlayerSlot,
        force: Boolean = false,
        requestedSelection: PlaylistSelection? = null,
    ) {
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
                val selection = requestedSelection ?: loadPlaylistUrl(slot, login)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                slot.customProxy = selection.customProxy
                slot.currentPlayerType = selection.playerType
                slot.usingAlternateStream = selection.alternate
                slot.currentPlaylistUrl = selection.url
                slot.lastResponseCode = null
                slot.lastMasterResponseCode = null
                slot.lastResponseWasMaster = false
                val mediaItem = MediaItem.Builder()
                    .setUri(selection.url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().apply {
                            setTargetOffsetMs(
                                if (applicationContext.prefs().getBoolean(C.PLAYER_LOW_LATENCY, C.DEFAULT_PLAYER_LOW_LATENCY)) {
                                    C.LOW_LATENCY_TARGET_OFFSET_MS
                                } else {
                                    C.NORMAL_LATENCY_TARGET_OFFSET_MS
                                }
                            )
                        }.build()
                    )
                    .setMediaMetadata(metadata(slot.stream))
                    .build()
                val source = HlsMediaSource.Factory(createDataSourceFactory(slot))
                    .setPlaylistParserFactory(
                        TwitchHlsPlaylistParserFactory(
                            lowLatencyEnabled = applicationContext.prefs().getBoolean(
                                C.PLAYER_LOW_LATENCY,
                                C.DEFAULT_PLAYER_LOW_LATENCY,
                            ),
                        ),
                    )
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                    .createMediaSource(mediaItem)
                slot.player.setMediaSource(source)
                slot.hasMediaSource = true
                applyQuality(slot)
                updateAudio()
                slot.player.prepare()
                slot.player.playWhenReady = shouldPlayWhenReady(slot)
                debugLog("channel=${slot.identity} playlistLoaded quality=${slot.target?.label} urlType=${if (slot.customProxy) "proxy" else "usher"} playerType=${slot.currentPlayerType ?: "custom"} alternate=${slot.usingAlternateStream} httpProxyDisabled=${slot.httpProxyDisabled}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleLoadError(slot, error)
            }
        }
    }

    private suspend fun loadPlaylistUrl(slot: MultiviewPlayerSlot, login: String): PlaylistSelection {
        val preferences = applicationContext.prefs()
        val proxyUrl = preferences.getString(C.PLAYER_PROXY_URL, null)
        val useCustomProxy = !slot.customProxyDisabled &&
            preferences.getBoolean(C.PLAYER_STREAM_PROXY, false) && !proxyUrl.isNullOrBlank()
        if (useCustomProxy) {
            return PlaylistSelection(
                url = proxyUrl.replace("\$channel", login),
                playerType = null,
                customProxy = true,
            )
        }

        val primaryPlayerType = preferences.getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        if (preferences.shouldAvoidTwitchAds()) {
            val cleanCandidate = loadCleanPlaylist(
                login,
                listOf(primaryPlayerType),
                false,
                slot.httpProxyDisabled,
            )
            if (cleanCandidate != null) {
                return PlaylistSelection(
                    url = cleanCandidate.url,
                    playerType = cleanCandidate.playerType,
                    alternate = cleanCandidate.playerType != primaryPlayerType,
                )
            }
        }
        return PlaylistSelection(
            url = loadPlaylist(login, slot.httpProxyDisabled),
            playerType = primaryPlayerType,
        )
    }

    private fun handleLoadError(slot: MultiviewPlayerSlot, error: Exception) {
        Log.w(TAG, "channel=${slot.identity} playlist load failed", error)
        handleFailure(slot, error, isOffline = false, responseCode = extractResponseCode(error))
    }

    private fun handlePlayerError(slot: MultiviewPlayerSlot, error: PlaybackException) {
        Log.w(TAG, "channel=${slot.identity} player error code=${error.errorCodeName}", error)
        val responseCode = slot.lastResponseCode ?: extractResponseCode(error)
        val behindLiveWindow = error.errorCodeName.contains("BEHIND_LIVE_WINDOW", true)
        if (behindLiveWindow) {
            slot.reloadRequired = true
        }
        // A 404 from a media playlist or segment is not authoritative evidence
        // that the channel ended. Only a failed master playlist may produce the
        // Offline state.
        val offline = !slot.customProxy &&
            (slot.lastMasterResponseCode == 404 || slot.lastMasterResponseCode == 410)
        val decoderFailure = error.errorCodeName.contains("DECODER", true)
        if (decoderFailure) {
            slot.markResourceFailure()
            applyQuality(slot)
        }
        handleFailure(slot, error, offline, responseCode)
    }

    private fun handleFailure(
        slot: MultiviewPlayerSlot,
        error: Throwable,
        isOffline: Boolean,
        responseCode: Int? = extractResponseCode(error),
    ) {
        if (isOffline) {
            publish(slot, MultiviewSlotStatus.OFFLINE, retryAvailable = true)
            return
        }

        if (!hasValidatedNetwork()) {
            slot.retryJob?.cancel()
            publish(slot, MultiviewSlotStatus.RECONNECTING, retryAvailable = true)
            slot.retryJob = scope.launch {
                delay(NETWORK_RETRY_DELAY_MS)
                if (!isActive || slots[slot.identity] !== slot) return@launch
                if (hasValidatedNetwork()) {
                    recoverSlot(slot, error)
                } else {
                    handleFailure(slot, error, isOffline = false, responseCode = responseCode)
                }
            }
            return
        }

        // Match the normal player: a broken custom stream proxy is disabled for
        // this slot and the normal Twitch path is retried immediately. The
        // decision is made only with validated connectivity so an offline device
        // cannot permanently disable a healthy proxy.
        if (responseCode != null && responseCode >= 400 && slot.customProxy) {
            slot.customProxyDisabled = true
            slot.retryCount = 0
            slot.manualRetry = false
            slot.reloadRequired = false
            start(slot, force = true)
            return
        }
        if (responseCode != null && responseCode >= 400 && slot.httpProxyActive) {
            slot.httpProxyDisabled = true
            slot.retryCount = 0
            slot.manualRetry = false
            slot.reloadRequired = false
            start(slot, force = true)
            return
        }

        if (slot.retryCount >= MAX_RETRIES) {
            slot.manualRetry = true
            publish(slot, MultiviewSlotStatus.PLAYBACK_UNAVAILABLE, retryAvailable = true)
            return
        }
        val delayMs = RETRY_DELAYS_MS[slot.retryCount.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
        debugLog("channel=${slot.identity} retry=${slot.retryCount + 1}/$MAX_RETRIES delayMs=$delayMs responseCode=$responseCode reload=${slot.reloadRequired}")
        slot.retryCount++
        publish(slot, MultiviewSlotStatus.RECONNECTING, retryAvailable = true)
        slot.retryJob?.cancel()
        slot.retryJob = scope.launch {
            delay(delayMs)
            if (!isActive || slots[slot.identity] !== slot) return@launch
            try {
                recoverSlot(slot, error)
            } catch (retryError: Exception) {
                handleFailure(slot, retryError, false, extractResponseCode(retryError))
            }
        }
    }

    private fun recoverSlot(slot: MultiviewPlayerSlot, error: Throwable) {
        if (!hasValidatedNetwork()) {
            handleFailure(slot, error, isOffline = false)
        } else if (slot.hasMediaSource && !slot.reloadRequired) {
            slot.lastResponseCode = null
            slot.lastMasterResponseCode = null
            slot.player.prepare()
            slot.player.playWhenReady = shouldPlayWhenReady(slot)
        } else {
            slot.reloadRequired = false
            start(slot)
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun extractResponseCode(error: Throwable): Int? {
        return generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                (throwable as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            }
            .firstOrNull()
    }

    private fun isMasterPlaylist(slot: MultiviewPlayerSlot, uri: String): Boolean {
        return uri == slot.currentPlaylistUrl ||
            uri.contains("/api/v2/channel/hls/", ignoreCase = true)
    }

    private fun applyQuality(slot: MultiviewPlayerSlot, force: Boolean = false) {
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
        if (!force && slot.target == target) return
        slot.target = target
        val builder = slot.player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            .setMaxVideoFrameRate(target.maxFrameRate)
            .setMaxVideoBitrate(Int.MAX_VALUE)
        if (target.maxWidthPx != null && target.maxHeightPx != null) {
            builder.setMaxVideoSize(target.maxWidthPx, target.maxHeightPx)
        }
        target.preferredHeightPx?.let { preferredHeight ->
            findPreferredVideoSelection(
                slot.player,
                preferredHeight,
                target.preferredFrameRate ?: target.maxFrameRate,
            )?.override?.let(builder::setOverrideForType)
        }
        slot.player.trackSelectionParameters = builder.build()
        onSnapshot(slot.identity, slot.snapshot(target.label))
        debugLog("channel=${slot.identity} policy mode=$qualityMode streams=${slots.size} active=${slot.identity == activeIdentity} focus=${slot.identity == focusedIdentity} target=${target.label} downgrade=${slot.downgradeLevel}")
    }

    private data class PreferredVideoSelection(
        val override: TrackSelectionOverride,
        val format: androidx.media3.common.Format,
    )

    private fun findPreferredVideoSelection(
        player: ExoPlayer,
        preferredHeight: Int,
        preferredFrameRate: Int,
    ): PreferredVideoSelection? {
        data class SupportedFormat(
            val group: androidx.media3.common.Tracks.Group,
            val index: Int,
            val format: androidx.media3.common.Format,
        )

        val formats = player.currentTracks.groups
            .filter {
                it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSupported
            }
            .flatMap { group ->
                (0 until group.mediaTrackGroup.length)
                    .filter { index -> group.isTrackSupported(index) }
                    .map { index -> SupportedFormat(group, index, group.mediaTrackGroup.getFormat(index)) }
            }
        if (formats.isEmpty()) return null

        val atOrBelow = formats.filter { candidate ->
            candidate.format.height in 1..preferredHeight &&
                (candidate.format.frameRate <= 0f ||
                    candidate.format.frameRate <= preferredFrameRate + FRAME_RATE_TOLERANCE)
        }
        val selected = atOrBelow.maxWithOrNull(
            compareBy<SupportedFormat> { it.format.height }
                .thenBy { it.format.frameRate }
                .thenBy { it.format.bitrate },
        ) ?: formats.minWithOrNull(
            compareBy<SupportedFormat> {
                kotlin.math.abs(it.format.height - preferredHeight)
            }.thenBy {
                kotlin.math.abs(
                    (it.format.frameRate.takeIf { fps -> fps > 0f } ?: preferredFrameRate.toFloat()) -
                        preferredFrameRate.toFloat(),
                )
            }.thenByDescending { it.format.bitrate },
        ) ?: return null

        return PreferredVideoSelection(
            override = TrackSelectionOverride(selected.group.mediaTrackGroup, selected.index),
            format = selected.format,
        )
    }

    private fun selectedFormatMatches(
        selected: androidx.media3.common.Format?,
        candidate: androidx.media3.common.Format,
    ): Boolean {
        if (selected == null || selected.height != candidate.height) return false
        return candidate.frameRate <= 0f || selected.frameRate <= 0f ||
            kotlin.math.abs(selected.frameRate - candidate.frameRate) <= FRAME_RATE_TOLERANCE
    }

    private fun scheduleStableQualityRecovery(slot: MultiviewPlayerSlot) {
        if (slot.downgradeLevel <= 0 && !slot.resourceFailure) return
        slot.stableRecoveryJob?.cancel()
        slot.stableRecoveryJob = scope.launch {
            while (isActive && slots[slot.identity] === slot &&
                (slot.downgradeLevel > 0 || slot.resourceFailure)
            ) {
                delay(MultiviewQualityRecovery.STABLE_PLAYBACK_MS)
                if (!isActive || slots[slot.identity] !== slot ||
                    slot.player.playbackState != Player.STATE_READY
                ) return@launch
                if (slot.recoverAfterStable()) {
                    applyQuality(slot)
                    publish(slot, slot.status)
                }
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun inspectAdState(slot: MultiviewPlayerSlot) {
        if (slots[slot.identity] !== slot) return
        val preferences = applicationContext.prefs()
        val avoidAds = preferences.shouldAvoidTwitchAds()
        val useProxy = slot.httpProxyActive
        if (!avoidAds && !useProxy) return
        val playlist = (slot.player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return
        val ads = TwitchAdDetector.isAd(playlist)
        val changed = ads != slot.playingAds
        slot.playingAds = ads
        if (changed) {
            debugLog("channel=${slot.identity} adState=$ads avoid=$avoidAds proxy=$useProxy playerType=${slot.currentPlayerType}")
        }

        if (ads) {
            if (avoidAds) {
                suppressAdPlayback(slot)
                if (slot.adAvoidanceJob?.isActive != true) {
                    val currentPlayerType = slot.currentPlayerType
                        ?: preferences.getString(C.TOKEN_PLAYER_TYPE, "site")
                        ?: "site"
                    val playerTypes = slot.adController.playerTypesForAd(currentPlayerType)
                    if (playerTypes.isNotEmpty()) {
                        val login = slot.stream.channelLogin?.trim()?.lowercase() ?: return
                        slot.adAvoidanceJob = scope.launch {
                            val candidate = try {
                                loadCleanPlaylist(
                                    login,
                                    playerTypes,
                                    true,
                                    slot.httpProxyDisabled,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.w(TAG, "channel=${slot.identity} alternate ad probe failed", error)
                                null
                            }
                            if (isActive && slots[slot.identity] === slot && candidate != null) {
                                start(
                                    slot,
                                    force = true,
                                    requestedSelection = PlaylistSelection(
                                        url = candidate.url,
                                        playerType = candidate.playerType,
                                        alternate = true,
                                    ),
                                )
                            } else if (isActive && slots[slot.identity] === slot) {
                                // Keep the tile quiet until either Twitch exposes
                                // another clean candidate or the ad window ends.
                                suppressAdPlayback(slot)
                            }
                        }.also { job ->
                            job.invokeOnCompletion {
                                if (slot.adAvoidanceJob === job) slot.adAvoidanceJob = null
                            }
                        }
                    }
                }
            } else if (useProxy) {
                // With ad avoidance disabled, preserve the normal player's proxy
                // fallback: a proxy that returns an ad is bypassed for this slot.
                slot.httpProxyDisabled = true
                start(slot, force = true)
            }
        } else {
            slot.adController.onCleanPlaylist()
            restoreAdPlayback(slot)
            schedulePrimaryStreamRestore(slot)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun suppressAdPlayback(slot: MultiviewPlayerSlot) {
        if (!slot.hiddenForAd) {
            slot.hiddenForAd = true
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        updateAudio()
    }

    @androidx.media3.common.util.UnstableApi
    private fun restoreAdPlayback(slot: MultiviewPlayerSlot) {
        if (slot.hiddenForAd) {
            slot.hiddenForAd = false
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        updateAudio()
    }

    @androidx.media3.common.util.UnstableApi
    private fun schedulePrimaryStreamRestore(slot: MultiviewPlayerSlot) {
        if (!slot.usingAlternateStream || slot.primaryRestoreJob?.isActive == true || slot.customProxy) return
        val login = slot.stream.channelLogin?.trim()?.lowercase() ?: return
        val primaryPlayerType = applicationContext.prefs().getString(C.TOKEN_PLAYER_TYPE, "site") ?: "site"
        slot.primaryRestoreJob = scope.launch {
            while (isActive && slots[slot.identity] === slot && slot.usingAlternateStream) {
                delay(PRIMARY_RESTORE_INTERVAL_MS)
                if (!isActive || slots[slot.identity] !== slot || !slot.usingAlternateStream) return@launch
                val candidate = try {
                    loadCleanPlaylist(
                        login,
                        listOf(primaryPlayerType),
                        true,
                        slot.httpProxyDisabled,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "channel=${slot.identity} primary restore probe failed", error)
                    null
                }
                if (candidate?.verifiedClean == true && isActive && slots[slot.identity] === slot) {
                    slot.usingAlternateStream = false
                    start(
                        slot,
                        force = true,
                        requestedSelection = PlaylistSelection(
                            url = candidate.url,
                            playerType = candidate.playerType,
                            alternate = false,
                        ),
                    )
                    slot.adController.reset()
                    restoreAdPlayback(slot)
                    debugLog("channel=${slot.identity} restored primary playerType=${candidate.playerType}")
                    return@launch
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (slot.primaryRestoreJob === job) slot.primaryRestoreJob = null
            }
        }
    }

    private fun updateAudio() {
        val fallbackVolume = activeVolume()
        slots.values.forEach { slot ->
            slot.player.volume = MultiviewAudioPolicy.volumeFor(
                identity = slot.identity,
                audioVolumes = audioVolumes,
                hiddenForAd = slot.hiddenForAd,
                fallbackVolume = if (slot.identity == activeIdentity) fallbackVolume else 0f,
            )
        }
    }

    private fun maintainBackgroundPlaybackService() {
        val shouldPrepare = lifecycleStarted &&
            applicationContext.prefs().getBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, true) &&
            slots.values.any { it.shouldPlay && configuredVolume(it) > 0f }
        if (!shouldPrepare) {
            backgroundServicePrepared = false
            MultiviewBackgroundPlaybackService.stop(applicationContext)
            return
        }
        if (!backgroundServicePrepared) {
            backgroundServicePrepared = MultiviewBackgroundPlaybackService.start(applicationContext)
        }
    }

    private fun configuredVolume(slot: MultiviewPlayerSlot): Float {
        return MultiviewAudioPolicy.volumeFor(
            identity = slot.identity,
            audioVolumes = audioVolumes,
            hiddenForAd = false,
            fallbackVolume = if (slot.identity == activeIdentity) activeVolume() else 0f,
        )
    }

    @androidx.media3.common.util.UnstableApi
    private fun suppressBackgroundVideo(slot: MultiviewPlayerSlot) {
        if (!slot.videoDisabledForBackground) {
            slot.videoDisabledForBackground = true
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        slot.attachedView?.let { view ->
            if (view.player === slot.player) {
                logVideoSurfaceBinding("multiview_detach", slot.player, view, view.player)
                view.player = null
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun restoreBackgroundVideo(slot: MultiviewPlayerSlot) {
        if (slot.videoDisabledForBackground) {
            slot.videoDisabledForBackground = false
            slot.player.trackSelectionParameters = slot.player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                .build()
        }
    }

    private fun updateFormats(slot: MultiviewPlayerSlot) {
        val manifest = slot.player.currentManifest as? HlsManifest
        val playlist = manifest?.multivariantPlaylist ?: run {
            slot.availableQualities = emptyList()
            publish(slot, slot.status)
            return
        }
        val formats = playlist.variants.mapNotNull { variant ->
            val formatName = variant.format.label?.takeIf { it.isNotBlank() }
                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
            variant.format.height.takeIf { it > 0 }?.let { height ->
                MultiviewQualityPolicy.AvailableFormat(
                    height = height,
                    frameRate = variant.format.frameRate,
                    isSource = formatName.equals("Source", true),
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
    private fun createDataSourceFactory(slot: MultiviewPlayerSlot): DataSource.Factory {
        val application = applicationContext as XtraApp
        val module = application.xtraModule
        val preferences = applicationContext.prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val proxyHost = preferences.getString(C.PROXY_HOST, null)
        val proxyPort = preferences.getString(C.PROXY_PORT, null)?.toIntOrNull()
        val proxyUser = preferences.getString(C.PROXY_USER, null)
        val proxyPassword = preferences.getString(C.PROXY_PASSWORD, null)
        val proxyMultivariant = !slot.httpProxyDisabled &&
            preferences.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false) &&
            !proxyHost.isNullOrBlank() && proxyPort != null
        val proxyMedia = !slot.httpProxyDisabled &&
            preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) &&
            !proxyHost.isNullOrBlank() && proxyPort != null
        slot.httpProxyActive = proxyMultivariant || proxyMedia
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
                ) { !slot.httpProxyDisabled && preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
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
                ) { !slot.httpProxyDisabled && preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
                    .apply { headers?.let(::setDefaultRequestProperties) }
            }
            else -> OkHttpDataSource.Factory(
                multivariantProxy ?: module.okHttpClient.value,
                mediaProxy,
            ) { !slot.httpProxyDisabled && preferences.getBoolean(C.PROXY_MEDIA_PLAYLIST, true) }
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
        val allowDirectFallback = applicationContext.prefs().getBoolean(C.PROXY_ALLOW_DIRECT_FALLBACK, true)
        return (applicationContext as XtraApp).xtraModule.okHttpClient.value.newBuilder().apply {
            proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    return if (Regex(regex).matches(uri.host.orEmpty())) {
                        proxyCandidates(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)), allowDirectFallback)
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

    /**
     * Statistics intentionally measure cumulative stream-hours: every tile is
     * a separately playing playback source and contributes independently.
     */
    private fun updateViewingStats(slot: MultiviewPlayerSlot, forceNotPlaying: Boolean = false) {
        viewingStatsRecorder?.update(
            sourceId = "multiview:${slot.identity}",
            metadata = ViewingPlaybackMetadata(
                channelId = slot.stream.channelId,
                channelLogin = slot.stream.channelLogin,
                channelName = slot.stream.channelName,
                channelImage = slot.stream.channelImage,
                categoryId = slot.stream.gameId,
                categoryName = slot.stream.gameName,
                contentType = ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                contentId = slot.stream.id,
                title = slot.stream.title,
            ),
            isPlaying = !forceNotPlaying && slot.player.isPlaying,
            isBuffering = slot.player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun releaseViewingStats(slot: MultiviewPlayerSlot) {
        viewingStatsRecorder?.release("multiview:${slot.identity}")
    }

    class MultiviewPlayerSlot(
        val identity: String,
        var stream: Stream,
        val player: ExoPlayer,
    ) {
        var attachedView: PlayerView? = null
        var loadJob: Job? = null
        var retryJob: Job? = null
        var stableRecoveryJob: Job? = null
        var adAvoidanceJob: Job? = null
        var primaryRestoreJob: Job? = null
        val adController = TwitchAdController()
        var retryCount: Int = 0
        private var recoveryState = MultiviewQualityRecoveryState()
        val downgradeLevel: Int get() = recoveryState.downgradeLevel
        val resourceFailure: Boolean get() = recoveryState.resourcePressure
        var customProxy: Boolean = false
        var customProxyDisabled: Boolean = false
        var httpProxyActive: Boolean = false
        var httpProxyDisabled: Boolean = false
        var currentPlayerType: String? = null
        var currentPlaylistUrl: String? = null
        var usingAlternateStream: Boolean = false
        var playingAds: Boolean = false
        var hiddenForAd: Boolean = false
        var videoDisabledForBackground: Boolean = false
        var lastResponseCode: Int? = null
        var lastMasterResponseCode: Int? = null
        var lastResponseWasMaster: Boolean = false
        var reloadRequired: Boolean = false
        var effectiveQualityLabel: String? = null
        var manualRetry: Boolean = false
        var shouldPlay: Boolean = true
        var hasMediaSource: Boolean = false
        var status: MultiviewSlotStatus = MultiviewSlotStatus.LOADING
        var target: MultiviewQualityTarget? = null
        var availableQualities: List<String> = emptyList()

        fun markReady() {
            recoveryState = MultiviewQualityRecovery.onReady(recoveryState)
        }

        fun recordRebuffer(now: Long = System.currentTimeMillis()): Boolean {
            val previous = recoveryState
            recoveryState = MultiviewQualityRecovery.onBuffering(recoveryState, now)
            return recoveryState.downgradeLevel > previous.downgradeLevel
        }

        fun markResourceFailure(now: Long = System.currentTimeMillis()) {
            recoveryState = MultiviewQualityRecovery.onResourceFailure(recoveryState, now)
        }

        fun retainDowngradeAtMost(level: Int) {
            recoveryState = recoveryState.copy(
                downgradeLevel = recoveryState.downgradeLevel.coerceAtMost(level),
            )
        }

        fun recoverAfterStable(now: Long = System.currentTimeMillis()): Boolean {
            val previous = recoveryState
            recoveryState = MultiviewQualityRecovery.onStablePlayback(recoveryState, now)
            return recoveryState != previous
        }

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
            stableRecoveryJob?.cancel()
            adAvoidanceJob?.cancel()
            primaryRestoreJob?.cancel()
            attachedView?.let { view ->
                if (view.player === player) {
                    logVideoSurfaceBinding("multiview_detach", player, view, view.player)
                    view.player = null
                }
            }
            attachedView = null
            player.release()
        }
    }

    companion object {
        private const val TAG = "MultiviewPlayback"
        private const val MAX_RETRIES = 6
        private const val NETWORK_RETRY_DELAY_MS = 15_000L
        private const val PRIMARY_RESTORE_INTERVAL_MS = 10_000L
        private const val FRAME_RATE_TOLERANCE = 0.5f
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)
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
