package com.github.andreyasadchy.xtra.repository.preload

import androidx.media3.common.MediaItem

/** Keeps the exact MediaItem handed from the preload manager to primary playback alive. */
internal class StreamMedia3PlaybackOwnership {
    private var primaryMediaItem: MediaItem? = null

    fun setPrimaryMediaItem(mediaItem: MediaItem?): Boolean {
        val changed = primaryMediaItem !== mediaItem
        primaryMediaItem = mediaItem
        return changed
    }

    fun currentMediaItem(): MediaItem? = primaryMediaItem

    fun protects(mediaItem: MediaItem): Boolean = primaryMediaItem === mediaItem

    fun release(): Boolean = setPrimaryMediaItem(null)
}
