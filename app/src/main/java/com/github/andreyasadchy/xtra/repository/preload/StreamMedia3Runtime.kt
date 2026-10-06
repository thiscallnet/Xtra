package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.os.Handler
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.player.hls.TwitchHlsPlaylistDiagnostics
import com.github.andreyasadchy.xtra.ui.player.StreamHlsMediaSourceFactory
import com.github.andreyasadchy.xtra.ui.player.SmoothHlsQualityPolicy
import com.github.andreyasadchy.xtra.ui.player.SmoothHlsTrackSelector
import com.github.andreyasadchy.xtra.ui.player.DesiredHlsQuality
import com.github.andreyasadchy.xtra.ui.player.captions.LiveCaptionManager
import com.github.andreyasadchy.xtra.ui.player.captions.LiveCaptionRenderersFactory
import com.github.andreyasadchy.xtra.ui.player.PlaybackRenderersFactory
import com.github.andreyasadchy.xtra.util.AdaptiveLiveLoadControl
import com.github.andreyasadchy.xtra.util.AdaptiveLivePlaybackController
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LivePlaybackPolicies
import com.github.andreyasadchy.xtra.util.m3u8.VaftSegmentEvidenceRecorder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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

data class VaftWarmupHandle(
    val token: String,
    val completion: ListenableFuture<Boolean>,
)

data class VaftPreloadedMediaSource(
    val mediaSource: MediaSource,
    val mediaItem: MediaItem,
    val warmAgeMs: Long,
    val renditionCompatibleWithIntent: Boolean,
)

