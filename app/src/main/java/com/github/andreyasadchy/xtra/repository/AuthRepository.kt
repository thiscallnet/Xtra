package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthHttpException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthProtocolException
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.util.concurrent.ExecutorService

private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"

internal data class AuthHttpResponse(
    val statusCode: Int,
    val body: String,
)

/** Minimal authentication transport for validating the Gecko-backed Twitch session. */
class AuthRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {
    /** Validates an already formatted Twitch Authorization header for account diagnostics. */
    suspend fun validate(networkLibrary: String?, authorization: String): ValidationResponse = withContext(Dispatchers.IO) {
        val response = executeGet(networkLibrary, VALIDATE_URL, authorization)
        ensureSuccess(response)
        decode(response.body)
    }

    private suspend fun executeGet(
        networkLibrary: String?,
        url: String,
        authorization: String,
    ): AuthHttpResponse = try {
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine<NetworkUtils.HttpEngineResponse> { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply { addHeader("Authorization", authorization) }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                AuthHttpResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine<NetworkUtils.CronetResponse> { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value,
                    ).apply { addHeader("Authorization", authorization) }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                AuthHttpResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
            else -> okHttpClient.value.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", authorization)
                    .build(),
            ).executeAsync().use { response ->
                AuthHttpResponse(response.code, response.body.string())
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw TwitchAuthException("Twitch authentication network request failed", error)
    }

    private fun ensureSuccess(response: AuthHttpResponse) {
        if (response.statusCode !in 200..299) throw httpException(response)
    }

    private fun httpException(response: AuthHttpResponse): TwitchAuthHttpException {
        val error = runCatching {
            json.parseToJsonElement(response.body).jsonObject.let { body ->
                body["error"]?.jsonPrimitive?.contentOrNull to
                    (body["error_description"]?.jsonPrimitive?.contentOrNull
                        ?: body["message"]?.jsonPrimitive?.contentOrNull)
            }
        }.getOrNull()
        return TwitchAuthHttpException(
            statusCode = response.statusCode,
            errorCode = error?.first,
            description = error?.second,
        )
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString(body)
    } catch (error: SerializationException) {
        throw TwitchAuthProtocolException("Malformed Twitch authentication response", error)
    } catch (error: IllegalArgumentException) {
        throw TwitchAuthProtocolException("Malformed Twitch authentication response", error)
    }
}
