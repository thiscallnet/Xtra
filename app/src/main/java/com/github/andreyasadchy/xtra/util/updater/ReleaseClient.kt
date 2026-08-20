package com.github.andreyasadchy.xtra.util.updater

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.IOException
import java.util.concurrent.ExecutorService

/** The only component that knows how GitHub release data is fetched. */
internal const val RELEASE_METADATA_ASSET_NAME = "xtra-release-metadata.json"
internal const val RELEASE_METADATA_RESPONSE_KEY = "xtra_release_metadata"

fun interface ReleaseSource {
    suspend fun fetch(url: String, networkLibrary: String?): JsonObject

    suspend fun fetchHistory(url: String, networkLibrary: String?, page: Int = 1): JsonArray? = null
}

class ReleaseClient(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) : ReleaseSource {

    override suspend fun fetch(url: String, networkLibrary: String?): JsonObject {
        return try {
            val release = fetchJson(url, networkLibrary)
            val metadataUrl = release.metadataUrl()
            if (metadataUrl == null) {
                release
            } else {
                JsonObject(release + (RELEASE_METADATA_RESPONSE_KEY to fetchJson(metadataUrl, networkLibrary)))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: UpdateException) {
            throw error
        } catch (error: Throwable) {
            throw UpdateException(UpdateErrorMapper.fromThrowable(error), error)
        }
    }

    override suspend fun fetchHistory(url: String, networkLibrary: String?, page: Int): JsonArray? {
        val historyUrl = historyUrl(url, page) ?: return null
        return try {
            fetchArray(historyUrl, networkLibrary)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: UpdateException) {
            throw error
        } catch (error: Throwable) {
            throw UpdateException(UpdateErrorMapper.fromThrowable(error), error)
        }
    }

    private suspend fun fetchJson(url: String, networkLibrary: String?): JsonObject = fetchElement(url, networkLibrary)
        .asObjectOrNull()
        ?: throw UpdateException(UpdateError.UnexpectedResponse, stage = UpdateStage.PARSE)

    private suspend fun fetchArray(url: String, networkLibrary: String?): JsonArray = fetchElement(url, networkLibrary)
        .asArrayOrNull()
        ?: throw UpdateException(UpdateError.UnexpectedResponse, stage = UpdateStage.PARSE)

    private suspend fun fetchElement(url: String, networkLibrary: String?): JsonElement = when {
        networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> fetchWithHttpEngine(url)
        networkLibrary == C.CRONET && cronetEngine.value != null -> fetchWithCronet(url)
        else -> fetchWithOkHttp(url)
    }

    @SuppressLint("NewApi")
    private suspend fun fetchWithHttpEngine(url: String): JsonElement {
        val response = suspendCancellableCoroutine { continuation ->
            val timeout = NetworkUtils.HttpEngineTimeout()
            val request = httpEngine.value!!.newUrlRequestBuilder(
                url,
                cronetExecutor.value,
                NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
            ).addHeader("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}").build()
            timeout.start(request, continuation)
            request.start()
            continuation.invokeOnCancellation {
                request.cancel()
                timeout.stop()
            }
        }
        return parseResponse(response.info.httpStatusCode, response.body.decodeToString())
    }

    private suspend fun fetchWithCronet(url: String): JsonElement {
        val response = suspendCancellableCoroutine { continuation ->
            val timeout = NetworkUtils.CronetTimeout()
            val request = cronetEngine.value!!.newUrlRequestBuilder(
                url,
                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                cronetExecutor.value,
            ).addHeader("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}").build()
            timeout.start(request, continuation)
            request.start()
            continuation.invokeOnCancellation {
                request.cancel()
                timeout.stop()
            }
        }
        return parseResponse(response.info.httpStatusCode, response.body.decodeToString())
    }

    private suspend fun fetchWithOkHttp(url: String): JsonElement {
        return okHttpClient.value.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                .build()
        ).executeAsync().use { response ->
            parseResponse(response.code, response.body.string())
        }
    }

    private fun parseResponse(statusCode: Int, body: String): JsonElement {
        if (statusCode !in 200..299) {
            throw UpdateException(UpdateErrorMapper.fromHttpCode(statusCode))
        }
        return try {
            json.parseToJsonElement(body)
        } catch (error: Throwable) {
            throw UpdateException(UpdateError.InvalidResponse, error, UpdateStage.PARSE)
        }
    }

    private fun historyUrl(url: String, page: Int): String? {
        val baseUrl = when {
            url.endsWith("/releases/latest") -> url.removeSuffix("/latest")
            url.endsWith("/releases/tags/latest") -> url.removeSuffix("/tags/latest")
            else -> return null
        }
        val separator = if ('?' in baseUrl) '&' else '?'
        return "$baseUrl${separator}per_page=$RELEASE_HISTORY_PAGE_SIZE&page=$page"
    }
}

internal const val RELEASE_HISTORY_PAGE_SIZE = 100

private fun JsonObject.metadataUrl(): String? = runCatching {
    this["assets"]?.jsonArray?.firstNotNullOfOrNull { element: JsonElement ->
        val asset = element.jsonObject
        if (asset["name"]?.jsonPrimitive?.contentOrNull == RELEASE_METADATA_ASSET_NAME) {
            asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
        } else {
            null
        }
    }
}.getOrNull()

private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.asArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
