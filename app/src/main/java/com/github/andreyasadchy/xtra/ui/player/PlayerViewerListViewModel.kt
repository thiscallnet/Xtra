package com.github.andreyasadchy.xtra.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.ChannelViewer
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.repository.GraphQLApiException
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewerListViewModel(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    private val _viewerList = MutableStateFlow<ChannelViewerList?>(null)
    val viewerList: StateFlow<ChannelViewerList?> = _viewerList
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError
    private var loading = false

    fun loadViewerList(channelLogin: String?, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (_viewerList.value == null && !loading) {
            loading = true
            _isLoading.value = true
            _hasError.value = false
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryUserChatters(networkLibrary, gqlHeaders, login = channelLogin)
                    response.errors?.firstOrNull()?.let {
                        throw GraphQLApiException(it.message, operation = "UserChatters")
                    }
                    val chatters = response.data?.user?.channel?.chatters
                        ?: throw GraphQLApiException("GraphQL response did not include viewer data", operation = "UserChatters")
                    val broadcasters = chatters.broadcasters?.mapNotNull { it.login } ?: emptyList()
                    val moderators = chatters.moderators?.mapNotNull { it.login } ?: emptyList()
                    val vips = chatters.vips?.mapNotNull { it.login } ?: emptyList()
                    val viewers = chatters.viewers?.mapNotNull { it.login } ?: emptyList()
                    _viewerList.value = ChannelViewerList(
                        broadcasters = broadcasters.map(::ChannelViewer),
                        moderators = moderators.map(::ChannelViewer),
                        vips = vips.map(::ChannelViewer),
                        viewers = viewers.map(::ChannelViewer),
                        count = chatters.count,
                    )
                } catch (_: Exception) {
                    _hasError.value = true
                } finally {
                    _isLoading.value = false
                    loading = false
                }
            }
        }
    }

    suspend fun enrichProfiles(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        viewers: List<ChannelViewer>,
    ): List<ChannelViewer> {
        val profileByLogin = loadProfiles(
            networkLibrary,
            gqlHeaders,
            viewers.map { it.login },
        ).associateBy { it.login.lowercase() }
        return viewers.map { profileByLogin[it.login.lowercase()] ?: it }
    }

    private suspend fun loadProfiles(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        logins: List<String>,
    ): List<ChannelViewer> = try {
        logins.chunked(100).flatMap { batch ->
            val response = graphQLRepository.loadQueryUsers(networkLibrary, gqlHeaders, logins = batch)
            if (!response.errors.isNullOrEmpty()) {
                emptyList()
            } else {
                response.data?.users.orEmpty().mapNotNull { nullableUser ->
                    nullableUser?.let { user ->
                        user.login?.let { login ->
                            ChannelViewer(
                                login = login,
                                id = user.id,
                                displayName = user.displayName,
                                profileImageURL = user.profileImageURL,
                            )
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        val PlayerViewerListViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                PlayerViewerListViewModel(xtraModule.graphQLRepository)
            }
        }
    }
}
