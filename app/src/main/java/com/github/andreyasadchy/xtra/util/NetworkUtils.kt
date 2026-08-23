package com.github.andreyasadchy.xtra.util

import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.chromium.net.CronetException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.net.Proxy
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkUtils {
    /** Preserve the legacy direct retry by default, with an explicit strict-proxy option. */
    fun proxyCandidates(proxy: Proxy, allowDirectFallback: Boolean): List<Proxy> =
        if (allowDirectFallback) listOf(proxy, Proxy.NO_PROXY) else listOf(proxy)

    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    private const val DEFAULT_MAX_BODY_BYTES = 64 * 1024 * 1024
    internal const val MAX_STREAM_BYTES = 8L * 1024 * 1024 * 1024
    private const val BYTE_BUFFER_CAPACITY = 32 * 1024

    /** Copies a streaming response without allowing an unknown-length body to fill storage. */
    fun copyToLimited(input: InputStream, output: OutputStream, maxBytes: Long = MAX_STREAM_BYTES): Long {
        require(maxBytes >= 0) { "Maximum response size cannot be negative" }
        val buffer = ByteArray(BYTE_BUFFER_CAPACITY)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            if (total > maxBytes - count) {
                throw IOException("Response body exceeds the $maxBytes byte limit")
            }
            output.write(buffer, 0, count)
            total += count
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val IDLE_TIMEOUT_MS = 60_000L
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val timeoutQueue = PriorityQueue<Timeout>()
    private var timeoutThread: TimeoutThread? = null

    fun interface ProgressListener {
        fun update(bytesRead: Int)
    }

    class HttpEngineResponse(
        val info: UrlResponseInfo,
        val body: ByteArray,
    )

    class HttpEngineStreamResponse(
        val info: UrlResponseInfo,
        val bytes: Long,
    )

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class ByteArrayUrlCallback(
        private val continuation: CancellableContinuation<HttpEngineResponse>,
        private val timeout: HttpEngineTimeout,
        private val progressListener: ProgressListener? = null,
        private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    ): UrlRequest.Callback {
        private lateinit var mResponseBodyStream: ByteArrayOutputStream
        private lateinit var mResponseBodyChannel: WritableByteChannel

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            val bodyLength = info.headers.asMap[CONTENT_LENGTH_HEADER_NAME]?.takeIf { it.size == 1 }?.getOrNull(0)?.toLongOrNull() ?: -1
            if (bodyLength > maxBodyBytes || bodyLength > MAX_ARRAY_SIZE) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBodyBytes byte limit"))
                return
            }
            mResponseBodyStream = if (bodyLength >= 0) {
                ByteArrayOutputStream(bodyLength.toInt())
            } else {
                ByteArrayOutputStream()
            }
            mResponseBodyChannel = Channels.newChannel(mResponseBodyStream)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            mResponseBodyChannel.write(byteBuffer)
            if (mResponseBodyStream.size() > maxBodyBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBodyBytes byte limit"))
                return
            }
            byteBuffer.clear()
            timeout.updateTimeout()
            progressListener?.update(mResponseBodyStream.size())
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            timeout.complete {
                mResponseBodyChannel.close()
                continuation.resume(HttpEngineResponse(info, mResponseBodyStream.toByteArray()))
            }
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
            fail(error)
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            fail(IOException("Request canceled"))
        }

        private fun fail(error: Throwable) {
            timeout.complete {
                if (::mResponseBodyChannel.isInitialized) mResponseBodyChannel.close()
                continuation.resumeWithException(error)
            }
        }
    }

    /** Streams a response directly to disk so large media never occupies the heap. */
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class OutputStreamUrlCallback(
        private val continuation: CancellableContinuation<HttpEngineStreamResponse>,
        private val timeout: HttpEngineStreamTimeout,
        private val output: OutputStream,
        private val progressListener: ProgressListener? = null,
        private val maxBytes: Long = MAX_STREAM_BYTES,
    ): UrlRequest.Callback {
        private lateinit var channel: WritableByteChannel
        private var bytes = 0L

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            if (info.httpStatusCode !in 200..299) {
                request.cancel()
                fail(IOException("Request failed with HTTP ${info.httpStatusCode}"))
                return
            }
            val contentLength = info.headers.asMap[CONTENT_LENGTH_HEADER_NAME]
                ?.singleOrNull()?.toLongOrNull()
            if (contentLength != null && contentLength > maxBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBytes byte limit"))
                return
            }
            channel = Channels.newChannel(output)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = byteBuffer.remaining().toLong()
            bytes += chunk
            if (bytes > maxBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBytes byte limit"))
                return
            }
            channel.write(byteBuffer)
            progressListener?.update(bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            byteBuffer.clear()
            timeout.updateTimeout()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            timeout.complete {
                channel.close()
                continuation.resume(HttpEngineStreamResponse(info, bytes))
            }
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
            fail(error)
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            fail(IOException("Request canceled"))
        }

        private fun fail(error: Throwable) {
            timeout.complete {
                if (::channel.isInitialized) channel.close() else output.close()
                continuation.resumeWithException(error)
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class ByteArrayUploadProvider(data: ByteArray, offset: Int = 0, length: Int = data.size): UploadDataProvider() {
        private val mUploadBuffer = ByteBuffer.wrap(data, offset, length).slice()

        override fun getLength(): Long {
            return mUploadBuffer.limit().toLong()
        }

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            check(byteBuffer.hasRemaining())
            if (byteBuffer.remaining() >= mUploadBuffer.remaining()) {
                byteBuffer.put(mUploadBuffer)
            } else {
                val oldLimit = mUploadBuffer.limit()
                mUploadBuffer.limit(mUploadBuffer.position() + byteBuffer.remaining())
                byteBuffer.put(mUploadBuffer)
                mUploadBuffer.limit(oldLimit)
            }
            uploadDataSink.onReadSucceeded(false)
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
            mUploadBuffer.position(0)
            uploadDataSink.onRewindSucceeded()
        }
    }

    class CronetResponse(
        val info: org.chromium.net.UrlResponseInfo,
        val body: ByteArray,
    )

    class CronetStreamResponse(
        val info: org.chromium.net.UrlResponseInfo,
        val bytes: Long,
    )

    class ByteArrayCronetCallback(
        private val continuation: CancellableContinuation<CronetResponse>,
        private val timeout: CronetTimeout,
        private val progressListener: ProgressListener? = null,
        private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    ): org.chromium.net.UrlRequest.Callback() {
        private lateinit var mResponseBodyStream: ByteArrayOutputStream
        private lateinit var mResponseBodyChannel: WritableByteChannel

        override fun onRedirectReceived(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            val bodyLength = info.allHeaders[CONTENT_LENGTH_HEADER_NAME]?.takeIf { it.size == 1 }?.getOrNull(0)?.toLongOrNull() ?: -1
            if (bodyLength > maxBodyBytes || bodyLength > MAX_ARRAY_SIZE) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBodyBytes byte limit"))
                return
            }
            mResponseBodyStream = if (bodyLength >= 0) {
                ByteArrayOutputStream(bodyLength.toInt())
            } else {
                ByteArrayOutputStream()
            }
            mResponseBodyChannel = Channels.newChannel(mResponseBodyStream)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            mResponseBodyChannel.write(byteBuffer)
            if (mResponseBodyStream.size() > maxBodyBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBodyBytes byte limit"))
                return
            }
            byteBuffer.clear()
            timeout.updateTimeout()
            progressListener?.update(mResponseBodyStream.size())
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            timeout.complete {
                mResponseBodyChannel.close()
                continuation.resume(CronetResponse(info, mResponseBodyStream.toByteArray()))
            }
        }

        override fun onFailed(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?, error: CronetException) {
            fail(error)
        }

        override fun onCanceled(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?) {
            fail(IOException("Request canceled"))
        }

        private fun fail(error: Throwable) {
            timeout.complete {
                if (::mResponseBodyChannel.isInitialized) mResponseBodyChannel.close()
                continuation.resumeWithException(error)
            }
        }
    }

    /** Cronet counterpart to [OutputStreamUrlCallback]. */
    class OutputStreamCronetCallback(
        private val continuation: CancellableContinuation<CronetStreamResponse>,
        private val timeout: CronetStreamTimeout,
        private val output: OutputStream,
        private val progressListener: ProgressListener? = null,
        private val maxBytes: Long = MAX_STREAM_BYTES,
    ): org.chromium.net.UrlRequest.Callback() {
        private lateinit var channel: WritableByteChannel
        private var bytes = 0L

        override fun onRedirectReceived(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            if (info.httpStatusCode !in 200..299) {
                request.cancel()
                fail(IOException("Request failed with HTTP ${info.httpStatusCode}"))
                return
            }
            val contentLength = info.allHeaders[CONTENT_LENGTH_HEADER_NAME]
                ?.singleOrNull()?.toLongOrNull()
            if (contentLength != null && contentLength > maxBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBytes byte limit"))
                return
            }
            channel = Channels.newChannel(output)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = byteBuffer.remaining().toLong()
            bytes += chunk
            if (bytes > maxBytes) {
                request.cancel()
                fail(IOException("Response body exceeds the $maxBytes byte limit"))
                return
            }
            channel.write(byteBuffer)
            progressListener?.update(bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            byteBuffer.clear()
            timeout.updateTimeout()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            timeout.complete {
                channel.close()
                continuation.resume(CronetStreamResponse(info, bytes))
            }
        }

        override fun onFailed(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?, error: CronetException) {
            fail(error)
        }

        override fun onCanceled(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?) {
            fail(IOException("Request canceled"))
        }

        private fun fail(error: Throwable) {
            timeout.complete {
                if (::channel.isInitialized) channel.close() else output.close()
                continuation.resumeWithException(error)
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class HttpEngineTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: UrlRequest
        lateinit var continuation: CancellableContinuation<HttpEngineResponse>

        fun start(request: UrlRequest, continuation: CancellableContinuation<HttpEngineResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            complete {
                request.cancel()
                continuation.resumeWithException(IOException("Timed out"))
            }
        }
    }

    class CronetTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: org.chromium.net.UrlRequest
        lateinit var continuation: CancellableContinuation<CronetResponse>

        fun start(request: org.chromium.net.UrlRequest, continuation: CancellableContinuation<CronetResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            complete {
                request.cancel()
                continuation.resumeWithException(IOException("Timed out"))
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class HttpEngineStreamTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: UrlRequest
        lateinit var continuation: CancellableContinuation<HttpEngineStreamResponse>

        fun start(request: UrlRequest, continuation: CancellableContinuation<HttpEngineStreamResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            complete {
                request.cancel()
                continuation.resumeWithException(IOException("Timed out"))
            }
        }
    }

    class CronetStreamTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: org.chromium.net.UrlRequest
        lateinit var continuation: CancellableContinuation<CronetStreamResponse>

        fun start(request: org.chromium.net.UrlRequest, continuation: CancellableContinuation<CronetStreamResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            complete {
                request.cancel()
                continuation.resumeWithException(IOException("Timed out"))
            }
        }
    }

    abstract class Timeout(val timeout: Long): Comparable<Timeout> {
        private val completionLock = Any()
        private var completed = false
        var timeoutAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)

        fun complete(action: () -> Unit) {
            val shouldComplete = synchronized(completionLock) {
                if (completed) false else {
                    completed = true
                    true
                }
            }
            if (shouldComplete) {
                stop()
                action()
            }
        }

        fun updateTimeout() {
            lock.withLock {
                timeoutQueue.remove(this)
                timeoutAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
                timeoutQueue.add(this)
                if (timeoutQueue.peek() == this) {
                    condition.signal()
                    if (timeoutThread == null) {
                        val thread = TimeoutThread()
                        timeoutThread = thread
                        thread.start()
                    }
                }
            }
        }

        fun stop() {
            lock.withLock {
                val wasHead = timeoutQueue.peek() == this
                timeoutQueue.remove(this)
                if (wasHead) {
                    condition.signal()
                }
            }
        }

        abstract fun timeout()

        override fun compareTo(other: Timeout): Int {
            return timeoutAt.compareTo(other.timeoutAt)
        }
    }

    private class TimeoutThread: Thread("TimeoutThread") {
        init {
            isDaemon = true
        }

        override fun run() {
            while (true) {
                try {
                    var expired: Timeout? = null
                    lock.withLock {
                        val item = timeoutQueue.peek()
                        if (item == null) {
                            condition.await(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            if (timeoutQueue.peek() == null) {
                                timeoutThread = null
                                return
                            }
                        } else {
                            val waitNanos = item.timeoutAt - System.nanoTime()
                            if (waitNanos > 0) {
                                condition.awaitNanos(waitNanos)
                            } else {
                                expired = timeoutQueue.remove()
                            }
                        }
                    }
                    expired?.timeout()
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    class ProgressInterceptor(val progressListener: ProgressListener?): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return response.newBuilder().apply {
                body(
                    object : ResponseBody() {
                        private var bufferedSource: BufferedSource? = null

                        override fun contentType() = response.body.contentType()

                        override fun contentLength() = response.body.contentLength()

                        override fun source(): BufferedSource {
                            return bufferedSource ?: object : ForwardingSource(response.body.source()) {
                                private var totalBytesRead = 0L

                                override fun read(sink: Buffer, byteCount: Long): Long {
                                    val bytesRead = super.read(sink, byteCount)
                                    if (bytesRead != -1L) {
                                        totalBytesRead += bytesRead
                                        progressListener?.update(totalBytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                    }
                                    return bytesRead
                                }
                            }.buffer().also { bufferedSource = it }
                        }
                    }
                )
            }.build()
        }
    }

    /** Reads small API/media responses without allowing an untrusted body to grow the heap indefinitely. */
    fun ResponseBody.readBytesLimited(maxBytes: Long = DEFAULT_MAX_BODY_BYTES.toLong()): ByteArray {
        require(maxBytes > 0L) { "Response body limit must be positive" }
        val declaredLength = contentLength()
        if (declaredLength > maxBytes) {
            throw IOException("Response body exceeds the $maxBytes byte limit")
        }
        val output = ByteArrayOutputStream(
            declaredLength.takeIf { it >= 0L }
                ?.coerceAtMost(maxBytes)
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt() ?: 0,
        )
        byteStream().use { input ->
            val buffer = ByteArray(BYTE_BUFFER_CAPACITY)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException("Response body exceeds the $maxBytes byte limit")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    suspend fun Call.executeAsync(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                this.cancel()
            }
            this.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: okio.IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response) { _, value, _ ->
                            value.closeQuietly()
                        }
                    }
                },
            )
        }
}
