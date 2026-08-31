package com.github.andreyasadchy.xtra.ui.player.captions

import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent

/**
 * Keeps delayed caption events ordered while coalescing cumulative partials.
 * This is only accessed by the caption worker thread.
 */
internal class CaptionEventDelayQueue(
    private val capacity: Int = 16,
) {
    private data class PendingEvent(
        var event: CaptionRecognitionEvent,
        val dueAtMs: Long,
    )

    private val pending = ArrayDeque<PendingEvent>()

    fun enqueue(
        event: CaptionRecognitionEvent,
        delayMs: Long,
        nowMs: Long,
        apply: (CaptionRecognitionEvent) -> Unit,
    ) {
        if (delayMs <= 0L) {
            apply(event)
            return
        }

        if (event is CaptionRecognitionEvent.Partial) {
            // Partial text is a cumulative hypothesis. Replace its payload,
            // but keep the original deadline so continuous speech cannot keep
            // postponing the first visible update forever.
            pending.firstOrNull { it.event is CaptionRecognitionEvent.Partial }?.let {
                it.event = event
                return
            }
        }

        if (pending.size >= capacity) {
            pending.removeFirst()
        }
        pending.addLast(
            PendingEvent(
                event = event,
                dueAtMs = nowMs + delayMs,
            ),
        )
    }

    fun drain(
        nowMs: Long,
        apply: (CaptionRecognitionEvent) -> Unit,
    ) {
        while (pending.firstOrNull()?.dueAtMs?.let { it <= nowMs } == true) {
            apply(pending.removeFirst().event)
        }
    }

    fun clear() = pending.clear()
}
