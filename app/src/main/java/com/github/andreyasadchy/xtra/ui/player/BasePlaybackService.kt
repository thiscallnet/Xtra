package com.github.andreyasadchy.xtra.ui.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.mergeViewingCategoryPatch
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.watch.TwitchWatchSession
import com.github.andreyasadchy.xtra.util.watch.WatchTelemetryReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

abstract class BasePlaybackService : LifecycleService() {

    lateinit var xtraModule: XtraModule

    protected abstract fun isActuallyPlayingForWatchCredit(): Boolean

    protected val watchTelemetryReporter by lazy {
        WatchTelemetryReporter(
            scope = lifecycleScope,
            sendMinuteWatched = { watchSession ->
                xtraModule.playerRepository.sendMinuteWatched(
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    session = watchSession,
                )
            },
            isAuthenticated = { authenticatedWatchUserId() != null },
            log = { message -> Log.d(WatchTelemetryReporter.LOG_TAG, message) },
        )
    }

    private var watchStreamIdentityRefreshJob: Job? = null

    var type: String? = null
    var streamId: String? = null
    var videoId: String? = null
    var clipId: String? = null
    var offlineVideoId: Int? = null
    var channelId: String? = null
    var channelLogin: String? = null
    var channelName: String? = null
    var channelImage: String? = null
    var gameId: String? = null
    var gameSlug: String? = null
    var gameName: String? = null
    var title: String? = null
    var thumbnail: String? = null
    var createdAt: String? = null
    var viewerCount: Int? = null
    var durationSeconds: Int? = null
    var videoType: String? = null
    var videoOffsetSeconds: Int? = null
    var videoCreatedAt: String? = null
    var videoAnimatedPreviewURL: String? = null
    var videoUrl: String? = null
    var savedPosition: Long? = null
    var paused = false
    var qualities: List<VideoQuality>? = null
    var quality: VideoQuality? = null
    var previousQuality: VideoQuality? = null
    var restoreQuality = false
    var playlistUrl: String? = null
    var restorePlaylist = false
    var useCustomProxy = false
    var skipAccessToken = false

    private val viewingStatsSourceId = "playback-service:primary"

    var chatUrl: String? = null
    var started = false
    var loaded = false

    protected fun updateWatchTelemetryPlayback() {
        val isActuallyPlaying = type == STREAM && isActuallyPlayingForWatchCredit()
        if (isActuallyPlaying) {
            val userId = authenticatedWatchUserId()
            if (!userId.isNullOrBlank() && !channelId.isNullOrBlank() && !channelLogin.isNullOrBlank()) {
                watchTelemetryReporter.start(
                    TwitchWatchSession(
                        channelId = channelId!!,
                        channelLogin = channelLogin!!,
                        streamId = streamId,
                        userId = userId,
                    ),
                )
            }
        }
        watchTelemetryReporter.setActuallyPlaying(isActuallyPlaying)
    }

    protected fun refreshWatchStreamIdentityAsync(reason: String) {
        val expectedChannelId = channelId ?: return
        val expectedChannelLogin = channelLogin ?: return
        watchStreamIdentityRefreshJob?.cancel()
        watchStreamIdentityRefreshJob = lifecycleScope.launch {
            val resolvedStreamId = try {
                xtraModule.graphQLRepository.loadQueryUsersStream(
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers = TwitchApiHelper.getGQLHeaders(
                        this@BasePlaybackService,
                        includeToken = true,
                    ),
                    ids = listOf(expectedChannelId),
                    logins = null,
                ).data?.users?.firstOrNull()?.stream?.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(
                    WatchTelemetryReporter.LOG_TAG,
                    "WatchSession stream identity refresh failed " +
                        "reason=$reason channel=$expectedChannelId " +
                        "error=${e.javaClass.simpleName}",
                )
                null
            }

            acceptedWatchStreamId(
                expectedChannelId = expectedChannelId,
                expectedChannelLogin = expectedChannelLogin,
                currentChannelId = channelId,
                currentChannelLogin = channelLogin,
                currentStreamId = streamId,
                resolvedStreamId = resolvedStreamId,
            )?.let { acceptedStreamId ->
                Log.d(
                    WatchTelemetryReporter.LOG_TAG,
                    "WatchSession broadcast changed " +
                        "old=${streamId ?: "null"} new=$acceptedStreamId",
                )
                streamId = acceptedStreamId
                watchTelemetryReporter.stop()
                updateWatchTelemetryPlayback()
            }
        }
    }

