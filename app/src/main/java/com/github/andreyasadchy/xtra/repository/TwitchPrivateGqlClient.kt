package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresExtension
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsField
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFieldKey
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkInterferenceReporter
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ExecutorService

class TwitchPrivateGqlClient(
    private val httpEngine: Lazy<HttpEngine?>,
    private val httpExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
    private val twitchWebSessionManager: TwitchWebSessionManager,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    suspend fun executePersisted(
        networkLibrary: String?,
        headers: Map<String, String>,
        operation: TwitchWebOperation,
        variables: JsonObject,
    ): JsonObject {
        val hash = operation.sha256Hash ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation.operationName))
        val request = buildJsonObject {
            put("operationName", operation.operationName)
            put("variables", variables)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        return execute(networkLibrary, headers, operation.operationName, request)
    }

    suspend fun executeDocument(
        networkLibrary: String?,
        headers: Map<String, String>,
        operationName: String,
        document: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val request = buildJsonObject {
            put("operationName", operationName)
            put("query", document)
            put("variables", variables)
        }
        return execute(networkLibrary, headers, operationName, request)
    }

    private suspend fun execute(
        networkLibrary: String?,
        headers: Map<String, String>,
        operationName: String,
        request: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val logger = diagnosticsLogger
        val token = if (logger?.isEnabled == true) {
            logger.beginRequest(
                category = DiagnosticsCategory.GQL,
                transport = DiagnosticsTransport.GQL,
                operation = operationName,
                fields = buildList {
                    networkLibrary?.let {
                        add(DiagnosticsField(DiagnosticsFieldKey.NETWORK_LIBRARY, it))
                    }
                    add(DiagnosticsField(DiagnosticsFieldKey.REQUEST_BYTES, request.toString().toByteArray().size.toString()))
                    add(DiagnosticsField(DiagnosticsFieldKey.AUTHENTICATED, "true"))
                },
            )
        } else null
        val response = try {
            twitchWebSessionManager.executeIntegrityAwareGql(
                fallbackHeaders = headers,
                requireActiveWebSession = true,
                isFailedIntegrityCheck = { response -> hasFailedIntegrityCheck(response.body) },
                send = { requestHeaders -> post(networkLibrary, requestHeaders, request.toString()) },
                diagnosticsOperation = operationName,
                diagnosticsCorrelationId = token?.correlationId,
            )
        } catch (error: MissingAuthenticationException) {
            logger?.failRequest(token, "authentication_required")
            throw TwitchInboxException(TwitchInboxError.RequiresReauth, error)
        } catch (error: CancellationException) {
            logger?.failRequest(token, "cancelled")
            throw error
        } catch (error: Throwable) {
            NetworkInterferenceReporter.report("gql.twitch.tv", error)
            logger?.failRequest(token, diagnosticsErrorCode(error))
            throw TwitchInboxException(TwitchInboxError.Network, error)
        }
        privateGqlHttpError(response.statusCode)?.let {
            logger?.finishRequest(
                token,
                successful = false,
                httpStatus = response.statusCode,
                code = "http_error",
                fields = token?.let { diagnosticsResponseFields(response.body) }.orEmpty(),
            )
            throw TwitchInboxException(it)
        }
        val body = try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (error: Throwable) {
            logger?.finishRequest(
                token,
                successful = false,
                httpStatus = response.statusCode,
                code = "response_parse_error",
                fields = token?.let { diagnosticsResponseFields(response.body) }.orEmpty(),
            )
            throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operationName), error)
        }
        val errors = body["errors"]?.jsonArray
        if (errors != null && errors.isNotEmpty()) {
            val message = errors.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
            logger?.finishRequest(
                token,
                successful = false,
                httpStatus = response.statusCode,
                code = "graphql_error_${diagnosticsGraphQlCode(message, response.statusCode)}",
                fields = token?.let { diagnosticsResponseFields(response.body) }.orEmpty(),
            )
            throw TwitchInboxException(mapError(operationName, message, response.statusCode))
        }
        logger?.finishRequest(
            token,
            successful = true,
            httpStatus = response.statusCode,
            fields = token?.let { diagnosticsResponseFields(response.body) }.orEmpty(),
        )
        body
    }

    private fun diagnosticsResponseFields(body: String): List<DiagnosticsField> = buildList {
        add(DiagnosticsField(DiagnosticsFieldKey.RESPONSE_BYTES, body.toByteArray().size.toString()))
        runCatching {
            json.parseToJsonElement(body).jsonObject["errors"]
                ?.let { errors ->
                    if (errors is kotlinx.serialization.json.JsonArray) {
                        add(DiagnosticsField(DiagnosticsFieldKey.ERROR_COUNT, errors.size.toString()))
                    }
                }
        }
    }

    private fun diagnosticsErrorCode(error: Throwable): String = when {
        NetworkInterferenceReporter.isDnsResolutionFailure(error) -> "dns_resolution_failed"
        error is java.io.IOException -> "io_error"
        else -> "request_failed"
    }

    private fun mapError(operation: String, message: String?, statusCode: Int): TwitchInboxError {
        return privateGqlError(operation, message, statusCode)
    }

    private fun hasFailedIntegrityCheck(response: String): Boolean = runCatching {
        json.parseToJsonElement(response).jsonObject["errors"]?.jsonArray
            ?.any { error ->
                error.jsonObject["message"]?.jsonPrimitive?.content
                    ?.trim()
                    ?.equals(C.FAILED_INTEGRITY_CHECK, ignoreCase = true) == true
            } == true
    }.getOrDefault(false)

    private data class Response(val statusCode: Int, val body: String)

    @SuppressLint("NewApi")
    private suspend fun post(networkLibrary: String?, headers: Map<String, String>, body: String): Response {
        val url = "https://gql.twitch.tv/gql"
        return when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> postHttpEngine(url, headers, body)
            else -> okHttpClient.value.newCall(Request.Builder().url(url).headers(headers.toHeaders()).header("Content-Type", "application/json").post(body.toRequestBody()).build()).executeAsync().use {
                Response(it.code, it.body.string())
            }
        }
    }

    @SuppressLint("NewApi")
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    private suspend fun postHttpEngine(url: String, headers: Map<String, String>, body: String): Response {
        val response = suspendCancellableCoroutine<NetworkUtils.HttpEngineResponse> { continuation ->
            val timeout = NetworkUtils.HttpEngineTimeout()
            val request = httpEngine.value!!.newUrlRequestBuilder(url, httpExecutor.value, NetworkUtils.ByteArrayUrlCallback(continuation, timeout)).apply {
                headers.forEach { addHeader(it.key, it.value) }
                addHeader("Content-Type", "application/json")
                setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), httpExecutor.value)
            }.build()
            timeout.start(request, continuation)
            request.start()
            continuation.invokeOnCancellation { request.cancel(); timeout.stop() }
        }
        return Response(response.info.httpStatusCode, response.body.decodeToString())
    }

}

