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
import com.github.andreyasadchy.xtra.model.gql.ErrorResponse
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

private val CURRENT_USER_ACCOUNT_QUERY = """
query CurrentUserAccount {
    currentUser {
        id
        login
        displayName
        description
        profileImageURL(width: 300)
        chatColor
        chatSettings {
            isEmoteOnlyModeEnabled
            isSubscribersOnlyModeEnabled
            isUniqueChatModeEnabled
            followersOnlyDurationMinutes
            slowModeDurationSeconds
        }
        broadcastSettings {
            title
        }
        channel {
            id
            game
            broadcasterLanguage
        }
        tags {
            id
            localizedName
            scope
        }
    }
}
""".trimIndent()

private val CURRENT_USER_BLOCKED_USERS_QUERY = """
query CurrentUserBlockedUsers {
    currentUser {
        blockedUsers {
            id
            login
            displayName
        }
    }
}
""".trimIndent()

private val UPDATE_USER_MUTATION = """
mutation UpdateUser(${ '$' }input: UpdateUserInput!) {
    updateUser(input: ${ '$' }input) {
        __typename
    }
}
""".trimIndent()

private val UPDATE_BROADCAST_SETTINGS_MUTATION = """
mutation UpdateBroadcastSettings(${ '$' }input: UpdateBroadcastSettingsInput!) {
    updateBroadcastSettings(input: ${ '$' }input) {
        __typename
    }
}
""".trimIndent()

private val UNBLOCK_USER_MUTATION = """
mutation UnblockUser(${ '$' }input: UnblockUserInput!) {
    unblockUser(input: ${ '$' }input) {
        __typename
    }
}
""".trimIndent()

data class AccountCapabilities(
    val editBio: Boolean = false,
    val editChatColor: Boolean = false,
    val editChannel: Boolean = false,
    val editChannelTags: Boolean = false,
    val editChatSettings: Boolean = false,
    val readBlockedUsers: Boolean = false,
    val manageBlockedUsers: Boolean = false,
) {
    companion object {
        fun from(scopes: Set<String>) = AccountCapabilities(
            editBio = "user:edit" in scopes,
            editChatColor = "user:manage:chat_color" in scopes,
            editChannel = "channel:manage:broadcast" in scopes,
            editChannelTags = "channel:manage:broadcast" in scopes,
            editChatSettings = "moderator:manage:chat_settings" in scopes,
            readBlockedUsers = "user:read:blocked_users" in scopes,
            manageBlockedUsers = "user:manage:blocked_users" in scopes,
        )
    }
}

private val WEB_ACCOUNT_CAPABILITIES = AccountCapabilities(
    editBio = true,
    editChatColor = true,
    editChannel = true,
    editChatSettings = true,
    readBlockedUsers = true,
    manageBlockedUsers = true,
)

private data class WebAccountSnapshot(
    val user: User,
    val chatColor: String?,
    val channel: ChannelInformation,
    val chatSettings: ChatSettings,
)

