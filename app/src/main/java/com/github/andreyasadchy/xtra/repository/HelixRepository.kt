package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformation
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformationResponse
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelSearchResponse
import com.github.andreyasadchy.xtra.model.helix.chat.BadgesResponse
import com.github.andreyasadchy.xtra.model.helix.chat.ChatUsersResponse
import com.github.andreyasadchy.xtra.model.helix.chat.ChatSettings
import com.github.andreyasadchy.xtra.model.helix.chat.ChatSettingsResponse
import com.github.andreyasadchy.xtra.model.helix.chat.CheerEmotesResponse
import com.github.andreyasadchy.xtra.model.helix.chat.EmoteSetsResponse
import com.github.andreyasadchy.xtra.model.helix.chat.UserEmotesResponse
import com.github.andreyasadchy.xtra.model.helix.clip.ClipsResponse
import com.github.andreyasadchy.xtra.model.helix.follows.FollowsResponse
import com.github.andreyasadchy.xtra.model.helix.game.GamesResponse
import com.github.andreyasadchy.xtra.model.helix.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUser
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUsersResponse
import com.github.andreyasadchy.xtra.model.helix.user.UsersResponse
import com.github.andreyasadchy.xtra.model.helix.video.VideosResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.UploadDataProviders
import java.io.IOException
import java.util.concurrent.ExecutorService

class TwitchApiException(
    val statusCode: Int,
    val rateLimitResetEpochSeconds: Long?,
    val rateLimitLimit: Long? = null,
    val rateLimitRemaining: Long? = null,
    message: String,
) : IOException(message)

data class HelixRateLimit(
    val limit: Long?,
    val remaining: Long?,
    val resetEpochSeconds: Long?,
)

internal fun buildChannelInformationUpdateBody(
    title: String? = null,
    gameId: String? = null,
    language: String? = null,
    tags: List<String>? = null,
): String = buildJsonObject {
    title?.let { put("title", it) }
    gameId?.let { put("game_id", it) }
    language?.let { put("broadcaster_language", it) }
    tags?.let { values ->
        putJsonArray("tags") {
            values.forEach { add(JsonPrimitive(it)) }
        }
    }
}.toString()

internal fun buildChatSettingsUpdateBody(
    emote: Boolean? = null,
    followers: Boolean? = null,
    followersDuration: Int? = null,
    slow: Boolean? = null,
    slowDuration: Int? = null,
    subs: Boolean? = null,
    unique: Boolean? = null,
): String = buildJsonObject {
    emote?.let { put("emote_mode", it) }
    followers?.let { put("follower_mode", it) }
    followersDuration?.let { put("follower_mode_duration", it) }
    slow?.let { put("slow_mode", it) }
    slowDuration?.let { put("slow_mode_wait_time", it) }
    subs?.let { put("subscriber_mode", it) }
    unique?.let { put("unique_chat_mode", it) }
}.toString()

data class EventSubSubscriptionResult(
    val statusCode: Int,
    val success: Boolean,
    val errorMessage: String? = null,
    val cost: Int? = null,
    val totalCost: Int? = null,
    val maxTotalCost: Int? = null,
    val subscriptionId: String? = null,
    val subscriptionType: String? = null,
    val subscriptionStatus: String? = null,
    val rateLimitResetEpochSeconds: Long? = null,
)

data class EventSubSubscriptionInfo(
    val statusCode: Int,
    val id: String? = null,
    val subscriptionType: String? = null,
    val subscriptionStatus: String? = null,
    val broadcasterUserId: String? = null,
    val transportMethod: String? = null,
    val transportSessionId: String? = null,
    val cost: Int? = null,
    val totalCost: Int? = null,
    val maxTotalCost: Int? = null,
)

