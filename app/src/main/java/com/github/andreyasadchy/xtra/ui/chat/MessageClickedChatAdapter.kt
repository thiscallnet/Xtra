package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.text.getSpans
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import com.github.andreyasadchy.xtra.util.chat.setChatMessageBackground
import com.github.andreyasadchy.xtra.util.chat.isHighlightedMessage
import com.github.andreyasadchy.xtra.util.chat.isWatchStreakNotice
import java.util.Random
import kotlin.math.roundToInt

internal fun isChatPopupMessageSelected(
    message: ChatMessage,
    selectedMessageId: String?,
    selectedMessage: ChatMessage?,
): Boolean = selectedMessageId?.takeIf { it.isNotBlank() }?.let { message.id == it } ?: (message === selectedMessage)

class MessageClickedChatAdapter(
    messages: List<ChatMessage>,
    private val localTwitchEmotes: List<TwitchEmote>,
    private val thirdPartyEmotes: List<Emote>,
    private val globalBadges: List<TwitchBadge>,
    private val channelBadges: List<TwitchBadge>,
    private val cheerEmotes: List<CheerEmote>,
    private val namePaints: List<NamePaint>,
    private val stvBadges: List<STVBadge>,
    private val personalEmoteSets: Map<String, List<Emote>>,
    private val stvUsers: List<STVUser>,
    private val enableTimestamps: Boolean,
    private val timestampFormat: String?,
    private val firstMsgVisibility: Int,
    private val firstChatMsg: String,
    private val redeemedChatMsg: String,
    private val redeemedNoMsg: String,
    private val replyMessage: String,
    private val replyClick: (ChatMessage) -> Unit,
    private val imageClick: (String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit,
    private val useRandomColors: Boolean,
    private val useReadableColors: Boolean,
    private val isLightTheme: Boolean,
    private val nameDisplay: String?,
    private val useBoldNames: Boolean,
    private val showNamePaints: Boolean,
    private val showBadges: Boolean,
    private val showSTVBadges: Boolean,
    private val showPersonalEmotes: Boolean,
    private val showSystemMessageEmotes: Boolean,
    private val chatUrl: String?,
    private val fragment: Fragment,
    private val backgroundColor: Int,
    private val imageLibrary: String?,
    private val messageTextSize: Float,
    private val emoteSize: Int,
    private val badgeSize: Int,
    private val inlineIconSize: Int,
    private val emoteQuality: String,
    private val animateGifs: Boolean,
    private val enableOverlayEmotes: Boolean,
    private val translateAllMessages: Boolean,
    private val translateMessage: (ChatMessage, String?) -> Unit,
    private val showLanguageDownloadDialog: (ChatMessage, String) -> Unit,
    private val random: Random,
    private val userColors: HashMap<String, Int>,
    private val savedColors: HashMap<String, Int>,
    private val savedLocalTwitchEmotes: MutableMap<String, ByteArray>,
    private val savedLocalBadges: MutableMap<String, ByteArray>,
    private val savedLocalCheerEmotes: MutableMap<String, ByteArray>,
    private val savedLocalEmotes: MutableMap<String, ByteArray>,
    private val loggedInUser: String?,
    var selectedMessage: ChatMessage?,
    private var v2Rows: List<ChatRowUiModel>? = null,
    private val v2Assets: ChatAssetRepository? = null,
    private val v2EmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
    private val v2GifClick: ((ChatGifInteraction) -> Unit)? = null,
) : RecyclerView.Adapter<MessageClickedChatAdapter.ViewHolder>() {

    private var selectedMessageId: String? = selectedMessage?.id?.takeIf { it.isNotBlank() }

    private fun isSelected(message: ChatMessage): Boolean =
        isChatPopupMessageSelected(message, selectedMessageId, selectedMessage)

    val type = selectedMessage?.type
    val userId = selectedMessage?.userId
    val userLogin = selectedMessage?.userLogin
    val messages = if (type == ChatMessage.USER_MESSAGE) {
        if (!userId.isNullOrBlank() || !userLogin.isNullOrBlank()) {
            synchronized(messages) {
                messages.filter {
                    (!userId.isNullOrBlank() && (it.userId == userId || it.replyParent?.userId == userId)) ||
                            (!userLogin.isNullOrBlank() && (it.userLogin == userLogin || it.replyParent?.userLogin == userLogin))
                }.toMutableList()
            }
        } else null
    } else {
        synchronized(messages) {
            messages.filter { it.type == type }.toMutableList()
        }
    }?.ifEmpty { null } ?: selectedMessage?.let { mutableListOf(it) } ?: mutableListOf()

    var messageClickListener: ((ChatMessage, ChatMessage?) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = v2Assets?.let {
            ChatMessageTextView(parent.context, it).apply {
                setMessageTextSizeSp(messageTextSize)
                setAnimateGifs(animateGifs)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
        } ?: LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatMessage = synchronized(messages) {
            messages.getOrNull(position)
        } ?: return
        val v2Row = v2Rows?.getOrNull(position)
        if (v2Row != null && holder.textView is ChatMessageTextView) {
            val v2View = holder.textView as ChatMessageTextView
            v2View.setInteractionCallbacks(null, v2EmoteClick, v2GifClick)
            v2View.bind(v2Row)
            bindSelectionClick(holder.textView, chatMessage)
            if (isSelected(chatMessage)) setChatMessageBackground(holder.textView, R.color.chatMessageSelected)
            return
        }
        val result = ChatAdapterUtils.prepareChatMessage(
            chatMessage, fragment.requireContext(), holder.textView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg,
            redeemedChatMsg, redeemedNoMsg, replyMessage, { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClick(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, random, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, namePaints, showBadges, showSTVBadges,
            stvBadges, showPersonalEmotes, personalEmoteSets, stvUsers, enableOverlayEmotes, showSystemMessageEmotes, loggedInUser, chatUrl,
            userColors, savedColors, translateAllMessages, translateMessage, showLanguageDownloadDialog, false, localTwitchEmotes,
            thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes,
            highlightSettings = resolveChatHighlightSettings(fragment.requireContext()),
        )
        if (isSelected(chatMessage)) {
            setChatMessageBackground(holder.textView, R.color.chatMessageSelected)
        }
        // Peek-only: the main list owns clip loading through the shared repository,
        // so a dialog opened after the row loaded shows the same unfurl.
        installLegacyClipLinkClicks(result.builder)
        clipLinksOf(chatMessage.message).takeIf { it.isNotEmpty() }?.let { clipLinks ->
            val repository = (fragment.context?.applicationContext as? XtraApp)?.xtraModule?.chatClipPreviewRepository
            appendLegacyClipEmbeds(
                fragment.requireContext(),
                result.builder,
                result.images,
                clipLinks,
                clipLinks.map { repository?.peek(it.slug) },
            )
        }
        ChatAdapterUtils.installImagePlaceholders(
            result.builder,
            result.images,
            emoteSize,
            badgeSize,
            inlineIconSize,
            result.imagePaint,
            result.userName,
            result.userNameStartIndex,
            backgroundColor,
        )
        holder.bind(chatMessage, result.builder)
        ChatAdapterUtils.loadImages(
            fragment, holder.textView, result.images, result.imagePaint, result.userName, result.userNameStartIndex,
            backgroundColor, imageLibrary, result.builder, emoteQuality, animateGifs,
            emoteSize = emoteSize,
            badgeSize = badgeSize,
            inlineIconSize = inlineIconSize,
        )
    }

    fun updateTranslation(chatMessage: ChatMessage, item: TextView, previousTranslation: String?) {
        (item.text as? SpannableString)?.let { text ->
            val builder = SpannableStringBuilder()
            builder.append(
                if (previousTranslation != null) {
                    text.dropLast(previousTranslation.length + 1)
                } else {
                    text
                }
            )
            ChatAdapterUtils.addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, false)
            item.text = builder
        }
    }

    override fun getItemCount(): Int = synchronized(messages) {
        messages.size
    }

    /** Replaces the popup's rows from the canonical v2 publication. */
    fun updateV2Messages(sourceMessages: List<ChatMessage>, rows: List<ChatRowUiModel>) {
        if (v2Assets == null) return
        val filteredMessages = sourceMessages.filter(::belongsToSelectedUser)
        val ids: Set<String?> = filteredMessages.mapTo(HashSet()) { it.id }
        val filteredRows = rows.filter { it.id.value in ids }
        synchronized(messages) {
            messages.clear()
            messages.addAll(filteredMessages)
        }
        selectedMessage = selectedMessageId?.let { id ->
            filteredMessages.firstOrNull { it.id == id }
        } ?: selectedMessage?.let { selected ->
            filteredMessages.firstOrNull { it === selected } ?: selected
        }
        v2Rows = filteredRows
        notifyDataSetChanged()
    }

    private fun belongsToSelectedUser(message: ChatMessage): Boolean {
        if (type != ChatMessage.USER_MESSAGE) return message.type == type
        if (!userId.isNullOrBlank() && (message.userId == userId || message.replyParent?.userId == userId)) return true
        return !userLogin.isNullOrBlank() &&
            (message.userLogin.equals(userLogin, true) || message.replyParent?.userLogin.equals(userLogin, true))
    }

    private fun bindSelectionClick(view: TextView, chatMessage: ChatMessage) {
        view.setOnClickListener {
            if (view.selectionStart == -1 && view.selectionEnd == -1 && !isSelected(chatMessage)) {
                val previous = selectedMessage
                messageClickListener?.invoke(chatMessage, previous)
                selectedMessage = chatMessage
                selectedMessageId = chatMessage.id?.takeIf { it.isNotBlank() }
                setChatMessageBackground(view, R.color.chatMessageSelected)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.let { view ->
                view.getSpans<ImageSpan>().forEach {
                    (it.drawable as? Animatable)?.start() ?:
                    (it.drawable as? LayerDrawable)?.let {
                        val lastIndex = it.numberOfLayers - 1
                        if (lastIndex > -1) {
                            for (i in 0..lastIndex) {
                                (it.getDrawable(i) as? Animatable)?.start()
                            }
                        }
                    }
                }
                view.getSpans<NamePaintImageSpan>().forEach {
                    (it.drawable as? Animatable)?.start()
                }
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (animateGifs) {
            (holder.textView.text as? Spannable)?.let { view ->
                view.getSpans<ImageSpan>().forEach {
                    (it.drawable as? Animatable)?.stop() ?:
                    (it.drawable as? LayerDrawable)?.let {
                        val lastIndex = it.numberOfLayers - 1
                        if (lastIndex > -1) {
                            for (i in 0..lastIndex) {
                                (it.getDrawable(i) as? Animatable)?.stop()
                            }
                        }
                    }
                }
                view.getSpans<NamePaintImageSpan>().forEach {
                    (it.drawable as? Animatable)?.stop()
                }
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        val childCount = recyclerView.childCount
        if (animateGifs) {
            for (i in 0 until childCount) {
                ((recyclerView.getChildAt(i) as TextView).text as? Spannable)?.let { view ->
                    view.getSpans<ImageSpan>().forEach {
                        (it.drawable as? Animatable)?.stop() ?:
                        (it.drawable as? LayerDrawable)?.let {
                            val lastIndex = it.numberOfLayers - 1
                            if (lastIndex > -1) {
                                for (i in 0..lastIndex) {
                                    (it.getDrawable(i) as? Animatable)?.stop()
                                }
                            }
                        }
                    }
                    view.getSpans<NamePaintImageSpan>().forEach {
                        (it.drawable as? Animatable)?.stop()
                    }
                }
            }
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView = itemView as TextView

        fun bind(chatMessage: ChatMessage, formattedMessage: SpannableStringBuilder) {
            textView.apply {
                text = formattedMessage
                val specialPadding = if (chatMessage.isHighlightedMessage() || chatMessage.isWatchStreakNotice()) {
                    (6f * resources.displayMetrics.density).roundToInt()
                } else 0
                setPadding(0, specialPadding, 0, specialPadding)
                contentDescription = ChatAdapterUtils.accessibilityDescription(
                    textView.context,
                    chatMessage,
                    nameDisplay,
                    formattedMessage,
                )
                textSize = messageTextSize
                if (chatMessage.type == ChatMessage.REPLY_MESSAGE) {
                    movementMethod = null
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    TooltipCompat.setTooltipText(this, chatMessage.replyParent?.message ?: chatMessage.replyParent?.systemMsg)
                    setOnClickListener {
                        chatMessage.replyParent?.let { replyClick(it) }
                    }
                } else {
                    movementMethod = LinkMovementMethod.getInstance()
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    TooltipCompat.setTooltipText(this, chatMessage.message ?: chatMessage.systemMsg)
                    setOnClickListener {
                        if (selectionStart == -1 && selectionEnd == -1 && !isSelected(chatMessage)) {
                            messageClickListener?.invoke(chatMessage, selectedMessage)
                            selectedMessage = chatMessage
                            selectedMessageId = chatMessage.id?.takeIf { it.isNotBlank() }
                            setChatMessageBackground(textView, R.color.chatMessageSelected)
                            (text as? Spannable)?.let { view ->
                                view.getSpans<NamePaintImageSpan>().forEach {
                                    it.backgroundColor = (background as? ColorDrawable)?.color
                                    view.setSpan(it, view.getSpanStart(it), view.getSpanEnd(it), SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
