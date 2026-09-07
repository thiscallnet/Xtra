package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogCheermote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey

internal data class ChatMetadataSettlement(
    val structuralSettled: Boolean,
    val badgesSettled: Boolean,
    val rewardsSettled: Boolean,
)

/**
 * Keeps settled catalog inputs stable for every message already published in a session.
 *
 * A message published before all metadata is available remains component-wise provisional. Each
 * settled component may upgrade the existing row once; later retries and live decoration updates
 * do not insert a new span into an already settled row.
 */
internal class ChatPresentationSnapshot {
    private var sessionKey: ChatSessionKey? = null
    private val catalogsByMessage = HashMap<ChatMessageId, FrozenCatalog>()
    private val activeMessageIds = HashSet<ChatMessageId>()

    @Synchronized
    fun catalogsFor(
        key: ChatSessionKey,
        messages: List<ChatMessage>,
        catalog: ChatCatalogSnapshot,
        captureBadges: Boolean = true,
        structuralSettled: Boolean = true,
        badgesSettled: Boolean = true,
        rewardsSettled: Boolean = true,
        forceUpgrade: Boolean = false,
    ): List<ChatCatalogSnapshot> {
        if (sessionKey != key) {
            sessionKey = key
            catalogsByMessage.clear()
        }
        activeMessageIds.clear()
        messages.forEach { activeMessageIds += it.id }
        catalogsByMessage.keys.retainAll(activeMessageIds)
        val settlement = ChatMetadataSettlement(structuralSettled, badgesSettled, rewardsSettled)
        return messages.map { message ->
            val frozen = catalogsByMessage[message.id]
            if (frozen == null) {
                val created = FrozenCatalog.from(
                    catalog = catalog,
                    captureBadges = captureBadges,
                    settlement = settlement,
                )
                catalogsByMessage[message.id] = created
                created.toCatalog(catalog)
            } else {
                frozen.update(
                    catalog = catalog,
                    captureBadges = captureBadges,
                    settlement = settlement,
                    forceUpgrade = forceUpgrade,
                )
                frozen.toCatalog(catalog)
            }
        }
    }

    @Synchronized
    fun clear() {
        sessionKey = null
        catalogsByMessage.clear()
    }

    private class FrozenCatalog(
        private var twitch: Map<String, ChatCatalogEmote>,
        private var sevenTv: ScopedEmoteCatalog,
        private var sevenTvChannelSetId: String?,
        private var bttv: ScopedEmoteCatalog,
        private var ffz: ScopedEmoteCatalog,
        private var badges: Map<String, ChatCatalogBadge>?,
        private var cheermotes: Map<String, ChatCatalogCheermote>,
        private var userDecorations: Map<String, ChatUserDecoration>,
        private var namePaints: Map<String, ChatNamePaint>,
        private var sevenTvBadges: Map<String, ChatCatalogBadge>,
        private var channelPointRewards: Map<String, ChatReward>,
        private var automaticChannelPointRewards: Map<String, ChatReward>,
        private var channelPointRewardsRevision: Int,
        private var structuralSettled: Boolean,
        private var badgesSettled: Boolean,
        private var rewardsSettled: Boolean,
    ) {
        private var resolvedCatalog: ChatCatalogSnapshot? = null
        private var resolvedRevision: Long? = null
        private var resolvedCurrentBadges: Map<String, ChatCatalogBadge>? = null

        fun update(
            catalog: ChatCatalogSnapshot,
            captureBadges: Boolean,
            settlement: ChatMetadataSettlement,
            forceUpgrade: Boolean,
        ) {
            var changed = false
            if (captureBadges && badges == null) {
                badges = catalog.badges
                changed = true
            }

            if (forceUpgrade || !structuralSettled && settlement.structuralSettled) {
                captureStructural(catalog)
                changed = true
            }
            if (captureBadges && (forceUpgrade || !badgesSettled && settlement.badgesSettled)) {
                badges = catalog.badges
                changed = true
            }
            if (forceUpgrade || !rewardsSettled && settlement.rewardsSettled) {
                captureRewards(catalog)
                changed = true
            }

            val nextStructuralSettled = structuralSettled || settlement.structuralSettled
            val nextBadgesSettled = badgesSettled || settlement.badgesSettled
            val nextRewardsSettled = rewardsSettled || settlement.rewardsSettled
            changed = changed ||
                structuralSettled != nextStructuralSettled ||
                badgesSettled != nextBadgesSettled ||
                rewardsSettled != nextRewardsSettled
            structuralSettled = nextStructuralSettled
            badgesSettled = nextBadgesSettled
            rewardsSettled = nextRewardsSettled
            if (changed) {
                resolvedCatalog = null
                resolvedRevision = null
                resolvedCurrentBadges = null
            }
        }

        private fun captureStructural(catalog: ChatCatalogSnapshot) {
            twitch = catalog.twitch
            sevenTv = catalog.sevenTv
            sevenTvChannelSetId = catalog.sevenTvChannelSetId
            bttv = catalog.bttv
            ffz = catalog.ffz
            cheermotes = catalog.cheermotes
            userDecorations = catalog.userDecorations
            namePaints = catalog.namePaints
            sevenTvBadges = catalog.sevenTvBadges
        }

        private fun captureRewards(catalog: ChatCatalogSnapshot) {
            channelPointRewards = catalog.channelPointRewards
            automaticChannelPointRewards = catalog.automaticChannelPointRewards
            channelPointRewardsRevision = catalog.channelPointRewardsRevision
        }

        fun toCatalog(current: ChatCatalogSnapshot): ChatCatalogSnapshot {
            val cached = resolvedCatalog
            if (cached != null && resolvedRevision == current.revision &&
                (badges != null || resolvedCurrentBadges === current.badges)
            ) {
                return cached
            }
            return current.copy(
                twitch = twitch,
                sevenTv = sevenTv,
                sevenTvChannelSetId = sevenTvChannelSetId,
                bttv = bttv,
                ffz = ffz,
                badges = badges ?: current.badges,
                cheermotes = cheermotes,
                userDecorations = userDecorations,
                namePaints = namePaints,
                sevenTvBadges = sevenTvBadges,
                channelPointRewards = channelPointRewards,
                automaticChannelPointRewards = automaticChannelPointRewards,
                channelPointRewardsRevision = channelPointRewardsRevision,
            ).also {
                resolvedCatalog = it
                resolvedRevision = current.revision
                resolvedCurrentBadges = current.badges
            }
        }

        companion object {
            fun from(
                catalog: ChatCatalogSnapshot,
                captureBadges: Boolean,
                settlement: ChatMetadataSettlement,
            ): FrozenCatalog = FrozenCatalog(
                twitch = catalog.twitch,
                sevenTv = catalog.sevenTv,
                sevenTvChannelSetId = catalog.sevenTvChannelSetId,
                bttv = catalog.bttv,
                ffz = catalog.ffz,
                badges = catalog.badges.takeIf { captureBadges },
                cheermotes = catalog.cheermotes,
                userDecorations = catalog.userDecorations,
                namePaints = catalog.namePaints,
                sevenTvBadges = catalog.sevenTvBadges,
                channelPointRewards = catalog.channelPointRewards,
                automaticChannelPointRewards = catalog.automaticChannelPointRewards,
                channelPointRewardsRevision = catalog.channelPointRewardsRevision,
                structuralSettled = settlement.structuralSettled,
                badgesSettled = settlement.badgesSettled,
                rewardsSettled = settlement.rewardsSettled,
            )
        }
    }
}
