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
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement

abstract class BasePlaybackService : LifecycleService() {

    lateinit var xtraModule: XtraModule

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
    /** True while the logical stream is temporarily backed by its recording VOD. */
    var liveRewindActive = false
        protected set
    var liveRewindVodId: String? = null
        protected set
    /** True while the physical player is being moved between live and rewind sources. */
    var liveRewindTransitioning = false
        protected set

    protected fun beginLiveRewindTransition() {
        liveRewindTransitioning = true
    }

    protected fun finishLiveRewindTransition() {
        liveRewindTransitioning = false
    }

    protected fun markLiveRewindActive(vodId: String) {
        liveRewindVodId = vodId
        liveRewindActive = true
    }

    protected fun clearLiveRewindState() {
        liveRewindActive = false
        liveRewindVodId = null
    }

    protected val liveRewindTransitionMutex = Mutex()

    private val viewingStatsSourceId = "playback-service:primary"

    var chatUrl: String? = null
    var started = false
    var loaded = false

    protected suspend fun restorePlaybackState() {
        val savedState = try {
            xtraModule.playbackPersistence.takePlaybackState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                "PlaybackRestore",
                "Unable to consume persisted playback state; starting without restore",
                e,
            )
            return
        }

        if (savedState != null) {
            val restoredQualities = decodeRestoredQualities(savedState.qualities)
            val decodedQuality = decodeRestoredQuality(savedState.quality, "quality")
            val decodedPreviousQuality =
                decodeRestoredQuality(savedState.previousQuality, "previous quality")
            // A quality without the list it belongs to can point at a stale VOD
            // or stream URL. Let the normal manifest/default-quality path choose
            // instead of combining fields from different playback sessions.
            val restoredQuality = selectRestoredQuality(restoredQualities, decodedQuality)
            val restoredPreviousQuality =
                selectRestoredQuality(restoredQualities, decodedPreviousQuality)

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
            qualities = restoredQualities
            quality = restoredQuality
            previousQuality = restoredPreviousQuality
            restoreQuality = savedState.restoreQuality && restoredPreviousQuality != null
            playlistUrl = savedState.playlistUrl
            restorePlaylist = savedState.restorePlaylist
            useCustomProxy = savedState.useCustomProxy
            skipAccessToken = savedState.skipAccessToken
        }
    }

    private fun decodeRestoredQualities(encoded: String?): List<VideoQuality>? =
        decodePlaybackQualities(xtraModule.json, encoded) { error ->
            Log.w(
                "PlaybackRestore",
                "Ignoring invalid persisted playback qualities",
                error,
            )
        }

    private fun decodeRestoredQuality(encoded: String?, fieldName: String): VideoQuality? =
        decodePlaybackQuality(xtraModule.json, encoded) { error ->
            Log.w(
                "PlaybackRestore",
                "Ignoring invalid persisted playback $fieldName",
                error,
            )
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
        quality = resolveDefaultQualityForNetwork(cellular)
    }

    fun resolveDefaultQualityForNetwork(cellular: Boolean): VideoQuality? {
        val defaultQuality = if (cellular) {
            prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        } else {
            prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved")
        }?.substringBefore(" ")
        return when (defaultQuality) {
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