internal fun diagnosticsGraphQlCode(message: String?, statusCode: Int): String {
    val lower = message.orEmpty().lowercase()
    return when {
        statusCode == 401 || lower.contains("unauthenticated") || lower.contains("authentication") ||
            lower.contains("token expired") || lower.contains("invalid token") -> "authentication"
        statusCode == 429 || lower.contains("rate limit") -> "rate_limited"
        lower.contains("first") && (lower.contains("limit") || lower.contains("maximum") || lower.contains("greater")) -> "invalid_page_size"
        lower.contains("persistedquerynotfound") || lower.contains("persisted query not found") -> "persisted_query_missing"
        lower.contains("internal server") || lower.contains("service unavailable") -> "server_error"
        else -> "rejected"
    }
}

internal fun privateGqlError(operation: String, message: String?, statusCode: Int): TwitchInboxError {
    val lower = message.orEmpty().lowercase()
    return when {
        statusCode == 401 || lower.contains("unauthenticated") || lower.contains("authentication") ||
            lower.contains("token expired") || lower.contains("invalid token") -> TwitchInboxError.RequiresReauth
        statusCode == 429 || lower.contains("rate limit") -> TwitchInboxError.RateLimited()
        lower.contains("persistedquerynotfound") || lower.contains("persisted query not found") ->
            TwitchInboxError.PrivateApiChanged(operation)
        lower.contains("internal server") || lower.contains("service unavailable") -> TwitchInboxError.TwitchServerError
        else -> TwitchInboxError.GraphQl(operation, message?.take(160))
    }
}

internal fun privateGqlHttpError(statusCode: Int): TwitchInboxError? = when {
    statusCode == 401 -> TwitchInboxError.RequiresReauth
    statusCode == 429 -> TwitchInboxError.RateLimited()
    statusCode >= 500 -> TwitchInboxError.TwitchServerError
    else -> null
}
