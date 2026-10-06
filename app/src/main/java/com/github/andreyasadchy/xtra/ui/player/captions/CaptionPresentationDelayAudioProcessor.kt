package com.github.andreyasadchy.xtra.ui.player.captions

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * Captures source-time PCM for ASR and applies its bounded presentation delay in one pass.
 * The ASR worker therefore receives source-time PCM immediately while audible presentation
 * stays a small, bounded distance behind it.
 */
@UnstableApi
internal class CaptionPresentationDelayAudioProcessor(
    private val audioBufferSink: TeeAudioProcessor.AudioBufferSink,
    private val delayMsProvider: () -> Int,
    private val captureAudio: () -> Boolean,
) : BaseAudioProcessor() {
    private var delayBuffer: PcmPresentationDelayBuffer? = null
    private var bytesPerFrame = 0
    private var sampleRateHz = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Stay active so caption toggles do not require rebuilding the audio sink.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (captureAudio()) {
            audioBufferSink.handleBuffer(Util.createReadOnlyByteBuffer(inputBuffer))
        }
        val targetBytes = durationToBytes(
            delayMsProvider().coerceIn(0, MAX_CAPTION_PRESENTATION_DELAY_MS),
        )
        if (targetBytes == 0) {
            // Discard the delayed tail on a delay decrease, as the FIFO does. Keep one
            // owned output copy: Media3's caller retains ownership of the input buffer.
            delayBuffer = null
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }
        val buffer = delayBuffer ?: PcmPresentationDelayBuffer(
            durationToBytes(MAX_CAPTION_PRESENTATION_DELAY_MS),
        ).also { delayBuffer = it }
        val outputByteCount = buffer.outputByteCount(inputBuffer.remaining(), targetBytes)
        val output = replaceOutputBuffer(outputByteCount)
        buffer.process(inputBuffer, targetBytes, output)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        flushSinkIfActive()
        val remaining = delayBuffer?.drain() ?: ByteArray(0)
        if (remaining.isNotEmpty()) {
            replaceOutputBuffer(remaining.size).apply {
                put(remaining)
                flip()
            }
        }
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        delayBuffer = null
        sampleRateHz = inputAudioFormat.sampleRate
        bytesPerFrame = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat.channelCount * 2
        } else {
            0
        }
        flushSinkIfActive()
    }

    override fun onReset() {
        flushSinkIfActive()
        delayBuffer = null
        bytesPerFrame = 0
        sampleRateHz = 0
    }

    private fun flushSinkIfActive() {
        if (isActive) {
            audioBufferSink.flush(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                inputAudioFormat.encoding,
            )
        }
    }

    private fun durationToBytes(durationMs: Int): Int {
        if (bytesPerFrame == 0 || sampleRateHz == 0) return 0
        val frames = sampleRateHz.toLong() * durationMs / 1_000L
        return (frames * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
