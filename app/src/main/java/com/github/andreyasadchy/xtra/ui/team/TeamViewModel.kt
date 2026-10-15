package com.github.andreyasadchy.xtra.ui.team

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Team
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.datasource.TeamMembersDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.github.andreyasadchy.xtra.ui.common.SectionLoadState
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TeamViewModel(
    private val applicationContext: Context,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args = TeamFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val team = MutableStateFlow<Team?>(null)

    private var isLoading = false
    private val _infoState = MutableStateFlow(SectionLoadState.LOADING)
    val infoState = _infoState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val flow = Pager(
        PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 30)
    ) {
        TeamMembersDataSource(
            teamName = args.teamName,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext),
            helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext),
            helixRepository = helixRepository,
            graphQLRepository = graphQLRepository,
            networkLibrary = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
        )
    }.flow.cachedIn(viewModelScope)

    fun loadTeamInfo(teamName: String?, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (team.value != null || isLoading) return
        if (teamName.isNullOrBlank()) {
            _infoState.value = SectionLoadState.FAILED
            return
        }
        isLoading = true
        _infoState.value = SectionLoadState.LOADING
        viewModelScope.launch {
            try {
                val response = graphQLRepository.loadQueryTeam(networkLibrary, gqlHeaders, teamName)
                val details = checkNotNull(response.data?.team)
                team.value = Team(
                    displayName = details.displayName,
                    description = details.description,
                    logoUrl = details.logoURL,
                    bannerUrl = details.bannerURL,
                    memberCount = details.members?.totalCount,
                    ownerLogin = details.owner?.login,
                    ownerName = details.owner?.displayName,
                )
                _infoState.value = SectionLoadState.READY
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _infoState.value = SectionLoadState.FAILED
            } finally {
                isLoading = false
            }
        }
    }

    companion object {
        val TeamViewModelFactory = viewModelFactory {
            initializer {
                val savedStateHandle = createSavedStateHandle()
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                TeamViewModel(
                    application.applicationContext,
                    xtraModule.graphQLRepository,
                    xtraModule.helixRepository,
                    savedStateHandle,
                )
            }
        }
    }
}
