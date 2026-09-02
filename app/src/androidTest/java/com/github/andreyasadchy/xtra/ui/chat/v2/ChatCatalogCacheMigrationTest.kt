package com.github.andreyasadchy.xtra.ui.chat.v2

import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.TwitchChatCatalogCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChatCatalogCacheMigrationTest {
    @Test
    fun schemaV1EmotesUseTheirNameAsTheLegacyId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val channelId = "schema-v1-fixture"
        val file = File(
            File(context.filesDir, "chat-v2/catalog"),
            "$channelId.json",
        )
        file.parentFile?.mkdirs()
        file.writeText(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("chat_catalog_schema_v1.json")
                .bufferedReader()
                .use { it.readText() },
        )
        try {
            val snapshot = TwitchChatCatalogCache(context, channelId).read()
            assertEquals("PartyParrot", snapshot?.sevenTv?.get("PartyParrot")?.id)
        } finally {
            file.delete()
        }
    }
}
