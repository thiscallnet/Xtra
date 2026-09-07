package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The strongest refresh needed by a row before its next frame. */
internal enum class ChatVisualRefreshKind {
    DRAW,
    LAYOUT_AND_DRAW,
}

/**
 * Thread-safe validity for one repository observer. The owner updates the generation on the UI
 * thread when a shared key survives a rebind; repository callbacks only read these atomics.
 */
internal class ChatObserverRegistration(
    initialGeneration: Long,
    onValid: (Long) -> Unit,
    private val onStale: () -> Unit = {},
) {
    private val active = AtomicBoolean(true)
    private val generation = AtomicLong(initialGeneration)

    val listener: () -> Unit = {
        val currentGeneration = generation.takeIf { active.get() }?.get()
        if (currentGeneration == null) onStale() else onValid(currentGeneration)
    }

    fun rebind(nextGeneration: Long) {
        generation.set(nextGeneration)
        active.set(true)
    }

    fun deactivate() {
        active.set(false)
    }
}

/**
 * Coalesces callbacks which arrive between two frames. The generation is part of the request so
 * a callback that was already queued when a holder was rebound cannot refresh the new message.
 */
internal class ChatVisualRefreshCoalescer(
    private val scheduleFrame: (Runnable) -> Unit,
    private val currentGeneration: () -> Long,
    private val onRefresh: (ChatVisualRefreshKind) -> Unit,
    private val onCoalesced: () -> Unit = {},
    private val onStale: () -> Unit = {},
) {
    private val lock = Any()
    private var pendingKind: ChatVisualRefreshKind? = null
    private var pendingGeneration = Long.MIN_VALUE
    private var frameScheduled = false

    fun request(kind: ChatVisualRefreshKind, generation: Long) {
        var shouldSchedule = false
        var coalesced = false
        synchronized(lock) {
            coalesced = frameScheduled && pendingKind != null
            if (pendingGeneration != generation) {
                pendingGeneration = generation
                pendingKind = null
            }
            if (pendingKind == null || kind.ordinal > pendingKind!!.ordinal) {
                pendingKind = kind
            }
            if (!frameScheduled) {
                frameScheduled = true
                shouldSchedule = true
            }
        }
        if (coalesced) onCoalesced()
        if (shouldSchedule) scheduleFrame(Runnable(::dispatch))
    }

    private fun dispatch() {
        val kind: ChatVisualRefreshKind
        val generation: Long
        synchronized(lock) {
            frameScheduled = false
            kind = pendingKind ?: return
            generation = pendingGeneration
            pendingKind = null
            pendingGeneration = Long.MIN_VALUE
        }
        if (generation != currentGeneration()) {
            onStale()
        } else {
            onRefresh(kind)
        }
    }
}
