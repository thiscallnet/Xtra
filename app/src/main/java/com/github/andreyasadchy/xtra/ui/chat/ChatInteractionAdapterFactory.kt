package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import java.util.Random

/**
 * Creates the small legacy-format adapters used inside message/reply dialogs.
 *
 * The live timeline is rendered by Chat v2. Keeping this factory separate means those dialogs
 * can retain their existing UI without constructing the legacy timeline adapter for live chat.
 */
internal class ChatInteractionAdapterFactory(
    private val configuration: ChatAdapterConfiguration,
    private val defaultMessages: List<ChatMessage> = emptyList(),
) {
    private var selectedMessage: ChatMessage? = null
    private val random = Random()
    private val userColors = HashMap<String, Int>()
    private val savedColors = HashMap<String, Int>()
    private val savedLocalTwitchEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalBadges = mutableMapOf<String, ByteArray>()
    private val savedLocalCheerEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalEmotes = mutableMapOf<String, ByteArray>()

    fun createMessageClickedChatAdapter(
        sourceMessages: List<ChatMessage>,
        selectedMessageOverride: ChatMessage? = selectedMessage,
        v2Rows: List<ChatRowUiModel>? = null,
        v2Assets: ChatAssetRepository? = null,
        v2EmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
        v2GifClick: ((ChatGifInteraction) -> Unit)? = null,
    ): MessageClickedChatAdapter = MessageClickedChatAdapter(
        sourceMessages,
        configuration.localTwitchEmotes,
        configuration.thirdPartyEmotes,
        configuration.globalBadges,
        configuration.channelBadges,
        configuration.cheerEmotes,
        configuration.namePaints,
        configuration.stvBadges,
        configuration.personalEmoteSets,
        configuration.stvUsers,
        configuration.enableTimestamps,
        configuration.timestampFormat,
        configuration.firstMsgVisibility,
        configuration.firstChatMsg,
        configuration.redeemedChatMsg,
        configuration.redeemedNoMsg,
        configuration.replyMessage,
        { chatMessage ->
            selectedMessage = chatMessage
            configuration.replyClickListener?.invoke()
        },
        { url, name, format, isAnimated, source, thirdParty, emoteId ->
            configuration.imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId)
        },
        configuration.useRandomColors,
        configuration.useReadableColors,
        configuration.isLightTheme,
        configuration.nameDisplay,
        configuration.useBoldNames,
        configuration.showNamePaints,
        configuration.showBadges,
        configuration.showSTVBadges,
        configuration.showPersonalEmotes,
        configuration.showSystemMessageEmotes,
        configuration.chatUrl,
        configuration.fragment,
        configuration.dialogBackgroundColor,
        configuration.imageLibrary,
        configuration.messageTextSize,
        configuration.emoteSize,
        configuration.badgeSize,
        configuration.inlineIconSize,
        configuration.emoteQuality,
        configuration.animateGifs,
        configuration.enableOverlayEmotes,
        false,
        configuration.translateMessage,
        configuration.showLanguageDownloadDialog,
        random,
        userColors,
        savedColors,
        savedLocalTwitchEmotes,
        savedLocalBadges,
        savedLocalCheerEmotes,
        savedLocalEmotes,
        configuration.loggedInUser,
        selectedMessageOverride,
        v2Rows,
        v2Assets,
        v2EmoteClick,
        v2GifClick,
    )

    fun createReplyClickedChatAdapter(
        sourceMessages: List<ChatMessage> = defaultMessages,
        selectedMessageOverride: ChatMessage? = selectedMessage,
    ): ReplyClickedChatAdapter = ReplyClickedChatAdapter(
        sourceMessages,
        configuration.localTwitchEmotes,
        configuration.thirdPartyEmotes,
        configuration.globalBadges,
        configuration.channelBadges,
        configuration.cheerEmotes,
        configuration.namePaints,
        configuration.stvBadges,
        configuration.personalEmoteSets,
        configuration.stvUsers,
        configuration.enableTimestamps,
        configuration.timestampFormat,
        configuration.firstMsgVisibility,
        configuration.firstChatMsg,
        configuration.redeemedChatMsg,
        configuration.redeemedNoMsg,
        configuration.replyMessage,
        { url, name, format, isAnimated, source, thirdParty, emoteId ->
            configuration.imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId)
        },
        configuration.useRandomColors,
        configuration.useReadableColors,
        configuration.isLightTheme,
        configuration.nameDisplay,
        configuration.useBoldNames,
        configuration.showNamePaints,
        configuration.showBadges,
        configuration.showSTVBadges,
        configuration.showPersonalEmotes,
        configuration.showSystemMessageEmotes,
        configuration.chatUrl,
        configuration.fragment,
        configuration.dialogBackgroundColor,
        configuration.imageLibrary,
        configuration.messageTextSize,
        configuration.emoteSize,
        configuration.badgeSize,
        configuration.inlineIconSize,
        configuration.emoteQuality,
        configuration.animateGifs,
        configuration.enableOverlayEmotes,
        false,
        configuration.translateMessage,
        configuration.showLanguageDownloadDialog,
        random,
        userColors,
        savedColors,
        savedLocalTwitchEmotes,
        savedLocalBadges,
        savedLocalCheerEmotes,
        savedLocalEmotes,
        configuration.loggedInUser,
        selectedMessageOverride,
    )
}
