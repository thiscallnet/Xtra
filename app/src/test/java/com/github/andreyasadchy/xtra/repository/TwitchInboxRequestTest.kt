package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.LocalSendState
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessagePreview
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadDetails
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadPage
import com.github.andreyasadchy.xtra.ui.whispers.discoverThreadWithRetry
import com.github.andreyasadchy.xtra.ui.whispers.filterWhisperThreads
import com.github.andreyasadchy.xtra.ui.whispers.mergeWhisperMessages
import com.github.andreyasadchy.xtra.ui.whispers.nextOlderCursor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.security.SecureRandom

class TwitchInboxRequestTest {
    @Test
    fun buildsSendWhisperVariablesWithoutChangingMessageText() {
        val variables = buildSendWhisperVariables("recipient-1", "line one\nline two", "0123456789abcdef0123456789abcdef")
        val input = variables.getValue("input") as kotlinx.serialization.json.JsonObject
        assertEquals("recipient-1", input.getValue("recipientUserID").jsonPrimitive.content)
        assertEquals("line one\nline two", input.getValue("message").jsonPrimitive.content)
        assertEquals(32, input.getValue("nonce").jsonPrimitive.content.length)
    }

    @Test
    fun whisperNonceIsCryptographicallySizedAndUnique() {
        val random = SecureRandom()
        val first = generateWhisperNonce(random)
        val second = generateWhisperNonce(random)
        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first, second)
    }

    @Test
    fun nonJsonHttpStatusesMapBeforeGraphQlParsing() {
        assertEquals(TwitchInboxError.RequiresReauth, privateGqlHttpError(401))
        assertTrue(privateGqlHttpError(429) is TwitchInboxError.RateLimited)
        assertEquals(TwitchInboxError.TwitchServerError, privateGqlHttpError(502))
    }

    @Test
    fun integrityFailureIsNotReportedAsPrivateApiChange() {
        val error = privateGqlError("Notifications", "failed integrity check", 200)
        assertTrue(error is TwitchInboxError.GraphQl)
        assertFalse(error is TwitchInboxError.PrivateApiChanged)
    }

    @Test
    fun localWhisperSearchKeepsExistingConversationMatches() {
        val peer = TwitchUserSummary("peer-1", "coldblackice", "Coldblackice", null)
        val thread = WhisperThread(
            id = "thread-1",
            peer = peer,
            lastMessage = WhisperMessagePreview("hello", "peer-1", Instant.parse("2026-08-25T12:00:00Z")),
            unreadCount = 0,
            isUnread = false,
            updatedAt = Instant.parse("2026-08-25T12:00:00Z"),
        )
        assertEquals(listOf(thread), filterWhisperThreads(listOf(thread), "cold"))
    }

    @Test
    fun optimisticWhisperSurvivesDelayedCanonicalHistoryAndReconcilesByNonce() {
        val local = WhisperMessage(
            id = "local-1",
            nonce = "nonce-1",
            senderId = "user-1",
            text = "hello",
            sentAt = Instant.parse("2026-08-26T12:00:00Z"),
            isMine = true,
            localState = LocalSendState.SENDING,
        )
        val pending = mapOf(local.id to local)
        val beforePropagation = mergeWhisperMessages(listOf(local), emptyList(), pending, replace = false)
        assertEquals(listOf("local-1"), beforePropagation.messages.map { it.id })
        assertTrue(beforePropagation.pending.containsKey("local-1"))

        val canonical = local.copy(id = "server-1", localState = LocalSendState.CONFIRMED)
        val canonicalWithSameNonce = local.copy(
            id = "server-2",
            text = "another server message",
            localState = LocalSendState.CONFIRMED,
        )
        val afterPropagation = mergeWhisperMessages(
            beforePropagation.messages,
            listOf(canonical, canonicalWithSameNonce, canonical),
            beforePropagation.pending,
            replace = false,
        )
        assertEquals(listOf("server-1", "server-2"), afterPropagation.messages.map { it.id })
        assertTrue(afterPropagation.pending.isEmpty())
    }

    @Test
    fun notificationSummaryDoesNotInferUnreadFromUnknownCount() {
        val withExplicitUnread = Json.parseToJsonElement(
            """{"data":{"currentUser":{"notifications":{"summary":{"viewerUnreadSummary":{"unreadCount":2}}}}}}""",
        ).jsonObject
        val unread = parseNotificationSummary(withExplicitUnread)
        assertNull(unread.count)
        assertTrue(unread.hasUnread)

        val withoutUnreadEvidence = Json.parseToJsonElement(
            """{"data":{"currentUser":{"notifications":{"summary":{}}}}}""",
        ).jsonObject
        val unknown = parseNotificationSummary(withoutUnreadEvidence)
        assertNull(unknown.count)
        assertFalse(unknown.hasUnread)

        val aggregateOverridesSeenCount = Json.parseToJsonElement(
            """{"data":{"currentUser":{"notifications":{"summary":{"unseenCount":0,"viewerUnreadSummary":{"unreadCount":2}}}}}}""",
        ).jsonObject
        val stillUnread = parseNotificationSummary(aggregateOverridesSeenCount)
        assertEquals(0, stillUnread.count)
        assertTrue(stillUnread.hasUnread)
    }

    @Test
    fun whisperUnreadBadgeDoesNotClaimPartialExactCount() = runBlocking {
        val pages = listOf(
            WhisperThreadPage(emptyList(), "cursor-1", true, null),
            WhisperThreadPage(listOf(unreadThread("thread-1")), "cursor-2", true, null),
            WhisperThreadPage((2..5).map { unreadThread("thread-$it") }, null, false, null),
        )
        var requested = 0
        val summary = scanWhisperUnreadPages(20) {
            pages[requested++]
        }
        assertNull(summary.count)
        assertTrue(summary.hasUnread)
        assertEquals(2, requested)

        requested = 0
        val unknownAtCap = scanWhisperUnreadPages(2) {
            WhisperThreadPage(listOf(unreadThread("cap-$requested")), "cap-${requested++}", true, null)
        }
        assertNull(unknownAtCap.count)
        assertTrue(unknownAtCap.hasUnread)
        assertEquals(1, requested)
    }

    @Test
    fun whisperUnreadBadgeStopsAfterFindingUnreadOnAnUnfinishedPage() = runBlocking {
        var requested = 0
        val summary = scanWhisperUnreadPages(5) {
            requested++
            if (requested == 1) {
                WhisperThreadPage(emptyList(), "cursor-1", true, null)
            } else {
                WhisperThreadPage(listOf(unreadThread("thread-1")), "cursor-2", true, null)
            }
        }
        assertNull(summary.count)
        assertTrue(summary.hasUnread)
        assertEquals(2, requested)
    }

    @Test
    fun whisperUnreadBadgeReturnsExactCountAtConnectionEnd() = runBlocking {
        var requested = 0
        val summary = scanWhisperUnreadPages(5) {
            requested++
            if (requested == 1) {
                WhisperThreadPage(emptyList(), "cursor-1", true, null)
            } else {
                WhisperThreadPage(listOf(unreadThread("thread-1")), null, false, null)
            }
        }
        assertEquals(1, summary.count)
        assertTrue(summary.hasUnread)
        assertEquals(2, requested)
    }

    @Test
    fun whisperUnreadBadgeRespectsNoUnreadScanCap() = runBlocking {
        var requested = 0
        val summary = scanWhisperUnreadPages(3) {
            requested++
            WhisperThreadPage(emptyList(), "cursor-$requested", true, null)
        }
        assertNull(summary.count)
        assertFalse(summary.hasUnread)
        assertEquals(3, requested)
    }

    @Test
    fun olderWhisperCursorUsesOpaqueResponseCursor() {
        val peer = TwitchUserSummary("peer-1", "peer", "Peer", null)
        val message = WhisperMessage("message-id", null, "peer-1", "hello", Instant.EPOCH, false)
        val details = WhisperThreadDetails(peer, listOf(message), "opaque-cursor", true, null)
        assertEquals("opaque-cursor", nextOlderCursor(details, null))
        assertNull(nextOlderCursor(details, "opaque-cursor"))
    }

    @Test
    fun newConversationDiscoveryRetriesAfterPropagationDelay() = runBlocking {
        var attempts = 0
        val threadId = discoverThreadWithRetry(
            findThread = {
                attempts++
                if (attempts == 3) "thread-new" else null
            },
            delaysMillis = longArrayOf(0, 0, 0),
        )
        assertEquals("thread-new", threadId)
        assertEquals(3, attempts)
    }

    private fun unreadThread(id: String) = WhisperThread(
        id = id,
        peer = TwitchUserSummary("peer-$id", "peer$id", "Peer $id", null),
        lastMessage = null,
        unreadCount = 1,
        isUnread = true,
        updatedAt = null,
    )
}
