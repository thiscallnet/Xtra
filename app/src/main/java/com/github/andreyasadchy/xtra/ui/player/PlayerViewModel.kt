package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
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
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlayerViewModel(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val playbackPersistence: PlaybackPersistence,
    private val bookmarksRepository: BookmarksRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val notificationsRepository: NotificationsRepository,
    private val streamFeedRefreshCoordinator: StreamFeedRefreshCoordinator,
) : ViewModel() {

    val stream = MutableStateFlow<Stream?>(null)
    val streamStatusKnown = MutableStateFlow(false)
    private var streamUpdateJob: Job? = null
    val isBookmarked = MutableStateFlow<Boolean?>(null)
    val gamesList = MutableStateFlow<List<Game>?>(null)
    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing
    val follow = MutableStateFlow<Pair<Boolean, String?>?>(null)
    private val _authenticationRequired = Channel<Unit>(Channel.BUFFERED)
    val authenticationRequired = _authenticationRequired.receiveAsFlow()

    fun deletePlaybackStates() {
        playbackPersistence.deletePlaybackStates()
    }

    fun loadStreamInfo(channelId: String?, channelLogin: String?, viewerCount: Int?, loop: Boolean, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, refreshForLiveRewind: Boolean = false) {
        if (loop || refreshForLiveRewind) {
            if (streamUpdateJob?.isActive != true) {
                streamUpdateJob?.cancel()
                streamUpdateJob = viewModelScope.launch {
                    while (isActive) {
                        try {
                            updateStreamInfoAndMarkKnown(channelId, channelLogin, networkLibrary, helixHeaders, gqlHeaders)
                            delay(if (refreshForLiveRewind) 45.seconds else 5.minutes)
                        } catch (e: Exception) {
                            delay(1.minutes)
                        }
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
        streamStatusKnown.value = true
    }

    private suspend fun updateStreamInfo(channelId: String?, channelLogin: String?, networkLibrary: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>) {
        stream.value = try {
            val response = graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                ids = channelId?.let { listOf(it) },
                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null,
            )
            response.data!!.users?.firstOrNull()?.takeIf { it.stream != null }?.let {
                Stream(
                    id = it.stream?.id,
                    channelId = it.id,
                    channelLogin = it.login,
                    channelName = it.displayName,
                    channelImageURL = it.profileImageURL,
                    gameId = it.stream?.game?.id,
                    gameSlug = it.stream?.game?.slug,
                    gameName = it.stream?.game?.displayName,
                    title = it.stream?.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = it.stream?.previewImageURL,
                    createdAt = it.stream?.createdAt?.toString(),
                    viewerCount = it.stream?.viewersCount,
                    tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                )
            }
        } catch (e: Exception) {
            if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
            try {
                helixRepository.getStreams(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                ).data.firstOrNull()?.let {
                    Stream(
                        id = it.id,
                        channelId = it.channelId,
                        channelLogin = it.channelLogin,
                        channelName = it.channelName,
                        gameId = it.gameId,
                        gameName = it.gameName,
                        title = it.title,
                        thumbnailURL = it.thumbnailURL,
                        createdAt = it.startedAt,
                        viewerCount = it.viewerCount,
                        tags = it.tags,
                    )
                }
            } catch (e: Exception) {
                val response = graphQLRepository.loadViewerCount(networkLibrary, gqlHeaders, channelLogin)
                response.data!!.user.stream?.let {
                    Stream(
                        id = it.id,
                        viewerCount = it.viewersCount,
                    )
                }
            }
        }
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
            XtraApp.INSTANCE,
            reason = "notification_users_changed",
        )
    }

    private suspend fun deleteNotificationUser(channelId: String) {
        notificationsRepository.deleteUser(NotificationUser(channelId))
        LiveNotificationScheduler.requestImmediateReconciliation(
            XtraApp.INSTANCE,
            reason = "notification_users_changed",
        )
    }

    companion object {
        val PlayerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                PlayerViewModel(xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.graphQLRepository, xtraModule.helixRepository, xtraModule.playerRepository, xtraModule.playbackPersistence, xtraModule.bookmarksRepository, xtraModule.localChannelFollowsRepository, xtraModule.notificationsRepository, xtraModule.streamFeedRefreshCoordinator)
            }
        }
    }
}
