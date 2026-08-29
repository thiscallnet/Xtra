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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
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

    data object Stop : AudioEvent
}

/** A deliberately non-blocking, bounded handoff between the audio sink and ASR. */
internal class CaptionAudioQueue(capacity: Int = 8) {
    private val queue = ArrayBlockingQueue<AudioEvent>(capacity)

    fun offer(event: AudioEvent): Boolean = queue.offer(event)

    fun take(): AudioEvent = queue.take()

    fun clear() = queue.clear()

    val size: Int get() = queue.size
}

private data class AudioFormatState(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
)

@OptIn(UnstableApi::class)
class LiveCaptionManager(private val context: Context) {

    private val enabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val audioGeneration = AtomicLong(0L)
    private val droppedAudioBuffers = AtomicInteger(0)
    private val audioQueue = CaptionAudioQueue()
    private val stateMutable = MutableStateFlow(LiveCaptionState())
    private val workerLock = Any()
    private val captionText = CaptionTextStateMachine()

    @Volatile
    private var audioFormat: AudioFormatState? = null

    @Volatile
    private var worker: Thread? = null

    val state: StateFlow<LiveCaptionState> = stateMutable.asStateFlow()

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

            // Never wait on the playback path. Caption audio is disposable.
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

    fun clearVisibleCaption() {
        captionText.reset()
        if (enabled.get()) {
            stateMutable.value = stateMutable.value.copy(text = "")
        }
    }

    /** Invalidates queued audio when the player view changes ownership. */
    fun resetForPlaybackTransition() {
        if (closed.get()) return
        audioGeneration.incrementAndGet()
        audioQueue.clear()
        clearVisibleCaption()
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

        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null

        try {
            while (!closed.get()) {
                when (val event = audioQueue.take()) {
                    AudioEvent.Stop -> {
                        releaseRecognizer(recognizer, stream)
                        recognizer = null
                        stream = null
                        audioQueue.clear()
                        if (!enabled.get()) break
                    }

                    is AudioEvent.Flush -> {
                        if (!enabled.get()) {
                            releaseRecognizer(recognizer, stream)
                            recognizer = null
                            stream = null
                            audioQueue.clear()
                            break
                        }
                        if (event.generation != audioGeneration.get()) continue
                        recognizer?.let { activeRecognizer ->
                            stream?.let(activeRecognizer::reset)
                        }
                        clearVisibleCaption()
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "audio format ${event.sampleRateHz}Hz, " +
                                    "${event.channelCount}ch, encoding=${event.encoding}, " +
                                    "dropped=${droppedAudioBuffers.get()}, queue=${audioQueue.size}",
                            )
                        }
                    }

                    is AudioEvent.Pcm -> {
                        if (!enabled.get() || event.generation != audioGeneration.get()) continue

                        if (recognizer == null) {
                            publishStarting()
                            val startedAt = SystemClock.elapsedRealtime()
                            val newRecognizer = createRecognizer()
                            val newStream = newRecognizer.createStream()
                            recognizer = newRecognizer
                            stream = newStream
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    TAG,
                                    "ASR initialized in ${SystemClock.elapsedRealtime() - startedAt}ms",
                                )
                            }
                            publishListening()
                        }

                        val activeRecognizer = checkNotNull(recognizer)
                        val activeStream = checkNotNull(stream)
                        val samples = when (event.encoding) {
                            C.ENCODING_PCM_16BIT -> pcm16ToMono(event.bytes, event.channelCount)
                            else -> continue
                        }
                        if (samples.isEmpty()) continue

                        activeStream.acceptWaveform(samples, event.sampleRateHz)
                        while (activeRecognizer.isReady(activeStream)) {
                            activeRecognizer.decode(activeStream)
                        }

                        if (event.generation != audioGeneration.get()) continue

                        val result = activeRecognizer.getResult(activeStream).text.trim()
                        if (result.isNotEmpty()) publishPartial(result)

                        if (activeRecognizer.isEndpoint(activeStream)) {
                            if (result.isNotEmpty()) publishFinal(result)
                            activeRecognizer.reset(activeStream)
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) publishError(throwable)
        } finally {
            releaseRecognizer(recognizer, stream)
            synchronized(workerLock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun createRecognizer(): OnlineRecognizer {
        val base = "live-captions/en-20m"
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$base/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$base/decoder-epoch-99-avg-1.onnx",
                    joiner = "$base/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$base/tokens.txt",
                numThreads = 2,
                debug = BuildConfig.DEBUG,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        return OnlineRecognizer(context.assets, config)
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

    private fun publishPartial(text: String) {
        if (!enabled.get()) return
        captionText.updatePartial(text)
        stateMutable.value = stateMutable.value.copy(text = captionText.visibleText)
    }

    private fun publishFinal(text: String) {
        if (!enabled.get()) return
        captionText.finalize(text)
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

    private fun releaseRecognizer(
        recognizer: OnlineRecognizer?,
        stream: OnlineStream?,
    ) {
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
    }

    private companion object {
        const val TAG = "LiveCaptionManager"
    }
}
