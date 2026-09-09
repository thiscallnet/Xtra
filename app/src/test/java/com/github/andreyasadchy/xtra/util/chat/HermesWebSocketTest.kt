package com.github.andreyasadchy.xtra.util.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesWebSocketTest {
    @Test
    fun raidMonitorCanExcludeUnrelatedChannelTopics() {
        assertEquals(
            emptyList<String>(),
            hermesChannelTopics("100", includeChannelTopics = false),
        )
        assertEquals(
            listOf(
                "video-playback-by-id.100",
                "broadcast-settings-update.100",
                "community-points-channel-v1.100",
            ),
            hermesChannelTopics("100", includeChannelTopics = true),
        )
    }

    @Test
    fun streamInfoReadsStringCategoryIdFromHermes() {
        val info = PubSubUtils.parseStreamInfo(
            JSONObject(
                """{"status":"Playing","game":"Game","game_id":"1234"}""",
            ),
        )

        assertEquals("Game", info.gameName)
        assertEquals("1234", info.gameId)
    }
}
