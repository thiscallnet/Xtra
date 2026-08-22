package com.github.andreyasadchy.xtra.repository

import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.model.helix.follows.FollowsResponse
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelixRepositoryTest {

    @Test
    fun `follow endpoint exposes HTTP authentication rate and server failures`() {
        val repository = repository()
        listOf(401, 403, 429, 500, 503).forEach { statusCode ->
            val error = runCatching {
                repository.ensureHelixSuccess(
                    statusCode = statusCode,
                    rateLimit = HelixRateLimit(null, null, 123L),
                    body = "{\"message\":\"failed\"}",
                )
            }.exceptionOrNull()

            assertTrue("status $statusCode", error is TwitchApiException)
            assertEquals(statusCode, (error as TwitchApiException).statusCode)
        }
    }

    @Test
    fun `follow response preserves pagination cursor for the next request`() {
        val json = Json { ignoreUnknownKeys = true }
        val first = json.decodeFromString<FollowsResponse>(
            """{"data":[{"broadcaster_id":"first"}],"pagination":{"cursor":"cursor-1"}}""",
        )
        val second = json.decodeFromString<FollowsResponse>(
            """{"data":[{"broadcaster_id":"second"}]}""",
        )

        assertEquals("cursor-1", first.pagination?.cursor)
        assertEquals(listOf("first", "second"), first.data.map { it.id } + second.data.map { it.id })
        assertTrue(second.pagination?.cursor.isNullOrBlank())
    }

    private fun repository(): HelixRepository = HelixRepository(
        httpEngine = lazy<HttpEngine?> { null },
        cronetEngine = lazy<CronetEngine?> { null },
        cronetExecutor = lazy { Executors.newSingleThreadExecutor() },
        okHttpClient = lazy { OkHttpClient() },
        json = Json { ignoreUnknownKeys = true },
    )
}
