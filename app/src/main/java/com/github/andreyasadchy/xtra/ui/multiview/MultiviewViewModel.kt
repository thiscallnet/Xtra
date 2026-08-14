package com.github.andreyasadchy.xtra.ui.multiview

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackCoordinator
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.httpProxyHost
import com.github.andreyasadchy.xtra.util.httpProxyPort
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MultiviewViewModel(
    private val applicationContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<MultiviewSessionState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<Map<String, MultiviewPlaybackSnapshot>>(emptyMap())
    val playback: StateFlow<Map<String, MultiviewPlaybackSnapshot>> = _playback.asStateFlow()

    val playbackCoordinator = MultiviewPlaybackCoordinator(
        context = applicationContext,
        loadPlaylist = ::loadStreamPlaylist,
        loadCleanPlaylist = ::loadCleanStreamPlaylist,
        onSnapshot = ::updatePlaybackSnapshot,
    )

    fun initialize(initialStream: Stream?) {
        if (_state.value.streams.isEmpty() && initialStream != null) {
            update(MultiviewSessionReducer.add(_state.value, initialStream))
        }
    }

    fun addStreams(streams: Collection<Stream>) {
        val next = streams.fold(_state.value) { state, stream ->
            MultiviewSessionReducer.add(state, stream)
        }
        update(next)
    }

    fun remove(identity: String) {
        val current = _state.value
        val wasFocused = current.focusedIdentity.equals(identity, true)
        val next = MultiviewSessionReducer.remove(current, identity)
        update(
            if (wasFocused) {
                next.copy(layoutMode = next.layoutBeforeFocus ?: next.layoutMode, layoutBeforeFocus = null)
            } else {
                next
            },
        )
        _playback.value = _playback.value - identity
    }

    fun reorder(identity: String, targetIndex: Int) {
        update(MultiviewSessionReducer.reorder(_state.value, identity, targetIndex))
    }

    fun setActive(identity: String) {
        update(MultiviewSessionReducer.setActive(_state.value, identity))
    }

    fun setFocus(identity: String?) {
        val current = _state.value
        if (identity == null) {
            update(
                current.copy(
                    focusedIdentity = null,
                    layoutMode = current.layoutBeforeFocus ?: current.layoutMode,
                    layoutBeforeFocus = null,
                ),
            )
        } else if (current.identities.any { it.equals(identity, true) }) {
            update(
                current.copy(
                    focusedIdentity = identity,
                    layoutBeforeFocus = current.layoutBeforeFocus ?: current.layoutMode.takeUnless { it == MultiviewLayoutMode.FOCUS },
                    layoutMode = MultiviewLayoutMode.FOCUS,
                ),
            )
        }
    }

    fun setLayoutMode(mode: MultiviewLayoutMode) {
        if (mode == MultiviewLayoutMode.FOCUS) {
            update(_state.value.copy(layoutMode = mode))
        } else {
            update(_state.value.copy(layoutMode = mode, focusedIdentity = null, layoutBeforeFocus = null))
        }
    }

    fun setFillVideo(fill: Boolean) {
        applicationContext.prefs().edit().putBoolean(C.MULTIVIEW_FILL, fill).apply()
        update(_state.value.copy(fillVideo = fill))
    }

    fun setChat(visible: Boolean, combined: Boolean = _state.value.combinedChat, identity: String? = _state.value.chatIdentity) {
        update(_state.value.copy(chatVisible = visible, combinedChat = combined, chatIdentity = identity))
    }

    fun setQualityMode(mode: MultiviewQualityMode) {
        applicationContext.prefs().edit().putString(C.MULTIVIEW_QUALITY_MODE, mode.name).apply()
        update(_state.value.copy(qualityMode = mode))
    }

    fun setQualityOverride(identity: String, label: String?) {
        val overrides = _state.value.qualityOverrides.toMutableMap()
        // The UI already maps its localized Smart label to null. Keeping this
        // API language-neutral avoids treating a translated label as a real
        // manual quality override.
        if (label.isNullOrBlank()) {
            overrides.remove(identity)
        } else {
            overrides[identity] = label
        }
        update(_state.value.copy(qualityOverrides = overrides))
    }

    fun onStart() = playbackCoordinator.onStart()

    fun onStop() = playbackCoordinator.onStop()

    suspend fun resolveLiveStream(channelLogin: String): Stream? {
        val preferences = applicationContext.prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)

        try {
            graphQLRepository.loadQueryUsersStream(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                logins = listOf(channelLogin),
            ).data?.users?.firstOrNull()?.let { user ->
                user.stream?.let { stream ->
                    return Stream(
                        id = stream.id,
                        channelId = user.id,
                        channelLogin = user.login,
                        channelName = user.displayName,
                        channelImageURL = user.profileImageURL,
                        gameId = stream.game?.id,
                        gameSlug = stream.game?.slug,
                        gameName = stream.game?.displayName,
                        title = stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = stream.previewImageURL,
                        createdAt = stream.createdAt?.toString(),
                        viewerCount = stream.viewersCount,
                        tags = stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            }
        } catch (_: Exception) {
            // Helix below is a useful fallback when the persisted GraphQL query is unavailable.
        }

        return runCatching {
            helixRepository.getStreams(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                logins = listOf(channelLogin),
            ).data.firstOrNull()?.let { stream ->
                Stream(
                    id = stream.id,
                    channelId = stream.channelId,
                    channelLogin = stream.channelLogin,
                    channelName = stream.channelName,
                    gameId = stream.gameId,
                    gameName = stream.gameName,
                    title = stream.title,
                    thumbnailURL = stream.thumbnailURL,
                    createdAt = stream.startedAt,
                    viewerCount = stream.viewerCount,
                    tags = stream.tags,
                )
            }
        }.getOrNull()
    }

    suspend fun loadStreamPlaylist(
        channelLogin: String,
        bypassHttpProxy: Boolean = false,
    ): String {
        val preferences = applicationContext.prefs()
        return playerRepository.loadStreamPlaylistUrl(
            context = applicationContext,
            networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(
                applicationContext,
                preferences.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true),
            ),
            channelLogin = channelLogin,
            randomDeviceId = preferences.getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
            xDeviceId = preferences.getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
            playerType = preferences.getString(C.TOKEN_PLAYER_TYPE, "site"),
            supportedCodecs = preferences.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
            proxyPlaybackAccessToken = !bypassHttpProxy && preferences.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
            proxyHost = preferences.httpProxyHost().takeUnless { bypassHttpProxy },
            proxyPort = preferences.httpProxyPort().takeUnless { bypassHttpProxy },
            proxyUser = preferences.getString(C.PROXY_USER, null),
            proxyPassword = preferences.getString(C.PROXY_PASSWORD, null),
            enableIntegrity = preferences.getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    private suspend fun loadCleanStreamPlaylist(
        channelLogin: String,
        playerTypes: List<String>,
        requireVerifiedClean: Boolean,
        bypassHttpProxy: Boolean,
    ): PlayerRepository.StreamPlaylistCandidate? {
        val preferences = applicationContext.prefs()
        return try {
            playerRepository.loadCleanStreamPlaylistUrl(
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
                proxyPlaybackAccessToken = !bypassHttpProxy && preferences.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                proxyHost = preferences.httpProxyHost().takeUnless { bypassHttpProxy },
                proxyPort = preferences.httpProxyPort().takeUnless { bypassHttpProxy },
                proxyUser = preferences.getString(C.PROXY_USER, null),
                proxyPassword = preferences.getString(C.PROXY_PASSWORD, null),
                enableIntegrity = preferences.getBoolean(C.ENABLE_INTEGRITY, false),
                requireVerifiedClean = requireVerifiedClean,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        playbackCoordinator.releaseAll()
        super.onCleared()
    }

    private fun updatePlaybackSnapshot(identity: String, snapshot: MultiviewPlaybackSnapshot) {
        _playback.value = _playback.value + (identity to snapshot)
    }

    private fun update(next: MultiviewSessionState) {
        _state.value = next
        savedStateHandle[KEY_STREAMS] = ArrayList(next.streams)
        savedStateHandle[KEY_ACTIVE] = next.activeIdentity
        savedStateHandle[KEY_FOCUSED] = next.focusedIdentity
        savedStateHandle[KEY_LAYOUT] = next.layoutMode.name
        savedStateHandle[KEY_LAYOUT_BEFORE_FOCUS] = next.layoutBeforeFocus?.name
        savedStateHandle[KEY_FILL] = next.fillVideo
        savedStateHandle[KEY_CHAT_VISIBLE] = next.chatVisible
        savedStateHandle[KEY_COMBINED_CHAT] = next.combinedChat
        savedStateHandle[KEY_CHAT_IDENTITY] = next.chatIdentity
        savedStateHandle[KEY_QUALITY_MODE] = next.qualityMode.name
        savedStateHandle[KEY_OVERRIDES] = HashMap(next.qualityOverrides)
    }

    private fun loadState(): MultiviewSessionState {
        val preferences = applicationContext.prefs()
        val streams = savedStateHandle.get<ArrayList<Stream>>(KEY_STREAMS)?.toList().orEmpty()
        val fillVideo = savedStateHandle.get<Boolean>(KEY_FILL) ?: preferences.getBoolean(C.MULTIVIEW_FILL, false)
        val qualityMode = savedStateHandle.get<String>(KEY_QUALITY_MODE)
            ?.let(MultiviewQualityMode::fromPersistedName)
            ?: preferences.getString(C.MULTIVIEW_QUALITY_MODE, MultiviewQualityMode.AUTO.name)
                ?.let(MultiviewQualityMode::fromPersistedName)
            ?: MultiviewQualityMode.AUTO
        return MultiviewSessionState(
            streams = streams,
            activeIdentity = savedStateHandle[KEY_ACTIVE] ?: streams.firstOrNull()?.let(MultiviewSessionReducer::stableIdentity),
            focusedIdentity = savedStateHandle[KEY_FOCUSED],
            layoutMode = savedStateHandle.get<String>(KEY_LAYOUT)
                ?.let { runCatching { MultiviewLayoutMode.valueOf(it) }.getOrNull() }
                ?: MultiviewLayoutMode.AUTO,
            layoutBeforeFocus = savedStateHandle.get<String>(KEY_LAYOUT_BEFORE_FOCUS)
                ?.let { runCatching { MultiviewLayoutMode.valueOf(it) }.getOrNull() },
            fillVideo = fillVideo,
            chatVisible = savedStateHandle[KEY_CHAT_VISIBLE] ?: false,
            combinedChat = savedStateHandle[KEY_COMBINED_CHAT] ?: false,
            chatIdentity = savedStateHandle[KEY_CHAT_IDENTITY],
            qualityMode = qualityMode,
            qualityOverrides = (savedStateHandle.get<HashMap<String, String>>(KEY_OVERRIDES) ?: hashMapOf()).toMap(),
        )
    }

    companion object {
        private const val KEY_STREAMS = "multiview_state_streams"
        private const val KEY_ACTIVE = "multiview_state_active"
        private const val KEY_FOCUSED = "multiview_state_focused"
        private const val KEY_LAYOUT = "multiview_state_layout"
        private const val KEY_LAYOUT_BEFORE_FOCUS = "multiview_state_layout_before_focus"
        private const val KEY_FILL = "multiview_state_fill"
        private const val KEY_CHAT_VISIBLE = "multiview_state_chat_visible"
        private const val KEY_COMBINED_CHAT = "multiview_state_combined_chat"
        private const val KEY_CHAT_IDENTITY = "multiview_state_chat_identity"
        private const val KEY_QUALITY_MODE = "multiview_state_quality_mode"
        private const val KEY_OVERRIDES = "multiview_state_quality_overrides"

        val MultiviewViewModelFactory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as XtraApp
                val xtraModule = application.xtraModule
                MultiviewViewModel(
                    applicationContext = application.applicationContext,
                    savedStateHandle = createSavedStateHandle(),
                    graphQLRepository = xtraModule.graphQLRepository,
                    helixRepository = xtraModule.helixRepository,
                    playerRepository = xtraModule.playerRepository,
                )
            }
        }
    }
}
