package com.github.andreyasadchy.xtra.ui.common

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedFreshnessPolicy
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedRefreshCoordinator
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connects a visible stream screen to the app-scoped freshness coordinator.
 * The view remains owned by Room/Paging; this class only supplies user-visible
 * revalidation signals while the fragment is actually resumed.
 */
class StreamFeedScreenController(
    private val fragment: Fragment,
    private val coordinator: StreamFeedRefreshCoordinator,
    private val specProvider: () -> StreamFeedSpec?,
) {
    private var tickerJob: Job? = null

    fun start() {
        tickerJob?.cancel()
        tickerJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(StreamFeedFreshnessPolicy.VISIBLE_REVALIDATION_INTERVAL_MS)
                    if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !coordinator.isPlayerFullscreen) {
                        revalidate(RefreshReason.SCREEN_VISIBLE)
                    }
                }
            }
        }
    }

    fun onResume() {
        if (!coordinator.isPlayerFullscreen) {
            revalidate(RefreshReason.SCREEN_VISIBLE)
        }
    }

    fun onPause() {
        // Keep the last browsing feed available for an activity-foreground or
        // player-return signal. A newly resumed screen replaces it.
    }

    fun onDestroyView() {
        tickerJob?.cancel()
        tickerJob = null
        if (!coordinator.isPlayerFullscreen) {
            specProvider()?.key?.let(coordinator::clearVisibleFeed)
        }
    }

    fun onSpecChanged(force: Boolean, reason: RefreshReason = RefreshReason.FILTER_CHANGED) {
        val spec = specProvider() ?: return
        coordinator.setVisibleFeed(spec)
        if (coordinator.isPlayerFullscreen) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (force) coordinator.forceRefresh(spec, reason)
                else coordinator.maybeRefresh(spec, reason)
            }
        }
    }

    private fun revalidate(reason: RefreshReason) {
        val spec = specProvider() ?: return
        coordinator.setVisibleFeed(spec)
        if (coordinator.isPlayerFullscreen) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching { coordinator.maybeRefresh(spec, reason) }
        }
    }

    private val viewLifecycleOwner
        get() = fragment.viewLifecycleOwner
}
