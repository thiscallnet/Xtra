package com.github.andreyasadchy.xtra.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap

internal fun visibleImageStartsPerFrame(
    interacting: Boolean,
): Int = if (interacting) {
    1
} else {
    2
}

/**
 * Process-local QoS signal for work that competes with touch, scrolling, and
 * pager transitions. Sources are identity-based because several independent
 * RecyclerViews can be active in one screen.
 */
object UiInteractionGovernor {
    private const val IDLE_SETTLE_MS = 150L

    private val activeSources = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val _isInteracting = MutableStateFlow(false)
    private val imageBudgetLock = Any()
    private var imageBudgetFrame = Long.MIN_VALUE
    private var imageStartsThisFrame = 0

    val isInteracting: StateFlow<Boolean> = _isInteracting.asStateFlow()

    fun setInteracting(source: Any, interacting: Boolean) {
        synchronized(activeSources) {
            if (interacting) activeSources.add(source) else activeSources.remove(source)
            val next = activeSources.isNotEmpty()
            if (_isInteracting.value == next) return
            _isInteracting.value = next
        }
    }

    fun runWhenIdle(scope: CoroutineScope, block: suspend () -> Unit): Job = scope.launch {
        awaitIdle()
        block()
    }

    /**
     * Reserves one optional visible-image start for the current display frame.
     * All feed adapters share this budget, including nested Overview shelves.
     */
    fun tryAcquireVisibleImageStart(frameTimeNanos: Long? = null): Boolean {
        // Callers draining work from postOnAnimation pass Choreographer's
        // actual frame timestamp. Immediate binds use a lightweight fallback
        // until the next display callback rather than installing a perpetual
        // Choreographer observer.
        val frame = frameTimeNanos ?: (System.nanoTime() / FRAME_NANOS)
        synchronized(imageBudgetLock) {
            if (imageBudgetFrame != frame) {
                imageBudgetFrame = frame
                imageStartsThisFrame = 0
            }

            val maxStartsThisFrame =
                visibleImageStartsPerFrame(
                    interacting = _isInteracting.value,
                )

            if (imageStartsThisFrame >= maxStartsThisFrame) return false
            imageStartsThisFrame++
            return true
        }
    }

    suspend fun awaitIdle() {
        while (true) {
            isInteracting.filter { !it }.first()
            delay(IDLE_SETTLE_MS)
            if (!isInteracting.value) return
        }
    }

    private const val FRAME_NANOS = 16_666_667L
}
