package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistDiagnostics
import com.github.andreyasadchy.xtra.ui.player.StreamHlsMediaSourceFactory
import com.github.andreyasadchy.xtra.ui.player.SmoothHlsQualityPolicy
import com.github.andreyasadchy.xtra.ui.player.SmoothHlsTrackSelectionFactory
import com.github.andreyasadchy.xtra.ui.player.captions.LiveCaptionManager
import com.github.andreyasadchy.xtra.ui.player.captions.LiveCaptionRenderersFactory
import com.github.andreyasadchy.xtra.util.AdaptiveLiveLoadControl
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackController
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class LiveMediaPreloadCandidate(
    val channelLogin: String,
    val url: String,
    val rank: Int,
    val title: String? = null,
    val channelName: String? = null,
    val channelLogo: String? = null,
)

data class PreloadedLiveMediaSource(
    val mediaSource: MediaSource,
    val mediaItem: MediaItem,
    val mediaAgeMs: Long,
    val targetStage: Int,
)

internal fun shouldResetPreloadManager(hasPrimaryPlaybackPlayer: Boolean): Boolean = !hasPrimaryPlaybackPlayer

internal fun shouldReleasePreloadGeneration(
    isCurrentGeneration: Boolean,
    wasPrimaryPlaybackGeneration: Boolean,
): Boolean = wasPrimaryPlaybackGeneration || !isCurrentGeneration

internal fun shouldDeferProtectedPreloadReplacement(
    mediaItem: MediaItem,
    ownership: StreamMedia3PlaybackOwnership,
): Boolean = ownership.protects(mediaItem)

