package com.github.andreyasadchy.xtra.repository

/** Private Twitch web operations used for viewer participation. */
internal object TwitchGqlOperations {
    // Private, unsupported Twitch web operation. Its hash can change without notice.
    const val DROPS_INVENTORY_NAME = "Inventory"
    const val DROPS_INVENTORY_HASH =
        "8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404"

    const val DROPS_DASHBOARD_NAME = "ViewerDropsDashboard"
    const val DROPS_DASHBOARD_HASH =
        "d9cae7761dafab85908c85e6683cb4201b449e66ac3bb5e894f15ff12aeafaa7"

    const val DROP_CAMPAIGN_DETAILS_NAME = "DropCampaignDetails"
    const val DROP_CAMPAIGN_DETAILS_HASH =
        "039277bf98f3130929262cc7c6efd9c141ca3749cb6dca442fc8ead9a53f77c1"

    const val AVAILABLE_DROPS_NAME = "DropsHighlightService_AvailableDrops"
    const val AVAILABLE_DROPS_HASH =
        "782dad0f032942260171d2d80a654f88bdd0c5a9dddc392e9bc92218a0f42d20"

    const val CURRENT_DROP_NAME = "DropCurrentSessionContext"
    const val CURRENT_DROP_HASH =
        "4d06b702d25d652afb9ef835d2a550031f1cf762b193523a92166f40ea3d142b"

    // Private, unsupported Twitch web operation. Its hash can change without notice.
    const val CLAIM_DROP_NAME = "DropsPage_ClaimDropRewards"
    const val CLAIM_DROP_HASH =
        "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930"

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
