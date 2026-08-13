package com.github.andreyasadchy.xtra.model.chat

data class PollVoteState(
    val pollId: String? = null,
    val selectedChoiceId: String? = null,
    val pendingChoiceId: String? = null,
    val inFlight: Boolean = false,
    val error: String? = null,
)
