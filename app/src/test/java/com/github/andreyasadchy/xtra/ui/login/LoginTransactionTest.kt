package com.github.andreyasadchy.xtra.ui.login

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginTransactionTest {

    @Test
    fun `cancellation is rejected once the complete session commit starts`() {
        assertTrue(canCancelLogin(finalCommitStarted = false))
        assertFalse(canCancelLogin(finalCommitStarted = true))
    }

    @Test
    fun `account cleanup disables scheduling and clears account state`() = runBlocking {
        val calls = mutableListOf<String>()

        clearAccountScopedState(
            disableScheduler = { calls += "disable scheduler" },
            disableNotifications = { calls += "disable notifications" },
            clearNotificationState = { calls += "clear notifications" },
            clearAccountMetadata = { calls += "clear metadata" },
        )

        assertEquals(
            listOf(
                "disable scheduler",
                "disable notifications",
                "clear notifications",
                "clear metadata",
            ),
            calls,
        )
    }
}
