package com.github.andreyasadchy.xtra.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.EmoteUsageKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmoteUsageMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "emote-usage-migration.db"
    private var database: AppDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationAndConcurrentIncrementWorkOnDeviceSqlite() = runBlocking {
        val seed = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val seedSqlite = seed.openHelper.writableDatabase
        seedSqlite.execSQL("DROP INDEX IF EXISTS index_emote_usage_viewer_id_channel_id")
        seedSqlite.execSQL("DROP INDEX IF EXISTS index_emote_usage_viewer_id_provider_emote_id")
        seedSqlite.execSQL("DROP TABLE IF EXISTS emote_usage")
        seedSqlite.execSQL("PRAGMA user_version = 51")
        seed.close()

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(EmoteUsageMigrations.FROM_51)
            .build()
        val db = checkNotNull(database)
        val dao = db.emoteUsage()
        coroutineScope {
            (0 until 8).map {
                async(Dispatchers.IO) {
                    repeat(25) {
                        dao.increment(
                            viewerId = "viewer-a",
                            usageKey = usageKey("viewer-a", "kappa", ChatEmoteScope.GLOBAL, "channel"),
                            provider = "TWITCH",
                            emoteId = "kappa",
                            scope = "GLOBAL",
                            channelId = null,
                            count = 1,
                            lastUsedAt = it.toLong(),
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            200L,
            dao.getForChannel("viewer-a", "channel").single().useCount,
        )

        dao.increment(
            viewerId = "viewer-a",
            usageKey = usageKey("viewer-a", "monotonic", ChatEmoteScope.GLOBAL, "channel"),
            provider = "TWITCH",
            emoteId = "monotonic",
            scope = "GLOBAL",
            channelId = null,
            count = 1,
            lastUsedAt = 200,
        )
        dao.increment(
            viewerId = "viewer-a",
            usageKey = usageKey("viewer-a", "monotonic", ChatEmoteScope.GLOBAL, "channel"),
            provider = "TWITCH",
            emoteId = "monotonic",
            scope = "GLOBAL",
            channelId = null,
            count = 1,
            lastUsedAt = 100,
        )

        val monotonic = dao.getForChannel("viewer-a", "channel").single { it.emoteId == "monotonic" }
        assertEquals(2L, monotonic.useCount)
        assertEquals(200L, monotonic.lastUsedAt)
        dao.increment(
            viewerId = "viewer-b",
            usageKey = usageKey("viewer-b", "kappa", ChatEmoteScope.GLOBAL, "channel"),
            provider = "TWITCH",
            emoteId = "kappa",
            scope = "GLOBAL",
            channelId = null,
            count = 3,
            lastUsedAt = 300,
        )
        assertTrue(dao.getForChannel("viewer-b", "channel").single().useCount == 3L)
        assertEquals(200L, dao.getForChannel("viewer-a", "channel").single { it.emoteId == "monotonic" }.lastUsedAt)

        dao.increment(
            viewerId = "viewer-a",
            usageKey = usageKey("viewer-a", "follower", ChatEmoteScope.CHANNEL, "channel-a"),
            provider = "TWITCH",
            emoteId = "follower",
            scope = "CHANNEL",
            channelId = "channel-a",
            count = 4,
            lastUsedAt = 400,
        )
        assertEquals(4L, dao.getForChannel("viewer-a", "channel-a").single { it.emoteId == "follower" }.useCount)
        assertTrue(dao.getForChannel("viewer-a", "channel-b").none { it.emoteId == "follower" })
        assertTrue(dao.getForChannel("viewer-b", "channel-a").none { it.emoteId == "follower" })

        // A delayed write from viewer A must stay in A's namespace after viewer B has become active.
        dao.increment(
            viewerId = "viewer-a",
            usageKey = usageKey("viewer-a", "kappa", ChatEmoteScope.GLOBAL, "channel"),
            provider = "TWITCH",
            emoteId = "kappa",
            scope = "GLOBAL",
            channelId = null,
            count = 1,
            lastUsedAt = 500,
        )
        assertEquals(3L, dao.getForChannel("viewer-b", "channel").single().useCount)
        assertEquals(201L, dao.getForChannel("viewer-a", "channel").single { it.emoteId == "kappa" }.useCount)
        assertTrue(tableExists(db.openHelper.readableDatabase, "emote_usage"))
        assertTrue(indexExists(db.openHelper.readableDatabase, "index_emote_usage_viewer_id_channel_id"))
        assertTrue(indexExists(db.openHelper.readableDatabase, "index_emote_usage_viewer_id_provider_emote_id"))
    }

    private fun usageKey(viewerId: String, emoteId: String, scope: ChatEmoteScope, channelId: String): String =
        EmoteUsageKeys.forEmote(
            ChatCatalogEmote(
                name = emoteId,
                id = emoteId,
                asset = ChatAssetSpec(ChatAssetKey("https://example.com/$emoteId"), 56, 56, 28),
                provider = ChatAssetProvider.TWITCH,
                animated = false,
                scope = scope,
            ),
            channelId = channelId,
            viewerId = viewerId,
        )

    private fun tableExists(database: androidx.sqlite.db.SupportSQLiteDatabase, name: String): Boolean =
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).use { it.moveToFirst() }

    private fun indexExists(database: androidx.sqlite.db.SupportSQLiteDatabase, name: String): Boolean =
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name),
        ).use { it.moveToFirst() }
}
