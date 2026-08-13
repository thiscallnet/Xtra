package com.github.andreyasadchy.xtra.repository

/** Private Twitch web operations used for viewer participation. */
internal object TwitchGqlOperations {
    const val CHANNEL_POINTS_PREDICTION_CONTEXT_NAME = "ChannelPointsPredictionContext"
    // Private Twitch web operation used by the channel page to discover the
    // current prediction for viewers who are not the broadcaster.
    const val CHANNEL_POINTS_PREDICTION_CONTEXT_HASH = "beb846598256b75bd7c1fe54a80431335996153e358ca9c7837ce7bb83d7d383"

    const val MAKE_PREDICTION_NAME = "MakePrediction"
    // Current Twitch web clients use this persisted MakePrediction document.
    const val MAKE_PREDICTION_HASH = "b44682ecc88358817009f20e69d75081b1e58825bb40aa53d5dbadcc17c881d8"

    const val VOTE_IN_POLL_NAME = "VoteInPoll"
    // Intentionally supports the normal/free viewer vote only. Extra paid
    // votes use VoteInPollInput.tokens and need separate modeling.
    val VOTE_IN_POLL_QUERY = """
        mutation VoteInPoll(${ '$' }input: VoteInPollInput!) {
            voteInPoll(input: ${ '$' }input) {
                error { code }
            }
        }
    """.trimIndent()
}
