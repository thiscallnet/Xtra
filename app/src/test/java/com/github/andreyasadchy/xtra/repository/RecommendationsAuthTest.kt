package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.repository.auth.PrivateGqlCredential
import com.github.andreyasadchy.xtra.repository.auth.PrivateGqlCredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationsAuthTest {

    @Test
    fun officialOnlySessionIsAnonymousForPrivateRecommendations() {
        val auth = recommendationAuthFor(
            officialUserId = "account-a",
            credential = null,
        )

        assertEquals(RecommendationAuthMode.ANONYMOUS, auth.mode)
        assertNull(auth.userId)
    }

    @Test
    fun mismatchedPrivateCredentialCannotAuthenticateAnotherAccount() {
        val auth = recommendationAuthFor(
            officialUserId = "account-a",
            credential = PrivateGqlCredential(
                type = PrivateGqlCredentialType.WEB,
                clientId = "gql-client",
                accessToken = "private-token",
                userId = "account-b",
            ),
        )

        assertEquals(RecommendationAuthMode.ANONYMOUS, auth.mode)
        assertNull(auth.userId)
    }

    @Test
    fun matchingWebCredentialIsUsedForPersonalSections() {
        val auth = recommendationAuthFor(
            officialUserId = "account-a",
            credential = PrivateGqlCredential(
                type = PrivateGqlCredentialType.WEB,
                clientId = "gql-client",
                accessToken = "private-token",
                userId = "account-a",
            ),
        )

        assertEquals(RecommendationAuthMode.WEB, auth.mode)
        assertEquals("account-a", auth.userId)
    }

    @Test
    fun fallbackAndUnavailableSourcesAreDistinguishable() {
        val fallbackStream = com.github.andreyasadchy.xtra.model.ui.Stream(channelId = "popular")

        assertEquals(
            RecommendationSource.FALLBACK,
            recommendationSourceFor(personalized = null, result = listOf(fallbackStream)),
        )
        assertEquals(
            RecommendationSource.UNAVAILABLE,
            recommendationSourceFor(personalized = null, result = emptyList()),
        )
    }
}