data class AccountUiState(
    val loading: Boolean = true,
    val user: User? = null,
    val webSession: Boolean = false,
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
        val tokenUserId = context.tokenPrefs().getString(C.USER_ID, null)
        val tokenLogin = context.tokenPrefs().getString(C.USERNAME, null)
        val webToken = context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
        val cached = try {
            module.metadataCache.readAccount(tokenUserId, tokenLogin)
        } catch (_: Exception) {
            null
        }
        if (!webToken.isNullOrBlank()) {
            _uiState.update { it.copy(loading = true, error = null, actionError = null, actionMessage = null) }
            try {
                val account = loadWebAccount()
                _uiState.update {
                    it.copy(
                        loading = false,
                        user = account.user,
                        webSession = true,
                        scopes = emptySet(),
                        capabilities = WEB_ACCOUNT_CAPABILITIES,
                        chatColor = account.chatColor,
                        chatColorLoadError = null,
                        channel = account.channel,
                        channelLoadError = null,
                        chatSettings = account.chatSettings,
                        chatSettingsLoadError = null,
                        blockedUsers = cached?.blockedUsers.orEmpty(),
                        blockedUsersCursor = cached?.blockedUsersCursor,
                        blockedUsersLoading = false,
                        blockedUsersLoadError = null,
                        error = null,
                    )
                }
                persistAccountCache(
                    userId = account.user.id,
                    login = account.user.login,
                    chatColorValidated = account.chatColor != null,
                    channelValidated = true,
                    chatSettingsValidated = true,
                )
                return
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        webSession = false,
                        scopes = emptySet(),
                        capabilities = AccountCapabilities(),
                        chatColorLoadError = null,
                        channelLoadError = null,
                        chatSettingsLoadError = null,
                        blockedUsersLoading = false,
                        blockedUsersLoadError = null,
                        error = readableError(error),
                    )
                }
                return
            }
        }
        if (token.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    user = cached?.user ?: cachedUser(),
                    webSession = false,
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

        val cachedScopes = cached?.scopes.orEmpty()
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                user = cached?.user ?: cachedUser(),
                webSession = false,
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
                    val expectedClientId = headers[C.HEADER_CLIENT_ID]
                    check(response.clientId == expectedClientId) { "The Twitch Helix token belongs to another client" }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        webSession = false,
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
                        webSession = false,
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
                persistAccountCache(
                    userId = resolvedUserId,
                    login = login,
                    scopesValidated = true,
                    chatColorValidated = colorResult?.getOrNull()?.let(::isCanonicalChatColor) == true,
                    channelValidated = channelResult?.getOrNull() != null,
                    chatSettingsValidated = chatSettingsResult?.getOrNull() != null,
                )
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

    private suspend fun loadWebAccount(): WebAccountSnapshot {
        val response = module.graphQLRepository.executeRawOperation(
            networkLibrary = networkLibrary(),
            headers = gqlHeaders(),
            operationName = "CurrentUserAccount",
            query = CURRENT_USER_ACCOUNT_QUERY,
        )
        val currentUser = response.requireDataObject("currentUser")
        val userId = currentUser.string("id") ?: error(context.getString(R.string.account_missing_user))
        val login = currentUser.string("login")
        val displayName = currentUser.string("displayName")
        val channel = currentUser.objectValue("channel")
        val chatSettings = currentUser.objectValue("chatSettings")
        val tags = currentUser.arrayValue("tags").mapNotNull { tag ->
            tag.objectValue("tag")?.string("localizedName") ?: tag.string("localizedName")
        }
        val followerDuration = chatSettings?.intValue("followersOnlyDurationMinutes")
        val slowDuration = chatSettings?.intValue("slowModeDurationSeconds")
        return WebAccountSnapshot(
            user = User(
                id = userId,
                login = login,
                displayName = displayName,
                description = currentUser.string("description"),
                profileImageURL = currentUser.string("profileImageURL"),
            ),
            chatColor = currentUser.string("chatColor").takeIf(::isCanonicalChatColor),
            channel = ChannelInformation(
                broadcasterId = userId,
                broadcasterLogin = login,
                broadcasterName = displayName,
                gameName = channel?.string("game"),
                language = channel?.string("broadcasterLanguage")?.lowercase(Locale.ROOT),
                title = currentUser.objectValue("broadcastSettings")?.string("title"),
                tags = tags,
            ),
            chatSettings = ChatSettings(
                broadcasterId = userId,
                moderatorId = userId,
                followerMode = followerDuration != null,
                followerModeDuration = followerDuration,
                slowMode = slowDuration != null,
                slowModeWaitTime = slowDuration,
                subscriberMode = chatSettings?.booleanValue("isSubscribersOnlyModeEnabled") ?: false,
                emoteMode = chatSettings?.booleanValue("isEmoteOnlyModeEnabled") ?: false,
                uniqueChatMode = chatSettings?.booleanValue("isUniqueChatModeEnabled") ?: false,
            ),
        )
    }

    fun updateChatColor(color: String) {
        mutate(R.string.account_saved) {
            requireCapability { it.editChatColor }
            if (_uiState.value.webSession) {
                requireNoGraphQLErrors(
                    module.graphQLRepository.updateChatColor(
                        networkLibrary(),
                        gqlHeaders(),
                        color,
                    ),
                )
                _uiState.update { state ->
                    state.copy(
                        chatColor = color.toCanonicalChatColor(),
                        chatColorLoadError = null,
                    )
                }
                persistAccountCache(chatColorValidated = color.toCanonicalChatColor() != null)
                return@mutate
            }
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
            persistAccountCache(
                chatColorValidated = canonicalColor.getOrNull()?.let(::isCanonicalChatColor) == true,
            )
        }
    }

    fun updateBio(description: String) {
        mutate(R.string.account_saved) {
            requireCapability { it.editBio }
            require(description.length <= 300) { context.getString(R.string.account_bio_too_long) }
            if (_uiState.value.webSession) {
                executeGqlMutation(
                    operationName = "UpdateUser",
                    query = UPDATE_USER_MUTATION,
                    variables = buildJsonObject {
                        putJsonObject("input") {
                            put("userID", currentUserId())
                            put("description", description)
                        }
                    },
                )
                _uiState.update { state ->
                    state.user?.let { user ->
                        state.copy(
                            user = User(
                                id = user.id,
                                login = user.login,
                                displayName = user.displayName,
                                type = user.type,
                                broadcasterType = user.broadcasterType,
                                profileImageURL = user.profileImageURL,
                                description = description,
                                offlineImageUrl = user.offlineImageUrl,
                                createdAt = user.createdAt,
                            ),
                        )
                    } ?: state
                }
                persistAccountCache()
                return@mutate
            }
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
            if (_uiState.value.webSession) {
                check(tags == null) { context.getString(R.string.account_tags_manage_on_twitch) }
                executeGqlMutation(
                    operationName = "UpdateBroadcastSettings",
                    query = UPDATE_BROADCAST_SETTINGS_MUTATION,
                    variables = buildJsonObject {
                        putJsonObject("input") {
                            put("userID", currentUserId())
                            title?.let { put("status", it) }
                            (gameId ?: gameName)?.let { put("game", it) }
                            language?.let { put("broadcasterLanguage", it.uppercase(Locale.ROOT)) }
                        }
                    },
                )
                _uiState.update { state ->
                    state.copy(
                        channel = state.channel?.copy(
                            title = title ?: state.channel.title,
                            gameId = gameId ?: state.channel.gameId,
                            gameName = if (gameId != null) gameName else state.channel.gameName,
                            language = language ?: state.channel.language,
                        ),
                    )
                }
                persistAccountCache()
                return@mutate
            }
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
            if (_uiState.value.webSession) {
                val headers = gqlHeaders()
                if (update.emote != null || update.unique != null) {
                    requireNoGraphQLErrors(
                        module.graphQLRepository.updateChatSettings(
                            networkLibrary(),
                            headers,
                            currentUserId(),
                            emote = update.emote,
                            unique = update.unique,
                        ),
                    )
                }
                update.subs?.let { enabled ->
                    requireNoGraphQLErrors(
                        module.graphQLRepository.sendMessage(
                            networkLibrary(),
                            headers,
                            currentUserId(),
                            if (enabled) "/subscribers" else "/subscribersoff",
                            null,
                        ),
                    )
                }
                update.followers?.let { enabled ->
                    requireNoGraphQLErrors(
                        module.graphQLRepository.setFollowersOnlyMode(
                            networkLibrary(),
                            headers,
                            currentUserId(),
                            if (enabled) update.followersDuration ?: _uiState.value.chatSettings?.followerModeDuration ?: 0 else -1,
                        ),
                    )
                }
                update.slow?.let { enabled ->
                    requireNoGraphQLErrors(
                        module.graphQLRepository.setSlowMode(
                            networkLibrary(),
                            headers,
                            currentUserId(),
                            if (enabled) update.slowDuration ?: _uiState.value.chatSettings?.slowModeWaitTime ?: 30 else 0,
                        ),
                    )
                }
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
                return@mutate
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
                if (_uiState.value.webSession) {
                    val response = module.graphQLRepository.loadQuerySearchGames(
                        networkLibrary(),
                        gqlHeaders(),
                        query.trim(),
                        20,
                        null,
                    )
                    response.data?.searchCategories?.edges.orEmpty().mapNotNull { edge ->
                        edge.node?.let { node ->
                            Game(
                                id = node.id,
                                name = node.displayName,
                                boxArtURL = node.boxArtURL,
                            )
                        }
                    }
                } else {
                    module.helixRepository.getSearchGames(
                        networkLibrary(),
                        helixHeaders(),
                        query.trim(),
                        20,
                        null,
                    ).data
                }
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
                val users: List<BlockedUser>
                val cursor: String?
                if (_uiState.value.webSession) {
                    val response = module.graphQLRepository.executeRawOperation(
                        networkLibrary(),
                        gqlHeaders(),
                        "CurrentUserBlockedUsers",
                        CURRENT_USER_BLOCKED_USERS_QUERY,
                    )
                    val currentUser = response.requireDataObject("currentUser")
                    users = currentUser.arrayValue("blockedUsers").mapNotNull { blockedUser ->
                        blockedUser.objectValue("user")?.blockedUserFromJson()
                            ?: blockedUser.blockedUserFromJson()
                    }
                    cursor = null
                } else {
                    val response = module.helixRepository.getBlockedUsers(
                        networkLibrary(),
                        helixHeaders(),
                        currentUserId(),
                        cursor = if (reset) null else _uiState.value.blockedUsersCursor,
                    )
                    users = response.data
                    cursor = response.pagination?.cursor
                }
                _uiState.update {
                    it.copy(
                        blockedUsers = if (reset || _uiState.value.webSession) users else it.blockedUsers + users,
                        blockedUsersCursor = cursor,
                        blockedUsersLoading = false,
                        blockedUsersLoadError = null,
                    )
                }
                persistAccountCache(blockedUsersValidated = reset)
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
            if (_uiState.value.webSession) {
                executeGqlMutation(
                    operationName = "UnblockUser",
                    query = UNBLOCK_USER_MUTATION,
                    variables = buildJsonObject {
                        putJsonObject("input") {
                            put("targetUserID", id)
                        }
                    },
                )
            } else {
                module.helixRepository.unblockUser(networkLibrary(), helixHeaders(), id)
            }
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

    private fun gqlHeaders() = TwitchApiHelper.getWebGQLHeaders(context, includeToken = true)

    private fun helixHeaders() = TwitchApiHelper.getHelixHeaders(context)

    private suspend fun executeGqlMutation(
        operationName: String,
        query: String,
        variables: JsonObject,
    ) = module.graphQLRepository.executeRawOperation(
        networkLibrary = networkLibrary(),
        headers = gqlHeaders(),
        operationName = operationName,
        query = query,
        variables = variables,
    ).requireData()

    private fun currentUserId(): String = _uiState.value.user?.id
        ?: context.tokenPrefs().getString(C.USER_ID, null)
        ?: error(context.getString(R.string.account_missing_user))

    private suspend fun persistAccountCache(
        userId: String? = null,
        login: String? = null,
        scopesValidated: Boolean = false,
        chatColorValidated: Boolean = false,
        channelValidated: Boolean = false,
        chatSettingsValidated: Boolean = false,
        blockedUsersValidated: Boolean = false,
    ) {
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
                scopesValidated = scopesValidated,
                chatColorValidated = chatColorValidated,
                channelValidated = channelValidated,
                chatSettingsValidated = chatSettingsValidated,
                blockedUsersValidated = blockedUsersValidated,
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

private fun JsonObject.requireData(): JsonObject {
    val errors = graphQLErrorMessages()
    check(errors.isEmpty()) { errors.joinToString("; ") }
    return objectValue("data") ?: error("Twitch did not return GraphQL data")
}

private fun JsonObject.requireDataObject(name: String): JsonObject {
    val data = requireData()
    return data.objectValue(name) ?: error("Twitch did not return $name")
}

private fun JsonObject.graphQLErrorMessages(): List<String> = this["errors"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    .orEmpty()
    .mapNotNull { element ->
        runCatching { element.jsonObject.string("message") }.getOrNull()
    }
    .ifEmpty { if (this["errors"] != null) listOf("Twitch rejected the GraphQL request") else emptyList() }

private fun JsonObject.objectValue(name: String): JsonObject? = this[name]
    ?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arrayValue(name: String): List<JsonObject> = this[name]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    .orEmpty()
    .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }

private fun JsonObject.string(name: String): String? = this[name]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.intValue(name: String): Int? = string(name)?.toIntOrNull()

private fun JsonObject.booleanValue(name: String): Boolean? = this[name]
    ?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }

private fun JsonObject.blockedUserFromJson(): BlockedUser? {
    val id = string("id") ?: return null
    return BlockedUser(
        id = id,
        login = string("login"),
        displayName = string("displayName"),
    )
}

private fun requireNoGraphQLErrors(response: ErrorResponse) {
    response.errors.orEmpty().mapNotNull { it.message }.takeIf { it.isNotEmpty() }?.let {
        throw TwitchApiException(400, null, message = it.joinToString("; "))
    }
}

private fun String.toCanonicalChatColor(): String? = takeIf { isCanonicalChatColor(it) }
    ?: TWITCH_CHAT_COLOR_OPTIONS.firstOrNull { it.apiValue.equals(this, ignoreCase = true) }?.hex