internal fun parseEventSubSubscriptionResult(
    json: Json,
    statusCode: Int,
    body: String,
    rateLimitResetEpochSeconds: Long? = null,
): EventSubSubscriptionResult {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    val subscription = root?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
    val rootNumber = { key: String ->
        root?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
    }
    val subscriptionNumber = { key: String ->
        subscription?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
    }
    return EventSubSubscriptionResult(
        statusCode = statusCode,
        success = statusCode in 200..299,
        errorMessage = if (statusCode in 200..299) null else {
            root?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(500)
        },
        cost = subscriptionNumber("cost"),
        totalCost = rootNumber("total_cost"),
        maxTotalCost = rootNumber("max_total_cost"),
        subscriptionId = subscription?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root?.get("id")?.jsonPrimitive?.contentOrNull,
        subscriptionType = subscription?.get("type")?.jsonPrimitive?.contentOrNull,
        subscriptionStatus = subscription?.get("status")?.jsonPrimitive?.contentOrNull,
        rateLimitResetEpochSeconds = rateLimitResetEpochSeconds,
    )
}

internal fun parseEventSubSubscriptionInfo(
    json: Json,
    statusCode: Int,
    body: String,
): EventSubSubscriptionInfo {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    val subscription = root?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
    val condition = subscription?.get("condition")?.jsonObject
    val transport = subscription?.get("transport")?.jsonObject
    val number = { key: String ->
        root?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
    }
    return EventSubSubscriptionInfo(
        statusCode = statusCode,
        id = subscription?.get("id")?.jsonPrimitive?.contentOrNull,
        subscriptionType = subscription?.get("type")?.jsonPrimitive?.contentOrNull,
        subscriptionStatus = subscription?.get("status")?.jsonPrimitive?.contentOrNull,
        broadcasterUserId = condition?.get("broadcaster_user_id")?.jsonPrimitive?.contentOrNull,
        transportMethod = transport?.get("method")?.jsonPrimitive?.contentOrNull,
        transportSessionId = transport?.get("session_id")?.jsonPrimitive?.contentOrNull,
        cost = subscription?.get("cost")?.jsonPrimitive?.content?.toIntOrNull(),
        totalCost = number("total_cost"),
        maxTotalCost = number("max_total_cost"),
    )
}

class HelixRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    suspend fun getGames(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, names: List<String>? = null): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            names?.forEach { appendQueryParameter("name", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getTopGames(networkLibrary: String?, headers: Map<String, String>, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games/top".toUri().buildUpon().apply {
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getStreams(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null, gameId: String? = null, languages: List<String>? = null, limit: Int? = null, offset: String? = null, rateLimitListener: ((HelixRateLimit) -> Unit)? = null): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("user_id", it) }
            logins?.forEach { appendQueryParameter("user_login", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            languages?.forEach { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                val body = response.body.decodeToString()
                val rateLimit = rateLimit(response.info.headers.asMap)
                rateLimitListener?.invoke(rateLimit)
                ensureHelixSuccess(response.info.httpStatusCode, rateLimit, body)
                json.decodeFromString<StreamsResponse>(body)
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                val body = response.body.decodeToString()
                val rateLimit = rateLimit(response.info.allHeaders)
                rateLimitListener?.invoke(rateLimit)
                ensureHelixSuccess(response.info.httpStatusCode, rateLimit, body)
                json.decodeFromString<StreamsResponse>(body)
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    val body = response.body.string()
                    val rateLimit = HelixRateLimit(
                        limit = response.header("Ratelimit-Limit")?.toLongOrNull(),
                        remaining = response.header("Ratelimit-Remaining")?.toLongOrNull(),
                        resetEpochSeconds = response.header("Ratelimit-Reset")?.toLongOrNull(),
                    )
                    rateLimitListener?.invoke(rateLimit)
                    ensureHelixSuccess(response.code, rateLimit, body)
                    json.decodeFromString<StreamsResponse>(body)
                }
            }
        }
    }

    private fun rateLimit(headers: Map<String, List<String>>): HelixRateLimit = HelixRateLimit(
        limit = headerValue(headers, "Ratelimit-Limit")?.toLongOrNull(),
        remaining = headerValue(headers, "Ratelimit-Remaining")?.toLongOrNull(),
        resetEpochSeconds = headerValue(headers, "Ratelimit-Reset")?.toLongOrNull(),
    )

