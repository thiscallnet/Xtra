package com.github.andreyasadchy.xtra.model.stats

/** A point-in-time copy of the Twitch metadata needed by local viewing statistics. */
data class ViewingPlaybackMetadata(
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImage: String?,
    val contentType: String,
    val contentId: String?,
) {

    val normalizedChannelId: String?
        get() = channelId?.trim()?.takeIf { it.isNotEmpty() }

    fun hasTrackableChannel(): Boolean = normalizedChannelId != null

    fun hasSamePlaybackAs(other: ViewingPlaybackMetadata): Boolean {
        return normalizedChannelId.equals(other.normalizedChannelId, ignoreCase = true) &&
                contentType == other.contentType &&
                contentId == other.contentId
    }

    companion object {
        const val CONTENT_TYPE_LIVE = "live"
        const val CONTENT_TYPE_VOD = "vod"
        const val CONTENT_TYPE_CLIP = "clip"
        const val CONTENT_TYPE_OFFLINE_VIDEO = "offline_video"
    }
}
