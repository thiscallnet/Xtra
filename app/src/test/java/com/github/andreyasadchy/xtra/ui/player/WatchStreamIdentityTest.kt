package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStreamIdentityTest {
    @Test
    fun staleResultFromPreviousChannelIsIgnored() {
        assertNull(
            acceptedWatchStreamId(
                expectedChannelId = "channel-a",
                expectedChannelLogin = "channel-a",
                currentChannelId = "channel-b",
                currentChannelLogin = "channel-b",
                currentStreamId = "stream-b",
                resolvedStreamId = "stream-a-new",
            ),
        )
    }

    @Test
    fun differentBroadcastIsAcceptedForTheCurrentChannel() {
        assertEquals(
            "stream-new",
            acceptedWatchStreamId(
                expectedChannelId = "channel-a",
                expectedChannelLogin = "channel-a",
                currentChannelId = "channel-a",
                currentChannelLogin = "channel-a",
                currentStreamId = "stream-old",
                resolvedStreamId = "stream-new",
            ),
        )
    }

    @Test
    fun failedOrUnchangedLookupHasNoIdentityUpdate() {
        assertNull(
            acceptedWatchStreamId(
                expectedChannelId = "channel-a",
                expectedChannelLogin = "channel-a",
                currentChannelId = "channel-a",
                currentChannelLogin = "channel-a",
                currentStreamId = "stream-a",
                resolvedStreamId = null,
            ),
        )
        assertNull(
            acceptedWatchStreamId(
                expectedChannelId = "channel-a",
                expectedChannelLogin = "channel-a",
                currentChannelId = "channel-a",
                currentChannelLogin = "channel-a",
                currentStreamId = "stream-a",
                resolvedStreamId = "stream-a",
            ),
        )
    }
}
