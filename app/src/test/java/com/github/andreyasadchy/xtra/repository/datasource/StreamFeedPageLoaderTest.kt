package com.github.andreyasadchy.xtra.repository.datasource

import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFeedPageLoaderTest {

    @Test
    fun `next cursor requires explicit hasNextPage true`() {
        assertNull(
            nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = "a",
                candidate = "b",
                hasNextPage = null,
            )
        )
        assertNull(
            nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = "a",
                candidate = "b",
                hasNextPage = false,
            )
        )
    }

    @Test
    fun `next cursor rejects repeated cursor`() {
        assertNull(
            nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = "same",
                candidate = "same",
                hasNextPage = true,
            )
        )
    }

    @Test
    fun `next cursor accepts a new cursor`() {
        assertEquals(
            StreamFeedCursor(C.GQL, "next"),
            nextStreamFeedCursor(
                api = C.GQL,
                currentCursor = "current",
                candidate = "next",
                hasNextPage = true,
            )
        )
    }

    @Test
    fun `search page key preserves backend identity`() {
        assertEquals(
            SearchPageKey(C.GQL_PERSISTED_QUERY, "next"),
            nextSearchPageKey(
                api = C.GQL_PERSISTED_QUERY,
                currentCursor = "current",
                candidate = "next",
            )
        )
        assertNull(nextSearchPageKey(C.HELIX, "same", "same"))
    }
}
