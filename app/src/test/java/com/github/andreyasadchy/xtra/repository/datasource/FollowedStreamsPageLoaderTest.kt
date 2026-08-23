package com.github.andreyasadchy.xtra.repository.datasource

import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FollowedStreamsPageLoaderTest {

    @Test
    fun relevanceDoesNotUseUnsupportedFallbackApis() = runBlocking {
        val calls = mutableListOf<String>()
        val primaryError = TwitchApiException(
            statusCode = 429,
            rateLimitResetEpochSeconds = 123L,
            message = "gql rate limited",
        )
        val failure = runCatching {
            loadFollowedFirstPageWithFallback(
                sort = StreamsSortDialog.RELEVANCE,
                onApiSelected = { calls += it },
                gql = { calls += "gql-first"; throw primaryError },
                persistedGql = { calls += "persisted-first"; error("must not be called") },
                helix = { calls += "helix-first"; error("must not be called") },
            )
        }.exceptionOrNull()

        assertSame(primaryError, failure)
        assertEquals(listOf(C.GQL, "gql-first"), calls)
    }

    @Test
    fun persistedGraphQlFallbackKeepsItsCursorAffinity() = runBlocking {
        var selectedApi: String? = null
        val calls = mutableListOf<String>()

        val firstPage = loadFollowedFirstPageWithFallback(
            sort = StreamsSortDialog.SORT_VIEWERS,
            onApiSelected = { selectedApi = it; calls += it },
            gql = { calls += "gql-first"; error("gql unavailable") },
            persistedGql = { calls += "persisted-first"; "first" },
            helix = { calls += "helix-first"; "helix" },
        )
        val nextPage = loadFollowedPageForCursor(
            cursor = StreamFeedCursor(selectedApi!!, "persisted-cursor"),
            gql = { calls += "gql:$it"; "wrong" },
            persistedGql = { calls += "persisted:$it"; "second" },
            helix = { calls += "helix:$it"; "wrong" },
        )

        assertEquals("first", firstPage)
        assertEquals("second", nextPage)
        assertEquals(
            listOf(C.GQL, "gql-first", C.GQL_PERSISTED_QUERY, "persisted-first", "persisted:persisted-cursor"),
            calls,
        )
    }

    @Test
    fun helixFallbackKeepsItsCursorAffinity() = runBlocking {
        var selectedApi: String? = null
        val calls = mutableListOf<String>()

        loadFollowedFirstPageWithFallback(
            sort = StreamsSortDialog.SORT_VIEWERS,
            onApiSelected = { selectedApi = it; calls += it },
            gql = { calls += "gql-first"; error("gql unavailable") },
            persistedGql = { calls += "persisted-first"; error("persisted unavailable") },
            helix = { calls += "helix-first"; "first" },
        )
        val nextPage = loadFollowedPageForCursor(
            cursor = StreamFeedCursor(selectedApi!!, "helix-cursor"),
            gql = { calls += "gql:$it"; "wrong" },
            persistedGql = { calls += "persisted:$it"; "wrong" },
            helix = { calls += "helix:$it"; "second" },
        )

        assertEquals("second", nextPage)
        assertEquals(
            listOf(
                C.GQL,
                "gql-first",
                C.GQL_PERSISTED_QUERY,
                "persisted-first",
                C.HELIX,
                "helix-first",
                "helix:helix-cursor",
            ),
            calls,
        )
    }
}
