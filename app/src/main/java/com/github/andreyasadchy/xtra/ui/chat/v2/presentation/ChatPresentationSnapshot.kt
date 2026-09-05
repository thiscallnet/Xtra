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

/**
 * Keeps structural catalog inputs stable for every message already published in a session.
 *
 * Catalog retries and live decoration updates are allowed to improve the presentation of new
 * messages. They must not insert a new span into an existing row, because the row may already
 * have been revealed by the atomic asset gate.
 */
internal class ChatPresentationSnapshot {
    private var sessionKey: ChatSessionKey? = null
    private val catalogsByMessage = HashMap<ChatMessageId, FrozenCatalog>()

    @Synchronized
    fun catalogsFor(
        key: ChatSessionKey,
        messages: List<ChatMessage>,
        catalog: ChatCatalogSnapshot,
        captureBadges: Boolean = true,
    ): List<ChatCatalogSnapshot> {
        if (sessionKey != key) {
            sessionKey = key
            catalogsByMessage.clear()
        }
        val messageIds = messages.asSequence().map(ChatMessage::id).toSet()
        catalogsByMessage.keys.retainAll(messageIds)
        return messages.map { message ->
            val frozen = catalogsByMessage[message.id]
            if (frozen == null) {
                val created = FrozenCatalog.from(catalog, captureBadges)
                catalogsByMessage[message.id] = created
                created.toCatalog(catalog)
            } else {
                if (captureBadges) frozen.captureBadges(catalog)
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
        private val twitch: Map<String, ChatCatalogEmote>,
        private val sevenTv: ScopedEmoteCatalog,
        private val sevenTvChannelSetId: String?,
        private val bttv: ScopedEmoteCatalog,
        private val ffz: ScopedEmoteCatalog,
        private var badges: Map<String, ChatCatalogBadge>?,
        private val cheermotes: Map<String, ChatCatalogCheermote>,
        private val userDecorations: Map<String, ChatUserDecoration>,
        private val namePaints: Map<String, ChatNamePaint>,
        private val sevenTvBadges: Map<String, ChatCatalogBadge>,
        private val channelPointRewards: Map<String, ChatReward>,
        private val automaticChannelPointRewards: Map<String, ChatReward>,
        private val channelPointRewardsRevision: Int,
    ) {
        fun captureBadges(catalog: ChatCatalogSnapshot) {
            if (badges == null) badges = catalog.badges
        }

        fun toCatalog(current: ChatCatalogSnapshot): ChatCatalogSnapshot = current.copy(
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
        )

        companion object {
            fun from(catalog: ChatCatalogSnapshot, captureBadges: Boolean): FrozenCatalog = FrozenCatalog(
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
            )
        }
    }
}
