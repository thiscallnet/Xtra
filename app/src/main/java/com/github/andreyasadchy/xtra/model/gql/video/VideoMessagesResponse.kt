package com.github.andreyasadchy.xtra.model.gql.video

import com.github.andreyasadchy.xtra.model.gql.Error
import com.github.andreyasadchy.xtra.model.gql.PageInfo
import kotlinx.serialization.Serializable

@Serializable
class VideoMessagesResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val video: Video? = null,
    )

    @Serializable
    class Video(
        val comments: Comments? = null,
    )

    @Serializable
    class Comments(
        val edges: List<Item?>? = null,
        val pageInfo: PageInfo? = null,
    )

    @Serializable
    class Item(
        val node: Comment? = null,
        val cursor: String? = null,
    )

    @Serializable
    class Comment(
        val id: String? = null,
        val contentOffsetSeconds: Int? = null,
        val createdAt: String? = null,
        val message: Message? = null,
        val commenter: Commenter? = null,
    )

    @Serializable
    class Message(
        val fragments: List<Fragment>? = null,
        val userBadges: List<Badge>? = null,
        val userColor: String? = null,
    )

    @Serializable
    class Fragment(
        val text: String? = null,
        val emote: Emote? = null,
    )

    @Serializable
    class Emote(
        val emoteID: String? = null,
    )

    @Serializable
    class Badge(
        val setID: String? = null,
        val version: String? = null,
    )

    @Serializable
    class Commenter(
        val id: String? = null,
        val login: String? = null,
        val displayName: String? = null,
    )
}

val VideoMessagesResponse.Comments.nextCursor: String?
    get() = edges.orEmpty()
        .asReversed()
        .firstNotNullOfOrNull { edge -> edge?.cursor?.takeIf(String::isNotBlank) }

val VideoMessagesResponse.Comments.lastNode: VideoMessagesResponse.Comment?
    get() = edges.orEmpty()
        .asReversed()
        .firstNotNullOfOrNull { edge -> edge?.node }
