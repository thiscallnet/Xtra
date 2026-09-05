package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import androidx.annotation.DrawableRes
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModeration
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink

data class ChatRowUiModel(
    val id: ChatMessageId,
    val channelId: String,
    val timestampText: String?,
    val timestampColor: Int = 0xFF999999.toInt(),
    val pieces: List<ChatPiece>,
    val background: Int,
    val backgroundStyle: ChatRowBackground = ChatRowBackground.NORMAL,
    /** Normalized event semantics and content sections, when this is a prominent event row. */
    val eventPresentation: ChatEventPresentation? = null,
    val accessibilityText: String,
    val moderation: ChatModeration? = null,
    val moderationColor: Int? = null,
    /** Piece range containing the moderated user's message, excluding surrounding metadata. */
    val moderationPieceRange: IntRange? = null,
    val reply: ChatReply?,
    val source: SharedChatSource?,
    val isAction: Boolean,
    val twitchType: TwitchChatMessageType = TwitchChatMessageType.Text,
    val clipPreviews: List<ChatClipPreviewLink> = emptyList(),
)

enum class ChatRowBackground {
    NORMAL,
    PERSONAL_HIGHLIGHT,
    EVENT,
    FIRST_CHATTER_TINT,
    REWARD,
    NOTICE,
}

/** Small, normalized event vocabulary used by the v2 chat presentation layer. */
enum class ChatEventKind {
    WATCH_STREAK,
    SUBSCRIPTION,
    CHANNEL_POINTS,
    HIGHLIGHT,
    FIRST_CHATTER,
    ANNOUNCEMENT,
    RAID,
    NOTICE,
}

/** The visual family controls color only. All event families share the same geometry. */
enum class ChatEventVisualStyle {
    SUPPORT,
    REWARD,
    STREAK,
    INTRO,
    NOTICE,
}

/**
 * The event contract is compiled once with the row. ChatMessageTextView only flattens these
 * sections into its existing span stream, keeping the hot rendering path allocation-light.
 */
data class ChatEventPresentation(
    val kind: ChatEventKind,
    val visualStyle: ChatEventVisualStyle,
    /** Usually an Icon, or a RewardIcon when a custom Channel Points image is available. */
    val icon: ChatPiece,
    val titlePieces: List<ChatPiece>,
    val metadataPieces: List<ChatPiece> = emptyList(),
    val bodyPieces: List<ChatPiece> = emptyList(),
    val accessibilityText: String,
) {
    fun flatten(): List<ChatPiece> = buildList {
        add(icon)
        add(ChatPiece.Text(" "))
        addAll(titlePieces)
        if (metadataPieces.isNotEmpty()) {
            add(ChatPiece.Text("\n"))
            addAll(metadataPieces)
        }
        if (bodyPieces.isNotEmpty()) {
            add(ChatPiece.Text("\n"))
            addAll(bodyPieces)
        }
    }
}

/** Shared event-row measurements. Keep these independent from event semantics and colors. */
object ChatEventVisualTokens {
    const val accentRailWidthDp = 4
    const val contentInsetAfterRailDp = 12
    const val endPaddingDp = 8
    const val verticalPaddingDp = 6
    const val lineSpacingExtraDp = 1
    const val iconSizeDp = 20

    const val contentStartInsetDp = accentRailWidthDp + contentInsetAfterRailDp
}

sealed interface ChatPiece {
    data class Text(val value: String, val color: Int? = null, val bold: Boolean = false) : ChatPiece
    data class Reply(val value: String, val color: Int) : ChatPiece
    data class Username(
        val value: String,
        val color: Int,
        val bold: Boolean = false,
        val paint: ChatNamePaint? = null,
        val separator: String = ": ",
    ) : ChatPiece
    /** Compact source-channel marker for Shared Chat messages. */
    data class Source(val value: String, val color: Int = 0xFF9F98A5.toInt()) : ChatPiece
    data class Icon(
        @DrawableRes val drawableRes: Int,
        val tint: Int? = null,
        val sizeDp: Int = 22,
    ) : ChatPiece
    data class Badge(val asset: ChatAssetSpec, val interaction: ChatEmoteInteraction? = null) : ChatPiece
    data class RewardIcon(val asset: ChatAssetSpec, val fallback: String) : ChatPiece
    data class Mention(val value: String, val userId: String?, val login: String?) : ChatPiece
    data class Emote(
        val asset: ChatAssetSpec,
        val fallback: String,
        val animated: Boolean = true,
        val interaction: ChatEmoteInteraction? = null,
    ) : ChatPiece
    data class Gif(
        val asset: ChatAssetSpec,
        val url: String,
        val fallback: String,
        val interaction: ChatGifInteraction? = null,
    ) : ChatPiece
    data class Cheermote(
        val asset: ChatAssetSpec,
        val value: String,
        val bits: Int,
        val color: Int?,
        val interaction: ChatEmoteInteraction? = null,
    ) : ChatPiece
}
