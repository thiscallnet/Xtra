package com.github.andreyasadchy.xtra.ui.player

internal fun shouldResolveFreshStreamForResumption(
    contentType: String?,
    isForPlayback: Boolean,
): Boolean = contentType == BasePlaybackService.STREAM && isForPlayback
