package com.github.andreyasadchy.xtra.ui.settings

import com.github.andreyasadchy.xtra.repository.GraphQLApiException
import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import kotlinx.serialization.SerializationException

enum class LiveNotificationSetupStage {
    NOTIFICATION_PERMISSION_CHANNEL_VALIDATION,
    NOTIFICATION_USER_FOLLOW_SYNC,
    INITIAL_LIVE_STREAM_BASELINE_FETCH,
    SCHEDULER_REALTIME_MONITOR_STARTUP,
}

enum class LiveNotificationFailureReason {
    NOTIFICATION_PERMISSION_OR_CHANNEL,
    MISSING_AUTHENTICATION,
    HTTP_401_UNAUTHORIZED,
    HTTP_403_FORBIDDEN,
    HTTP_429_RATE_LIMITED,
    TWITCH_HTTP_5XX,
    DNS_CONNECTIVITY_OR_TIMEOUT,
    MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE,
    LOCAL_DATABASE_FAILURE,
    UNKNOWN_FAILURE,
}

data class LiveNotificationFailure(
    val stage: LiveNotificationSetupStage,
    val reason: LiveNotificationFailureReason,
    val httpStatus: Int? = null,
    val technicalMessage: String? = null,
    val exceptionClass: String? = null,
    val rateLimitResetEpochSeconds: Long? = null,
    val rateLimitLimit: Long? = null,
    val rateLimitRemaining: Long? = null,
) {
    val isAuthenticationFailure: Boolean
        get() = reason == LiveNotificationFailureReason.MISSING_AUTHENTICATION ||
                reason == LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED ||
                reason == LiveNotificationFailureReason.HTTP_403_FORBIDDEN

    val canRetry: Boolean
        get() = when (reason) {
            LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL,
            LiveNotificationFailureReason.MISSING_AUTHENTICATION,
            LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED,
            LiveNotificationFailureReason.HTTP_403_FORBIDDEN,
            -> false
            else -> true
        }
}

object LiveNotificationFailureClassifier {

    fun classify(stage: LiveNotificationSetupStage, error: Throwable): LiveNotificationFailure {
        val causes = error.causes().toList()
        val twitchError = causes.filterIsInstance<TwitchApiException>().firstOrNull()
        val graphQLError = causes.filterIsInstance<GraphQLApiException>().firstOrNull()
        val detailError = twitchError ?: graphQLError ?: causes.firstOrNull() ?: error
        val status = twitchError?.statusCode
        val reason = when {
            causes.any { it is MissingAuthenticationException } -> LiveNotificationFailureReason.MISSING_AUTHENTICATION
            status == 401 -> LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED
            status == 403 -> LiveNotificationFailureReason.HTTP_403_FORBIDDEN
            status == 429 -> LiveNotificationFailureReason.HTTP_429_RATE_LIMITED
            status in 500..599 -> LiveNotificationFailureReason.TWITCH_HTTP_5XX
            causes.any(::looksLikeMissingAuthentication) -> LiveNotificationFailureReason.MISSING_AUTHENTICATION
            causes.any(::isLocalDatabaseFailure) -> LiveNotificationFailureReason.LOCAL_DATABASE_FAILURE
            causes.any(::isConnectivityFailure) -> LiveNotificationFailureReason.DNS_CONNECTIVITY_OR_TIMEOUT
            causes.any(::isMalformedTwitchResponse) -> LiveNotificationFailureReason.MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE
            else -> LiveNotificationFailureReason.UNKNOWN_FAILURE
        }
        return LiveNotificationFailure(
            stage = stage,
            reason = reason,
            httpStatus = status,
            technicalMessage = sanitizeLiveNotificationTechnicalMessage(detailError.message),
            exceptionClass = detailError::class.simpleName ?: detailError::class.qualifiedName,
            rateLimitResetEpochSeconds = twitchError?.rateLimitResetEpochSeconds,
            rateLimitLimit = twitchError?.rateLimitLimit,
            rateLimitRemaining = twitchError?.rateLimitRemaining,
        )
    }

    private fun Throwable.causes(): Sequence<Throwable> = sequence {
        val seen = hashSetOf<Throwable>()
        var current: Throwable? = this@causes
        while (current != null && seen.add(current)) {
            yield(current)
            current = current.cause
        }
    }

    private fun looksLikeMissingAuthentication(error: Throwable): Boolean {
        if (!isApiRelatedFailure(error)) {
            return false
        }
        val message = error.message ?: return false
        return Regex(
            "(?i)\\b(?:unauthori[sz]ed|unauthenticated|authentication required|login required|invalid (?:oauth|access|refresh) token|(?:oauth|access|refresh) token(?: is)? (?:invalid|expired)|invalid token|not authorized|permission denied|HTTP\\s+(?:401|403)|(?:401|403))\\b"
        ).containsMatchIn(message)
    }

    private fun isApiRelatedFailure(error: Throwable): Boolean {
        if (error is GraphQLApiException ||
            error is MissingAuthenticationException ||
            error is TwitchApiException
        ) {
            return true
        }
        val simpleName = error::class.simpleName.orEmpty()
        if (simpleName.contains("ApiException", ignoreCase = true) ||
            simpleName.contains("HttpException", ignoreCase = true) ||
            simpleName.contains("ResponseException", ignoreCase = true)
        ) {
            return true
        }
        return error is IOException &&
                Regex("(?i)\\b(?:Twitch|GraphQL|HTTP)\\b").containsMatchIn(error.message.orEmpty())
    }

    private fun isLocalDatabaseFailure(error: Throwable): Boolean {
        val simpleName = error::class.simpleName.orEmpty()
        return error is SQLException ||
                simpleName.equals("SQLException", ignoreCase = true) ||
                simpleName.contains("SQLite", ignoreCase = true) ||
                simpleName.contains("Room", ignoreCase = true)
    }

    private fun isConnectivityFailure(error: Throwable): Boolean =
        error is UnknownHostException ||
                error is SocketTimeoutException ||
                error is ConnectException ||
                error is NoRouteToHostException ||
                error is SocketException ||
                error is InterruptedIOException ||
                (error is IOException && error !is GraphQLApiException && error !is TwitchApiException)

    private fun isMalformedTwitchResponse(error: Throwable): Boolean =
        error is GraphQLApiException ||
                error is SerializationException ||
                error::class.simpleName.orEmpty() in setOf(
                    "JsonDataException",
                    "JsonDecodingException",
                    "ApolloParseException",
                ) ||
                error::class.simpleName.orEmpty() in setOf(
                    "JsonParseException",
                    "MoshiJsonDataException",
                )
}
