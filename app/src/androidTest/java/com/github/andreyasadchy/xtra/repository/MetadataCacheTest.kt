package com.github.andreyasadchy.xtra.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformation
import com.github.andreyasadchy.xtra.model.helix.chat.ChatSettings
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUser
import com.github.andreyasadchy.xtra.model.helix.user.User as HelixUser
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.User
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataCacheTest {

    private val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()
    private val cache = MetadataCache(database, Json { ignoreUnknownKeys = true })

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun accountChannelAndGamePayloadsRoundTripThroughRoom() = runBlocking {
        val now = System.currentTimeMillis()
        cache.writeAccount(
            userId = "42",
            login = "Streamer",
            snapshot = AccountCacheSnapshot(
                user = HelixUser(id = "42", login = "Streamer", displayName = "Streamer", description = "Bio"),
                scopes = setOf("user:edit", "channel:manage:broadcast"),
                chatColor = "#123456",
                channel = ChannelInformation(broadcasterId = "42", title = "Live", tags = listOf("one")),
                chatSettings = ChatSettings(broadcasterId = "42", followerMode = true, followerModeDuration = 10),
                blockedUsers = listOf(BlockedUser(id = "9", login = "blocked", displayName = "Blocked")),
                blockedUsersCursor = "next",
            ),
            nowMs = now,
        )

        val account = cache.readAccount(null, "STREAMER")
        assertNotNull(account)
        assertEquals("Bio", account?.user?.description)
        assertEquals(setOf("user:edit", "channel:manage:broadcast"), account?.scopes)
        assertEquals("Live", account?.channel?.title)
        assertEquals(true, account?.chatSettings?.followerMode)
        assertEquals("next", account?.blockedUsersCursor)

        cache.writeChannel(
            channelId = "99",
            login = "Channel",
            snapshot = ChannelPageCacheSnapshot(
                user = User(id = "99", login = "Channel", name = "Channel"),
                stream = Stream(id = "stream", channelId = "99", title = "Title"),
            ),
            nowMs = now,
        )
        assertEquals("Title", cache.readChannel(null, "channel")?.stream?.title)

        cache.writeGame(
            gameId = "7",
            slug = "game-slug",
            name = "Game",
            snapshot = GamePageCacheSnapshot(
                Game(id = "7", slug = "game-slug", name = "Game", tags = listOf(Tag(id = "t", name = "Tag"))),
            ),
            nowMs = now,
        )
        assertEquals("Tag", cache.readGame(null, null, "game")?.game?.tags?.firstOrNull()?.name)
    }
}
