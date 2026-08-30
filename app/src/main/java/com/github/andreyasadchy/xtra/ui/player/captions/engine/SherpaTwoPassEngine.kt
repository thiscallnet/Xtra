package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context

class SherpaTwoPassEngine(context: Context) : LiveCaptionEngine {
    override val id: String = LiveCaptionEngineId.ZIPFORMER_MOONSHINE_2PASS.preferenceValue

    private val streaming = SherpaZipformerEngine(context)
    private val finalizer = SherpaMoonshineEngine(
        context = context,
        emitPartials = false,
    )

    override fun accept(
        samples: FloatArray,
        sampleRateHz: Int,
    ): List<CaptionRecognitionEvent> {
        return mergeTwoPassEvents(
            streaming.accept(samples, sampleRateHz),
            finalizer.accept(samples, sampleRateHz),
        )
    }

    override fun reset() {
        streaming.reset()
        finalizer.reset()
    }

    override fun close() {
        streaming.close()
        finalizer.close()
    }
}

internal fun mergeTwoPassEvents(
    streamingEvents: List<CaptionRecognitionEvent>,
    finalizerEvents: List<CaptionRecognitionEvent>,
): List<CaptionRecognitionEvent> {
    return buildList {
        // Zipformer owns low-latency partials. Its endpoint finals are intentionally ignored.
        streamingEvents.filterIsInstance<CaptionRecognitionEvent.Partial>().forEach(::add)
        // Moonshine is the authoritative second-pass final/correction.
        finalizerEvents.filterIsInstance<CaptionRecognitionEvent.Final>().forEach(::add)
    }
}
