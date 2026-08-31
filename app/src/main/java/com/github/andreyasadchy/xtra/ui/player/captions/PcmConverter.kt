package com.github.andreyasadchy.xtra.ui.player.captions

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun pcm16ToMono(
    bytes: ByteArray,
    channelCount: Int,
): FloatArray {
    require(channelCount > 0)

    val bytesPerFrame = 2 * channelCount
    val frameCount = bytes.size / bytesPerFrame
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    return FloatArray(frameCount) { frameIndex ->
        var sum = 0f
        repeat(channelCount) {
            sum += input.short.toInt() / 32768.0f
        }
        (sum / channelCount).coerceIn(-1f, 1f)
    }
}

internal fun pcmFloatToMono(
    bytes: ByteArray,
    channelCount: Int,
): FloatArray {
    require(channelCount > 0)

    val bytesPerFrame = 4 * channelCount
    val frameCount = bytes.size / bytesPerFrame
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    return FloatArray(frameCount) {
        var sum = 0f
        repeat(channelCount) {
            sum += input.float
        }
        (sum / channelCount).coerceIn(-1f, 1f)
    }
}

/** Moonshine and Silero VAD use 16 kHz input. */
internal fun resampleTo16k(samples: FloatArray, sampleRateHz: Int): FloatArray {
    require(sampleRateHz > 0)
    if (samples.isEmpty() || sampleRateHz == 16_000) return samples

    val outputSize = (samples.size.toLong() * 16_000L / sampleRateHz)
        .toInt()
        .coerceAtLeast(1)
    return FloatArray(outputSize) { index ->
        val sourcePosition = index.toDouble() * sampleRateHz / 16_000.0
        val left = sourcePosition.toInt().coerceAtMost(samples.lastIndex)
        val right = (left + 1).coerceAtMost(samples.lastIndex)
        val fraction = (sourcePosition - left).toFloat()
        samples[left] + (samples[right] - samples[left]) * fraction
    }
}
