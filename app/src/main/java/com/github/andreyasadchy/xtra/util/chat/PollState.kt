package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Poll

/**
 * Poll snapshots arrive from more than one transport. Keep the merge rules in
 * one place so a late progress/begin message cannot undo a terminal result.
 */
object PollState {
    private val terminalStatuses = setOf(
        "COMPLETED",
        "TERMINATED",
        "ARCHIVED",
        "MODERATED",
        "INVALID",
        "CANCELED",
    )

    fun isTerminal(poll: Poll?): Boolean = poll?.status?.uppercase() in terminalStatuses

    fun isActive(poll: Poll?, now: Long = System.currentTimeMillis()): Boolean {
        if (poll?.status?.uppercase() != "ACTIVE") return false
        if (poll.endsAt != null && poll.endsAt <= now) return false
        if (poll.endsAt == null && poll.remainingMilliseconds != null && poll.remainingMilliseconds <= 0L) return false
        return true
    }

    fun normalizeCached(poll: Poll, now: Long = System.currentTimeMillis()): Poll {
        if (poll.status?.uppercase() != "ACTIVE") return poll
        return when {
            poll.endsAt != null && poll.endsAt <= now -> poll.copy(
                status = "COMPLETED",
                endedAt = poll.endedAt ?: poll.endsAt,
                remainingMilliseconds = 0L,
            )
            poll.endsAt == null && poll.remainingMilliseconds == null -> poll.copy(
                status = "COMPLETED",
                endedAt = poll.endedAt ?: poll.observedAt,
            )
            else -> poll
        }
    }

    fun merge(current: Poll?, incoming: Poll?): Poll? {
        if (incoming?.id.isNullOrBlank()) return current
        if (current == null || current.id.isNullOrBlank()) return incoming

        if (current.id != incoming.id) {
            return if (isNewerPoll(incoming, current)) incoming else current
        }

        // Polls move forward only once. A delayed begin/progress event must
        // never reopen a poll after a terminal result was observed.
        if (isTerminal(current) && !isTerminal(incoming)) return current
        if (isTerminal(incoming) && !isTerminal(current)) {
            return mergeFields(current, incoming)
        }
        if (isTerminal(current) && !isNewerSnapshot(incoming, current)) return current
        if (incoming.observedAt != null && current.observedAt != null && incoming.observedAt < current.observedAt) {
            return current
        }

        return mergeFields(current, incoming)
    }

    private fun mergeFields(current: Poll, incoming: Poll): Poll {
        return incoming.copy(
            title = incoming.title ?: current.title,
            status = incoming.status ?: current.status,
            choices = mergeChoices(current.choices, incoming.choices),
            totalVotes = max(current.totalVotes, incoming.totalVotes),
            remainingMilliseconds = minRemaining(current.remainingMilliseconds, incoming.remainingMilliseconds),
            channelPointsVotingEnabled = incoming.channelPointsVotingEnabled || current.channelPointsVotingEnabled,
            channelPointsPerVote = incoming.channelPointsPerVote ?: current.channelPointsPerVote,
            bitsVotingEnabled = incoming.bitsVotingEnabled || current.bitsVotingEnabled,
            bitsPerVote = incoming.bitsPerVote ?: current.bitsPerVote,
            startedAt = incoming.startedAt ?: current.startedAt,
            endsAt = incoming.endsAt ?: current.endsAt,
            endedAt = incoming.endedAt ?: current.endedAt,
            durationSeconds = incoming.durationSeconds ?: current.durationSeconds,
            observedAt = incoming.observedAt ?: current.observedAt,
        )
    }

    private fun mergeChoices(current: List<Poll.PollChoice>?, incoming: List<Poll.PollChoice>?): List<Poll.PollChoice>? {
        if (current.isNullOrEmpty()) return incoming
        if (incoming.isNullOrEmpty()) return current
        val currentByKey = current.associateBy { it.id ?: it.title }
        return incoming.map { choice ->
            val previous = currentByKey[choice.id ?: choice.title]
            if (previous == null) choice else choice.copy(
                totalVotes = max(previous.totalVotes, choice.totalVotes),
                channelPointsVotes = max(previous.channelPointsVotes, choice.channelPointsVotes),
                bitsVotes = max(previous.bitsVotes, choice.bitsVotes),
                title = choice.title ?: previous.title,
            )
        }
    }

    private fun max(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> kotlin.math.max(first, second)
    }

    private fun minRemaining(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> kotlin.math.min(first, second)
    }

    private fun isNewerPoll(incoming: Poll, current: Poll): Boolean {
        val incomingStart = incoming.startedAt
        val currentStart = current.startedAt
        if (incomingStart != null && currentStart != null && incomingStart != currentStart) {
            return incomingStart > currentStart
        }
        return isNewerSnapshot(incoming, current)
    }

    private fun isNewerSnapshot(incoming: Poll, current: Poll): Boolean {
        val incomingObserved = incoming.observedAt
        val currentObserved = current.observedAt
        return when {
            incomingObserved != null && currentObserved != null -> incomingObserved >= currentObserved
            incomingObserved != null -> true
            currentObserved != null -> false
            else -> true
        }
    }
}
