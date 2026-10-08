package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaintShadow
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatUserDecoration
import kotlinx.coroutines.flow.map

internal fun ChatViewModel.v2DecorationSnapshot(): ChatDecorationSnapshot {
    val paints = synchronized(namePaints) {
        namePaints.mapNotNull { paint -> paint.id?.let { it to paint.toV2() } }.toMap()
    }
    val badges = synchronized(stvBadges) {
        stvBadges.mapNotNull { badge -> badge.toV2() }.toMap()
    }
    val users = synchronized(stvUsers) {
        stvUsers.associate { user ->
            user.userId to ChatUserDecoration(user.paintId, user.badgeId, user.emoteSetId)
        }
    }
    return ChatDecorationSnapshot(users = users, paints = paints, badges = badges)
}

internal fun NamePaint.toV2() = ChatNamePaint(
    colors = colors?.toList().orEmpty(),
    imageUrl = imageUrl,
    colorPositions = colorPositions?.toList().orEmpty(),
    type = type,
    angle = angle,
    repeat = repeat == true,
    shadows = shadows.orEmpty().map { ChatNamePaintShadow(it.xOffset, it.yOffset, it.radius, it.color) },
)

internal fun STVBadge.toV2(): Pair<String, ChatCatalogBadge>? {
    val key = id.takeIf { it.isNotBlank() } ?: return null
    val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
    return key to ChatCatalogBadge(
        name = key,
        asset = ChatAssetSpec(ChatAssetKey(url), 18, 18, 18),
        provider = ChatAssetProvider.SEVEN_TV,
        setId = key,
        versionId = "default",
        info = name,
    )
}

internal fun TwitchBadge.toV2CatalogEntry(): Pair<String, ChatCatalogBadge>? {
    val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
    val key = "$setId:$version"
    return key to ChatCatalogBadge(
        name = key,
        asset = ChatAssetSpec(ChatAssetKey(url), 18, 18, 18),
        provider = ChatAssetProvider.TWITCH,
        setId = setId,
        versionId = version,
        info = title,
    )
}
