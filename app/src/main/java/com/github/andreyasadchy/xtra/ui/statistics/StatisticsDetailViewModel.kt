package com.github.andreyasadchy.xtra.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.repository.ViewingStatsDetailSnapshot
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
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

class StatisticsDetailViewModel(
    private val repository: ViewingStatsRepository,
    private val type: String,
    private val channelId: String?,
    private val categoryKey: String?,
    private val title: String?,
    private val bucketFrom: Long?,
    private val bucketTo: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsDetailUiState(isLoading = true))
    val uiState: StateFlow<StatisticsDetailUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        if (bucketFrom != null && bucketTo != null) {
            loadJob = viewModelScope.launch { loadBucket() }
        } else {
            selectRange(ViewingStatsRange.LAST_7_DAYS)
        }
    }

    fun selectRange(range: ViewingStatsRange) {
        if (bucketFrom != null && bucketTo != null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load(range) }
    }

    fun selectBucket(index: Int) {
        _uiState.value = _uiState.value.copy(selectedBucketIndex = index)
    }

    private suspend fun loadBucket() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val snapshot = withContext(Dispatchers.IO) {
            repository.loadBucketDetail(bucketFrom!!, bucketTo!!)
        }
        currentCoroutineContext().ensureActive()
        _uiState.value = StatisticsDetailUiState(
            isLoading = false,
            range = ViewingStatsRange.LAST_7_DAYS,
            snapshot = snapshot,
            title = title,
            selectedBucketIndex = -1,
        )
    }

    private suspend fun load(range: ViewingStatsRange) {
        _uiState.value = _uiState.value.copy(isLoading = true, range = range)
        val snapshot = withContext(Dispatchers.IO) {
            when {
                type == TYPE_CHANNEL && channelId != null -> repository.loadChannelDetail(channelId, range)
                type == TYPE_CATEGORY && categoryKey != null -> repository.loadCategoryDetail(categoryKey, range)
                else -> null
            }
        }
        currentCoroutineContext().ensureActive()
        _uiState.value = StatisticsDetailUiState(
            isLoading = false,
            range = range,
            snapshot = snapshot,
            title = title,
            selectedBucketIndex = -1,
        )
    }

    companion object {
        const val TYPE_CHANNEL = "channel"
        const val TYPE_CATEGORY = "category"
        const val TYPE_BUCKET = "bucket"

        fun factory(
            repository: ViewingStatsRepository,
            type: String,
            channelId: String?,
            categoryKey: String?,
            title: String?,
            bucketFrom: Long?,
            bucketTo: Long?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StatisticsDetailViewModel(
                    repository = repository,
                    type = type,
                    channelId = channelId,
                    categoryKey = categoryKey,
                    title = title,
                    bucketFrom = bucketFrom,
                    bucketTo = bucketTo,
                ) as T
            }
        }
    }
}

data class StatisticsDetailUiState(
    val isLoading: Boolean,
    val range: ViewingStatsRange = ViewingStatsRange.LAST_7_DAYS,
    val snapshot: ViewingStatsDetailSnapshot? = null,
    val title: String? = null,
    val selectedBucketIndex: Int = -1,
)
