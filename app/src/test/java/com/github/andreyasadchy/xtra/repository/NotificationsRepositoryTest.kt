package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsRepositoryTest {

    @Test
    fun profileLookupOnlyTargetsNewStreamsWithoutCachedImages() {
        assertEquals(
            listOf("missing", "also-missing"),
            streamIdsMissingProfileImages(
                listOf(
                    Stream(channelId = "missing"),
                    Stream(channelId = "cached", channelImageURL = "https://example.test/avatar.png"),
                    Stream(channelId = "missing"),
                    Stream(channelId = "also-missing"),
                    Stream(channelId = " "),
                )
            ),
        )
    }

    @Test
    fun profileLookupFailureLeavesNotificationStreamMetadataUntouched() {
        val stream = Stream(channelId = "channel", title = "Live title", gameName = "Game")

        mergeProfileImages(listOf(stream), emptyMap())

        assertEquals("Live title", stream.title)
        assertEquals("Game", stream.gameName)
        assertEquals(null, stream.channelImageURL)
    }

    @Test
    fun profileLookupAddsTheReturnedAvatarUrl() {
        val stream = Stream(channelId = "channel")

        mergeProfileImages(listOf(stream), mapOf("channel" to "https://example.test/avatar.png"))

        assertEquals("https://example.test/avatar.png", stream.channelImageURL)
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
    fun `web session preferences filter followed channels`() {
        assertEquals(
            setOf("enabled"),
            selectNotificationChannelIds(
                followedIds = listOf("enabled", "disabled"),
                preferenceEnabledIds = setOf("enabled", "unfollowed"),
            ),
        )
    }

    @Test
    fun `unavailable web session preferences fall back to Helix follows`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            webSessionAvailable = false,
        ) {
            error("the loader must not run without an authenticated web session")
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
    fun `transient web session preference failure retains the previous channel set`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            webSessionAvailable = true,
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
    fun `transient web session preference failure uses Helix follows for an empty cache`() = runBlocking {
        val preferences = loadOptionalNotificationPreferenceIds(
            webSessionAvailable = true,
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

        assertTrue(authFailure is NotificationPreferenceLoadResult.WebSessionUnavailable)
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
