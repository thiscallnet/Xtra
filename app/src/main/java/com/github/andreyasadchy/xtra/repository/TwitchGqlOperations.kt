package com.github.andreyasadchy.xtra.repository

/** Private Twitch web operations used for viewer participation. */
internal object TwitchGqlOperations {
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
