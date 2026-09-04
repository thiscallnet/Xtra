package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import android.content.ContentResolver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.Patterns
import android.view.View
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.ImageKind
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintSpan
import com.github.andreyasadchy.xtra.ui.chat.ChatHighlightSettings
import com.github.andreyasadchy.xtra.ui.chat.shouldHighlightLegacyChatMessage
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import java.text.NumberFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import android.util.Base64
import coil3.request.Disposable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

object ChatAdapterUtils {

    class ChatImagePrefetchTracker(private val maxEntries: Int = 512) {
        private val keys = LinkedHashMap<String, Long>(maxEntries, 0.75f, true)
        private var nextToken = 0L

        init {
            require(maxEntries > 0)
        }

        fun tryStart(key: String): Long? = synchronized(keys) {
            if (keys.containsKey(key)) {
                null
            }
            else {
                val token = ++nextToken
                keys[key] = token
                while (keys.size > maxEntries) {
                    keys.entries.iterator().next().let { keys.remove(it.key) }
                }
                token
            }
        }

        fun markFailed(key: String, token: Long) = synchronized(keys) {
            if (keys[key] == token) keys.remove(key)
        }
    }

    private data class LocalEmoteKey(val source: String, val offset: Long, val length: Int)

    private const val LOCAL_EMOTE_CACHE_MAX_BYTES = 8 * 1024 * 1024
    private val localEmoteCache = LinkedHashMap<LocalEmoteKey, ByteArray>(32, 0.75f, true)
    private var localEmoteCacheBytes = 0
    /**
     * Shares one decode operation between simultaneous renders. The result is cloned at each
     * render boundary, so mutable drawable bounds/callbacks never cross TextViews.
     */
    private val imageResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(8))
    private val imageResolutions = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Drawable?>>()

    class ChatCatalogIndexes private constructor(
        val twitchEmotesById: Map<String, TwitchEmote>,
        val thirdPartyEmotesByName: Map<String, Emote>,
        val personalEmotesBySet: Map<String, Map<String, Emote>>,
        val globalBadgesByKey: Map<String, TwitchBadge>,
        val channelBadgesByKey: Map<String, TwitchBadge>,
        val stvUsersById: Map<String, STVUser>,
        val stvBadgesById: Map<String, STVBadge>,
        val namePaintsById: Map<String, NamePaint>,
        val cheersByName: Map<String, List<CheerEmote>>,
    ) {
        companion object {
            fun create(
                localTwitchEmotes: List<TwitchEmote>,
                thirdPartyEmotes: List<Emote>,
                globalBadges: List<TwitchBadge>,
                channelBadges: List<TwitchBadge>,
                stvUsers: List<STVUser>,
                stvBadges: List<STVBadge>,
                namePaints: List<NamePaint>,
                personalEmoteSets: Map<String, List<Emote>>,
                cheerEmotes: List<CheerEmote>,
            ) = ChatCatalogIndexes(
                twitchEmotesById = localTwitchEmotes.mapNotNull { it.id?.let { id -> id to it } }.toMap(),
                thirdPartyEmotesByName = thirdPartyEmotes.mapNotNull { it.name?.let { name -> name to it } }.toMap(),
                personalEmotesBySet = personalEmoteSets.mapValues { (_, emotes) ->
                    emotes.mapNotNull { it.name?.let { name -> name to it } }.toMap()
                },
                globalBadgesByKey = globalBadges.associateBy(::badgeKey),
                channelBadgesByKey = channelBadges.associateBy(::badgeKey),
                stvUsersById = stvUsers.associateBy { it.userId },
                stvBadgesById = stvBadges.associateBy { it.id },
                namePaintsById = namePaints.mapNotNull { it.id?.let { id -> id to it } }.toMap(),
                cheersByName = cheerEmotes.groupBy { it.name.lowercase() },
            )

            private fun badgeKey(badge: TwitchBadge) = "${badge.setId}:${badge.version}"
        }
    }

    class ImageRequestBag {
        private val disposables = mutableListOf<Disposable>()
        private val jobs = mutableListOf<Job>()
        private val clearers = mutableListOf<() -> Unit>()
        fun add(disposable: Disposable) { disposables += disposable }
        fun add(job: Job) { jobs += job }
        fun addClearer(clear: () -> Unit) { clearers += clear }
        fun cancel() {
            disposables.forEach(Disposable::dispose)
            jobs.forEach(Job::cancel)
            clearers.forEach { it() }
            disposables.clear(); jobs.clear(); clearers.clear()
        }
    }

    private val twitchColors = intArrayOf(-65536, -16776961, -16744448, -5103070, -32944, -6632142, -47872, -13726889, -2448096, -2987746, -10510688, -14774017, -38476, -7722014, -16711809)
    private const val RED_HUE_DEGREES = 0f
    private const val GREEN_HUE_DEGREES = 120f
    private const val BLUE_HUE_DEGREES = 240f
    private const val PI_DEGREES = 180f
    private const val TWO_PI_DEGREES = 360f

    fun accessibilityDescription(
        context: Context,
        chatMessage: ChatMessage,
        nameDisplay: String?,
        formattedFallback: CharSequence,
    ): String {
        fun userLabel(name: String?, login: String?): String? {
            return if (!name.isNullOrBlank() && !login.isNullOrBlank() && !login.equals(name, true)) {
                when (nameDisplay) {
                    "0" -> "$name($login)"
                    "1" -> name
                    else -> login
                }
            } else {
                name ?: login
            }
        }

        fun userLabel(message: ChatMessage): String? = userLabel(message.userName, message.userLogin)

        fun messageText(message: ChatMessage, fallback: CharSequence? = null): String {
            return message.message ?: message.systemMsg ?: fallback?.toString().orEmpty()
        }

        val sender = userLabel(chatMessage)
            ?: chatMessage.reply?.let { userLabel(it.userName, it.userLogin) }
        val body = messageText(chatMessage).takeIf { it.isNotBlank() }
            ?: chatMessage.reply?.message
            ?: formattedFallback.toString()
        val description = if (!sender.isNullOrBlank() && body.isNotBlank()) {
            context.getString(R.string.chat_accessibility_message, sender, body)
        } else {
            sender ?: body
        }
        val parent = chatMessage.replyParent
        if (chatMessage.type == ChatMessage.REPLY_MESSAGE && parent != null) {
            val parentSender = userLabel(parent)
            val parentBody = messageText(parent)
            val parentDescription = if (!parentSender.isNullOrBlank() && parentBody.isNotBlank()) {
                context.getString(R.string.chat_accessibility_message, parentSender, parentBody)
            } else {
                parentSender ?: parentBody
            }
            return context.getString(R.string.chat_accessibility_reply, description, parentDescription)
        }
        return description
    }

    fun prepareChatMessage(chatMessage: ChatMessage, context: Context, itemView: View?, enableTimestamps: Boolean, timestampFormat: String?, firstMsgVisibility: Int, firstChatMsg: String, redeemedChatMsg: String, redeemedNoMsg: String, replyMessage: String, imageClick: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?, useRandomColors: Boolean, random: Random, useReadableColors: Boolean, isLightTheme: Boolean, nameDisplay: String?, useBoldNames: Boolean, showNamePaints: Boolean, namePaints: List<NamePaint>, showBadges: Boolean, showSTVBadges: Boolean, stvBadges: List<STVBadge>, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>, stvUsers: List<STVUser>, enableOverlayEmotes: Boolean, showSystemMessageEmotes: Boolean, loggedInUser: String?, chatUrl: String?, userColors: HashMap<String, Int>, savedColors: HashMap<String, Int>, translateAllMessages: Boolean, translateMessage: (ChatMessage, String?) -> Unit, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean, localTwitchEmotes: List<TwitchEmote>, thirdPartyEmotes: List<Emote>, globalBadges: List<TwitchBadge>, channelBadges: List<TwitchBadge>, cheerEmotes: List<CheerEmote>, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalBadges: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>, catalogIndexes: ChatCatalogIndexes? = null, includeAccessibilityDescription: Boolean = false, highlightSettings: ChatHighlightSettings = ChatHighlightSettings()): MessageResult {
        val indexes = catalogIndexes ?: ChatCatalogIndexes.create(localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, stvUsers, stvBadges, namePaints, personalEmoteSets, cheerEmotes)
        val builder = SpannableStringBuilder()
        val images = ArrayList<Image>()
        var imagePaint: NamePaint? = null
        var userName: String? = null
        var userNameStartIndex: Int? = null
        var wasMentioned = false
        var translated = false
        var backgroundResource = 0
        var backgroundColor: Int? = null
        val highlightMatch = shouldHighlightLegacyChatMessage(chatMessage, highlightSettings)
        var builderIndex = 0
        val badgeVisibility = chatBadgeVisibility(showBadges, showSTVBadges, showNamePaints, showPersonalEmotes)
        when {
            chatMessage.isFirst && firstMsgVisibility == 0 -> {
                val headingColor = getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme)
                val userColor = chatMessage.color?.let { getSavedColor(it, savedColors, useReadableColors, isLightTheme) }
                    ?: headingColor
                appendSpecialIcon(builder, context, R.drawable.ic_chat_first_chatter, headingColor)
                builder.append(' ')
                appendSpecialText(builder, firstChatMsg, headingColor, bold = true)
                builder.append('\n')
                val chatterName = chatMessage.displayName(nameDisplay)
                if (!chatterName.isNullOrBlank()) {
                    appendSpecialText(builder, chatterName, userColor, bold = true)
                    builder.append(": ")
                }
                val message = chatMessage.message.orEmpty()
                val messageStart = builder.length
                builder.append(message)
                if (message.isNotEmpty()) {
                    wasMentioned = prepareEmotes(chatMessage, message, builder, messageStart, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                }
                builderIndex = builder.length
                if (chatMessage.translatedMessage != null) {
                    translated = true
                    builderIndex = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                } else if (translateAllMessages) {
                    translateMessage(chatMessage, null)
                }
                backgroundResource = R.drawable.bg_chat_first_chatter
            }
            chatMessage.type == ChatMessage.REPLY_MESSAGE -> {
                val userName = if (chatMessage.reply?.userName != null && chatMessage.reply.userLogin != null && !chatMessage.reply.userLogin.equals(chatMessage.reply.userName, true)) {
                    when (nameDisplay) {
                        "0" -> "${chatMessage.reply.userName}(${chatMessage.reply.userLogin})"
                        "1" -> chatMessage.reply.userName
                        else -> chatMessage.reply.userLogin
                    }
                } else {
                    chatMessage.reply?.userName ?: chatMessage.reply?.userLogin
                }
                val mutedColor = getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)
                appendSpecialIcon(builder, context, R.drawable.ic_chat_reply, mutedColor, sizeDp = 18)
                builder.append(' ')
                val string = replyMessage.format(userName, "")
                appendSpecialText(builder, string, mutedColor)
                builderIndex = builder.length
                val message = chatMessage.reply?.message
                if (message != null) {
                    builder.append(message)
                    builder.setSpan(ForegroundColorSpan(mutedColor), builderIndex, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    prepareEmotes(chatMessage, message, builder, builderIndex, images, null, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                    builderIndex = builder.length
                }
                backgroundResource = 0
            }
            chatMessage.isWatchStreakNotice() -> {
                val headingColor = getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme)
                val mutedColor = getSavedColor("#C4BEC9", savedColors, useReadableColors, isLightTheme)
                val userColor = chatMessage.color?.let { getSavedColor(it, savedColors, useReadableColors, isLightTheme) }
                    ?: headingColor
                userName = chatMessage.displayName(nameDisplay)
                appendSpecialIcon(builder, context, R.drawable.ic_watch_streak, headingColor)
                builder.append(' ')
                appendSpecialText(builder, context.getString(R.string.chat_watch_streak_reached), headingColor, bold = true)
                chatMessage.watchStreakPoints?.let { points ->
                    builder.append("  ")
                    appendSpecialIcon(builder, context, R.drawable.ic_chat_channel_points, headingColor)
                    builder.append(' ')
                    appendSpecialText(builder, "+${NumberFormat.getInstance().format(points)}", headingColor, bold = true)
                }
                builder.append('\n')
                val streakUser = userName ?: chatMessage.userLogin.orEmpty()
                val userMarker = "\uE000"
                val status = context.getString(
                    R.string.chat_watch_streak_status,
                    userMarker,
                    NumberFormat.getInstance().format(chatMessage.watchStreakCount ?: 0),
                )
                val markerStart = status.indexOf(userMarker)
                if (markerStart >= 0) {
                    appendSpecialText(builder, status.substring(0, markerStart), mutedColor)
                    appendSpecialText(builder, streakUser, userColor, bold = true)
                    appendSpecialText(builder, status.substring(markerStart + userMarker.length), mutedColor)
                } else {
                    appendSpecialText(builder, streakUser, userColor, bold = true)
                    appendSpecialText(builder, status, mutedColor)
                }
                if (!chatMessage.message.isNullOrBlank()) {
                    builder.append('\n')
                    val messageStart = builder.length
                    builder.append(chatMessage.message)
                    wasMentioned = prepareEmotes(chatMessage, chatMessage.message, builder, messageStart, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                    builderIndex = builder.length
                }
                if (chatMessage.translatedMessage != null) {
                    translated = true
                    builderIndex = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                } else if (translateAllMessages) {
                    translateMessage(chatMessage, null)
                }
                builder.setSpan(
                    LeadingMarginSpan.Standard(dp(context, 30), dp(context, 10)),
                    0,
                    builder.length,
                    SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                backgroundResource = R.drawable.bg_chat_watch_streak
            }
            chatMessage.message.isNullOrBlank() &&
                (chatMessage.systemMsg != null || chatMessage.reward?.title != null) &&
                !chatMessage.isHighlightedMessage() -> {
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), 0, timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                if (chatMessage.systemMsg != null) {
                    if (chatMessage.isSubscriptionNotice()) {
                        appendSpecialIcon(builder, context, chatMessage.subscriptionIcon(), getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme))
                        builder.append(' ')
                        builderIndex = builder.length
                    }
                    val systemStart = builder.length
                    if (chatMessage.isSubscriptionNotice()) {
                        appendSubscriptionSystemMessage(
                            builder,
                            chatMessage.systemMsg,
                            getSavedColor("#C4BEC9", savedColors, useReadableColors, isLightTheme),
                            chatMessage.color?.let { getSavedColor(it, savedColors, useReadableColors, isLightTheme) }
                                ?: getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme),
                            chatMessage.userName ?: chatMessage.userLogin,
                        )
                    } else {
                        builder.append(chatMessage.systemMsg)
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), systemStart, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (showSystemMessageEmotes) {
                        prepareEmotes(chatMessage, chatMessage.systemMsg, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                    }
                    builderIndex = builder.length
                    if (chatMessage.translatedMessage != null) {
                        translated = true
                        val result = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                        builderIndex = result
                    } else {
                        if (translateAllMessages) {
                            translateMessage(chatMessage, null)
                        }
                    }
                } else {
                    val reward = chatMessage.reward
                    if (reward?.title != null) {
                        val userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                            when (nameDisplay) {
                                "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                                "1" -> chatMessage.userName
                                else -> chatMessage.userLogin
                            }
                        } else {
                            chatMessage.userName
                        }
                        val string = redeemedNoMsg.format(userName, reward.title)
                        builder.append("$string ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + string.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (showSystemMessageEmotes) {
                            prepareEmotes(chatMessage, string, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                        }
                        builderIndex = builder.length
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        images.add(Image(
                            url1x = reward.url1x,
                            url2x = reward.url2x,
                            url3x = reward.url4x,
                            url4x = reward.url4x,
                            kind = ImageKind.INLINE_ICON,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                        if (reward.cost != null) {
                            val cost = NumberFormat.getInstance().format(reward.cost)
                            builder.append(cost)
                            builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + cost.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            builderIndex += cost.length
                        }
                    }
                }
                backgroundResource = if (chatMessage.isSubscriptionNotice()) {
                    R.drawable.bg_chat_subscription
                } else {
                    0
                }
            }
            else -> {
                val reward = chatMessage.reward
                if (chatMessage.isHighlightedMessage()) {
                    val headingColor = getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme)
                    val headingTitle = reward?.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.chat_highlight_title)
                    val heading = context.getString(R.string.chat_highlight_redeemed, headingTitle)
                    val titleStart = heading.indexOf(headingTitle).coerceAtLeast(0)
                    appendSpecialText(builder, heading.substring(0, titleStart), headingColor)
                    appendSpecialText(builder, heading.substring(titleStart), headingColor, bold = true)
                    builder.append('\n')
                    reward?.cost?.let { cost ->
                        appendSpecialIcon(builder, context, R.drawable.ic_chat_channel_points, headingColor)
                        builder.append(' ')
                        appendSpecialText(builder, NumberFormat.getInstance().format(cost), headingColor, bold = true)
                        builder.append('\n')
                    }
                    builderIndex = builder.length
                } else if (chatMessage.systemMsg != null) {
                    if (chatMessage.isSubscriptionNotice()) {
                        appendSpecialIcon(builder, context, chatMessage.subscriptionIcon(), getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme))
                        builder.append(' ')
                        builderIndex = builder.length
                    }
                    val systemStart = builder.length
                    if (chatMessage.isSubscriptionNotice()) {
                        appendSubscriptionSystemMessage(
                            builder,
                            chatMessage.systemMsg,
                            getSavedColor("#C4BEC9", savedColors, useReadableColors, isLightTheme),
                            chatMessage.color?.let { getSavedColor(it, savedColors, useReadableColors, isLightTheme) }
                                ?: getSavedColor("#E8E4EC", savedColors, useReadableColors, isLightTheme),
                            chatMessage.userName ?: chatMessage.userLogin,
                        )
                    } else {
                        builder.append(chatMessage.systemMsg)
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), systemStart, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    builder.append('\n')
                    builderIndex += chatMessage.systemMsg.length + 1
                } else {
                    if (chatMessage.msgId != null) {
                        val msgId = TwitchApiHelper.getMessageIdString(context, chatMessage.msgId) ?: chatMessage.msgId
                        builder.append("$msgId\n")
                        builderIndex += msgId.length + 1
                    }
                }
                if (chatMessage.isFirst && firstMsgVisibility == 0) {
                    builder.append("$firstChatMsg\n")
                    builderIndex += firstChatMsg.length + 1
                }
                if (reward?.title != null && !chatMessage.isHighlightedMessage()) {
                    val string = redeemedChatMsg.format(reward.title)
                    builder.append("$string ")
                    builderIndex += string.length + 1
                    builder.append(". ")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    images.add(Image(
                        url1x = reward.url1x,
                        url2x = reward.url2x,
                        url3x = reward.url4x,
                        url4x = reward.url4x,
                        kind = ImageKind.INLINE_ICON,
                        start = builderIndex++,
                        end = builderIndex++
                    ))
                    if (reward.cost != null) {
                        val cost = NumberFormat.getInstance().format(reward.cost)
                        builder.append(cost)
                        builderIndex += cost.length
                    }
                    builder.append("\n")
                    builderIndex += 1
                }
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                var hasBadge = false
                if (badgeVisibility.showTwitchBadges) {
                    chatMessage.badges?.forEach { chatBadge ->
                        val badge = synchronized(channelBadges) {
                            indexes.channelBadgesByKey["${chatBadge.setId}:${chatBadge.version}"]
                        } ?:
                        synchronized(globalBadges) {
                            indexes.globalBadgesByKey["${chatBadge.setId}:${chatBadge.version}"]
                        }
                        if (badge != null) {
                            hasBadge = true
                            builder.append(". ")
                            builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (imageClick != null) {
                                builder.setSpan(object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.title, null, null, null, null, null)
                                    }

                                    override fun updateDrawState(ds: TextPaint) {}
                                }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            images.add(Image(
                                localData = badge.localData?.let { getLocalEmoteData(badge.setId + badge.version, it, savedLocalBadges, chatUrl)?.first },
                                localDataUrl = badge.localData?.let { getLocalEmoteData(badge.setId + badge.version, it, savedLocalBadges, chatUrl)?.second },
                                localDataRange = badge.localData?.let { getLocalEmoteData(badge.setId + badge.version, it, savedLocalBadges, chatUrl)?.third },
                                url1x = badge.url1x,
                                url2x = badge.url2x,
                                url3x = badge.url3x,
                                url4x = badge.url4x,
                                kind = ImageKind.BADGE,
                                start = builderIndex++,
                                end = builderIndex++
                            ))
                        }
                    }
                }
                val stvUser = if (badgeVisibility.loadStvUser && !chatMessage.userId.isNullOrBlank()) {
                    synchronized(stvUsers) {
                        indexes.stvUsersById[chatMessage.userId]
                    }
                } else null
                if (badgeVisibility.showStvBadges && !chatMessage.userId.isNullOrBlank()) {
                    val badge = stvUser?.badgeId?.let { badgeId ->
                        synchronized(stvBadges) {
                            indexes.stvBadgesById[badgeId]
                        }
                    }
                    if (badge != null) {
                        hasBadge = true
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.name, badge.format, true, null, true, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        images.add(Image(
                            url1x = badge.url1x,
                            url2x = badge.url2x,
                            url3x = badge.url3x,
                            url4x = badge.url4x,
                            format = badge.format,
                            isAnimated = true,
                            thirdParty = true,
                            kind = ImageKind.BADGE,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                    }
                }
                if (hasBadge && builder.lastOrNull() == ' ') {
                    builder.delete(builder.length - 1, builder.length)
                    builderIndex--
                }
                val color = if (chatMessage.color != null) {
                    getSavedColor(chatMessage.color, savedColors, useReadableColors, isLightTheme)
                } else {
                    synchronized(userColors) {
                        userColors[chatMessage.userName] ?: if (useRandomColors) {
                            twitchColors[random.nextInt(twitchColors.size)]
                        } else {
                            -10066329
                        }.let { newColor ->
                            if (useReadableColors) {
                                adaptUsernameColor(newColor, isLightTheme)
                            } else {
                                newColor
                            }.also { if (chatMessage.userName != null) userColors[chatMessage.userName] = it }
                        }
                    }
                }
                if (!chatMessage.userName.isNullOrBlank()) {
                    userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                        when (nameDisplay) {
                            "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                            "1" -> chatMessage.userName
                            else -> chatMessage.userLogin
                        }
                    } else {
                        chatMessage.userName
                    }
                    builder.append(userName)
                    builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (useBoldNames) {
                        builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (showNamePaints && !chatMessage.userId.isNullOrBlank()) {
                        stvUser?.paintId?.let { paintId ->
                            synchronized(namePaints) {
                                indexes.namePaintsById[paintId]
                            }
                        }?.let { paint ->
                            when (paint.type) {
                                "LINEAR_GRADIENT", "RADIAL_GRADIENT" -> {
                                    if (paint.colors != null && paint.colorPositions != null) {
                                        builder.setSpan(
                                            NamePaintSpan(
                                                userName,
                                                paint.type,
                                                paint.colors,
                                                paint.colorPositions,
                                                paint.angle,
                                                paint.repeat,
                                                paint.shadows
                                            ),
                                            builderIndex,
                                            builderIndex + userName.length,
                                            SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                }
                                "URL" -> {
                                    if (!paint.imageUrl.isNullOrBlank()) {
                                        imagePaint = paint
                                        userNameStartIndex = builderIndex
                                    }
                                }
                            }
                        }
                    }
                    builderIndex += userName.length
                    if (!chatMessage.isAction) {
                        builder.append(": ")
                        builderIndex += 2
                    } else {
                        builder.append(" ")
                        builderIndex += 1
                    }
                }
                if (chatMessage.message != null) {
                    builder.append(chatMessage.message)
                    if (chatMessage.isAction) {
                        builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + chatMessage.message.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val result = prepareEmotes(chatMessage, chatMessage.message, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, stvUser, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes, indexes)
                    wasMentioned = result
                    builderIndex = builder.length
                }
                if (chatMessage.translatedMessage != null) {
                    translated = true
                    val result = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                    builderIndex = result
                } else {
                    if (translateAllMessages) {
                        translateMessage(chatMessage, null)
                    }
                }
                backgroundResource = chatMessageBackgroundResource(chatMessage, firstMsgVisibility, highlightMatch)
            }
        }
        if (chatMessage.isHighlightedMessage()) {
            builder.setSpan(
                LeadingMarginSpan.Standard(dp(context, 28), dp(context, 28)),
                0,
                builder.length,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            backgroundResource = R.drawable.bg_chat_highlight
        }
        if (backgroundResource == R.color.chatMessageMention && highlightMatch) {
            backgroundColor = highlightSettings.color
            backgroundResource = 0
        }
        itemView?.let { view ->
            backgroundColor?.let(view::setBackgroundColor) ?: view.setBackgroundResource(backgroundResource)
        }
        return MessageResult(
            builder = builder,
            images = images,
            imagePaint = imagePaint,
            userName = userName,
            userNameStartIndex = userNameStartIndex,
            translated = translated,
            backgroundResource = backgroundResource,
            backgroundColor = backgroundColor,
            accessibilityDescription = if (includeAccessibilityDescription) {
                accessibilityDescription(context, chatMessage, nameDisplay, builder)
            } else null,
        )
    }

    /**
     * Builds the terminal representation used only when the authoritative renderer cannot
     * recover. It has no image references, so publication never leaves a semantic row waiting
     * for a later visual upgrade.
     */
    fun prepareTerminalFailureRender(
        chatMessage: ChatMessage,
        context: Context,
        enableTimestamps: Boolean,
        timestampFormat: String?,
        firstMsgVisibility: Int,
        nameDisplay: String?,
        useReadableColors: Boolean,
        isLightTheme: Boolean,
        savedColors: HashMap<String, Int>,
        highlightSettings: ChatHighlightSettings = ChatHighlightSettings(),
    ): MessageResult {
        val builder = SpannableStringBuilder()
        val muted = getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)
        var userName: String? = null
        var userNameStartIndex: Int? = null

        fun appendMuted(value: String) {
            val start = builder.length
            builder.append(value)
            if (value.isNotEmpty()) builder.setSpan(ForegroundColorSpan(muted), start, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (enableTimestamps && chatMessage.timestamp != null) {
            runCatching { TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat) }
                .getOrNull()?.let { appendMuted("$it ") }
        }

        val rawName = chatMessage.displayName(nameDisplay)
        if (!rawName.isNullOrBlank() && chatMessage.type != ChatMessage.SYSTEM_MESSAGE) {
            userName = rawName
            userNameStartIndex = builder.length
            val color = chatMessage.color?.let { colorValue ->
                runCatching { getSavedColor(colorValue, savedColors, useReadableColors, isLightTheme) }.getOrNull()
            } ?: Color.rgb(153, 153, 153)
            builder.append(rawName)
            builder.setSpan(ForegroundColorSpan(color), userNameStartIndex, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(if (chatMessage.isAction) " " else ": ")
        }

        val content = when {
            chatMessage.type == ChatMessage.REPLY_MESSAGE -> chatMessage.reply?.message ?: chatMessage.message
            !chatMessage.message.isNullOrEmpty() -> chatMessage.message
            !chatMessage.systemMsg.isNullOrEmpty() -> chatMessage.systemMsg
            chatMessage.reward?.title != null -> chatMessage.reward?.title
            !chatMessage.fullMsg.isNullOrEmpty() -> chatMessage.fullMsg
            else -> ""
        }.orEmpty()
        appendMuted(content)

        var backgroundResource = when {
            chatMessage.isHighlightedMessage() -> R.drawable.bg_chat_highlight
            chatMessage.isWatchStreakNotice() -> R.drawable.bg_chat_watch_streak
            else -> chatMessageBackgroundResource(
                chatMessage,
                firstMsgVisibility,
                shouldHighlightLegacyChatMessage(chatMessage, highlightSettings),
            )
        }
        var backgroundColor: Int? = null
        if (backgroundResource == R.color.chatMessageMention) {
            backgroundColor = highlightSettings.color
            backgroundResource = 0
        }
        return MessageResult(
            builder = builder,
            images = ArrayList(),
            imagePaint = null,
            userName = userName,
            userNameStartIndex = userNameStartIndex,
            translated = false,
            backgroundResource = backgroundResource,
            backgroundColor = backgroundColor,
        )
    }

    class MessageResult(
        val builder: SpannableStringBuilder,
        val images: ArrayList<Image>,
        val imagePaint: NamePaint?,
        val userName: String?,
        val userNameStartIndex: Int?,
        val translated: Boolean,
        val backgroundResource: Int,
        val backgroundColor: Int? = null,
        val accessibilityDescription: String? = null,
        val resolvedImages: List<Drawable?> = emptyList(),
        val resolvedImagePaint: Drawable? = null,
    ) {
        /** Keep the parsed spans but give each holder its own mutable builder and image list. */
        fun copyForBind() = MessageResult(
            builder = SpannableStringBuilder(builder),
            images = ArrayList(images),
            imagePaint = imagePaint,
            userName = userName,
            userNameStartIndex = userNameStartIndex,
            translated = translated,
            backgroundResource = backgroundResource,
            backgroundColor = backgroundColor,
            accessibilityDescription = accessibilityDescription,
            resolvedImages = resolvedImages.map { it?.constantState?.newDrawable()?.mutate() ?: it },
            resolvedImagePaint = resolvedImagePaint?.constantState?.newDrawable()?.mutate() ?: resolvedImagePaint,
        )
    }

    /**
     * Resolves every drawable needed by a message before its render result is published.
     * Glide and Coil perform network and decode work off the main thread. A null result is a
     * settled failure, not a placeholder that may later upgrade a visible row.
     */
    suspend fun resolveChatImages(
        context: Context,
        images: List<Image>,
        imagePaint: NamePaint?,
        imageLibrary: String?,
        emoteQuality: String,
        emoteSize: Int,
        badgeSize: Int,
        inlineIconSize: Int,
    ): Pair<List<Drawable?>, Drawable?> = coroutineScope {
        val resolved = images.map { image ->
            // resolveChatImage composes the complete overlay chain. The cache identity must
            // therefore include that chain, not only the base drawable.
            val key = "${imageLibrary ?: "default"}|" +
                imageCompositionKey(image, emoteQuality, emoteSize, badgeSize, inlineIconSize)
            sharedImageResolution(key) {
                resolveChatImage(context, image, imageLibrary, emoteQuality, emoteSize, badgeSize, inlineIconSize)
            }
        }.awaitAll().map(::copyDrawableForRender)
        val paint = imagePaint?.imageUrl?.let { url ->
            sharedImageResolution("${imageLibrary ?: "default"}|name-paint:$url") {
                resolveNamePaint(context, url, imageLibrary)
            }
        }?.await()
        resolved to copyDrawableForRender(paint)
    }

    private fun sharedImageResolution(
        key: String,
        block: suspend () -> Drawable?,
    ): kotlinx.coroutines.Deferred<Drawable?> {
        imageResolutions[key]?.let { return it }
        val candidate = imageResolutionScope.async(start = CoroutineStart.LAZY) { block() }
        val existing = imageResolutions.putIfAbsent(key, candidate)
        if (existing != null) {
            candidate.cancel()
            return existing
        }
        candidate.invokeOnCompletion { imageResolutions.remove(key, candidate) }
        candidate.start()
        return candidate
    }

    private fun copyDrawableForRender(drawable: Drawable?): Drawable? =
        drawable?.constantState?.newDrawable()?.mutate() ?: drawable

    private suspend fun resolveChatImage(
        context: Context,
        image: Image,
        imageLibrary: String?,
        emoteQuality: String,
        emoteSize: Int,
        badgeSize: Int,
        inlineIconSize: Int,
    ): Drawable? {
        return try {
            val resolvedImage = if (image.localData == null && image.localDataUrl != null && image.localDataRange != null) {
                val range = image.localDataRange
                val bytes = getCachedLocalEmote(image.localDataUrl, range)
                    ?: withContext(Dispatchers.IO) {
                        readLocalEmote(context, image.localDataUrl, range).also {
                            cacheLocalEmote(image.localDataUrl, range, it)
                        }
                    }
                image.withLocalData(bytes)
            } else image
            val data = imageData(resolvedImage, emoteQuality) ?: return null
            val geometry = imageGeometry(
                resolvedImage,
                imageSizeForKind(resolvedImage.kind, emoteSize, badgeSize, inlineIconSize),
            )
            val drawable = if (imageLibrary == "0" || (imageLibrary == "1" && !resolvedImage.format.equals("webp", true))) {
                val result = context.imageLoader.execute(
                    chatImageRequest(
                        context, resolvedImage, data, geometry.widthPx, geometry.heightPx,
                        imageMemoryCacheKey(stableImageSourceKey(resolvedImage, emoteQuality), geometry.widthPx, geometry.heightPx),
                        stableImageSourceKey(resolvedImage, emoteQuality),
                    ).build(),
                )
                (result as? coil3.request.SuccessResult)?.image?.asDrawable(context.resources)
            } else {
                withContext(Dispatchers.IO) {
                    Glide.with(context)
                        .load(glideImageModel(resolvedImage, data))
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .dontAnimate()
                        .override(geometry.widthPx, geometry.heightPx)
                        .submit()
                        .get()
                }
            }
            if (drawable == null) return null
            val overlay = resolvedImage.overlayEmote?.let {
                resolveChatImage(context, it, imageLibrary, emoteQuality, emoteSize, badgeSize, inlineIconSize)
            }
            if (resolvedImage.overlayEmote != null && overlay == null) return null
            if (overlay == null) drawable else LayerDrawable(arrayOf(drawable, overlay))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveNamePaint(context: Context, url: String, imageLibrary: String?): Drawable? = try {
        if (imageLibrary == "0") {
            (context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).crossfade(false).build(),
            ) as? coil3.request.SuccessResult)?.image?.asDrawable(context.resources)
        } else {
            withContext(Dispatchers.IO) {
                Glide.with(context).load(GlideUrl(url) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) })
                    .diskCacheStrategy(DiskCacheStrategy.DATA).dontAnimate().submit().get()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun installResolvedImages(
        builder: SpannableStringBuilder,
        images: List<Image>,
        drawables: List<Drawable?>,
        imagePaint: NamePaint? = null,
        imagePaintDrawable: Drawable? = null,
        userName: String? = null,
        userNameStartIndex: Int? = null,
        backgroundColor: Int = Color.TRANSPARENT,
        emoteSize: Int = 1,
        badgeSize: Int = 1,
        inlineIconSize: Int = 1,
    ) {
        images.forEachIndexed { index, image ->
            builder.getSpans(image.start, image.end, CenteredImageSpan::class.java).forEach(builder::removeSpan)
            val geometry = imageGeometry(image, imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize))
            val drawable = drawables.getOrNull(index) ?: ColorDrawable(Color.TRANSPARENT).apply {
                setBounds(0, 0, geometry.widthPx, geometry.heightPx)
            }
            builder.setSpan(
                CenteredImageSpan(drawable, geometry.widthPx, geometry.heightPx),
                image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (imagePaint != null && imagePaintDrawable != null && !userName.isNullOrEmpty() && userNameStartIndex != null) {
            builder.getSpans(userNameStartIndex, userNameStartIndex + userName.length, NamePaintImageSpan::class.java)
                .forEach(builder::removeSpan)
            builder.setSpan(
                NamePaintImageSpan(userName, imagePaint.shadows, null, backgroundColor, imagePaintDrawable),
                userNameStartIndex, userNameStartIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun addTranslation(chatMessage: ChatMessage, builder: SpannableStringBuilder, startIndex: Int, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean): Int {
        var builderIndex = startIndex
        if (!hideErrors || !chatMessage.translationFailed) {
            val translatedMessage = "\n${chatMessage.translatedMessage}"
            builder.append(translatedMessage)
            builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + translatedMessage.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            val messageLanguage = chatMessage.messageLanguage
            if (messageLanguage != null) {
                builder.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showLanguageDownloadDialog(chatMessage, messageLanguage)
                    }

                    override fun updateDrawState(ds: TextPaint) {}
                }, builderIndex, builderIndex + translatedMessage.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builderIndex += translatedMessage.length
        }
        return builderIndex
    }

    fun installImagePlaceholders(
        builder: SpannableStringBuilder,
        images: List<Image>,
        emoteSize: Int,
        badgeSize: Int,
        inlineIconSize: Int,
        imagePaint: NamePaint? = null,
        userName: String? = null,
        userNameStartIndex: Int? = null,
        backgroundColor: Int = Color.TRANSPARENT,
    ) {
        images.forEach { image ->
            val geometry = imageGeometry(
                image,
                imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize),
            )
            val placeholder = ColorDrawable(Color.TRANSPARENT).apply {
                setBounds(0, 0, geometry.widthPx, geometry.heightPx)
            }
            builder.setSpan(
                CenteredImageSpan(placeholder, geometry.widthPx, geometry.heightPx),
                image.start,
                image.end,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (imagePaint != null && !userName.isNullOrEmpty() && userNameStartIndex != null) {
            val placeholder = ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 1, 1) }
            builder.setSpan(
                NamePaintImageSpan(userName, imagePaint.shadows, null, backgroundColor, placeholder),
                userNameStartIndex,
                userNameStartIndex + userName.length,
                SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Starts bounded chat image work without attaching it to a transient ViewHolder. */
    fun prefetchImages(
        context: Context,
        images: List<Image>,
        imageLibrary: String?,
        emoteQuality: String,
        emoteSize: Int,
        badgeSize: Int,
        inlineIconSize: Int,
        prefetchTracker: ChatImagePrefetchTracker,
    ) {
        val queued = HashSet<String>()

        fun prefetch(image: Image) {
            val targetSize = imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize)
            val geometry = imageGeometry(image, targetSize)
            val data = imageData(image, emoteQuality) ?: return
            val sourceKey = stableImageSourceKey(image, emoteQuality)
            val usesCoil = imageLibrary == "0" || (imageLibrary == "1" && !image.format.equals("webp", true))
            val requestKey = prefetchRequestKey(usesCoil, sourceKey, geometry.widthPx, geometry.heightPx)
            if (!queued.add(requestKey)) {
                image.overlayEmote?.let(::prefetch)
                return
            }
            val prefetchToken = prefetchTracker.tryStart(requestKey)
            if (prefetchToken == null) {
                image.overlayEmote?.let(::prefetch)
                return
            }
            if (usesCoil) {
                context.imageLoader.enqueue(
                    chatImageRequest(
                        context,
                        image,
                        data,
                        geometry.widthPx,
                        geometry.heightPx,
                        imageMemoryCacheKey(sourceKey, geometry.widthPx, geometry.heightPx),
                        sourceKey,
                    ).listener(object : ImageRequest.Listener {
                        override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                            prefetchTracker.markFailed(requestKey, prefetchToken)
                        }

                        override fun onSuccess(request: ImageRequest, result: coil3.request.SuccessResult) = Unit
                    }).build(),
                )
            } else {
                val model = glideImageModel(image, data)
                Glide.with(context)
                    .load(model)
                    .override(geometry.widthPx, geometry.heightPx)
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .dontAnimate()
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean,
                        ): Boolean {
                            prefetchTracker.markFailed(requestKey, prefetchToken)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>,
                            dataSource: DataSource,
                            isFirstResource: Boolean,
                        ): Boolean = false
                    })
                    .preload()
            }
            image.overlayEmote?.let(::prefetch)
        }

        images.forEach(::prefetch)
    }

    private fun getSavedColor(color: String, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean): Int {
        return synchronized(savedColors) {
            savedColors[color] ?: Color.parseColor(color).let { newColor ->
                if (useReadableColors) {
                    adaptUsernameColor(newColor, isLightTheme)
                } else {
                    newColor
                }.also { savedColors[color] = it }
            }
        }
    }

    private fun adaptUsernameColor(color: Int, isLightTheme: Boolean): Int {
        val colorArray = FloatArray(3)
        ColorUtils.colorToHSL(color, colorArray)
        if (isLightTheme) {
            val luminanceMax = 0.75f -
                    maxOf(1f - ((colorArray[0] - GREEN_HUE_DEGREES) / 100f).pow(2f), RED_HUE_DEGREES) * 0.4f
            colorArray[2] = minOf(colorArray[2], luminanceMax)
        } else {
            val distToRed = RED_HUE_DEGREES - colorArray[0]
            val distToBlue = BLUE_HUE_DEGREES - colorArray[0]
            val normDistanceToRed = distToRed - TWO_PI_DEGREES * floor((distToRed + PI_DEGREES) / TWO_PI_DEGREES)
            val normDistanceToBlue = distToBlue - TWO_PI_DEGREES * floor((distToBlue + PI_DEGREES) / TWO_PI_DEGREES)

            val luminanceMin = 0.3f +
                    maxOf((1f - (normDistanceToBlue / 40f).pow(2f)) * 0.35f, RED_HUE_DEGREES) +
                    maxOf((1f - (normDistanceToRed / 40f).pow(2f)) * 0.1f, RED_HUE_DEGREES)
            colorArray[2] = maxOf(colorArray[2], luminanceMin)
        }

        return ColorUtils.HSLToColor(colorArray)
    }

    private fun prepareEmotes(chatMessage: ChatMessage, message: String, builder: SpannableStringBuilder, startIndex: Int, images: ArrayList<Image>, imageClick: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?, useReadableColors: Boolean, isLightTheme: Boolean, enableOverlayEmotes: Boolean, useBoldNames: Boolean, loggedInUser: String?, chatUrl: String?, savedColors: HashMap<String, Int>, localTwitchEmotes: List<TwitchEmote>, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>, stvUser: STVUser?, thirdPartyEmotes: List<Emote>, cheerEmotes: List<CheerEmote>, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>, catalogIndexes: ChatCatalogIndexes): Boolean {
        var wasMentioned = false
        try {
            var builderIndex = startIndex
            val split = builder.substring(builderIndex).split(" ")
            var previousImage: Image? = null
            val twitchEmotes = chatMessage.emotes?.map {
                val realBegin = message.offsetByCodePoints(0, it.begin)
                val realEnd = if (it.begin == realBegin) {
                    it.end
                } else {
                    it.end + realBegin - it.begin
                }
                catalogIndexes.twitchEmotesById[it.id]?.let { emote ->
                    TwitchEmote(
                        id = emote.id,
                        name = emote.name,
                        localData = emote.localData,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        begin = realBegin,
                        end = realEnd,
                        setId = emote.setId,
                        ownerId = emote.ownerId
                    )
                } ?: TwitchEmote(id = it.id, begin = realBegin, end = realEnd)
            }?.sortedBy { it.begin }?.toMutableList()
            val personalEmotes = if (showPersonalEmotes) {
                stvUser?.emoteSetId?.let(catalogIndexes.personalEmotesBySet::get)
            } else null
            for (value in split) {
                if (chatMessage.bits != null) {
                    val bitsCount = value.takeLastWhile { it.isDigit() }
                    val bitsName = value.substringBeforeLast(bitsCount)
                    if (bitsCount.isNotEmpty()) {
                        val emote = synchronized(cheerEmotes) {
                            catalogIndexes.cheersByName[bitsName.lowercase()]?.lastOrNull { it.minBits <= bitsCount.toInt() }
                        }
                        if (emote != null) {
                            builder.replace(builderIndex, builderIndex + bitsName.length, ".")
                            builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (imageClick != null) {
                                builder.setSpan(object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, emote.format, emote.isAnimated, null, null, null)
                                    }

                                    override fun updateDrawState(ds: TextPaint) {}
                                }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            images.add(Image(
                                localData = emote.localData?.let { getLocalEmoteData(emote.name + emote.minBits, it, savedLocalCheerEmotes, chatUrl)?.first },
                                localDataUrl = emote.localData?.let { getLocalEmoteData(emote.name + emote.minBits, it, savedLocalCheerEmotes, chatUrl)?.second },
                                localDataRange = emote.localData?.let { getLocalEmoteData(emote.name + emote.minBits, it, savedLocalCheerEmotes, chatUrl)?.third },
                                url1x = emote.url1x,
                                url2x = emote.url2x,
                                url3x = emote.url3x,
                                url4x = emote.url4x,
                                format = emote.format,
                                isAnimated = emote.isAnimated,
                                kind = ImageKind.EMOTE,
                                start = builderIndex,
                                end = builderIndex + 1
                            ))
                            builderIndex += 1
                            if (!emote.color.isNullOrBlank()) {
                                builder.setSpan(ForegroundColorSpan(getSavedColor(emote.color, savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + bitsCount.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (!twitchEmotes.isNullOrEmpty()) {
                                val removed = bitsName.length - 1
                                twitchEmotes.forEach {
                                    it.begin -= removed
                                    it.end -= removed
                                }
                            }
                            previousImage = null
                            builderIndex += bitsCount.length + 1
                            continue
                        }
                    }
                }
                val emote = personalEmotes?.get(value)
                    ?: catalogIndexes.thirdPartyEmotesByName[value]
                if (emote != null) {
                    if (emote.isOverlayEmote && enableOverlayEmotes && previousImage != null) {
                        builder.replace(builderIndex - 1, builderIndex + value.length, "")
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.first },
                            localDataUrl = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.second },
                            localDataRange = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.third },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            sourceWidth = emote.width,
                            sourceHeight = emote.height,
                            kind = ImageKind.EMOTE,
                            thirdParty = emote.thirdParty,
                            start = previousImage.start,
                            end = previousImage.end
                        )
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length + 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage.overlayEmote = image
                        previousImage = image
                        continue
                    } else {
                        builder.replace(builderIndex, builderIndex + value.length, ".")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, emote.name, emote.format, emote.isAnimated, emote.source, emote.thirdParty, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.first },
                            localDataUrl = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.second },
                            localDataRange = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl)?.third },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            sourceWidth = emote.width,
                            sourceHeight = emote.height,
                            kind = ImageKind.EMOTE,
                            thirdParty = emote.thirdParty,
                            start = builderIndex,
                            end = builderIndex + 1
                        )
                        images.add(image)
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length - 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage = image
                        builderIndex += 2
                        continue
                    }
                }
                val twitchEmote = twitchEmotes?.firstOrNull()?.let { first ->
                    val messageIndex = builderIndex - startIndex
                    when {
                        first.begin == messageIndex -> first
                        first.begin < messageIndex -> {
                            twitchEmotes.remove(first)
                            twitchEmotes.firstOrNull()?.takeIf { it.begin == messageIndex }
                        }
                        else -> null
                    }
                }
                if (twitchEmote != null) {
                    twitchEmotes.remove(twitchEmote)
                    builder.replace(builderIndex, builderIndex + value.length, ".")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    val emote = catalogIndexes.twitchEmotesById[twitchEmote.id]?.let { emote ->
                        TwitchEmote(
                            id = emote.id,
                            name = emote.name,
                            localData = emote.localData,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            begin = builderIndex,
                            end = builderIndex + 1,
                            setId = emote.setId,
                            ownerId = emote.ownerId
                        )
                    } ?: TwitchEmote(id = twitchEmote.id)
                    if (imageClick != null) {
                        builder.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, emote.format, emote.isAnimated, null, null, emote.id)
                            }

                            override fun updateDrawState(ds: TextPaint) {}
                        }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val image = Image(
                        localData = emote.localData?.let { getLocalEmoteData(emote.id!!, it, savedLocalTwitchEmotes, chatUrl)?.first },
                        localDataUrl = emote.localData?.let { getLocalEmoteData(emote.id!!, it, savedLocalTwitchEmotes, chatUrl)?.second },
                        localDataRange = emote.localData?.let { getLocalEmoteData(emote.id!!, it, savedLocalTwitchEmotes, chatUrl)?.third },
                        url1x = emote.url1x,
                        url2x = emote.url2x,
                        url3x = emote.url3x,
                        url4x = emote.url4x,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        kind = ImageKind.EMOTE,
                        start = builderIndex,
                        end = builderIndex + 1
                    )
                    images.add(image)
                    if (twitchEmotes.isNotEmpty()) {
                        val removed = value.length - 1
                        twitchEmotes.forEach {
                            it.begin -= removed
                            it.end -= removed
                        }
                    }
                    previousImage = image
                    builderIndex += 2
                    continue
                }
                if (Patterns.WEB_URL.matcher(value).matches()) {
                    val url = if (value.startsWith("http")) value else "https://$value"
                    builder.setSpan(URLSpan(url), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    previousImage = null
                    builderIndex += value.length + 1
                    continue
                }
                if (value.startsWith('@') && useBoldNames) {
                    builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!wasMentioned &&
                    !loggedInUser.isNullOrBlank() &&
                    value.contains(loggedInUser, true) &&
                    chatMessage.userId != null &&
                    chatMessage.userLogin != loggedInUser
                ) {
                    wasMentioned = true
                }
                previousImage = null
                builderIndex += value.length + 1
            }
        } catch (e: Exception) {

        }
        return wasMentioned
    }

    private fun getLocalEmoteData(name: String, data: Pair<Long, Int>, savedLocalEmotes: MutableMap<String, ByteArray>, chatUrl: String?): Triple<ByteArray?, String?, Pair<Long, Int>?>? {
        synchronized(savedLocalEmotes) {
            savedLocalEmotes[name]?.let { return Triple(it, null, null) }
        }
        return chatUrl?.let { Triple(null, it, data) }
    }

    private fun getCachedLocalEmote(source: String, range: Pair<Long, Int>): ByteArray? = synchronized(localEmoteCache) {
        localEmoteCache[LocalEmoteKey(source, range.first, range.second)]
    }

    private fun cacheLocalEmote(source: String, range: Pair<Long, Int>, bytes: ByteArray) {
        if (bytes.size > LOCAL_EMOTE_CACHE_MAX_BYTES) return
        synchronized(localEmoteCache) {
            val key = LocalEmoteKey(source, range.first, range.second)
            localEmoteCache.remove(key)?.let { localEmoteCacheBytes -= it.size }
            localEmoteCache[key] = bytes
            localEmoteCacheBytes += bytes.size
            while (localEmoteCacheBytes > LOCAL_EMOTE_CACHE_MAX_BYTES) {
                val iterator = localEmoteCache.entries.iterator()
                if (!iterator.hasNext()) break
                localEmoteCacheBytes -= iterator.next().value.size
                iterator.remove()
            }
        }
    }

    fun loadImages(fragment: Fragment, itemView: View, images: List<Image>, imagePaint: NamePaint?, userName: String?, userNameStartIndex: Int?, backgroundColor: Int, imageLibrary: String?, builder: SpannableStringBuilder, emoteQuality: String, animateGifs: Boolean, isCurrent: () -> Boolean = { true }, shouldAnimate: () -> Boolean = { true }, requestBag: ImageRequestBag? = null, emoteSize: Int = 1, badgeSize: Int = 1, inlineIconSize: Int = 1) {
        if (imagePaint != null) {
            if (imageLibrary == "0") {
                val disposable = fragment.requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(fragment.requireContext()).apply {
                        data(imagePaint.imageUrl)
                        crossfade(false)
                        httpHeaders(NetworkHeaders.Builder().apply {
                            add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build())
                        target(
                            onSuccess = imagePaintLoaded@{
                                if (!isCurrent()) return@imagePaintLoaded
                                (it.asDrawable(fragment.resources)).let { result ->
                                    if (result is Animatable && animateGifs) {
                                        result.callback = object : Drawable.Callback {
                                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                                itemView.removeCallbacks(what)
                                            }

                                            override fun invalidateDrawable(who: Drawable) {
                                                itemView.invalidate()
                                            }

                                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                                itemView.postDelayed(what, `when`)
                                            }
                                        }
                                        if (shouldAnimate()) (result as Animatable).start()
                                    }
                                    try {
                                        builder.getSpans(
                                            userNameStartIndex!!,
                                            userNameStartIndex + userName!!.length,
                                            NamePaintImageSpan::class.java,
                                        ).firstOrNull()?.let { span ->
                                            span.backgroundColor = (itemView.background as? ColorDrawable)?.color
                                            span.drawable = result
                                        }
                                    } catch (e: IndexOutOfBoundsException) {
                                    }
                                    itemView.invalidate()
                                }
                            },
                        )
                    }.build()
                )
                requestBag?.add(disposable)
            } else {
                val requestManager = Glide.with(fragment)
                val target = object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        if (!isCurrent()) return
                        if (resource is Animatable && animateGifs) {
                            resource.callback = object : Drawable.Callback {
                                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                    itemView.removeCallbacks(what)
                                }

                                override fun invalidateDrawable(who: Drawable) {
                                    itemView.invalidate()
                                }

                                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                    itemView.postDelayed(what, `when`)
                                }
                            }
                            if (shouldAnimate()) (resource as Animatable).start()
                        }
                        try {
                            builder.getSpans(
                                userNameStartIndex!!,
                                userNameStartIndex + userName!!.length,
                                NamePaintImageSpan::class.java,
                            ).firstOrNull()?.let { span ->
                                span.backgroundColor = (itemView.background as? ColorDrawable)?.color
                                span.drawable = resource
                            }
                        } catch (e: IndexOutOfBoundsException) {
                        }
                        itemView.invalidate()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                    }
                }
                requestManager
                    .load(GlideUrl(imagePaint.imageUrl) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) })
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .dontAnimate()
                    .into(target)
                requestBag?.addClearer { requestManager.clear(target) }
            }
        }
        images.forEach { image ->
            val geometry = imageGeometry(image, imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize))
            loadImage(imageLibrary, fragment, image, emoteQuality, requestBag, imageLoaded@{ result ->
                if (!isCurrent()) return@imageLoaded
                if (result is Animatable && image.isAnimated && animateGifs) {
                    result.callback = object : Drawable.Callback {
                        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                            itemView.removeCallbacks(what)
                        }

                        override fun invalidateDrawable(who: Drawable) {
                            itemView.invalidate()
                        }

                        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                            itemView.postDelayed(what, `when`)
                        }
                    }
                    if (shouldAnimate()) (result as Animatable).start()
                }
                if (image.overlayEmote != null) {
                    val drawables = arrayOf(result)
                    nextOverlayEmote(imageLibrary, fragment, drawables, image.overlayEmote!!, image, itemView, builder, emoteQuality, animateGifs, isCurrent, shouldAnimate, requestBag, emoteSize, badgeSize, inlineIconSize)
                } else {
                    builder.getSpans(image.start, image.end, CenteredImageSpan::class.java).firstOrNull()?.imageDrawable = result
                    itemView.invalidate()
                }
            }, geometry.widthPx, geometry.heightPx)
        }
    }

    private fun nextOverlayEmote(imageLibrary: String?, fragment: Fragment, drawables: Array<Drawable>, image: Image, bottomImage: Image, itemView: View, builder: SpannableStringBuilder, emoteQuality: String, animateGifs: Boolean, isCurrent: () -> Boolean, shouldAnimate: () -> Boolean, requestBag: ImageRequestBag?, emoteSize: Int, badgeSize: Int, inlineIconSize: Int) {
        val geometry = imageGeometry(image, imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize))
        loadImage(imageLibrary, fragment, image, emoteQuality, requestBag, overlayLoaded@{ result ->
            if (!isCurrent()) return@overlayLoaded
            if (result is Animatable && image.isAnimated && animateGifs) {
                result.callback = object : Drawable.Callback {
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                        itemView.removeCallbacks(what)
                    }

                    override fun invalidateDrawable(who: Drawable) {
                        itemView.invalidate()
                    }

                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                        itemView.postDelayed(what, `when`)
                    }
                }
                if (shouldAnimate()) (result as Animatable).start()
            }
            val array = drawables.plus(result)
            if (image.overlayEmote != null) {
                nextOverlayEmote(imageLibrary, fragment, array, image.overlayEmote!!, bottomImage, itemView, builder, emoteQuality, animateGifs, isCurrent, shouldAnimate, requestBag, emoteSize, badgeSize, inlineIconSize)
            } else {
                val layer = LayerDrawable(array)
                builder.getSpans(bottomImage.start, bottomImage.end, CenteredImageSpan::class.java).firstOrNull()?.imageDrawable = layer
                itemView.invalidate()
            }
        }, geometry.widthPx, geometry.heightPx)
    }

    private fun loadImage(imageLibrary: String?, fragment: Fragment, image: Image, emoteQuality: String, requestBag: ImageRequestBag?, onLoaded: (Drawable) -> Unit, widthPx: Int? = null, heightPx: Int? = null) {
        if (image.localData == null && image.localDataUrl != null && image.localDataRange != null) {
            val context = fragment.requireContext()
            val range = image.localDataRange
            getCachedLocalEmote(image.localDataUrl, range)?.let { bytes ->
                loadImage(imageLibrary, fragment, image.withLocalData(bytes), emoteQuality, requestBag, onLoaded, widthPx, heightPx)
                return
            }
            val job = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bytes = readLocalEmote(context, image.localDataUrl, range)
                cacheLocalEmote(image.localDataUrl, range, bytes)
                withContext(Dispatchers.Main.immediate) { loadImage(imageLibrary, fragment, image.withLocalData(bytes), emoteQuality, requestBag, onLoaded, widthPx, heightPx) }
            }
            requestBag?.add(job)
            return
        }
        if (imageLibrary == "0" || (imageLibrary == "1" && !image.format.equals("webp", true))) {
            loadCoil(fragment, image, emoteQuality, requestBag, widthPx, heightPx, onLoaded)
        } else {
            loadGlide(fragment, image, emoteQuality, requestBag, widthPx, heightPx, onLoaded)
        }
    }

    private fun readLocalEmote(context: Context, url: String, range: Pair<Long, Int>): ByteArray {
        val encoded = ByteArray(range.second)
        val uri = Uri.parse(url)
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                try {
                    Os.lseek(descriptor.fileDescriptor, range.first, OsConstants.SEEK_SET)
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        input.readFully(encoded)
                    }
                    return Base64.decode(encoded, Base64.NO_WRAP or Base64.NO_PADDING)
                } catch (_: ErrnoException) {
                    descriptor.close()
                } catch (_: IOException) {
                    descriptor.close()
                }
            }
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open local emote source")
            stream.use { input ->
                input.skipFully(range.first)
                input.readFully(encoded)
            }
        } else {
            RandomAccessFile(File(url), "r").use { file ->
                file.seek(range.first)
                file.readFully(encoded)
            }
        }
        return Base64.decode(encoded, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun java.io.InputStream.skipFully(byteCount: Long) {
        var skipped = 0L
        while (skipped < byteCount) {
            val count = skip(byteCount - skipped)
            if (count <= 0) error("Unexpected end of local emote data")
            skipped += count
        }
    }

    private fun java.io.InputStream.readFully(buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val count = read(buffer, read, buffer.size - read)
            if (count < 0) error("Unexpected end of local emote data")
            read += count
        }
    }

    private fun loadCoil(fragment: Fragment, image: Image, emoteQuality: String, requestBag: ImageRequestBag?, widthPx: Int?, heightPx: Int?, onLoaded: (Drawable) -> Unit) {
        val context = fragment.requireContext()
        val data = imageData(image, emoteQuality) ?: return
        val sourceKey = stableImageSourceKey(image, emoteQuality)
        val disposable = context.imageLoader.enqueue(
            chatImageRequest(
                context,
                image,
                data,
                widthPx,
                heightPx,
                imageMemoryCacheKey(sourceKey, widthPx ?: 0, heightPx ?: 0),
                sourceKey,
            ).apply {
                target(
                    onSuccess = {
                        onLoaded((it.asDrawable(fragment.resources)))
                    },
                )
            }.build(),
        )
        requestBag?.add(disposable)
    }

    private fun loadGlide(fragment: Fragment, image: Image, emoteQuality: String, requestBag: ImageRequestBag?, widthPx: Int?, heightPx: Int?, onLoaded: (Drawable) -> Unit) {
        val data = imageData(image, emoteQuality) ?: return
        val requestManager = Glide.with(fragment)
        val target = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) { onLoaded(resource) }
            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        requestManager
            .load(glideImageModel(image, data))
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .dontAnimate()
            .override(widthPx ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL, heightPx ?: com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
            .into(target)
        requestBag?.addClearer { requestManager.clear(target) }
    }

    private fun imageData(image: Image, emoteQuality: String): Any? = image.localData ?: when (emoteQuality) {
        "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
        "3" -> image.url3x ?: image.url2x ?: image.url1x
        "2" -> image.url2x ?: image.url1x
        else -> image.url1x
    }

    internal fun glideImageModel(image: Image, data: Any): Any = if (image.thirdParty && data is String) {
        GlideUrl(data) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
    } else {
        data
    }

    internal fun byteArraySourceKey(data: ByteArray): String =
        "bytes:${data.size}:${sha256Hex(data)}"

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(hex)
    }

    private fun stableImageSourceKey(image: Image, emoteQuality: String): String {
        val localSource = image.localDataUrl?.let { url ->
            image.localDataRange?.let { range -> "local:$url:${range.first}:${range.second}" }
        }
        if (localSource != null) return localSource

        return when (val data = imageData(image, emoteQuality)) {
            is String -> "url:$data"
            is ByteArray -> byteArraySourceKey(data)
            null -> "missing"
            else -> "data:${data::class.java.name}:${data.hashCode()}"
        }
    }

    internal fun imageCompositionKey(
        image: Image,
        emoteQuality: String,
        emoteSize: Int,
        badgeSize: Int,
        inlineIconSize: Int,
    ): String {
        val geometry = imageGeometry(image, imageSizeForKind(image.kind, emoteSize, badgeSize, inlineIconSize))
        return buildString {
            append(stableImageSourceKey(image, emoteQuality))
            append('@').append(geometry.widthPx).append('x').append(geometry.heightPx)
            image.overlayEmote?.let { overlay ->
                append("|overlay=")
                append(imageCompositionKey(overlay, emoteQuality, emoteSize, badgeSize, inlineIconSize))
            }
        }
    }

    private fun imageMemoryCacheKey(sourceKey: String, widthPx: Int, heightPx: Int): String =
        "xtra:chat-image:$sourceKey:${widthPx}x$heightPx"

    private fun prefetchRequestKey(usesCoil: Boolean, sourceKey: String, widthPx: Int, heightPx: Int): String =
        "${if (usesCoil) "coil" else "glide"}:$sourceKey:${widthPx}x$heightPx"

    private fun chatImageRequest(
        context: Context,
        image: Image,
        data: Any,
        widthPx: Int?,
        heightPx: Int?,
        memoryKey: String,
        sourceKey: String,
    ): ImageRequest.Builder = ImageRequest.Builder(context).apply {
        data(data)
        if (widthPx != null && heightPx != null) size(widthPx, heightPx)
        memoryCacheKey(memoryKey)
        diskCacheKey(sourceKey)
        memoryCachePolicy(CachePolicy.ENABLED)
        diskCachePolicy(CachePolicy.ENABLED)
        networkCachePolicy(CachePolicy.ENABLED)
        crossfade(false)
        if (image.thirdParty) {
            httpHeaders(NetworkHeaders.Builder().apply {
                add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
            }.build())
        }
    }
}

