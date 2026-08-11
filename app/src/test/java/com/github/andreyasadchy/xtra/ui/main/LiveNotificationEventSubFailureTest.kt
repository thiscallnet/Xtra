package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.repository.EventSubSubscriptionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationEventSubFailureTest {

    @Test
    fun authenticationFailuresSuspendTheFastLane() {
        assertEquals(
            LiveEventSubSuspensionReason.AUTHENTICATION,
            classifyEventSubFailure(401, "Invalid OAuth token"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.AUTHENTICATION,
            classifyEventSubFailure(403, "Forbidden"),
        )
    }

    @Test
    fun rateLimitAndRemovedVersionFailuresAreTerminalForTheSocket() {
        assertEquals(
            LiveEventSubSuspensionReason.RATE_LIMIT,
            classifyEventSubFailure(429, "Too many requests"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.VERSION_REMOVED,
            classifyEventSubFailure(410, "Gone"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.VERSION_REMOVED,
            classifyEventSubFailure(400, "Subscription version removed"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.SESSION_INVALID,
            classifyEventSubFailure(400, "The EventSub session is invalid or expired"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.CAPACITY_REACHED,
            classifyEventSubFailure(400, "Maximum total cost reached for subscriptions"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.ALREADY_EXISTS,
            classifyEventSubFailure(409, "subscription already exists"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.DUPLICATE_CONDITION_LIMIT,
            classifyEventSubFailure(429, "Subscription limit reached for this condition"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.DUPLICATE_CONDITION_LIMIT,
            classifyEventSubFailure(
                429,
                "The maximum number of subscriptions with the same type and condition has been reached",
            ),
        )
        assertEquals(
            LiveEventSubSuspensionReason.CAPACITY_REACHED,
            classifyEventSubFailure(429, "Maximum total cost reached"),
        )
    }

    @Test
    fun channelAndGlobalClientFailuresHaveDifferentActions() {
        assertEquals(
            LiveEventSubSuspensionReason.CHANNEL_REJECTED,
            classifyEventSubFailure(400, "Broadcaster not found"),
        )
        assertEquals(
            LiveEventSubSuspensionReason.CHANNEL_REJECTED,
            classifyEventSubFailure(
                400,
                "The user specified in the condition object does not exist",
            ),
        )
        assertEquals(
            LiveEventSubFailureAction.REJECT_CHANNEL,
            eventSubFailureAction(LiveEventSubSuspensionReason.CHANNEL_REJECTED, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubSuspensionReason.CONFIGURATION_INVALID,
            classifyEventSubFailure(400, "Invalid subscription type or version"),
        )
        assertEquals(
            LiveEventSubFailureAction.SUSPEND,
            eventSubFailureAction(LiveEventSubSuspensionReason.CONFIGURATION_INVALID, hasActiveSubscriptions = true),
        )
    }

    @Test
    fun partialFailuresKeepWorkingSubscriptionsAndRetryRemaining() {
        assertEquals(
            LiveEventSubFailureAction.RETRY_REMAINING,
            eventSubFailureAction(LiveEventSubSuspensionReason.RATE_LIMIT, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubFailureAction.RECONNECT,
            eventSubFailureAction(LiveEventSubSuspensionReason.SESSION_INVALID, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubFailureAction.STOP_FILLING,
            eventSubFailureAction(LiveEventSubSuspensionReason.CAPACITY_REACHED, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubFailureAction.DEFER_CHANNEL,
            eventSubFailureAction(LiveEventSubSuspensionReason.ALREADY_EXISTS, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubFailureAction.SUSPEND,
            eventSubFailureAction(LiveEventSubSuspensionReason.RATE_LIMIT, hasActiveSubscriptions = false),
        )
        assertEquals(
            LiveEventSubFailureAction.DEFER_CHANNEL,
            eventSubFailureAction(LiveEventSubSuspensionReason.DUPLICATE_CONDITION_LIMIT, hasActiveSubscriptions = true),
        )
        assertEquals(
            LiveEventSubFailureAction.DEFER_CHANNEL,
            eventSubFailureAction(LiveEventSubSuspensionReason.TRANSIENT_SERVER_FAILURE, hasActiveSubscriptions = true),
        )
    }

    @Test
    fun existingSubscriptionIsAdoptedOnlyWhenItBelongsToCurrentSession() {
        val subscription = EventSubSubscriptionInfo(
            statusCode = 200,
            id = "subscription-1",
            subscriptionType = "stream.online",
            subscriptionStatus = "enabled",
            broadcasterUserId = "channel-1",
            transportMethod = "websocket",
            transportSessionId = "session-1",
        )

        assertTrue(
            isMatchingEventSubSubscription(
                subscription = subscription,
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ),
        )
        assertFalse(
            isMatchingEventSubSubscription(
                subscription = subscription,
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-2",
            ),
        )
        assertTrue(
            classifyExistingEventSubSubscription(
                subscription = subscription,
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ) is ExistingEventSubSubscriptionResolution.CurrentSession,
        )
        assertSame(
            ExistingEventSubSubscriptionResolution.OtherSession,
            classifyExistingEventSubSubscription(
                subscription = subscription.copy(transportSessionId = "session-2"),
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ),
        )
    }

    @Test
    fun failedOrIncompleteExistingSubscriptionLookupIsRetryable() {
        assertSame(
            ExistingEventSubSubscriptionResolution.Retry,
            classifyExistingEventSubSubscription(
                subscription = null,
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ),
        )
        assertSame(
            ExistingEventSubSubscriptionResolution.Retry,
            classifyExistingEventSubSubscription(
                subscription = EventSubSubscriptionInfo(statusCode = 503),
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ),
        )
        assertSame(
            ExistingEventSubSubscriptionResolution.Retry,
            classifyExistingEventSubSubscription(
                subscription = EventSubSubscriptionInfo(
                    statusCode = 200,
                    id = "subscription-1",
                    subscriptionType = "stream.online",
                    subscriptionStatus = "enabled",
                    broadcasterUserId = "channel-1",
                    transportMethod = "websocket",
                ),
                subscriptionId = "subscription-1",
                channelId = "channel-1",
                sessionId = "session-1",
            ),
        )
    }

    @Test
    fun crossSessionRetryIsOnlySuppressedUntilItsDeadline() {
        assertTrue(isEventSubChannelRetryDue(nowElapsedMs = 1_000L, retryAtElapsedMs = null))
        assertFalse(isEventSubChannelRetryDue(nowElapsedMs = 1_000L, retryAtElapsedMs = 2_000L))
        assertTrue(isEventSubChannelRetryDue(nowElapsedMs = 2_000L, retryAtElapsedMs = 2_000L))
    }
}
