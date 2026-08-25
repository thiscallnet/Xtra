package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.util.chat.ChannelPointsBalanceEvent

/**
 * Keeps the viewer's balance live while GQL catches up.
 *
 * A delta is retained as a pending adjustment until a matching absolute
 * snapshot arrives. That is what prevents an older ChannelPointsContext
 * response, or the matching Hermes spend notification, from undoing or
 * repeating a change already shown to the user.
 */
class ChannelPointsBalanceReducer(
    private val pendingAdjustmentWindowMillis: Long = DEFAULT_PENDING_ADJUSTMENT_WINDOW_MILLIS,
) {
    data class PendingAdjustment(
        val channelId: String,
        val type: ChannelPointsBalanceEvent.Type,
        val amount: Int,
        val appliedAtMs: Long,
        val transactionId: String? = null,
    )

    data class State(
        val balance: Int? = null,
        val revision: Long = 0L,
        val pendingAdjustments: List<PendingAdjustment> = emptyList(),
        val handledMessageIds: Set<String> = emptySet(),
    )

    fun applyLiveEvent(
        state: State,
        event: ChannelPointsBalanceEvent,
        nowMs: Long,
    ): State {
        val messageId = event.messageId?.takeIf { it.isNotBlank() }
        if (messageId != null && messageId in state.handledMessageIds) return state

        val handledMessageIds = messageId?.let {
            (state.handledMessageIds + it).toList().takeLast(MAX_HANDLED_MESSAGE_IDS).toSet()
        } ?: state.handledMessageIds
        val activePending = pruneExpired(state.pendingAdjustments, nowMs)
        val nextRevision = state.revision + 1L
        val absoluteBalance = event.absoluteBalance
        if (absoluteBalance != null) {
            return state.copy(
                balance = absoluteBalance,
                revision = nextRevision,
                pendingAdjustments = if (event.channelId == null) {
                    emptyList()
                } else {
                    activePending.filter { it.channelId != event.channelId }
                },
                handledMessageIds = handledMessageIds,
            )
        }

        val amount = event.delta?.takeIf { it > 0 }
        if (amount == null || event.channelId.isNullOrBlank()) {
            return state.copy(
                revision = nextRevision,
                pendingAdjustments = activePending,
                handledMessageIds = handledMessageIds,
            )
        }

        val matchingPending = activePending.firstOrNull { pending ->
            pending.channelId == event.channelId &&
                pending.type == event.type &&
                pending.amount == amount &&
                ((event.transactionId != null && pending.transactionId != null &&
                    event.transactionId == pending.transactionId) ||
                    nowMs - pending.appliedAtMs in 0..pendingAdjustmentWindowMillis)
        }
        val nextPending = if (matchingPending != null) {
            activePending - matchingPending
        } else {
            activePending + PendingAdjustment(
                channelId = event.channelId,
                type = event.type,
                amount = amount,
                appliedAtMs = nowMs,
                transactionId = event.transactionId,
            )
        }
        val nextBalance = if (matchingPending == null) {
            state.balance?.let { balance -> applyDelta(balance, event.type, amount) }
        } else {
            state.balance
        }
        return state.copy(
            balance = nextBalance,
            revision = nextRevision,
            pendingAdjustments = nextPending,
            handledMessageIds = handledMessageIds,
        )
    }

    fun applyLocalSpend(
        state: State,
        channelId: String,
        amount: Int,
        nowMs: Long,
        transactionId: String? = null,
    ): State {
        if (channelId.isBlank() || amount <= 0) return state
        val activePending = pruneExpired(state.pendingAdjustments, nowMs)
        return state.copy(
            balance = state.balance?.let { (it - amount).coerceAtLeast(0) },
            revision = state.revision + 1L,
            pendingAdjustments = activePending + PendingAdjustment(
                channelId = channelId,
                type = ChannelPointsBalanceEvent.Type.SPENT,
                amount = amount,
                appliedAtMs = nowMs,
                transactionId = transactionId,
            ),
        )
    }

    /**
     * Applies a GQL balance only when it is not an older view of a live
     * mutation. Catalog data can still be merged by the caller in either
     * case.
     */
    fun applySnapshot(
        state: State,
        snapshotBalance: Int,
        nowMs: Long,
        requestRevision: Long? = null,
    ): State {
        if (requestRevision != null && requestRevision < state.revision) return state
        val activePending = pruneExpired(state.pendingAdjustments, nowMs)
        if (activePending.isEmpty()) {
            return state.copy(
                balance = snapshotBalance,
                revision = state.revision + 1L,
                pendingAdjustments = emptyList(),
            )
        }

        val currentBalance = state.balance
        val pendingDelta = activePending.sumOf { signedDelta(it.type, it.amount) }
        val preMutationBalance = currentBalance?.let { it - pendingDelta }
        val pendingExpired = activePending.all {
            nowMs - it.appliedAtMs > pendingAdjustmentWindowMillis
        }
        return when {
            snapshotBalance == currentBalance -> state.copy(
                balance = snapshotBalance,
                revision = state.revision + 1L,
                // Keep the short-lived adjustment so a Hermes confirmation
                // that arrives after this snapshot cannot subtract twice.
                pendingAdjustments = activePending,
            )
            currentBalance == null && !pendingExpired -> state.copy(
                balance = (snapshotBalance + pendingDelta).coerceAtLeast(0),
                revision = state.revision + 1L,
                pendingAdjustments = activePending,
            )
            !pendingExpired && snapshotBalance == preMutationBalance -> state
            !pendingExpired -> state
            else -> state.copy(
                balance = snapshotBalance,
                revision = state.revision + 1L,
                pendingAdjustments = emptyList(),
            )
        }
    }

    fun reset(): State = State()

    private fun pruneExpired(
        pending: List<PendingAdjustment>,
        nowMs: Long,
    ): List<PendingAdjustment> = pending.filter {
        nowMs - it.appliedAtMs <= pendingAdjustmentWindowMillis
    }

    private fun signedDelta(type: ChannelPointsBalanceEvent.Type, amount: Int): Int = when (type) {
        ChannelPointsBalanceEvent.Type.EARNED -> amount
        ChannelPointsBalanceEvent.Type.SPENT -> -amount
    }

    private fun applyDelta(
        balance: Int,
        type: ChannelPointsBalanceEvent.Type,
        amount: Int,
    ): Int = when (type) {
        ChannelPointsBalanceEvent.Type.EARNED -> balance + amount
        ChannelPointsBalanceEvent.Type.SPENT -> (balance - amount).coerceAtLeast(0)
    }

    private companion object {
        const val DEFAULT_PENDING_ADJUSTMENT_WINDOW_MILLIS = 30_000L
        const val MAX_HANDLED_MESSAGE_IDS = 200
    }
}
