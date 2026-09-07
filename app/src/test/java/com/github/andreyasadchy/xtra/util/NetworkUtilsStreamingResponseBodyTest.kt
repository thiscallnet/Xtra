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
import org.chromium.net.CronetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.AbstractMap
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

    @Test
    fun cronetCallbackStreamsChunksAndCancelsWhenConsumerCloses() = runBlocking {
        val harness = startCronetResponse()
        harness.callback.onReadCompleted(harness.request, harness.info, callbackBuffer("cronet"))
        harness.callback.onSucceeded(harness.request, harness.info)
        assertEquals("cronet", readAll(harness.response.body))

        val closed = startCronetResponse()
        closed.response.body.close()
        closed.callback.onCanceled(closed.request, closed.info)
        assertTrue(closed.request.cancelled)
    }

    @Test
    fun cronetCallbackPropagatesFailureTimeoutAndStreamOverflow() = runBlocking {
        val failed = startCronetResponse()
        failed.callback.onFailed(failed.request, failed.info, TestCronetException("network failure"))
        org.junit.Assert.assertThrows(IOException::class.java) { failed.response.body.read(Buffer(), 1) }

        val timedOut = startCronetResponse()
        timedOut.timeout.timeout()
        assertTrue(timedOut.request.cancelled)
        org.junit.Assert.assertThrows(IOException::class.java) { timedOut.response.body.read(Buffer(), 1) }

        val overflow = startCronetResponse(maxBodyBytes = 4)
        overflow.callback.onReadCompleted(overflow.request, overflow.info, callbackBuffer("12345"))
        assertTrue(overflow.request.cancelled)
        org.junit.Assert.assertThrows(IOException::class.java) { overflow.response.body.read(Buffer(), 1) }
        Unit
    }

    private suspend fun startCronetResponse(
        headers: Map<String, List<String>> = emptyMap(),
        maxBodyBytes: Int = 64 * 1024 * 1024,
    ): CronetHarness {
        lateinit var callback: NetworkUtils.StreamingCronetCallback
        lateinit var request: FakeCronetRequest
        lateinit var info: FakeCronetResponseInfo
        lateinit var timeout: NetworkUtils.CronetStreamingTimeout
        val response = kotlinx.coroutines.suspendCancellableCoroutine<NetworkUtils.CronetStreamingResponse> { continuation ->
            timeout = NetworkUtils.CronetStreamingTimeout(60_000L)
            request = FakeCronetRequest()
            info = FakeCronetResponseInfo(headers)
            callback = NetworkUtils.StreamingCronetCallback(continuation, timeout, maxBodyBytes)
            timeout.start(request)
            callback.onResponseStarted(request, info)
        }
        return CronetHarness(callback, request, info, timeout, response)
    }

    private fun readAll(source: okio.BufferedSource): String {
        val output = Buffer()
        while (source.read(output, 4) != -1L) { }
        return output.readUtf8()
    }

    private fun callbackBuffer(value: String): ByteBuffer = ByteBuffer.allocate(value.length).apply {
        put(value.toByteArray())
    }

    private data class CronetHarness(
        val callback: NetworkUtils.StreamingCronetCallback,
        val request: FakeCronetRequest,
        val info: FakeCronetResponseInfo,
        val timeout: NetworkUtils.CronetStreamingTimeout,
        val response: NetworkUtils.CronetStreamingResponse,
    )

    private class FakeCronetRequest : org.chromium.net.UrlRequest() {
        var cancelled = false

        override fun cancel() { cancelled = true }
        override fun followRedirect() = Unit
        override fun read(byteBuffer: ByteBuffer) = Unit
        override fun start() = Unit
        override fun isDone(): Boolean = cancelled
        override fun getStatus(listener: org.chromium.net.UrlRequest.StatusListener) = Unit
    }

    private class FakeCronetResponseInfo(
        private val headers: Map<String, List<String>>,
    ) : org.chromium.net.UrlResponseInfo() {
        override fun getUrl(): String = "https://example.test/image"
        override fun getUrlChain(): List<String> = listOf("https://example.test/image")
        override fun getHttpStatusCode(): Int = 200
        override fun getHttpStatusText(): String = "OK"
        override fun getAllHeadersAsList(): List<Map.Entry<String, String>> = headers.flatMap { (name, values) ->
            values.map { value -> AbstractMap.SimpleImmutableEntry(name, value) }
        }
        override fun getAllHeaders(): Map<String, List<String>> = headers
        override fun wasCached(): Boolean = false
        override fun getNegotiatedProtocol(): String = "h2"
        override fun getProxyServer(): String = ""
        override fun getReceivedByteCount(): Long = 0L
    }

    private class TestCronetException(message: String) : CronetException(message, null)
}
