package com.github.andreyasadchy.xtra.ui.player.captions.engine

sealed interface CaptionRecognitionEvent {
    data class Partial(val text: String) : CaptionRecognitionEvent
    data class Final(val text: String) : CaptionRecognitionEvent
}

interface LiveCaptionEngine : AutoCloseable {
    val id: String

    /** Called only by LiveCaptionManager's background recognition worker. */
    fun accept(samples: FloatArray, sampleRateHz: Int): List<CaptionRecognitionEvent>

    fun reset()

    override fun close()
}
