package com.github.andreyasadchy.xtra.ui.player

internal fun acceptedWatchStreamId(
    expectedChannelId: String,
    expectedChannelLogin: String,
    currentChannelId: String?,
    currentChannelLogin: String?,
    currentStreamId: String?,
    resolvedStreamId: String?,
): String? {
    if (currentChannelId != expectedChannelId || currentChannelLogin != expectedChannelLogin) {
        return null
    }
    return resolvedStreamId?.takeIf { it.isNotBlank() && it != currentStreamId }
}
