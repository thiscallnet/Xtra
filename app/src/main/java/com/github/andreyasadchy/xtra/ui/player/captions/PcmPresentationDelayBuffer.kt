package com.github.andreyasadchy.xtra.ui.player.captions

import java.nio.ByteBuffer

/**
 * Bounded FIFO used after the caption PCM tee.
 *
 * Increasing the target holds future PCM. Decreasing it discards the old delayed
 * tail rather than emitting a burst of stale audio while playback catches up.
 */
internal class PcmPresentationDelayBuffer(capacityBytes: Int) {
    private val bytes = ByteArray(capacityBytes.coerceAtLeast(1))
    private var readIndex = 0
    private var size = 0
    private var previousTargetBytes = 0

    fun process(input: ByteArray, targetBytes: Int): ByteArray {
        val source = ByteBuffer.wrap(input)
        val outputCount = outputByteCount(input.size, targetBytes)
        val output = ByteArray(outputCount)
        process(source, targetBytes, ByteBuffer.wrap(output))
        return output
    }

    fun outputByteCount(inputByteCount: Int, targetBytes: Int): Int {
        val boundedTarget = updateTarget(targetBytes)
        return (size + inputByteCount - boundedTarget).coerceAtLeast(0)
    }

    fun process(input: ByteBuffer, targetBytes: Int, output: ByteBuffer) {
        val boundedTarget = updateTarget(targetBytes)
        val outputCount = (size + input.remaining() - boundedTarget).coerceAtLeast(0)
        require(output.remaining() >= outputCount)
        val bufferedOutputCount = minOf(outputCount, size)
        read(output, bufferedOutputCount)

        val directOutputCount = outputCount - bufferedOutputCount
        if (directOutputCount > 0) {
            val originalLimit = input.limit()
            input.limit(input.position() + directOutputCount)
            output.put(input)
            input.limit(originalLimit)
        }

        append(input)
    }

    fun drain(): ByteArray {
        val output = ByteArray(size)
        read(ByteBuffer.wrap(output), size)
        return output
    }

    fun clear() {
        readIndex = 0
        size = 0
    }

    private fun updateTarget(targetBytes: Int): Int {
        val boundedTarget = targetBytes.coerceIn(0, bytes.size)
        if (boundedTarget < previousTargetBytes) clear()
        previousTargetBytes = boundedTarget
        return boundedTarget
    }

    private fun append(input: ByteBuffer) {
        val count = input.remaining()
        require(count <= bytes.size - size)
        val writeIndex = (readIndex + size) % bytes.size
        val firstCount = minOf(count, bytes.size - writeIndex)
        input.get(bytes, writeIndex, firstCount)
        if (count > firstCount) input.get(bytes, 0, count - firstCount)
        size += count
    }

    private fun read(output: ByteBuffer, count: Int) {
        val firstCount = minOf(count, bytes.size - readIndex)
        output.put(bytes, readIndex, firstCount)
        if (count > firstCount) output.put(bytes, 0, count - firstCount)
        readIndex = (readIndex + count) % bytes.size
        size -= count
    }
}
