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
            var release = fetchJson(url, networkLibrary)
            release.changelogApiUrl()?.let { changelogUrl ->
                val changelog = runCatching { fetchJson(changelogUrl, networkLibrary) }.getOrNull()
                changelog?.get("commits")?.let { commits ->
                    release = JsonObject(release + ("commits" to commits))
                }
            }
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

    private suspend fun fetchJson(url: String, networkLibrary: String?): JsonObject = when {
        networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> fetchWithHttpEngine(url)
        networkLibrary == C.CRONET && cronetEngine.value != null -> fetchWithCronet(url)
        else -> fetchWithOkHttp(url)
    }

    @SuppressLint("NewApi")
    private suspend fun fetchWithHttpEngine(url: String): JsonObject {
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

    private suspend fun fetchWithCronet(url: String): JsonObject {
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

    private suspend fun fetchWithOkHttp(url: String): JsonObject {
        return okHttpClient.value.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Xtra/${BuildConfig.VERSION_NAME}")
                .build()
        ).executeAsync().use { response ->
            parseResponse(response.code, response.body.string())
        }
    }

    private fun parseResponse(statusCode: Int, body: String): JsonObject {
        if (statusCode !in 200..299) {
            throw UpdateException(UpdateErrorMapper.fromHttpCode(statusCode))
        }
        return try {
            json.decodeFromString<JsonObject>(body)
        } catch (error: Throwable) {
            throw UpdateException(UpdateError.InvalidResponse, error, UpdateStage.PARSE)
        }
    }
}

private val githubChangelog = Regex(
    "https://github\\.com/([^/]+/[^/]+)/compare/([^\\s)]+)",
    RegexOption.IGNORE_CASE,
)

private fun JsonObject.changelogApiUrl(): String? =
    this["body"]?.jsonPrimitive?.contentOrNull
        ?.let(githubChangelog::find)
        ?.let { match -> "https://api.github.com/repos/${match.groupValues[1]}/compare/${match.groupValues[2]}" }

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
