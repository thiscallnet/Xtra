package com.github.andreyasadchy.xtra.ui.chat.v2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.TwitchChatCatalogCache
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TwitchChatCatalogCacheTest {
    @Test
    fun cacheRoundTripPreservesShadowedProviderScopes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val channelId = "scope-cache-regression"
        val cache = TwitchChatCatalogCache(context, channelId)
        val file = File(File(context.filesDir, "chat-v2/catalog"), "$channelId.json")
        val global = emote("global", ChatEmoteScope.GLOBAL).copy(name = "same")
        val channel = emote("channel", ChatEmoteScope.CHANNEL).copy(name = "same")
        val snapshot = ChatCatalogSnapshot(
            revision = 4,
            sevenTv = ScopedEmoteCatalog(
                global = mapOf("same" to global),
                channel = mapOf("same" to channel),
            ),
        )

        try {
            cache.write(snapshot)
            val restored = checkNotNull(cache.read())
            assertEquals(global, restored.sevenTv.global["same"])
            assertEquals(channel, restored.sevenTv.channel["same"])
            assertEquals(channel, restored.sevenTv["same"])
        } finally {
            file.delete()
        }
    }

    private fun emote(name: String, scope: ChatEmoteScope) = ChatCatalogEmote(
        name = name,
        asset = ChatAssetSpec(ChatAssetKey(name), 28, 28, 28),
        provider = ChatAssetProvider.SEVEN_TV,
        animated = false,
        id = name,
        scope = scope,
    )
}
