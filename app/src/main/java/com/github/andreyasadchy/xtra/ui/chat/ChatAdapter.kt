package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Animatable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
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
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Random
import java.util.Collections
import java.util.IdentityHashMap

internal data class ChatRenderConfiguration(
    val revision: Int,
    val indexes: ChatAdapterUtils.ChatCatalogIndexes,
    val translateAllMessages: Boolean,
)

internal fun composeChatRenderConfiguration(
    active: ChatRenderConfiguration,
    pending: ChatRenderConfiguration?,
    revision: Int,
    indexes: ChatAdapterUtils.ChatCatalogIndexes? = null,
    translateAllMessages: Boolean? = null,
): ChatRenderConfiguration {
    val base = pending ?: active
    return base.copy(
        revision = revision,
        indexes = indexes ?: base.indexes,
        translateAllMessages = translateAllMessages ?: base.translateAllMessages,
    )
}

class ChatAdapter(
    initialMessages: List<ChatMessage>,
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
    private val rewardChatMsg: String,
    private val replyMessage: String,
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
    private val dialogBackgroundColor: Int,
    private val imageLibrary: String?,
    private val messageTextSize: Float,
    private val emoteSize: Int,
    private val badgeSize: Int,
    private val inlineIconSize: Int,
    private val emoteQuality: String,
    private val animateGifs: Boolean,
    private val enableOverlayEmotes: Boolean,
    private val translateMessage: (ChatMessage, String?) -> Unit,
    private val showLanguageDownloadDialog: (ChatMessage, String) -> Unit,
    private val channelId: String?,
    private val loggedInUser: String?,
    private val messageClickListener: ((String?) -> Unit)?,
    private val replyClickListener: (() -> Unit)?,
    private val imageClickListener: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?,
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    /** UI-owned snapshot. ChatViewModel may continue receiving messages while a fling is active. */
    private val messages = ArrayList(initialMessages)
    private val generatedStableIds = IdentityHashMap<ChatMessage, Long>()
    private var nextGeneratedStableId = Long.MIN_VALUE
    private class RenderCacheKey(
        val message: ChatMessage,
        val catalogRevision: Int,
        val translateAllMessages: Boolean,
        val translatedMessage: String?,
        val translationFailed: Boolean,
        val messageLanguage: String?,
    ) {
        // ChatMessage translation/moderation fields are mutable. Cache by object identity so a
        // mutation cannot change the hash of an entry that is already in the LRU map.
        override fun equals(other: Any?): Boolean = other is RenderCacheKey &&
            message === other.message &&
            catalogRevision == other.catalogRevision &&
            translateAllMessages == other.translateAllMessages &&
            translatedMessage == other.translatedMessage &&
            translationFailed == other.translationFailed &&
            messageLanguage == other.messageLanguage

        override fun hashCode(): Int {
            var result = System.identityHashCode(message)
            result = 31 * result + catalogRevision
            result = 31 * result + translateAllMessages.hashCode()
            result = 31 * result + (translatedMessage?.hashCode() ?: 0)
            result = 31 * result + translationFailed.hashCode()
            result = 31 * result + (messageLanguage?.hashCode() ?: 0)
            return result
        }
    }

    private data class RenderRequest(
        val message: ChatMessage,
        val cacheKey: RenderCacheKey,
        val context: android.content.Context,
        val configuration: ChatRenderConfiguration,
        val prewarmGeneration: Long?,
    )

    private val renderCache = object : LinkedHashMap<RenderCacheKey, ChatAdapterUtils.MessageResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RenderCacheKey, ChatAdapterUtils.MessageResult>?): Boolean =
            size > MAX_RENDER_CACHE_ENTRIES
    }
    private var renderScope = newRenderScope()
    private val renderJobs = HashSet<RenderCacheKey>()
    private val prewarmRenderJobs = HashSet<RenderCacheKey>()
    private val visibleRenderJobs = HashSet<RenderCacheKey>()
    private val renderWaiters = HashMap<RenderCacheKey, MutableList<CompletableDeferred<Unit>>>()
    private var visibleRenderQueue = Channel<RenderRequest>(VISIBLE_RENDER_QUEUE_CAPACITY)
    private var prewarmRenderQueue = Channel<RenderRequest>(PREWARM_QUEUE_CAPACITY)
    /** One signal represents one queued request; this keeps both workers fed during bursts. */
    private var renderSignal = Channel<Unit>(Channel.UNLIMITED)
    private var renderWorkers = emptyList<Job>()
    private val renderWorkerLimit = MutableStateFlow(MAX_RENDER_WORKERS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRenderedMessages = Collections.newSetFromMap(
        IdentityHashMap<ChatMessage, Boolean>(),
    )
    private var renderUpdatesPaused = false
    private var prewarmJob: Job? = null
    @Volatile
    private var prewarmGeneration = 0L
    private var prewarmPosted = false
    private val prewarmRunnable = Runnable {
        prewarmPosted = false
        attachedRecyclerView?.let(::scheduleVisiblePrewarm)
    }
    private var directBinding = false
    private var selectedMessage: ChatMessage? = null
    private val random = Random()
    private val userColors = HashMap<String, Int>()
    private val savedColors = HashMap<String, Int>()
    private val savedLocalTwitchEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalBadges = mutableMapOf<String, ByteArray>()
    private val savedLocalCheerEmotes = mutableMapOf<String, ByteArray>()
    private val savedLocalEmotes = mutableMapOf<String, ByteArray>()
    private val initialCatalogIndexes = ChatAdapterUtils.ChatCatalogIndexes.create(
        localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, stvUsers, stvBadges, namePaints, personalEmoteSets, cheerEmotes,
    )
    @Volatile
    private var activeConfiguration = ChatRenderConfiguration(0, initialCatalogIndexes, false)
    @Volatile
    private var pendingConfiguration: ChatRenderConfiguration? = null
    private var configurationRevisionCounter = 0
    private var configurationJob: Job? = null
    private val catalogRevision: Int
        get() = activeConfiguration.revision
    private val catalogIndexes: ChatAdapterUtils.ChatCatalogIndexes
        get() = activeConfiguration.indexes
    var translateAllMessages: Boolean
        get() = activeConfiguration.translateAllMessages
        set(value) {
            val base = pendingConfiguration ?: activeConfiguration
            if (value == base.translateAllMessages) return
            scheduleConfigurationSwitch(
                composeChatRenderConfiguration(
                    active = activeConfiguration,
                    pending = pendingConfiguration,
                    revision = nextConfigurationRevision(),
                    translateAllMessages = value,
                ),
            )
        }
    private var attachedRecyclerView: RecyclerView? = null
    private var animationsPaused = false
    private var pauseAnimationsPosted = false
    private val pendingAnimationStops = ArrayDeque<TextView>()
    private val runningAnimationTextViews = Collections.newSetFromMap(
        IdentityHashMap<TextView, Boolean>(),
    )
    private var resumeAnimationsPosted = false
    private var resumeAnimationIndex = 0
    private var renderedFlushPosted = false
    private val renderedFlushRunnable = Runnable {
        renderedFlushPosted = false
        flushRenderedMessages()
    }
    private val pauseAnimationsRunnable = object : Runnable {
        override fun run() {
            pauseAnimationsPosted = false
            if (!animationsPaused) {
                pendingAnimationStops.clear()
                return
            }
            repeat(MAX_ANIMATION_OPERATIONS_PER_FRAME) {
                pendingAnimationStops.removeFirstOrNull()?.let { textView ->
                    if (textView.isAttachedToWindow) setAnimations(textView, start = false)
                } ?: return@repeat
            }
            if (pendingAnimationStops.isNotEmpty()) {
                pauseAnimationsPosted = true
                attachedRecyclerView?.postOnAnimation(this)
            }
        }
    }
    private val resumeAnimationsRunnable = object : Runnable {
        override fun run() {
            resumeAnimationsPosted = false
            val recyclerView = attachedRecyclerView ?: return
            if (animationsPaused || resumeAnimationIndex >= recyclerView.childCount) return
            (recyclerView.getChildAt(resumeAnimationIndex) as? TextView)?.let {
                setAnimations(it, start = true)
            }
            resumeAnimationIndex++
            if (!animationsPaused && resumeAnimationIndex < recyclerView.childCount) {
                resumeAnimationsPosted = true
                recyclerView.postOnAnimation(this)
            }
        }
    }

    init {
        setHasStableIds(true)
        messages.forEach(::stableIdFor)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.imageRequests.cancel()
        val configuration = activeConfiguration
        val bindGeneration = holder.beginBind(configuration.revision)
        val chatMessage = messages.getOrNull(position) ?: return
        val cacheKey = createRenderKey(chatMessage, configuration)
        val cachedResult = cachedRender(cacheKey)
        val result = checkNotNull(cachedResult) {
            "Chat message was displayed before its render plan was prepared"
        }.copyForBind()
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
        holder.bind(chatMessage, result)
        ChatAdapterUtils.loadImages(
            fragment, holder.textView, result.images, result.imagePaint, result.userName, result.userNameStartIndex,
            backgroundColor, imageLibrary, result.builder, emoteQuality, animateGifs,
            isCurrent = { holder.isCurrentBind(bindGeneration) },
            shouldAnimate = { holder.canAnimate(bindGeneration) },
            requestBag = holder.imageRequests,
            shouldLoad = { holder.canLoadImages(bindGeneration) },
            onLoadDeferred = { holder.hasDeferredImageLoad = true },
        )
    }

    override fun getItemId(position: Int): Long = stableIdFor(messages[position])

    override fun onViewRecycled(holder: ViewHolder) {
        holder.imageRequests.cancel()
        holder.cancelBind()
        super.onViewRecycled(holder)
    }

    fun appendMessages(incoming: List<ChatMessage>, trimCount: Int) {
        val removed = trimCount.coerceAtMost(messages.size)
        if (removed > 0) {
            repeat(removed) { pendingRenderedMessages.remove(messages[it]) }
            messages.subList(0, removed).clear()
            notifyItemRangeRemoved(0, removed)
        }
        if (incoming.isNotEmpty()) {
            val start = messages.size
            messages.addAll(incoming)
            incoming.forEach(::stableIdFor)
            notifyItemRangeInserted(start, incoming.size)
        }
    }

    fun prependMessages(incoming: List<ChatMessage>) {
        if (incoming.isEmpty()) return
        messages.addAll(0, incoming)
        incoming.forEach(::stableIdFor)
        notifyItemRangeInserted(0, incoming.size)
    }

    fun removeMessages(count: Int) {
        val removed = count.coerceAtMost(messages.size)
        if (removed <= 0) return
        repeat(removed) { pendingRenderedMessages.remove(messages[it]) }
        messages.subList(0, removed).clear()
        notifyItemRangeRemoved(0, removed)
    }

    fun clearMessages() {
        if (messages.isEmpty()) return
        val removed = messages.size
        messages.clear()
        pendingRenderedMessages.clear()
        notifyItemRangeRemoved(0, removed)
    }

    fun notifyUserMessages(userId: String) {
        if (messages.none { it.userId == userId }) return
        scheduleConfigurationSwitch(
            composeChatRenderConfiguration(
                active = activeConfiguration,
                pending = pendingConfiguration,
                revision = nextConfigurationRevision(),
            ),
        )
    }

    /** Used by CombinedChatFragment, which renders one message without RecyclerView ownership. */
    fun setDirectMessage(message: ChatMessage) {
        directBinding = true
        messages.clear()
        pendingRenderedMessages.clear()
        messages += message
    }

    /** Cancel image work when this adapter is used to render into a non-RecyclerView TextView. */
    fun releaseDirectViewHolder(holder: ViewHolder) {
        holder.imageRequests.cancel()
        holder.cancelBind()
        if (animateGifs) setAnimations(holder.textView, start = false)
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
            if (!chatMessage.translationFailed) {
                ChatAdapterUtils.addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, true)
            }
            item.text = builder
        }
        // Translation is an explicit content update. Prepare the new cache entry off-main so a
        // later holder recycle never falls back to parsing inside onBindViewHolder.
        renderScope.launch { prepareForDisplay(listOf(chatMessage)) }
    }

    fun rebindMessageAfterContentUpdate(chatMessage: ChatMessage, position: Int) {
        renderScope.launch {
            prepareForDisplay(listOf(chatMessage))
            withContext(Dispatchers.Main.immediate) {
                if (messages.getOrNull(position) === chatMessage) notifyItemChanged(position)
            }
        }
    }

    fun createMessageClickedChatAdapter(): MessageClickedChatAdapter {
        return MessageClickedChatAdapter(
            messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { chatMessage -> selectedMessage = chatMessage; replyClickListener?.invoke() },
            { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, showBadges, showSTVBadges, showPersonalEmotes,
            showSystemMessageEmotes, chatUrl, fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize, inlineIconSize,
            emoteQuality, animateGifs, enableOverlayEmotes, translateAllMessages, translateMessage, showLanguageDownloadDialog, random, userColors,
            savedColors, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes, loggedInUser, selectedMessage
        )
    }

    fun createReplyClickedChatAdapter(): ReplyClickedChatAdapter {
        return ReplyClickedChatAdapter(
            messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage,
            { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, showBadges, showSTVBadges, showPersonalEmotes,
            showSystemMessageEmotes, chatUrl, fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize, inlineIconSize,
            emoteQuality, animateGifs, enableOverlayEmotes, translateAllMessages, translateMessage, showLanguageDownloadDialog, random, userColors,
            savedColors, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes, loggedInUser, selectedMessage
        )
    }

    override fun getItemCount(): Int = messages.size

    fun stableIdAt(position: Int): Long = getItemId(position)

    fun positionOfStableId(stableId: Long): Int = messages.indexOfFirst { stableIdFor(it) == stableId }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.catalogRevision < catalogRevision) {
            holder.postCatalogRefresh()
            return
        }
        if (holder.hasDeferredImageLoad) {
            holder.postDeferredImageReload()
            return
        }
        if (animateGifs && !animationsPaused) setAnimations(holder.textView, start = true)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (animateGifs) setAnimations(holder.textView, start = false)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(prewarmScrollListener)
        recyclerView.removeCallbacks(pauseAnimationsRunnable)
        recyclerView.removeCallbacks(resumeAnimationsRunnable)
        pauseAnimationsPosted = false
        pendingAnimationStops.clear()
        resumeAnimationsPosted = false
        val childCount = recyclerView.childCount
        if (animateGifs) {
            for (i in 0 until childCount) {
                setAnimations(recyclerView.getChildAt(i) as TextView, start = false)
            }
        }
        prewarmJob?.cancel()
        prewarmJob = null
        recyclerView.removeCallbacks(prewarmRunnable)
        prewarmPosted = false
        recyclerView.removeCallbacks(renderedFlushRunnable)
        renderedFlushPosted = false
        visibleRenderQueue.close()
        prewarmRenderQueue.close()
        renderSignal.close()
        renderWorkers.forEach(Job::cancel)
        renderWorkers = emptyList()
        configurationJob?.cancel()
        configurationJob = null
        pendingConfiguration = null
        renderScope.cancel()
        synchronized(renderJobs) {
            renderWaiters.values.flatten().forEach(CompletableDeferred<Unit>::cancel)
            renderWaiters.clear()
            renderJobs.clear()
            prewarmRenderJobs.clear()
            visibleRenderJobs.clear()
        }
        pendingRenderedMessages.clear()
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        directBinding = false
        ensureRenderWorkers()
        attachedRecyclerView = recyclerView
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnScrollListener(prewarmScrollListener)
        scheduleVisiblePrewarm(recyclerView)
    }

    private val prewarmScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!prewarmPosted) {
                prewarmPosted = true
                recyclerView.postDelayed(prewarmRunnable, PREWARM_DEBOUNCE_MS)
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE && !prewarmPosted) {
                prewarmPosted = true
                recyclerView.postOnAnimation(prewarmRunnable)
            }
        }
    }

    fun setAnimationsPaused(paused: Boolean) {
        if (animationsPaused == paused) return
        animationsPaused = paused
        val recyclerView = attachedRecyclerView ?: return
        recyclerView.removeCallbacks(pauseAnimationsRunnable)
        recyclerView.removeCallbacks(resumeAnimationsRunnable)
        pauseAnimationsPosted = false
        pendingAnimationStops.clear()
        resumeAnimationsPosted = false
        resumeAnimationIndex = 0
        if (paused) {
            for (i in 0 until recyclerView.childCount) {
                (recyclerView.getChildAt(i) as? TextView)?.let(pendingAnimationStops::addLast)
            }
            if (pendingAnimationStops.isNotEmpty()) {
                pauseAnimationsPosted = true
                recyclerView.postOnAnimation(pauseAnimationsRunnable)
            }
        } else {
            for (i in 0 until recyclerView.childCount) {
                (recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? ViewHolder)?.let { holder ->
                    holder.postDeferredImageReload()
                }
            }
            if (!animateGifs) return
            resumeAnimationsPosted = true
            recyclerView.postOnAnimation(resumeAnimationsRunnable)
        }
    }

    /** Keeps completed background renders from invalidating rows while the list is flinging. */
    fun setRenderUpdatesPaused(paused: Boolean) {
        if (renderUpdatesPaused == paused) return
        renderUpdatesPaused = paused
        // Keep visible work responsive while the list is moving. Release the
        // second CPU-heavy renderer only after scrolling has settled.
        renderWorkerLimit.value = if (paused) 1 else MAX_RENDER_WORKERS
        if (!paused) attachedRecyclerView?.postOnAnimation { flushRenderedMessages() }
    }

    fun notifyCatalogChanged() {
        scheduleConfigurationSwitch(
            composeChatRenderConfiguration(
                active = activeConfiguration,
                pending = pendingConfiguration,
                revision = nextConfigurationRevision(),
                indexes = ChatAdapterUtils.ChatCatalogIndexes.create(
                    localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, stvUsers, stvBadges, namePaints, personalEmoteSets, cheerEmotes,
                ),
            ),
        )
    }

    private fun scheduleConfigurationSwitch(
        configuration: ChatRenderConfiguration,
    ) {
        configurationJob?.cancel()
        prewarmGeneration++
        prewarmJob?.cancel()
        prewarmJob = null
        synchronized(renderCache) {
            renderCache.keys.removeIf { key -> key.catalogRevision != activeConfiguration.revision }
        }
        pendingConfiguration = configuration
        val initialSnapshot = messages.toList()
        configurationJob = renderScope.launch {
            prepareForDisplay(initialSnapshot, configuration)
            val currentSnapshot = withContext(Dispatchers.Main.immediate) { messages.toList() }
            prepareForDisplay(currentSnapshot, configuration)
            withContext(Dispatchers.Main.immediate) {
                if (pendingConfiguration !== configuration) return@withContext
                activeConfiguration = configuration
                pendingConfiguration = null
                configurationJob = null
                synchronized(renderCache) {
                    renderCache.keys.removeIf { key ->
                        key.catalogRevision != configuration.revision ||
                            key.translateAllMessages != configuration.translateAllMessages
                    }
                }
                visiblePositions().forEach { position ->
                    if (messages.getOrNull(position) != null) notifyItemChanged(position)
                }
            }
        }
    }

    private fun nextConfigurationRevision(): Int = ++configurationRevisionCounter

    private fun visiblePositions(): List<Int> {
        val recyclerView = attachedRecyclerView ?: return emptyList()
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return emptyList()
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        return if (first == RecyclerView.NO_POSITION || last < first) emptyList() else (first..last).toList()
    }

    private fun setAnimations(textView: TextView, start: Boolean) {
        if (start) {
            if (!runningAnimationTextViews.add(textView)) return
        } else if (!runningAnimationTextViews.remove(textView)) {
            return
        }
        var foundAnimatable = false
        val action: (Animatable) -> Unit = {
            foundAnimatable = true
            if (start) it.start() else it.stop()
        }
        (textView.text as? Spannable)?.let { view ->
            view.getSpans<ImageSpan>().forEach { span ->
                (span.drawable as? Animatable)?.let(action) ?: (span.drawable as? LayerDrawable)?.let { layers ->
                    for (i in 0 until layers.numberOfLayers) {
                        (layers.getDrawable(i) as? Animatable)?.let(action)
                    }
                }
            }
            view.getSpans<NamePaintImageSpan>().forEach { span ->
                (span.drawable as? Animatable)?.let(action)
            }
        }
        if (start && !foundAnimatable) runningAnimationTextViews.remove(textView)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView = itemView as TextView
        val imageRequests = ChatAdapterUtils.ImageRequestBag()
        private var boundMessage: ChatMessage? = null
        private var boundReplyMessage: Boolean? = null
        var hasDeferredImageLoad = false
        private var bindGeneration = 0
        private var catalogRefreshPosted = false
        var catalogRevision = 0
            private set

        init {
            textView.textSize = messageTextSize
            textView.setOnClickListener {
                if (textView.selectionStart == -1 && textView.selectionEnd == -1) {
                    val message = boundMessage ?: return@setOnClickListener
                    selectedMessage = if (message.type == ChatMessage.REPLY_MESSAGE) message.replyParent else message
                    messageClickListener?.invoke(channelId)
                }
            }
        }
        private val catalogRefreshRunnable = Runnable {
            catalogRefreshPosted = false
            val position = bindingAdapterPosition
            if (catalogRevision < this@ChatAdapter.catalogRevision && position != RecyclerView.NO_POSITION) {
                notifyItemChanged(position)
            }
        }
        fun beginBind(catalogRevision: Int): Int {
            if (animateGifs) setAnimations(textView, start = false)
            imageRequests.cancel()
            hasDeferredImageLoad = false
            itemView.removeCallbacks(catalogRefreshRunnable)
            catalogRefreshPosted = false
            this.catalogRevision = catalogRevision
            bindGeneration++
            return bindGeneration
        }

        fun isCurrentBind(generation: Int): Boolean = generation == bindGeneration

        fun canAnimate(generation: Int): Boolean = isCurrentBind(generation) && itemView.isAttachedToWindow && !animationsPaused

        fun canLoadImages(generation: Int): Boolean {
            val recyclerView = attachedRecyclerView
            return isCurrentBind(generation) &&
                itemView.isAttachedToWindow &&
                !animationsPaused &&
                (recyclerView == null || recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE)
        }

        fun postDeferredImageReload() {
            if (!hasDeferredImageLoad) return
            hasDeferredImageLoad = false
            itemView.post {
                if (itemView.isAttachedToWindow) {
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
                }
            }
        }

        fun cancelBind() {
            itemView.removeCallbacks(catalogRefreshRunnable)
            catalogRefreshPosted = false
            bindGeneration++
        }

        fun postCatalogRefresh() {
            if (catalogRefreshPosted) return
            catalogRefreshPosted = true
            itemView.post(catalogRefreshRunnable)
        }

        fun bind(chatMessage: ChatMessage, result: ChatAdapterUtils.MessageResult) {
            itemView.setBackgroundResource(result.backgroundResource)
            bindContent(chatMessage, result.builder, result.accessibilityDescription)
        }

        private fun bindContent(chatMessage: ChatMessage, formattedMessage: SpannableStringBuilder, preparedAccessibilityDescription: String? = null) {
            textView.apply {
                boundMessage = chatMessage
                text = formattedMessage
                contentDescription = preparedAccessibilityDescription ?: ChatAdapterUtils.accessibilityDescription(
                    textView.context, chatMessage, nameDisplay, formattedMessage,
                )
                val isReply = chatMessage.type == ChatMessage.REPLY_MESSAGE
                if (boundReplyMessage != isReply) {
                    boundReplyMessage = isReply
                    if (isReply) {
                        movementMethod = null
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    } else {
                        movementMethod = LinkMovementMethod.getInstance()
                        maxLines = Int.MAX_VALUE
                        ellipsize = null
                    }
                }
                TooltipCompat.setTooltipText(
                    this,
                    if (isReply) chatMessage.replyParent?.message ?: chatMessage.replyParent?.systemMsg
                    else chatMessage.message ?: chatMessage.systemMsg,
                )
            }
        }
    }

    private companion object {
        // Chat history is capped at 600 messages. Keep one complete history plus a small
        // prewarm window so scrolling to the beginning cannot re-enter an uncached bind.
        // Keep both the active and the pending revision bindable while a catalog switch is
        // prepared. Chat history is capped at 600 messages.
        const val MAX_RENDER_CACHE_ENTRIES = 1536
        const val MAX_ANIMATION_OPERATIONS_PER_FRAME = 4
        const val MAX_RENDER_UPDATES_PER_FRAME = 2
        const val MAX_RENDER_WORKERS = 2
        const val VISIBLE_RENDER_QUEUE_CAPACITY = 32
        const val PREWARM_QUEUE_CAPACITY = 64
        const val PREWARM_BEFORE = 8
        const val PREWARM_AFTER = 24
        const val PREWARM_DEBOUNCE_MS = 100L
    }

    private fun cachedRender(key: RenderCacheKey): ChatAdapterUtils.MessageResult? = synchronized(renderCache) {
        renderCache[key]
    }

    private fun stableIdFor(message: ChatMessage): Long {
        val explicitId = message.id?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitId != null) {
            var hash = -0x340d631b8c467dL
            explicitId.forEach { character ->
                hash = (hash xor character.code.toLong()) * 0x100000001b3L
            }
            return if (hash == RecyclerView.NO_ID) 0L else hash
        }
        return generatedStableIds[message] ?: nextGeneratedStableId++.also { generatedStableIds[message] = it }
    }

    private suspend fun enqueueRender(
        chatMessage: ChatMessage,
        cacheKey: RenderCacheKey,
        context: android.content.Context,
        configuration: ChatRenderConfiguration,
        prewarmGeneration: Long? = null,
    ) {
        ensureRenderWorkers()
        val isPrewarm = prewarmGeneration != null
        var shouldEnqueue = false
        synchronized(renderJobs) {
            if (isPrewarm) {
                if (renderJobs.add(cacheKey)) {
                    prewarmRenderJobs.add(cacheKey)
                    shouldEnqueue = true
                }
            } else {
                // A visible request must have its own queue entry. Removing a prewarm marker here
                // promotes the key without leaving the visible waiter dependent on an old,
                // cancellable prewarm generation.
                if (prewarmRenderJobs.remove(cacheKey)) renderJobs.remove(cacheKey)
                if (renderJobs.add(cacheKey)) shouldEnqueue = true
                visibleRenderJobs.add(cacheKey)
            }
        }
        if (!shouldEnqueue) return
        val queue = if (isPrewarm) prewarmRenderQueue else visibleRenderQueue
        try {
            queue.send(RenderRequest(chatMessage, cacheKey, context, configuration, prewarmGeneration))
            renderSignal.send(Unit)
        } catch (e: Exception) {
            synchronized(renderJobs) {
                prewarmRenderJobs.remove(cacheKey)
                if (!isPrewarm || cacheKey !in visibleRenderJobs) {
                    renderJobs.remove(cacheKey)
                    visibleRenderJobs.remove(cacheKey)
                }
            }
            if (!isPrewarm || synchronized(renderJobs) { cacheKey !in visibleRenderJobs }) {
                completeRenderWaiters(cacheKey)
            }
            throw e
        }
    }

    private fun startRenderWorkers(): List<Job> = List(MAX_RENDER_WORKERS) { workerIndex ->
        renderScope.launch {
            while (isActive) {
                if (workerIndex >= renderWorkerLimit.value) {
                    renderWorkerLimit.filter { it > workerIndex }.first()
                }
                if (renderSignal.receiveCatching().getOrNull() == null) break
                val request = visibleRenderQueue.tryReceive().getOrNull()
                    ?: prewarmRenderQueue.tryReceive().getOrNull()
                    ?: continue
                if (request.prewarmGeneration != null && request.prewarmGeneration != prewarmGeneration) {
                    synchronized(renderJobs) {
                        if (request.cacheKey !in visibleRenderJobs) {
                            renderJobs.remove(request.cacheKey)
                        }
                        prewarmRenderJobs.remove(request.cacheKey)
                    }
                    continue
                }
                if (request.prewarmGeneration != null) {
                    val shouldRender = synchronized(renderJobs) {
                        val supersededByVisible = request.cacheKey in visibleRenderJobs
                        val stillQueuedAsPrewarm = prewarmRenderJobs.remove(request.cacheKey)
                        if (!supersededByVisible && !stillQueuedAsPrewarm) {
                            renderJobs.remove(request.cacheKey)
                        }
                        !supersededByVisible && stillQueuedAsPrewarm
                    }
                    if (!shouldRender) continue
                }
                renderMessage(request)
            }
        }
    }

    private fun ensureRenderWorkers() {
        if (renderWorkers.any(Job::isActive)) return
        if (!renderScope.isActive) {
            renderScope = newRenderScope()
            visibleRenderQueue = Channel(VISIBLE_RENDER_QUEUE_CAPACITY)
            prewarmRenderQueue = Channel(PREWARM_QUEUE_CAPACITY)
            renderSignal = Channel(Channel.UNLIMITED)
        }
        renderWorkers = startRenderWorkers()
    }

    private suspend fun renderMessage(request: RenderRequest) {
        val chatMessage = request.message
        val cacheKey = request.cacheKey
        try {
            if (!isKnownConfiguration(request.configuration)) return
            if (cachedRender(cacheKey) != null) return
            val prepared = prepareMessage(
                chatMessage,
                request.context,
                null,
                request.configuration.indexes,
                request.cacheKey.translateAllMessages,
                offMain = true,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isKnownConfiguration(request.configuration) && currentRenderKey(chatMessage, request.configuration) == cacheKey) {
                    synchronized(renderCache) { renderCache[cacheKey] = prepared }
                    if (isActiveConfiguration(request.configuration) && addPendingRenderedMessage(chatMessage)) {
                        scheduleRenderedFlush()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep malformed payloads out of the UI thread as well. This fallback is only an
            // exceptional parse failure; ordinary rows are always committed from renderCache.
            withContext(Dispatchers.Main.immediate) {
                if (isKnownConfiguration(request.configuration) && currentRenderKey(chatMessage, request.configuration) == cacheKey) {
                    synchronized(renderCache) { renderCache[cacheKey] = fastFallback(chatMessage) }
                }
            }
        } finally {
            synchronized(renderJobs) {
                renderJobs.remove(cacheKey)
                prewarmRenderJobs.remove(cacheKey)
                visibleRenderJobs.remove(cacheKey)
            }
            completeRenderWaiters(cacheKey)
        }
    }

    /**
     * Prepares complete immutable render plans before messages are inserted into RecyclerView.
     * The existing bounded render workers do the expensive parsing; this method only coordinates
     * their completion and never touches a child View.
     */
    suspend fun prepareForDisplay(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        while (true) {
            val configurations = buildList {
                add(activeConfiguration)
                pendingConfiguration?.takeUnless { it === activeConfiguration }?.let(::add)
            }
            configurations.forEach { configuration ->
                prepareForDisplay(messages, configuration)
            }
            val preparedActive = configurations.first()
            if (activeConfiguration === preparedActive && pendingConfiguration == configurations.drop(1).firstOrNull()) {
                return
            }
        }
    }

    private suspend fun prepareForDisplay(messages: List<ChatMessage>, configuration: ChatRenderConfiguration) {
        if (messages.isEmpty()) return
        val context = fragment.context ?: throw CancellationException("Chat renderer is no longer attached")
        val requests = LinkedHashMap<RenderCacheKey, Pair<ChatMessage, CompletableDeferred<Unit>>>()
        messages.forEach { message ->
            val cacheKey = createRenderKey(message, configuration)
            if (cachedRender(cacheKey) == null) {
                requests.putIfAbsent(cacheKey, message to CompletableDeferred())
            }
        }
        if (requests.isEmpty()) return

        synchronized(renderJobs) {
            requests.forEach { (cacheKey, pair) ->
                if (cachedRender(cacheKey) == null) {
                    renderWaiters.getOrPut(cacheKey) { ArrayList() }.add(pair.second)
                } else {
                    pair.second.complete(Unit)
                }
            }
        }
        try {
            requests.forEach { (cacheKey, pair) ->
                if (!pair.second.isCompleted) {
                    enqueueRender(pair.first, cacheKey, context, configuration)
                }
            }
            requests.values.map { it.second }.awaitAll()
        } catch (cancellation: CancellationException) {
            synchronized(renderJobs) {
                requests.forEach { (cacheKey, pair) ->
                    renderWaiters[cacheKey]?.remove(pair.second)
                    if (renderWaiters[cacheKey].isNullOrEmpty()) renderWaiters.remove(cacheKey)
                }
            }
            throw cancellation
        }
    }

    private fun completeRenderWaiters(cacheKey: RenderCacheKey) {
        val waiters = synchronized(renderJobs) { renderWaiters.remove(cacheKey).orEmpty() }
        waiters.forEach { it.complete(Unit) }
    }

    private suspend fun enqueuePrewarmRender(
        message: ChatMessage,
        configuration: ChatRenderConfiguration,
        context: android.content.Context,
        generation: Long,
    ) {
        enqueueRender(message, createRenderKey(message, configuration), context, configuration, generation)
    }

    private fun scheduleVisiblePrewarm(recyclerView: RecyclerView) {
        recyclerView.post {
            if (attachedRecyclerView !== recyclerView) return@post
            val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()
            val context = fragment.context ?: return@post
            if (first == RecyclerView.NO_POSITION || last < first) return@post
            val start = (first - PREWARM_BEFORE).coerceAtLeast(0)
            val end = (last + PREWARM_AFTER).coerceAtMost(messages.size - 1)
            if (end < start) return@post
            val snapshot = messages.subList(start, end + 1).toList()
            val configuration = activeConfiguration
            val generation = ++prewarmGeneration
            prewarmJob?.cancel()
            prewarmJob = renderScope.launch {
                snapshot.forEach { message ->
                    enqueuePrewarmRender(message, configuration, context, generation)
                    yield()
                }
            }
        }
    }

    private fun currentRenderKey(message: ChatMessage, configuration: ChatRenderConfiguration = activeConfiguration): RenderCacheKey =
        createRenderKey(message, configuration)

    private fun createRenderKey(message: ChatMessage, configuration: ChatRenderConfiguration) = RenderCacheKey(
        message = message,
        catalogRevision = configuration.revision,
        translateAllMessages = configuration.translateAllMessages,
        translatedMessage = message.translatedMessage,
        translationFailed = message.translationFailed,
        messageLanguage = message.messageLanguage,
    )

    private fun isActiveConfiguration(configuration: ChatRenderConfiguration): Boolean =
        configuration.revision == activeConfiguration.revision &&
            configuration.indexes === activeConfiguration.indexes &&
            configuration.translateAllMessages == activeConfiguration.translateAllMessages

    private fun isKnownConfiguration(configuration: ChatRenderConfiguration): Boolean =
        isActiveConfiguration(configuration) ||
            pendingConfiguration?.let {
                configuration.revision == it.revision &&
                    configuration.indexes === it.indexes &&
                    configuration.translateAllMessages == it.translateAllMessages
            } == true

    private fun addPendingRenderedMessage(message: ChatMessage): Boolean {
        val recyclerView = attachedRecyclerView ?: return false
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return false
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible < firstVisible) return false
        val visible = (firstVisible..lastVisible).any { position ->
            messages.getOrNull(position) === message &&
                recyclerView.findViewHolderForAdapterPosition(position) != null
        }
        if (!visible) return false
        return pendingRenderedMessages.add(message)
    }

    private fun scheduleRenderedFlush() {
        val recyclerView = attachedRecyclerView ?: return
        if (renderedFlushPosted || renderUpdatesPaused || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        renderedFlushPosted = true
        recyclerView.postOnAnimation(renderedFlushRunnable)
    }

    private fun flushRenderedMessages() {
        val recyclerView = attachedRecyclerView ?: return
        if (renderUpdatesPaused || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        if (recyclerView.isComputingLayout) {
            recyclerView.postOnAnimation { flushRenderedMessages() }
            return
        }
        if (pendingRenderedMessages.isEmpty()) return
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible < firstVisible) return
        var applied = 0
        var hasMoreVisiblePending = false
        for (position in firstVisible..lastVisible) {
            val message = messages.getOrNull(position) ?: continue
            if (!pendingRenderedMessages.contains(message)) continue
            if (recyclerView.findViewHolderForAdapterPosition(position) == null) {
                pendingRenderedMessages.remove(message)
            } else if (applied < MAX_RENDER_UPDATES_PER_FRAME) {
                pendingRenderedMessages.remove(message)
                notifyItemChanged(position)
                applied++
            } else {
                hasMoreVisiblePending = true
            }
        }
        // Off-screen prewarm completions only need to remain in renderCache. Do
        // not keep posting at display refresh rate for rows that cannot update.
        if (!hasMoreVisiblePending) pendingRenderedMessages.clear()
        if (hasMoreVisiblePending) scheduleRenderedFlush()
    }

    private fun newRenderScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(MAX_RENDER_WORKERS),
    )

    private fun prepareMessage(
        chatMessage: ChatMessage,
        context: android.content.Context,
        itemView: View?,
        indexes: ChatAdapterUtils.ChatCatalogIndexes,
        translateAllMessages: Boolean,
        offMain: Boolean,
    ): ChatAdapterUtils.MessageResult {
        val deferredTranslate: (ChatMessage, String?) -> Unit = { message, language ->
            if (offMain) {
                mainHandler.post {
                    if (attachedRecyclerView != null && fragment.isAdded) translateMessage(message, language)
                }
            } else {
                translateMessage(message, language)
            }
        }
        val deferredLanguageDialog: (ChatMessage, String) -> Unit = { message, language ->
            if (offMain) {
                mainHandler.post {
                    if (attachedRecyclerView != null && fragment.isAdded) showLanguageDownloadDialog(message, language)
                }
            } else {
                showLanguageDownloadDialog(message, language)
            }
        }
        return ChatAdapterUtils.prepareChatMessage(
            chatMessage, context, itemView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg,
            redeemedChatMsg, redeemedNoMsg, rewardChatMsg, replyMessage, null, useRandomColors, random, useReadableColors, isLightTheme,
            nameDisplay, useBoldNames, showNamePaints, namePaints, showBadges, showSTVBadges, stvBadges, showPersonalEmotes, personalEmoteSets, stvUsers,
            enableOverlayEmotes, showSystemMessageEmotes, loggedInUser, chatUrl, userColors, savedColors, translateAllMessages,
            deferredTranslate, deferredLanguageDialog, true, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes,
            savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes,
            catalogIndexes = indexes, includeAccessibilityDescription = true,
        )
    }

    private fun fastFallback(chatMessage: ChatMessage): ChatAdapterUtils.MessageResult {
        val displayName = when {
            chatMessage.userName.isNullOrBlank() -> null
            chatMessage.userLogin.isNullOrBlank() || chatMessage.userLogin.equals(chatMessage.userName, true) -> chatMessage.userName
            nameDisplay == "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
            nameDisplay == "1" -> chatMessage.userName
            else -> chatMessage.userLogin
        }
        val builder = SpannableStringBuilder()
        if (chatMessage.type == ChatMessage.REPLY_MESSAGE) {
            val replyName = chatMessage.reply?.userName ?: chatMessage.reply?.userLogin
            builder.append(replyMessage.format(replyName, ""))
            builder.append(chatMessage.reply?.message.orEmpty())
        } else {
            chatMessage.systemMsg?.takeIf { it.isNotBlank() }?.let {
                builder.append(it)
                builder.append('\n')
            }
            displayName?.let { builder.append(it).append(if (chatMessage.isAction) " " else ": ") }
            builder.append(chatMessage.message ?: chatMessage.reward?.title.orEmpty())
        }
        val backgroundResource = when {
            chatMessage.isFirst && firstMsgVisibility < 2 -> R.color.chatMessageFirst
            chatMessage.reward?.id != null && firstMsgVisibility < 2 -> R.color.chatMessageReward
            chatMessage.systemMsg != null || chatMessage.msgId != null -> R.color.chatMessageNotice
            else -> 0
        }
        return ChatAdapterUtils.MessageResult(builder, arrayListOf(), null, displayName, null, false, backgroundResource)
    }
}
