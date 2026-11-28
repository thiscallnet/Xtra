package com.github.andreyasadchy.xtra.ui.chat.v2.session

import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsField
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFieldKey
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsPhase
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatCommunityGift
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscriptionNoticeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Lossless canonical event ingress. Only UI snapshots may be conflated. */
class ChatEventProcessor(
    scope: CoroutineScope,
    private val store: ChatTimelineStore,
    private val allowMessage: suspend (com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage) -> Boolean = { true },
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val compressCommunityGiftRecipients: suspend () -> Boolean = { false },
) {
    private data class Envelope(val key: ChatSessionKey, val event: ChatEvent)
    private sealed interface Command {
        data class Activate(val key: ChatSessionKey, val acknowledged: CompletableDeferred<Unit>) : Command
        data class Event(val value: Envelope) : Command
        data class Reconcile(val key: ChatSessionKey, val recent: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>, val acknowledged: CompletableDeferred<Unit>) : Command
        data class Deactivate(val acknowledged: CompletableDeferred<Unit>) : Command
    }
    private val events = Channel<Command>(capacity = 4096)
    private val seen = LinkedHashSet<String>(4096)
    private val desiredKey = AtomicReference<ChatSessionKey?>(null)
    private val processorJob: Job
    private var pipelineEventCount = 0L
    private var pipelineMessageCount = 0L
    private var pipelineDuplicateCount = 0L
    private var pipelineFilteredCount = 0L
    private var pipelineAcceptedCount = 0L
    private var pipelineReportAtMs = 0L

    init {
        processorJob = scope.launch {
            var activeKey: ChatSessionKey? = null
            val pendingCommunityGifts = ArrayDeque<ChatCommunityGift>()
            for (command in events) {
                val desired = desiredKey.get()
                if (desired != null && desired != activeKey) {
                    reportPipelineSummary(force = true)
                    resetPipelineSummary()
                    activeKey = desired
                    seen.clear()
                    pendingCommunityGifts.clear()
                    store.apply(TimelineOperation.Replace(emptyList()))
                }
                when (command) {
                    is Command.Activate -> command.acknowledged.complete(Unit)
                    is Command.Deactivate -> {
                        reportPipelineSummary(force = true)
                        activeKey = null
                        seen.clear()
                        pendingCommunityGifts.clear()
                        resetPipelineSummary()
                        store.apply(TimelineOperation.Replace(emptyList()))
                        command.acknowledged.complete(Unit)
                    }
                    is Command.Event -> {
                        if (command.value.key != activeKey) continue
                        val event = command.value.event
                        val trackDiagnostics = diagnosticsLogger?.isEnabled == true
                        if (!trackDiagnostics) resetPipelineSummary(0L)
                        if (trackDiagnostics) {
                            pipelineEventCount++
                            if (event is ChatEvent.Message) pipelineMessageCount++
                        }
                        val dedupeKey = eventDedupeKey(event)
                        if (dedupeKey != null && !seen.add(dedupeKey)) {
                            if (trackDiagnostics) {
                                pipelineDuplicateCount++
                                reportPipelineSummary()
                            }
                            continue
                        }
                        if (seen.size > 4096) seen.remove(seen.first())
                        if (event is ChatEvent.CommunityGift) {
                            val compressGifts = compressCommunityGiftRecipients()
                            if (compressGifts && event.gift.count >= COMMUNITY_GIFT_COMPRESSION_MIN_COUNT) {
                                if (pendingCommunityGifts.size >= MAX_PENDING_COMMUNITY_GIFTS) {
                                    pendingCommunityGifts.removeFirst()
                                }
                                pendingCommunityGifts.addLast(event.gift)
                            } else if (!compressGifts) {
                                pendingCommunityGifts.clear()
                            }
                            while (pendingCommunityGifts.isNotEmpty() &&
                                event.receivedAtMs - pendingCommunityGifts.first().occurredAt > COMMUNITY_GIFT_MATCH_WINDOW_MS
                            ) {
                                pendingCommunityGifts.removeFirst()
                            }
                        }
                        val compressedRecipient = if (event is ChatEvent.Message && isCommunityGiftRecipient(event.message)) {
                            if (compressCommunityGiftRecipients()) {
                                consumeCommunityGiftRecipient(event.message, pendingCommunityGifts)
                            } else {
                                pendingCommunityGifts.clear()
                                false
                            }
                        } else {
                            false
                        }
                        if (compressedRecipient) {
                            if (trackDiagnostics) pipelineFilteredCount++
                        } else if (event !is ChatEvent.Message || allowMessage(event.message)) {
                            store.accept(event)
                            if (trackDiagnostics && event is ChatEvent.Message) pipelineAcceptedCount++
                        } else {
                            if (trackDiagnostics) pipelineFilteredCount++
                        }
                        if (trackDiagnostics) reportPipelineSummary()
                    }
                    is Command.Reconcile -> {
                        if (command.key == activeKey) {
                            val allowed = command.recent.filter { allowMessage(it) }
                            store.apply(TimelineOperation.Reconcile(allowed))
                        }
                        command.acknowledged.complete(Unit)
                    }
                }
            }
        }
        // Wake control senders and their acknowledgements if the parent/session dies before the
        // actor can consume their command. In particular, close() must not wait forever on an
        // acknowledgement from an actor that was already cancelled with its parent.
        processorJob.invokeOnCompletion {
            events.close()
        }
    }

    private fun reportPipelineSummary(force: Boolean = false) {
        val logger = diagnosticsLogger?.takeIf { it.isEnabled } ?: return
        if (pipelineEventCount == 0L) return
        val nowMs = System.nanoTime() / 1_000_000L
        if (!force && pipelineReportAtMs != 0L && nowMs - pipelineReportAtMs < CHAT_DIAGNOSTIC_INTERVAL_MS) return
        logger.event(
            category = DiagnosticsCategory.CHAT,
            severity = DiagnosticsSeverity.INFO,
            transport = DiagnosticsTransport.LOCAL,
            operation = "chat_pipeline",
            event = "event_summary",
            phase = DiagnosticsPhase.EVENT,
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.REQUEST_TYPE, "v2"),
                DiagnosticsField(DiagnosticsFieldKey.COUNT, pipelineEventCount.toString()),
                DiagnosticsField(DiagnosticsFieldKey.MESSAGE_COUNT, pipelineMessageCount.toString()),
                DiagnosticsField(DiagnosticsFieldKey.DUPLICATE_COUNT, pipelineDuplicateCount.toString()),
                DiagnosticsField(DiagnosticsFieldKey.FILTERED_COUNT, pipelineFilteredCount.toString()),
                DiagnosticsField(DiagnosticsFieldKey.ACCEPTED_COUNT, pipelineAcceptedCount.toString()),
            ),
        )
        resetPipelineSummary(nowMs)
    }

    private fun resetPipelineSummary(nowMs: Long = System.nanoTime() / 1_000_000L) {
        pipelineEventCount = 0L
        pipelineMessageCount = 0L
        pipelineDuplicateCount = 0L
        pipelineFilteredCount = 0L
        pipelineAcceptedCount = 0L
        pipelineReportAtMs = nowMs
    }

    /** Match EventSub's explicit bundle ID first; IRC falls back to the same gifter and gift count. */
    private fun consumeCommunityGiftRecipient(
        message: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage,
        pending: ArrayDeque<ChatCommunityGift>,
    ): Boolean {
        val details = message.subscription ?: return false
        if (!isCommunityGiftRecipient(message)) return false

        while (pending.isNotEmpty() && message.timestampMs - pending.first().occurredAt > COMMUNITY_GIFT_MATCH_WINDOW_MS) {
            pending.removeFirst()
        }
        val exactGiftId = details.communityGiftId
        val gift = if (!exactGiftId.isNullOrBlank()) {
            pending.firstOrNull { it.stableId == exactGiftId }
        } else {
            pending.firstOrNull { pendingGift -> pendingGift.matchesGifters(message.user?.id, message.user?.login, message.user?.displayName) }
        } ?: return false

        val remaining = (gift.count - 1).coerceAtLeast(0)
        pending.remove(gift)
        if (remaining > 0) pending.addFirst(gift.copy(count = remaining))
        return true
    }

    private fun isCommunityGiftRecipient(message: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage): Boolean {
        val details = message.subscription ?: return false
        return !details.isCommunityGift && !details.recipientName.isNullOrBlank() &&
            ChatSubscriptionNoticeTypes.isGift(message.noticeType)
    }

    private companion object {
        const val CHAT_DIAGNOSTIC_INTERVAL_MS = 60_000L
        const val COMMUNITY_GIFT_COMPRESSION_MIN_COUNT = 5
        const val COMMUNITY_GIFT_MATCH_WINDOW_MS = 30_000L
        const val MAX_PENDING_COMMUNITY_GIFTS = 128
    }

    /**
     * Protocol event IDs are not one global namespace. A message ID can also be the target of
     * its delete event, while a clear-user payload commonly contains only the user ID. Clear-user
     * is idempotent, so it deliberately has no dedupe key until a transport supplies a distinct
     * protocol delivery ID.
     */
    private fun eventDedupeKey(event: ChatEvent): String? = when (event) {
        is ChatEvent.Message -> event.message.rewardRedemptionId?.let { "reward:$it" }
            ?: event.eventId?.let { "message:$it" }
        is ChatEvent.Notice -> event.eventId?.let { "notice:$it" }
        is ChatEvent.Delete -> "delete:${event.eventId ?: event.messageId.value}"
        is ChatEvent.ClearUser -> null
        is ChatEvent.Clear -> event.eventId?.let { "clear:$it" }
        is ChatEvent.SettingsUpdated -> event.eventId?.let { "settings:$it" }
        is ChatEvent.DecorationUpdated -> null
        is ChatEvent.CommunityGift -> event.eventId?.let { "community-gift:$it" }
        is ChatEvent.TransportDisconnected -> null
    }

    val isActive: Boolean
        get() = processorJob.isActive

    private fun acknowledgement(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { acknowledgement ->
            processorJob.invokeOnCompletion { acknowledgement.complete(Unit) }
        }

    private suspend fun sendControl(command: Command, acknowledgement: CompletableDeferred<Unit>) {
        if (!processorJob.isActive) return
        try {
            events.send(command)
        } catch (_: ClosedSendChannelException) {
            if (processorJob.isActive) throw IllegalStateException("Chat event processor closed")
            return
        } catch (e: CancellationException) {
            if (processorJob.isActive) throw e
            return
        }
        acknowledgement.await()
    }

    suspend fun activate(key: ChatSessionKey) {
        desiredKey.set(key)
        val acknowledged = acknowledgement()
        sendControl(Command.Activate(key, acknowledged), acknowledged)
    }

    suspend fun deactivate() {
        desiredKey.set(null)
        if (!processorJob.isActive) return
        val acknowledged = acknowledgement()
        sendControl(Command.Deactivate(acknowledged), acknowledged)
    }

    suspend fun submit(key: ChatSessionKey, event: ChatEvent) {
        if (desiredKey.get() == key) events.send(Command.Event(Envelope(key, event)))
    }

    suspend fun reconcile(key: ChatSessionKey, recent: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>) {
        if (desiredKey.get() != key) return
        if (!processorJob.isActive) return
        val acknowledged = acknowledgement()
        sendControl(Command.Reconcile(key, recent, acknowledged), acknowledged)
    }
}

private fun ChatCommunityGift.matchesGifters(userId: String?, login: String?, displayName: String?): Boolean {
    if (isAnonymous) {
        return login.equals("ananonymousgifter", ignoreCase = true) ||
            displayName.equals("AnAnonymousGifter", ignoreCase = true)
    }
    if (gifterUserId != null && userId != null) return gifterUserId == userId
    if (!gifterLogin.isNullOrBlank() && !login.isNullOrBlank()) return gifterLogin.equals(login, ignoreCase = true)
    return !gifterDisplayName.isNullOrBlank() && !displayName.isNullOrBlank() &&
        gifterDisplayName.equals(displayName, ignoreCase = true)
}