internal fun ChatMessage.isHighlightedMessage(): Boolean =
    msgId.equals("highlighted-message", ignoreCase = true) ||
        msgId.equals("channel_points_highlighted", ignoreCase = true) ||
        reward?.title.equals("Highlight My Message", ignoreCase = true) ||
        reward?.title.equals("Send Highlighted Message", ignoreCase = true)

internal fun ChatMessage.effectiveNoticeId(): String? = sourceMsgId ?: msgId

internal fun ChatMessage.isWatchStreakNotice(): Boolean =
    watchStreakCount != null && (
        effectiveNoticeId().equals("viewermilestone", ignoreCase = true) ||
            effectiveNoticeId().equals("watch_streak", ignoreCase = true) ||
            effectiveNoticeId().equals("watch-streak", ignoreCase = true)
        )

internal fun ChatMessage.isSubscriptionNotice(): Boolean = effectiveNoticeId()?.lowercase() in setOf(
    "sub",
    "resub",
    "subgift",
    "submysterygift",
    "giftpaidupgrade",
    "anongiftpaidupgrade",
    "prime_paid_upgrade",
    "gift_paid_upgrade",
    "sub_gift",
    "community_sub_gift",
    "shared_chat_sub",
    "shared_chat_resub",
    "shared_chat_sub_gift",
    "shared_chat_community_sub_gift",
    "pay_it_forward",
    "shared_chat_gift_paid_upgrade",
    "shared_chat_prime_paid_upgrade",
    "shared_chat_pay_it_forward",
)

