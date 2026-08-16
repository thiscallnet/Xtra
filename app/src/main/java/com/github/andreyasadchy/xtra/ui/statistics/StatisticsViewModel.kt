package com.github.andreyasadchy.xtra.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.ViewingStatsRepository
import com.github.andreyasadchy.xtra.repository.ViewingStatsSnapshot
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone

class StatisticsViewModel(
    private val repository: ViewingStatsRepository,
    private val recorder: ViewingStatsRecorder,
) : ViewModel() {

    private val selectedRange = MutableStateFlow(ViewingStatsRange.LAST_7_DAYS)
    private val _uiState = MutableStateFlow(
        StatisticsUiState(
            range = ViewingStatsRange.LAST_7_DAYS,
            isLoading = true,
            snapshot = ViewingStatsSnapshot.empty(ViewingStatsRange.LAST_7_DAYS, null),
        )
    )
    private val loadMutex = Mutex()
    private val _uiStateReadOnly: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var bucketDetailJob: Job? = null

    val uiState: StateFlow<StatisticsUiState> = _uiStateReadOnly

    init {
        viewModelScope.launch {
            selectedRange.collectLatest { range ->
                load(range, flushRecorder = true)
            }
        }
        viewModelScope.launch {
            repository.observeChanges()
                .drop(1)
                .collectLatest {
                    load(selectedRange.value, showLoading = false, flushRecorder = false)
                }
        }
    }

    fun selectRange(range: ViewingStatsRange) {
        bucketDetailJob?.cancel()
        selectedRange.value = range
        _uiState.value = _uiState.value.copy(selectedBucketIndex = -1, selectedBucketDetail = null)
    }

    fun selectBucket(index: Int) {
        val snapshot = _uiState.value.snapshot
        val bucket = snapshot.timeline.getOrNull(index) ?: return
        val bucketStart = bucket.startAt
        val bucketEnd = bucket.endAt
        bucketDetailJob?.cancel()
        _uiState.value = _uiState.value.copy(selectedBucketIndex = index, selectedBucketDetail = null)
        bucketDetailJob = viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) {
                repository.loadBucketDetail(bucketStart, bucketEnd, TimeZone.getDefault())
            }
            currentCoroutineContext().ensureActive()
            val currentBucket = _uiState.value.snapshot.timeline.getOrNull(_uiState.value.selectedBucketIndex)
            if (currentBucket?.startAt == bucketStart && currentBucket.endAt == bucketEnd) {
                _uiState.value = _uiState.value.copy(selectedBucketDetail = detail)
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive != true) {
            refreshJob = viewModelScope.launch {
                load(selectedRange.value, flushRecorder = true)
            }
        }
    }

    /** Runs from the visible Fragment's lifecycle for periodic silent updates. */
    suspend fun refreshSilently() {
        load(selectedRange.value, showLoading = false, flushRecorder = false)
    }

    fun resetStatistics() {
        viewModelScope.launch {
            loadMutex.withLock {
                _uiState.value = _uiState.value.copy(isLoading = true)
                withContext(Dispatchers.IO) {
                    recorder.reset()
                    recorder.awaitIdle()
                }
                loadLocked(selectedRange.value, flushRecorder = false)
            }
        }
    }

    private suspend fun load(
        range: ViewingStatsRange,
        showLoading: Boolean = true,
        flushRecorder: Boolean,
    ) {
        loadMutex.withLock {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(range = range, isLoading = true)
            }
            loadLocked(range, flushRecorder)
        }
    }

    private suspend fun loadLocked(range: ViewingStatsRange, flushRecorder: Boolean) {
        val snapshot = withContext(Dispatchers.IO) {
            if (flushRecorder) recorder.flush()
            repository.loadStatistics(
                range = range,
                timeZone = TimeZone.getDefault(),
            )
        }
        val previousState = _uiState.value
        val previousBucket = previousState.snapshot.timeline.getOrNull(previousState.selectedBucketIndex)
        val selectedBucketIndex = previousBucket?.let { bucket ->
            snapshot.timeline.indexOfFirst { it.startAt == bucket.startAt && it.endAt == bucket.endAt }
        }?.takeIf { it >= 0 } ?: -1
        _uiState.value = StatisticsUiState(
            range = range,
            isLoading = false,
            snapshot = snapshot,
            selectedBucketIndex = selectedBucketIndex,
            selectedBucketDetail = if (selectedBucketIndex >= 0) previousState.selectedBucketDetail else null,
        )
    }

    companion object {
        val StatisticsViewModelFactory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as XtraApp
                val module = application.xtraModule
                StatisticsViewModel(
                    repository = module.viewingStatsRepository,
                    recorder = module.viewingStatsRecorder,
                )
            }
        }
    }
}

data class StatisticsUiState(
    val range: ViewingStatsRange,
    val isLoading: Boolean,
    val snapshot: ViewingStatsSnapshot,
    val selectedBucketIndex: Int = -1,
    val selectedBucketDetail: com.github.andreyasadchy.xtra.repository.ViewingStatsDetailSnapshot? = null,
)
