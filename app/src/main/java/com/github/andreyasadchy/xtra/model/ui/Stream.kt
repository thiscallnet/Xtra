package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
class Stream(
    var id: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    var channelImageURL: String? = null,
    var gameId: String? = null,
    var gameSlug: String? = null,
    var gameName: String? = null,
    var title: String? = null,
    val thumbnailURL: String? = null,
    var createdAt: String? = null,
    var viewerCount: Int? = null,
    val tags: List<String>? = null,
    /** True only after Twitch's channel-specific AvailableDrops response confirms Drops. */
    var dropsAvailable: Boolean? = null,
    /** Feed refresh generation used to revalidate live preview pixels. */
    @IgnoredOnParcel
    val thumbnailGeneration: Long = 0L,
) : Parcelable {

    val channelImage: String?
        get() = TwitchApiHelper.getProfileImage(channelImageURL)
    val thumbnail: String?
        get() = TwitchApiHelper.getStreamThumbnail(thumbnailURL)

    /** Creates the same stream snapshot with a new live-preview refresh generation. */
    fun withThumbnailGeneration(generation: Long): Stream = Stream(
        id = id,
        channelId = channelId,
        channelLogin = channelLogin,
        channelName = channelName,
        channelImageURL = channelImageURL,
        gameId = gameId,
        gameSlug = gameSlug,
        gameName = gameName,
        title = title,
        thumbnailURL = thumbnailURL,
        createdAt = createdAt,
        viewerCount = viewerCount,
        tags = tags,
        dropsAvailable = dropsAvailable,
        thumbnailGeneration = generation,
    )
}