internal fun ChatMessage.isPrimeSubscriptionNotice(): Boolean = isSubscriptionNotice() && (
    isPrimeSubscription == true ||
        subscriptionPlan?.contains("prime", ignoreCase = true) == true
    )

internal fun ChatMessage.subscriptionIcon(): Int = if (isPrimeSubscriptionNotice()) {
    R.drawable.ic_chat_subscription
} else {
    R.drawable.ic_chat_subscription_gift
}

internal fun ChatMessage.displayName(nameDisplay: String?): String? = when {
    userName.isNullOrBlank() -> userLogin
    userLogin.isNullOrBlank() || userLogin.equals(userName, ignoreCase = true) -> userName
    nameDisplay == "0" -> "$userName($userLogin)"
    nameDisplay == "1" -> userName
    else -> userLogin
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).roundToInt()

internal fun chatMessageBackgroundResource(
    chatMessage: ChatMessage,
    firstMsgVisibility: Int,
    wasMentioned: Boolean = false,
): Int = when {
    chatMessage.isHighlightedMessage() -> R.drawable.bg_chat_highlight
    chatMessage.isFirst && firstMsgVisibility == 0 -> R.drawable.bg_chat_first_chatter
    chatMessage.isSubscriptionNotice() -> R.drawable.bg_chat_subscription
    chatMessage.isFirst && firstMsgVisibility == 1 -> R.color.chatMessageFirst
    chatMessage.reward?.id != null && firstMsgVisibility < 2 -> R.color.chatMessageReward
    chatMessage.systemMsg != null || chatMessage.msgId != null -> R.color.chatMessageNotice
    wasMentioned -> R.color.chatMessageMention
    else -> 0
}

