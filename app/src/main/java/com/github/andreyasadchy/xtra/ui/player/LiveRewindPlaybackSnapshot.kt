package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters

internal data class LiveRewindPlaybackSnapshot(
    val mediaItem: MediaItem,
    val liveStreamExtras: Bundle,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val volume: Float,
    val playbackSpeed: Float,
    val trackSelectionParameters: TrackSelectionParameters,
    val proxyMediaPlaylist: Boolean,
    val liveRewindActive: Boolean,
    val liveRewindVodId: String?,
)
