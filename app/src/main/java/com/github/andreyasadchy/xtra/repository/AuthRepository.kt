package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthHttpException
import com.github.andreyasadchy.xtra.repository.auth.TwitchAuthProtocolException
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.UploadDataProviders
import java.util.concurrent.ExecutorService

private const val DEVICE_AUTHORIZATION_URL = "https://id.twitch.tv/oauth2/device"
private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"
private const val REVOKE_URL = "https://id.twitch.tv/oauth2/revoke"
private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal data class AuthHttpResponse(
    val statusCode: Int,
    val body: String,
)

class AuthRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    suspend fun startDeviceAuthorization(
        networkLibrary: String?,
        clientId: String,
        scopes: Collection<String>,
    ): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val response = executePost(
            networkLibrary = networkLibrary,
            url = DEVICE_AUTHORIZATION_URL,
            body = encodeForm(
                mapOf(
                    "client_id" to clientId,
                    "scopes" to scopes.joinToString(" "),
                ),
            ),
        )
        ensureSuccess(response)
        decode<DeviceCodeResponse>(response.body).copy(httpStatusCode = response.statusCode).also {
            if (it.deviceCode.isNullOrBlank() ||
                it.userCode.isNullOrBlank() ||
                (it.verificationUri.isNullOrBlank() && it.verificationUriComplete.isNullOrBlank()) ||
                it.expiresIn == null ||
                it.expiresIn <= 0
            ) {
                throw TwitchAuthProtocolException("Twitch returned an incomplete device authorization response")
            }
        }
    }

    /** Performs one device-code token request. authorization_pending is returned to the poller. */
    suspend fun pollDeviceAuthorization(
        networkLibrary: String?,
        clientId: String,
        deviceCode: String,
        scopes: Collection<String>,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val response = executePost(
            networkLibrary = networkLibrary,
            url = TOKEN_URL,
            body = buildDeviceTokenForm(clientId, deviceCode, scopes),
        )
        val parsed = decode<TokenResponse>(response.body).copy(httpStatusCode = response.statusCode)
        if (response.statusCode >= 500 ||
            (response.statusCode !in 200..299 && parsed.error.isNullOrBlank() && parsed.message.isNullOrBlank())
        ) {
            throw httpException(response)
        }
        parsed
    }

    suspend fun refreshUserToken(
        networkLibrary: String?,
        clientId: String,
        refreshToken: String,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val response = executePost(
            networkLibrary = networkLibrary,
            url = TOKEN_URL,
            body = encodeForm(
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
            ),
        )
        ensureSuccess(response)
        decode<TokenResponse>(response.body).copy(httpStatusCode = response.statusCode).also {
            if (it.accessToken.isNullOrBlank() || it.expiresIn == null || it.expiresIn <= 0) {
                throw TwitchAuthProtocolException("Twitch returned an incomplete refresh response")
            }
        }
    }

    suspend fun validateAccessToken(networkLibrary: String?, accessToken: String): ValidationResponse = withContext(Dispatchers.IO) {
        val response = executeGet(
            networkLibrary = networkLibrary,
            url = VALIDATE_URL,
            authorization = "Bearer $accessToken",
        )
        ensureSuccess(response)
        decode(response.body)
    }

    suspend fun revoke(
        networkLibrary: String?,
        clientId: String,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        val response = executePost(
            networkLibrary = networkLibrary,
            url = REVOKE_URL,
            body = encodeForm(
                mapOf(
                    "client_id" to clientId,
                    "token" to accessToken,
                ),
            ),
        )
        ensureSuccess(response)
    }

    /** Compatibility entry point for callers that already have a Helix/GQL Authorization header. */
    suspend fun validate(networkLibrary: String?, token: String): ValidationResponse = withContext(Dispatchers.IO) {
        val response = executeGet(networkLibrary, VALIDATE_URL, token)
        ensureSuccess(response)
        decode(response.body)
    }

    /** Compatibility entry point retained for non-login cleanup code. */
    suspend fun revoke(networkLibrary: String?, body: String) = withContext(Dispatchers.IO) {
        val response = executePost(networkLibrary, REVOKE_URL, body)
        ensureSuccess(response)
    }

    /** Raw read-only transport for the debug authentication lab. */
    internal suspend fun diagnosticGet(
        networkLibrary: String?,
        url: String,
        headers: Map<String, String>,
    ): AuthHttpResponse {
        val authorization = headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Authentication lab request has no authorization")
        return executeGet(networkLibrary, url, authorization, headers)
    }

    /** Raw JSON POST transport for the debug authentication lab. */
    internal suspend fun diagnosticPost(
        networkLibrary: String?,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): AuthHttpResponse = executePost(
        networkLibrary = networkLibrary,
        url = url,
        body = body,
        headers = headers,
        mediaType = JSON_MEDIA_TYPE,
    )

    /** Legacy raw-body entry point retained for developer tooling during migration. */
    @Deprecated("Use startDeviceAuthorization")
    suspend fun getDeviceCode(networkLibrary: String?, body: String): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val response = executePost(networkLibrary, DEVICE_AUTHORIZATION_URL, body)
        ensureSuccess(response)
        decode<DeviceCodeResponse>(response.body).copy(httpStatusCode = response.statusCode)
    }

    /** Legacy raw-body entry point retained for developer tooling during migration. */
    @Deprecated("Use pollDeviceAuthorization or refreshUserToken")
    suspend fun getToken(networkLibrary: String?, body: String): TokenResponse = withContext(Dispatchers.IO) {
        val response = executePost(networkLibrary, TOKEN_URL, body)
        val parsed = decode<TokenResponse>(response.body).copy(httpStatusCode = response.statusCode)
        if (response.statusCode >= 500 ||
            (response.statusCode !in 200..299 && parsed.error.isNullOrBlank() && parsed.message.isNullOrBlank())
        ) {
            throw httpException(response)
        }
        parsed
    }

    private suspend fun executeGet(
        networkLibrary: String?,
        url: String,
        authorization: String,
        headers: Map<String, String> = emptyMap(),
    ): AuthHttpResponse {
        val requestHeaders = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
        return try {
            when {
                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine<NetworkUtils.HttpEngineResponse> { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply {
                        requestHeaders.forEach { (name, value) -> addHeader(name, value) }
                        addHeader("Authorization", authorization)
                    }.build()
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
                    ).apply {
                        requestHeaders.forEach { (name, value) -> addHeader(name, value) }
                        addHeader("Authorization", authorization)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                AuthHttpResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
                else -> {
                    okHttpClient.value.newCall(
                        Request.Builder()
                            .url(url)
                            .apply {
                                requestHeaders.forEach { (name, value) -> header(name, value) }
                            }
                            .header("Authorization", authorization)
                            .build(),
                    ).executeAsync().use { response ->
                        AuthHttpResponse(response.code, response.body.string())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TwitchAuthException("Twitch authentication network request failed", e)
        }
    }

    private suspend fun executePost(
        networkLibrary: String?,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        mediaType: okhttp3.MediaType = FORM_MEDIA_TYPE,
    ): AuthHttpResponse {
        val bodyBytes = body.toByteArray()
        return try {
            when {
                networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine<NetworkUtils.HttpEngineResponse> { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply {
                        headers.forEach { (name, value) -> addHeader(name, value) }
                        addHeader("Content-Type", mediaType.toString())
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(bodyBytes), cronetExecutor.value)
                    }.build()
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
                    ).apply {
                        headers.forEach { (name, value) -> addHeader(name, value) }
                        addHeader("Content-Type", mediaType.toString())
                        setUploadDataProvider(UploadDataProviders.create(bodyBytes), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                AuthHttpResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
                else -> {
                    okHttpClient.value.newCall(
                        Request.Builder()
                            .url(url)
                            .apply {
                                headers.forEach { (name, value) -> header(name, value) }
                            }
                            .header("Content-Type", mediaType.toString())
                            .post(body.toRequestBody(mediaType))
                            .build(),
                    ).executeAsync().use { response ->
                        AuthHttpResponse(response.code, response.body.string())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TwitchAuthException("Twitch authentication network request failed", e)
        }
    }

    private fun encodeForm(values: Map<String, String>): String {
        val form = FormBody.Builder().apply {
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        return Buffer().apply { form.writeTo(this) }.readUtf8()
    }

    private fun ensureSuccess(response: AuthHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw httpException(response)
        }
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
    } catch (e: SerializationException) {
        throw TwitchAuthProtocolException("Malformed Twitch authentication response", e)
    } catch (e: IllegalArgumentException) {
        throw TwitchAuthProtocolException("Malformed Twitch authentication response", e)
    }
}

internal fun buildDeviceTokenForm(
    clientId: String,
    deviceCode: String,
    scopes: Collection<String>,
): String = encodeAuthForm(
    linkedMapOf(
        "client_id" to clientId,
        "scopes" to scopes.joinToString(" "),
        "device_code" to deviceCode,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
    ),
)

private fun encodeAuthForm(values: Map<String, String>): String {
    val form = FormBody.Builder().apply {
        values.forEach { (key, value) -> add(key, value) }
    }.build()
    return Buffer().apply { form.writeTo(this) }.readUtf8()
}