private fun appendSpecialText(
    builder: SpannableStringBuilder,
    text: String,
    color: Int,
    bold: Boolean = false,
) {
    if (text.isEmpty()) return
    val start = builder.length
    builder.append(text)
    builder.setSpan(ForegroundColorSpan(color), start, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
    if (bold) {
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private fun appendSubscriptionSystemMessage(
    builder: SpannableStringBuilder,
    message: String,
    mutedColor: Int,
    userColor: Int,
    userName: String?,
) {
    val actor = userName ?: message.substringBefore(' ').takeIf { !it.equals("An", ignoreCase = true) }
    val nameEnd = actor
        ?.takeIf { message.startsWith(it, ignoreCase = true) }
        ?.length
    if (nameEnd != null) {
        appendSpecialText(builder, message.substring(0, nameEnd), userColor, bold = true)
        appendSpecialText(builder, message.substring(nameEnd), mutedColor)
    } else {
        appendSpecialText(builder, message, mutedColor)
    }
}

private fun appendSpecialIcon(
    builder: SpannableStringBuilder,
    context: Context,
    drawableId: Int,
    tint: Int,
    sizeDp: Int = 22,
) {
    val start = builder.length
    builder.append('.')
    val size = dp(context, sizeDp)
    val drawable = ContextCompat.getDrawable(context, drawableId)?.mutate() ?: return
    drawable.setTint(tint)
    drawable.setBounds(0, 0, size, size)
    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), start, start + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
    builder.setSpan(CenteredImageSpan(drawable, size, size), start, start + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
}
