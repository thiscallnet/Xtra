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
