package com.github.andreyasadchy.xtra.ui.multiview

import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MultiviewSessionStoreTest {
    @Test
    fun roundTripsSessionConfigurationAndStreamMetadata() {
        val first = Stream(
            id = "stream-1",
            channelId = "100",
            channelLogin = "Alpha",
            channelName = "Alpha Channel",
            gameId = "game-1",
            gameName = "Game",
            title = "First stream",
            createdAt = "2026-08-14T10:00:00Z",
            viewerCount = 123,
            tags = listOf("English", "Esports"),
        )
        val second = Stream(
            id = "stream-2",
            channelId = "200",
            channelLogin = "Beta",
            channelName = "Beta Channel",
        )
        val state = MultiviewSessionState(
            streams = listOf(first, second),
            activeIdentity = "id:200",
            focusedIdentity = "id:100",
            layoutMode = MultiviewLayoutMode.FOCUS,
            layoutBeforeFocus = MultiviewLayoutMode.GRID,
            fillVideo = true,
            chatVisible = true,
            combinedChat = true,
            chatIdentity = "id:200",
            qualityMode = MultiviewQualityMode.QUALITY_720P,
            qualityOverrides = mapOf("id:100" to "720p60"),
            audioVolumes = mapOf("id:100" to 0.25f, "id:200" to 0.75f),
        )

        val decoded = MultiviewSessionStore.decode(MultiviewSessionStore.encode(state))

        assertNotNull(decoded)
        assertEquals(state.activeIdentity, decoded?.activeIdentity)
        assertEquals(state.focusedIdentity, decoded?.focusedIdentity)
        assertEquals(state.layoutMode, decoded?.layoutMode)
        assertEquals(state.layoutBeforeFocus, decoded?.layoutBeforeFocus)
        assertEquals(state.fillVideo, decoded?.fillVideo)
        assertEquals(state.chatVisible, decoded?.chatVisible)
        assertEquals(state.combinedChat, decoded?.combinedChat)
        assertEquals(state.chatIdentity, decoded?.chatIdentity)
        assertEquals(state.qualityMode, decoded?.qualityMode)
        assertEquals(state.qualityOverrides, decoded?.qualityOverrides)
        assertEquals(state.audioVolumes, decoded?.audioVolumes)
        assertEquals(2, decoded?.streams?.size)
        assertEquals(first.id, decoded?.streams?.get(0)?.id)
        assertEquals(first.channelName, decoded?.streams?.get(0)?.channelName)
        assertEquals(first.viewerCount, decoded?.streams?.get(0)?.viewerCount)
        assertEquals(first.tags, decoded?.streams?.get(0)?.tags)
        assertEquals(second.channelLogin, decoded?.streams?.get(1)?.channelLogin)
    }
}
