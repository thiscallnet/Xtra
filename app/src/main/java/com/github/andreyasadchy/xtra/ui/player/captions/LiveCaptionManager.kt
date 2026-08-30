package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent
import com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngine
import com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngineFactory
import com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngineId
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
import java.util.concurrent.atomic.AtomicReference

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

    data class EngineChanged(
        val id: LiveCaptionEngineId,
        val generation: Long,
    ) : AudioEvent

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
    private val engineFactory: (Context, LiveCaptionEngineId) -> LiveCaptionEngine =
        LiveCaptionEngineFactory::create,
) {
    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val audioGeneration = AtomicLong(0L)
    private val droppedAudioBuffers = AtomicInteger(0)
    private val selectedEngineId = AtomicReference(
        LiveCaptionEngineId.fromPreference(
            context.prefs().getString(PreferenceKeys.PLAYER_LIVE_CAPTION_ENGINE, null),
        ),
    )
    private val audioQueue = CaptionAudioQueue()
    private val stateMutable = MutableStateFlow(LiveCaptionState())
    private val metricsMutable = MutableStateFlow(
        LiveCaptionMetrics(selectedEngineId.get().preferenceValue),
    )
    private val workerLock = Any()
    private val captionText = CaptionTextStateMachine()

    @Volatile
    private var audioFormat: AudioFormatState? = null

    @Volatile
    private var worker: Thread? = null

    val state: StateFlow<LiveCaptionState> = stateMutable.asStateFlow()
    val metrics: StateFlow<LiveCaptionMetrics> = metricsMutable.asStateFlow()

    val audioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(
            sampleRateHz: Int,
            channelCount: Int,
            encoding: Int,
        ) {
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
            if (!enabled.get()) return

            val format = audioFormat ?: return
            if (format.encoding != C.ENCODING_PCM_16BIT) return

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
    }

    fun setEnabled(value: Boolean) {
        if (closed.get()) return

        if (!value) {
            enabled.set(false)
            audioGeneration.incrementAndGet()
            audioQueue.clear()
            audioQueue.offer(AudioEvent.Stop)
            captionText.reset()
            stateMutable.value = LiveCaptionState()
            return
        }

        enabled.set(true)
        captionText.reset()
        stateMutable.value = LiveCaptionState(
            enabled = true,
            status = LiveCaptionState.Status.STARTING,
        )
        startWorker()
    }

    fun setEngine(id: LiveCaptionEngineId) {
        if (closed.get()) return

        context.prefs().edit {
            putString(PreferenceKeys.PLAYER_LIVE_CAPTION_ENGINE, id.preferenceValue)
        }
        selectedEngineId.set(id)
        val generation = audioGeneration.incrementAndGet()
        audioQueue.clear()
        captionText.reset()
        if (enabled.get()) {
            stateMutable.value = LiveCaptionState(
                enabled = true,
                status = LiveCaptionState.Status.STARTING,
            )
            audioQueue.offer(AudioEvent.EngineChanged(id, generation))
            startWorker()
        } else {
            audioQueue.offer(AudioEvent.Stop)
            stateMutable.value = LiveCaptionState()
        }
    }

    fun clearVisibleCaption() {
        captionText.reset()
        if (enabled.get()) {
            stateMutable.value = stateMutable.value.copy(text = "")
        }
    }

    /** Invalidates queued audio when the player view changes ownership. */
    fun resetForPlaybackTransition() {
        if (closed.get()) return
        val generation = audioGeneration.incrementAndGet()
        audioQueue.clear()
        captionText.reset()
        if (enabled.get()) {
            audioQueue.offer(AudioEvent.Reset(generation))
            stateMutable.value = stateMutable.value.copy(text = "")
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enabled.set(false)
        audioQueue.clear()
        audioQueue.offer(AudioEvent.Stop)
        captionText.reset()
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
        var metricsEngineId = selectedEngineId.get().preferenceValue
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
        val droppedBuffersBaseline = EngineDropBaseline(droppedAudioBuffers.get())

        fun resetMetrics(id: LiveCaptionEngineId) {
            metricsEngineId = id.preferenceValue
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
                if (visibleCaptionExpiresAtMs != 0L &&
                    SystemClock.elapsedRealtime() >= visibleCaptionExpiresAtMs
                ) {
                    clearVisibleCaption()
                    visibleCaptionExpiresAtMs = 0L
                }

                val event = audioQueue.poll(QUEUE_POLL_INTERVAL_MS) ?: continue
                when (event) {
                    AudioEvent.Stop -> {
                        closeEngine()
                        audioQueue.clear()
                        if (!enabled.get()) break
                    }

                    is AudioEvent.EngineChanged -> {
                        if (event.generation != audioGeneration.get()) continue
                        closeEngine()
                        captionText.reset()
                        visibleCaptionExpiresAtMs = 0L
                        resetMetrics(event.id)
                    }

                    is AudioEvent.Reset -> {
                        if (event.generation != audioGeneration.get()) continue
                        engine?.reset()
                        engineGeneration = event.generation
                        clearVisibleCaption()
                        visibleCaptionExpiresAtMs = 0L
                    }

                    is AudioEvent.Flush -> {
                        if (!enabled.get()) {
                            closeEngine()
                            audioQueue.clear()
                            break
                        }
                        if (event.generation != audioGeneration.get()) continue
                        if (engine?.id != selectedEngineId.get().preferenceValue) {
                            closeEngine()
                        }
                        engine?.reset()
                        engineGeneration = event.generation
                        clearVisibleCaption()
                        visibleCaptionExpiresAtMs = 0L
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "audio format ${event.sampleRateHz}Hz, " +
                                    "${event.channelCount}ch, encoding=${event.encoding}, " +
                                    "dropped=${droppedBuffersBaseline.delta(droppedAudioBuffers.get())}, " +
                                    "queue=${audioQueue.size}",
                            )
                        }
                    }

                    is AudioEvent.Pcm -> {
                        if (!enabled.get() || event.generation != audioGeneration.get()) continue

                        if (engine != null &&
                            (engineGeneration != event.generation ||
                                engine?.id != selectedEngineId.get().preferenceValue)
                        ) {
                            closeEngine()
                        }
                        if (engine == null) {
                            publishStarting()
                            val selectedId = selectedEngineId.get()
                            resetMetrics(selectedId)
                            val initStartedAt = SystemClock.elapsedRealtime()
                            engine = engineFactory(context, selectedId)
                            engineGeneration = event.generation
                            engineInitMs = SystemClock.elapsedRealtime() - initStartedAt
                            metricsMutable.value = metricsMutable.value.copy(
                                engineId = selectedId.preferenceValue,
                                engineInitMs = engineInitMs,
                            )
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "ASR engine=${selectedId.preferenceValue} initialized in ${engineInitMs}ms")
                            }
                            publishListening()
                        }

                        if (engineGeneration != event.generation) continue
                        val samples = when (event.encoding) {
                            C.ENCODING_PCM_16BIT -> pcm16ToMono(event.bytes, event.channelCount)
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
                        events.forEach { recognition ->
                            when (recognition) {
                                is CaptionRecognitionEvent.Partial -> {
                                    visibleCaptionExpiresAtMs = 0L
                                    if (firstOutputAfterStartMs == null && recognition.text.isNotBlank()) {
                                        firstOutputAfterStartMs = SystemClock.elapsedRealtime() - metricsStartedAtMs
                                    }
                                    publishPartial(recognition)
                                }

                                is CaptionRecognitionEvent.Final -> {
                                    publishFinal(recognition)
                                    visibleCaptionExpiresAtMs =
                                        SystemClock.elapsedRealtime() + FINAL_CAPTION_HOLD_MS
                                }
                            }
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
                        if (BuildConfig.DEBUG && now >= nextMetricsLogMs) {
                            Log.d(
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
        captionText.apply(event)
        stateMutable.value = stateMutable.value.copy(text = captionText.visibleText)
    }

    private fun publishFinal(event: CaptionRecognitionEvent.Final) {
        if (!enabled.get()) return
        captionText.apply(event)
        stateMutable.value = stateMutable.value.copy(text = captionText.visibleText)
    }

    private fun publishError(throwable: Throwable) {
        enabled.set(false)
        audioQueue.clear()
        captionText.reset()
        stateMutable.value = LiveCaptionState(
            status = LiveCaptionState.Status.ERROR,
            error = throwable.message ?: throwable::class.java.simpleName,
        )
        if (BuildConfig.DEBUG) Log.e(TAG, "ASR failed", throwable)
    }

    private fun publishMetrics(value: LiveCaptionMetrics) {
        metricsMutable.value = value.copy(
            droppedAudioBuffers = value.droppedAudioBuffers,
        )
    }

    private companion object {
        const val TAG = "LiveCaptionManager"
        const val METRICS_LOG_INTERVAL_MS = 10_000L
        const val QUEUE_POLL_INTERVAL_MS = 100L
        const val FINAL_CAPTION_HOLD_MS = 2_000L
    }
}
