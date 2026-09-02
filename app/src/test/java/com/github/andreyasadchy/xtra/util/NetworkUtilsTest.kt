package com.github.andreyasadchy.xtra.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsyncChecked
import org.junit.Test

class NetworkUtilsTest {
    private fun clientReturning(statusCode: Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("test")
                .body("body".toResponseBody())
                .build()
        }
        .build()

    @Test
    fun `executeAsync exposes non-success responses`() = runBlocking {
        val request = Request.Builder().url("https://example.test/status").build()

        clientReturning(404).newCall(request).executeAsync().use { response ->
            assertEquals(404, response.code)
            assertEquals("body", response.body.string())
        }
    }

    @Test
    fun `executeAsyncChecked throws the HTTP status`() = runBlocking {
        val request = Request.Builder().url("https://example.test/status").build()

        try {
            clientReturning(404).newCall(request).executeAsyncChecked()
            throw AssertionError("Expected HttpStatusException")
        } catch (error: NetworkUtils.HttpStatusException) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `limited streaming copy writes the complete response`() {
        val output = ByteArrayOutputStream()

        val copied = NetworkUtils.copyToLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            output,
            maxBytes = 4,
        )

        assertEquals(4L, copied)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test(expected = IOException::class)
    fun `limited streaming copy rejects an oversized unknown length response`() {
        NetworkUtils.copyToLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
            ByteArrayOutputStream(),
            maxBytes = 4,
        )
    }
}
