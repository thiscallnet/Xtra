package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context
import android.os.SystemClock
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.player.captions.resampleTo16k
import com.github.andreyasadchy.xtra.ui.player.captions.liveCaptionPartialIntervalMs
import com.github.andreyasadchy.xtra.util.prefs
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Moonshine's offline model driven by VAD and periodic simulated-streaming re-decodes. */
class SherpaMoonshineEngine(
    private val context: Context,
) : LiveCaptionEngine {
    override val id: String = MOONSHINE_ENGINE_ID

    private val recognizer = createRecognizer()
    private val vad = createVad()
    private val partialDecodeIntervalMs = context.prefs().liveCaptionPartialIntervalMs().toLong()
    private val pendingWindow = FloatArray(VAD_WINDOW_SAMPLES)
    private var pendingWindowSize = 0
    private val preRoll = FloatRingBuffer(PRE_ROLL_SAMPLES_AT_16K)
    private val utterance = FloatBuffer(MAX_UTTERANCE_SAMPLES)
    private var speechActive = false
    private var audioPositionSamples = 0L
    private var nextPartialDecodeMs = DEFAULT_PARTIAL_DECODE_INTERVAL_MS
    private var previousPartialDecodeDurationMs: Long? = null

    override fun accept(
        samples: FloatArray,
        sampleRateHz: Int,
    ): List<CaptionRecognitionEvent> {
        if (samples.isEmpty()) return emptyList()
        val samplesAt16k = resampleTo16k(samples, sampleRateHz)
        val events = ArrayList<CaptionRecognitionEvent>(2)

        for (sample in samplesAt16k) {
            pendingWindow[pendingWindowSize++] = sample
            if (pendingWindowSize == pendingWindow.size) {
                processVadWindow(events)
                pendingWindowSize = 0
            }
        }
        return events
    }

    override fun reset() {
        vad.reset()
        pendingWindowSize = 0
        preRoll.clear()
        utterance.clear()
        speechActive = false
        audioPositionSamples = 0L
        nextPartialDecodeMs = partialDecodeIntervalMs
        previousPartialDecodeDurationMs = null
    }

    override fun close() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
    }

    private fun processVadWindow(events: MutableList<CaptionRecognitionEvent>) {
        if (speechActive && utterance.size + pendingWindow.size > utterance.capacity) {
            val rollover = prepareMoonshineRollover(
                utterance = utterance.toArray(),
                boundaryWindow = pendingWindow,
                capacity = utterance.capacity,
            )
            decode(rollover.completedUtterance).takeIf(String::isNotEmpty)?.let {
                events += CaptionRecognitionEvent.Final(it)
            }
            utterance.clear()
            vad.reset()
            speechActive = rollover.speechActiveBeforeFreshVad
            preRoll.clear()
        }

        val wasSpeechActive = speechActive
        if (!wasSpeechActive) {
            preRoll.append(pendingWindow)
        }

        vad.acceptWaveform(pendingWindow)
        audioPositionSamples += pendingWindow.size

        if (!wasSpeechActive && vad.isSpeechDetected()) {
            speechActive = true
            utterance.clear()
            utterance.append(preRoll.toArray())
            resetPartialScheduling()
        } else if (wasSpeechActive) {
            utterance.append(pendingWindow)
        }

        var emittedFinal = false
        while (!vad.empty()) {
            val segment = vad.front()
            val text = decode(segment.samples)
            if (text.isNotEmpty()) {
                events += CaptionRecognitionEvent.Final(text)
            }
            vad.pop()
            emittedFinal = true
            speechActive = false
            utterance.clear()
            preRoll.clear()
            resetPartialScheduling()
        }

        if (speechActive && !emittedFinal) {
            val nowMs = audioPositionMs()
            if (shouldDecodeMoonshinePartial(
                    speechActive = speechActive,
                    utteranceSize = utterance.size,
                    nowMs = nowMs,
                    nextPartialDecodeMs = nextPartialDecodeMs,
                )
            ) {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                val partial = decode(utterance.toArray())
                previousPartialDecodeDurationMs =
                    (SystemClock.elapsedRealtime() - decodeStartedAt).coerceAtLeast(0L)
                partial.takeIf(String::isNotEmpty)?.let {
                    events += CaptionRecognitionEvent.Partial(it)
                }
                nextPartialDecodeMs = audioPositionMs() + nextMoonshinePartialIntervalMs(
                    configuredIntervalMs = partialDecodeIntervalMs,
                    previousPartialDecodeDurationMs = previousPartialDecodeDurationMs,
                    firstPartial = false,
                )
            }
        }
    }

    private fun decode(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun resetPartialScheduling() {
        previousPartialDecodeDurationMs = null
        nextPartialDecodeMs = audioPositionMs() + nextMoonshinePartialIntervalMs(
            configuredIntervalMs = partialDecodeIntervalMs,
            previousPartialDecodeDurationMs = null,
            firstPartial = true,
        )
    }

    private fun createRecognizer(): OfflineRecognizer {
        val base = "live-captions/moonshine-v2-tiny-en"
        val moonshine = OfflineMoonshineModelConfig().apply {
            encoder = "$base/encoder_model.ort"
            mergedDecoder = "$base/decoder_model_merged.ort"
        }
        val modelConfig = OfflineModelConfig().apply {
            this.moonshine = moonshine
            tokens = "$base/tokens.txt"
            numThreads = 2
            debug = BuildConfig.DEBUG
            provider = "cpu"
            modelType = "moonshine"
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(sampleRate = TARGET_SAMPLE_RATE, featureDim = 80)
            this.modelConfig = modelConfig
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
        return OfflineRecognizer(context.assets, config)
    }

    private fun createVad(): Vad {
        val silero = SileroVadModelConfig().apply {
            model = "live-captions/moonshine-v2-tiny-en/silero_vad.onnx"
            threshold = VAD_THRESHOLD
            minSilenceDuration = MIN_SILENCE_SECONDS
            minSpeechDuration = MIN_SPEECH_SECONDS
            windowSize = VAD_WINDOW_SAMPLES
            maxSpeechDuration = MAX_SPEECH_SECONDS
        }
        val config = VadModelConfig().apply {
            sileroVadModelConfig = silero
            sampleRate = TARGET_SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
            debug = BuildConfig.DEBUG
        }
        return Vad(context.assets, config)
    }

    private fun audioPositionMs(): Long = audioPositionSamples * 1_000L / TARGET_SAMPLE_RATE

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val VAD_WINDOW_SAMPLES = 512
        const val VAD_THRESHOLD = 0.5f
        const val MIN_SILENCE_SECONDS = 0.25f
        const val MIN_SPEECH_SECONDS = 0.2f
        const val MAX_SPEECH_SECONDS = 6.0f
        const val DEFAULT_PARTIAL_DECODE_INTERVAL_MS = DEFAULT_MOONSHINE_PARTIAL_INTERVAL_MS
        const val PRE_ROLL_SAMPLES_AT_16K = 6_400
        const val MAX_UTTERANCE_SAMPLES = TARGET_SAMPLE_RATE * 8
    }
}

