package com.github.andreyasadchy.xtra.util

import java.util.Locale

/**
 * Process-local state for the debug runtime diagnostic.
 *
 * This is deliberately separate from the persisted player preference. The diagnostic must
 * answer whether a player is currently attached to the foreground activity, not which backend
 * the next player would use. State is owned by an Activity instance so an outgoing Activity
 * cannot clear a replacement Activity's state during an overlapping restart.
 */
internal object PlaybackRuntimeDiagnostic {
    private data class State(
        val owner: Any,
        val backend: PlaybackBackend,
    )

    @Volatile
    private var state: State? = null

    @Synchronized
    fun setActive(owner: Any, backend: PlaybackBackend) {
        state = State(owner, backend)
    }

    @Synchronized
    fun clear(owner: Any) {
        if (state?.owner === owner) {
            state = null
        }
    }

    fun currentBackendName(): String =
        state?.backend?.name?.lowercase(Locale.ROOT) ?: "none"
}
