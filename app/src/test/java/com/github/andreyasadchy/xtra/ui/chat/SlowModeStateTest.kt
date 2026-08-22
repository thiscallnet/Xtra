package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowModeStateTest {

    @Test
    fun remainingTimeRoundsUpUntilTheDeadline() {
        assertEquals(30, slowModeRemainingSeconds(30, 1_000L, 1_000L))
        assertEquals(29, slowModeRemainingSeconds(30, 1_000L, 2_001L))
        assertEquals(1, slowModeRemainingSeconds(30, 1_000L, 30_001L))
        assertEquals(0, slowModeRemainingSeconds(30, 1_000L, 31_000L))
    }

    @Test
    fun onlyDefinitelyApplicableUsersAreBlocked() {
        assertTrue(
            SlowModeState(
                intervalSeconds = 30,
                remainingSeconds = 1,
                applicability = SlowModeApplicability.APPLIES,
            ).blocked,
        )
        assertFalse(
            SlowModeState(
                intervalSeconds = 30,
                remainingSeconds = 1,
                applicability = SlowModeApplicability.UNKNOWN,
            ).blocked,
        )
        assertFalse(
            SlowModeState(
                intervalSeconds = 30,
                remainingSeconds = 1,
                applicability = SlowModeApplicability.EXEMPT,
            ).blocked,
        )
    }

    @Test
    fun writeAcknowledgementAndReadEchoAreOneAcceptance() {
        val dedupe = SlowModeMessageDedupe()

        assertTrue(
            dedupe.accept(
                SlowModeMessageIdentity(messageId = "message-id"),
                nowElapsedRealtime = 1_000L,
            ),
        )
        assertFalse(
            dedupe.accept(
                SlowModeMessageIdentity(messageId = "message-id"),
                nowElapsedRealtime = 1_200L,
            ),
        )
    }

    @Test
    fun apiAcceptanceAndReadEchoWithDifferentIdAreOneAcceptance() {
        val dedupe = SlowModeMessageDedupe()

        assertTrue(
            dedupe.accept(
                SlowModeMessageIdentity(message = "hello", replyId = "parent-id"),
                nowElapsedRealtime = 1_000L,
            ),
        )
        assertFalse(
            dedupe.accept(
                SlowModeMessageIdentity(
                    messageId = "message-id",
                    message = "hello",
                    replyId = "parent-id",
                ),
                nowElapsedRealtime = 1_250L,
            ),
        )
    }

    @Test
    fun sameMessageWithoutIdAfterDedupeWindowIsAcceptedAgain() {
        val dedupe = SlowModeMessageDedupe()
        val identity = SlowModeMessageIdentity(message = "hello")

        assertTrue(dedupe.accept(identity, nowElapsedRealtime = 1_000L))
        assertTrue(dedupe.accept(identity, nowElapsedRealtime = 1_501L))
    }

    @Test
    fun messageIdRemainsDeduplicatedWhenReadEchoIsDelayed() {
        val dedupe = SlowModeMessageDedupe()
        val identity = SlowModeMessageIdentity(messageId = "message-id")

        assertTrue(dedupe.accept(identity, nowElapsedRealtime = 1_000L))
        assertFalse(dedupe.accept(identity, nowElapsedRealtime = 30_000L))
    }
}