internal const val MOONSHINE_ENGINE_ID = "moonshine_v2_tiny"
internal const val DEFAULT_MOONSHINE_PARTIAL_INTERVAL_MS = 1_000L

internal data class MoonshineRolloverTransition(
    val completedUtterance: FloatArray,
    val boundaryWindowForVad: FloatArray,
    val speechActiveBeforeFreshVad: Boolean,
)

internal fun prepareMoonshineRollover(
    utterance: FloatArray,
    boundaryWindow: FloatArray,
    capacity: Int,
): MoonshineRolloverTransition {
    require(utterance.size + boundaryWindow.size > capacity)
    return MoonshineRolloverTransition(
        completedUtterance = utterance,
        boundaryWindowForVad = boundaryWindow,
        speechActiveBeforeFreshVad = false,
    )
}

internal fun nextMoonshinePartialIntervalMs(
    configuredIntervalMs: Long,
    previousPartialDecodeDurationMs: Long?,
    firstPartial: Boolean,
): Long {
    val configured = configuredIntervalMs.coerceAtLeast(0L)
    if (firstPartial) return configured
    return maxOf(configured, (previousPartialDecodeDurationMs ?: 0L) * 2L)
        .coerceAtMost(MAX_ADAPTIVE_PARTIAL_INTERVAL_MS)
}

private const val MAX_ADAPTIVE_PARTIAL_INTERVAL_MS = 2_500L

internal fun shouldDecodeMoonshinePartial(
    speechActive: Boolean,
    utteranceSize: Int,
    nowMs: Long,
    nextPartialDecodeMs: Long,
): Boolean {
    return speechActive &&
        utteranceSize > 0 &&
        nowMs >= nextPartialDecodeMs
}

private class FloatBuffer(val capacity: Int) {
    private val values = FloatArray(capacity)
    var size: Int = 0
        private set

    fun append(valuesToAdd: FloatArray) {
        val count = minOf(valuesToAdd.size, capacity - size)
        valuesToAdd.copyInto(values, destinationOffset = size, endIndex = count)
        size += count
    }

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = values.copyOf(size)
}

private class FloatRingBuffer(private val capacity: Int) {
    private val values = FloatArray(capacity)
    private var start = 0
    private var size = 0

    fun append(valuesToAdd: FloatArray) {
        for (value in valuesToAdd) {
            val index = (start + size) % capacity
            values[index] = value
            if (size < capacity) {
                size++
            } else {
                start = (start + 1) % capacity
            }
        }
    }

    fun clear() {
        start = 0
        size = 0
    }

    fun toArray(): FloatArray {
        return FloatArray(size) { values[(start + it) % capacity] }
    }
}
