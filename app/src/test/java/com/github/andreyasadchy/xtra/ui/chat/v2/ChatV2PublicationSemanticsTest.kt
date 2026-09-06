package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogState
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.isReadyForChatPublication
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.countNewLiveMessages
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.isReadyForChatPublication as isPresentationReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatV2PublicationSemanticsTest {
    @Test
    fun badgeSettlementGatesOnlyBadgeEnabledPublication() {
        val unsettled = ChatCatalogState(
            ChatCatalogSnapshot(0),
            hydrated = true,
            badgesSettled = false,
            structuralCatalogSettled = false,
        )
        val structural = unsettled.copy(structuralCatalogSettled = true)
        val settled = structural.copy(badgesSettled = true)

        assertEquals(false, unsettled.isReadyForChatPublication(showBadges = true))
        assertEquals(false, structural.isReadyForChatPublication(showBadges = true))
        assertEquals(true, settled.isReadyForChatPublication(showBadges = true))
        assertEquals(false, unsettled.isReadyForChatPublication(showBadges = false))
        assertEquals(true, structural.isReadyForChatPublication(showBadges = false))
    }

    @Test
    fun rewardMessagePublishesBeforeInitialRewardMetadata() {
        val state = ChatCatalogState(
            snapshot = ChatCatalogSnapshot(0),
            hydrated = true,
            badgesSettled = true,
            structuralCatalogSettled = true,
        )
        val rewardMessage = message("reward", 1L).copy(rewardId = "reward-id")
        val ordinaryMessage = message("ordinary", 2L)

        assertEquals(
            true,
            isPresentationReady(state, showBadges = true, messages = listOf(rewardMessage), rewardCatalogSettled = false),
        )
        assertEquals(
            true,
            isPresentationReady(state, showBadges = true, messages = listOf(rewardMessage), rewardCatalogSettled = true),
        )
        assertEquals(
            true,
            isPresentationReady(state, showBadges = true, messages = listOf(ordinaryMessage), rewardCatalogSettled = false),
        )

        val resolvedCatalog = state.snapshot.copy(
            channelPointRewards = mapOf(
                "reward-id" to ChatReward("Super Reward", cost = 1_000, imageUrl = "https://cdn.example/reward.png"),
            ),
        )
        val resolvedRow = ChatRowCompiler().compile(rewardMessage, resolvedCatalog)
        assertTrue(resolvedRow.pieces.any { it is ChatPiece.RewardIcon })
    }

    @Test
    fun failedInitialRewardLoadDoesNotBlockForever() {
        val state = ChatCatalogState(
            snapshot = ChatCatalogSnapshot(0),
            hydrated = true,
            badgesSettled = true,
            structuralCatalogSettled = true,
        )
        val rewardMessage = message("reward", 1L).copy(rewardId = "missing-reward")

        assertEquals(
            true,
            isPresentationReady(state, showBadges = true, messages = listOf(rewardMessage), rewardCatalogSettled = true),
        )
        val row = ChatRowCompiler().compile(rewardMessage, ChatCatalogSnapshot(0))
        assertTrue(row.pieces.none { it is ChatPiece.RewardIcon })
        assertTrue(row.pieces.any { it is ChatPiece.Text })
    }

    @Test
    fun recoveredOlderHistoryDoesNotCountAsNewLiveMessages() {
        val visible = (100..200).map(::message)
        val reconciled = (50..200).map(::message)

        assertEquals(
            0,
            countNewLiveMessages(
                previousIds = visible.map { it.id }.toSet(),
                previousTailId = visible.last().id,
                messages = reconciled,
            ),
        )
    }

    @Test
    fun messageAfterPreviousTailCountsAsNew() {
        val visible = (100..200).map(::message)
        val appended = (100..201).map(::message)

        assertEquals(
            1,
            countNewLiveMessages(
                previousIds = visible.map { it.id }.toSet(),
                previousTailId = visible.last().id,
                messages = appended,
            ),
        )
    }

    @Test
    fun messageWithSameTimestampAfterPreviousTailCountsAsNew() {
        val previous = message("A", 1_000L)
        val current = listOf(previous, message("B", 1_000L))

        assertEquals(
            1,
            countNewLiveMessages(
                previousIds = setOf(previous.id),
                previousTailId = previous.id,
                messages = current,
            ),
        )
    }

    @Test
    fun recoveredBadgesDoNotRetrofitAlreadyPublishedMessages() {
        val session = ChatSessionKey("channel", 1L)
        val badge = ChatBadgeRef("subscriber", "1")
        val first = message("first", 1L, badge)
        val second = message("second", 2L, badge)
        val compiler = ChatRowCompiler()
        val presentation = ChatPresentationSnapshot()
        val failedCatalog = ChatCatalogSnapshot(revision = 1)
        val recoveredCatalog = failedCatalog.copy(
            revision = 2,
            badges = mapOf(
                badge.catalogKey to ChatCatalogBadge(
                    name = "subscriber",
                    asset = ChatAssetSpec(ChatAssetKey("https://cdn.example/subscriber.png"), 18, 18, 18),
                    provider = ChatAssetProvider.TWITCH,
                    setId = badge.setId,
                    versionId = badge.versionId,
                ),
            ),
        )

        val firstRow = compiler.compile(first, presentation.catalogsFor(session, listOf(first), failedCatalog).single())
        val existingRowAfterRecovery = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), recoveredCatalog).single(),
        )
        val newRowAfterRecovery = compiler.compile(
            second,
            presentation.catalogsFor(session, listOf(first, second), recoveredCatalog).last(),
        )

        assertTrue(firstRow.pieces.none { it is ChatPiece.Badge })
        assertEquals(firstRow, existingRowAfterRecovery)
        assertTrue(newRowAfterRecovery.pieces.any { it is ChatPiece.Badge })
    }

    @Test
    fun thirdPartyCatalogRecoveryDoesNotRetrofitExistingMessage() {
        val session = ChatSessionKey("channel", 2L)
        val first = message("first", 1L).copy(segments = listOf(ChatSegment.Text("OMEGALUL")))
        val second = message("second", 2L).copy(segments = listOf(ChatSegment.Text("OMEGALUL")))
        val emote = ChatCatalogEmote(
            name = "OMEGALUL",
            asset = ChatAssetSpec(ChatAssetKey("https://cdn.example/omegalul.png"), 28, 28, 28),
            provider = ChatAssetProvider.SEVEN_TV,
            animated = false,
        )
        val emptyCatalog = ChatCatalogSnapshot(revision = 1)
        val recoveredCatalog = emptyCatalog.copy(
            revision = 2,
            sevenTv = ScopedEmoteCatalog(global = mapOf(emote.name to emote)),
        )
        val compiler = ChatRowCompiler()
        val presentation = ChatPresentationSnapshot()

        val firstRow = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), emptyCatalog).single(),
        )
        val existingRowAfterRecovery = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), recoveredCatalog).single(),
        )
        val newRowAfterRecovery = compiler.compile(
            second,
            presentation.catalogsFor(session, listOf(first, second), recoveredCatalog).last(),
        )

        assertTrue(firstRow.pieces.none { it is ChatPiece.Emote })
        assertEquals(firstRow, existingRowAfterRecovery)
        assertTrue(newRowAfterRecovery.pieces.any { it is ChatPiece.Emote })
    }

    @Test
    fun lateSevenTvBadgeDoesNotRetrofitExistingMessage() {
        val session = ChatSessionKey("channel", 3L)
        val user = ChatUser("user", "user", "User", null)
        val first = message("first", 1L).copy(user = user)
        val second = message("second", 2L).copy(user = user)
        val badge = ChatCatalogBadge(
            name = "late-badge",
            asset = ChatAssetSpec(ChatAssetKey("https://cdn.example/late-badge.png"), 18, 18, 18),
            provider = ChatAssetProvider.SEVEN_TV,
            setId = "late-badge",
            versionId = "1",
        )
        val emptyCatalog = ChatCatalogSnapshot(revision = 1)
        val recoveredCatalog = emptyCatalog.copy(
            revision = 2,
            userDecorations = mapOf(user.id!! to ChatUserDecoration(badgeId = "late-badge")),
            sevenTvBadges = mapOf("late-badge" to badge),
        )
        val compiler = ChatRowCompiler()
        val presentation = ChatPresentationSnapshot()

        val firstRow = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), emptyCatalog).single(),
        )
        val existingRowAfterRecovery = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), recoveredCatalog).single(),
        )
        val newRowAfterRecovery = compiler.compile(
            second,
            presentation.catalogsFor(session, listOf(first, second), recoveredCatalog).last(),
        )

        assertTrue(firstRow.pieces.none { it is ChatPiece.Badge })
        assertEquals(firstRow, existingRowAfterRecovery)
        assertTrue(newRowAfterRecovery.pieces.any { it is ChatPiece.Badge })
    }

    @Test
    fun lateNamePaintDoesNotRetrofitExistingMessage() {
        val session = ChatSessionKey("channel", 4L)
        val user = ChatUser("user", "user", "User", null)
        val first = message("first", 1L).copy(user = user)
        val second = message("second", 2L).copy(user = user)
        val paint = ChatNamePaint(colors = listOf(0xffff00ff.toInt(), 0xff9146ff.toInt()))
        val emptyCatalog = ChatCatalogSnapshot(revision = 1)
        val recoveredCatalog = emptyCatalog.copy(
            revision = 2,
            userDecorations = mapOf(user.id!! to ChatUserDecoration(paintId = "late-paint")),
            namePaints = mapOf("late-paint" to paint),
        )
        val compiler = ChatRowCompiler()
        val presentation = ChatPresentationSnapshot()

        val firstUsername = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), emptyCatalog).single(),
        ).pieces.filterIsInstance<ChatPiece.Username>().single()
        val existingUsernameAfterRecovery = compiler.compile(
            first,
            presentation.catalogsFor(session, listOf(first), recoveredCatalog).single(),
        ).pieces.filterIsInstance<ChatPiece.Username>().single()
        val newUsernameAfterRecovery = compiler.compile(
            second,
            presentation.catalogsFor(session, listOf(first, second), recoveredCatalog).last(),
        ).pieces.filterIsInstance<ChatPiece.Username>().single()

        assertEquals(null, firstUsername.paint)
        assertEquals(firstUsername, existingUsernameAfterRecovery)
        assertEquals(paint, newUsernameAfterRecovery.paint)
    }

    @Test
    fun badgesDisabledAtColdStartCanBeEnabledLater() {
        val session = ChatSessionKey("channel", 5L)
        val badge = ChatBadgeRef("subscriber", "1")
        val message = message("first", 1L, badge)
        val emptyCatalog = ChatCatalogSnapshot(revision = 1)
        val recoveredCatalog = emptyCatalog.copy(
            revision = 2,
            badges = mapOf(
                badge.catalogKey to ChatCatalogBadge(
                    name = "subscriber",
                    asset = ChatAssetSpec(ChatAssetKey("https://cdn.example/subscriber.png"), 18, 18, 18),
                    provider = ChatAssetProvider.TWITCH,
                    setId = badge.setId,
                    versionId = badge.versionId,
                ),
            ),
        )
        val presentation = ChatPresentationSnapshot()

        val disabledRow = ChatRowCompiler(showBadges = false).compile(
            message,
            presentation.catalogsFor(session, listOf(message), emptyCatalog, captureBadges = false).single(),
        )
        val enabledRow = ChatRowCompiler(showBadges = true).compile(
            message,
            presentation.catalogsFor(session, listOf(message), recoveredCatalog, captureBadges = true).single(),
        )

        assertTrue(disabledRow.pieces.none { it is ChatPiece.Badge })
        assertTrue(enabledRow.pieces.any { it is ChatPiece.Badge })
    }

    private fun message(index: Int) = ChatMessage(
        id = ChatMessageId(index.toString()),
        channelId = "channel",
        timestampMs = index.toLong(),
        user = null,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )

    private fun message(id: String, timestampMs: Long) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = timestampMs,
        user = null,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )

    private fun message(id: String, timestampMs: Long, badge: ChatBadgeRef) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = timestampMs,
        user = ChatUser("user-$id", id, id, null),
        badges = listOf(badge),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )
}
