package com.github.andreyasadchy.xtra.ui.channel.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChannelAboutViewModel(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val description = MutableStateFlow<String?>(null)
    val socialMedias = MutableStateFlow<List<Pair<String?, String?>>?>(null)
    val team = MutableStateFlow<Pair<String?, String?>?>(null)
    val originalName = MutableStateFlow<String?>(null)
    val panels = MutableStateFlow<List<ChannelPanel>?>(null)

    enum class LoadState { Loading, Ready, Failed }
    val state = MutableStateFlow(LoadState.Loading)
    val scheduleState = MutableStateFlow(LoadState.Loading)
    data class ScheduledBroadcast(val title: String?, val start: String?, val end: String?, val category: String?)
    data class ViewerConnection(val followedAt: String?, val subscribed: Boolean, val months: Int?)
    val schedule = MutableStateFlow<List<ScheduledBroadcast>>(emptyList())
    val connection = MutableStateFlow<ViewerConnection?>(null)
    private var aboutLoaded = false
    private var scheduleLoaded = false
    private var scheduleLoading = false
    private var connectionLoading = false
    private var connectionLoaded = false
    private var isLoading = false

    fun loadAbout(channelId: String?, channelLogin: String?, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (!aboutLoaded && !isLoading) {
            isLoading = true
            state.value = LoadState.Loading
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryUserAbout(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
                    check(response.errors.isNullOrEmpty())
                    val user = checkNotNull(response.data?.user)
                    user.let {
                        description.value = user.description
                        socialMedias.value = user.channel?.socialMedias?.map {
                            it.title to it.url
                        }
                        team.value = user.primaryTeam?.name to user.primaryTeam?.displayName
                        originalName.value = user.subscriptionProducts?.find { it?.tier == "1000" }?.name?.takeIf { it != channelLogin }
                        panels.value = user.panels?.mapNotNull { item ->
                            item?.onDefaultPanel?.let {
                                ChannelPanel(
                                    title = it.title,
                                    imageUrl = it.imageURL,
                                    linkUrl = it.linkURL,
                                    description = it.description,
                                )
                            }
                        }
                    }
                    aboutLoaded = true
                    state.value = LoadState.Ready
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.value = LoadState.Failed
                } finally {
                    isLoading = false
                }
            }
        }
    }

    fun loadSchedule(channelId: String?, channelLogin: String?, networkLibrary: String?, headers: Map<String, String>) {
        if (scheduleLoaded || scheduleLoading) return
        scheduleLoading = true
        scheduleState.value = LoadState.Loading
        viewModelScope.launch {
            try {
                val response = graphQLRepository.loadQueryUserSchedule(networkLibrary, headers, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
                check(response.errors.isNullOrEmpty())
                val user = checkNotNull(response.data?.user)
                schedule.value = user.channel?.schedule?.segments.orEmpty().map {
                    ScheduledBroadcast(it.title, it.startAt?.toString(), it.endAt?.toString(), it.categories?.firstOrNull()?.name)
                }
                scheduleLoaded = true
                scheduleState.value = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleState.value = LoadState.Failed
            } finally {
                scheduleLoading = false
            }
        }
    }

    fun loadConnection(viewerId: String?, channelId: String?, networkLibrary: String?, headers: Map<String, String>, revalidate: Boolean = false) {
        if (viewerId.isNullOrBlank() || channelId.isNullOrBlank() || (connectionLoaded && !revalidate) || connectionLoading) return
        connectionLoading = true
        viewModelScope.launch {
            try {
                val response = graphQLRepository.loadQueryUserProfileConnection(networkLibrary, headers, viewerId, channelId)
                check(response.errors.isNullOrEmpty())
                val user = checkNotNull(response.data?.user)
                connection.value = ViewerConnection(
                    user.follow?.followedAt?.toString(),
                    user.relationship?.subscriptionBenefit != null,
                    user.relationship?.subscriptionTenure?.months,
                )
                connectionLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Viewer-only fields are optional; an auth failure must not erase public data.
            } finally {
                connectionLoading = false
            }
        }
    }

    companion object {
        val ChannelAboutViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                ChannelAboutViewModel(xtraModule.graphQLRepository)
            }
        }
    }
}
