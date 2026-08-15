package com.github.andreyasadchy.xtra.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformation
import com.github.andreyasadchy.xtra.model.helix.chat.ChatSettings
import com.github.andreyasadchy.xtra.model.helix.game.Game
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUser
import com.github.andreyasadchy.xtra.model.helix.user.User
import com.github.andreyasadchy.xtra.repository.AccountCacheSnapshot
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class AccountCapabilities(
    val editBio: Boolean = false,
    val editChatColor: Boolean = false,
    val editChannel: Boolean = false,
    val editChatSettings: Boolean = false,
    val readBlockedUsers: Boolean = false,
    val manageBlockedUsers: Boolean = false,
) {
    companion object {
        fun from(scopes: Set<String>) = AccountCapabilities(
            editBio = "user:edit" in scopes,
            editChatColor = "user:manage:chat_color" in scopes,
            editChannel = "channel:manage:broadcast" in scopes,
            editChatSettings = "moderator:manage:chat_settings" in scopes,
            readBlockedUsers = "user:read:blocked_users" in scopes,
            manageBlockedUsers = "user:manage:blocked_users" in scopes,
        )
    }
}

data class AccountUiState(
    val loading: Boolean = true,
    val user: User? = null,
    val scopes: Set<String> = emptySet(),
    val capabilities: AccountCapabilities = AccountCapabilities(),
    val chatColor: String? = null,
    val chatColorLoadError: String? = null,
    val channel: ChannelInformation? = null,
    val channelLoadError: String? = null,
    val chatSettings: ChatSettings? = null,
    val chatSettingsLoadError: String? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val blockedUsersCursor: String? = null,
    val blockedUsersLoading: Boolean = false,
    val blockedUsersLoadError: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    val actionMessage: String? = null,
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val module = (application as XtraApp).xtraModule
    private val _uiState = MutableStateFlow(AccountUiState(user = cachedUser()))
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private val _categoryResults = MutableStateFlow<List<Game>>(emptyList())
    val categoryResults: StateFlow<List<Game>> = _categoryResults.asStateFlow()

    private var categorySearchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loadAccount()
        }
    }

    private suspend fun loadAccount() {
        val headers = TwitchApiHelper.getHelixHeaders(context)
        val token = headers[C.HEADER_TOKEN]
        if (token.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    user = null,
                    scopes = emptySet(),
                    capabilities = AccountCapabilities(),
                    chatColor = null,
                    chatColorLoadError = null,
                    channel = null,
                    channelLoadError = null,
                    chatSettings = null,
                    chatSettingsLoadError = null,
                    blockedUsers = emptyList(),
                    blockedUsersCursor = null,
                    blockedUsersLoading = false,
                    blockedUsersLoadError = null,
                    error = context.getString(R.string.account_reconnect_message),
                )
            }
            return
        }

        val tokenUserId = context.tokenPrefs().getString(C.USER_ID, null)
        val tokenLogin = context.tokenPrefs().getString(C.USERNAME, null)
        val cached = try {
            module.metadataCache.readAccount(tokenUserId, tokenLogin)
        } catch (_: Exception) {
            null
        }
        val cachedScopes = cached?.scopes.orEmpty()
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                user = cached?.user ?: cachedUser(),
                scopes = cachedScopes,
                capabilities = AccountCapabilities.from(cachedScopes),
                chatColor = cached?.chatColor,
                chatColorLoadError = null,
                channel = cached?.channel,
                channelLoadError = null,
                chatSettings = cached?.chatSettings,
                chatSettingsLoadError = null,
                blockedUsers = cached?.blockedUsers.orEmpty(),
                blockedUsersCursor = cached?.blockedUsersCursor,
                blockedUsersLoading = false,
                blockedUsersLoadError = null,
                actionError = null,
                actionMessage = null,
            )
        }

        try {
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val validation = try {
                module.authRepository.validate(networkLibrary, token).also { response ->
                    val expectedClientId = context.prefs().getString(C.HELIX_CLIENT_ID, C.DEFAULT_HELIX_CLIENT_ID)
                    check(response.clientId == expectedClientId) { "The Twitch Helix token belongs to another client" }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        scopes = emptySet(),
                        capabilities = AccountCapabilities(),
                        blockedUsersLoading = false,
                        blockedUsersLoadError = null,
                        error = readableError(error),
                    )
                }
                return
            }

            val userId = validation.userId ?: context.tokenPrefs().getString(C.USER_ID, null)
            val login = validation.login ?: context.tokenPrefs().getString(C.USERNAME, null)
            val scopes = validation.scopes.toSet()
            val capabilities = AccountCapabilities.from(scopes)
            _uiState.update {
                it.copy(scopes = scopes, capabilities = capabilities)
            }
            val user = module.helixRepository.getUsers(
                networkLibrary = networkLibrary,
                headers = headers,
                ids = userId?.let { listOf(it) },
                logins = if (userId.isNullOrBlank()) login?.let { listOf(it) } else null,
            ).data.firstOrNull() ?: cached?.user ?: cachedUser()
            val resolvedUserId = userId ?: user?.id

            supervisorScope {
                val color = if (capabilities.editChatColor && resolvedUserId != null) {
                    async {
                        runCatching {
                            module.helixRepository.getChatColor(networkLibrary, headers, resolvedUserId)
                        }
                    }
                } else null
                val channel = if (capabilities.editChannel && resolvedUserId != null) {
                    async {
                        runCatching {
                            module.helixRepository.getChannelInformation(networkLibrary, headers, resolvedUserId)
                        }
                    }
                } else null
                val chatSettings = if (capabilities.editChatSettings && resolvedUserId != null) {
                    async {
                        runCatching {
                            module.helixRepository.getChatSettings(networkLibrary, headers, resolvedUserId, resolvedUserId)
                        }
                    }
                } else null

                val colorResult = color?.await()
                val channelResult = channel?.await()
                val chatSettingsResult = chatSettings?.await()
                val loadedColor = colorResult?.getOrNull()?.takeIf(::isCanonicalChatColor) ?: cached?.chatColor
                val loadedChannel = channelResult?.getOrNull() ?: cached?.channel
                val loadedChatSettings = chatSettingsResult?.getOrNull() ?: cached?.chatSettings
                _uiState.update {
                    it.copy(
                        loading = false,
                        user = user,
                        scopes = scopes,
                        capabilities = capabilities,
                        chatColor = loadedColor,
                        chatColorLoadError = colorResult?.let {
                            if (it.isFailure && loadedColor == null) context.getString(R.string.account_load_failed) else null
                        },
                        channel = loadedChannel,
                        channelLoadError = channelResult?.let {
                            if ((it.isFailure || loadedChannel == null) && cached?.channel == null) context.getString(R.string.account_load_failed) else null
                        },
                        chatSettings = loadedChatSettings,
                        chatSettingsLoadError = chatSettingsResult?.let {
                            if ((it.isFailure || loadedChatSettings == null) && cached?.chatSettings == null) context.getString(R.string.account_load_failed) else null
                        },
                        error = null,
                    )
                }
                persistAccountCache(resolvedUserId, login)
            }
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    loading = false,
                    error = readableError(error),
                    chatColorLoadError = if (it.capabilities.editChatColor) context.getString(R.string.account_load_failed) else null,
                    channelLoadError = if (it.capabilities.editChannel) context.getString(R.string.account_load_failed) else null,
                    chatSettingsLoadError = if (it.capabilities.editChatSettings) context.getString(R.string.account_load_failed) else null,
                )
            }
        }
    }

    fun updateChatColor(color: String) {
        mutate(R.string.account_saved) {
            requireCapability { it.editChatColor }
            val error = module.helixRepository.updateChatColor(
                networkLibrary(),
                helixHeaders(),
                currentUserId(),
                color,
            )
            error?.takeIf { it.isNotBlank() }?.let { throw TwitchApiException(400, null, message = it) }
            val canonicalColor = runCatching {
                module.helixRepository.getChatColor(networkLibrary(), helixHeaders(), currentUserId())
            }
            _uiState.update { state ->
                state.copy(
                    chatColor = canonicalColor.getOrNull()?.takeIf(::isCanonicalChatColor),
                    chatColorLoadError = canonicalColor.exceptionOrNull()?.let { context.getString(R.string.account_load_failed) },
                )
            }
            persistAccountCache()
        }
    }

    fun updateBio(description: String) {
        mutate(R.string.account_saved) {
            requireCapability { it.editBio }
            require(description.length <= 300) { context.getString(R.string.account_bio_too_long) }
            val user = module.helixRepository.updateUserDescription(networkLibrary(), helixHeaders(), description)
            _uiState.update { state -> state.copy(user = user ?: state.user) }
            persistAccountCache()
        }
    }

    fun updateChannel(
        title: String? = null,
        gameId: String? = null,
        gameName: String? = null,
        language: String? = null,
        tags: List<String>? = null,
    ) {
        mutate(R.string.account_saved) {
            requireCapability { it.editChannel }
            title?.let {
                require(it.isNotEmpty()) { context.getString(R.string.account_title_empty) }
                require(isValidStreamTitle(it)) { context.getString(R.string.account_title_too_long) }
            }
            tags?.let(::validateTags)
            module.helixRepository.updateChannelInformation(
                networkLibrary(),
                helixHeaders(),
                currentUserId(),
                title = title,
                gameId = gameId,
                language = language,
                tags = tags,
            )
            _uiState.update { state ->
                state.copy(
                    channel = state.channel?.copy(
                        title = title ?: state.channel.title,
                        gameId = gameId ?: state.channel.gameId,
                        gameName = if (gameId != null) gameName else state.channel.gameName,
                        language = language ?: state.channel.language,
                        tags = tags ?: state.channel.tags,
                    )
                )
            }
            persistAccountCache()
        }
    }

    fun updateChatSettings(
        emote: Boolean? = null,
        followers: Boolean? = null,
        followersDuration: Int? = null,
        slow: Boolean? = null,
        slowDuration: Int? = null,
        subs: Boolean? = null,
        unique: Boolean? = null,
    ) {
        mutate(R.string.account_saved) {
            requireCapability { it.editChatSettings }
            val update = normalizeChatSettingsUpdate(
                emote = emote,
                followers = followers,
                followersDuration = followersDuration,
                slow = slow,
                slowDuration = slowDuration,
                subs = subs,
                unique = unique,
            )
            require(update.followersDuration == null || update.followersDuration in 0..129600) {
                context.getString(R.string.account_follower_duration_invalid)
            }
            require(update.slowDuration == null || update.slowDuration in 3..120) {
                context.getString(R.string.account_slow_interval_invalid)
            }
            val error = module.helixRepository.updateChatSettings(
                networkLibrary(),
                helixHeaders(),
                currentUserId(),
                currentUserId(),
                emote = update.emote,
                followers = update.followers,
                followersDuration = update.followersDuration,
                slow = update.slow,
                slowDuration = update.slowDuration,
                subs = update.subs,
                unique = update.unique,
            )
            error?.takeIf { it.isNotBlank() }?.let { throw TwitchApiException(400, null, message = it) }
            _uiState.update { state ->
                state.copy(
                    chatSettings = state.chatSettings?.copy(
                        emoteMode = update.emote ?: state.chatSettings.emoteMode,
                        followerMode = update.followers ?: state.chatSettings.followerMode,
                        followerModeDuration = update.followersDuration ?: state.chatSettings.followerModeDuration,
                        slowMode = update.slow ?: state.chatSettings.slowMode,
                        slowModeWaitTime = update.slowDuration ?: state.chatSettings.slowModeWaitTime,
                        subscriberMode = update.subs ?: state.chatSettings.subscriberMode,
                        uniqueChatMode = update.unique ?: state.chatSettings.uniqueChatMode,
                    ),
                )
            }
            persistAccountCache()
        }
    }

    fun searchCategories(query: String) {
        categorySearchJob?.cancel()
        if (query.trim().length < 2) {
            _categoryResults.value = emptyList()
            return
        }
        categorySearchJob = viewModelScope.launch {
            delay(300)
            runCatching {
                module.helixRepository.getSearchGames(
                    networkLibrary(),
                    helixHeaders(),
                    query.trim(),
                    20,
                    null,
                ).data
            }.onSuccess { _categoryResults.value = it }
                .onFailure { _categoryResults.value = emptyList() }
        }
    }

    fun loadBlockedUsers(reset: Boolean = false) {
        val state = _uiState.value
        if (!state.capabilities.readBlockedUsers || state.blockedUsersLoading || (!reset && state.blockedUsersCursor == null && state.blockedUsers.isNotEmpty())) {
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    blockedUsersLoading = true,
                    blockedUsersLoadError = null,
                    actionError = null,
                )
            }
            try {
                val response = module.helixRepository.getBlockedUsers(
                    networkLibrary(),
                    helixHeaders(),
                    currentUserId(),
                    cursor = if (reset) null else _uiState.value.blockedUsersCursor,
                )
                _uiState.update {
                    it.copy(
                        blockedUsers = if (reset) response.data else it.blockedUsers + response.data,
                        blockedUsersCursor = response.pagination?.cursor,
                        blockedUsersLoading = false,
                        blockedUsersLoadError = null,
                    )
                }
                persistAccountCache()
            } catch (error: Exception) {
                val message = readableError(error)
                _uiState.update {
                    it.copy(
                        blockedUsersLoading = false,
                        blockedUsersLoadError = message,
                        actionError = message,
                    )
                }
            }
        }
    }

    fun unblockUser(user: BlockedUser) {
        val id = user.id ?: return
        mutate(R.string.account_unblocked) {
            requireCapability { it.manageBlockedUsers }
            module.helixRepository.unblockUser(networkLibrary(), helixHeaders(), id)
            _uiState.update { state -> state.copy(blockedUsers = state.blockedUsers.filterNot { it.id == id }) }
            persistAccountCache()
        }
    }

    fun consumeActionMessage() {
        _uiState.update { it.copy(actionMessage = null, actionError = null) }
    }

    private fun mutate(successMessage: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, actionError = null, actionMessage = null) }
            try {
                block()
                _uiState.update { it.copy(saving = false, actionMessage = context.getString(successMessage)) }
            } catch (error: Exception) {
                _uiState.update { it.copy(saving = false, actionError = readableError(error)) }
            }
        }
    }

    private fun requireCapability(selector: (AccountCapabilities) -> Boolean) {
        check(selector(_uiState.value.capabilities)) { context.getString(R.string.account_reconnect_message) }
    }

    private fun validateTags(tags: List<String>) {
        require(tags.size <= 10) { context.getString(R.string.account_too_many_tags) }
        require(tags.all { it.length <= 25 }) { context.getString(R.string.account_tag_too_long) }
        require(tags.all(::isValidAccountTag)) { context.getString(R.string.account_invalid_tag) }
    }

    private fun networkLibrary() = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

    private fun helixHeaders() = TwitchApiHelper.getHelixHeaders(context)

    private fun currentUserId(): String = _uiState.value.user?.id
        ?: context.tokenPrefs().getString(C.USER_ID, null)
        ?: error(context.getString(R.string.account_missing_user))

    private suspend fun persistAccountCache(userId: String? = null, login: String? = null) {
        val state = _uiState.value
        val resolvedUserId = userId
            ?: state.user?.id
            ?: context.tokenPrefs().getString(C.USER_ID, null)
        val resolvedLogin = login
            ?: state.user?.login
            ?: context.tokenPrefs().getString(C.USERNAME, null)
        if (resolvedUserId.isNullOrBlank() && resolvedLogin.isNullOrBlank()) return
        try {
            module.metadataCache.writeAccount(
                userId = resolvedUserId,
                login = resolvedLogin,
                snapshot = AccountCacheSnapshot(
                    user = state.user,
                    scopes = state.scopes,
                    chatColor = state.chatColor,
                    channel = state.channel,
                    chatSettings = state.chatSettings,
                    blockedUsers = state.blockedUsers,
                    blockedUsersCursor = state.blockedUsersCursor,
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun cachedUser(): User? {
        val id = context.tokenPrefs().getString(C.USER_ID, null)
        val login = context.tokenPrefs().getString(C.USERNAME, null)
        if (id.isNullOrBlank() && login.isNullOrBlank()) return null
        return User(
            id = id,
            login = login,
            displayName = login,
            profileImageURL = context.tokenPrefs().getString(C.PROFILE_IMAGE_URL, null),
        )
    }

    private fun readableError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        if (message.isNotBlank()) {
            val apiMessage = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)").find(message)?.groupValues?.getOrNull(1)
            return apiMessage ?: message.substringAfter(": ", message).take(240)
        }
        return context.getString(R.string.account_error)
    }

}
