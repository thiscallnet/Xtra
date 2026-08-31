package com.github.andreyasadchy.xtra.ui.player.captions

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Holds PCM after [androidx.media3.exoplayer.audio.TeeAudioProcessor] has copied it for ASR.
 * The ASR worker therefore receives source-time PCM immediately while audible presentation
 * stays a small, bounded distance behind it.
 */
@UnstableApi
internal class CaptionPresentationDelayAudioProcessor(
    private val delayMsProvider: () -> Int,
) : BaseAudioProcessor() {
    private var delayBuffer: PcmPresentationDelayBuffer? = null
    private var bytesPerFrame = 0
    private var sampleRateHz = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        sampleRateHz = inputAudioFormat.sampleRate
        bytesPerFrame = inputAudioFormat.channelCount * 2
        val capacityBytes = durationToBytes(MAX_CAPTION_PRESENTATION_DELAY_MS)
        delayBuffer = PcmPresentationDelayBuffer(capacityBytes)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val buffer = checkNotNull(delayBuffer)
        val targetBytes = durationToBytes(
            delayMsProvider().coerceIn(0, MAX_CAPTION_PRESENTATION_DELAY_MS),
        )
        val outputByteCount = buffer.outputByteCount(inputBuffer.remaining(), targetBytes)
        val output = replaceOutputBuffer(outputByteCount)
        buffer.process(inputBuffer, targetBytes, output)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        val remaining = delayBuffer?.drain() ?: ByteArray(0)
        if (remaining.isNotEmpty()) {
            replaceOutputBuffer(remaining.size).apply {
                put(remaining)
                flip()
            }
        }
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        delayBuffer?.clear()
    }

    override fun onReset() {
        delayBuffer = null
        bytesPerFrame = 0
        sampleRateHz = 0
    }

    private fun durationToBytes(durationMs: Int): Int {
        if (bytesPerFrame == 0 || sampleRateHz == 0) return 0
        val frames = sampleRateHz.toLong() * durationMs / 1_000L
        return (frames * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
