package com.github.andreyasadchy.xtra.repository.datasource

import com.apollographql.apollo.api.Optional
import com.github.andreyasadchy.xtra.graphql.UserFollowedStreamsQuery
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedStreamsPageLoaderTest {

    @Test
    fun followedStreamsQuerySendsSelectedSortArgument() {
        val query = UserFollowedStreamsQuery(
            first = Optional.Present(100),
            after = Optional.Present(null),
            sort = Optional.Present(StreamSort.RELEVANCE),
        )

        assertTrue(query.document().contains("sort:"))
    }

    @Test
    fun persistedGraphQlFallbackKeepsItsCursorAffinity() = runBlocking {
        var selectedApi: String? = null
        val calls = mutableListOf<String>()

        val firstPage = loadFollowedFirstPageWithFallback(
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
