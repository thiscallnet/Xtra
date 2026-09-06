package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.repository.ViewingStatsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/**
 * Serializes playback state from every player source into local viewing rows.
 *
 * State is measured with elapsed realtime. Wall-clock values are snapshots used
 * only for calendar grouping, so a timezone or wall-clock change cannot inflate
 * watch time.
 */
class ViewingStatsRecorder(
    private val repository: ViewingStatsStore,
    private val clock: ViewingStatsClock = SystemViewingStatsClock,
    private val checkpointIntervalMs: Long = DEFAULT_CHECKPOINT_INTERVAL_MS,
    scope: CoroutineScope? = null,
) {

    private val ownedScope = scope == null
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Only ordered transitions are sent here. Producer callbacks are
     * non-suspending, so a pathological full-channel stall applies
     * synchronous backpressure to the caller until a slot is available. This
     * is intentional: preserving every semantic transition is more important
     * than returning immediately after the bounded queue is saturated.
     */
    private val commands = Channel<Command>(SEMANTIC_COMMAND_CAPACITY)
    /** Repeated metadata refreshes share one coalesced wake-up. */
    private val refreshWork = Channel<Unit>(Channel.CONFLATED)
    /** Timer wakes are separate so a refresh cannot hide a forced checkpoint. */
    private val timerWork = Channel<Unit>(Channel.CONFLATED)
    private val schedulerWake = Channel<Unit>(Channel.CONFLATED)
    /**
     * There is one synchronous backpressure boundary for semantic commands.
     * Keeping the sender serialized is important: a bounded channel alone is
     * not enough if every full-channel send creates another suspended job.
     */
    private val orderedIngressLock = ReentrantLock()
    private val commandDepth = AtomicInteger(0)
    private val maxCommandDepth = AtomicInteger(0)
    private val activeSourceCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val ingressLock = Any()
    private val latestInputs = mutableMapOf<String, IngressState>()
    private val pendingRefreshes = mutableMapOf<String, Command.StateChanged>()
    private val states = linkedMapOf<String, SourceState>()
    private val worker = this.scope.launch {
        while (isActive) {
            val work = nextWork() ?: break
            when (work) {
                is Work.Semantic -> if (processSemanticCommand(work.command)) break
                Work.Timer -> {
                    drainPendingRefreshes()
                    runCheckpoint(reading(), force = true)
                }
                Work.Refresh -> {
                    drainPendingRefreshes()
                    runCheckpoint(reading())
                }
            }
        }
    }
    private val ticker = this.scope.launch {
        var nextCheckpointAt = NO_DEADLINE
        while (isActive) {
            if (activeSourceCount.get() == 0) {
                nextCheckpointAt = NO_DEADLINE
                schedulerWake.receive()
            } else {
                val now = clock.elapsedRealtime()
                if (nextCheckpointAt == NO_DEADLINE || nextCheckpointAt <= now) {
                    nextCheckpointAt = safeAdd(now, checkpointIntervalMs)
                }
                val waitMs = (nextCheckpointAt - now).coerceAtLeast(1L)
                val schedulerWoke = withTimeoutOrNull(waitMs) {
                    schedulerWake.receive()
                }
                val wakeTime = clock.elapsedRealtime()
                if (schedulerWoke == null || wakeTime >= nextCheckpointAt) {
                    timerWork.trySend(Unit)
                    nextCheckpointAt = safeAdd(wakeTime, checkpointIntervalMs)
                }
            }
        }
    }

    fun update(
        sourceId: String,
        metadata: ViewingPlaybackMetadata?,
        isPlaying: Boolean,
        isBuffering: Boolean,
    ) {
        var isSemantic = false
        var semanticCommand: Command.StateChanged? = null
        orderedIngressLock.lock()
        try {
            val accepted = synchronized(ingressLock) {
                if (closed.get()) {
                    false
                } else {
                    val command = Command.StateChanged(
                        sourceId = sourceId,
                        metadata = metadata,
                        shouldPlay = isPlaying && !isBuffering,
                        reading = reading(),
                    )
                    val previous = latestInputs[sourceId]
                    latestInputs[sourceId] = IngressState(command.metadata, command.shouldPlay)
                    isSemantic = isSemanticTransition(previous, command)
                    if (isSemantic) {
                        pendingRefreshes.remove(sourceId)
                        semanticCommand = command
                    } else {
                        pendingRefreshes[sourceId] = command
                    }
                    true
                }
            }
            if (accepted) {
                // The channel send may block, but ingressLock is deliberately
                // not held here so the worker can drain pending refreshes.
                semanticCommand?.let(::sendOrderedBlocking)
            }
            if (accepted && !isSemantic) {
                refreshWork.trySend(Unit)
            }
        } finally {
            orderedIngressLock.unlock()
        }
    }

    fun release(sourceId: String) {
        orderedIngressLock.lock()
        try {
            val command = synchronized(ingressLock) {
                if (closed.get()) {
                    null
                } else {
                    latestInputs.remove(sourceId)
                    pendingRefreshes.remove(sourceId)
                    Command.SourceReleased(sourceId, reading())
                }
            }
            command?.let(::sendOrderedBlocking)
        } finally {
            orderedIngressLock.unlock()
        }
    }

    /** Deletes persisted rows and creates new zero-based sessions for active sources. */
    suspend fun reset() {
        val completed = CompletableDeferred<Unit>()
        sendOrdered(Command.Reset(reading(), completed))
        completed.await()
    }

    suspend fun awaitIdle() {
        val completed = CompletableDeferred<Unit>()
        sendOrdered(Command.Barrier(completed))
        completed.await()
    }

    /** Persists the current playback baseline before a foreground stats query. */
    suspend fun flush() {
        val completed = CompletableDeferred<Unit>()
        sendOrdered(Command.Checkpoint(reading(), completed))
        completed.await()
    }

    fun close() {
        orderedIngressLock.lock()
        try {
            val shouldClose = synchronized(ingressLock) {
                if (closed.get()) {
                    false
                } else {
                    closed.set(true)
                    ticker.cancel()
                    true
                }
            }
            if (shouldClose) {
                sendOrderedBlocking(Command.Close)
            }
        } finally {
            orderedIngressLock.unlock()
        }
        if (ownedScope) {
            scope.launch {
                worker.join()
                scope.cancel()
            }
        }
    }

    private suspend fun nextWork(): Work? {
        commands.tryReceive().getOrNull()?.let {
            commandDepth.decrementAndGet()
            return Work.Semantic(it)
        }
        timerWork.tryReceive().getOrNull()?.let {
            return Work.Timer
        }
        return kotlinx.coroutines.selects.select {
            commands.onReceiveCatching { result ->
                result.getOrNull()?.let {
                    commandDepth.decrementAndGet()
                    Work.Semantic(it)
                }
            }
            timerWork.onReceive { Work.Timer }
            refreshWork.onReceive { Work.Refresh }
        }
    }

    private suspend fun processSemanticCommand(command: Command): Boolean {
        var retried = false
        while (true) {
            try {
                when (command) {
                    is Command.StateChanged -> handleStateChanged(command)
                    is Command.SourceReleased -> handleSourceReleased(command)
                    is Command.Checkpoint -> {
                        drainPendingRefreshes()
                        runCheckpoint(command.reading, force = true)
                        command.completed?.complete(Unit)
                    }
                    is Command.Reset -> {
                        drainPendingRefreshes()
                        handleReset(command)
                        command.completed.complete(Unit)
                    }
                    is Command.Barrier -> {
                        drainPendingRefreshes()
                        val timerPending = timerWork.tryReceive().isSuccess
                        val refreshPending = refreshWork.tryReceive().isSuccess
                        if (timerPending || refreshPending) {
                            drainPendingRefreshes()
                            runCheckpoint(reading(), force = timerPending)
                        }
                        command.completed.complete(Unit)
                    }
                    Command.Close -> {
                        drainPendingRefreshes()
                        finishAll(reading())
                        return true
                    }
                }
                return false
            } catch (cancelled: CancellationException) {
                when (command) {
                    is Command.Checkpoint -> command.completed?.completeExceptionally(cancelled)
                    is Command.Reset -> command.completed.completeExceptionally(cancelled)
                    is Command.Barrier -> command.completed.completeExceptionally(cancelled)
                    else -> Unit
                }
                throw cancelled
            } catch (failure: Exception) {
                if (!retried &&
                    (command is Command.StateChanged || command is Command.SourceReleased)
                ) {
                    retried = true
                    continue
                }
                when (command) {
                    is Command.Checkpoint -> command.completed?.completeExceptionally(failure)
                    is Command.Reset -> command.completed.completeExceptionally(failure)
                    is Command.Barrier -> command.completed.completeExceptionally(failure)
                    else -> Unit
                }
                // A single failed Room write must not permanently stop later
                // transitions. The in-memory state remains the retry baseline.
                return false
            }
        }
    }

    private suspend fun handleStateChanged(command: Command.StateChanged) {
        val metadata = command.metadata?.takeIf { it.hasTrackableChannel() }
        val state = states[command.sourceId]
        if (metadata == null) {
            state?.let {
                finish(it, command.reading)
                states.remove(command.sourceId)
            }
            return
        }

        if (state == null) {
            val newState = SourceState(command.sourceId, metadata)
            states[command.sourceId] = newState
            if (command.shouldPlay) {
                startSession(newState, command.reading)
            } else {
                newState.lastElapsed = command.reading.elapsedRealtime
                newState.lastWall = command.reading.wallTimeMillis
            }
            return
        }

        accrue(state, command.reading)
        if (!state.metadata.hasSamePlaybackAs(metadata)) {
            finish(state, command.reading)
            state.resetForNewMetadata(metadata, command.reading)
        } else if (!state.metadata.hasSameAttributionAs(metadata)) {
            // A category change is a new attribution interval, not a new
            // viewing session. The elapsed time accrued above belongs to the
            // previous category before we checkpoint it.
            if (state.actualPlaying) {
                closeActiveIntervalAndPersistSession(state, command.reading)
            }
            state.metadata = metadata
        } else {
            // Keep the latest display-name/avatar/category snapshot without
            // changing the playback or attribution identity.
            state.metadata = metadata
        }

        if (command.shouldPlay) {
            if (!state.actualPlaying) {
                if (state.sessionId == null) {
                    startSession(state, command.reading)
                } else {
                    startInterval(state, command.reading)
                }
            } else if (shouldCheckpoint(state, command.reading)) {
                persistCheckpoint(state, command.reading)
            }
        } else if (state.actualPlaying) {
            closeActiveIntervalAndPersistSession(state, command.reading)
            state.lastPersistElapsed = command.reading.elapsedRealtime
        }
    }

    private suspend fun handleSourceReleased(command: Command.SourceReleased) {
        states[command.sourceId]?.let {
            finish(it, command.reading)
            states.remove(command.sourceId)
        }
    }

    private suspend fun runCheckpoint(reading: ClockReading, force: Boolean = false) {
        val activeStates = states.values.filter { it.actualPlaying }
        activeStates.forEach { accrue(it, reading) }
        val dueStates = activeStates.filter { force || shouldCheckpoint(it, reading) }
        if (dueStates.isEmpty()) return

        try {
            repository.updateCheckpoints(
                intervals = dueStates.mapNotNull { it.checkpointInterval(reading) },
                sessions = dueStates.mapNotNull { it.checkpointSession(reading.wallTimeMillis) },
            )
            dueStates.forEach { it.lastPersistElapsed = reading.elapsedRealtime }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the state and its elapsed baseline intact so the next
            // checkpoint or transition can retry the write.
        }
    }

    private suspend fun drainPendingRefreshes() {
        val refreshes = synchronized(ingressLock) {
            pendingRefreshes.values.toList().also { pendingRefreshes.clear() }
        }
        refreshes.forEach { command ->
            try {
                handleStateChanged(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retain a failed metadata refresh for a future coalesced
                // wake, but never let one Room failure kill the worker.
                synchronized(ingressLock) {
                    val latest = latestInputs[command.sourceId]
                    if (latest == IngressState(command.metadata, command.shouldPlay)) {
                        pendingRefreshes[command.sourceId] = command
                    }
                }
            }
        }
    }

    private suspend fun finishAll(reading: ClockReading) {
        states.values.toList().forEach { state ->
            try {
                finish(state, reading)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Close must still drain the other sources. A failed row can
                // be recovered by the next application session's normal DB
                // state, while this recorder is no longer usable.
            }
        }
        states.clear()
    }

    private fun isSemanticTransition(
        previous: IngressState?,
        next: Command.StateChanged,
    ): Boolean {
        if (previous == null || previous.metadata == null || next.metadata == null) {
            return true
        }
        return previous.shouldPlay != next.shouldPlay ||
                !previous.metadata.hasSamePlaybackAs(next.metadata) ||
                !previous.metadata.hasSameAttributionAs(next.metadata)
    }

    private fun enqueueOrdered(command: Command) {
        // Do not replace this with one coroutine per send. A full channel must
        // backpressure the producer rather than move an unbounded backlog into
        // suspended sender jobs.
        orderedIngressLock.lock()
        try {
            sendOrderedBlocking(command)
        } finally {
            orderedIngressLock.unlock()
        }
    }

    private suspend fun sendOrdered(command: Command) {
        withContext(Dispatchers.IO) {
            enqueueOrdered(command)
        }
    }

    private fun sendOrderedBlocking(command: Command) {
        val pending = commandDepth.incrementAndGet()
        // A rendezvous receiver can resume the sender just before its select
        // callback decrements commandDepth. Do not count that bookkeeping
        // handoff as an additional pending command.
        maxCommandDepth.updateAndGet {
            max(it, pending.coerceAtMost(SEMANTIC_COMMAND_CAPACITY + 1))
        }
        val result = commands.trySend(command).let { initial ->
            if (initial.isSuccess) initial else commands.trySendBlocking(command)
        }
        if (result.isFailure) {
            commandDepth.decrementAndGet()
            error("Viewing stats recorder command channel is unavailable")
        }
    }

    internal fun pendingSemanticWorkForTest(): Int = commandDepth.get()

    internal fun maxPendingSemanticWorkForTest(): Int = maxCommandDepth.get()

    private fun setActualPlaying(state: SourceState, actualPlaying: Boolean) {
        if (state.actualPlaying == actualPlaying) return
        state.actualPlaying = actualPlaying
        if (actualPlaying) {
            activeSourceCount.incrementAndGet()
        } else {
            activeSourceCount.decrementAndGet()
        }
        schedulerWake.trySend(Unit)
    }

    private suspend fun handleReset(command: Command.Reset) {
        repository.resetAll()
        states.values.forEach { state ->
            state.wasPlayingBeforeReset = state.actualPlaying
            state.sessionId = null
            state.intervalId = null
            state.sessionStartedAt = 0L
            state.sessionWatchedMs = 0L
            state.intervalStartWall = 0L
            state.intervalWatchedMs = 0L
            setActualPlaying(state, false)
            state.lastElapsed = command.reading.elapsedRealtime
            state.lastWall = command.reading.wallTimeMillis
            state.lastPersistElapsed = command.reading.elapsedRealtime
        }
        states.values.filter { it.metadata.hasTrackableChannel() && it.wasPlayingBeforeReset }.forEach {
            startSession(it, command.reading)
        }
    }

    private suspend fun startSession(state: SourceState, reading: ClockReading) {
        if (state.sessionId == null) {
            state.sessionStartedAt = reading.wallTimeMillis
            state.sessionWatchedMs = 0L
            state.sessionId = repository.insertSession(state.metadata, reading.wallTimeMillis)
        }
        startInterval(state, reading)
        persistSession(state, reading.wallTimeMillis)
    }

    private suspend fun startInterval(state: SourceState, reading: ClockReading) {
        if (state.sessionId == null || state.intervalId != null) return
        state.intervalStartWall = reading.wallTimeMillis
        state.intervalWatchedMs = 0L
        state.intervalId = repository.insertInterval(state.metadata, state.sessionId!!, reading.wallTimeMillis)
        setActualPlaying(state, true)
        state.lastElapsed = reading.elapsedRealtime
        state.lastWall = reading.wallTimeMillis
        state.lastPersistElapsed = reading.elapsedRealtime
    }

    private suspend fun finish(state: SourceState, reading: ClockReading) {
        accrue(state, reading)
        if (state.intervalId != null) {
            closeActiveIntervalAndPersistSession(state, reading)
        } else if (state.sessionId != null) {
            persistSession(state, reading.wallTimeMillis)
        }
        setActualPlaying(state, false)
    }

    private suspend fun closeActiveIntervalAndPersistSession(
        state: SourceState,
        reading: ClockReading,
    ) {
        val intervalId = state.intervalId ?: return
        val endAt = normalizeIntervalStart(state, reading.wallTimeMillis)
        repository.updateCheckpoints(
            intervals = listOf(state.interval(endAt, reading.wallTimeMillis, intervalId)),
            sessions = listOfNotNull(state.checkpointSession(reading.wallTimeMillis)),
        )
        state.intervalId = null
        state.intervalWatchedMs = 0L
        setActualPlaying(state, false)
    }

    private suspend fun persistCheckpoint(state: SourceState, reading: ClockReading) {
        repository.updateCheckpoints(
            intervals = listOfNotNull(state.checkpointInterval(reading)),
            sessions = listOfNotNull(state.checkpointSession(reading.wallTimeMillis)),
        )
        state.lastPersistElapsed = reading.elapsedRealtime
    }

    private suspend fun persistSession(state: SourceState, endedAt: Long) {
        state.checkpointSession(endedAt)?.let { repository.updateSession(it) }
    }

    private fun SourceState.checkpointInterval(reading: ClockReading): ViewingInterval? {
        val intervalId = intervalId ?: return null
        val endAt = normalizeIntervalStart(this, reading.wallTimeMillis)
        return interval(endAt, reading.wallTimeMillis, intervalId)
    }

    private fun SourceState.interval(
        endAt: Long,
        lastCheckpointAt: Long,
        intervalId: Long = this.intervalId!!,
    ): ViewingInterval {
        return ViewingInterval(
            id = intervalId,
            sessionId = sessionId!!,
            channelId = metadata.normalizedChannelId!!,
            channelLogin = metadata.channelLogin,
            channelName = metadata.channelName,
            channelImage = metadata.channelImage,
            categoryId = metadata.categoryId,
            categoryName = metadata.categoryName,
            categoryImage = metadata.categoryImage,
            contentType = metadata.contentType,
            contentId = metadata.contentId,
            streamTitle = metadata.title,
            startAt = intervalStartWall,
            endAt = endAt,
            watchedMs = intervalWatchedMs,
            lastCheckpointAt = lastCheckpointAt,
        )
    }

    private fun SourceState.checkpointSession(endedAt: Long): ViewingSession? {
        val sessionId = sessionId ?: return null
        return ViewingSession(
            id = sessionId,
            channelId = metadata.normalizedChannelId!!,
            channelLogin = metadata.channelLogin,
            channelName = metadata.channelName,
            channelImage = metadata.channelImage,
            contentType = metadata.contentType,
            contentId = metadata.contentId,
            startedAt = sessionStartedAt,
            endedAt = max(sessionStartedAt, endedAt),
            watchedMs = sessionWatchedMs,
            lastCheckpointAt = endedAt,
        )
    }

    private fun accrue(state: SourceState, reading: ClockReading) {
        if (state.actualPlaying) {
            val delta = reading.elapsedRealtime - state.lastElapsed
            if (delta > 0L) {
                state.sessionWatchedMs = safeAdd(state.sessionWatchedMs, delta)
                state.intervalWatchedMs = safeAdd(state.intervalWatchedMs, delta)
            }
        }
        state.lastElapsed = reading.elapsedRealtime
        state.lastWall = reading.wallTimeMillis
    }

    private fun normalizeIntervalStart(state: SourceState, wallEnd: Long): Long {
        if (state.intervalWatchedMs > 0L) {
            val wallDuration = wallEnd - state.intervalStartWall
            val clockMoved = wallDuration < 0L || wallDuration > safeAdd(state.intervalWatchedMs, CLOCK_SKEW_TOLERANCE_MS)
            if (clockMoved) {
                state.intervalStartWall = safeSubtract(wallEnd, state.intervalWatchedMs)
            }
        }
        return max(state.intervalStartWall, wallEnd)
    }

    private fun shouldCheckpoint(state: SourceState, reading: ClockReading): Boolean {
        return reading.elapsedRealtime - state.lastPersistElapsed >= checkpointIntervalMs
    }

    private fun reading(): ClockReading = ClockReading(clock.elapsedRealtime(), clock.currentTimeMillis())

    private fun safeAdd(left: Long, right: Long): Long {
        return if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun safeSubtract(left: Long, right: Long): Long {
        return if (right > 0L && left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right
    }

    private sealed interface Command {
        data class StateChanged(
            val sourceId: String,
            val metadata: ViewingPlaybackMetadata?,
            val shouldPlay: Boolean,
            val reading: ClockReading,
        ) : Command

        data class SourceReleased(val sourceId: String, val reading: ClockReading) : Command
        data class Checkpoint(
            val reading: ClockReading,
            val completed: CompletableDeferred<Unit>? = null,
        ) : Command
        data class Reset(val reading: ClockReading, val completed: CompletableDeferred<Unit>) : Command
        data class Barrier(val completed: CompletableDeferred<Unit>) : Command
        data object Close : Command
    }

    private data class ClockReading(
        val elapsedRealtime: Long,
        val wallTimeMillis: Long,
    )

    private class SourceState(
        val sourceId: String,
        var metadata: ViewingPlaybackMetadata,
    ) {
        var sessionId: Long? = null
        var sessionStartedAt = 0L
        var sessionWatchedMs = 0L
        var intervalId: Long? = null
        var intervalStartWall = 0L
        var intervalWatchedMs = 0L
        var actualPlaying = false
        var lastElapsed = 0L
        var lastWall = 0L
        var lastPersistElapsed = 0L
        var wasPlayingBeforeReset = false

        fun resetForNewMetadata(newMetadata: ViewingPlaybackMetadata, reading: ClockReading) {
            metadata = newMetadata
            sessionId = null
            sessionStartedAt = 0L
            sessionWatchedMs = 0L
            intervalId = null
            intervalStartWall = 0L
            intervalWatchedMs = 0L
            actualPlaying = false
            lastElapsed = reading.elapsedRealtime
            lastWall = reading.wallTimeMillis
            lastPersistElapsed = reading.elapsedRealtime
        }
    }

    private data class IngressState(
        val metadata: ViewingPlaybackMetadata?,
        val shouldPlay: Boolean,
    )

    private sealed interface Work {
        data class Semantic(val command: Command) : Work
        data object Timer : Work
        data object Refresh : Work
    }

    private companion object {
        const val SEMANTIC_COMMAND_CAPACITY = 128
        const val DEFAULT_CHECKPOINT_INTERVAL_MS = 120_000L
        const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
        const val NO_DEADLINE = Long.MAX_VALUE
    }
}
