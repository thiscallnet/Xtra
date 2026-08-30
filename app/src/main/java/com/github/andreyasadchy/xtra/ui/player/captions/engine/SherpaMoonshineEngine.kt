package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.player.captions.resampleTo16k
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Moonshine's offline model driven by VAD and optional periodic re-decodes. */
class SherpaMoonshineEngine(
    private val context: Context,
    private val emitPartials: Boolean = true,
) : LiveCaptionEngine {
    override val id: String = LiveCaptionEngineId.MOONSHINE_V2_TINY.preferenceValue

    private val recognizer = createRecognizer()
    private val vad = createVad()
    private val partialDecodeIntervalMs = context.prefs().getInt(
        C.PLAYER_LIVE_CAPTION_PARTIAL_INTERVAL_MS,
        DEFAULT_PARTIAL_DECODE_INTERVAL_MS.toInt(),
    ).coerceIn(MIN_PARTIAL_DECODE_INTERVAL_MS.toInt(), MAX_PARTIAL_DECODE_INTERVAL_MS.toInt()).toLong()
    private val pendingWindow = FloatArray(VAD_WINDOW_SAMPLES)
    private var pendingWindowSize = 0
    private val preRoll = FloatRingBuffer(PRE_ROLL_SAMPLES_AT_16K)
    private val utterance = FloatBuffer(MAX_UTTERANCE_SAMPLES)
    private var speechActive = false
    private var audioPositionSamples = 0L
    private var nextPartialDecodeMs = DEFAULT_PARTIAL_DECODE_INTERVAL_MS

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
    }

    override fun close() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
    }

    private fun processVadWindow(events: MutableList<CaptionRecognitionEvent>) {
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
            nextPartialDecodeMs = audioPositionMs() + partialDecodeIntervalMs
        } else if (wasSpeechActive) {
            appendToUtteranceOrFinalize(events)
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
            nextPartialDecodeMs = audioPositionMs() + partialDecodeIntervalMs
        }

        if (speechActive && !emittedFinal) {
            val nowMs = audioPositionMs()
            if (shouldDecodeMoonshinePartial(
                    emitPartials = emitPartials,
                    speechActive = speechActive,
                    utteranceSize = utterance.size,
                    nowMs = nowMs,
                    nextPartialDecodeMs = nextPartialDecodeMs,
                )
            ) {
                decode(utterance.toArray()).takeIf(String::isNotEmpty)?.let {
                    events += CaptionRecognitionEvent.Partial(it)
                }
                nextPartialDecodeMs = nowMs + partialDecodeIntervalMs
            }
        }
    }

    private fun appendToUtteranceOrFinalize(events: MutableList<CaptionRecognitionEvent>) {
        if (utterance.size + pendingWindow.size > utterance.capacity) {
            decode(utterance.toArray()).takeIf(String::isNotEmpty)?.let {
                events += CaptionRecognitionEvent.Final(it)
            }
            utterance.clear()
            vad.reset()
            speechActive = false
            preRoll.clear()
            return
        }
        utterance.append(pendingWindow)
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
        const val MIN_SILENCE_SECONDS = 0.4f
        const val MIN_SPEECH_SECONDS = 0.2f
        const val MAX_SPEECH_SECONDS = 10.0f
        const val DEFAULT_PARTIAL_DECODE_INTERVAL_MS = 300L
        const val MIN_PARTIAL_DECODE_INTERVAL_MS = 200L
        const val MAX_PARTIAL_DECODE_INTERVAL_MS = 2_000L
        const val PRE_ROLL_SAMPLES_AT_16K = 6_400
        const val MAX_UTTERANCE_SAMPLES = TARGET_SAMPLE_RATE * MAX_SPEECH_SECONDS.toInt()
    }
}

internal fun shouldDecodeMoonshinePartial(
    emitPartials: Boolean,
    speechActive: Boolean,
    utteranceSize: Int,
    nowMs: Long,
    nextPartialDecodeMs: Long,
): Boolean {
    return emitPartials &&
        speechActive &&
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
