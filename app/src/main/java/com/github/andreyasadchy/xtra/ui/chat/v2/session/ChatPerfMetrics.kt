package com.github.andreyasadchy.xtra.ui.chat.v2.session

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

data class ChatPerfSnapshot(
    val received: Long,
    val stored: Long,
    val queued: Long,
    val snapshotsCreated: Long,
    val adapterCommits: Long,
    val currentBacklog: Long,
    val maxBacklog: Long,
    val lastTransportToStoreNanos: Long,
)

data class ChatEventToken(val rxNanos: Long)

class ChatPerfMetrics {
    private val received = AtomicLong()
    private val stored = AtomicLong()
    private val queued = AtomicLong()
    private val snapshotsCreated = AtomicLong()
    private val adapterCommits = AtomicLong()
    private val currentBacklog = AtomicLong()
    private val maxBacklog = AtomicLong()
    private val lastLatency = AtomicLong()

    fun onTransportReceived(rxNanos: Long = SystemClock.elapsedRealtimeNanos()): ChatEventToken {
        received.incrementAndGet()
        return ChatEventToken(rxNanos)
    }

    fun onProcessorQueued(backlog: Long) {
        queued.incrementAndGet()
        currentBacklog.set(backlog)
        maxBacklog.accumulateAndGet(backlog) { current, value -> maxOf(current, value) }
    }

    fun onStored(token: ChatEventToken, storedNanos: Long = SystemClock.elapsedRealtimeNanos(), backlog: Long) {
        stored.incrementAndGet()
        currentBacklog.set(backlog)
        lastLatency.set((storedNanos - token.rxNanos).coerceAtLeast(0))
        maxBacklog.accumulateAndGet(backlog) { current, value -> maxOf(current, value) }
    }

    fun onSnapshotCreated() { snapshotsCreated.incrementAndGet() }
    fun onAdapterCommitted() { adapterCommits.incrementAndGet() }

    fun snapshot() = ChatPerfSnapshot(
        received.get(), stored.get(), queued.get(), snapshotsCreated.get(), adapterCommits.get(),
        currentBacklog.get(), maxBacklog.get(), lastLatency.get(),
    )
}
