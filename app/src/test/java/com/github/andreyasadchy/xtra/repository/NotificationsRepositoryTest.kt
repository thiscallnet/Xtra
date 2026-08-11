package com.github.andreyasadchy.xtra.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsRepositoryTest {

    @Test
    fun rejectsEventsForDisabledChannels() {
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = false, shownStartedAt = null, eventStartedAt = 100L))
    }

    @Test
    fun rejectsDuplicateOrOlderStreamStarts() {
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 100L, eventStartedAt = 100L))
        assertFalse(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 200L, eventStartedAt = 100L))
    }

    @Test
    fun acceptsNewerStreamStarts() {
        assertTrue(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = 100L, eventStartedAt = 200L))
        assertTrue(shouldEnqueueStreamOnline(channelEnabled = true, shownStartedAt = null, eventStartedAt = 100L))
    }
}
