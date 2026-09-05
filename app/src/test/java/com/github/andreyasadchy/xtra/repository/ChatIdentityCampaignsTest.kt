package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadgeKey
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaign
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatIdentityCampaignsTest {
    private val now = Instant.parse("2026-09-05T00:00:00Z")

    @Test
    fun `display badge parser retains every badge and description`() {
        val body = Json.parseToJsonElement(
            """
            {"channel":{"self":{"displayBadges":[
              {"setID":"subscriber","version":"1","title":"Subscriber","description":"sub","imageURL":"sub.png"},
              {"setID":"vanity","version":"pikachu","title":"Pikachu","description":"vanity","imageURL":"pika.png"}
            ]}}}
            """.trimIndent(),
        ).jsonObject

        val badges = ChatIdentityCampaignParser.parseDisplayBadges(body)

        assertEquals(listOf("subscriber", "vanity"), badges.map { it.key.setId })
        assertEquals("vanity", badges[1].description)
    }

    @Test
    fun `duplicate reward mechanics become one four badge collection with one owned`() {
        val campaigns = parse(owned = setOf(ChatIdentityBadgeKey("first-partners", "pichu")))

        assertEquals(1, campaigns.size)
        assertEquals("First Partners Collection", campaigns.single().title)
        assertEquals("Pokémon", campaigns.single().subtitle)
        assertEquals(1, campaigns.single().earnedBadges)
        assertEquals(4, campaigns.single().totalBadges)
        assertEquals(2, campaigns.single().rewardCampaignIds.size)
        assertEquals(2, campaigns.single().rewards.size)
    }

    @Test
    fun `collection progress supports zero and complete ownership`() {
        assertEquals(0, parse(emptySet()).single().earnedBadges)
        assertEquals(
            4,
            parse(
                setOf("pichu", "bulbasaur", "charmander", "squirtle")
                    .map { ChatIdentityBadgeKey("first-partners", it) }
                    .toSet(),
            ).single().earnedBadges,
        )
    }

    @Test
    fun `no active campaigns produces no chat identity cards`() {
        val expired = fixture("chat_identity_campaigns.json")
            .replace("\"endsAt\": \"2026-12-31T23:59:59Z\"", "\"endsAt\": \"2026-01-02T00:00:00Z\"")
        val result = ChatIdentityCampaignParser.parse(
            rewardCampaignsBody = expired,
            badgeCatalogBody = fixture("chat_identity_badges.json"),
            ownedBadges = emptySet(),
            now = now,
        )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `badge catalog is only needed after an active dashboard campaign is found`() {
        assertTrue(
            ChatIdentityCampaignParser.hasUsableActiveRewardCampaigns(
                body = fixture("chat_identity_campaigns.json"),
                now = now,
            ),
        )
        assertFalse(
            ChatIdentityCampaignParser.hasUsableActiveRewardCampaigns(
                body = fixture("chat_identity_campaigns.json")
                    .replace("\"endsAt\": \"2026-12-31T23:59:59Z\"", "\"endsAt\": \"2026-01-02T00:00:00Z\""),
                now = now,
            ),
        )
        assertFalse(
            ChatIdentityCampaignParser.hasUsableActiveRewardCampaigns(
                body = "{\"data\":{}}",
                now = now,
            ),
        )
    }

    @Test
    fun `campaign schema failure is isolated`() {
        assertNull(
            ChatIdentityCampaignParser.parse(
                rewardCampaignsBody = "{\"data\":{}}",
                badgeCatalogBody = fixture("chat_identity_badges.json"),
                ownedBadges = emptySet(),
                now = now,
            ),
        )
    }

    @Test
    fun `delayed campaign loading does not change the already available base identity`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<ChatIdentityCampaign>>()
        val repository = ChatIdentityCampaignRepository.forTesting(
            networkLoader = { _, _, _ ->
                started.complete(Unit)
                release.await()
            },
        )
        val baseState = ChatIdentityState(
            displayName = "viewer",
            displayBadges = listOf(
                ChatIdentityBadge(
                    key = ChatIdentityBadgeKey("subscriber", "1"),
                    title = "Subscriber",
                    description = null,
                    imageUrl = "subscriber.png",
                ),
            ),
        )
        val load = async {
            repository.load(
                viewerId = "viewer",
                networkLibrary = null,
                headers = emptyMap(),
                ownedBadges = emptyList(),
            )
        }

        started.await()
        assertEquals("viewer", baseState.displayName)
        assertEquals("subscriber", baseState.displayBadges.single().key.setId)
        assertFalse(load.isCompleted)

        release.complete(emptyList())
        assertEquals(emptyList<ChatIdentityCampaign>(), load.await())
    }

    @Test
    fun `campaign loader failure is cached as an empty optional result`() = runBlocking {
        var calls = 0
        val repository = ChatIdentityCampaignRepository.forTesting(
            networkLoader = { _, _, _ ->
                calls += 1
                error("campaign endpoint unavailable")
            },
        )

        assertEquals(
            emptyList<ChatIdentityCampaign>(),
            repository.load("viewer", null, emptyMap(), emptyList()),
        )
        assertEquals(
            emptyList<ChatIdentityCampaign>(),
            repository.load("viewer", null, emptyMap(), emptyList()),
        )
        assertEquals(1, calls)
    }

    private fun parse(owned: Set<ChatIdentityBadgeKey>): List<com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaign> =
        ChatIdentityCampaignParser.parse(
            rewardCampaignsBody = fixture("chat_identity_campaigns.json"),
            badgeCatalogBody = fixture("chat_identity_badges.json"),
            ownedBadges = owned,
            now = now,
        )!!

    private fun fixture(name: String): String =
        javaClass.getResource("/twitch_gql/$name")!!.readText()
}
