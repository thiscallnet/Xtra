package com.github.andreyasadchy.xtra.ui.multiview

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.Raid
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.net.ssl.X509TrustManager

class MultiviewViewModel(
    private val applicationContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val playerRepository: PlayerRepository,
    private val trustManager: Lazy<X509TrustManager>,
) : ViewModel() {
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<MultiviewSessionState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<Map<String, MultiviewPlaybackSnapshot>>(emptyMap())
    val playback: StateFlow<Map<String, MultiviewPlaybackSnapshot>> = _playback.asStateFlow()
    private var raidMonitoringJob: Job? = null
    private var raidMonitor: MultiviewRaidMonitor? = null
    private var raidMonitoringGeneration: Any? = null
    private var raidPreferenceListenerRegistered = false
    private val raidPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == C.MULTIVIEW_RAIDS || key == C.CHAT_RAIDS_SHOW) {
            viewModelScope.launch { syncRaidMonitoring() }
        }
    }

    val playbackCoordinator = MultiviewPlaybackCoordinator(
        context = applicationContext,
        loadPlaylist = ::loadStreamPlaylist,
        loadCleanPlaylist = ::loadCleanStreamPlaylist,
        onSnapshot = ::updatePlaybackSnapshot,
    )

    fun initialize(initialStream: Stream?) {
        val current = _state.value
        val next = initializeStateOnce(
            savedStateHandle = savedStateHandle,
            state = current,
            initialStream = initialStream,
            initialAudioVolume = { if (current.streams.isEmpty()) defaultAudioVolume() else 0f },
        ) ?: return
        if (next != current) update(next)
    }

    fun startRaidMonitoring() {
        if (raidMonitoringJob != null) return
        applicationContext.prefs().registerOnSharedPreferenceChangeListener(raidPreferenceListener)
        raidPreferenceListenerRegistered = true
        raidMonitoringJob = viewModelScope.launch {
            state.collect { syncRaidMonitoring() }
        }
    }

    fun stopRaidMonitoring() {
        if (raidPreferenceListenerRegistered) {
            applicationContext.prefs().unregisterOnSharedPreferenceChangeListener(raidPreferenceListener)
            raidPreferenceListenerRegistered = false
        }
        raidMonitoringGeneration = null
        raidMonitoringJob?.cancel()
        raidMonitoringJob = null
        raidMonitor?.close()
        raidMonitor = null
    }

    private fun syncRaidMonitoring() {
        if (!isRaidMonitoringEnabled()) {
            raidMonitoringGeneration = null
            raidMonitor?.close()
            raidMonitor = null
            return
        }

        val monitor = raidMonitor ?: run {
            val generation = Any()
            val created = MultiviewRaidMonitor.create(
                context = applicationContext,
                trustManager = trustManager,
                scope = viewModelScope,
                resolveChannelId = { stream ->
                    stream.channelLogin
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { resolveLiveStream(it)?.channelId }
                },
                onRaid = { identity, raid ->
                    if (raidMonitoringGeneration === generation) {
                        viewModelScope.launch { handleRaid(identity, raid) }
                    }
                },
            )
            raidMonitoringGeneration = generation
            raidMonitor = created
            created
        }
        monitor.sync(_state.value.streams)
    }

    private fun isRaidMonitoringEnabled(): Boolean {
        val preferences = applicationContext.prefs()
        return preferences.getBoolean(C.MULTIVIEW_RAIDS, true) &&
            preferences.getBoolean(C.CHAT_RAIDS_SHOW, true)
    }

    fun addStreams(streams: Collection<Stream>) {
        val next = streams.fold(_state.value) { state, stream ->
            MultiviewSessionReducer.add(
                state = state,
                stream = stream,
                initialAudioVolume = if (state.streams.isEmpty()) defaultAudioVolume() else 0f,
            )
        }
        update(next)
    }

    fun replaceStream(identity: String, stream: Stream) {
        val current = _state.value
        update(
            MultiviewSessionReducer.replace(
                state = current,
                identity = identity,
                stream = stream,
                initialAudioVolume = if (current.activeIdentity.equals(identity, true)) {
                    defaultAudioVolume()
                } else {
                    0f
                },
            ),
        )
        _playback.value = cleanupPlaybackSnapshots(_playback.value, identity, _state.value)
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
        _playback.value = cleanupPlaybackSnapshots(_playback.value, identity, _state.value)
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

    fun setAudioVolume(identity: String, volume: Float, persist: Boolean = true) {
        update(
            next = MultiviewSessionReducer.setAudioVolume(_state.value, identity, volume),
            persist = persist,
        )
    }

    fun toggleAudio(identity: String) {
        val current = _state.value.audioVolumes[identity] ?: 0f
        setAudioVolume(
            identity,
            if (current > 0f) 0f else defaultAudioVolume().takeIf { it > 0f } ?: 1f,
        )
    }

    fun audioVolume(identity: String): Float {
        return _state.value.audioVolumes[identity]
            ?: if (_state.value.activeIdentity.equals(identity, true)) defaultAudioVolume() else 0f
    }

    fun persistSession() {
        persistSession(_state.value)
    }

    fun onStart() = playbackCoordinator.onStart()

    fun onStop(allowBackground: Boolean = true, inPictureInPicture: Boolean = false) =
        playbackCoordinator.onStop(allowBackground, inPictureInPicture)

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
                requireVerifiedClean = requireVerifiedClean,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        stopRaidMonitoring()
        playbackCoordinator.releaseAll()
    }

    private fun handleRaid(sourceIdentity: String, raid: Raid) {
        val preferences = applicationContext.prefs()
        if (!raid.openStream ||
            !preferences.getBoolean(C.MULTIVIEW_RAIDS, true) ||
            !preferences.getBoolean(C.CHAT_RAIDS_SHOW, true)
        ) return
        if (raid.targetId.isNullOrBlank() && raid.targetLogin.isNullOrBlank()) return
        replaceStream(
            sourceIdentity,
            Stream(
                channelId = raid.targetId,
                channelLogin = raid.targetLogin,
                channelName = raid.targetName,
                channelImageURL = raid.targetImageURL,
            ),
        )
    }

    private fun updatePlaybackSnapshot(identity: String, snapshot: MultiviewPlaybackSnapshot) {
        _playback.value = _playback.value + (identity to snapshot)
    }

    private fun update(next: MultiviewSessionState, persist: Boolean = true) {
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
        savedStateHandle[KEY_AUDIO_VOLUMES] = HashMap(next.audioVolumes)
        if (persist) persistSession(next)
    }

    private fun persistSession(state: MultiviewSessionState) {
        applicationContext.prefs().edit().apply {
            if (state.streams.isEmpty()) {
                remove(C.MULTIVIEW_SESSION)
            } else {
                putString(C.MULTIVIEW_SESSION, MultiviewSessionStore.encode(state))
            }
        }.apply()
    }

    private fun loadState(): MultiviewSessionState {
        val preferences = applicationContext.prefs()
        val persisted = MultiviewSessionStore.decode(preferences.getString(C.MULTIVIEW_SESSION, null))
        val streams = savedStateHandle.get<ArrayList<Stream>>(KEY_STREAMS)?.toList().orEmpty()
        if (streams.isEmpty() && persisted != null) {
            return persisted.withAudioDefaults(preferences)
        }
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
            audioVolumes = (savedStateHandle.get<HashMap<String, Float>>(KEY_AUDIO_VOLUMES) ?: hashMapOf()).toMap(),
        ).withAudioDefaults(preferences)
    }

    fun defaultAudioVolume(): Float {
        return (applicationContext.prefs().getInt(C.PLAYER_VOLUME, 100) / 100f).coerceIn(0f, 1f)
    }

    private fun MultiviewSessionState.withAudioDefaults(
        preferences: android.content.SharedPreferences,
    ): MultiviewSessionState {
        val fallback = (preferences.getInt(C.PLAYER_VOLUME, 100) / 100f).coerceIn(0f, 1f)
        val volumes = audioVolumes.toMutableMap()
        streams.mapNotNull(MultiviewSessionReducer::stableIdentity).forEach { identity ->
            if (identity !in volumes) {
                volumes[identity] = if (identity.equals(activeIdentity, true)) fallback else 0f
            }
        }
        return copy(audioVolumes = volumes)
    }

    companion object {
        private const val KEY_INITIAL_STREAM_APPLIED = "multiview_initial_stream_applied"
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
        private const val KEY_AUDIO_VOLUMES = "multiview_state_audio_volumes"

        internal fun initializeStateOnce(
            savedStateHandle: SavedStateHandle,
            state: MultiviewSessionState,
            initialStream: Stream?,
            initialAudioVolume: () -> Float,
        ): MultiviewSessionState? {
            if (savedStateHandle.get<Boolean>(KEY_INITIAL_STREAM_APPLIED) == true) return null
            savedStateHandle[KEY_INITIAL_STREAM_APPLIED] = true
            if (initialStream == null) return state
            return MultiviewSessionReducer.addOrReplaceLast(
                state = state,
                stream = initialStream,
                initialAudioVolume = initialAudioVolume(),
            )
        }

        internal fun cleanupPlaybackSnapshots(
            playback: Map<String, MultiviewPlaybackSnapshot>,
            sourceIdentity: String,
            state: MultiviewSessionState,
        ): Map<String, MultiviewPlaybackSnapshot> {
            return if (state.identities.any { it.equals(sourceIdentity, true) }) {
                playback
            } else {
                playback.filterKeys { !it.equals(sourceIdentity, true) }
            }
        }

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
                    trustManager = xtraModule.trustManager,
                )
            }
        }
    }
}
