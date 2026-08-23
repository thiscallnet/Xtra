package com.github.andreyasadchy.xtra.repository.preload

object StreamPreviewLifecyclePolicy {
    const val OFFSCREEN_GRACE_MS = 650L
}

/** Keeps player ownership independent from a temporarily recycled PlayerView. */
class StreamPreviewLifecycle(
    private val offscreenGraceMs: Long = StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS,
) {
    private val entries = linkedMapOf<String, Entry>()

    fun track(identity: String, nowMs: Long) {
        val normalized = StreamPreviewSelectionPolicy.normalizeIdentity(identity)
        if (normalized.isEmpty()) return
        if (!entries.containsKey(normalized)) {
            entries[normalized] = Entry(lastVisibleAtMs = nowMs)
        }
    }

    fun observeVisible(visibleIdentities: Collection<String>, nowMs: Long) {
        val visible = visibleIdentities
            .mapTo(mutableSetOf(), StreamPreviewSelectionPolicy::normalizeIdentity)
        entries.keys.toList().forEach { identity ->
            val entry = entries[identity] ?: return@forEach
            entries[identity] = if (identity in visible) {
                entry.copy(lastVisibleAtMs = nowMs, offscreenSinceMs = null)
            } else {
                entry.copy(offscreenSinceMs = entry.offscreenSinceMs ?: nowMs)
            }
        }
    }

    fun expire(nowMs: Long) {
        entries.entries.toList().forEach { (identity, entry) ->
            if (entry.offscreenSinceMs?.let { nowMs - it >= offscreenGraceMs } == true) {
                entries.remove(identity)
            }
        }
    }

    /** The coordinator uses this to wake up when no viewport event is expected. */
    fun nextExpiryAtMs(): Long? {
        var nextExpiry: Long? = null
        entries.values.forEach { entry ->
            val offscreenSinceMs = entry.offscreenSinceMs ?: return@forEach
            val expiryAtMs = offscreenSinceMs + offscreenGraceMs
            val currentNextExpiry = nextExpiry
            if (currentNextExpiry == null || expiryAtMs < currentNextExpiry) nextExpiry = expiryAtMs
        }
        return nextExpiry
    }

    fun onScrolling() {
        // Scrolling changes visibility; it is not a release event.
    }

    fun failed(identity: String) {
        entries.remove(StreamPreviewSelectionPolicy.normalizeIdentity(identity))
    }

    fun retainOnly(identity: String) {
        val normalized = StreamPreviewSelectionPolicy.normalizeIdentity(identity)
        entries.keys.retainAll { it == normalized }
    }

    fun clear() = entries.clear()

    fun activeIdentities(): Set<String> = entries.keys.toSet()

    private data class Entry(
        val lastVisibleAtMs: Long,
        val offscreenSinceMs: Long? = null,
    )
}

/** Schedules the coordinator's next lifecycle reconciliation even without viewport updates. */
class StreamPreviewLifecycleReconciler(
    private val lifecycle: StreamPreviewLifecycle,
    private val schedule: (delayMs: Long, callback: () -> Unit) -> (() -> Unit),
    private val onExpired: () -> Unit,
) {
    private var scheduledExpiryAtMs: Long? = null
    private var cancelScheduled: (() -> Unit)? = null

    fun reconcile(nowMs: Long, additionalDeadlines: Collection<Long> = emptyList()) {
        var expiryAtMs = lifecycle.nextExpiryAtMs()
        additionalDeadlines.forEach { deadline ->
            val currentExpiryAtMs = expiryAtMs
            if (currentExpiryAtMs == null || deadline < currentExpiryAtMs) expiryAtMs = deadline
        }
        if (expiryAtMs == null) {
            cancel()
            return
        }
        if (expiryAtMs == scheduledExpiryAtMs && cancelScheduled != null) return

        cancel()
        scheduledExpiryAtMs = expiryAtMs
        cancelScheduled = schedule((expiryAtMs - nowMs).coerceAtLeast(0L)) {
            scheduledExpiryAtMs = null
            cancelScheduled = null
            onExpired()
        }
    }

    fun cancel() {
        cancelScheduled?.invoke()
        cancelScheduled = null
        scheduledExpiryAtMs = null
    }
}
