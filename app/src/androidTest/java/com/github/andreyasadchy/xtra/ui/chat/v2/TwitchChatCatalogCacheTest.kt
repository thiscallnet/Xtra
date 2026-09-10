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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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

    @Test
    fun oldNativeEmoteOverlayDimensionsRemainUpgradeable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val channelId = "native-overlay-schema-regression"
        val cache = TwitchChatCatalogCache(context, channelId)
        val file = File(File(context.filesDir, "chat-v2/catalog"), "$channelId.json")
        val nativeAsset = JSONObject()
            .put("key", "native")
                    .put("sourceWidth", 56)
                    .put("sourceHeight", 56)
                    .put("targetHeight", 28)
                    .put("dimensionsAreAuthoritative", true)
                    .put("overlays", JSONArray().put(
                        JSONObject()
                            .put("key", "native-overlay")
                            .put("sourceWidth", 56)
                            .put("sourceHeight", 56)
                            .put("targetHeight", 28)
                            .put("dimensionsAreAuthoritative", true),
                    ))
        val snapshot = JSONObject()
            .put("schemaVersion", 7)
            .put("revision", 1)
            .put("twitch", JSONArray().put(
                JSONObject()
                    .put("name", "Native")
                    .put("id", "native")
                    .put("provider", ChatAssetProvider.TWITCH.name)
                    .put("asset", nativeAsset),
            ))

        try {
            file.parentFile?.mkdirs()
            file.writeText(snapshot.toString())
            val restored = checkNotNull(cache.read())
            val asset = checkNotNull(restored.twitch["Native"]?.asset)
            assertFalse(asset.dimensionsAreAuthoritative)
            assertFalse(asset.overlays.single().dimensionsAreAuthoritative)
        } finally {
            file.delete()
        }
    }

    @Test
    fun oldThirdPartyEmoteDimensionsRemainUpgradeable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val channelId = "third-party-schema-regression"
        val cache = TwitchChatCatalogCache(context, channelId)
        val file = File(File(context.filesDir, "chat-v2/catalog"), "$channelId.json")
        val snapshot = JSONObject()
            .put("schemaVersion", 7)
            .put("revision", 1)
            .put("sevenTv", JSONObject().put(
                "global", JSONArray().put(
                    JSONObject()
                        .put("name", "ThirdParty")
                        .put("id", "third-party")
                        .put("provider", ChatAssetProvider.SEVEN_TV.name)
                        .put("asset", JSONObject()
                            .put("key", "third-party")
                            .put("sourceWidth", 56)
                            .put("sourceHeight", 56)
                            .put("targetHeight", 28)
                            .put("dimensionsAreAuthoritative", true)),
                ),
            ))

        try {
            file.parentFile?.mkdirs()
            file.writeText(snapshot.toString())
            val restored = checkNotNull(cache.read())
            assertFalse(checkNotNull(restored.sevenTv.global["ThirdParty"]?.asset).dimensionsAreAuthoritative)
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