    protected fun stopWatchTelemetry() {
        watchStreamIdentityRefreshJob?.cancel()
        watchStreamIdentityRefreshJob = null
        watchTelemetryReporter.stop()
    }

    private fun authenticatedWatchUserId(): String? {
        val userId = tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val gqlToken = TwitchApiHelper.getGQLHeaders(this, includeToken = true)[C.HEADER_TOKEN]
        val helixToken = TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN]
        return userId.takeIf { !gqlToken.isNullOrBlank() || !helixToken.isNullOrBlank() }
    }

    protected suspend fun restorePlaybackState() {
        val savedState = xtraModule.playbackPersistence.takePlaybackState()
        if (savedState != null) {
            type = savedState.type
            streamId = savedState.streamId
            videoId = savedState.videoId
            clipId = savedState.clipId
            offlineVideoId = savedState.offlineVideoId
            channelId = savedState.channelId
            channelLogin = savedState.channelLogin
            channelName = savedState.channelName
            channelImage = savedState.channelImage
            gameId = savedState.gameId
            gameSlug = savedState.gameSlug
            gameName = savedState.gameName
            title = savedState.title
            thumbnail = savedState.thumbnail
            createdAt = savedState.createdAt
            viewerCount = savedState.viewerCount
            durationSeconds = savedState.durationSeconds
            videoType = savedState.videoType
            videoOffsetSeconds = savedState.videoOffsetSeconds
            videoCreatedAt = savedState.videoCreatedAt
            videoAnimatedPreviewURL = savedState.videoAnimatedPreviewURL
            videoUrl = savedState.videoUrl
            savedPosition = savedState.position
            paused = savedState.paused
            qualities = savedState.qualities?.let { qualities ->
                xtraModule.json.decodeFromString<JsonArray>(qualities).map {
                    xtraModule.json.decodeFromJsonElement<VideoQuality>(it)
                }
            }
            quality = savedState.quality?.let { xtraModule.json.decodeFromString(it) }
            previousQuality = savedState.previousQuality?.let { xtraModule.json.decodeFromString(it) }
            restoreQuality = savedState.restoreQuality
            playlistUrl = savedState.playlistUrl
            restorePlaylist = savedState.restorePlaylist
            useCustomProxy = savedState.useCustomProxy
            skipAccessToken = savedState.skipAccessToken
        }
    }

    protected fun savePlaybackState(position: Long?, paused: Boolean) {
        val item = PlaybackState(
            type = type,
            streamId = streamId,
            videoId = videoId,
            clipId = clipId,
            offlineVideoId = offlineVideoId,
            channelId = channelId,
            channelLogin = channelLogin,
            channelName = channelName,
            channelImage = channelImage,
            gameId = gameId,
            gameSlug = gameSlug,
            gameName = gameName,
            title = title,
            thumbnail = thumbnail,
            createdAt = createdAt,
            viewerCount = viewerCount,
            durationSeconds = durationSeconds,
            videoType = videoType,
            videoOffsetSeconds = videoOffsetSeconds,
            videoCreatedAt = videoCreatedAt,
            videoAnimatedPreviewURL = videoAnimatedPreviewURL,
            videoUrl = videoUrl,
            position = position,
            paused = paused,
            qualities = qualities?.let { qualities ->
                buildJsonArray {
                    qualities.forEach {
                        add(xtraModule.json.encodeToJsonElement(it))
                    }
                }.toString()
            },
            quality = quality?.let { xtraModule.json.encodeToString(it) },
            previousQuality = previousQuality?.let { xtraModule.json.encodeToString(it) },
            restoreQuality = restoreQuality,
            playlistUrl = playlistUrl,
            restorePlaylist = restorePlaylist,
            useCustomProxy = useCustomProxy,
            skipAccessToken = skipAccessToken,
        )
        xtraModule.playbackPersistence.savePlaybackState(item)
    }

    protected fun saveVideoPosition(position: Long) {
        videoId?.toLongOrNull()?.let {
            xtraModule.playbackPersistence.saveVideoPosition(
                VideoPosition(it, position),
            )
            xtraModule.playbackPersistence.saveVideoHistoryPosition(it, position)
        } ?: offlineVideoId?.let {
            xtraModule.playbackPersistence.saveOfflineVideoPosition(it, position)
        }
    }

    protected fun deletePlaybackStates() {
        xtraModule.playbackPersistence.deletePlaybackStates()
    }

    protected fun runAfterPlaybackPersistence(action: () -> Unit) {
        lifecycleScope.launch {
            xtraModule.playbackPersistence.flush()
            action()
        }
    }

    protected fun updateViewingStats(isPlaying: Boolean, isBuffering: Boolean = false) {
        if (!::xtraModule.isInitialized) return
        val contentType = when (type) {
            STREAM -> ViewingPlaybackMetadata.CONTENT_TYPE_LIVE
            VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_VOD
            CLIP -> ViewingPlaybackMetadata.CONTENT_TYPE_CLIP
            OFFLINE_VIDEO -> ViewingPlaybackMetadata.CONTENT_TYPE_OFFLINE_VIDEO
            else -> null
        } ?: return
        val contentId = when (type) {
            STREAM -> streamId
            VIDEO -> videoId
            CLIP -> clipId ?: videoId
            OFFLINE_VIDEO -> offlineVideoId?.toString() ?: clipId
            else -> null
        }
        xtraModule.viewingStatsRecorder.update(
            sourceId = viewingStatsSourceId,
            metadata = ViewingPlaybackMetadata(
                channelId = channelId,
                channelLogin = channelLogin,
                channelName = channelName,
                channelImage = channelImage,
                categoryId = gameId,
                categoryName = gameName,
                contentType = contentType,
                contentId = contentId,
                title = title,
            ),
            isPlaying = isPlaying,
            isBuffering = isBuffering,
        )
    }

    /**
     * Updates metadata for the playback that is already running. This must
     * not use the start/finish path: a live channel can change category while
     * the same player and viewing session continue.
     */
    fun updateViewingMetadata(
        categoryId: String?,
        categoryName: String?,
        title: String? = null,
    ) {
        if (type == null) return
        // PubSub stream-info messages can omit individual fields. Treat null
        // as "not supplied" so a partial refresh cannot erase attribution
        // that is still valid for the running playback.
        val nextCategory = mergeViewingCategoryPatch(
            currentId = gameId,
            currentName = gameName,
            patchId = categoryId,
            patchName = categoryName,
        )
        val nextGameId = nextCategory.id
        val nextGameName = nextCategory.name
        val nextTitle = title ?: this.title
        val changed = gameId != nextGameId || gameName != nextGameName ||
                this.title != nextTitle
        gameId = nextGameId
        gameName = nextGameName
        this.title = nextTitle
        if (changed) {
            updateViewingStats(
                isPlaying = isViewingPlaybackPlaying(),
                isBuffering = isViewingPlaybackBuffering(),
            )
        }
    }

    protected open fun isViewingPlaybackPlaying(): Boolean = false

    protected open fun isViewingPlaybackBuffering(): Boolean = false

    protected fun releaseViewingStats() {
        if (::xtraModule.isInitialized) {
            xtraModule.viewingStatsRecorder.release(viewingStatsSourceId)
        }
    }

    protected fun setDefaultQuality() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val defaultQuality = if (cellular) {
            prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        } else {
            prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved")
        }?.substringBefore(" ")
        quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs().getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    AUTO_QUALITY -> qualities?.find { it.name == AUTO_QUALITY }
                    AUDIO_ONLY_QUALITY -> qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    CHAT_ONLY_QUALITY -> qualities?.find { it.name == CHAT_ONLY_QUALITY }
                    else -> findQuality(savedQuality)
                }
            }
            AUTO_QUALITY -> qualities?.find { it.name == AUTO_QUALITY }
            "Source" -> qualities?.find { it.name != AUTO_QUALITY }
            AUDIO_ONLY_QUALITY -> qualities?.find { it.name == AUDIO_ONLY_QUALITY }
            CHAT_ONLY_QUALITY -> qualities?.find { it.name == CHAT_ONLY_QUALITY }
            else -> findQuality(defaultQuality)
        } ?: qualities?.firstOrNull()
    }

    private fun findQuality(targetQualityString: String?): VideoQuality? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = qualities?.last { it.name != AUDIO_ONLY_QUALITY && it.name != CHAT_ONLY_QUALITY }
            qualities?.find { qualityString ->
                val quality = qualityString.name?.split("p")
                val resolution = quality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                val fps = quality?.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || qualityString == last)
            }
        }
    }

    companion object {
        const val AUTO_QUALITY = "auto"
        const val SOURCE_QUALITY = "source"
        const val AUDIO_ONLY_QUALITY = "audio_only"
        const val CHAT_ONLY_QUALITY = "chat_only"

        const val STREAM = "stream"
        const val VIDEO = "video"
        const val CLIP = "clip"
        const val OFFLINE_VIDEO = "offlineVideo"
    }
}
