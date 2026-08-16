package com.github.andreyasadchy.xtra.model.stats

/** The category identity used for one viewing attribution. */
data class ViewingCategoryIdentity(
    val id: String?,
    val name: String?,
)

/**
 * Applies a category metadata patch without ever combining fields from
 * different categories. Category ID and name are one atomic identity pair.
 */
fun mergeViewingCategoryPatch(
    currentId: String?,
    currentName: String?,
    patchId: String?,
    patchName: String?,
): ViewingCategoryIdentity {
    return if (patchId != null && patchName != null) {
        ViewingCategoryIdentity(patchId, patchName)
    } else {
        ViewingCategoryIdentity(currentId, currentName)
    }
}

/** A point-in-time copy of the Twitch metadata needed by local viewing statistics. */
data class ViewingPlaybackMetadata(
    val channelId: String?,
    val channelLogin: String?,
    val channelName: String?,
    val channelImage: String?,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val categoryImage: String? = null,
    val contentType: String,
    val contentId: String?,
    val title: String? = null,
) {

    val normalizedChannelId: String?
        get() = channelId?.trim()?.takeIf { it.isNotEmpty() }

    fun hasTrackableChannel(): Boolean = normalizedChannelId != null

    fun hasSamePlaybackAs(other: ViewingPlaybackMetadata): Boolean {
        return normalizedChannelId.equals(other.normalizedChannelId, ignoreCase = true) &&
                contentType == other.contentType &&
                contentId == other.contentId
    }

    /**
     * A live broadcast can change category without becoming a new playback.
     * Keep that change at interval granularity so one viewing session can still
     * contain several category attributions.
     */
    fun hasSameAttributionAs(other: ViewingPlaybackMetadata): Boolean {
        return categoryKey() == other.categoryKey()
    }

    fun categoryKey(): String? {
        return categoryId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "id:${it.lowercase()}" }
            ?: categoryName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "name:${it.lowercase()}" }
    }

    companion object {
        const val CONTENT_TYPE_LIVE = "live"
        const val CONTENT_TYPE_VOD = "vod"
        const val CONTENT_TYPE_CLIP = "clip"
        const val CONTENT_TYPE_OFFLINE_VIDEO = "offline_video"
        const val CONTENT_TYPE_UNKNOWN = "unknown"
    }
}
