package com.github.andreyasadchy.xtra.ui.channel.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.ChannelAboutCacheSnapshot
import com.github.andreyasadchy.xtra.repository.ChannelPanelCacheEntry
import com.github.andreyasadchy.xtra.repository.ChannelScheduleCacheSnapshot
import com.github.andreyasadchy.xtra.repository.ChannelScheduledBroadcastCacheEntry
import com.github.andreyasadchy.xtra.repository.ChannelSocialMediaCacheEntry
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.MetadataCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChannelAboutViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val metadataCache: MetadataCache,
) : ViewModel() {

    val description = MutableStateFlow<String?>(null)
    val socialMedias = MutableStateFlow<List<Pair<String?, String?>>?>(null)
    val team = MutableStateFlow<Pair<String?, String?>?>(null)
    val originalName = MutableStateFlow<String?>(null)
    val panels = MutableStateFlow<List<ChannelPanel>?>(null)

    enum class LoadState { Loading, Ready, Stale, Failed }
    val state = MutableStateFlow(LoadState.Loading)
    val scheduleState = MutableStateFlow(LoadState.Loading)
    data class ScheduledBroadcast(val title: String?, val start: String?, val end: String?, val category: String?)
    data class ViewerConnection(val followedAt: String?, val subscribed: Boolean, val months: Int?)
    val schedule = MutableStateFlow<List<ScheduledBroadcast>>(emptyList())
    val connection = MutableStateFlow<ViewerConnection?>(null)
    private var aboutLoaded = false
    private var scheduleLoaded = false
    private var hasAboutCache = false
    private var hasScheduleCache = false
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
                    val cached = try {
                        metadataCache.readChannelAbout(channelId, channelLogin)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    cached?.let { entry ->
                        hasAboutCache = true
                        applyAboutSnapshot(entry.snapshot)
                        if (entry.fresh) {
                            state.value = LoadState.Ready
                            aboutLoaded = true
                            return@launch
                        }
                    }
                    val response = graphQLRepository.loadQueryUserAbout(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
                    check(response.errors.isNullOrEmpty())
                    val user = checkNotNull(response.data?.user)
                    val snapshot = ChannelAboutCacheSnapshot(
                        description = user.description,
                        socialMedias = user.channel?.socialMedias.orEmpty().map {
                            ChannelSocialMediaCacheEntry(it.title, it.url)
                        },
                        teamName = user.primaryTeam?.name,
                        teamDisplayName = user.primaryTeam?.displayName,
                        originalName = user.subscriptionProducts?.find { it?.tier == "1000" }?.name
                            ?.takeIf { it != channelLogin },
                        panels = user.panels.orEmpty().mapNotNull { item ->
                            item?.onDefaultPanel?.let {
                                ChannelPanelCacheEntry(it.title, it.imageURL, it.linkURL, it.description)
                            }
                        },
                    )
                    applyAboutSnapshot(snapshot)
                    try {
                        metadataCache.writeChannelAbout(
                            channelId = user.id,
                            login = user.login ?: channelLogin,
                            snapshot = snapshot,
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                    aboutLoaded = true
                    state.value = LoadState.Ready
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.value = if (hasAboutCache) LoadState.Stale else LoadState.Failed
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
                val cached = try {
                    metadataCache.readChannelSchedule(channelId, channelLogin)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                cached?.let { entry ->
                    hasScheduleCache = true
                    schedule.value = entry.snapshot.broadcasts.map {
                        ScheduledBroadcast(it.title, it.start, it.end, it.category)
                    }
                    if (entry.fresh) {
                        scheduleState.value = LoadState.Ready
                        scheduleLoaded = true
                        return@launch
                    }
                }
                val response = graphQLRepository.loadQueryUserSchedule(networkLibrary, headers, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
                check(response.errors.isNullOrEmpty())
                val user = checkNotNull(response.data?.user)
                val broadcasts = user.channel?.schedule?.segments.orEmpty().map {
                    ScheduledBroadcast(it.title, it.startAt?.toString(), it.endAt?.toString(), it.categories?.firstOrNull()?.name)
                }
                schedule.value = broadcasts
                try {
                    metadataCache.writeChannelSchedule(
                        channelId = user.id,
                        login = user.login ?: channelLogin,
                        snapshot = ChannelScheduleCacheSnapshot(
                            broadcasts = broadcasts.map {
                                ChannelScheduledBroadcastCacheEntry(it.title, it.start, it.end, it.category)
                            },
                        ),
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                scheduleLoaded = true
                scheduleState.value = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleState.value = if (hasScheduleCache) LoadState.Stale else LoadState.Failed
            } finally {
                scheduleLoading = false
            }
        }
    }

    private fun applyAboutSnapshot(snapshot: ChannelAboutCacheSnapshot) {
        description.value = snapshot.description
        socialMedias.value = snapshot.socialMedias.map { it.title to it.url }
        team.value = snapshot.teamName to snapshot.teamDisplayName
        originalName.value = snapshot.originalName
        panels.value = snapshot.panels.map {
            ChannelPanel(it.title, it.imageUrl, it.linkUrl, it.description)
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
                ChannelAboutViewModel(xtraModule.graphQLRepository, xtraModule.metadataCache)
            }
        }
    }
}
