package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class PinnedChatMessageResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val channel: Channel? = null,
    )

    @Serializable
    class Channel(
        val pinnedChatMessages: Connection? = null,
    )

    @Serializable
    class Connection(
        val edges: List<Edge>? = null,
    )

    @Serializable
    class Edge(
        val node: Node? = null,
    )

    @Serializable
    class Node(
        val id: String? = null,
        val pinnedBy: User? = null,
        val pinnedMessage: Message? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val displayName: String? = null,
        val login: String? = null,
        val chatColor: String? = null,
    )

    @Serializable
    class Message(
        val sentAt: String? = null,
        val sender: User? = null,
        val content: Content? = null,
    )

    @Serializable
    class Content(
        val text: String? = null,
    )
}
