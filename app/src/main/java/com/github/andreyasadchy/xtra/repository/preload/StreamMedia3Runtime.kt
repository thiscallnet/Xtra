package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.ui.player.StreamHlsMediaSourceFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.security.MessageDigest

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
) {
    companion object {
        private const val TAG = "StreamMedia3"
        const val PRELOAD_TARGET_BYTES = 32 * 1024 * 1024
        const val SAMPLE_PRELOAD_MAX_AGE_MS = 4_500L
        const val SAMPLE_PRELOAD_DURATION_MS = 1_800L
        private const val STAGE_NOT_ACHIEVED = -1
    }

    private val context = context.applicationContext
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private val states = mutableListOf<Generation>()
    private var currentGeneration: Generation? = null
    private var desiredCandidates: List<LiveMediaPreloadCandidate> = emptyList()
    private val staleRefresh = Runnable {
        if (desiredCandidates.isNotEmpty() && currentGeneration != null) reconcile(desiredCandidates)
    }
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
        C.ENABLE_INTEGRITY,
        C.PLAYER_LOW_LATENCY,
        C.TOKEN_INCLUDE_TOKEN_STREAM,
        C.TOKEN_RANDOM_DEVICE_ID,
        C.TOKEN_X_DEVICE_ID,
        C.TOKEN_PLAYER_TYPE,
        C.TOKEN_SUPPORTED_CODECS,
        C.GQL_HEADERS,
        C.GQL_TOKEN2,
        C.GQL_TOKEN2_REFRESH,
        C.GQL_TOKEN2_EXPIRES_AT,
        C.GQL_TOKEN2_CLIENT_ID,
        C.GQL_TOKEN2_USER_ID,
        C.GQL_TOKEN2_SCOPES,
        C.GQL_TOKEN2_TYPE,
        C.GQL_TOKEN_WEB,
        C.GQL_CLIENT_ID2,
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
        mainHandler.removeCallbacks(staleRefresh)
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
                MediaPreloadPlanEntry(it.channelLogin, it.url, it.rank, addedAtMs = now)
            },
            nowMs = now,
            staleAfterMs = SAMPLE_PRELOAD_MAX_AGE_MS,
        )
        plan.removed.forEach { removed ->
            val entry = generation.entries[removed.channelLogin] ?: return@forEach
            if (generation.playbackOwnership.protects(entry.mediaItem)) {
                debug("preload_protected", entry.channelLogin)
                return@forEach
            }
            generation.entries.remove(removed.channelLogin)
            if (removed.rank == 0 && removed.samplesLoadedAtMs != null &&
                now - removed.samplesLoadedAtMs >= SAMPLE_PRELOAD_MAX_AGE_MS &&
                desired[removed.channelLogin]?.url == removed.url
            ) {
                debug("preload_stale_refresh", entry.channelLogin)
            } else {
                debug("preload_evicted", entry.channelLogin)
            }
            generation.manager.remove(entry.mediaItem)
        }

        plan.added.sortedBy { it.rank }.forEach { planned ->
            val candidate = desired[planned.channelLogin] ?: return@forEach
            val login = candidate.channelLogin.trim().lowercase()
            val item = generation.hlsFactory.createLiveMediaItem(
                mediaId = mediaId(generation.configuration, login, candidate.url),
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
        }
        generation.manager.setCurrentPlayingIndex(0)
        generation.manager.invalidate()
        scheduleStaleRefresh(generation)
    }

    @Synchronized
    fun clearPreloads(keepChannelLogin: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 preload clearing must run on the main looper" }
        mainHandler.removeCallbacks(staleRefresh)
        desiredCandidates = emptyList()
        currentGeneration?.let { generation ->
            val keep = keepChannelLogin?.trim()?.lowercase()
            if (keep == null) {
                if (shouldResetPreloadManager(generation.player != null)) {
                    generation.manager.reset()
                    generation.entries.clear()
                } else {
                    generation.entries.values.toList()
                        .filterNot { generation.playbackOwnership.protects(it.mediaItem) }
                        .forEach {
                            generation.manager.remove(it.mediaItem)
                            generation.entries.remove(it.channelLogin)
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
                    }
                generation.manager.setCurrentPlayingIndex(0)
                generation.manager.invalidate()
            }
        }
    }

    @Synchronized
    fun invalidateConfiguration() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 preload invalidation must run on the main looper" }
        mainHandler.removeCallbacks(staleRefresh)
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
    ): MediaItem {
        val generation = ensureGeneration()
        val login = channelLogin.trim().lowercase()
        return generation.hlsFactory.createLiveMediaItem(
            mediaId(generation.configuration, login, url),
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
    ): MediaItem {
        val generation = ensureGeneration()
        return generation.hlsFactory.createVodMediaItem(
            mediaId(generation.configuration, "vod:$videoId", url),
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
                configurationMatches = generation.configuration.fingerprint == StreamPlaybackConfiguration.from(context).fingerprint,
                nowMs = now,
                staleAfterMs = SAMPLE_PRELOAD_MAX_AGE_MS,
        )) return null
        val age = now - (entry.samplesLoadedAtMs ?: entry.addedAtMs)
        val source = runCatching { generation.manager.getMediaSource(entry.mediaItem) }.getOrNull() ?: return null
        return PreloadedLiveMediaSource(source, entry.mediaItem, age, entry.achievedStage)
    }

    @Synchronized
    fun setPrimaryPlaybackMediaItem(mediaItem: MediaItem?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 playback handoff must run on the main looper" }
        val targetGeneration = mediaItem?.let { target ->
            states.asReversed().firstOrNull { generation ->
                generation.entries.values.any { it.mediaItem === target }
            }
        }
        val currentMediaItem = states.asReversed()
            .firstOrNull { it.playbackOwnership.currentMediaItem() != null }
            ?.playbackOwnership
            ?.currentMediaItem()
        if (currentMediaItem === mediaItem && (mediaItem == null || targetGeneration != null)) return
        states.forEach { it.playbackOwnership.release() }
        targetGeneration?.playbackOwnership?.setPrimaryMediaItem(mediaItem)
        if (desiredCandidates.isNotEmpty()) reconcile(desiredCandidates)
    }

    @Synchronized
    fun createLiveMediaSource(mediaItem: MediaItem): MediaSource {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media3 live source creation must run on the main looper" }
        return ensureGeneration().hlsFactory.createMediaSource(mediaItem)
    }

    @Synchronized
    fun buildPlaybackPlayer(
        playerContext: Context,
        configure: ExoPlayer.Builder.() -> Unit,
    ): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback player creation must run on the main looper" }
        states.firstOrNull { it.player != null }?.player?.let { return it }
        val generation = ensureGeneration()
        generation.player?.let { return it }
        return generation.builder.buildExoPlayer(
            ExoPlayer.Builder(playerContext).apply(configure)
        ).also { generation.player = it }
    }

    @Synchronized
    fun buildPreviewPlayer(playerContext: Context, trackSelectionParameters: androidx.media3.common.TrackSelectionParameters): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Preview player creation must run on the main looper" }
        val generation = ensureGeneration()
        return ExoPlayer.Builder(playerContext, generation.hlsFactory).apply {
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
    fun releasePlaybackPlayer(player: ExoPlayer?) {
        if (player == null) return
        val playbackGenerations = states.filter { it.player === player }.toSet()
        states.forEach { generation ->
            if (generation.player === player) {
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
        val configuration = StreamPlaybackConfiguration.from(context)
        currentGeneration?.takeIf { it.configuration.fingerprint == configuration.fingerprint }?.let { return it }
        currentGeneration?.let { old ->
            if (shouldResetPreloadManager(old.player != null)) {
                old.manager.release()
                states.remove(old)
            }
        }
        currentGeneration = null
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_000, 2_000)
            .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, PRELOAD_TARGET_BYTES)
            .build()
        val statusControl = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
            when (rank) {
                0 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(SAMPLE_PRELOAD_DURATION_MS)
                1 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
        val hlsFactory = StreamHlsMediaSourceFactory(context, xtraModule, configuration)
        val builder = DefaultPreloadManager.Builder(context, statusControl)
            .setMediaSourceFactory(hlsFactory)
            .setLoadControl(loadControl)
        val generation = Generation(configuration, hlsFactory, builder, builder.build())
        generation.manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                val entry = generation.entries.values.firstOrNull { it.mediaItem == mediaItem } ?: return
                entry.achievedStage = targetStage(entry.rank)
                if (entry.rank == 0) entry.samplesLoadedAtMs = elapsedRealtimeMs()
                logStage(entry.rank, entry.channelLogin)
                if (entry.rank == 0) scheduleStaleRefresh(generation)
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

    private fun scheduleStaleRefresh(generation: Generation) {
        mainHandler.removeCallbacks(staleRefresh)
        if (generation.entries.values.any {
                it.rank == 0 && !generation.playbackOwnership.protects(it.mediaItem)
            }) {
            mainHandler.postDelayed(staleRefresh, SAMPLE_PRELOAD_MAX_AGE_MS)
        }
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

    private class Generation(
        val configuration: StreamPlaybackConfiguration,
        val hlsFactory: StreamHlsMediaSourceFactory,
        val builder: DefaultPreloadManager.Builder,
        val manager: DefaultPreloadManager,
        val entries: StreamMedia3PreloadEntries<Entry> = StreamMedia3PreloadEntries(),
        var player: ExoPlayer? = null,
        val playbackOwnership: StreamMedia3PlaybackOwnership = StreamMedia3PlaybackOwnership(),
    )

}
