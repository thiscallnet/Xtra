package com.github.andreyasadchy.xtra.ui.player

internal fun shouldResolveFreshStreamForResumption(
    contentType: String?,
    playWhenReady: Boolean,
): Boolean = contentType == BasePlaybackService.STREAM && playWhenReady
