package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationNoticeCoalescerTest {
    @Test
    fun clearThenBanEmitsStateAndActorButSuppressesGenericNotice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var clearCount = 0
            var fallbackCount = 0
            var actionCount = 0

            coalescer.clear(clear("clear-1", 1_000L), { clearCount++ }, { fallbackCount++ })
            coalescer.action(action("ban-1", 1_050L)) { actionCount++ }
            delay(40L)

            assertEquals(1, clearCount)
            assertEquals(1, actionCount)
            assertEquals(0, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun banThenClearConsumesOnlyOneMatchingClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var fallbackCount = 0

            coalescer.action(action("ban-1", 1_000L)) {}
            coalescer.clear(clear("clear-1", 1_000L), {}, { fallbackCount++ })
            coalescer.clear(clear("clear-2", 1_010L), {}, { fallbackCount++ })
            delay(40L)

            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun twoActionsPairWithTwoClosestClears() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var fallbackCount = 0

            coalescer.action(action("ban-1", 1_000L)) {}
            coalescer.action(action("ban-2", 1_030L)) {}
            coalescer.clear(clear("clear-1", 1_005L), {}, { fallbackCount++ })
            coalescer.clear(clear("clear-2", 1_025L), {}, { fallbackCount++ })
            delay(40L)

            assertEquals(0, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun actionConsumesClosestPendingClearInsteadOfFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var fallbackCount = 0

            coalescer.clear(clear("farther-clear", 1_020L), {}, { fallbackCount++ })
            coalescer.clear(clear("nearer-clear", 1_000L), {}, { fallbackCount++ })
            coalescer.action(action("ban-1", 1_001L)) {}
            delay(40L)

            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun duplicateEventIdsDoNotCreateExtraRowsOrCorrelationRecords() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var clearCount = 0
            var actionCount = 0
            var fallbackCount = 0

            coalescer.action(action("ban-1", 1_000L)) { actionCount++ }
            coalescer.action(action("ban-1", 1_000L)) { actionCount++ }
            coalescer.clear(clear("clear-1", 1_000L), { clearCount++ }, { fallbackCount++ })
            coalescer.clear(clear("clear-1", 1_000L), { clearCount++ }, { fallbackCount++ })
            coalescer.clear(clear("clear-2", 1_010L), { clearCount++ }, { fallbackCount++ })
            delay(40L)

            assertEquals(1, actionCount)
            assertEquals(2, clearCount)
            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun serverEventTimeDoesNotExpireRecentActionUsingLocalClock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(
                scope = scope,
                gracePeriodMs = 20L,
                now = { 1_000_000L },
            )
            var fallbackCount = 0

            coalescer.action(action("ban-1", 1_000L)) {}
            coalescer.clear(clear("clear-1", 1_050L), {}, { fallbackCount++ })
            delay(40L)

            assertEquals(0, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingIdentityShowsGenericFallbackImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var fallbackCount = 0
            val event = ChatEvent.ClearUser(
                userId = null,
                userLogin = null,
                eventId = "clear-without-target",
                receivedAtMs = 1_000L,
            )

            coalescer.clear(event, {}, { fallbackCount++ })

            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unbanNearClearNeverConsumesTheClearFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var actionCount = 0
            var fallbackCount = 0

            coalescer.clear(clear("clear-1", 1_000L), {}, { fallbackCount++ })
            coalescer.action(action("unban-1", 1_000L, TwitchModeratorActionKind.REMOVE)) { actionCount++ }
            delay(40L)

            assertEquals(1, actionCount)
            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unmatchedClearKeepsGenericFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coalescer = ModerationNoticeCoalescer(scope, gracePeriodMs = 20L)
            var fallbackCount = 0

            coalescer.clear(clear("clear-1", 1_000L), {}, { fallbackCount++ })
            delay(40L)

            assertEquals(1, fallbackCount)
        } finally {
            scope.cancel()
        }
    }

    private fun clear(eventId: String, timestampMs: Long) = ChatEvent.ClearUser(
        userId = "42",
        userLogin = "amy",
        eventId = eventId,
        receivedAtMs = timestampMs,
    )

    private fun action(
        eventId: String,
        timestampMs: Long,
        kind: TwitchModeratorActionKind = TwitchModeratorActionKind.TIMEOUT,
    ) = TwitchModeratorActionNotice(
        kind = kind,
        moderator = "Mod_User",
        target = "Cool_User",
        reason = null,
        durationSeconds = if (kind == TwitchModeratorActionKind.TIMEOUT) 60L else null,
        targetId = "42",
        targetLogin = "amy",
        occurredAtMs = timestampMs,
        eventId = eventId,
    )
}
