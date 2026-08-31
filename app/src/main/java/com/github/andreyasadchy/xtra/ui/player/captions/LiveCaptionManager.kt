package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent
import com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngine
import com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngineFactory
import com.github.andreyasadchy.xtra.ui.player.captions.engine.MOONSHINE_ENGINE_ID
import com.github.andreyasadchy.xtra.util.C as PreferenceKeys
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal sealed interface AudioEvent {
    data class Pcm(
        val bytes: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
        val generation: Long,
    ) : AudioEvent

    data class Flush(
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
        val generation: Long,
    ) : AudioEvent

    data class Reset(val generation: Long) : AudioEvent

    data class Reconfigure(val generation: Long) : AudioEvent

    data object Stop : AudioEvent
}

/** A deliberately non-blocking, bounded handoff between the audio sink and ASR. */
internal class CaptionAudioQueue(capacity: Int = 8) {
    private val queue = ArrayBlockingQueue<AudioEvent>(capacity)

    fun offer(event: AudioEvent): Boolean = queue.offer(event)

    fun take(): AudioEvent = queue.take()

    fun poll(timeoutMs: Long): AudioEvent? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun clear() = queue.clear()

    val size: Int get() = queue.size
}

private data class AudioFormatState(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
)

@OptIn(UnstableApi::class)
class LiveCaptionManager(
    private val context: Context,
    private val engineFactory: (Context) -> LiveCaptionEngine =
        LiveCaptionEngineFactory::create,
) {
    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val audioGeneration = AtomicLong(0L)
    private val nextAudioSinkId = AtomicLong(1L)
    private val activeAudioSinkId = AtomicLong(NO_AUDIO_SINK)
    private val droppedAudioBuffers = AtomicInteger(0)
    private val presentationDelayMs = AtomicInteger(0)
    private val captionTextOffsetMs = AtomicInteger(DEFAULT_CAPTION_TEXT_OFFSET_MS)
    private val captionSettingsGeneration = AtomicLong(0L)
    private val captionHoldMs = AtomicInteger(DEFAULT_CAPTION_HOLD_SECONDS * 1_000)
    private val audioQueue = CaptionAudioQueue()
    private val stateMutable = MutableStateFlow(LiveCaptionState())
    private val metricsMutable = MutableStateFlow(
        LiveCaptionMetrics(MOONSHINE_ENGINE_ID),
    )
    private val workerLock = Any()
    private val captionTextLock = Any()
    private val captionText = CaptionTextStateMachine()

    @Volatile
    private var audioFormat: AudioFormatState? = null

    @Volatile
    private var worker: Thread? = null

    val state: StateFlow<LiveCaptionState> = stateMutable.asStateFlow()
    val metrics: StateFlow<LiveCaptionMetrics> = metricsMutable.asStateFlow()

    /** Read from the audio render thread; this is deliberately only an atomic load. */
    fun presentationDelayMs(): Int {
        val baseDelayMs = presentationDelayMs.get()
        val textOffsetMs = captionTextOffsetMs.get()
        // Positive offsets are applied to caption events by the worker. A
        // negative offset is represented by holding playback back slightly,
        // which is the only safe way to make already-produced text appear
        // earlier relative to the audible content.
        return if (textOffsetMs < 0) {
            (baseDelayMs - textOffsetMs).coerceAtMost(MAX_CAPTION_PRESENTATION_DELAY_MS)
        } else {
            baseDelayMs
        }
    }

    fun reloadCaptionSettings() {
        if (closed.get()) return
        refreshRuntimeSettings()
        captionSettingsGeneration.incrementAndGet()
    }

    /**
     * A renderer-owned sink. Only the sink claimed by the current playback
     * player may affect the shared caption worker. This prevents a renderer
     * from an old playback generation from flushing the active stream.
     */
    class AudioBufferSinkSession internal constructor(
        internal val id: Long,
        internal val sink: TeeAudioProcessor.AudioBufferSink,
    )

    private val defaultAudioBufferSinkSession = createAudioBufferSinkSessionInternal(alwaysActive = true)
    val audioBufferSink: TeeAudioProcessor.AudioBufferSink = defaultAudioBufferSinkSession.sink

    fun createAudioBufferSinkSession(): AudioBufferSinkSession =
        createAudioBufferSinkSessionInternal()

    fun activateAudioBufferSink(session: AudioBufferSinkSession) {
        if (!closed.get()) activeAudioSinkId.set(session.id)
    }

    fun deactivateAudioBufferSink(session: AudioBufferSinkSession) {
        if (!activeAudioSinkId.compareAndSet(session.id, NO_AUDIO_SINK)) return
        audioGeneration.incrementAndGet()
        audioFormat = null
        audioQueue.clear()
        audioQueue.offer(AudioEvent.Stop)
    }

    private fun createAudioBufferSinkSessionInternal(
        alwaysActive: Boolean = false,
    ): AudioBufferSinkSession {
        val id = nextAudioSinkId.getAndIncrement()
        return AudioBufferSinkSession(
            id = id,
            sink = object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(
                    sampleRateHz: Int,
                    channelCount: Int,
                    encoding: Int,
                ) {
                    if (!alwaysActive && activeAudioSinkId.get() != id) return
                    val generation = audioGeneration.incrementAndGet()
                    audioFormat = AudioFormatState(sampleRateHz, channelCount, encoding)
                    audioQueue.clear()
                    if (enabled.get()) {
                        audioQueue.offer(
                            AudioEvent.Flush(sampleRateHz, channelCount, encoding, generation),
                        )
                    } else {
                        audioQueue.offer(AudioEvent.Stop)
                    }
                }

                override fun handleBuffer(buffer: ByteBuffer) {
                    if ((!alwaysActive && activeAudioSinkId.get() != id) || !enabled.get()) return

                    val format = audioFormat ?: return
                    if (format.encoding != C.ENCODING_PCM_16BIT &&
                        format.encoding != C.ENCODING_PCM_FLOAT
                    ) return

                    val copy = ByteArray(buffer.remaining())
                    buffer.duplicate().get(copy)
                    val event = AudioEvent.Pcm(
                        bytes = copy,
                        sampleRateHz = format.sampleRateHz,
                        channelCount = format.channelCount,
                        encoding = format.encoding,
                        generation = audioGeneration.get(),
                    )

                    // NEVER block playback. Caption audio is disposable when the worker falls behind.
                    if (!audioQueue.offer(event)) {
                        droppedAudioBuffers.incrementAndGet()
                    }
                }
            },
        )
    }

    fun setEnabled(value: Boolean) {
        if (closed.get()) return

        if (!value) {
            enabled.set(false)
            presentationDelayMs.set(0)
            captionTextOffsetMs.set(DEFAULT_CAPTION_TEXT_OFFSET_MS)
            audioGeneration.incrementAndGet()
            audioQueue.clear()
            audioQueue.offer(AudioEvent.Stop)
            resetCaptionText()
            stateMutable.value = LiveCaptionState()
            return
        }

        refreshRuntimeSettings()
        enabled.set(true)
        resetCaptionText()
        stateMutable.value = LiveCaptionState(
            enabled = true,
            status = LiveCaptionState.Status.STARTING,
        )
        startWorker()
    }

    fun reloadConfiguration() {
        if (closed.get()) return

        refreshRuntimeSettings()
        val generation = audioGeneration.incrementAndGet()
        audioQueue.clear()
        resetCaptionText()
        if (enabled.get()) {
            stateMutable.value = LiveCaptionState(
                enabled = true,
                status = LiveCaptionState.Status.STARTING,
            )
            audioQueue.offer(AudioEvent.Reconfigure(generation))
            startWorker()
        } else {
            audioQueue.offer(AudioEvent.Stop)
            stateMutable.value = LiveCaptionState()
        }
    }

    fun clearVisibleCaption() {
        val lineShiftToken = resetCaptionText()
        if (enabled.get()) {
            stateMutable.value = stateMutable.value.copy(
                text = "",
                lineShiftToken = lineShiftToken,
            )
        }
    }

    /** Invalidates queued audio when the player view changes ownership. */
    fun resetForPlaybackTransition() {
        if (closed.get()) return
        val generation = audioGeneration.incrementAndGet()
        audioQueue.clear()
        val lineShiftToken = resetCaptionText()
        if (enabled.get()) {
            audioQueue.offer(AudioEvent.Reset(generation))
            stateMutable.value = stateMutable.value.copy(
                text = "",
                lineShiftToken = lineShiftToken,
            )
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enabled.set(false)
        presentationDelayMs.set(0)
        captionTextOffsetMs.set(DEFAULT_CAPTION_TEXT_OFFSET_MS)
        audioQueue.clear()
        audioQueue.offer(AudioEvent.Stop)
        resetCaptionText()
        stateMutable.value = LiveCaptionState()
    }

    private fun startWorker() {
        synchronized(workerLock) {
            if (worker?.isAlive == true) return
            val thread = Thread(::runRecognitionLoop, "xtra-live-caption-asr")
            worker = thread
            thread.start()
        }
    }

    private fun runRecognitionLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        var engine: LiveCaptionEngine? = null
        var engineGeneration = -1L
        var metricsEngineId = MOONSHINE_ENGINE_ID
        var metricsStartedAtMs = 0L
        var engineInitMs = 0L
        var firstOutputAfterStartMs: Long? = null
        var lastInferenceMs = 0L
        var maxInferenceMs = 0L
        var inferenceCalls = 0L
        var totalInferenceMs = 0L
        var acceptedAudioMs = 0L
        var nextMetricsLogMs = SystemClock.elapsedRealtime() + METRICS_LOG_INTERVAL_MS
        var visibleCaptionExpiresAtMs = 0L
        val pendingCaptionEvents = CaptionEventDelayQueue()
        var appliedCaptionSettingsGeneration = captionSettingsGeneration.get()
        val droppedBuffersBaseline = EngineDropBaseline(droppedAudioBuffers.get())

        fun markFirstOutput(text: String) {
            if (firstOutputAfterStartMs == null && text.isNotBlank()) {
                firstOutputAfterStartMs = SystemClock.elapsedRealtime() - metricsStartedAtMs
            }
        }

        fun applyCaptionEvent(event: CaptionRecognitionEvent) {
            val text = when (event) {
                is CaptionRecognitionEvent.Partial -> event.text
                is CaptionRecognitionEvent.Final -> event.text
            }
            markFirstOutput(text)
            when (event) {
                is CaptionRecognitionEvent.Partial -> publishPartial(event)
                is CaptionRecognitionEvent.Final -> publishFinal(event)
            }
            visibleCaptionExpiresAtMs = SystemClock.elapsedRealtime() + captionHoldMs.get()
        }

        fun invalidatePendingCaptionEventsIfNeeded() {
            val currentGeneration = captionSettingsGeneration.get()
            if (currentGeneration != appliedCaptionSettingsGeneration) {
                pendingCaptionEvents.clear()
                appliedCaptionSettingsGeneration = currentGeneration
            }
        }

        fun enqueueCaptionEvent(event: CaptionRecognitionEvent) {
            pendingCaptionEvents.enqueue(
                event = event,
                delayMs = captionTextOffsetMs.get().coerceAtLeast(0).toLong(),
                nowMs = SystemClock.elapsedRealtime(),
                apply = ::applyCaptionEvent,
            )
        }

        fun drainPendingCaptionEvents() {
            pendingCaptionEvents.drain(
                nowMs = SystemClock.elapsedRealtime(),
                apply = ::applyCaptionEvent,
            )
        }

        fun resetMetrics() {
            metricsEngineId = MOONSHINE_ENGINE_ID
            metricsStartedAtMs = SystemClock.elapsedRealtime()
            engineInitMs = 0L
            firstOutputAfterStartMs = null
            lastInferenceMs = 0L
            maxInferenceMs = 0L
            inferenceCalls = 0L
            totalInferenceMs = 0L
            acceptedAudioMs = 0L
            droppedBuffersBaseline.reset(droppedAudioBuffers.get())
            nextMetricsLogMs = metricsStartedAtMs + METRICS_LOG_INTERVAL_MS
            publishMetrics(
                LiveCaptionMetrics(
                    engineId = metricsEngineId,
                    droppedAudioBuffers = droppedBuffersBaseline.delta(droppedAudioBuffers.get()),
                ),
            )
        }

        fun closeEngine() {
            runCatching { engine?.close() }
            engine = null
            engineGeneration = -1L
        }

        try {
            while (!closed.get()) {
                invalidatePendingCaptionEventsIfNeeded()
                drainPendingCaptionEvents()
                if (visibleCaptionExpiresAtMs != 0L &&
                    SystemClock.elapsedRealtime() >= visibleCaptionExpiresAtMs
                ) {
                    clearVisibleCaption()
                    visibleCaptionExpiresAtMs = 0L
                }

                val event = audioQueue.poll(QUEUE_POLL_INTERVAL_MS) ?: continue
                when (event) {
                    AudioEvent.Stop -> {
                        pendingCaptionEvents.clear()
                        closeEngine()
                        audioQueue.clear()
                        if (!enabled.get()) break
                    }

                    is AudioEvent.Reconfigure -> {
                        if (event.generation != audioGeneration.get()) continue
                        pendingCaptionEvents.clear()
                        closeEngine()
                        resetCaptionText()
                        visibleCaptionExpiresAtMs = 0L
                        resetMetrics()
                    }

                    is AudioEvent.Reset -> {
                        if (event.generation != audioGeneration.get()) continue
                        pendingCaptionEvents.clear()
                        engine?.reset()
                        engineGeneration = event.generation
                        clearVisibleCaption()
                        visibleCaptionExpiresAtMs = 0L
                    }

                    is AudioEvent.Flush -> {
                        pendingCaptionEvents.clear()
                        if (!enabled.get()) {
                            closeEngine()
                            audioQueue.clear()
                            break
                        }
                        if (event.generation != audioGeneration.get()) continue
                        engine?.reset()
                        engineGeneration = event.generation
                        clearVisibleCaption()
                        visibleCaptionExpiresAtMs = 0L
                        Log.i(
                            TAG,
                            "audio format ${event.sampleRateHz}Hz, " +
                                "${event.channelCount}ch, encoding=${event.encoding}, " +
                                "dropped=${droppedBuffersBaseline.delta(droppedAudioBuffers.get())}, " +
                                "queue=${audioQueue.size}",
                        )
                    }

                    is AudioEvent.Pcm -> {
                        if (!enabled.get() || event.generation != audioGeneration.get()) continue

                        if (engine != null &&
                            engineGeneration != event.generation
                        ) {
                            closeEngine()
                        }
                        if (engine == null) {
                            publishStarting()
                            resetMetrics()
                            val initStartedAt = SystemClock.elapsedRealtime()
                            engine = engineFactory(context)
                            engineGeneration = event.generation
                            engineInitMs = SystemClock.elapsedRealtime() - initStartedAt
                            metricsMutable.value = metricsMutable.value.copy(
                                engineId = MOONSHINE_ENGINE_ID,
                                engineInitMs = engineInitMs,
                            )
                            Log.i(TAG, "ASR engine=$MOONSHINE_ENGINE_ID initialized in ${engineInitMs}ms")
                            publishListening()
                        }

                        if (engineGeneration != event.generation) continue
                        val samples = when (event.encoding) {
                            C.ENCODING_PCM_16BIT -> pcm16ToMono(event.bytes, event.channelCount)
                            C.ENCODING_PCM_FLOAT -> pcmFloatToMono(event.bytes, event.channelCount)
                            else -> continue
                        }
                        if (samples.isEmpty()) continue

                        val activeEngine = checkNotNull(engine)
                        val inferenceStartedAt = SystemClock.elapsedRealtime()
                        val events = activeEngine.accept(samples, event.sampleRateHz)
                        val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
                        inferenceCalls++
                        lastInferenceMs = inferenceMs
                        maxInferenceMs = maxOf(maxInferenceMs, inferenceMs)
                        totalInferenceMs += inferenceMs
                        acceptedAudioMs += samples.size * 1_000L / event.sampleRateHz

                        if (event.generation != audioGeneration.get() || !enabled.get()) continue
                        invalidatePendingCaptionEventsIfNeeded()
                        events.forEach { recognition ->
                            enqueueCaptionEvent(recognition)
                        }

                        val now = SystemClock.elapsedRealtime()
                        publishMetrics(
                            LiveCaptionMetrics(
                                engineId = metricsEngineId,
                                engineInitMs = engineInitMs,
                                firstOutputAfterStartMs = firstOutputAfterStartMs,
                                lastInferenceMs = lastInferenceMs,
                                maxInferenceMs = maxInferenceMs,
                                inferenceCalls = inferenceCalls,
                                droppedAudioBuffers = droppedBuffersBaseline.delta(droppedAudioBuffers.get()),
                                realTimeFactor = if (acceptedAudioMs == 0L) {
                                    0.0
                                } else {
                                    totalInferenceMs.toDouble() / acceptedAudioMs
                                },
                            ),
                        )
                        if (now >= nextMetricsLogMs) {
                            Log.i(
                                TAG,
                                "LiveCaptions engine=$metricsEngineId " +
                                    "firstOutput=${firstOutputAfterStartMs ?: "none"}ms " +
                                    "lastInfer=${lastInferenceMs}ms " +
                                    "maxInfer=${maxInferenceMs}ms " +
                                    "rtf=${"%.2f".format(java.util.Locale.US, totalInferenceMs.toDouble() / acceptedAudioMs.coerceAtLeast(1L))} " +
                                    "drops=${droppedBuffersBaseline.delta(droppedAudioBuffers.get())}",
                            )
                            nextMetricsLogMs = now + METRICS_LOG_INTERVAL_MS
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) publishError(throwable)
        } finally {
            closeEngine()
            var restartWorker = false
            synchronized(workerLock) {
                if (worker === Thread.currentThread()) {
                    worker = null
                    restartWorker = enabled.get() && !closed.get()
                }
            }
            if (restartWorker) startWorker()
        }
    }

    private fun publishStarting() {
        if (enabled.get()) {
            stateMutable.value = stateMutable.value.copy(
                enabled = true,
                status = LiveCaptionState.Status.STARTING,
                error = null,
            )
        }
    }

    private fun publishListening() {
        if (enabled.get()) {
            stateMutable.value = stateMutable.value.copy(
                enabled = true,
                status = LiveCaptionState.Status.LISTENING,
                error = null,
            )
        }
    }

    private fun publishPartial(event: CaptionRecognitionEvent.Partial) {
        if (!enabled.get()) return
        val output = applyCaptionEvent(event)
        stateMutable.value = stateMutable.value.copy(
            text = output.text,
            lineShiftToken = output.lineShiftToken,
        )
    }

    private fun publishFinal(event: CaptionRecognitionEvent.Final) {
        if (!enabled.get()) return
        val output = applyCaptionEvent(event)
        stateMutable.value = stateMutable.value.copy(
            text = output.text,
            lineShiftToken = output.lineShiftToken,
        )
    }

    private fun resetCaptionText(): Long = synchronized(captionTextLock) {
        captionText.reset()
        captionText.lineShiftToken
    }

    private fun applyCaptionEvent(event: CaptionRecognitionEvent): CaptionTextOutput =
        synchronized(captionTextLock) {
            captionText.apply(event)
            CaptionTextOutput(captionText.visibleText, captionText.lineShiftToken)
        }

    private data class CaptionTextOutput(
        val text: String,
        val lineShiftToken: Long,
    )

    private fun publishError(throwable: Throwable) {
        enabled.set(false)
        presentationDelayMs.set(0)
        audioQueue.clear()
        resetCaptionText()
        stateMutable.value = LiveCaptionState(
            status = LiveCaptionState.Status.ERROR,
            error = throwable.message ?: throwable::class.java.simpleName,
        )
        // Initialization/decode failures must remain diagnosable in release builds too.
        // Do not include recognition text in this log.
        Log.e(TAG, "ASR failed", throwable)
    }

    private fun publishMetrics(value: LiveCaptionMetrics) {
        metricsMutable.value = value.copy(
            droppedAudioBuffers = value.droppedAudioBuffers,
        )
    }

    private fun refreshRuntimeSettings() {
        val preferences = context.prefs()
        presentationDelayMs.set(
            preferences.getInt(
                PreferenceKeys.PLAYER_LIVE_CAPTION_PRESENTATION_DELAY_MS,
                DEFAULT_CAPTION_PRESENTATION_DELAY_MS,
            ).coerceIn(0, MAX_CAPTION_PRESENTATION_DELAY_MS),
        )
        captionTextOffsetMs.set(
            parseCaptionTextOffsetMs(
                preferences.getString(
                    PreferenceKeys.PLAYER_LIVE_CAPTION_TEXT_OFFSET_SECONDS,
                    DEFAULT_CAPTION_TEXT_OFFSET_MS.toString(),
                ),
            ),
        )
        captionHoldMs.set(
            preferences.getInt(
                PreferenceKeys.PLAYER_LIVE_CAPTION_HOLD_SECONDS,
                DEFAULT_CAPTION_HOLD_SECONDS,
            ).coerceIn(1, 8) * 1_000,
        )
    }

    private companion object {
        const val NO_AUDIO_SINK = Long.MIN_VALUE
        const val TAG = "LiveCaptionManager"
        const val METRICS_LOG_INTERVAL_MS = 10_000L
        const val QUEUE_POLL_INTERVAL_MS = 100L
    }
}
