package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsRepositoryTest {

    @Test
    fun rejectsEventsForDisabledChannels() {
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = false, shownStartedAt = null, eventStartedAt = 100L))
    }

    @Test
    fun rejectsDuplicateOrOlderStreamStarts() {
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 100L, eventStartedAt = 100L))
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 200L, eventStartedAt = 100L))
    }

    @Test
    fun acceptsNewerStreamStarts() {
        assertTrue(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 100L, eventStartedAt = 200L))
        assertTrue(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = null, eventStartedAt = 100L))
    }

    @Test
    fun baselineAuthenticationIsOnlyRequiredForCachedChannels() {
        assertTrue(
            isLiveNotificationBaselineAuthenticationMissing(
                cachedChannelCount = 1,
                gqlHeaders = emptyMap(),
                helixHeaders = emptyMap(),
            )
        )
        assertFalse(
            isLiveNotificationBaselineAuthenticationMissing(
                cachedChannelCount = 0,
                gqlHeaders = emptyMap(),
                helixHeaders = emptyMap(),
            )
        )
        assertFalse(
            isLiveNotificationBaselineAuthenticationMissing(
                cachedChannelCount = 1,
                gqlHeaders = mapOf("Authorization" to "OAuth test-token"),
                helixHeaders = emptyMap(),
            )
        )
    }

    @Test
    fun nonFatalGraphQlErrorsMayAccompanyUsableData() {
        assertFalse(isFatalLiveNotificationGraphQlError("A secondary field failed", requiredDataAvailable = true))
        assertTrue(isFatalLiveNotificationGraphQlError("Unauthenticated user", requiredDataAvailable = true))
        assertTrue(isFatalLiveNotificationGraphQlError(null, requiredDataAvailable = false))
    }

    @Test
    fun `compatibility preferences filter followed channels`() {
        assertEquals(
            setOf("enabled"),
            selectNotificationChannelIds(
                followedIds = listOf("enabled", "disabled"),
                preferenceEnabledIds = setOf("enabled", "unfollowed"),
            ),
        )
    }

    @Test
    fun `unavailable compatibility preferences fall back to Helix follows`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            compatibilityAuthAvailable = false,
        ) {
            error("the loader must not run without compatibility auth")
        }

        assertEquals(
            setOf("first", "second"),
            selectNotificationChannelIds(
                followedIds = listOf("first", "second"),
                preferenceResult = preferences,
                previousNotificationIds = emptyList(),
            ),
        )
    }

    @Test
    fun `transient compatibility preference failure retains the previous channel set`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            compatibilityAuthAvailable = true,
        ) {
            throw java.io.IOException("temporary GraphQL outage")
        }

        assertEquals(
            setOf("enabled"),
            selectNotificationChannelIds(
                followedIds = listOf("enabled", "newly-followed"),
                preferenceResult = preferences,
                previousNotificationIds = listOf("enabled"),
            ),
        )
        assertTrue(preferences is NotificationPreferenceLoadResult.TransientFailure)
    }

    @Test
    fun `transient compatibility preference failure uses Helix follows for an empty cache`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            compatibilityAuthAvailable = true,
        ) {
            throw java.io.IOException("temporary GraphQL outage")
        }

        assertEquals(
            setOf("first", "second"),
            selectNotificationChannelIds(
                followedIds = listOf("first", "second"),
                preferenceResult = preferences,
                previousNotificationIds = emptyList(),
            ),
        )
    }

    @Test
    fun `authentication failure uses the Helix fallback while server failure is transient`() = runBlocking {
        val authFailure = loadOptionalNotificationPreferenceIds(true) {
            throw GraphQLApiException("Authentication required")
        }
        val serverFailure = loadOptionalNotificationPreferenceIds(true) {
            throw GraphQLApiException("temporary server failure")
        }

        assertTrue(authFailure is NotificationPreferenceLoadResult.CompatibilityUnavailable)
        assertTrue(serverFailure is NotificationPreferenceLoadResult.TransientFailure)
    }

    @Test
    fun `official-only fallback monitors every followed channel`() {
        assertEquals(
            setOf("first", "second"),
            selectNotificationChannelIds(
                followedIds = listOf("first", "second"),
                preferenceEnabledIds = null,
            ),
        )
    }
}
