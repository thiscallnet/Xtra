package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.github.andreyasadchy.xtra.BuildConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

@OptIn(UnstableApi::class)
class SherpaZipformerEngine(private val context: Context) : LiveCaptionEngine {
    override val id: String = LiveCaptionEngineId.ZIPFORMER_20M.preferenceValue

    private val recognizer: OnlineRecognizer = createRecognizer()
    private val stream: OnlineStream = recognizer.createStream()

    override fun accept(
        samples: FloatArray,
        sampleRateHz: Int,
    ): List<CaptionRecognitionEvent> {
        if (samples.isEmpty()) return emptyList()

        stream.acceptWaveform(samples, sampleRateHz)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }

        val text = recognizer.getResult(stream).text.trim()
        val events = ArrayList<CaptionRecognitionEvent>(2)
        if (text.isNotEmpty()) {
            events += CaptionRecognitionEvent.Partial(text)
        }

        if (recognizer.isEndpoint(stream)) {
            if (text.isNotEmpty()) {
                events += CaptionRecognitionEvent.Final(text)
            }
            recognizer.reset(stream)
        }

        return events
    }

    override fun reset() {
        recognizer.reset(stream)
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
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
}
