package com.github.andreyasadchy.xtra.ui.settings

import com.github.andreyasadchy.xtra.repository.GraphQLApiException
import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationSchedulerResult
import com.github.andreyasadchy.xtra.ui.main.NotificationBlockReason
import com.github.andreyasadchy.xtra.util.sanitizeLiveNotificationTechnicalMessage
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationFailureTest {

    private val stage = LiveNotificationSetupStage.INITIAL_LIVE_STREAM_BASELINE_FETCH

    @Test
    fun classifiesUnauthorizedHelixResponse() {
        val failure = classify(
            TwitchApiException(
                statusCode = 401,
                rateLimitResetEpochSeconds = null,
                message = "Twitch Helix request failed with HTTP 401",
            )
        )

        assertEquals(LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED, failure.reason)
        assertEquals(401, failure.httpStatus)
        assertFalse(failure.canRetry)
    }

    @Test
    fun classifiesForbiddenHelixResponse() {
        val failure = classify(
            TwitchApiException(
                statusCode = 403,
                rateLimitResetEpochSeconds = null,
                message = "Twitch Helix request failed with HTTP 403",
            )
        )

        assertEquals(LiveNotificationFailureReason.HTTP_403_FORBIDDEN, failure.reason)
        assertEquals(403, failure.httpStatus)
    }

    @Test
    fun classifiesRateLimitAndRetainsHelixRateLimitData() {
        val failure = LiveNotificationFailureClassifier.classify(
            stage,
            TwitchApiException(
                statusCode = 429,
                rateLimitResetEpochSeconds = 1234L,
                rateLimitLimit = 800L,
                rateLimitRemaining = 0L,
                message = "rate limited",
            ),
        )

        assertEquals(LiveNotificationFailureReason.HTTP_429_RATE_LIMITED, failure.reason)
        assertEquals(429, failure.httpStatus)
        assertEquals(1234L, failure.rateLimitResetEpochSeconds)
        assertEquals(800L, failure.rateLimitLimit)
        assertEquals(0L, failure.rateLimitRemaining)
        assertTrue(failure.canRetry)
    }

    @Test
    fun classifiesTwitchServerErrors() {
        val failure = classify(
            TwitchApiException(
                statusCode = 503,
                rateLimitResetEpochSeconds = null,
                message = "Twitch Helix request failed with HTTP 503",
            )
        )

        assertEquals(LiveNotificationFailureReason.TWITCH_HTTP_5XX, failure.reason)
        assertEquals(503, failure.httpStatus)
    }

    @Test
    fun classifiesTimeoutAndDnsFailuresAsConnectivity() {
        assertEquals(
            LiveNotificationFailureReason.DNS_CONNECTIVITY_OR_TIMEOUT,
            classify(SocketTimeoutException("timed out")).reason,
        )
        assertEquals(
            LiveNotificationFailureReason.DNS_CONNECTIVITY_OR_TIMEOUT,
            classify(UnknownHostException("gql.twitch.tv")).reason,
        )
    }

    @Test
    fun classifiesGraphQlErrorsAndUnexpectedDataAsMalformedResponses() {
        assertEquals(
            LiveNotificationFailureReason.MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE,
            classify(GraphQLApiException("GraphQL error: invalid query")).reason,
        )
        assertEquals(
            LiveNotificationFailureReason.MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE,
            classify(GraphQLApiException("GraphQL response did not include data")).reason,
        )
    }

    @Test
    fun classifiesGraphQlAuthenticationErrorsSeparately() {
        val failure = classify(GraphQLApiException("Unauthenticated user"))

        assertEquals(LiveNotificationFailureReason.MISSING_AUTHENTICATION, failure.reason)
    }

    @Test
    fun classifiesMissingCredentialsAtTheFollowSyncStage() {
        val failure = LiveNotificationFailureClassifier.classify(
            LiveNotificationSetupStage.NOTIFICATION_USER_FOLLOW_SYNC,
            MissingAuthenticationException("notification-user/follow sync"),
        )

        assertEquals(LiveNotificationSetupStage.NOTIFICATION_USER_FOLLOW_SYNC, failure.stage)
        assertEquals(LiveNotificationFailureReason.MISSING_AUTHENTICATION, failure.reason)
    }

    @Test
    fun classifiesLocalDatabaseAndUnknownFailures() {
        assertEquals(
            LiveNotificationFailureReason.LOCAL_DATABASE_FAILURE,
            classify(SQLException("database is locked")).reason,
        )
        assertEquals(
            LiveNotificationFailureReason.UNKNOWN_FAILURE,
            classify(IllegalStateException("unexpected scheduler state")).reason,
        )
        assertEquals(
            LiveNotificationFailureReason.UNKNOWN_FAILURE,
            classify(NullPointerException("x")).reason,
        )
        assertEquals(
            LiveNotificationFailureReason.UNKNOWN_FAILURE,
            classify(IllegalStateException("Permission denied")).reason,
        )
    }

    @Test
    fun sanitizesCredentialRepresentationsBeforeTheyReachUiOrDiagnostics() {
        val samples = listOf(
            "\"access_token\": \"access-secret\"" to listOf("access-secret"),
            "\"access_token\":\"access-secret-no-space\"" to listOf("access-secret-no-space"),
            "\"refresh_token\": \"refresh-secret\"" to listOf("refresh-secret"),
            "\"oauth_token\": \"oauth-secret\"" to listOf("oauth-secret"),
            "\"client_secret\": \"client-secret\"" to listOf("client-secret"),
            "Authorization: Bearer authorization-secret" to listOf("authorization-secret"),
            "Authorization=Bearer authorization-equals-secret" to listOf("authorization-equals-secret"),
            "Cookie: foo=cookie-secret; bar=cookie-secret-2" to listOf("cookie-secret", "cookie-secret-2"),
            "Cookie: foo=cookie-comma-secret, bar=cookie-comma-secret-2" to listOf("cookie-comma-secret", "cookie-comma-secret-2"),
            "Set-Cookie: session=set-cookie-secret; Path=/" to listOf("set-cookie-secret"),
            "https://example/?access_token=query-secret" to listOf("query-secret"),
            "headers={Authorization=Bearer header-secret, Cookie=session-secret}" to listOf("header-secret", "session-secret"),
        )

        samples.forEach { (message, secrets) ->
            val sanitized = sanitizeLiveNotificationTechnicalMessage(message).orEmpty()
            secrets.forEach { secret ->
                assertFalse("Secret leaked from: $message", sanitized.contains(secret))
            }
        }

        assertTrue(sanitizeLiveNotificationTechnicalMessage("Authorization: Bearer secret").orEmpty().contains("<redacted>"))
    }

    @Test
    fun graphqlExceptionRedactsHeadersInItsMessage() {
        val error = GraphQLApiException(
            "GraphQL request headers={Authorization=Bearer oauth-secret, Cookie=session-secret}",
            operation = "UserFollowedUsers",
        )

        assertFalse(error.message.orEmpty().contains("oauth-secret"))
        assertFalse(error.message.orEmpty().contains("session-secret"))
        assertTrue(error.message.orEmpty().contains("headers=<redacted>"))
    }

    @Test
    fun schedulerBlockedResultRetainsTheSpecificNotificationBlockReason() {
        val result = LiveNotificationSchedulerResult.Blocked(NotificationBlockReason.LIVE_CHANNEL_DISABLED)

        assertEquals(NotificationBlockReason.LIVE_CHANNEL_DISABLED, result.reason)
        assertSame(LiveNotificationSchedulerResult.Started, LiveNotificationSchedulerResult.Started)
    }

    private fun classify(error: Throwable): LiveNotificationFailure =
        LiveNotificationFailureClassifier.classify(stage, error)
}
