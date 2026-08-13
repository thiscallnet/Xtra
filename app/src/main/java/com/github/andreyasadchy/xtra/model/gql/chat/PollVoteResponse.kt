package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.GqlError
import kotlinx.serialization.Serializable

@Serializable
class PollVoteResponse(
    val errors: List<GqlError>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val voteInPoll: Payload? = null,
    ) {
        fun errorCode(): String? = voteInPoll?.error?.code

        fun hasPayload(): Boolean = voteInPoll != null

        fun hasError(): Boolean = voteInPoll?.error != null
    }

    @Serializable
    class Payload(
        val error: PollVoteError? = null,
    )

    @Serializable
    class PollVoteError(
        val code: String? = null,
    )
}
