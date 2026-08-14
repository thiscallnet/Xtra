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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    val uiState: StateFlow<StatisticsUiState> = _uiStateReadOnly

    init {
        viewModelScope.launch {
            selectedRange.collectLatest { range ->
                load(range)
            }
        }
    }

    fun selectRange(range: ViewingStatsRange) {
        selectedRange.value = range
    }

    fun refresh() {
        if (refreshJob?.isActive != true) {
            refreshJob = viewModelScope.launch {
                load(selectedRange.value)
            }
        }
    }

    /** Runs from the visible Fragment's lifecycle for periodic silent updates. */
    suspend fun refreshSilently() {
        load(selectedRange.value, showLoading = false)
    }

    fun resetStatistics() {
        viewModelScope.launch {
            loadMutex.withLock {
                _uiState.value = _uiState.value.copy(isLoading = true)
                withContext(Dispatchers.IO) {
                    recorder.reset()
                    recorder.awaitIdle()
                }
                loadLocked(selectedRange.value)
            }
        }
    }

    private suspend fun load(range: ViewingStatsRange, showLoading: Boolean = true) {
        loadMutex.withLock {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(range = range, isLoading = true)
            }
            loadLocked(range)
        }
    }

    private suspend fun loadLocked(range: ViewingStatsRange) {
        val snapshot = withContext(Dispatchers.IO) {
            recorder.flush()
            repository.loadStatistics(
                range = range,
                timeZone = TimeZone.getDefault(),
            )
        }
        _uiState.value = StatisticsUiState(
            range = range,
            isLoading = false,
            snapshot = snapshot,
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
)
