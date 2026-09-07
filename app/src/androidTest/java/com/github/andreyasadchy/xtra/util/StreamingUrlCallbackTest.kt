package com.github.andreyasadchy.xtra.util

import android.net.http.HttpEngine
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class StreamingUrlCallbackTest {
    @Test
    fun httpEngineStreamsUnknownLengthChunksAndEof() = runBlocking {
        withResponse(
            responseHeaders = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n",
            writeBody = { output, _ ->
                output.write("one".toByteArray())
                output.flush()
                output.write("two".toByteArray())
                output.flush()
            },
        ) { response, _ ->
            val output = Buffer()
            while (response.body.read(output, 2) != -1L) { }
            assertEquals("onetwo", output.readUtf8())
        }
    }

    @Test
    fun httpEngineRejectsDeclaredOversizeAndStreamOverflow() = runBlocking {
        val declaredError = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking {
                withResponse(
                    responseHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n",
                    maxBodyBytes = 4,
                    writeBody = { _, _ -> },
                ) { _, _ -> error("response should not be delivered") }
            }
        }
        assertTrue(declaredError.message.orEmpty().contains("4 byte limit"))

        val overflowError = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking {
                withResponse(
                    responseHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n",
                    maxBodyBytes = 4,
                    writeBody = { output, _ -> output.write("12345".toByteArray()); output.flush() },
                ) { response, _ ->
                    response.body.read(Buffer(), 1)
                }
            }
        }
        assertTrue(overflowError.message.orEmpty().contains("4 byte limit"))
    }

    @Test
    fun httpEnginePropagatesPostHeaderFailureTimeoutAndConsumerClose() = runBlocking {
        val postHeaderError = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking {
                withResponse(
                    responseHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n",
                    writeBody = { output, _ -> output.write('x'.code); output.flush() },
                ) { response, _ ->
                    val output = Buffer()
                    while (response.body.read(output, 1) != -1L) { }
                }
            }
        }
        assertTrue(postHeaderError is IOException)

        val timedOut = org.junit.Assert.assertThrows(IOException::class.java) {
            runBlocking {
                withResponse(
                    responseHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: keep-alive\r\n\r\n",
                    timeoutMs = 100L,
                    writeBody = { _, _ -> Thread.sleep(1_000L) },
                ) { response, _ ->
                    response.body.read(Buffer(), 1)
                }
            }
        }
        assertTrue(timedOut is IOException)

        val closeObserved = CountDownLatch(1)
        withResponse(
            responseHeaders = "HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: keep-alive\r\n\r\n",
            writeBody = { output, socket ->
                try {
                    output.write(ByteArray(512 * 1024))
                    output.flush()
                    if (socket.getInputStream().read() < 0) closeObserved.countDown()
                } catch (_: IOException) {
                    closeObserved.countDown()
                }
            },
        ) { response, _ -> response.body.close() }
        assertTrue(closeObserved.await(2, TimeUnit.SECONDS))
    }

    private suspend fun <T> withResponse(
        responseHeaders: String,
        maxBodyBytes: Int = 64 * 1024 * 1024,
        timeoutMs: Long = 20_000L,
        writeBody: (java.io.OutputStream, Socket) -> Unit,
        block: suspend (NetworkUtils.HttpEngineStreamingResponse, NetworkUtils.HttpEngineStreamingTimeout) -> T,
    ): T {
        val server = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = thread(start = true, isDaemon = true, name = "streaming-test-server") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    readRequest(socket)
                    socket.getOutputStream().apply {
                        write(responseHeaders.toByteArray())
                        flush()
                        writeBody(this, socket)
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val engine = HttpEngine.Builder(InstrumentationRegistry.getInstrumentation().targetContext)
            .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0L)
            .setEnableQuic(false)
            .setEnableHttp2(false)
            .build()
        try {
            lateinit var timeout: NetworkUtils.HttpEngineStreamingTimeout
            val response = suspendCancellableCoroutine<NetworkUtils.HttpEngineStreamingResponse> { continuation ->
                timeout = NetworkUtils.HttpEngineStreamingTimeout(timeoutMs)
                val request = engine.newUrlRequestBuilder(
                    "http://localhost:${server.localPort}/image",
                    executor,
                    NetworkUtils.StreamingUrlCallback(continuation, timeout, maxBodyBytes),
                ).build()
                timeout.start(request)
                request.start()
                continuation.invokeOnCancellation {
                    request.cancel()
                    timeout.stop()
                }
            }
            return block(response, timeout)
        } finally {
            engine.shutdown()
            executor.shutdownNow()
            server.close()
            serverThread.join(2_000)
            serverFailure.get()?.let { error ->
                if (error !is IOException) throw error
            }
        }
    }

    private fun readRequest(socket: Socket) {
        var matched = 0
        while (matched < 4) {
            val next = socket.getInputStream().read()
            if (next < 0) return
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
        }
    }
}
