package com.github.andreyasadchy.xtra.repository.auth

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.C
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONArray
import org.json.JSONObject

private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"
private const val HELIX_USERS_URL = "https://api.twitch.tv/helix/users"
private const val HELIX_FOLLOWED_CHANNELS_URL = "https://api.twitch.tv/helix/channels/followed"
private const val GQL_URL = "https://gql.twitch.tv/gql"
private const val AUTH_EVENT_HISTORY_LIMIT = 100

enum class AuthLabCredentialSource {
    OFFICIAL,
    COMPATIBILITY,
    WEB,
}

enum class AuthLabOperation(val label: String) {
    HELIX_VALIDATE("/oauth2/validate"),
    HELIX_GET_USERS("Helix Get Users"),
    HELIX_FOLLOWED_CHANNELS("Helix followed channels"),
    GQL_CHANNEL_POINTS_CONTEXT("GQL ChannelPointsContext"),
    GQL_REWARD_LIST("GQL RewardList / watch streak"),
    GQL_CURRENT_PREDICTION("GQL current prediction"),
    GQL_PERSONAL_RECOMMENDATIONS("GQL personalized recommendations"),
    GQL_PLAYBACK_ACCESS_TOKEN("GQL playback access token"),
    HERMES_SUBSCRIPTIONS("Hermes authenticated subscriptions"),
}

data class AuthLabCredentialSummary(
    val source: AuthLabCredentialSource,
    val available: Boolean,
    val clientId: String? = null,
    val userId: String? = null,
    val login: String? = null,
    val accessFingerprint: String? = null,
    val refreshFingerprint: String? = null,
)

data class AuthLabValidationResult(
    val source: AuthLabCredentialSource,
    val httpStatus: Int? = null,
    val accepted: Boolean = false,
    val clientId: String? = null,
    val userId: String? = null,
    val login: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresIn: Int? = null,
    val accessFingerprint: String? = null,
    val refreshFingerprint: String? = null,
    val classification: String,
    val message: String? = null,
)

data class AuthLabProbeResult(
    val source: AuthLabCredentialSource,
    val operation: AuthLabOperation,
    val httpStatus: Int? = null,
    val success: Boolean,
    val gqlSuccess: Boolean? = null,
    val classification: String,
    val message: String? = null,
)

data class AuthDiagnosticEvent(
    val timestampMillis: Long,
    val credential: String,
    val operation: String,
    val httpStatus: Int?,
    val classification: String,
    val message: String?,
    val accessFingerprint: String? = null,
    val refreshFingerprint: String? = null,
)

