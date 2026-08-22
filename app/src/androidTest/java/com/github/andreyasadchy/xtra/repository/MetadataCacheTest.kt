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
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            scopesValidated = true,
            chatColorValidated = true,
            channelValidated = true,
            chatSettingsValidated = true,
            blockedUsersValidated = true,
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

    @Test
    fun followingOverviewPayloadRoundTripsThroughRoom() = runBlocking {
        val snapshot = FollowingOverviewCacheSnapshot(
            recentVideos = listOf(
                Video(id = "video-1", channelName = "Channel", title = "Recent video"),
            ),
            upcomingStreams = listOf(
                UpcomingStream(
                    id = "channel-1:segment-1",
                    channelId = "channel-1",
                    channelLogin = "channel",
                    channelName = "Channel",
                    channelImageURL = "image",
                    title = "Upcoming stream",
                    gameName = "Game",
                    startTimeMillis = System.currentTimeMillis() + 60_000,
                    endTimeMillis = null,
                    isRecurring = false,
                ),
            ),
        )

        cache.writeFollowingOverview("42", snapshot)

        val cached = cache.readFollowingOverview("42")
        assertNotNull(cached)
        assertEquals(snapshot.recentVideos.single().id, cached?.recentVideos?.single()?.id)
        assertEquals(snapshot.recentVideos.single().channelName, cached?.recentVideos?.single()?.channelName)
        assertEquals(snapshot.recentVideos.single().title, cached?.recentVideos?.single()?.title)
        assertEquals(snapshot.upcomingStreams, cached?.upcomingStreams)

        cache.writeFollowingOverview(null, snapshot)

        val anonymousCached = cache.readFollowingOverview(null)
        assertNotNull(anonymousCached)
        assertEquals(snapshot.recentVideos.single().id, anonymousCached?.recentVideos?.single()?.id)
        assertEquals(snapshot.upcomingStreams, anonymousCached?.upcomingStreams)
    }

    @Test
    fun failedStreamFallbackKeepsLiveChannelInRoom() = runBlocking {
        val now = System.currentTimeMillis()
        val liveSnapshot = ChannelPageCacheSnapshot(
            user = User(id = "99", login = "channel", name = "Old name"),
            stream = Stream(id = "stream", channelId = "99", title = "Still live"),
        )
        cache.writeChannel(
            channelId = "99",
            login = "channel",
            snapshot = liveSnapshot,
            nowMs = now,
            streamValidated = true,
        )

        val resolution = resolveChannelFallback(
            cached = cache.readChannel("99", "channel"),
            streamResult = Result.failure(IllegalStateException("temporary failure")),
            userResult = Result.success(User(id = "99", login = "channel", name = "New name")),
        )
        assertTrue(resolution.shouldPersist)
        resolution.snapshot?.let { cache.writeChannel("99", "channel", it, now + 1) }

        val persisted = cache.readChannel("99", "channel")
        assertEquals("New name", persisted?.user?.name)
        assertEquals("stream", persisted?.stream?.id)
        assertEquals("Still live", persisted?.stream?.title)
    }

    @Test
    fun stableIdRejectsMismatchedAccountAliasAndSameIdAliasStillWorks() = runBlocking {
        val oldSnapshot = AccountCacheSnapshot(
            user = HelixUser(id = "old", login = "old-login", displayName = "Old"),
            scopes = setOf("old:scope"),
        )
        cache.writeAccount("old", "old-login", oldSnapshot)

        assertNull(cache.readAccount("new", "old-login"))
        assertNull(cache.readAccount(null, "old-login"))

        val currentSnapshot = AccountCacheSnapshot(
            user = HelixUser(id = "new", login = "new-login", displayName = "New"),
            scopes = setOf("new:scope"),
        )
        cache.writeAccount("new", "new-login", currentSnapshot)
        assertEquals("New", cache.readAccount("new", "new-login")?.user?.displayName)
        assertEquals("New", cache.readAccount(null, "new-login")?.user?.displayName)

        cache.writeAccount("new", "renamed-login", currentSnapshot)
        assertNull(cache.readAccount(null, "new-login"))
        assertEquals("New", cache.readAccount(null, "renamed-login")?.user?.displayName)
        assertNull(cache.readAccount(null, "old-login"))
    }

    @Test
    fun durableMetadataRetentionDoesNotMakeVolatileFieldsLookFresh() = runBlocking {
        val now = maxOf(
            MetadataCachePolicy.CURRENT_STREAM_BOOTSTRAP_MAX_AGE_MS,
            MetadataCachePolicy.FOLLOWER_COUNT_BOOTSTRAP_MAX_AGE_MS,
        ) + 1L
        val clockedCache = MetadataCache(
            database = database,
            json = Json { ignoreUnknownKeys = true },
            clockMs = { now },
        )
        clockedCache.writeChannel(
            channelId = "99",
            login = "channel",
            snapshot = ChannelPageCacheSnapshot(
                user = User(id = "99", login = "channel", name = "Channel", followerCount = 321),
                stream = Stream(
                    id = "stream",
                    channelId = "99",
                    title = "Still useful title",
                    viewerCount = 123,
                ),
            ),
            nowMs = 0L,
        )

        val snapshot = clockedCache.readChannel("99", "channel")
        assertEquals("Still useful title", snapshot?.stream?.title)
        assertNull(snapshot?.stream?.viewerCount)
        assertNull(snapshot?.user?.followerCount)
    }

    @Test
    fun partialFallbackWriteDoesNotRejuvenateChannelLiveState() = runBlocking {
        var nowMs = 0L
        val clockedCache = MetadataCache(
            database = database,
            json = Json { ignoreUnknownKeys = true },
            clockMs = { nowMs },
        )
        val original = ChannelPageCacheSnapshot(
            user = User(id = "99", login = "channel", name = "Old name", followerCount = 321),
            stream = Stream(
                id = "stream",
                channelId = "99",
                title = "Old title",
                viewerCount = 123,
            ),
        )
        clockedCache.writeChannel(
            channelId = "99",
            login = "channel",
            snapshot = original,
            nowMs = nowMs,
            streamValidated = true,
            followerCountValidated = true,
        )

        nowMs = MetadataCachePolicy.CURRENT_STREAM_BOOTSTRAP_MAX_AGE_MS - 1L
        clockedCache.writeChannel(
            channelId = "99",
            login = "channel",
            snapshot = ChannelPageCacheSnapshot(
                user = User(id = "99", login = "channel", name = "Fresh static name", followerCount = 321),
                stream = original.stream,
            ),
            nowMs = nowMs,
        )

        nowMs = MetadataCachePolicy.CURRENT_STREAM_BOOTSTRAP_MAX_AGE_MS + 1L
        val snapshot = clockedCache.readChannel("99", "channel")
        assertEquals("Fresh static name", snapshot?.user?.name)
        assertEquals("Old title", snapshot?.stream?.title)
        assertNull(snapshot?.stream?.viewerCount)
    }

    @Test
    fun partialAccountMutationsDoNotRejuvenateUntouchedComponents() = runBlocking {
        var nowMs = 0L
        val clockedCache = MetadataCache(
            database = database,
            json = Json { ignoreUnknownKeys = true },
            clockMs = { nowMs },
        )
        val original = AccountCacheSnapshot(
            user = HelixUser(id = "42", login = "streamer", displayName = "Streamer"),
            channel = ChannelInformation(
                broadcasterId = "42",
                title = "Old title",
                delay = 3,
                tags = listOf("old"),
            ),
            chatSettings = ChatSettings(
                broadcasterId = "42",
                followerMode = true,
                followerModeDuration = 10,
                slowMode = false,
                slowModeWaitTime = 30,
                subscriberMode = true,
                emoteMode = true,
                uniqueChatMode = true,
            ),
        )
        clockedCache.writeAccount(
            userId = "42",
            login = "streamer",
            snapshot = original,
            nowMs = nowMs,
            channelValidated = true,
            chatSettingsValidated = true,
        )

        nowMs = MetadataCachePolicy.ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS - 1L
        clockedCache.writeAccount(
            userId = "42",
            login = "streamer",
            snapshot = original.copy(
                channel = original.channel?.copy(title = "Mutated title"),
                chatSettings = original.chatSettings?.copy(slowMode = true),
            ),
            nowMs = nowMs,
            // Simulates a successful one-field PATCH while the full GETs
            // failed: copied fields retain their original validation times.
        )

        nowMs = MetadataCachePolicy.ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS + 1L
        val cached = clockedCache.readAccount("42", "streamer")
        assertNull(cached?.channel)
        assertNull(cached?.chatSettings)
    }

    @Test
    fun appendingBlockedUsersPageDoesNotRejuvenateTheOriginalPage() = runBlocking {
        var nowMs = 0L
        val clockedCache = MetadataCache(
            database = database,
            json = Json { ignoreUnknownKeys = true },
            clockMs = { nowMs },
        )
        val firstPage = AccountCacheSnapshot(
            user = HelixUser(id = "42", login = "streamer", displayName = "Streamer"),
            blockedUsers = listOf(BlockedUser(id = "1", login = "first", displayName = "First")),
            blockedUsersCursor = "page-2",
        )
        clockedCache.writeAccount(
            userId = "42",
            login = "streamer",
            snapshot = firstPage,
            nowMs = nowMs,
            blockedUsersValidated = true,
        )

        nowMs = MetadataCachePolicy.ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS - 1L
        clockedCache.writeAccount(
            userId = "42",
            login = "streamer",
            snapshot = firstPage.copy(
                blockedUsers = firstPage.blockedUsers + BlockedUser(
                    id = "2",
                    login = "second",
                    displayName = "Second",
                ),
                blockedUsersCursor = null,
            ),
            nowMs = nowMs,
            // Appending page two does not revalidate page one.
        )

        nowMs = MetadataCachePolicy.ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS + 1L
        val cached = clockedCache.readAccount("42", "streamer")
        assertTrue(cached?.blockedUsers.isNullOrEmpty())
        assertNull(cached?.blockedUsersCursor)
    }
}
