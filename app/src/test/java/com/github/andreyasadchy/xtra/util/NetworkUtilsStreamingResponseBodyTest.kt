package com.github.andreyasadchy.xtra.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class NetworkUtilsStreamingResponseBodyTest {
    @Test
    fun multipleChunksAreReadInOrderAndEndWithEof() = runBlocking {
        val body = NetworkUtils.StreamingResponseBody(1024) {}
        val writer = launch(Dispatchers.Default) {
            body.write(ByteBuffer.wrap("first".toByteArray()))
            body.write(ByteBuffer.wrap("-second".toByteArray()))
            body.complete()
        }
        val output = Buffer()
        while (body.source.read(output, 3) != -1L) { }

        writer.join()
        assertEquals("first-second", output.readUtf8())
        assertEquals(-1L, body.source.read(Buffer(), 1))
    }

    @Test
    fun unknownLengthStyleStreamingDoesNotRequireAWholeBodyBuffer() = runBlocking {
        val body = NetworkUtils.StreamingResponseBody(512 * 1024) {}
        val writer = launch(Dispatchers.Default) {
            repeat(32) { body.write(ByteBuffer.wrap(ByteArray(16 * 1024) { it.toByte() })) }
            body.complete()
        }
        val output = Buffer()
        var total = 0L
        while (true) {
            val read = body.source.read(output, 8 * 1024)
            if (read == -1L) break
            total += read
            output.clear()
        }

        writer.join()
        assertEquals(512 * 1024L, total)
    }

    @Test
    fun aResponseThatExceedsTheLimitIsRejectedBeforeWriting() {
        val body = NetworkUtils.StreamingResponseBody(4) {}
        body.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))

        val error = org.junit.Assert.assertThrows(IOException::class.java) {
            body.write(ByteBuffer.wrap(byteArrayOf(5)))
        }

        assertTrue(error.message.orEmpty().contains("4 byte limit"))
        body.cancel()
    }

    @Test
    fun closingTheConsumerSourceCancelsTheProducer() {
        val cancelled = AtomicBoolean(false)
        lateinit var body: NetworkUtils.StreamingResponseBody
        body = NetworkUtils.StreamingResponseBody(1024) {
            cancelled.set(true)
            body.cancel()
        }

        body.source.close()

        assertTrue(cancelled.get())
    }

    @Test
    fun upstreamFailureAfterHeadersWakesTheConsumerWithAnError() {
        val body = NetworkUtils.StreamingResponseBody(1024) {}
        body.cancel()

        org.junit.Assert.assertThrows(IOException::class.java) {
            body.source.read(Buffer(), 1)
        }
    }

    @Test
    fun aSlowConsumerBackpressuresTheProducerAndCancellationUnblocksIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        lateinit var body: NetworkUtils.StreamingResponseBody
        body = NetworkUtils.StreamingResponseBody(1024 * 1024) {
            cancelled.set(true)
            body.cancel()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = scope.launch {
            started.complete(Unit)
            body.write(ByteBuffer.wrap(ByteArray(512 * 1024)))
        }

        withTimeout(1_000) { started.await() }
        delay(50)
        assertFalse(writer.isCompleted)

        body.source.close()
        withTimeout(1_000) { writer.join() }
        assertTrue(cancelled.get())
        scope.cancel()
    }

    private fun readAll(source: okio.BufferedSource): String {
        val output = Buffer()
        while (source.read(output, 4) != -1L) { }
        return output.readUtf8()
    }

    private fun callbackBuffer(value: String): ByteBuffer = ByteBuffer.allocate(value.length).apply {
        put(value.toByteArray())
    }

}
