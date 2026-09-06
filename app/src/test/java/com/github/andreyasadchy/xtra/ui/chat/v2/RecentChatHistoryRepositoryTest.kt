package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.model.gql.video.VideoMessagesResponse
import com.github.andreyasadchy.xtra.model.gql.video.lastNode
import com.github.andreyasadchy.xtra.model.gql.video.nextCursor
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.session.RecentChatHistoryPage
import com.github.andreyasadchy.xtra.ui.chat.v2.session.RecentChatHistoryRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.session.RecentChatHistorySource
import com.github.andreyasadchy.xtra.ui.chat.v2.session.paginateRecentChatPages
import com.github.andreyasadchy.xtra.ui.chat.v2.session.toV2
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentChatHistoryRepositoryTest {
    private val spec = LiveChatSessionSpec("42", "channel", streamId = "stream")
    private val message = ChatMessage(
        id = ChatMessageId("message"), channelId = "42", timestampMs = 1,
        user = null, badges = emptyList(), segments = listOf(ChatSegment.Text("hello")),
        kind = ChatMessageKind.CHAT,
    )

    @Test fun prefersTwitchWhenItHasHistory() = runBlocking {
        var robottyCalled = false
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource { listOf(message) },
            robotty = RecentChatHistorySource { robottyCalled = true; emptyList() },
            log = {},
        ).load(spec)
        assertEquals(listOf(message), result)
        assertTrue(!robottyCalled)
    }

    @Test fun fallsBackWhenVodIsUnavailable() = runBlocking {
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource { emptyList() },
            robotty = RecentChatHistorySource { listOf(message) },
            log = {},
        ).load(spec)
        assertEquals(listOf(message), result)
    }

    @Test fun disabledHistoryDoesNotQueryEitherSource() = runBlocking {
        var calls = 0
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource { calls++; listOf(message) },
            robotty = RecentChatHistorySource { calls++; listOf(message) },
            enabled = { false },
            log = {},
        ).load(spec)
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun twitchTimeoutFallsBackToRobotty() = runBlocking {
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource { delay(50); listOf(message) },
            robotty = RecentChatHistorySource { listOf(message) },
            log = {},
            twitchTimeoutMs = 1,
            robottyTimeoutMs = 100,
        ).load(spec)
        assertEquals(listOf(message), result)
    }

    @Test fun twitchSerializationFailureFallsBackToRobotty() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        var robottyCalls = 0
        val logs = mutableListOf<String>()
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource {
                json.decodeFromString<VideoMessagesResponse>(
                    """{"data":{"video":{"comments":{"edges":{"invalid":true}}}}}""",
                )
                error("unreachable")
            },
            robotty = RecentChatHistorySource {
                robottyCalls += 1
                listOf(message)
            },
            log = logs::add,
        ).load(spec)

        assertEquals(listOf(message), result)
        assertEquals(1, robottyCalls)
        assertTrue(logs.contains("history fallback=twitch-failed reason=JsonDecodingException"))
    }

    @Test fun twitchResponseAcceptsNullableHistoryContainersAndEntries() {
        val json = Json { ignoreUnknownKeys = true }
        fun decode(payload: String) = json.decodeFromString<VideoMessagesResponse>(payload)

        assertNull(decode("""{"data":null}""").data)
        assertNull(decode("""{"data":{"video":null}}""").data?.video)
        assertNull(decode("""{"data":{"video":{"comments":null}}}""").data?.video?.comments)
        assertNull(decode("""{"data":{"video":{"comments":{"edges":null}}}}""").data?.video?.comments?.edges)
        assertNull(decode("""{"data":{"video":{"comments":{"edges":[null]}}}}""").data?.video?.comments?.edges?.single())
        assertNull(decode("""{"data":{"video":{"comments":{"edges":[{"cursor":"cursor-node-null","node":null}]}}}}""").data?.video?.comments?.edges?.single()?.node)

        val comments = decode(
            """{"data":{"video":{"comments":{"edges":[{"cursor":"cursor-valid","node":{"id":"valid"}},null],"pageInfo":{"hasNextPage":true}}}}}""",
        ).data!!.video!!.comments!!
        assertEquals("cursor-valid", comments.nextCursor)
        assertEquals("valid", comments.lastNode?.id)
    }

    @Test fun gqlFragmentsKeepTheirEmotePositions() {
        val comment = VideoMessagesResponse.Comment(
            id = "gql-message",
            createdAt = "2026-09-03T10:00:00Z",
            message = VideoMessagesResponse.Message(
                fragments = listOf(
                    VideoMessagesResponse.Fragment(text = "hello "),
                    VideoMessagesResponse.Fragment(text = "Kappa", emote = VideoMessagesResponse.Emote("25")),
                    VideoMessagesResponse.Fragment(text = " world"),
                ),
            ),
        )
        val normalized = comment.toV2("42")!!
        assertEquals("hello Kappa world", normalized.rawText)
        assertEquals(3, normalized.segments.size)
        assertEquals("hello ", (normalized.segments[0] as ChatSegment.Text).text)
        assertEquals("Kappa", (normalized.segments[1] as ChatSegment.Emote).fallbackText)
        assertEquals(" world", (normalized.segments[2] as ChatSegment.Text).text)
    }

    @Test fun replayPaginationReachesNewestPage() = runBlocking {
        val requests = mutableListOf<Pair<Int?, String?>>()
        val result = paginateRecentChatPages(
            initialOffsetSeconds = 100,
            load = { offset, cursor ->
                requests += offset to cursor
                when (cursor) {
                    null -> RecentChatHistoryPage(listOf("old"), hasNextPage = true, nextCursor = "page-2")
                    "page-2" -> RecentChatHistoryPage(listOf("new"), hasNextPage = false, nextCursor = null)
                    else -> error("unexpected cursor")
                }
            },
            isAtLiveEdge = { it == "new" },
        )
        assertEquals(listOf("old", "new"), result.items)
        assertTrue(result.reachedLiveEdge)
        assertEquals(listOf(100 to null, null to "page-2"), requests)
    }

    @Test fun paginationExhaustionIsIncompleteAndFallsBackToRobotty() = runBlocking {
        var robottyCalled = false
        val result = RecentChatHistoryRepository(
            twitch = RecentChatHistorySource {
                val pagination = paginateRecentChatPages(
                    initialOffsetSeconds = 100,
                    maxPages = 2,
                    load = { _, cursor ->
                        RecentChatHistoryPage(
                            items = listOf(cursor ?: "page-1"),
                            hasNextPage = true,
                            nextCursor = "next-${cursor ?: "page-1"}",
                        )
                    },
                    isAtLiveEdge = { false },
                )
                assertTrue(!pagination.reachedLiveEdge)
                emptyList()
            },
            robotty = RecentChatHistorySource { robottyCalled = true; listOf(message) },
            log = {},
        ).load(spec)

        assertTrue(robottyCalled)
        assertEquals(listOf(message), result)
    }

    @Test fun missingCreatedAtUsesVodStartPlusOffset() {
        val comment = VideoMessagesResponse.Comment(
            id = "offset-message",
            contentOffsetSeconds = 3_900,
            message = VideoMessagesResponse.Message(
                fragments = listOf(VideoMessagesResponse.Fragment(text = "hello")),
            ),
        )
        val normalized = comment.toV2("42", vodStartTimestampMs = 1_000_000L)!!
        assertEquals(4_900_000L, normalized.timestampMs)
    }
}
