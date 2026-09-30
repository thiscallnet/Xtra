package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.preload.StreamPreloadCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.TwitchVaftDetector
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal sealed interface FreshLiveStatus {
    data class Live(val stream: Stream) : FreshLiveStatus
    data object Offline : FreshLiveStatus
    data object Unknown : FreshLiveStatus
}

@OptIn(UnstableApi::class)
class Media3PlayerViewModel(
    private val applicationContext: Context,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val streamPreloadCoordinator: StreamPreloadCoordinator,
    private val bookmarksRepository: BookmarksRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val notificationsRepository: NotificationsRepository,
    private val streamFeedRefreshCoordinator: StreamFeedRefreshCoordinator,
    private val playbackPersistence: PlaybackPersistence,
) : ViewModel() {

    val streamResult = MutableStateFlow<String?>(null)
    val streamUrlWarm = MutableStateFlow(false)
    var streamUrlAvailableElapsedMs: Long? = null
    val stream = MutableStateFlow<Stream?>(null)
    val streamStatusKnown = MutableStateFlow(false)
    private val streamStatusRequestGeneration = AtomicLong()
    private var streamJob: Job? = null
    var useCustomProxy = false
    var vaftRequired = false
    var usingProxy = false
    var stopProxy = false
    var usingAlternateStream = false
    internal val vaftQualityState = VaftQualityState()
    private val vaftController = TwitchVaftController()
    var vaftLogicalQuality: VideoQuality? = null
    var vaftVerifiedRendition: VideoQuality? = null
    var vaftWindowActive = false

    val videoResult = MutableStateFlow<String?>(null)
    var backupQualities: List<String>? = null
    var playbackPosition: Long? = null
    val savedPosition = MutableStateFlow<Long?>(null)
    val isBookmarked = MutableStateFlow<Boolean?>(null)
    val gamesList = MutableStateFlow<List<Game>?>(null)
    var shouldRetry = true

    val clipUrls = MutableStateFlow<List<VideoQuality>?>(null)

    val savedOfflineVideoPosition = MutableStateFlow<Long?>(null)

    var qualities: List<VideoQuality>? = null
    var quality: VideoQuality? = null
    /** Restored quality arguments are a one-time startup fallback, never ongoing authority. */
    var restoredQualityBootstrapConsumed = false
    /** Last rendition reported by the active video decoder input. */
    var confirmedVideoQuality: VideoQuality? = null
    var confirmedVideoQualityMediaId: String? = null
    var resumeAppliedVideoQuality: VideoQuality? = null
    var resumeAppliedVideoQualityMediaId: String? = null
    var pendingResumeAppliedVideoQuality: VideoQuality? = null
    var pendingResumeAppliedSourceMediaId: String? = null
    var pendingVideoQuality: VideoQuality? = null
    var previousQuality: VideoQuality? = null
    var playlistUrl: Uri? = null
    var updateQualities = false
    var started = false
    var restoreQuality = false
    var resume = false
    var hidden = false
    val loaded = MutableStateFlow(false)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)
    private val _authenticationRequired = Channel<Unit>(Channel.BUFFERED)
    val authenticationRequired = _authenticationRequired.receiveAsFlow()

    suspend fun checkPlaylist(networkLibrary: String?, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout()
                        val request = httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout()
                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            cronetExecutor.value
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    }
                }
            }
            TwitchVaftDetector.requiresVaft(playlist)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun isPlayableMediaPlaylist(networkLibrary: String?, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = when {
                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout()
                        val request = httpEngine.value!!.newUrlRequestBuilder(
                            url,
                            cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(
                                continuation,
                                timeout,
                                throwOnHttpError = true,
                            ),
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.takeIf { response.info.httpStatusCode in 200..299 }
                }
                networkLibrary == C.CRONET && cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout()
                        val request = cronetEngine.value!!.newUrlRequestBuilder(
                            url,
                            NetworkUtils.ByteArrayCronetCallback(
                                continuation,
                                timeout,
                                throwOnHttpError = true,
                            ),
                            cronetExecutor.value,
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    response.body.takeIf { response.info.httpStatusCode in 200..299 }
                }
                else -> okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                    if (!response.isSuccessful) null else response.body.byteStream().use { it.readBytes() }
                }
            } ?: return@withContext false

            val text = String(body, Charsets.UTF_8)
            if (text.lineSequence().firstOrNull()?.removePrefix("\uFEFF")?.trim() != "#EXTM3U") {
                return@withContext false
            }
            PlaylistUtils.parseMediaPlaylist(body.inputStream()).segments.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun playerTypesForVaft(currentPlayerType: String?): List<String> {
        return vaftController.playerTypesForVaft(currentPlayerType)
    }

    fun onCleanVaftPlaylist() {
        vaftController.onCleanPlaylist()
    }

    fun resetVaftController() {
        vaftController.reset()
    }

    suspend fun loadCleanStreamPlaylistUrl(
        channelLogin: String,
        playerTypes: List<String>,
        requireVerifiedClean: Boolean = false,
    ): PlayerRepository.StreamPlaylistCandidate? {
        val preferences = applicationContext.prefs()
        return playerRepository.loadCleanStreamPlaylistUrl(
            context = applicationContext,
            networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                applicationContext,
                preferences.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
            ),
            channelLogin = channelLogin,
            randomDeviceId = preferences.getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
            xDeviceId = preferences.getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
            playerTypes = playerTypes,
            supportedCodecs = preferences.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
            proxyPlaybackAccessToken = preferences.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
            proxyHost = preferences.httpProxyHost(),
            proxyPort = preferences.httpProxyPort(),
            proxyUser = preferences.getString(C.PROXY_USER, null),
            proxyPassword = preferences.getString(C.PROXY_PASSWORD, null),
            requireVerifiedClean = requireVerifiedClean,
        )
    }

    fun loadStreamResult(networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, randomDeviceId: Boolean?, xDeviceId: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?) {
        if (streamResult.value == null) {
                viewModelScope.launch {
                    try {
                        val warmUrl = streamPreloadCoordinator.resolveForPlayback(channelLogin)
                        streamUrlWarm.value = warmUrl != null
                        streamResult.value = warmUrl
                            ?: playerRepository.loadStreamPlaylistUrl(applicationContext, networkLibrary, gqlHeaders, channelLogin, randomDeviceId, xDeviceId, playerType, supportedCodecs, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword)
                        streamUrlAvailableElapsedMs = SystemClock.elapsedRealtime()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun loadStreamInfo(channelId: String?, channelLogin: String?, viewerCount: Int?, loop: Boolean, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, refreshForLiveRewind: Boolean = false) {
        if (loop || refreshForLiveRewind) {
            streamJob?.cancel()
            streamJob = viewModelScope.launch {
                while (isActive) {
                    try {
                        updateStreamInfoAndMarkKnown(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders)
                        delay(if (refreshForLiveRewind) 45.seconds else 5.minutes)
                    } catch (e: Exception) {
                        delay(1.minutes)
                    }
                }
            }
        } else {
            if (viewerCount == null) {
                viewModelScope.launch {
                    try {
                    updateStreamInfoAndMarkKnown(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun updateStreamInfoAndMarkKnown(
        channelId: String?,
        channelLogin: String?,
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
        gqlHeaders: Map<String, String>,
    ) {
        updateStreamInfo(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders)
    }

    internal suspend fun refreshLiveStatusNow(
        channelId: String?,
        channelLogin: String?,
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
        gqlHeaders: Map<String, String>,
    ): FreshLiveStatus = updateStreamInfo(
        channelId,
        channelLogin,
        networkLibrary,
        helixHeaders,
        gqlHeaders,
    )

    private suspend fun updateStreamInfo(
        channelId: String?,
        channelLogin: String?,
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
        gqlHeaders: Map<String, String>,
    ): FreshLiveStatus {
        val requestGeneration = streamStatusRequestGeneration.incrementAndGet()
        val status = queryFreshLiveStatus(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders)
        if (requestGeneration == streamStatusRequestGeneration.get()) {
            when (status) {
                is FreshLiveStatus.Live -> {
                    stream.value = status.stream
                    streamStatusKnown.value = true
                }
                FreshLiveStatus.Offline -> {
                    stream.value = null
                    streamStatusKnown.value = true
                }
                FreshLiveStatus.Unknown -> Unit
            }
        }
        return status
    }

    private suspend fun queryFreshLiveStatus(
        channelId: String?,
        channelLogin: String?,
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
        gqlHeaders: Map<String, String>,
    ): FreshLiveStatus {
        if (channelId.isNullOrBlank() && channelLogin.isNullOrBlank()) {
            return FreshLiveStatus.Unknown
        }
        try {
            val response = graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null,
            )
            if (response.errors.isNullOrEmpty()) {
                val user = response.data?.users?.firstOrNull()
                val liveStream = user?.stream
                if (user != null) {
                    if (liveStream == null) return FreshLiveStatus.Offline
                    return FreshLiveStatus.Live(
                        Stream(
                            id = liveStream.id,
                            channelId = user.id,
                            channelLogin = user.login,
                            channelName = user.displayName,
                            channelImageURL = user.profileImageURL,
                            gameId = liveStream.game?.id,
                            gameSlug = liveStream.game?.slug,
                            gameName = liveStream.game?.displayName,
                            title = liveStream.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = liveStream.previewImageURL,
                            createdAt = liveStream.createdAt?.toString(),
                            viewerCount = liveStream.viewersCount,
                            tags = liveStream.freeformTags?.mapNotNull { tag -> tag.name },
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Try the existing Helix and lightweight GraphQL fallbacks.
        }

        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                val response = helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                )
                val liveStream = response.data.firstOrNull()
                if (liveStream == null) return FreshLiveStatus.Offline
                return FreshLiveStatus.Live(
                    Stream(
                        id = liveStream.id,
                        channelId = liveStream.channelId,
                        channelLogin = liveStream.channelLogin,
                        channelName = liveStream.channelName,
                        gameId = liveStream.gameId,
                        gameName = liveStream.gameName,
                        title = liveStream.title,
                        thumbnailURL = liveStream.thumbnailURL,
                        createdAt = liveStream.startedAt,
                        viewerCount = liveStream.viewerCount,
                        tags = liveStream.tags,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the lightweight GraphQL fallback below.
            }
        }

        try {
            val response = graphQLRepository.loadViewerCount(networkLibrary, gqlHeaders, channelLogin)
            if (response.errors.isNullOrEmpty()) {
                val user = response.data?.user ?: return FreshLiveStatus.Unknown
                val liveStream = user.stream ?: return FreshLiveStatus.Offline
                return FreshLiveStatus.Live(
                    Stream(
                        id = liveStream.id,
                        viewerCount = liveStream.viewersCount,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No authoritative live status was available.
        }
        return FreshLiveStatus.Unknown
    }

    suspend fun findCurrentRecordingVod(
        channelId: String?,
        channelLogin: String?,
        streamCreatedAt: String?,
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
    ): LiveRewindVod? = graphQLRepository.findCurrentRecordingVod(
        networkLibrary = networkLibrary,
        headers = gqlHeaders,
        channelId = channelId,
        channelLogin = channelLogin,
        streamCreatedAt = streamCreatedAt,
    )

    suspend fun loadFreshStreamPlaylistUrl(channelLogin: String): String? =
        playerRepository.loadStreamPlaylistUrl(
            context = applicationContext,
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                applicationContext,
                applicationContext.prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
            ),
            channelLogin = channelLogin,
            randomDeviceId = applicationContext.prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
            xDeviceId = applicationContext.prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
            playerType = applicationContext.prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
            supportedCodecs = applicationContext.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
            proxyPlaybackAccessToken = applicationContext.prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
            proxyHost = applicationContext.prefs().httpProxyHost(),
            proxyPort = applicationContext.prefs().httpProxyPort(),
            proxyUser = applicationContext.prefs().getString(C.PROXY_USER, null),
            proxyPassword = applicationContext.prefs().getString(C.PROXY_PASSWORD, null),
        )

    suspend fun loadRewindVideoPlaylistUrl(videoId: String): String? =
        playerRepository.loadVideoPlaylistUrl(
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                applicationContext,
                applicationContext.prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true),
            ),
            videoId = videoId,
            playerType = applicationContext.prefs().getString(C.TOKEN_PLAYER_TYPE_VIDEO, "channel_home_live"),
            supportedCodecs = applicationContext.prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
        ).first

    fun loadVideo(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, playerType: String?, supportedCodecs: String?) {
        if (videoResult.value == null) {
            viewModelScope.launch {
                try {
                    val result = playerRepository.loadVideoPlaylistUrl(networkLibrary, gqlHeaders, videoId, playerType, supportedCodecs)
                    videoResult.value = result.first
                    backupQualities = result.second
                } catch (e: Exception) {
                }
            }
        }
    }

    fun getVideoPosition(id: Long, fallbackPosition: Long? = null) {
        viewModelScope.launch {
            savedPosition.value = playerRepository.getVideoPosition(id)?.position
                ?.takeIf { it > 0L }
                ?: fallbackPosition
                ?: 0L
        }
    }

    fun saveVideoPosition(id: Long, position: Long) {
        if (loaded.value) {
            playbackPersistence.saveVideoPosition(VideoPosition(id, position))
            playbackPersistence.saveVideoHistoryPosition(id, position)
        }
    }

    suspend fun savePosition(id: Long, position: Long) {
        playbackPersistence.saveVideoPositionAndWait(VideoPosition(id, position))
    }

    fun loadGamesList(videoId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (gamesList.value == null) {
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryVideoMoments(networkLibrary, gqlHeaders, videoId)
                    gamesList.value = response.data!!.video!!.moments!!.edges!!.map { item ->
                        item.node!!.let {
                            Game(
                                id = it.details?.onGameChangeMomentDetails?.game?.id,
                                name = it.details?.onGameChangeMomentDetails?.game?.displayName,
                                boxArtURL = it.details?.onGameChangeMomentDetails?.game?.boxArtURL,
                                vodPosition = it.positionMilliseconds,
                                vodDuration = it.durationMilliseconds,
                            )
                        }
                    }
                } catch (e: Exception) {
                    try {
                        val response = graphQLRepository.loadVideoGames(networkLibrary, gqlHeaders, videoId)
                        gamesList.value = response.data!!.video.moments.edges.map { item ->
                            item.node.let {
                                Game(
                                    id = it.details?.game?.id,
                                    name = it.details?.game?.displayName,
                                    boxArtURL = it.details?.game?.boxArtURL,
                                    vodPosition = it.positionMilliseconds,
                                    vodDuration = it.durationMilliseconds,
                                )
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    fun checkBookmark(id: String) {
        viewModelScope.launch {
            isBookmarked.value = bookmarksRepository.getByVideoId(id) != null
        }
    }

    fun saveBookmark(filesDir: String, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, videoId: String?, title: String?, uploadDate: String?, durationSeconds: Int?, type: String?, animatedPreviewUrl: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        viewModelScope.launch {
            val item = videoId?.let { bookmarksRepository.getByVideoId(it) }
            if (item != null) {
                bookmarksRepository.delete(item)
            } else {
                val downloadedThumbnail = videoId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "thumbnails").mkdir()
                        val path = filesDir + File.separator + "thumbnails" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val downloadedLogo = channelId.takeIf { !it.isNullOrBlank() }?.let { id ->
                    channelImage.takeIf { !it.isNullOrBlank() }?.let { url ->
                        File(filesDir, "profile_pics").mkdir()
                        val path = filesDir + File.separator + "profile_pics" + File.separator + id
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(path).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(path).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        path
                    }
                }
                val userTypes = channelId?.let {
                    try {
                        val response = graphQLRepository.loadQueryUsersType(networkLibrary, gqlHeaders, listOf(channelId))
                        response.data!!.users?.firstOrNull()?.let {
                            User(
                                id = it.id,
                                broadcasterType = when {
                                    it.roles?.isPartner == true -> "partner"
                                    it.roles?.isAffiliate == true -> "affiliate"
                                    else -> null
                                },
                                type = when {
                                    it.roles?.isStaff == true -> "staff"
                                    else -> null
                                },
                            )
                        }
                    } catch (e: Exception) {
                        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                            try {
                                helixRepository.getUsers(
                                    networkLibrary = networkLibrary,
                                    headers = helixHeaders,
                                    ids = listOf(channelId)
                                ).data.firstOrNull()?.let {
                                    User(
                                        id = it.id,
                                        login = it.login,
                                        name = it.displayName,
                                        profileImageURL = it.profileImageURL,
                                        type = it.type,
                                        broadcasterType = it.broadcasterType,
                                        createdAt = it.createdAt,
                                    )
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }
                }
                bookmarksRepository.save(
                    Bookmark(
                        videoId = videoId,
                        userId = channelId,
                        userLogin = channelLogin,
                        userName = channelName,
                        userType = userTypes?.type,
                        userBroadcasterType = userTypes?.broadcasterType,
                        userLogo = downloadedLogo,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = title,
                        createdAt = uploadDate,
                        thumbnail = downloadedThumbnail,
                        type = type,
                        duration = durationSeconds.toString(),
                        animatedPreviewURL = animatedPreviewUrl
                    )
                )
            }
        }
    }

    fun loadClip(networkLibrary: String?, gqlHeaders: Map<String, String>, id: String?) {
        if (clipUrls.value == null) {
            viewModelScope.launch {
                try {
                    clipUrls.value = playerRepository.loadClipQualities(networkLibrary, gqlHeaders, id) ?: emptyList()
                } catch (e: Exception) {
                    clipUrls.value = emptyList()
                }
            }
        }
    }

    fun getOfflineVideoPosition(id: Int, fallbackPosition: Long? = null) {
        viewModelScope.launch {
            savedOfflineVideoPosition.value = offlineVideosRepository.getById(id)?.lastWatchPosition
                ?.takeIf { it > 0L }
                ?: fallbackPosition
                ?: 0L
        }
    }

    fun saveOfflineVideoPosition(id: Int, position: Long) {
        if (loaded.value) {
            playbackPersistence.saveOfflineVideoPosition(id, position)
        }
    }

    fun isFollowingChannel(userId: String?, channelId: String?, channelLogin: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        if (_isFollowing.value == null) {
            viewModelScope.launch {
                try {
                    if (!channelId.isNullOrBlank()) {
                        _isFollowing.value = if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                            graphQLRepository.loadQueryFollowingUser(
                                networkLibrary = networkLibrary,
                                headers = gqlHeaders,
                                id = channelId,
                                login = channelLogin.takeIf { channelId.isBlank() },
                            ).data?.user?.self?.follower?.followedAt != null
                        } else {
                            localChannelFollowsRepository.getById(channelId) != null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MissingAuthenticationException) {
                    _authenticationRequired.trySend(Unit)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun saveFollowChannel(userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int, liveNotificationsEnabled: Boolean, disableNotifications: Boolean, startedAt: String?, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = graphQLRepository.loadFollowUser(networkLibrary, gqlHeaders, channelId, disableNotifications).also { response ->
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(true, errorMessage)
                        } else {
                            _isFollowing.value = true
                            follow.value = Pair(true, null)
                            if (!disableNotifications) {
                                saveNotificationUser(channelId)
                            } else {
                                deleteNotificationUser(channelId)
                            }
                            if (liveNotificationsEnabled) {
                                startedAt.takeUnless { it.isNullOrBlank() }?.let {
                                    Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
                                }?.let {
                                    notificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                                }
                            }
                            streamFeedRefreshCoordinator.invalidateFollowedFeeds()
                        }
                    } else {
                        localChannelFollowsRepository.save(LocalChannelFollow(channelId, channelLogin, channelName))
                        _isFollowing.value = true
                        follow.value = Pair(true, null)
                        if (!disableNotifications) {
                            saveNotificationUser(channelId)
                        }
                        if (liveNotificationsEnabled) {
                            startedAt.takeUnless { it.isNullOrBlank() }?.let {
                                Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }
                            }?.let {
                                notificationsRepository.saveList(listOf(ShownNotification(channelId, it)))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingAuthenticationException) {
                _authenticationRequired.trySend(Unit)
            } catch (e: Exception) {
            }
        }
    }

    fun deleteFollowChannel(userId: String?, channelId: String?, setting: Int, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        viewModelScope.launch {
            try {
                if (!channelId.isNullOrBlank()) {
                    if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && userId != channelId) {
                        val errorMessage = graphQLRepository.loadUnfollowUser(networkLibrary, gqlHeaders, channelId).also { response ->
                        }.errors?.firstOrNull()?.message
                        if (!errorMessage.isNullOrBlank()) {
                            follow.value = Pair(false, errorMessage)
                        } else {
                            _isFollowing.value = false
                            follow.value = Pair(false, null)
                            deleteNotificationUser(channelId)
                            streamFeedRefreshCoordinator.invalidateFollowedFeeds()
                        }
                    } else {
                        localChannelFollowsRepository.getById(channelId)?.let { localChannelFollowsRepository.delete(it) }
                        _isFollowing.value = false
                        follow.value = Pair(false, null)
                        deleteNotificationUser(channelId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingAuthenticationException) {
                _authenticationRequired.trySend(Unit)
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun saveNotificationUser(channelId: String) {
        notificationsRepository.saveUser(NotificationUser(channelId))
        LiveNotificationScheduler.requestImmediateReconciliation(
            applicationContext,
            reason = "notification_users_changed",
        )
    }

    private suspend fun deleteNotificationUser(channelId: String) {
        notificationsRepository.deleteUser(NotificationUser(channelId))
        LiveNotificationScheduler.requestImmediateReconciliation(
            applicationContext,
            reason = "notification_users_changed",
        )
    }

    companion object {
        val Media3PlayerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                Media3PlayerViewModel(application.applicationContext, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.streamPreloadCoordinator, xtraModule.bookmarksRepository, xtraModule.offlineVideosRepository, xtraModule.localChannelFollowsRepository, xtraModule.notificationsRepository, xtraModule.streamFeedRefreshCoordinator, xtraModule.playbackPersistence)
            }
        }
    }
}
