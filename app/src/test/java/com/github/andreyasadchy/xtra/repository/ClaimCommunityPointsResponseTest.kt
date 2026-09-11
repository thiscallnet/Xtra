package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.gql.ClaimCommunityPointsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimCommunityPointsResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missingMutationResultIsNotSuccessful() {
        val response = json.decodeFromString<ClaimCommunityPointsResponse>(
            """{"data":{"claimCommunityPoints":null}}""",
        )

        assertFalse(response.isSuccessful)
    }

    @Test
    fun mutationResultWithoutErrorsIsSuccessful() {
        val response = json.decodeFromString<ClaimCommunityPointsResponse>(
            """{"data":{"claimCommunityPoints":{"currentPoints":50}}}""",
        )

        assertTrue(response.isSuccessful)
    }
}
