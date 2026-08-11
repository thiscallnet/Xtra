package com.github.andreyasadchy.xtra.model.chat

data class Poll(
    val id: String?,
    val title: String?,
    val status: String?,
    val choices: List<PollChoice>?,
    val totalVotes: Int?,
    val remainingMilliseconds: Long?,
    val channelPointsVotingEnabled: Boolean = false,
    val channelPointsPerVote: Int? = null,
    val bitsVotingEnabled: Boolean = false,
    val bitsPerVote: Int? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val endedAt: Long? = null,
    val durationSeconds: Int? = null,
    /** The time at which Xtra observed this snapshot, used to reject stale updates. */
    val observedAt: Long? = null,
) {
    data class PollChoice(
        val id: String?,
        val title: String?,
        val totalVotes: Int?,
        val channelPointsVotes: Int? = null,
        val bitsVotes: Int? = null,
    )
}
