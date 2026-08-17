package com.github.andreyasadchy.xtra.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
import com.github.andreyasadchy.xtra.util.viewingstats.CategoryWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ChannelWatchTotal
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatisticsListViewModel(
    private val repository: ViewingStatsRepository,
    private val type: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatisticsListUiState(isLoading = true))
    val uiState: StateFlow<StatisticsListUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { selectRange(ViewingStatsRange.LAST_7_DAYS) }

    fun selectRange(range: ViewingStatsRange) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load(range) }
    }

    private suspend fun load(range: ViewingStatsRange) {
        _uiState.value = _uiState.value.copy(isLoading = true, range = range)
        val result = withContext(Dispatchers.IO) {
            val total = repository.loadTotalWatchMs(range)
            if (type == TYPE_CHANNEL) {
                StatisticsListResult.Channels(total, repository.loadAllChannels(range))
            } else {
                StatisticsListResult.Categories(total, repository.loadAllCategories(range))
            }
        }
        currentCoroutineContext().ensureActive()
        _uiState.value = StatisticsListUiState(isLoading = false, range = range, result = result)
    }

    companion object {
        const val TYPE_CHANNEL = "channels"
        const val TYPE_CATEGORY = "categories"
        fun factory(repository: ViewingStatsRepository, type: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StatisticsListViewModel(repository, type) as T
        }
    }
}

data class StatisticsListUiState(
    val isLoading: Boolean,
    val range: ViewingStatsRange = ViewingStatsRange.LAST_7_DAYS,
    val result: StatisticsListResult? = null,
)

sealed interface StatisticsListResult {
    val totalWatchMs: Long
    data class Channels(override val totalWatchMs: Long, val items: List<ChannelWatchTotal>) : StatisticsListResult
    data class Categories(override val totalWatchMs: Long, val items: List<CategoryWatchTotal>) : StatisticsListResult
}
