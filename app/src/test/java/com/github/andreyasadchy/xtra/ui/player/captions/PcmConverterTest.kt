package com.github.andreyasadchy.xtra.ui.player.captions

import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.github.andreyasadchy.xtra.ui.player.captions.engine.CaptionRecognitionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmConverterTest {

    @Test
    fun stereoPcm16IsDownmixedToMono() {
        val bytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(32767)
            .putShort((-32768).toShort())
            .array()

        val mono = pcm16ToMono(bytes, channelCount = 2)

        assertEquals(1, mono.size)
        assertEquals(0f, mono[0], 0.001f)
    }
}

class CaptionAudioQueueTest {

    @Test
    fun fullQueueDropsWithoutBlocking() {
        val queue = CaptionAudioQueue(capacity = 2)
        val event = AudioEvent.Pcm(ByteArray(4), 16_000, 2, 2, 0)
        assertTrue(queue.offer(event))
        assertTrue(queue.offer(event))

        val startedAt = System.nanoTime()
        val offered = queue.offer(event)
        val elapsedNanos = System.nanoTime() - startedAt

        assertEquals(false, offered)
        assertTrue("offer should be non-blocking", elapsedNanos < 100_000_000L)
    }
}

class CaptionTextStateMachineTest {

    @Test
    fun partialReplacesPartialAndResetClearsCaption() {
        val captions = CaptionTextStateMachine()

        captions.updatePartial("hello")
        captions.updatePartial("hello guys")
        assertEquals("hello guys", captions.visibleText)

        captions.finalize("hello guys today")
        assertEquals("hello guys today", captions.visibleText)

        captions.reset()
        assertEquals("", captions.visibleText)
    }

    @Test
    fun fakeEngineEventsUseTheSharedCaptionStateMachine() {
        val fakeEngine = FakeCaptionEngine(
            listOf(
                CaptionRecognitionEvent.Partial("hello"),
                CaptionRecognitionEvent.Partial("hello guys"),
                CaptionRecognitionEvent.Final("hello guys today"),
            ),
        )
        val captions = CaptionTextStateMachine()

        fakeEngine.accept(FloatArray(160), 16_000).forEach(captions::apply)

        assertEquals("hello guys today", captions.visibleText)
        fakeEngine.reset()
        captions.reset()
        fakeEngine.close()
        assertTrue(fakeEngine.wasClosed)
        assertEquals("", captions.visibleText)
    }

    @Test
    fun displayCaptionIsLimitedToTwoRollingLines() {
        val displayed = formatCaptionTextForDisplay(
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen",
        )

        assertTrue(displayed.lines().size <= 2)
        assertTrue(displayed.length <= 73)
    }

    private class FakeCaptionEngine(
        private val events: List<CaptionRecognitionEvent>,
    ) : com.github.andreyasadchy.xtra.ui.player.captions.engine.LiveCaptionEngine {
        override val id = "fake"
        var wasClosed = false

        override fun accept(samples: FloatArray, sampleRateHz: Int): List<CaptionRecognitionEvent> = events
        override fun reset() = Unit
        override fun close() {
            wasClosed = true
        }
    }
}