    private fun headerValue(headers: Map<String, List<String>>, name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private fun ensureHelixSuccess(statusCode: Int, rateLimit: HelixRateLimit, body: String) {
        if (statusCode !in 200..299) {
            throw TwitchApiException(
                statusCode = statusCode,
                rateLimitResetEpochSeconds = rateLimit.resetEpochSeconds,
                rateLimitLimit = rateLimit.limit,
                rateLimitRemaining = rateLimit.remaining,
                message = "Twitch Helix request failed with HTTP $statusCode: ${body.take(240)}",
            )
        }
    }

    private data class RawHelixResponse(
        val statusCode: Int,
        val body: String,
    )

    /**
     * Small common transport for account endpoints. Most of the older Helix
     * methods below predate consistent error handling, so account mutations
     * use this path to make HTTP failures visible to the account UI.
     */
    private suspend fun executeRawHelix(
        networkLibrary: String?,
        headers: Map<String, String>,
        url: String,
        method: String = "GET",
        body: String? = null,
    ): RawHelixResponse = withContext(Dispatchers.IO) {
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        body?.let {
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(it.toByteArray()), cronetExecutor.value)
                        }
                        if (method != "GET") {
                            setHttpMethod(method)
                        }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                RawHelixResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value,
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        body?.let {
                            addHeader("Content-Type", "application/json")
                            setUploadDataProvider(UploadDataProviders.create(it.toByteArray()), cronetExecutor.value)
                        }
                        if (method != "GET") {
                            setHttpMethod(method)
                        }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                RawHelixResponse(response.info.httpStatusCode, response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    val requestBody = body?.toRequestBody()
                        ?: if (method == "POST" || method == "PUT" || method == "PATCH") "".toRequestBody() else null
                    if (requestBody != null) {
                        header("Content-Type", "application/json")
                    }
                    if (requestBody != null || method != "GET") {
                        method(method, requestBody)
                    }
                }.build()).executeAsync().use { response ->
                    RawHelixResponse(response.code, response.body.string())
                }
            }
        }.also {
            ensureHelixSuccess(it.statusCode, HelixRateLimit(null, null, null), it.body)
        }
    }

    suspend fun getChannelInformation(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
    ): ChannelInformation? {
        val url = "https://api.twitch.tv/helix/channels".toUri().buildUpon().apply {
            broadcasterId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        return json.decodeFromString<ChannelInformationResponse>(
            executeRawHelix(networkLibrary, headers, url).body,
        ).data.firstOrNull()
    }

    suspend fun updateChannelInformation(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
        title: String? = null,
        gameId: String? = null,
        language: String? = null,
        tags: List<String>? = null,
    ) {
        val url = "https://api.twitch.tv/helix/channels".toUri().buildUpon().apply {
            broadcasterId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        val body = buildChannelInformationUpdateBody(title, gameId, language, tags)
        executeRawHelix(networkLibrary, headers, url, method = "PATCH", body = body)
    }

    suspend fun getChatSettings(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
        moderatorId: String?,
    ): ChatSettings? {
        val url = "https://api.twitch.tv/helix/chat/settings".toUri().buildUpon().apply {
            broadcasterId?.let { appendQueryParameter("broadcaster_id", it) }
            moderatorId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        return json.decodeFromString<ChatSettingsResponse>(
            executeRawHelix(networkLibrary, headers, url).body,
        ).data.firstOrNull()
    }

    suspend fun updateUserDescription(
        networkLibrary: String?,
        headers: Map<String, String>,
        description: String,
    ): com.github.andreyasadchy.xtra.model.helix.user.User? {
        val url = "https://api.twitch.tv/helix/users".toUri().buildUpon().apply {
            appendQueryParameter("description", description)
        }.build().toString()
        return json.decodeFromString<UsersResponse>(
            executeRawHelix(networkLibrary, headers, url, method = "PUT").body,
        ).data.firstOrNull()
    }

    suspend fun getBlockedUsers(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
        limit: Int = 100,
        cursor: String? = null,
    ): BlockedUsersResponse {
        val url = "https://api.twitch.tv/helix/users/blocks".toUri().buildUpon().apply {
            broadcasterId?.let { appendQueryParameter("broadcaster_id", it) }
            appendQueryParameter("first", limit.coerceIn(1, 100).toString())
            cursor?.let { appendQueryParameter("after", it) }
        }.build().toString()
        return json.decodeFromString(executeRawHelix(networkLibrary, headers, url).body)
    }

    suspend fun blockUser(
        networkLibrary: String?,
        headers: Map<String, String>,
        targetUserId: String,
        sourceContext: String = "chat",
        reason: String = "other",
    ) {
        val url = "https://api.twitch.tv/helix/users/blocks".toUri().buildUpon().apply {
            appendQueryParameter("target_user_id", targetUserId)
            appendQueryParameter("source_context", sourceContext)
            appendQueryParameter("reason", reason)
        }.build().toString()
        executeRawHelix(networkLibrary, headers, url, method = "PUT")
    }

    suspend fun unblockUser(
        networkLibrary: String?,
        headers: Map<String, String>,
        targetUserId: String,
    ) {
        val url = "https://api.twitch.tv/helix/users/blocks".toUri().buildUpon().apply {
            appendQueryParameter("target_user_id", targetUserId)
        }.build().toString()
        executeRawHelix(networkLibrary, headers, url, method = "DELETE")
    }

    suspend fun getFollowedStreams(networkLibrary: String?, headers: Map<String, String>, userId: String?, limit: Int?, offset: String?): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<StreamsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getClips(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, channelId: String? = null, gameId: String? = null, startedAt: String? = null, endedAt: String? = null, limit: Int? = null, offset: String? = null): ClipsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/clips".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            startedAt?.let { appendQueryParameter("started_at", it) }
            endedAt?.let { appendQueryParameter("ended_at", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ClipsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ClipsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<ClipsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getVideos(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, gameId: String? = null, channelId: String? = null, period: String? = null, broadcastType: String? = null, sort: String? = null, language: String? = null, limit: Int? = null, offset: String? = null): VideosResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/videos".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            channelId?.let { appendQueryParameter("user_id", it) }
            period?.let { appendQueryParameter("period", it) }
            broadcastType?.let { appendQueryParameter("type", it) }
            sort?.let { appendQueryParameter("sort", it) }
            language?.let { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<VideosResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<VideosResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<VideosResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUsers(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): UsersResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/users".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            logins?.forEach { appendQueryParameter("login", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UsersResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UsersResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<UsersResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchGames(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/categories".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?, live: Boolean? = null): ChannelSearchResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/channels".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
            live?.let { appendQueryParameter("live_only", it.toString()) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChannelSearchResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChannelSearchResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<ChannelSearchResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserFollows(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String? = null, limit: Int? = null, offset: String? = null): FollowsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            targetId?.let { appendQueryParameter("broadcaster_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<FollowsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserFollowers(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String? = null, limit: Int? = null, offset: String? = null): FollowsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/followers".toUri().buildUpon().apply {
            targetId?.let { appendQueryParameter("user_id", it) }
            userId?.let { appendQueryParameter("broadcaster_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<FollowsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserEmotes(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, offset: String?): UserEmotesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/emotes/user".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UserEmotesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UserEmotesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<UserEmotesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getEmotesFromSet(networkLibrary: String?, headers: Map<String, String>, setIds: List<String>): EmoteSetsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/emotes/set".toUri().buildUpon().apply {
            setIds.forEach { appendQueryParameter("emote_set_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<EmoteSetsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<EmoteSetsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<EmoteSetsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getGlobalBadges(networkLibrary: String?, headers: Map<String, String>): BadgesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/badges/global"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<BadgesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<BadgesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<BadgesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getChannelBadges(networkLibrary: String?, headers: Map<String, String>, userId: String?): BadgesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/badges".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<BadgesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<BadgesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<BadgesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getCheerEmotes(networkLibrary: String?, headers: Map<String, String>, userId: String?): CheerEmotesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/bits/cheermotes".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<CheerEmotesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<CheerEmotesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<CheerEmotesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getChatters(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, limit: Int? = null, offset: String? = null): ChatUsersResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/chatters".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChatUsersResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChatUsersResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<ChatUsersResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun createEventSubSubscription(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, type: String?, sessionId: String?): String? =
        createEventSubSubscriptionResult(networkLibrary, headers, userId, channelId, type, sessionId)
            .takeUnless { it.success }
            ?.errorMessage

    /**
     * Returns the raw Get Predictions response. Twitch only permits this endpoint
     * when broadcasterId matches the authenticated user; other channels use
     * Xtra's Hermes live activity stream instead.
     */
    suspend fun getPredictions(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (broadcasterId.isNullOrBlank()) {
            return@withContext null
        }
        val url = "https://api.twitch.tv/helix/predictions".toUri().buildUpon().apply {
            appendQueryParameter("broadcaster_id", broadcasterId)
            appendQueryParameter("first", "1")
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString().takeIf { response.info.httpStatusCode in 200..299 }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value,
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString().takeIf { response.info.httpStatusCode in 200..299 }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    response.body.string().takeIf { response.isSuccessful }
                }
            }
        }
    }

    /**
     * Returns the raw Get Polls response. Twitch restricts this endpoint to
     * the broadcaster represented by the user access token; arbitrary watched
     * channels must use the live Hermes activity stream instead.
     */
    suspend fun getPolls(
        networkLibrary: String?,
        headers: Map<String, String>,
        broadcasterId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (broadcasterId.isNullOrBlank()) {
            return@withContext null
        }
        val url = "https://api.twitch.tv/helix/polls".toUri().buildUpon().apply {
            appendQueryParameter("broadcaster_id", broadcasterId)
            appendQueryParameter("first", "1")
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout),
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString().takeIf { response.info.httpStatusCode in 200..299 }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value,
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                response.body.decodeToString().takeIf { response.info.httpStatusCode in 200..299 }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    response.body.string().takeIf { response.isSuccessful }
                }
            }
        }
    }

    suspend fun createEventSubSubscriptionResult(
        networkLibrary: String?,
        headers: Map<String, String>,
        userId: String?,
        channelId: String?,
        type: String?,
        sessionId: String?,
    ): EventSubSubscriptionResult = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/eventsub/subscriptions"
        val body = buildJsonObject {
            put("type", type)
            put("version", "1")
            putJsonObject("condition") {
                put("broadcaster_user_id", channelId)
                if (type != "stream.online") {
                    put("user_id", userId)
                }
            }
            putJsonObject("transport") {
                put("method", "websocket")
                put("session_id", sessionId)
            }
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                parseEventSubSubscriptionResponse(
                    statusCode = response.info.httpStatusCode,
                    body = response.body.decodeToString(),
                    rateLimitResetEpochSeconds = rateLimit(response.info.headers.asMap).resetEpochSeconds,
                )
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                parseEventSubSubscriptionResponse(
                    statusCode = response.info.httpStatusCode,
                    body = response.body.decodeToString(),
                    rateLimitResetEpochSeconds = rateLimit(response.info.allHeaders).resetEpochSeconds,
                )
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.use {
                        parseEventSubSubscriptionResponse(
                            statusCode = it.code,
                            body = it.body.string(),
                            rateLimitResetEpochSeconds = it.header("Ratelimit-Reset")?.toLongOrNull(),
                        )
                    }
                }
            }
        }
    }

    suspend fun getEventSubSubscription(
        headers: Map<String, String>,
        subscriptionId: String,
    ): EventSubSubscriptionInfo = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/eventsub/subscriptions".toUri().buildUpon()
            .appendQueryParameter("subscription_id", subscriptionId)
            .build()
            .toString()
        okHttpClient.value.newCall(Request.Builder().apply {
            url(url)
            headers(headers.toHeaders())
        }.build()).executeAsync().use { response ->
            parseEventSubSubscriptionInfo(
                json = json,
                statusCode = response.code,
                body = response.body.string(),
            )
        }
    }

    private fun parseEventSubSubscriptionResponse(
        statusCode: Int,
        body: String,
        rateLimitResetEpochSeconds: Long?,
    ): EventSubSubscriptionResult {
        return parseEventSubSubscriptionResult(json, statusCode, body, rateLimitResetEpochSeconds)
    }

    suspend fun sendMessage(networkLibrary: String?, headers: Map<String, String>, userId: String?, channelId: String?, message: String?, replyId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/messages"
        val body = buildJsonObject {
            put("broadcaster_id", channelId)
            put("sender_id", userId)
            put("message", message)
            replyId?.let { put("reply_parent_message_id", it) }
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun sendAnnouncement(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, message: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/announcements".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            put("message", message)
            color?.let { put("color", it) }
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun banUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, targetId: String?, duration: String? = null, reason: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/bans".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            putJsonObject("data") {
                duration?.toIntOrNull()?.let { put("duration", it) }
                put("reason", reason)
                put("user_id", targetId)
            }
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun unbanUser(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/bans".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun deleteMessages(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, messageId: String? = null): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/chat".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
            messageId?.let { appendQueryParameter("message_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    private fun parseChatColor(body: String): String? =
        json.decodeFromString<JsonElement>(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull

    suspend fun getChatColor(networkLibrary: String?, headers: Map<String, String>, userId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/color".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                val body = response.body.decodeToString()
                ensureHelixSuccess(response.info.httpStatusCode, HelixRateLimit(null, null, null), body)
                parseChatColor(body)
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                val body = response.body.decodeToString()
                ensureHelixSuccess(response.info.httpStatusCode, HelixRateLimit(null, null, null), body)
                parseChatColor(body)
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    val body = response.body.string()
                    ensureHelixSuccess(response.code, HelixRateLimit(null, null, null), body)
                    parseChatColor(body)
                }
            }
        }
    }

    suspend fun updateChatColor(networkLibrary: String?, headers: Map<String, String>, userId: String?, color: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/color".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            color?.let { appendQueryParameter("color", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("PUT")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("PUT")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("PUT", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun startCommercial(networkLibrary: String?, headers: Map<String, String>, channelId: String?, length: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/commercial"
        val body = buildJsonObject {
            put("broadcaster_id", channelId)
            put("length", length?.toIntOrNull())
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        json.decodeFromString<JsonElement>(response.body.string()).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun updateChatSettings(networkLibrary: String?, headers: Map<String, String>, channelId: String?, userId: String?, emote: Boolean? = null, followers: Boolean? = null, followersDuration: Int? = null, slow: Boolean? = null, slowDuration: Int? = null, subs: Boolean? = null, unique: Boolean? = null): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/chat/settings".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            userId?.let { appendQueryParameter("moderator_id", it) }
        }.build().toString()
        val body = buildChatSettingsUpdateBody(emote, followers, followersDuration, slow, slowDuration, subs, unique)
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                        setHttpMethod("PATCH")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                        setHttpMethod("PATCH")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    method("PATCH", body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun createStreamMarker(networkLibrary: String?, headers: Map<String, String>, channelId: String?, description: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams/markers"
        val body = buildJsonObject {
            put("user_id", channelId)
            description?.let { put("description", it) }
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun addModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/moderators".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun removeModerator(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/moderation/moderators".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun startRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/raids".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("from_broadcaster_id", it) }
            targetId?.let { appendQueryParameter("to_broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun cancelRaid(networkLibrary: String?, headers: Map<String, String>, channelId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/raids".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun addVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/vips".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun removeVip(networkLibrary: String?, headers: Map<String, String>, channelId: String?, targetId: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/vips".toUri().buildUpon().apply {
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            targetId?.let { appendQueryParameter("user_id", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        setHttpMethod("DELETE")
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    method("DELETE", null)
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }

    suspend fun sendWhisper(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String?, message: String?): String? = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/whispers".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("from_user_id", it) }
            targetId?.let { appendQueryParameter("to_user_id", it) }
        }.build().toString()
        val body = buildJsonObject {
            put("message", message)
        }.toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                        addHeader("Content-Type", "application/json")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    null
                } else {
                    response.body.decodeToString()
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        null
                    } else {
                        response.body.string()
                    }
                }
            }
        }
    }
}