/** Shared Media3 builder/configuration owner for real live playback and speculative media. */
@UnstableApi
class StreamMedia3Runtime(
    context: Context,
    private val xtraModule: XtraModule,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val configurationStore: StreamPlaybackConfigurationStore = StreamPlaybackConfigurationStore(context),
) {
    val qualitySelectionPolicy = SmoothHlsQualityPolicy()
    companion object {
        private const val TAG = "StreamMedia3"
        const val PRELOAD_TARGET_BYTES = 32 * 1024 * 1024
        const val SAMPLE_PRELOAD_DURATION_MS = 1_800L
        private const val STAGE_NOT_ACHIEVED = -1
    }

    private val context = context.applicationContext
    private val states = mutableListOf<Generation>()
    private val sourceInstanceCounter = AtomicLong()
    private var currentGeneration: Generation? = null
    private var primaryPlaybackMediaId: String? = null
    private var desiredCandidates: List<LiveMediaPreloadCandidate> = emptyList()
    private val playbackPreferences = context.prefs()
    private val tokenPreferences = context.tokenPrefs()
    private val configurationPreferenceKeys = setOf(
        C.NETWORK_LIBRARY,
        C.PLAYER_STREAM_HEADERS,
        C.PLAYER_STREAM_PROXY,
        C.PLAYER_PROXY_URL,
        C.PROXY_PLAYBACK_ACCESS_TOKEN,
        C.PROXY_MULTIVARIANT_PLAYLIST,
        C.PROXY_HOST,
        C.PROXY_PORT,
        C.PROXY_USER,
        C.PROXY_PASSWORD,
        C.PLAYER_LOW_LATENCY,
        C.TOKEN_INCLUDE_TOKEN_STREAM,
        C.TOKEN_RANDOM_DEVICE_ID,
        C.TOKEN_X_DEVICE_ID,
        C.TOKEN_PLAYER_TYPE,
        C.TOKEN_SUPPORTED_CODECS,
        C.GQL_TOKEN_WEB,
        C.TWITCH_WEB_COOKIE_HEADER,
        C.GQL_CLIENT_ID_WEB,
    )
    private val configurationPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == C.STREAM_PRELOAD_MODE) {
            android.os.Handler(Looper.getMainLooper()).post { clearPreloads() }
            return@OnSharedPreferenceChangeListener
        }
        if (key !in configurationPreferenceKeys) return@OnSharedPreferenceChangeListener
        android.os.Handler(Looper.getMainLooper()).post {
            invalidateConfiguration()
        }
    }

    init {
        playbackPreferences.registerOnSharedPreferenceChangeListener(configurationPreferenceListener)
        tokenPreferences.registerOnSharedPreferenceChangeListener(configurationPreferenceListener)
    }

    @Synchronized
    fun reconcile(candidates: List<LiveMediaPreloadCandidate>) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 preload reconciliation must run on the main looper" }
        desiredCandidates = candidates
        val generation = ensureGeneration()
        val desired = candidates
            .filter { it.channelLogin.isNotBlank() && it.url.isNotBlank() }
            .associateBy { it.channelLogin.trim().lowercase() }
        val now = elapsedRealtimeMs()

        val plan = StreamMediaPreloadPlan.reconcile(
            existing = generation.entries.values.map {
                MediaPreloadPlanEntry(
                    channelLogin = it.channelLogin,
                    url = it.url,
                    rank = it.rank,
                    samplesLoadedAtMs = it.samplesLoadedAtMs,
                    addedAtMs = it.addedAtMs,
                )
            },
            candidates = desired.values.map {
                MediaPreloadPlanEntry(it.channelLogin, it.url, it.rank)
            },
        )
        var managerChanged = false
        plan.removed.forEach { removed ->
            val entry = generation.entries[removed.channelLogin] ?: return@forEach
            if (generation.playbackOwnership.protects(entry.mediaItem)) {
                debug("preload_protected", entry.channelLogin)
                return@forEach
            }
            generation.entries.remove(removed.channelLogin)
            debug("preload_evicted", entry.channelLogin)
            generation.manager.remove(entry.mediaItem)
            releaseClipDataSourceFactoryIfUnretained(entry.mediaItem.mediaId)
            managerChanged = true
            if (entry.rank == 0) generation.targetPreloadState.rankZeroSampleComplete = false
        }

        plan.added.sortedBy { it.rank }.forEach { planned ->
            val candidate = desired[planned.channelLogin] ?: return@forEach
            val login = candidate.channelLogin.trim().lowercase()
            val item = generation.hlsFactory.createLiveMediaItem(
                mediaId = sourceInstanceId(mediaId(generation.configuration, login, candidate.url)),
                uri = candidate.url,
                title = candidate.title,
                channelName = candidate.channelName,
                channelLogo = candidate.channelLogo,
            )
            val entry = Entry(
                channelLogin = login,
                url = candidate.url,
                mediaItem = item,
                rank = candidate.rank,
                addedAtMs = now,
            )
            if (!generation.entries.replaceUnlessProtected(login, entry) {
                    shouldDeferProtectedPreloadReplacement(it.mediaItem, generation.playbackOwnership)
                }) {
                debug("preload_replacement_deferred", login)
                return@forEach
            }
            generation.manager.add(item, candidate.rank)
            managerChanged = true
            if (candidate.rank == 0) generation.targetPreloadState.rankZeroSampleComplete = false
        }
        // A viewport update often repeats the same candidates. Re-invalidating
        // here would re-arm a completed live preload and start downloading it
        // again after the completion callback has cleared its period.
        if (!managerChanged) return
        generation.manager.setCurrentPlayingIndex(0)
        generation.manager.invalidate()
    }

    @Synchronized
    fun clearPreloads(keepChannelLogin: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 preload clearing must run on the main looper" }
        desiredCandidates = emptyList()
        currentGeneration?.let { generation ->
            val keep = keepChannelLogin?.trim()?.lowercase()
            if (keep == null) {
                if (shouldResetPreloadManager(generation.player != null)) {
                    generation.manager.reset()
                    val removedMediaIds = generation.entries.values.map { it.mediaItem.mediaId }
                    generation.entries.clear()
                    removedMediaIds.forEach(::releaseClipDataSourceFactoryIfUnretained)
                } else {
                    generation.entries.values.toList()
                        .filterNot { generation.playbackOwnership.protects(it.mediaItem) }
                        .forEach {
                            generation.manager.remove(it.mediaItem)
                            generation.entries.remove(it.channelLogin)
                            releaseClipDataSourceFactoryIfUnretained(it.mediaItem.mediaId)
                        }
                    generation.manager.setCurrentPlayingIndex(0)
                    generation.manager.invalidate()
                }
            } else {
                generation.entries.values.toList()
                    .filter {
                        it.channelLogin != keep &&
                            !generation.playbackOwnership.protects(it.mediaItem)
                    }
                    .forEach {
                        generation.manager.remove(it.mediaItem)
                        generation.entries.remove(it.channelLogin)
                        releaseClipDataSourceFactoryIfUnretained(it.mediaItem.mediaId)
                    }
                generation.manager.setCurrentPlayingIndex(0)
                generation.manager.invalidate()
            }
        }
    }

    @Synchronized
    fun invalidateConfiguration() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 preload invalidation must run on the main looper" }
        desiredCandidates = emptyList()
        currentGeneration?.let { generation ->
            if (shouldResetPreloadManager(generation.player != null)) {
                generation.manager.reset()
                generation.entries.clear()
                generation.manager.release()
                states.remove(generation)
                currentGeneration = null
            } else {
                // The primary player may still be reading a source owned by this
                // generation. Retain it until PlaybackService releases the player.
                currentGeneration = null
            }
        }
    }

    @Synchronized
    fun createLiveMediaItem(
        channelLogin: String,
        url: String,
        title: String? = null,
        channelName: String? = null,
        channelLogo: String? = null,
        uniqueSourceInstance: Boolean = true,
    ): MediaItem {
        val generation = ensureGeneration()
        val login = channelLogin.trim().lowercase()
        val contentMediaId = mediaId(generation.configuration, login, url)
        return generation.hlsFactory.createLiveMediaItem(
            if (uniqueSourceInstance) sourceInstanceId(contentMediaId) else contentMediaId,
            url,
            title,
            channelName,
            channelLogo,
        )
    }

    @Synchronized
    fun createVodMediaItem(
        videoId: String,
        url: String,
        title: String? = null,
        channelName: String? = null,
        channelLogo: String? = null,
        uniqueSourceInstance: Boolean = true,
    ): MediaItem {
        val generation = ensureGeneration()
        val contentMediaId = mediaId(generation.configuration, "vod:$videoId", url)
        return generation.hlsFactory.createVodMediaItem(
            if (uniqueSourceInstance) sourceInstanceId(contentMediaId) else contentMediaId,
            url,
            title,
            channelName,
            channelLogo,
        )
    }

    @Synchronized
    fun getPreloadedMediaSource(channelLogin: String, url: String): PreloadedLiveMediaSource? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 playback handoff must run on the main looper" }
        val generation = currentGeneration ?: return null
        // A new configuration generation must not hand a source to a player
        // created from the previous DefaultPreloadManager.Builder.
        if (generation.player == null) return null
        val login = channelLogin.trim().lowercase()
        val entry = generation.entries[login] ?: return null
        val now = elapsedRealtimeMs()
        if (entry.achievedStage == STAGE_NOT_ACHIEVED ||
            !StreamMediaPreloadHandoff.isUsable(
                entry = MediaPreloadPlanEntry(entry.channelLogin, entry.url, entry.rank, entry.samplesLoadedAtMs, entry.addedAtMs),
                requestedChannelLogin = login,
                requestedUrl = url,
                configurationMatches = generation.configuration.fingerprint == configurationStore.current.fingerprint,
                nowMs = now,
        )) return null
        val age = now - (entry.samplesLoadedAtMs ?: entry.addedAtMs)
        val source = runCatching { generation.manager.getMediaSource(entry.mediaItem) }.getOrNull() ?: return null
        return PreloadedLiveMediaSource(source, entry.mediaItem, age, entry.achievedStage)
    }

    @Synchronized
    fun setPrimaryPlaybackMediaItem(mediaItem: MediaItem?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 playback handoff must run on the main looper" }
        val targetEntry = mediaItem?.let { target ->
            states.asReversed().firstNotNullOfOrNull { generation ->
                generation.entries.values.firstOrNull { it.mediaItem.mediaId == target.mediaId }
                    ?.let { generation to it }
            }
        }
        val currentMediaId = primaryPlaybackMediaId
        if (currentMediaId == mediaItem?.mediaId) return
        states.forEach { it.playbackOwnership.release() }
        targetEntry?.let { (generation, entry) -> generation.playbackOwnership.setPrimaryMediaItem(entry.mediaItem) }
        primaryPlaybackMediaId = mediaItem?.mediaId
        currentMediaId?.let(::releaseClipDataSourceFactoryIfUnretained)
        if (desiredCandidates.isNotEmpty()) reconcile(desiredCandidates)
    }

    @Synchronized
    fun createLiveMediaSource(mediaItem: MediaItem): MediaSource {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 live source creation must run on the main looper" }
        return createHlsMediaSource(mediaItem)
    }

    @Synchronized
    fun createHlsMediaSource(mediaItem: MediaItem): MediaSource {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 HLS source creation must run on the main looper" }
        val generation = ensureGeneration()
        return generation.hlsFactory.createMediaSource(mediaItem)
    }

    @Synchronized
    fun clipDataSourceFactory(mediaId: String): DataSource.Factory? =
        states.asReversed()
            .asSequence()
            .mapNotNull { it.hlsFactory.clipDataSourceFactory(mediaId) }
            .firstOrNull()

    @Synchronized
    fun primaryPlaybackClipDataSourceFactory(mediaId: String): DataSource.Factory? =
        primaryPlaybackMediaId
            ?.takeIf { it == mediaId }
            ?.let(::clipDataSourceFactory)

    @Synchronized
    fun releaseTransientMediaItem(mediaId: String) {
        releaseClipDataSourceFactoryIfUnretained(mediaId)
    }

    @Synchronized
    fun buildPlaybackPlayer(
        playerContext: Context,
        configure: ExoPlayer.Builder.(AdaptiveLivePlaybackController, AdaptiveLiveLoadControl) -> Unit,
    ): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback player creation must run on the main looper" }
        states.firstOrNull { it.player != null }?.player?.let { return it }
        val generation = ensureGeneration()
        generation.player?.let { return it }
        return generation.builder.buildExoPlayer(
            ExoPlayer.Builder(playerContext).apply {
                configure(generation.adaptiveLiveController, generation.adaptiveLiveLoadControl)
            },
        ).also {
            // Claim this runtime generation before playback can deliver audio.
            // Older generation sinks then become inert and cannot flush the
            // active caption stream during a configuration transition.
            xtraModule.liveCaptionManager.activateAudioBufferSink(generation.captionAudioSink)
            generation.player = it
        }
    }

    @Synchronized
    fun buildPreviewPlayer(playerContext: Context, trackSelectionParameters: androidx.media3.common.TrackSelectionParameters): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Preview player creation must run on the main looper" }
        val generation = ensureGeneration()
        return ExoPlayer.Builder(playerContext, generation.hlsFactory).apply {
            setLoadControl(
                LivePlaybackPolicies.LOW_LATENCY.buffers.buildLoadControl {
                    setTargetBufferBytes(4 * 1024 * 1024)
                    setBackBuffer(0, false)
                },
            )
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            setHandleAudioBecomingNoisy(false)
        }.build().apply {
            setTrackSelectionParameters(trackSelectionParameters)
            volume = 0f
        }
    }

    @Synchronized
    fun setProxyMediaPlaylist(mediaId: String?, enabled: Boolean) {
        mediaId ?: return
        states.asReversed()
            .asSequence()
            .mapNotNull { it.hlsFactory.findState(mediaId) }
            .firstOrNull()
            ?.proxyMediaPlaylist = enabled
    }

    @Synchronized
    fun hlsDiagnosticsFor(mediaId: String): TwitchHlsPlaylistDiagnostics? =
        states.asReversed()
            .asSequence()
            .mapNotNull { it.hlsFactory.hlsDiagnosticsFor(mediaId) }
            .firstOrNull()

    @Synchronized
    fun releasePlaybackPlayer(player: ExoPlayer?) {
        if (player == null) return
        val playbackGenerations = states.filter { it.player === player }.toSet()
        if (playbackGenerations.isNotEmpty()) {
            val releasedMediaId = primaryPlaybackMediaId
            primaryPlaybackMediaId = null
            releasedMediaId?.let(::releaseClipDataSourceFactoryIfUnretained)
        }
        states.forEach { generation ->
            if (generation.player === player) {
                xtraModule.liveCaptionManager.deactivateAudioBufferSink(generation.captionAudioSink)
                generation.playbackOwnership.release()
                generation.player = null
            }
        }
        states.toList()
            .filter {
                it.player == null && shouldReleasePreloadGeneration(
                    isCurrentGeneration = it === currentGeneration,
                    wasPrimaryPlaybackGeneration = it in playbackGenerations,
                )
            }
            .forEach { generation ->
                generation.manager.release()
                states.remove(generation)
                if (currentGeneration === generation) currentGeneration = null
            }
    }

    private fun ensureGeneration(): Generation {
        val configuration = configurationStore.current
        currentGeneration?.takeIf { it.configuration.fingerprint == configuration.fingerprint }?.let { return it }
        currentGeneration?.let { old ->
            if (shouldResetPreloadManager(old.player != null)) {
                xtraModule.liveCaptionManager.deactivateAudioBufferSink(old.captionAudioSink)
                old.manager.release()
                states.remove(old)
            }
        }
        currentGeneration = null
        val initialLivePolicy = LivePlaybackPolicies.forLowLatency(configuration.lowLatency)
        val adaptiveLiveController = AdaptiveLivePlaybackController(
            initialPolicy = initialLivePolicy,
            adaptiveEnabled = false,
        )
        val playbackLoadControl = AdaptiveLiveLoadControl(
            controller = adaptiveLiveController,
            initialPolicy = initialLivePolicy,
            // Live latency settings only apply to actual live items. VOD keeps the
            // 15–50 second normal buffer policy for stable seeks and transient networks.
            nonLivePolicy = LivePlaybackPolicies.NORMAL,
            delegate = LivePlaybackPolicies.NORMAL.buffers.buildLoadControl {
                setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, PRELOAD_TARGET_BYTES)
            },
        )
        val targetPreloadState = TargetPreloadState()
        val statusControl = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
            when (rank) {
                // Live HLS never reaches a terminal range. Once the first
                // sample warmup completes, only retain source/track setup so
                // manager invalidations cannot turn it back into a stream.
                0 -> if (targetPreloadState.rankZeroSampleComplete) {
                    DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                } else {
                    DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(SAMPLE_PRELOAD_DURATION_MS)
                }
                1 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
        val hlsFactory = StreamHlsMediaSourceFactory(context, xtraModule, configuration)
        val captionAudioSink = xtraModule.liveCaptionManager.createAudioBufferSinkSession()
        val builder = DefaultPreloadManager.Builder(context, statusControl)
            .setMediaSourceFactory(hlsFactory)
            .setTrackSelectorFactory { selectorContext ->
                DefaultTrackSelector(
                    selectorContext,
                    SmoothHlsTrackSelectionFactory(qualitySelectionPolicy),
                )
            }
            .setLoadControl(playbackLoadControl)
            .setRenderersFactory(
                LiveCaptionRenderersFactory(
                    context = context,
                    audioBufferSink = captionAudioSink.sink,
                    presentationDelayMs = xtraModule.liveCaptionManager::presentationDelayMs,
                ),
            )
        val generation = Generation(
            configuration = configuration,
            hlsFactory = hlsFactory,
            builder = builder,
            manager = builder.build(),
            captionAudioSink = captionAudioSink,
            targetPreloadState = targetPreloadState,
            adaptiveLiveController = adaptiveLiveController,
            adaptiveLiveLoadControl = playbackLoadControl,
        )
        generation.manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                val entry = generation.entries.values.firstOrNull { it.mediaItem == mediaItem } ?: return
                if (entry.achievedStage == STAGE_NOT_ACHIEVED) {
                    entry.achievedStage = targetStage(entry.rank)
                }
                if (entry.rank == 0) {
                    if (!generation.targetPreloadState.rankZeroSampleComplete) {
                        generation.targetPreloadState.rankZeroSampleComplete = true
                        entry.samplesLoadedAtMs = elapsedRealtimeMs()
                        debug("sample_preload_complete", entry.channelLogin)
                        clearPreloadPeriod(generation, entry)
                    }
                }
                logStage(entry.rank, entry.channelLogin)
            }

            override fun onError(preloadException: PreloadException) {
                if (BuildConfig.DEBUG) Log.d(TAG, "preload_failed type=${preloadException::class.simpleName}")
            }
        })
        states += generation
        currentGeneration = generation
        debug("generation_created", null)
        return generation
    }

    fun newSourceInstanceId(contentIdentity: String): String = sourceInstanceId(contentIdentity)

    fun newSourceInstanceMediaItem(mediaItem: MediaItem, uri: String?): MediaItem {
        val contentIdentity = sourceContentIdentity(mediaItem.mediaId)
        return mediaItem.buildUpon()
            .setMediaId(sourceInstanceId(contentIdentity))
            .setUri(uri)
            .build()
    }

    private fun sourceInstanceId(contentIdentity: String): String =
        "$contentIdentity:${SystemClock.elapsedRealtimeNanos()}:${sourceInstanceCounter.incrementAndGet()}"

    private fun sourceContentIdentity(mediaId: String): String {
        // Generated source IDs end in elapsed-realtime nanos and a process counter.
        val counterSeparator = mediaId.lastIndexOf(':')
        val elapsedSeparator = mediaId.lastIndexOf(':', counterSeparator - 1)
        if (elapsedSeparator <= 0 || counterSeparator <= elapsedSeparator) return mediaId

        val elapsedRealtimeNanos = mediaId.substring(elapsedSeparator + 1, counterSeparator).toLongOrNull()
        val instanceSequence = mediaId.substring(counterSeparator + 1).toLongOrNull()
        return if (elapsedRealtimeNanos != null && instanceSequence != null && instanceSequence > 0) {
            mediaId.substring(0, elapsedSeparator)
        } else {
            mediaId
        }
    }

    private fun releaseClipDataSourceFactoryIfUnretained(mediaId: String) {
        if (primaryPlaybackMediaId == mediaId ||
            states.any { generation -> generation.entries.values.any { it.mediaItem.mediaId == mediaId } }
        ) return
        states.forEach { it.hlsFactory.releaseMediaItem(mediaId) }
    }

    private fun mediaId(configuration: StreamPlaybackConfiguration, login: String, url: String): String {
        val urlFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "xtra-live:${configuration.fingerprint.take(16)}:$login:$urlFingerprint"
    }

    private fun targetStage(rank: Int): Int = when (rank) {
        0 -> DefaultPreloadManager.PreloadStatus.STAGE_SPECIFIED_RANGE_LOADED
        1 -> DefaultPreloadManager.PreloadStatus.STAGE_TRACKS_SELECTED
        else -> DefaultPreloadManager.PreloadStatus.STAGE_SOURCE_PREPARED
    }

    private fun clearPreloadPeriod(generation: Generation, entry: Entry) {
        val source = runCatching { generation.manager.getMediaSource(entry.mediaItem) }.getOrNull()
        if (source is PreloadMediaSource) {
            source.clear()
        } else {
            debug("sample_preload_clear_failed", entry.channelLogin)
        }
    }

    private fun logStage(rank: Int, login: String) {
        if (!BuildConfig.DEBUG) return
        val stage = when (rank) {
            0 -> "samples_loaded"
            1 -> "tracks_selected"
            2 -> "source_prepared"
            else -> "not_preloaded"
        }
        Log.d(TAG, "$stage channel=$login")
    }

    private fun debug(event: String, login: String?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$event${login?.let { " channel=$it" }.orEmpty()}")
    }

    private data class Entry(
        val channelLogin: String,
        val url: String,
        val mediaItem: MediaItem,
        val rank: Int,
        val addedAtMs: Long,
        var achievedStage: Int = STAGE_NOT_ACHIEVED,
        var samplesLoadedAtMs: Long? = null,
    )

    private class TargetPreloadState {
        @Volatile
        var rankZeroSampleComplete = false
    }

    private class Generation(
        val configuration: StreamPlaybackConfiguration,
        val hlsFactory: StreamHlsMediaSourceFactory,
        val builder: DefaultPreloadManager.Builder,
        val manager: DefaultPreloadManager,
        val captionAudioSink: LiveCaptionManager.AudioBufferSinkSession,
        val targetPreloadState: TargetPreloadState,
        val adaptiveLiveController: AdaptiveLivePlaybackController,
        val adaptiveLiveLoadControl: AdaptiveLiveLoadControl,
        val entries: StreamMedia3PreloadEntries<Entry> = StreamMedia3PreloadEntries(),
        var player: ExoPlayer? = null,
        val playbackOwnership: StreamMedia3PlaybackOwnership = StreamMedia3PlaybackOwnership(),
    )

}