/** Stores only diagnostic metadata. It never accepts or serializes a raw credential. */
class AuthDiagnosticLog(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    @Synchronized
    fun record(
        credential: AuthLabCredentialSource,
        operation: String,
        httpStatus: Int?,
        classification: String,
        message: String? = null,
        accessToken: String? = null,
        refreshToken: String? = null,
    ) {
        val events = read().toMutableList()
        events += AuthDiagnosticEvent(
            timestampMillis = nowMillis(),
            credential = credential.name,
            operation = operation,
            httpStatus = httpStatus,
            classification = classification,
                message = message.redactCredentials(accessToken, refreshToken)?.take(160),
            accessFingerprint = accessToken?.let(::tokenFingerprint),
            refreshFingerprint = refreshToken?.let(::tokenFingerprint),
        )
        val encoded = JSONArray()
        events.takeLast(AUTH_EVENT_HISTORY_LIMIT).forEach { event ->
            encoded.put(JSONObject().apply {
                put("timestamp", event.timestampMillis)
                put("credential", event.credential)
                put("operation", event.operation)
                event.httpStatus?.let { put("http", it) }
                put("classification", event.classification)
                event.message?.let { put("message", it) }
                event.accessFingerprint?.let { put("access", it) }
                event.refreshFingerprint?.let { put("refresh", it) }
            })
        }
        preferences.edit(commit = true) { putString(C.AUTH_EVENT_HISTORY, encoded.toString()) }
    }

    @Synchronized
    fun read(): List<AuthDiagnosticEvent> {
        val raw = preferences.getString(C.AUTH_EVENT_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AuthDiagnosticEvent(
                        timestampMillis = item.optLong("timestamp"),
                        credential = item.optString("credential"),
                        operation = item.optString("operation"),
                        httpStatus = item.optInt("http", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                        classification = item.optString("classification"),
                        message = item.optString("message").takeIf { it.isNotBlank() },
                        accessFingerprint = item.optString("access").takeIf { it.isNotBlank() },
                        refreshFingerprint = item.optString("refresh").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit(commit = true) { remove(C.AUTH_EVENT_HISTORY) }
    }
}

private fun String?.redactCredentials(
    accessToken: String?,
    refreshToken: String?,
): String? = this?.let { message ->
    sequenceOf(accessToken, refreshToken)
        .filter { !it.isNullOrBlank() }
        .map { it!! }
        .fold(message) { redacted, credential -> redacted.replace(credential, "<redacted>") }
}

fun tokenFingerprint(token: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(8)

private data class AuthLabCredential(
    val source: AuthLabCredentialSource,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val userId: String?,
    val login: String?,
    val authorizationScheme: String,
)

class AuthLabRepository(
    private val authRepository: AuthRepository,
    private val sessionStore: AuthSessionStore,
    private val networkLibrary: String?,
    private val json: Json,
    private val diagnosticLog: AuthDiagnosticLog,
) {
    fun summarize(
        source: AuthLabCredentialSource,
        browserToken: String? = null,
        browserClientId: String? = null,
    ): AuthLabCredentialSummary {
        val credential = resolve(source, browserToken, browserClientId) ?:
            return AuthLabCredentialSummary(source = source, available = false)
        return AuthLabCredentialSummary(
            source = source,
            available = true,
            clientId = credential.clientId,
            userId = credential.userId,
            login = credential.login,
            accessFingerprint = tokenFingerprint(credential.accessToken),
            refreshFingerprint = credential.refreshToken?.let(::tokenFingerprint),
        )
    }

    suspend fun validate(
        source: AuthLabCredentialSource,
        browserToken: String? = null,
        browserClientId: String? = null,
    ): AuthLabValidationResult {
        val credential = resolve(source, browserToken, browserClientId)
            ?: return unavailableValidation(source, "No credential is available")
        val result = runCatching {
            val response = authRepository.diagnosticGet(
                networkLibrary = networkLibrary,
                url = VALIDATE_URL,
                headers = authHeaders(credential),
            )
            val validation = response.body
                .takeIf { response.statusCode in 200..299 }
                ?.let { json.decodeFromString<ValidationResponse>(it) }
            val accepted = response.statusCode in 200..299 && validation?.clientId?.isNotBlank() == true
            AuthLabValidationResult(
                source = source,
                httpStatus = response.statusCode,
                accepted = accepted,
                clientId = validation?.clientId,
                userId = validation?.userId,
                login = validation?.login,
                scopes = validation?.scopes.orEmpty(),
                expiresIn = validation?.expiresIn,
                accessFingerprint = tokenFingerprint(credential.accessToken),
                refreshFingerprint = credential.refreshToken?.let(::tokenFingerprint),
                classification = classify(response.statusCode, accepted),
                message = if (accepted) null else response.body.errorMessage()
                    .redactCredentials(credential.accessToken, credential.refreshToken),
            )
        }.getOrElse { error ->
            AuthLabValidationResult(
                source = source,
                accepted = false,
                accessFingerprint = tokenFingerprint(credential.accessToken),
                refreshFingerprint = credential.refreshToken?.let(::tokenFingerprint),
                classification = "TRANSIENT_FAILURE",
                message = error.safeMessage().redactCredentials(credential.accessToken, credential.refreshToken),
            )
        }
        diagnosticLog.record(
            credential = source,
            operation = AuthLabOperation.HELIX_VALIDATE.label,
            httpStatus = result.httpStatus,
            classification = result.classification,
            message = result.message,
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
        )
        return result
    }

    suspend fun runReadOnlyMatrix(
        source: AuthLabCredentialSource,
        channelLogin: String? = null,
        channelId: String? = null,
        browserToken: String? = null,
        browserClientId: String? = null,
    ): List<AuthLabProbeResult> {
        val credential = resolve(source, browserToken, browserClientId)
            ?: return AuthLabOperation.entries
                .filter { it != AuthLabOperation.HELIX_VALIDATE }
                .map { operation ->
                    AuthLabProbeResult(
                        source = source,
                        operation = operation,
                        success = false,
                        classification = "NO_CREDENTIAL",
                        message = "No credential is available",
                    )
                }
        val resolvedLogin = channelLogin?.trim().takeUnless { it.isNullOrBlank() } ?: credential.login.orEmpty()
        val resolvedChannelId = channelId?.trim().takeUnless { it.isNullOrBlank() } ?: credential.userId.orEmpty()
        val operations = listOf(
            AuthLabOperation.HELIX_GET_USERS,
            AuthLabOperation.HELIX_FOLLOWED_CHANNELS,
            AuthLabOperation.GQL_CHANNEL_POINTS_CONTEXT,
            AuthLabOperation.GQL_REWARD_LIST,
            AuthLabOperation.GQL_CURRENT_PREDICTION,
            AuthLabOperation.GQL_PERSONAL_RECOMMENDATIONS,
            AuthLabOperation.GQL_PLAYBACK_ACCESS_TOKEN,
            AuthLabOperation.HERMES_SUBSCRIPTIONS,
        )
        return operations.map { operation ->
            val result = if (operation == AuthLabOperation.HERMES_SUBSCRIPTIONS) {
                AuthLabProbeResult(
                    source = source,
                    operation = operation,
                    success = false,
                    classification = "MANUAL_ONLY",
                    message = "Hermes is a long-lived WebSocket; run it from a live chat session",
                )
            } else {
                runProbe(credential, operation, resolvedLogin, resolvedChannelId)
            }
            diagnosticLog.record(
                credential = source,
                operation = operation.label,
                httpStatus = result.httpStatus,
                classification = result.classification,
                message = result.message,
                accessToken = credential.accessToken,
                refreshToken = credential.refreshToken,
            )
            result
        }
    }

    fun readHistory(): List<AuthDiagnosticEvent> = diagnosticLog.read()

    fun clearHistory() = diagnosticLog.clear()

    private suspend fun runProbe(
        credential: AuthLabCredential,
        operation: AuthLabOperation,
        channelLogin: String,
        channelId: String,
    ): AuthLabProbeResult {
        val response = runCatching {
            when (operation) {
                AuthLabOperation.HELIX_GET_USERS -> authRepository.diagnosticGet(
                    networkLibrary,
                    HELIX_USERS_URL.toUri().buildUpon().appendQueryParameter("id", credential.userId).build().toString(),
                    helixHeaders(credential),
                )
                AuthLabOperation.HELIX_FOLLOWED_CHANNELS -> authRepository.diagnosticGet(
                    networkLibrary,
                    HELIX_FOLLOWED_CHANNELS_URL.toUri().buildUpon()
                        .appendQueryParameter("user_id", credential.userId)
                        .appendQueryParameter("first", "1")
                        .build().toString(),
                    helixHeaders(credential),
                )
                AuthLabOperation.GQL_CHANNEL_POINTS_CONTEXT -> authRepository.diagnosticPost(
                    networkLibrary,
                    GQL_URL,
                    gqlHeaders(credential),
                    channelPointsContextBody(channelLogin),
                )
                AuthLabOperation.GQL_REWARD_LIST -> authRepository.diagnosticPost(
                    networkLibrary,
                    GQL_URL,
                    gqlHeaders(credential),
                    rewardListBody(channelId),
                )
                AuthLabOperation.GQL_CURRENT_PREDICTION -> authRepository.diagnosticPost(
                    networkLibrary,
                    GQL_URL,
                    gqlHeaders(credential),
                    predictionBody(channelLogin),
                )
                AuthLabOperation.GQL_PERSONAL_RECOMMENDATIONS -> authRepository.diagnosticPost(
                    networkLibrary,
                    GQL_URL,
                    gqlHeaders(credential),
                    personalSectionsBody(),
                )
                AuthLabOperation.GQL_PLAYBACK_ACCESS_TOKEN -> authRepository.diagnosticPost(
                    networkLibrary,
                    GQL_URL,
                    gqlHeaders(credential),
                    playbackAccessTokenBody(channelLogin),
                )
                AuthLabOperation.HELIX_VALIDATE,
                AuthLabOperation.HERMES_SUBSCRIPTIONS,
                -> error("Unsupported lab operation")
            }
        }.getOrElse { error ->
            return AuthLabProbeResult(
                source = credential.source,
                operation = operation,
                success = false,
                classification = "TRANSIENT_FAILURE",
                message = error.safeMessage().redactCredentials(credential.accessToken, credential.refreshToken),
            )
        }
        val gqlSuccess = if (operation.name.startsWith("GQL_")) response.body.gqlSuccess() else null
        val success = response.statusCode in 200..299 && gqlSuccess != false
        return AuthLabProbeResult(
            source = credential.source,
            operation = operation,
            httpStatus = response.statusCode,
            success = success,
            gqlSuccess = gqlSuccess,
            classification = classify(response.statusCode, success),
            message = (if (success) response.body.gqlMessage() else response.body.errorMessage())
                .redactCredentials(credential.accessToken, credential.refreshToken),
        )
    }

    private fun resolve(
        source: AuthLabCredentialSource,
        browserToken: String?,
        browserClientId: String?,
    ): AuthLabCredential? = when (source) {
        AuthLabCredentialSource.OFFICIAL -> sessionStore.read()?.let {
            AuthLabCredential(
                source = source,
                clientId = it.clientId,
                accessToken = it.accessToken,
                refreshToken = it.refreshToken,
                userId = it.userId,
                login = it.login,
                authorizationScheme = "Bearer",
            )
        }
        AuthLabCredentialSource.COMPATIBILITY -> sessionStore.readCompatibility()?.let {
            AuthLabCredential(
                source = source,
                clientId = it.clientId,
                accessToken = it.accessToken,
                refreshToken = it.refreshToken,
                userId = it.userId,
                login = sessionStore.read()?.login,
                authorizationScheme = "OAuth",
            )
        }
        AuthLabCredentialSource.WEB -> browserToken?.trim()?.takeIf { it.isNotBlank() }?.let {
            AuthLabCredential(
                source = source,
                clientId = browserClientId?.trim()?.takeIf { id -> id.isNotBlank() }
                    ?: C.DEFAULT_GQL_CLIENT_ID_WEB,
                accessToken = it,
                refreshToken = null,
                userId = null,
                login = null,
                authorizationScheme = "OAuth",
            )
        }
    }

    private fun authHeaders(credential: AuthLabCredential): Map<String, String> = mapOf(
        C.HEADER_CLIENT_ID to credential.clientId,
        C.HEADER_TOKEN to "${credential.authorizationScheme} ${credential.accessToken}",
    )

    private fun helixHeaders(credential: AuthLabCredential): Map<String, String> = authHeaders(credential)

    private fun gqlHeaders(credential: AuthLabCredential): Map<String, String> = authHeaders(credential) + mapOf(
        "Origin" to "https://www.twitch.tv",
        "Referer" to "https://www.twitch.tv/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
    )

    private fun unavailableValidation(source: AuthLabCredentialSource, message: String) = AuthLabValidationResult(
        source = source,
        classification = "NO_CREDENTIAL",
        message = message,
    )

    private fun classify(status: Int, success: Boolean): String = when {
        success -> "SUCCESS"
        status == 401 -> "UNAUTHORIZED"
        status == 408 || status == 429 || status >= 500 -> "TRANSIENT_FAILURE"
        else -> "FAILURE"
    }

    private fun String.errorMessage(): String? = runCatching {
        json.parseToJsonElement(this).jsonObject["message"]?.toString()
            ?: json.parseToJsonElement(this).jsonObject["error"]?.toString()
    }.getOrNull()?.trim('"')?.takeIf { it.isNotBlank() }

    private fun String.gqlSuccess(): Boolean? = runCatching {
        json.parseToJsonElement(this).jsonObject["errors"]?.jsonArray?.isEmpty() ?: true
    }.getOrNull()

    private fun String.gqlMessage(): String? = runCatching {
        val root = json.parseToJsonElement(this).jsonObject
        root["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.toString()?.trim('"')
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Throwable.safeMessage(): String = message?.take(160) ?: this::class.simpleName.orEmpty()

    private fun channelPointsContextBody(channelLogin: String) = buildJsonObject {
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("sha256Hash", "7fe050e3761eb2cf258d70ee1a21cbd76fa8cf3d7e7b12fc437e7029d446b5e3")
                put("version", 1)
            }
        }
        put("operationName", "ChannelPointsContext")
        putJsonObject("variables") {
            put("channelLogin", channelLogin)
            putJsonArray("includeGoalTypes") {
                add(JsonPrimitive("CREATOR"))
                add(JsonPrimitive("BOOST"))
            }
        }
    }.toString()

    private fun rewardListBody(channelId: String) = buildJsonObject {
        put("operationName", "RewardList")
        put("query", REWARD_LIST_QUERY)
        putJsonObject("variables") {
            put("channelID", channelId)
            put("shouldIncludeAllSuspendedStreaks", false)
        }
    }.toString()

    private fun predictionBody(channelLogin: String) = buildJsonObject {
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("sha256Hash", "beb846598256b75bd7c1fe54a80431335996153e358ca9c7837ce7bb83d7d383")
                put("version", 1)
            }
        }
        put("operationName", "ChannelPointsPredictionContext")
        putJsonObject("variables") {
            put("count", 1)
            put("channelLogin", channelLogin)
        }
    }.toString()

    private fun personalSectionsBody() = buildJsonObject {
        put("operationName", "PersonalSections")
        put("query", PERSONAL_SECTIONS_QUERY)
        putJsonObject("variables") {
            putJsonObject("input") {
                putJsonArray("sectionInputs") { add(JsonPrimitive("RECOMMENDED_SECTION")) }
                putJsonObject("recommendationContext") {
                    put("platform", "web")
                    put("clientApp", "twilight")
                    put("location", "following")
                }
            }
        }
    }.toString()

    private fun playbackAccessTokenBody(channelLogin: String) = buildJsonObject {
        putJsonObject("extensions") {
            putJsonObject("persistedQuery") {
                put("sha256Hash", "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9")
                put("version", 1)
            }
        }
        put("operationName", "PlaybackAccessToken")
        putJsonObject("variables") {
            put("isLive", true)
            put("login", channelLogin)
            put("isVod", false)
            put("vodID", "")
            put("platform", "web")
            put("playerType", "site")
        }
    }.toString()

    private companion object {
        val REWARD_LIST_QUERY = """
            query RewardList(${'$'}channelID: ID!, ${'$'}shouldIncludeAllSuspendedStreaks: Boolean = false) {
              channel(id: ${'$'}channelID) {
                self {
                  watchStreakMilestone(shouldIncludeAllSuspendedStreaks: ${'$'}shouldIncludeAllSuspendedStreaks) {
                    watchStreakMilestone { id value shareStatus }
                    watchStreakThreshold
                    watchStreakCopoBonus
                  }
                }
              }
            }
        """.trimIndent()

        val PERSONAL_SECTIONS_QUERY = """
            query PersonalSections(${'$'}input: PersonalSectionInput!) {
              personalSections(input: ${'$'}input) {
                type
                items {
                  ... on PersonalSectionChannel {
                    user { id login displayName profileImageURL(width: 300) }
                    content {
                      __typename
                      ... on Stream { id title viewersCount createdAt previewImageURL game { id slug displayName } }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