private data class VaftWarmupKey(
    val vaftGeneration: Long,
    val configurationFingerprint: String,
    val channelLogin: String,
    val candidateUrl: String,
    val playerType: String,
    val qualityIntent: DesiredHlsQuality,
    val qualityIntentRevision: Long,
    val verifiedRenditionUrl: String?,
    val allowAdaptiveRendition: Boolean,
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
        private const val VAFT_PRELOAD_SAMPLE_DURATION_MS = 1_000L
        private const val VAFT_PRELOAD_RANK = -1
        private const val VAFT_PRELOAD_WARM_TTL_MS = 3_000L
        const val VAFT_SOURCE_MEDIA_ID_PREFIX = "vaft-source:"
        private const val STAGE_NOT_ACHIEVED = -1
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = mutableListOf<Generation>()
    private val sourceInstanceCounter = AtomicLong()
    private var currentGeneration: Generation? = null
    @Volatile
    private var primaryPlaybackMediaId: String? = null
    private val proxyPlaylistObservations = ConcurrentHashMap<String, com.github.andreyasadchy.xtra.player.lowlatency.StreamRequestObservation>()
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
        qualitySelectionPolicy.addChangeListener { intent ->
            val invalidate = Runnable {
                synchronized(this) {
                    states.forEach { generation ->
                        val staleEntries = generation.vaftWarmEntries.values
                            .filter { !it.adopted && it.key.qualityIntent != intent }
                        if (staleEntries.isNotEmpty()) {
                            staleEntries.forEach { removeVaftWarmEntry(generation, it) }
                            generation.manager.setCurrentPlayingIndex(0)
                            generation.manager.invalidate()
                        }
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) invalidate.run() else mainHandler.post(invalidate)
        }
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
                clearVaftWarmEntries(generation)
                generation.manager.reset()
                generation.entries.clear()
                generation.manager.release()
                states.remove(generation)
                currentGeneration = null
            } else {
                // The primary player may still be reading a source owned by this
                // generation. Retain it until PlaybackService releases the player.
                discardPendingVaftWarmups(generation)
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
    fun configurationFingerprintFor(playbackPlayer: ExoPlayer): String? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 playback lookup must run on the main looper" }
        val generation = states.firstOrNull { it.player === playbackPlayer } ?: return null
        if (generation.configuration.fingerprint != configurationStore.current.fingerprint) return null
        return generation.configuration.fingerprint
    }

    @Synchronized
    fun beginVaftCandidateWarmup(
        playbackPlayer: ExoPlayer,
        vaftGeneration: Long,
        channelLogin: String,
        url: String,
        playerType: String,
        title: String?,
        channelName: String?,
        channelLogo: String?,
        qualityIntent: DesiredHlsQuality,
        verifiedRenditionUrl: String?,
        allowAdaptiveRendition: Boolean,
    ): VaftWarmupHandle? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 VAFT warmup must run on the main looper" }
        val generation = states.firstOrNull { it.player === playbackPlayer } ?: return null
        if (generation.player !== playbackPlayer) return null
        val currentConfiguration = configurationStore.current
        if (generation.configuration.fingerprint != currentConfiguration.fingerprint) return null
        val normalizedLogin = channelLogin.trim().lowercase()
        val key = VaftWarmupKey(
            vaftGeneration = vaftGeneration,
            configurationFingerprint = generation.configuration.fingerprint,
            channelLogin = normalizedLogin,
            candidateUrl = url,
            playerType = playerType,
            qualityIntent = qualityIntent,
            qualityIntentRevision = qualitySelectionPolicy.revision(),
            verifiedRenditionUrl = verifiedRenditionUrl,
            allowAdaptiveRendition = allowAdaptiveRendition,
        )
        generation.vaftWarmEntries.values.firstOrNull { it.key == key }?.let { existing ->
            val completedAgeMs = existing.completedAtMs?.let { elapsedRealtimeMs() - it }
            if (!existing.completion.isDone || completedAgeMs?.let { it in 0L..VAFT_PRELOAD_WARM_TTL_MS } == true) {
                return VaftWarmupHandle(existing.token, existing.completion)
            }
            removeVaftWarmEntry(generation, existing)
        }

        discardPendingVaftWarmups(generation)
        val rank = VAFT_PRELOAD_RANK
        val token = java.util.UUID.randomUUID().toString()
        val mediaItem = generation.hlsFactory.createLiveMediaItem(
            mediaId = "$VAFT_SOURCE_MEDIA_ID_PREFIX$token",
            uri = url,
            title = title,
            channelName = channelName,
            channelLogo = channelLogo,
        )
        val entry = VaftWarmEntry(
            token = token,
            key = key,
            mediaItem = mediaItem,
            rank = rank,
            addedAtMs = elapsedRealtimeMs(),
            completion = SettableFuture.create(),
        )
        generation.vaftWarmEntries[token] = entry
        generation.targetPreloadState.completedVaftRanks.remove(rank)
        generation.manager.add(mediaItem, rank)
        // Negative rank separates this entry from feed indices. The manager's
        // comparator uses distance from the current index, so this does not
        // claim priority over the normal feed preload queue.
        generation.manager.setCurrentPlayingIndex(0)
        generation.manager.invalidate()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "vaft_warm_start token=${token.take(8)} source=${mediaItem.mediaId.take(18)} playerType=$playerType")
        }
        return VaftWarmupHandle(token, entry.completion)
    }

    @Synchronized
    fun adoptVaftCandidateWarmup(
        playbackPlayer: ExoPlayer,
        token: String,
        vaftGeneration: Long,
        expectedUrl: String,
    ): VaftPreloadedMediaSource? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 VAFT warmup adoption must run on the main looper" }
        val generation = states.firstOrNull { it.player === playbackPlayer } ?: return null
        if (generation.player !== playbackPlayer) return null
        val entry = generation.vaftWarmEntries[token] ?: return null
        val completedAtMs = entry.completedAtMs ?: return null
        if (entry.adopted || runCatching { entry.completion.get() }.getOrNull() != true) return null
        val warmAgeMs = elapsedRealtimeMs() - completedAtMs
        if (entry.key.vaftGeneration != vaftGeneration ||
            entry.key.configurationFingerprint != generation.configuration.fingerprint ||
            entry.key.configurationFingerprint != configurationStore.current.fingerprint ||
            entry.key.candidateUrl != expectedUrl ||
            entry.key.qualityIntent != qualitySelectionPolicy.snapshot() ||
            entry.key.qualityIntentRevision != qualitySelectionPolicy.revision() ||
            warmAgeMs !in 0..VAFT_PRELOAD_WARM_TTL_MS
        ) {
            return null
        }
        val sourceState = generation.hlsFactory.findState(entry.mediaItem.mediaId) ?: return null
        if (sourceState.lastMediaPlaylistClean != true) return null
        val loadedRenditionKnown = sourceState.lastMediaPlaylistBaseUri?.isNotBlank() == true
        val exactRenditionWarm = entry.key.verifiedRenditionUrl == null ||
            sourceState.lastMediaPlaylistBaseUri == entry.key.verifiedRenditionUrl
        val renditionCompatibleWithIntent = exactRenditionWarm ||
            (entry.key.allowAdaptiveRendition && loadedRenditionKnown)
        val mediaSource = runCatching { generation.manager.getMediaSource(entry.mediaItem) }.getOrNull() ?: return null
        entry.adopted = true
        if (BuildConfig.DEBUG) {
            val renditionMode = when {
                exactRenditionWarm -> "exact"
                renditionCompatibleWithIntent -> "adaptive_clean"
                else -> "mismatch"
            }
            Log.d(TAG, "vaft_warm_adopt token=${token.take(8)} ageMs=$warmAgeMs renditionMode=$renditionMode")
        }
        return VaftPreloadedMediaSource(mediaSource, entry.mediaItem, warmAgeMs, renditionCompatibleWithIntent)
    }

    @Synchronized
    fun discardVaftCandidateWarmup(token: String?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 VAFT warmup cleanup must run on the main looper" }
        if (token.isNullOrBlank()) return
        val pair = states.asSequence().mapNotNull { generation ->
            generation.vaftWarmEntries[token]?.let { generation to it }
        }.firstOrNull() ?: return
        val (generation, entry) = pair
        if (entry.adopted && generation.player?.currentMediaItem?.mediaId == entry.mediaItem.mediaId) return
        removeVaftWarmEntry(generation, entry)
        generation.manager.setCurrentPlayingIndex(0)
        generation.manager.invalidate()
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
        val previousVaftWarm = currentMediaId?.let { id ->
            states.firstNotNullOfOrNull { generation ->
                generation.vaftWarmEntries.values.firstOrNull { it.adopted && it.mediaItem.mediaId == id }
                    ?.let { generation to it }
            }
        }
        val targetVaftWarm = mediaItem?.let { target ->
            states.asReversed().firstNotNullOfOrNull { generation ->
                generation.vaftWarmEntries.values.firstOrNull { it.mediaItem.mediaId == target.mediaId }
                    ?.let { generation to it }
            }
        }
        currentMediaId?.let(proxyPlaylistObservations::remove)
        mediaItem?.mediaId?.let(proxyPlaylistObservations::remove)
        currentMediaId?.let { currentId ->
            states.forEach { it.hlsFactory.findState(currentId)?.setPrimaryPlayback(false) }
        }
        targetEntry?.let { (generation, entry) ->
            generation.hlsFactory.findState(entry.mediaItem.mediaId)?.setPrimaryPlayback(true)
        }
        if (targetEntry == null) {
            mediaItem?.mediaId?.let { mediaId ->
                states.asReversed().firstNotNullOfOrNull { it.hlsFactory.findState(mediaId) }
                    ?.setPrimaryPlayback(true)
            }
        }
        states.forEach { it.playbackOwnership.release() }
        previousVaftWarm?.let { (generation, entry) ->
            generation.manager.remove(entry.mediaItem)
            generation.vaftWarmEntries.remove(entry.token)
            generation.targetPreloadState.completedVaftRanks.remove(entry.rank)
            generation.hlsFactory.releaseMediaItem(entry.mediaItem.mediaId)
            generation.manager.setCurrentPlayingIndex(0)
        }
        targetVaftWarm?.let { (generation, entry) ->
            generation.hlsFactory.findState(entry.mediaItem.mediaId)?.setPrimaryPlayback(true)
            entry.adopted = true
        }
        targetEntry?.let { (generation, entry) -> generation.playbackOwnership.setPrimaryMediaItem(entry.mediaItem) }
        targetVaftWarm?.let { (generation, entry) -> generation.playbackOwnership.setPrimaryMediaItem(entry.mediaItem) }
        primaryPlaybackMediaId = mediaItem?.mediaId
        currentMediaId?.let(::releaseClipDataSourceFactoryIfUnretained)
        if (desiredCandidates.isNotEmpty()) reconcile(desiredCandidates)
    }

    @Synchronized
    fun setVaftEvidenceAlternateSource(mediaItem: MediaItem?, isAlternate: Boolean) {
        val mediaId = mediaItem?.mediaId ?: return
        states.asReversed().firstNotNullOfOrNull { generation ->
            generation.hlsFactory.findState(mediaId)
        }?.setVaftAlternateEvidenceSource(isAlternate)
    }

    fun resetVaftEvidenceSession() {
        if (BuildConfig.DEBUG) VaftSegmentEvidenceRecorder.resetSession()
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
            setRenderersFactory(PlaybackRenderersFactory(playerContext))
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
    fun controlledPlaylistFor(mediaId: String?): com.github.andreyasadchy.xtra.player.hls.ControlledVaftPlaylist? =
        mediaId?.let { id -> states.asReversed().firstNotNullOfOrNull { it.hlsFactory.findState(id)?.controlledPlaylist } }

    @Synchronized
    fun proxyPlaylistObservationFor(mediaId: String): com.github.andreyasadchy.xtra.player.lowlatency.StreamRequestObservation? =
        if (primaryPlaybackMediaId != mediaId) null else proxyPlaylistObservations[mediaId]

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
                clearVaftWarmEntries(generation)
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
                clearVaftWarmEntries(old)
                xtraModule.liveCaptionManager.deactivateAudioBufferSink(old.captionAudioSink)
                old.manager.release()
                states.remove(old)
            } else {
                discardPendingVaftWarmups(old)
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
            when {
                isVaftPreloadRank(rank) -> if (targetPreloadState.completedVaftRanks.contains(rank)) {
                    DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                } else {
                    DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(VAFT_PRELOAD_SAMPLE_DURATION_MS)
                }
                // Live HLS never reaches a terminal range. Once the first
                // sample warmup completes, only retain source/track setup so
                // manager invalidations cannot turn it back into a stream.
                rank == 0 -> if (targetPreloadState.rankZeroSampleComplete) {
                    DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                } else {
                    DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(SAMPLE_PRELOAD_DURATION_MS)
                }
                rank == 1 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                rank == 2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
        val hlsFactory = StreamHlsMediaSourceFactory(context, xtraModule, configuration) { mediaId, observation ->
            if (primaryPlaybackMediaId == mediaId) proxyPlaylistObservations[mediaId] = observation
        }
        val captionAudioSink = xtraModule.liveCaptionManager.createAudioBufferSinkSession()
        val builder = DefaultPreloadManager.Builder(context, statusControl)
            .setMediaSourceFactory(hlsFactory)
            .setTrackSelectorFactory { selectorContext ->
                SmoothHlsTrackSelector(selectorContext, qualitySelectionPolicy)
            }
            .setLoadControl(playbackLoadControl)
            .setRenderersFactory(
                LiveCaptionRenderersFactory(
                    context = context,
                    audioBufferSink = captionAudioSink.sink,
                    presentationDelayMs = xtraModule.liveCaptionManager::presentationDelayMs,
                    captureAudio = { xtraModule.liveCaptionManager.isAudioBufferSinkCapturing(captionAudioSink) },
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
                val vaftWarm = generation.vaftWarmEntries.values.firstOrNull { it.mediaItem == mediaItem }
                if (vaftWarm != null) {
                    if (vaftWarm.completion.isDone) return
                    val sourceState = generation.hlsFactory.findState(mediaItem.mediaId)
                    val isClean = sourceState?.lastMediaPlaylistClean == true
                    vaftWarm.completedAtMs = elapsedRealtimeMs()
                    if (isClean) generation.targetPreloadState.completedVaftRanks.add(vaftWarm.rank)
                    vaftWarm.completion.set(isClean)
                    if (BuildConfig.DEBUG) {
                        val exactRendition = vaftWarm.key.verifiedRenditionUrl == null ||
                            sourceState?.lastMediaPlaylistBaseUri == vaftWarm.key.verifiedRenditionUrl
                        val loadedRenditionKnown = sourceState?.lastMediaPlaylistBaseUri?.isNotBlank() == true
                        val adaptiveCleanRendition = vaftWarm.key.allowAdaptiveRendition && loadedRenditionKnown
                        val renditionMode = when {
                            exactRendition -> "exact"
                            isClean && adaptiveCleanRendition -> "adaptive_clean"
                            else -> "mismatch"
                        }
                        Log.d(
                            TAG,
                            "vaft_warm_complete token=${vaftWarm.token.take(8)} clean=$isClean " +
                                "renditionMode=$renditionMode elapsedMs=${vaftWarm.completedAtMs!! - vaftWarm.addedAtMs}",
                        )
                    }
                    return
                }
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
                generation.vaftWarmEntries.values.firstOrNull { !it.adopted && !it.completion.isDone }
                    ?.completion?.set(false)
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
            states.any { generation ->
                generation.entries.values.any { it.mediaItem.mediaId == mediaId } ||
                    generation.vaftWarmEntries.values.any { it.mediaItem.mediaId == mediaId }
            }
        ) return
        states.forEach { it.hlsFactory.releaseMediaItem(mediaId) }
    }

    private fun isVaftPreloadRank(rank: Int): Boolean =
        rank == VAFT_PRELOAD_RANK

    private fun discardPendingVaftWarmups(generation: Generation) {
        generation.vaftWarmEntries.values.filterNot { it.adopted }.toList().forEach {
            removeVaftWarmEntry(generation, it)
        }
    }

    private fun clearVaftWarmEntries(generation: Generation) {
        generation.vaftWarmEntries.values.toList().forEach { entry ->
            if (!entry.completion.isDone) entry.completion.set(false)
            if (!entry.adopted) {
                generation.manager.remove(entry.mediaItem)
                generation.hlsFactory.releaseMediaItem(entry.mediaItem.mediaId)
            }
            generation.targetPreloadState.completedVaftRanks.remove(entry.rank)
        }
        generation.vaftWarmEntries.clear()
    }

    private fun removeVaftWarmEntry(generation: Generation, entry: VaftWarmEntry) {
        generation.vaftWarmEntries.remove(entry.token)
        if (!entry.completion.isDone) entry.completion.set(false)
        generation.manager.remove(entry.mediaItem)
        generation.targetPreloadState.completedVaftRanks.remove(entry.rank)
        generation.hlsFactory.releaseMediaItem(entry.mediaItem.mediaId)
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

    private data class VaftWarmEntry(
        val token: String,
        val key: VaftWarmupKey,
        val mediaItem: MediaItem,
        val rank: Int,
        val addedAtMs: Long,
        val completion: SettableFuture<Boolean>,
        var completedAtMs: Long? = null,
        var adopted: Boolean = false,
    )

    private class TargetPreloadState {
        @Volatile
        var rankZeroSampleComplete = false

        val completedVaftRanks: MutableSet<Int> = ConcurrentHashMap.newKeySet()
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
        val vaftWarmEntries: MutableMap<String, VaftWarmEntry> = ConcurrentHashMap(),
        var player: ExoPlayer? = null,
        val playbackOwnership: StreamMedia3PlaybackOwnership = StreamMedia3PlaybackOwnership(),
    )

}
