package com.github.andreyasadchy.xtra.util.viewingstats

import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import com.github.andreyasadchy.xtra.model.stats.ViewingSession
import com.github.andreyasadchy.xtra.model.stats.ViewingInterval
import com.github.andreyasadchy.xtra.repository.ViewingStatsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val states = linkedMapOf<String, SourceState>()
    private val worker = this.scope.launch {
        for (command in commands) {
            when (command) {
                is Command.StateChanged -> handleStateChanged(command)
                is Command.SourceReleased -> handleSourceReleased(command)
                is Command.Checkpoint -> {
                    handleCheckpoint(command.reading)
                    command.completed?.complete(Unit)
                }
                is Command.Reset -> handleReset(command)
                is Command.Barrier -> command.completed.complete(Unit)
                Command.Close -> break
            }
        }
    }
    private val ticker = this.scope.launch {
        while (isActive) {
            delay(checkpointIntervalMs)
            commands.send(Command.Checkpoint(reading()))
        }
    }

    fun update(
        sourceId: String,
        metadata: ViewingPlaybackMetadata?,
        isPlaying: Boolean,
        isBuffering: Boolean,
    ) {
        commands.trySend(
            Command.StateChanged(
                sourceId = sourceId,
                metadata = metadata,
                shouldPlay = isPlaying && !isBuffering,
                reading = reading(),
            )
        )
    }

    fun release(sourceId: String) {
        commands.trySend(Command.SourceReleased(sourceId, reading()))
    }

    /** Deletes persisted rows and creates new zero-based sessions for active sources. */
    suspend fun reset() {
        val completed = CompletableDeferred<Unit>()
        commands.send(Command.Reset(reading(), completed))
        completed.await()
    }

    suspend fun awaitIdle() {
        val completed = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(completed))
        completed.await()
    }

    /** Persists the current playback baseline before a foreground stats query. */
    suspend fun flush() {
        val completed = CompletableDeferred<Unit>()
        commands.send(Command.Checkpoint(reading(), completed))
        completed.await()
    }

    fun close() {
        commands.trySend(Command.Close)
        ticker.cancel()
        if (ownedScope) {
            scope.cancel()
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
        } else {
            // Keep the latest display-name/avatar snapshot without changing channel identity.
            state.metadata = metadata
        }

        if (command.shouldPlay) {
            if (!state.actualPlaying) {
                startSession(state, command.reading)
            } else if (shouldCheckpoint(state, command.reading)) {
                persistCheckpoint(state, command.reading)
            }
        } else if (state.actualPlaying) {
            closeActiveInterval(state, command.reading)
            persistSession(state, command.reading.wallTimeMillis)
            state.actualPlaying = false
            state.lastPersistElapsed = command.reading.elapsedRealtime
        }
    }

    private suspend fun handleSourceReleased(command: Command.SourceReleased) {
        states.remove(command.sourceId)?.let { finish(it, command.reading) }
    }

    private suspend fun handleCheckpoint(reading: ClockReading) {
        states.values.toList().forEach { state ->
            if (state.actualPlaying) {
                accrue(state, reading)
                persistCheckpoint(state, reading)
            }
        }
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
            state.actualPlaying = false
            state.lastElapsed = command.reading.elapsedRealtime
            state.lastWall = command.reading.wallTimeMillis
            state.lastPersistElapsed = command.reading.elapsedRealtime
        }
        states.values.filter { it.metadata.hasTrackableChannel() && it.wasPlayingBeforeReset }.forEach {
            it.actualPlaying = false
            startSession(it, command.reading)
        }
        command.completed.complete(Unit)
    }

    private suspend fun startSession(state: SourceState, reading: ClockReading) {
        if (state.sessionId == null) {
            state.sessionStartedAt = reading.wallTimeMillis
            state.sessionWatchedMs = 0L
            state.sessionId = repository.insertSession(state.metadata, reading.wallTimeMillis)
        }
        state.intervalStartWall = reading.wallTimeMillis
        state.intervalWatchedMs = 0L
        state.intervalId = repository.insertInterval(state.metadata, state.sessionId!!, reading.wallTimeMillis)
        state.actualPlaying = true
        state.lastElapsed = reading.elapsedRealtime
        state.lastWall = reading.wallTimeMillis
        state.lastPersistElapsed = reading.elapsedRealtime
        persistSession(state, reading.wallTimeMillis)
    }

    private suspend fun finish(state: SourceState, reading: ClockReading) {
        accrue(state, reading)
        if (state.intervalId != null) {
            closeActiveInterval(state, reading)
        }
        if (state.sessionId != null) {
            persistSession(state, reading.wallTimeMillis)
        }
        state.actualPlaying = false
    }

    private suspend fun closeActiveInterval(state: SourceState, reading: ClockReading) {
        val intervalId = state.intervalId ?: return
        val endAt = normalizeIntervalStart(state, reading.wallTimeMillis)
        repository.updateInterval(
            ViewingInterval(
                id = intervalId,
                sessionId = state.sessionId!!,
                channelId = state.metadata.normalizedChannelId!!,
                channelLogin = state.metadata.channelLogin,
                channelName = state.metadata.channelName,
                channelImage = state.metadata.channelImage,
                startAt = state.intervalStartWall,
                endAt = endAt,
                watchedMs = state.intervalWatchedMs,
                lastCheckpointAt = reading.wallTimeMillis,
            )
        )
        state.intervalId = null
        state.intervalWatchedMs = 0L
        state.actualPlaying = false
    }

    private suspend fun persistCheckpoint(state: SourceState, reading: ClockReading) {
        val intervalId = state.intervalId
        if (intervalId != null) {
            val endAt = normalizeIntervalStart(state, reading.wallTimeMillis)
            repository.updateInterval(
                ViewingInterval(
                    id = intervalId,
                    sessionId = state.sessionId!!,
                    channelId = state.metadata.normalizedChannelId!!,
                    channelLogin = state.metadata.channelLogin,
                    channelName = state.metadata.channelName,
                    channelImage = state.metadata.channelImage,
                    startAt = state.intervalStartWall,
                    endAt = endAt,
                    watchedMs = state.intervalWatchedMs,
                    lastCheckpointAt = reading.wallTimeMillis,
                )
            )
        }
        persistSession(state, reading.wallTimeMillis)
        state.lastPersistElapsed = reading.elapsedRealtime
    }

    private suspend fun persistSession(state: SourceState, endedAt: Long) {
        val sessionId = state.sessionId ?: return
        repository.updateSession(
            ViewingSession(
                id = sessionId,
                channelId = state.metadata.normalizedChannelId!!,
                channelLogin = state.metadata.channelLogin,
                channelName = state.metadata.channelName,
                channelImage = state.metadata.channelImage,
                contentType = state.metadata.contentType,
                contentId = state.metadata.contentId,
                startedAt = state.sessionStartedAt,
                endedAt = max(state.sessionStartedAt, endedAt),
                watchedMs = state.sessionWatchedMs,
                lastCheckpointAt = endedAt,
            )
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

    private companion object {
        const val DEFAULT_CHECKPOINT_INTERVAL_MS = 45_000L
        const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
