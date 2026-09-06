package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import com.github.andreyasadchy.xtra.model.chat.EmoteUsage
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmoteRecommendationEngineTest {
    private val engine = EmoteRecommendationEngine(maxResults = 20)

    @Test
    fun `usage is dominant then quality recency and stable fallback`() {
        val exact = emote("Kappa", "1")
        val prefix = emote("KappaPride", "2")
        val later = emote("Kappaa", "3")
        val catalog = engine.catalog(ChatCatalogSnapshot(1, twitch = mapOf(exact.name to exact, prefix.name to prefix, later.name to later)))
        val usage = listOf(
            usage(exact, "channel", count = 190, lastUsedAt = 1),
            usage(prefix, "channel", count = 4, lastUsedAt = 100),
            usage(later, "channel", count = 4, lastUsedAt = 50),
        )

        val result = engine.recommend("kapp", "channel", catalog, usage, EmoteUsageKeys.ANONYMOUS_VIEWER_ID)

        assertEquals(listOf("Kappa", "KappaPride", "Kappaa"), result.map { it.emote.name })
    }

    @Test
    fun `global usage follows channels while channel usage does not`() {
        val global = emote("Kappa", "global", ChatEmoteScope.GLOBAL)
        val channel = emote("KappaLocal", "channel", ChatEmoteScope.CHANNEL)
        assertEquals(
            EmoteUsageKeys.forEmote(global, "a", "viewer-a"),
            EmoteUsageKeys.forEmote(global, "b", "viewer-a"),
        )
        assertTrue(EmoteUsageKeys.forEmote(channel, "a", "viewer-a") != EmoteUsageKeys.forEmote(channel, "b", "viewer-a"))
    }

    @Test
    fun `provider and stable id are part of usage identity`() {
        val twitch = emote("Same", "same", provider = ChatAssetProvider.TWITCH)
        val bttv = emote("Same", "same", provider = ChatAssetProvider.BTTV)
        assertTrue(EmoteUsageKeys.forEmote(twitch, "channel", "viewer-a") != EmoteUsageKeys.forEmote(bttv, "channel", "viewer-a"))
    }

    @Test
    fun `equal usage uses recency then deterministic provider ordering`() {
        val twitch = emote("SameTwitch", "same-twitch", provider = ChatAssetProvider.TWITCH)
        val bttv = emote("SameBttv", "same-bttv", provider = ChatAssetProvider.BTTV)
        val catalog = engine.catalog(
            ChatCatalogSnapshot(1, twitch = mapOf(twitch.name to twitch)).copy(
                bttv = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog(
                    global = mapOf(bttv.name to bttv),
                ),
            ),
        )
        val recentFirst = engine.recommend(
            "same",
            "channel",
            catalog,
            listOf(usage(twitch, "channel", 1, 10), usage(bttv, "channel", 1, 20)),
            EmoteUsageKeys.ANONYMOUS_VIEWER_ID,
        )
        assertEquals("SameBttv", recentFirst.first().emote.name)
        assertEquals(ChatAssetProvider.BTTV, recentFirst.first().emote.provider)

        val stable = engine.recommend(
            "same",
            "channel",
            catalog,
            listOf(usage(twitch, "channel", 1, 20), usage(bttv, "channel", 1, 20)),
            EmoteUsageKeys.ANONYMOUS_VIEWER_ID,
        )
        assertEquals(ChatAssetProvider.BTTV, stable.first().emote.provider)
    }

    @Test
    fun `usage recorder counts only exact sendable emotes and occurrences`() {
        val kappa = emote("Kappa", "1")
        val snapshot = ChatCatalogSnapshot(1, twitch = mapOf(kappa.name to kappa))
        val increments = engine.usageInMessage(snapshot, "Kappa Kappa plain", "channel", 10, EmoteUsageKeys.ANONYMOUS_VIEWER_ID)

        assertEquals(1, increments.size)
        assertEquals(2L, increments.single().count)
        assertNull(engine.resolveSentToken(snapshot, "plain"))
    }

    @Test
    fun `other chatters personal sets are not recommendation or send candidates`() {
        val viewer = emote("ViewerOnly", "viewer", ChatEmoteScope.PERSONAL, ChatAssetProvider.SEVEN_TV)
        val chatter = emote("ChatterOnly", "chatter", ChatEmoteScope.PERSONAL, ChatAssetProvider.SEVEN_TV)
        val snapshot = ChatCatalogSnapshot(
            revision = 1,
            sevenTv = ScopedEmoteCatalog(
                personal = mapOf(
                    "viewer-set" to mapOf(viewer.name to viewer),
                    "chatter-set" to mapOf(chatter.name to chatter),
                ),
                viewerPersonalSetIds = setOf("viewer-set"),
            ),
        )

        val catalog = engine.catalog(snapshot)
        assertEquals(listOf("ViewerOnly"), catalog.emotes.map { it.name })
        assertEquals(viewer, engine.resolveSentToken(snapshot, "ViewerOnly"))
        assertNull(engine.resolveSentToken(snapshot, "ChatterOnly"))
        assertEquals(listOf(viewer.id), engine.usageInMessage(snapshot, "ViewerOnly ChatterOnly", "channel", 10, EmoteUsageKeys.ANONYMOUS_VIEWER_ID).map { it.emoteId })
    }

    @Test
    fun `same name has one recommendation and usage follows send resolution`() {
        val twitch = emote("Same", "twitch-same", provider = ChatAssetProvider.TWITCH)
        val bttv = emote("Same", "bttv-same", provider = ChatAssetProvider.BTTV)
        val snapshot = ChatCatalogSnapshot(
            revision = 1,
            twitch = mapOf(twitch.name to twitch),
            bttv = ScopedEmoteCatalog(global = mapOf(bttv.name to bttv)),
        )

        val catalog = engine.catalog(snapshot)
        assertEquals(listOf(twitch), catalog.emotes)
        assertEquals(twitch, engine.resolveSentToken(snapshot, "Same"))
        assertEquals(listOf(twitch.id), engine.usageInMessage(snapshot, "Same", "channel", 10, EmoteUsageKeys.ANONYMOUS_VIEWER_ID).map { it.emoteId })
    }

    @Test
    fun `channel name collision wins within one provider projection`() {
        val global = emote("Same", "global", ChatEmoteScope.GLOBAL, ChatAssetProvider.SEVEN_TV)
        val channel = emote("Same", "channel", ChatEmoteScope.CHANNEL, ChatAssetProvider.SEVEN_TV)
        val snapshot = ChatCatalogSnapshot(
            revision = 1,
            sevenTv = ScopedEmoteCatalog(
                global = mapOf(global.name to global),
                channel = mapOf(channel.name to channel),
            ),
        )

        assertEquals(listOf(channel), snapshot.sevenTv.sendableValues().toList())
        assertEquals(listOf(channel), engine.catalog(snapshot).emotes)
        assertEquals(channel, engine.resolveSentToken(snapshot, "Same"))
        assertEquals(listOf(channel.id), engine.usageInMessage(snapshot, "Same", "channel", 10, EmoteUsageKeys.ANONYMOUS_VIEWER_ID).map { it.emoteId })
    }

    @Test
    fun `usage identity is viewer scoped and switching back restores the original history`() {
        val emote = emote("Kappa", "kappa")
        val aKey = EmoteUsageKeys.forEmote(emote, "channel", viewerId = "viewer-a")
        val bKey = EmoteUsageKeys.forEmote(emote, "channel", viewerId = "viewer-b")
        assertTrue(aKey != bKey)

        val catalog = engine.catalog(ChatCatalogSnapshot(1, twitch = mapOf(emote.name to emote)))
        val aUsage = listOf(usage(emote, "channel", 7, 10, viewerId = "viewer-a"))
        val bUsage = listOf(usage(emote, "channel", 2, 20, viewerId = "viewer-b"))

        assertEquals(7L, engine.recommend("kap", "channel", catalog, aUsage, "viewer-a").single().useCount)
        assertEquals(2L, engine.recommend("kap", "channel", catalog, bUsage, "viewer-b").single().useCount)
        assertEquals(7L, engine.recommend("kap", "channel", catalog, aUsage, "viewer-a").single().useCount)
        assertEquals("viewer-a", engine.usageInMessage(
            ChatCatalogSnapshot(1, twitch = mapOf(emote.name to emote)),
            "Kappa",
            "channel",
            30,
            viewerId = "viewer-a",
        ).single().viewerId)
    }

    @Test
    fun `recommendation state carries its viewer namespace through ranking`() {
        val kappa = emote("Kappa", "kappa")
        val catalog = engine.catalog(ChatCatalogSnapshot(1, twitch = mapOf(kappa.name to kappa)))
        val stateA = EmoteRecommendationState(
            viewerId = "viewer-a",
            catalog = catalog,
            usage = listOf(usage(kappa, "channel", 9, 100, viewerId = "viewer-a")),
        )
        val stateB = EmoteRecommendationState(
            viewerId = "viewer-b",
            catalog = catalog,
            usage = emptyList(),
        )

        fun rank(state: EmoteRecommendationState) = engine.recommend(
            query = "kap",
            channelId = "channel",
            catalog = state.catalog,
            usage = state.usage,
            viewerId = state.viewerId,
        )

        assertEquals(9L, rank(stateA).single().useCount)
        assertEquals(0L, rank(stateB).single().useCount)
        assertEquals(9L, rank(stateA).single().useCount)
    }

    @Test
    fun `global and channel usage remain scoped correctly within one viewer`() {
        val global = emote("Global", "global", ChatEmoteScope.GLOBAL)
        val channel = emote("Channel", "channel", ChatEmoteScope.CHANNEL)
        assertEquals(
            EmoteUsageKeys.forEmote(global, "a", "viewer-a"),
            EmoteUsageKeys.forEmote(global, "b", "viewer-a"),
        )
        assertTrue(
            EmoteUsageKeys.forEmote(channel, "a", "viewer-a") !=
                    EmoteUsageKeys.forEmote(channel, "b", "viewer-a"),
        )
    }

    private fun emote(
        name: String,
        id: String,
        scope: ChatEmoteScope = ChatEmoteScope.GLOBAL,
        provider: ChatAssetProvider = ChatAssetProvider.TWITCH,
    ) = ChatCatalogEmote(
        name = name,
        id = id,
        asset = ChatAssetSpec(ChatAssetKey("https://example.com/$id"), 56, 56, 28),
        provider = provider,
        animated = false,
        scope = scope,
    )

    private fun usage(
        emote: ChatCatalogEmote,
        channelId: String,
        count: Long,
        lastUsedAt: Long,
        viewerId: String = EmoteUsageKeys.ANONYMOUS_VIEWER_ID,
    ) = EmoteUsage(
        viewerId = viewerId,
        usageKey = EmoteUsageKeys.forEmote(emote, channelId, viewerId),
        provider = emote.provider.name,
        emoteId = emote.id,
        scope = emote.scope.name,
        channelId = EmoteUsageKeys.channelIdFor(emote.scope, channelId),
        useCount = count,
        lastUsedAt = lastUsedAt,
    )
}
