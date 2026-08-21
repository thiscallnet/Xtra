package com.github.andreyasadchy.xtra.ui.player

/**
 * Keeps a pending background-video surface restoration until the UI has
 * successfully taken ownership of the restoration.
 */
internal class VideoOutputState {
    private var detachedForBackground = false

    fun markDetachedForBackground() {
        detachedForBackground = true
    }

    fun restoreIfNeeded(restore: () -> Boolean): Boolean {
        if (!detachedForBackground || !restore()) {
            return false
        }
        detachedForBackground = false
        return true
    }

    fun clear() {
        detachedForBackground = false
    }
}
