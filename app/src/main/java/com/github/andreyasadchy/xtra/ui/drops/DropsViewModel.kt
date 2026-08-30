package com.github.andreyasadchy.xtra.ui.drops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.repository.DropsInventoryState
import com.github.andreyasadchy.xtra.repository.DropsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.receiveAsFlow

data class DropsPageUiState(
    val inventory: DropsInventoryState = DropsInventoryState(),
    val campaigns: List<TwitchDropCampaign> = emptyList(),
    val error: Throwable? = null,
    val dashboardError: Throwable? = null,
    val campaignsLoaded: Boolean = false,
    val campaignsRefreshing: Boolean = false,
    val claimingDropId: String? = null,
    val campaignDetailsLoading: Set<String> = emptySet(),
    val campaignDetailsLoaded: Set<String> = emptySet(),
)

data class DropsClaimResult(val success: Boolean)

class DropsViewModel(private val repository: DropsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(DropsPageUiState())
    val uiState: StateFlow<DropsPageUiState> = _uiState.asStateFlow()
    private val claimEvents = Channel<DropsClaimResult>(Channel.BUFFERED)
    val claimResults: Flow<DropsClaimResult> = claimEvents.receiveAsFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.inventory.collectLatest { inventory ->
                _uiState.value = _uiState.value.copy(
                    inventory = inventory,
                    error = inventory.error ?: repository.dashboardError.value,
                )
            }
        }
        viewModelScope.launch {
            repository.dashboard.collectLatest { campaigns ->
                _uiState.value = _uiState.value.copy(
                    campaigns = mergeLoadedCampaignDetails(campaigns),
                    error = _uiState.value.inventory.error ?: repository.dashboardError.value,
                    dashboardError = repository.dashboardError.value,
                )
            }
        }
        viewModelScope.launch {
            repository.dashboardError.collectLatest { error ->
                _uiState.value = _uiState.value.copy(
                    error = _uiState.value.inventory.error ?: error,
                    dashboardError = error,
                )
            }
        }
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(campaignsRefreshing = true) }
            try {
                supervisorScope {
                    val inventoryDeferred = async {
                        try {
                            withTimeout(45_000L) {
                                repository.refreshInventory(force = force)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            DropsInventoryState(error = error)
                        }
                    }
                    val campaignsDeferred = async {
                        try {
                            withTimeout(45_000L) { repository.refreshDashboard(force = force) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            repository.dashboard.value
                        }
                    }
                    val inventory = inventoryDeferred.await()
                    val campaigns = campaignsDeferred.await()
                    val dashboardError = repository.dashboardError.value
                    _uiState.update {
                        it.copy(
                            inventory = inventory,
                            campaigns = mergeLoadedCampaignDetails(campaigns),
                            error = inventory.error ?: dashboardError,
                            dashboardError = dashboardError,
                            campaignsLoaded = it.campaignsLoaded ||
                                (inventory.authenticated && dashboardError == null),
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(campaignsRefreshing = false) }
            }
        }.also { job ->
            job.invokeOnCompletion { refreshJob = null }
        }
    }

    private fun mergeLoadedCampaignDetails(
        campaigns: List<TwitchDropCampaign>,
    ): List<TwitchDropCampaign> {
        val state = _uiState.value
        return campaigns.map { campaign ->
            val loaded = state.campaigns.firstOrNull {
                it.id == campaign.id && it.id in state.campaignDetailsLoaded
            }
            loaded?.let { mergeCampaignDetails(campaign, it) } ?: campaign
        }
    }

    fun claim(drop: TwitchDrop) {
        if (!drop.isClaimable || _uiState.value.claimingDropId != null) return
        _uiState.update {
            it.copy(
                claimingDropId = drop.id,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val success = repository.claim(drop)
                if (!success) {
                    _uiState.value = _uiState.value.copy(
                        error = IllegalStateException("Twitch rejected the Drop claim"),
                    )
                }
                claimEvents.send(DropsClaimResult(success))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(error = error)
                claimEvents.send(DropsClaimResult(false))
            } finally {
                _uiState.update { it.copy(claimingDropId = null) }
            }
        }
    }

    fun loadCampaignDetails(campaignId: String) {
        if (campaignId.isBlank() || campaignId in _uiState.value.campaignDetailsLoading) return
        val campaign = _uiState.value.campaigns.firstOrNull { it.id == campaignId }
        if (campaign == null || campaignId in _uiState.value.campaignDetailsLoaded) return

        _uiState.update { it.copy(campaignDetailsLoading = it.campaignDetailsLoading + campaignId) }
        viewModelScope.launch {
            try {
                repository.loadCampaignDetails(campaignId)?.let { details ->
                    _uiState.update { state ->
                        state.copy(
                            campaigns = state.campaigns.map { current ->
                                if (current.id == campaignId) mergeCampaignDetails(current, details) else current
                            },
                            campaignDetailsLoaded = state.campaignDetailsLoaded + campaignId,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Details are optional enrichment; keep the dashboard usable if this private
                // operation changes independently of the catalog operation.
            } finally {
                _uiState.update {
                    it.copy(campaignDetailsLoading = it.campaignDetailsLoading - campaignId)
                }
            }
        }
    }

    companion object {
        fun factory(repository: DropsRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DropsViewModel(repository) as T
        }
    }
}

internal fun mergeCampaignDetails(
    dashboard: TwitchDropCampaign,
    details: TwitchDropCampaign,
): TwitchDropCampaign = details.copy(
    name = details.name ?: dashboard.name,
    gameName = details.gameName ?: dashboard.gameName,
    imageUrl = details.imageUrl ?: dashboard.imageUrl,
    startTime = details.startTime ?: dashboard.startTime,
    endTime = details.endTime ?: dashboard.endTime,
    // The dashboard is the fresh source of campaign lifecycle state. Details are optional
    // enrichment and must not make an active campaign remain permanently Upcoming.
    isUpcoming = dashboard.isUpcoming,
    drops = details.drops.map { detailDrop ->
        val dashboardDrop = dashboard.drops.firstOrNull { it.id == detailDrop.id }
        detailDrop.copy(
            name = detailDrop.name ?: dashboardDrop?.name,
            requiredMinutesWatched = detailDrop.requiredMinutesWatched.takeIf { it > 0 }
                ?: dashboardDrop?.requiredMinutesWatched.orZero(),
            benefits = detailDrop.benefits.ifEmpty { dashboardDrop?.benefits.orEmpty() },
        )
    } + dashboard.drops.filterNot { dashboardDrop ->
        details.drops.any { it.id == dashboardDrop.id }
    },
)

private fun Int?.orZero(): Int = this ?: 0
