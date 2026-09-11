package com.github.andreyasadchy.xtra.repository

import android.net.http.HttpEngine
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsPhase
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.CronetEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsRequestInstrumentationTest {
    private lateinit var logger: DiagnosticsLogger

    @Before
    fun enableDiagnostics() {
        logger = DiagnosticsLogger(ApplicationProvider.getApplicationContext()).also {
            it.setEnabled(true)
            it.clear()
        }
    }

    @After
    fun disableDiagnostics() {
        logger.setEnabled(false)
    }

    @Test
    fun apolloDecodeFailureRetainsHttpStatusAndUsesDecodeCode() = runBlocking {
        val repository = GraphQLRepository(
            httpEngine = lazyOf<HttpEngine?>(null),
            cronetEngine = lazyOf<CronetEngine?>(null),
            cronetExecutor = lazyOf(Executors.newSingleThreadExecutor()),
            okHttpClient = lazyOf(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{\"data\":[]}".toResponseBody())
                            .build()
                    }
                    .build(),
            ),
            json = Json { ignoreUnknownKeys = true },
            diagnosticsLogger = logger,
        )

        val error = runCatching {
            repository.loadQueryGameBoxArt(
                networkLibrary = null,
                headers = emptyMap(),
                id = "game-id",
            )
        }.exceptionOrNull()

        assertNotNull(error)
        val entries = logger.snapshot()
        val terminal = entries.single { it.phase == DiagnosticsPhase.ERROR }
        assertEquals(200, terminal.httpStatus)
        assertEquals("response_decode_error", terminal.code)
        val correlated = entries.filter { it.correlationId == terminal.correlationId }
        assertEquals(2, correlated.size)
        assertEquals(1, correlated.count { it.phase == DiagnosticsPhase.REQUEST })
        assertEquals(1, correlated.count { it.phase == DiagnosticsPhase.ERROR })
    }

    @Test
    fun twitchHttpFailureIsLoggedAsHttpError() = runBlocking {
        val repository = HelixRepository(
            httpEngine = lazyOf<HttpEngine?>(null),
            cronetEngine = lazyOf<CronetEngine?>(null),
            cronetExecutor = lazyOf(Executors.newSingleThreadExecutor()),
            okHttpClient = lazyOf(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("unavailable")
                            .body("{\"message\":\"unavailable\"}".toResponseBody())
                            .build()
                    }
                    .build(),
            ),
            json = Json { ignoreUnknownKeys = true },
            diagnosticsLogger = logger,
        )

        val error = runCatching {
            repository.getStreamSchedule(
                networkLibrary = null,
                headers = emptyMap(),
                broadcasterId = "broadcaster-id",
            )
        }.exceptionOrNull()

        assertTrue(error is TwitchApiException)
        val terminal = logger.snapshot().first { it.phase == DiagnosticsPhase.ERROR }
        assertEquals(503, terminal.httpStatus)
        assertEquals("http_error", terminal.code)
    }
}
