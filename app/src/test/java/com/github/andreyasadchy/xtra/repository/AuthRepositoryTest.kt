package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthRepository
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun `typed validation operations send one authorization scheme`() {
        val authorizationHeaders = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                authorizationHeaders += chain.request().header("Authorization")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"client_id":"client","user_id":"user"}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val repository = AuthRepository(
            httpEngine = lazy { null },
            cronetEngine = lazy { null },
            cronetExecutor = lazy { Executors.newSingleThreadExecutor() },
            okHttpClient = lazy { client },
            json = Json { ignoreUnknownKeys = true },
        )
        val operations = TwitchAuthRepository(repository, "okhttp")

        runBlocking {
            operations.validate("raw-helix-token")
            operations.validateCompatibility("raw-gql-token")
        }

        assertEquals(
            listOf("Bearer raw-helix-token", "OAuth raw-gql-token"),
            authorizationHeaders,
        )
    }

    @Test
    fun `device token exchange form includes the requested scopes`() {
        val form = buildDeviceTokenForm(
            clientId = "public-client",
            deviceCode = "device-code",
            scopes = listOf("user:read:follows", "chat:read"),
        )
        val fields = form.split('&').associate { field ->
            val (key, value) = field.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to
                URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

        assertEquals("public-client", fields["client_id"])
        assertEquals("user:read:follows chat:read", fields["scopes"])
        assertEquals("device-code", fields["device_code"])
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", fields["grant_type"])
    }
}
