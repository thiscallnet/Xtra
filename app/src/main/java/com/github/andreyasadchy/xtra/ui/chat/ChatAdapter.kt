package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
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
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.ImageKind
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import com.github.andreyasadchy.xtra.util.chat.setChatMessageBackground
import com.github.andreyasadchy.xtra.util.chat.displayName
import com.github.andreyasadchy.xtra.util.chat.isHighlightedMessage
import com.github.andreyasadchy.xtra.util.chat.isWatchStreakNotice
import com.github.andreyasadchy.xtra.util.chat.isSubscriptionNotice
import com.github.andreyasadchy.xtra.util.chat.chatMessageBackgroundResource
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Random
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

private sealed interface ConfigurationSwitchResult {
    data class NeedsPreparation(val messages: List<ChatMessage>) : ConfigurationSwitchResult
    data object Committed : ConfigurationSwitchResult
    data object Aborted : ConfigurationSwitchResult
}

private class StaleRenderConfigurationException : CancellationException(
    "Render configuration is no longer current",
)

internal data class ChatRenderConfiguration(
    val revision: Int,
    val indexes: ChatAdapterUtils.ChatCatalogIndexes,
    val translateAllMessages: Boolean,
    val highlightSettings: ChatHighlightSettings = ChatHighlightSettings(),
)

internal enum class ChatPublicationKind { APPEND, PREPEND, REPLACE }

internal data class TerminalAppendEntry<T>(
    val value: T,
    val ready: Boolean,
    val trimBeforePublish: Int = 0,
)

internal data class AppendPublication<T>(
    val removed: Int,
    val inserted: List<T>,
)

internal fun <T> applyTerminalAppend(
    visible: MutableList<T>,
    entries: List<TerminalAppendEntry<T>>,
): AppendPublication<T> {
    val removed = entries.sumOf { it.trimBeforePublish }.coerceAtMost(visible.size)
    if (removed > 0) visible.subList(0, removed).clear()
    val inserted = entries.filter { it.ready }.map { it.value }
    visible.addAll(inserted)
    return AppendPublication(removed, inserted)
}

internal fun hasPendingPublications(
    pendingReplacement: Boolean,
    pendingPrepends: Boolean,
    pendingAppends: Boolean,
): Boolean = pendingReplacement || pendingPrepends || pendingAppends

internal fun applyNamePaintBackground(builder: Spannable, background: Drawable?) {
    val color = (background as? ColorDrawable)?.color
    builder.getSpans<NamePaintImageSpan>().forEach { it.backgroundColor = color }
}

internal fun composeChatRenderConfiguration(
    active: ChatRenderConfiguration,
    pending: ChatRenderConfiguration?,
    revision: Int,
    indexes: ChatAdapterUtils.ChatCatalogIndexes? = null,
    translateAllMessages: Boolean? = null,
    highlightSettings: ChatHighlightSettings? = null,
): ChatRenderConfiguration {
    val base = pending ?: active
    return base.copy(
        revision = revision,
        indexes = indexes ?: base.indexes,
        translateAllMessages = translateAllMessages ?: base.translateAllMessages,
        highlightSettings = highlightSettings ?: base.highlightSettings,
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
    private val profilePopoutGesture: ChatProfilePopoutGesture = ChatProfilePopoutGesture.TAP,
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    internal var onMessagesPublished: ((ChatPublicationKind, Boolean) -> Unit)? = null

    /** UI-owned snapshot. ChatViewModel may continue receiving messages while a fling is active. */
    private val messages = ArrayList(initialMessages)
    /** Entries in this queue are not part of the RecyclerView dataset yet. */
    private val publicationQueue = ChatPublicationQueue<ChatMessage>()
    private val inFlightDisplayEntries = Collections.newSetFromMap(IdentityHashMap<PublicationEntry<ChatMessage>, Boolean>())
    /** The key currently promoted for each visible message. Mutable message fields may change. */
    private val readyRenderKeys = IdentityHashMap<ChatMessage, RenderCacheKey>()
    /** Direct Combined Chat rows have an independent lifecycle from RecyclerView dataset rows. */
    private val directReadyRenderKeys = IdentityHashMap<ChatMessage, RenderCacheKey>()
    private val directReadyOrder = ArrayDeque<ChatMessage>()
    private var renderGeneration = 0L
    private val generatedStableIds = IdentityHashMap<ChatMessage, Long>()
    private var nextGeneratedStableId = Long.MIN_VALUE
    internal class RenderCacheKey(
        val message: ChatMessage,
        val catalogRevision: Int,
        val translateAllMessages: Boolean,
        val translatedMessage: String?,
        val translationFailed: Boolean,
        val messageLanguage: String?,
        val clipPreviews: List<ChatClipPreview?> = emptyList(),
        val highlightSettings: ChatHighlightSettings = ChatHighlightSettings(),
    ) {
        // ChatMessage translation/moderation fields are mutable. Cache by object identity so a
        // mutation cannot change the hash of an entry that is already in the LRU map.
        override fun equals(other: Any?): Boolean = other is RenderCacheKey &&
            message === other.message &&
            catalogRevision == other.catalogRevision &&
            translateAllMessages == other.translateAllMessages &&
            translatedMessage == other.translatedMessage &&
            translationFailed == other.translationFailed &&
            messageLanguage == other.messageLanguage &&
            clipPreviews == other.clipPreviews &&
            highlightSettings == other.highlightSettings

        override fun hashCode(): Int {
            var result = System.identityHashCode(message)
            result = 31 * result + catalogRevision
            result = 31 * result + translateAllMessages.hashCode()
            result = 31 * result + (translatedMessage?.hashCode() ?: 0)
            result = 31 * result + translationFailed.hashCode()
            result = 31 * result + (messageLanguage?.hashCode() ?: 0)
            result = 31 * result + clipPreviews.hashCode()
            result = 31 * result + highlightSettings.hashCode()
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

    private val renderCache = LinkedHashMap<RenderCacheKey, ChatAdapterUtils.MessageResult>(256, 0.75f, true)

    /**
     * Removes stale entries once the cache exceeds its target. Must be called
     * while holding `synchronized(renderCache)` on the main thread, matching
     * every other renderCache mutation.
     */
    private fun trimRenderCache() {
        if (renderCache.size <= MAX_RENDER_CACHE_ENTRIES) {
            return
        }
        val currentMessages =
            Collections.newSetFromMap(
                IdentityHashMap<ChatMessage, Boolean>()
            )
        currentMessages.addAll(messages)
        val iterator = renderCache.entries.iterator()
        while (
            renderCache.size > MAX_RENDER_CACHE_ENTRIES &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            if (entry.key.message !in currentMessages) {
                iterator.remove()
            }
        }
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
    private val imagePrefetchTracker = ChatAdapterUtils.ChatImagePrefetchTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private var activeConfiguration = ChatRenderConfiguration(
        revision = 0,
        indexes = initialCatalogIndexes,
        translateAllMessages = false,
        highlightSettings = resolveChatHighlightSettings(fragment.requireContext()),
    )
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

    fun refreshChatHighlightSettings() {
        val next = resolveChatHighlightSettings(fragment.requireContext())
        val base = pendingConfiguration ?: activeConfiguration
        if (next == base.highlightSettings) return
        scheduleConfigurationSwitch(
            composeChatRenderConfiguration(
                active = activeConfiguration,
                pending = pendingConfiguration,
                revision = nextConfigurationRevision(),
                highlightSettings = next,
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
        val configuration = activeConfiguration
        val chatMessage = messages.getOrNull(position) ?: return
        val promotedKeys = if (directBinding) directReadyRenderKeys else readyRenderKeys
        val cacheKey = checkNotNull(promotedKeys[chatMessage]) {
            "Visible chat item has no promoted render key"
        }
        if (holder.isAlreadyBoundTo(chatMessage, cacheKey)) return
        val bindGeneration = holder.beginBind(configuration.revision)
        // The dataset is a READY-only dataset. A cache miss is an internal pipeline bug.
        val result = requireReadyRender(cachedRender(cacheKey))
        ChatAdapterUtils.installResolvedImages(
            result.builder, result.images, result.resolvedImages,
            result.imagePaint, result.resolvedImagePaint,
            result.userName, result.userNameStartIndex, backgroundColor,
            emoteSize, badgeSize, inlineIconSize,
        )
        holder.bind(chatMessage, cacheKey, result)
        // A failed clip request stays retryable: revisiting the row re-arms observation.
        clipLinksOf(chatMessage.message).forEach { ensureClipSlugObserved(it.slug) }
        if (animateGifs && holder.canAnimate(bindGeneration)) {
            setAnimations(holder.textView, start = true)
        }
        check(holder.isCurrentBind(bindGeneration))
    }

    override fun getItemId(position: Int): Long = stableIdFor(messages[position])

    override fun onViewRecycled(holder: ViewHolder) {
        holder.imageRequests.cancel()
        if (animateGifs) setAnimations(holder.textView, start = false)
        holder.detachDrawables()
        holder.cancelBind()
        super.onViewRecycled(holder)
    }

    fun appendMessages(
        incoming: List<ChatMessage>,
        trimCount: Int,
    ) {
        if (incoming.isEmpty()) return
        queueForDisplay(incoming, trimCount)
    }

    fun replaceMessages(
        replacement: List<ChatMessage>,
    ) {
        renderGeneration++
        // A snapshot supersedes every mutation staged before it. Their jobs will observe the
        // generation change and become non-publishable; clearing the queues prevents an old
        // Preparing entry from blocking the new snapshot or later live messages.
        inFlightDisplayEntries.clear()
        publicationQueue.clear()
        val snapshot = replacement
        if (snapshot.isEmpty()) {
            readyRenderKeys.clear()
            if (messages.isNotEmpty()) {
                val removed = messages.size
                messages.clear()
                notifyItemRangeRemoved(0, removed)
            }
            onMessagesPublished?.invoke(ChatPublicationKind.REPLACE, hasPendingPublications())
            return
        }
        val generation = renderGeneration
        val entries = snapshot.map { message ->
            stableIdFor(message)
            PublicationEntry(message, generation)
        }
        publicationQueue.beginReplacement(entries)
        entries.forEach {
            inFlightDisplayEntries.add(it)
            scheduleDisplayEntry(it, activeConfiguration, generation)
        }
    }

    fun prependMessages(incoming: List<ChatMessage>) {
        if (incoming.isEmpty()) return
        val configuration = activeConfiguration
        val entries = incoming.map { message -> PublicationEntry(message, renderGeneration) }
        // Enqueue the barrier before starting work. A very fast completion must still observe
        // that this history batch is ahead of every later append.
        publicationQueue.enqueuePrepend(entries)
        entries.forEach {
            stableIdFor(it.value)
            inFlightDisplayEntries.add(it)
            scheduleDisplayEntry(it, configuration, renderGeneration)
        }
        // History is inserted as one complete batch once every entry is terminal. The existing
        // viewport anchor is restored by ChatFragment from the publication callback.
    }

    fun removeMessages(count: Int) {
        val removed = count.coerceAtMost(messages.size)
        if (removed <= 0) return
        messages.take(removed).forEach(readyRenderKeys::remove)
        messages.subList(0, removed).clear()
        notifyItemRangeRemoved(0, removed)
    }

    fun clearMessages() {
        renderGeneration++
        inFlightDisplayEntries.clear()
        publicationQueue.clear()
        readyRenderKeys.clear()
        directReadyRenderKeys.clear()
        directReadyOrder.clear()
        if (messages.isEmpty()) return
        val removed = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, removed)
    }

    /**
     * Render work may finish in any order, but only the contiguous ready prefix is published.
     * RecyclerView therefore never receives a message whose complete render is still pending.
     */
    private fun queueForDisplay(incoming: List<ChatMessage>, trimCount: Int) {
        val generation = renderGeneration
        incoming.forEachIndexed { index, message ->
            val entry = PublicationEntry(
                value = message,
                generation = generation,
                trimBeforePublish = if (index == 0) trimCount else 0,
            )
            publicationQueue.enqueueAppend(listOf(entry))
            stableIdFor(message)
            inFlightDisplayEntries.add(entry)
            scheduleDisplayEntry(entry, activeConfiguration, generation)
        }
    }

    private fun publishReadyPrefix() {
        while (true) {
            val entries = publicationQueue.takeReadyAppendSegment() ?: return
            entries.forEach(inFlightDisplayEntries::remove)
            val terminal = entries.map { entry ->
                TerminalAppendEntry(
                    value = entry.value,
                    ready = entry.state == PublicationState.READY,
                    trimBeforePublish = entry.trimBeforePublish,
                )
            }

            val logicalTrim = terminal.sumOf { it.trimBeforePublish }
            val rowsToRemove = adapterRowsToRemoveForTrim(messages, logicalTrim)
            val removedMessages = messages.take(rowsToRemove)
            val publication = applyTerminalAppend(
                messages,
                terminal.mapIndexed { index, entry ->
                    entry.copy(trimBeforePublish = if (index == 0) rowsToRemove else 0)
                },
            )
            removedMessages.forEach(readyRenderKeys::remove)
            publication.inserted.forEach { message -> promoteReadyRender(message, activeConfiguration) }
            if (publication.removed > 0) {
                notifyItemRangeRemoved(0, publication.removed)
            }
            if (publication.inserted.isNotEmpty()) {
                notifyItemRangeInserted(messages.size - publication.inserted.size, publication.inserted.size)
            }
            onMessagesPublished?.invoke(ChatPublicationKind.APPEND, hasPendingPublications())
        }
    }

    private fun scheduleDisplayEntry(
        entry: PublicationEntry<ChatMessage>,
        configuration: ChatRenderConfiguration,
        generation: Long,
    ) {
        val preparationToken = ++entry.preparationToken
        entry.state = PublicationState.PREPARING
        renderScope.launch {
            try {
                val preparedKey = prepareCurrentRender(entry.value, configuration)
                withContext(Dispatchers.Main.immediate) {
                    if (entry.preparationToken != preparationToken || entry.generation != generation || generation != renderGeneration ||
                        pendingConfiguration != null || !isActiveConfiguration(configuration)
                    ) return@withContext
                    val currentKey = createRenderKey(entry.value, configuration)
                    if (!renderRequestCanBecomeReady(preparedKey, currentKey, cachedRender(currentKey) != null)) {
                        entry.state = PublicationState.QUEUED
                        scheduleDisplayEntry(entry, configuration, generation)
                        return@withContext
                    }
                    entry.state = PublicationState.READY
                    if (publicationQueue.isReplacementEntry(entry)) maybePublishReplacement()
                    maybePublishPrepends()
                    publishReadyPrefix()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val failedKey = createRenderKey(entry.value, configuration)
                val terminal = runCatching {
                    ChatAdapterUtils.prepareTerminalFailureRender(
                        chatMessage = entry.value,
                        context = fragment.requireContext(),
                        enableTimestamps = enableTimestamps,
                        timestampFormat = timestampFormat,
                        firstMsgVisibility = firstMsgVisibility,
                        nameDisplay = nameDisplay,
                        useReadableColors = useReadableColors,
                        isLightTheme = isLightTheme,
                        savedColors = savedColors,
                        highlightSettings = configuration.highlightSettings,
                    )
                }.getOrElse { prepareLastResortTerminalFailureRender(entry.value, configuration.highlightSettings) }
                withContext(Dispatchers.Main.immediate) {
                    if (entry.preparationToken == preparationToken && entry.generation == generation && generation == renderGeneration && pendingConfiguration == null) {
                        val currentKey = createRenderKey(entry.value, configuration)
                        if (currentKey == failedKey && cachedRender(currentKey) == null) {
                            // This catch can also cover queue/worker failures before renderMessage
                            // gets a chance to install its terminal result. Finish the same
                            // exceptional path here instead of retrying forever at the queue head.
                            synchronized(renderCache) { renderCache[currentKey] = terminal; trimRenderCache() }
                        } else if (currentKey != failedKey) {
                            entry.state = PublicationState.QUEUED
                            scheduleDisplayEntry(entry, configuration, generation)
                            return@withContext
                        }
                        entry.state = PublicationState.READY
                        if (publicationQueue.isReplacementEntry(entry)) maybePublishReplacement()
                        maybePublishPrepends()
                        publishReadyPrefix()
                    }
                }
            }
        }
    }

    private fun maybePublishReplacement() {
        val replacement = publicationQueue.takeReadyReplacement() ?: return
        val published = replacement.map { entry ->
            check(entry.state == PublicationState.READY) { "Replacement entry is not ready" }
            entry.value
        }
        replacement.forEach(inFlightDisplayEntries::remove)
        messages.clear()
        readyRenderKeys.clear()
        messages.addAll(published)
        published.forEach { message -> promoteReadyRender(message, activeConfiguration) }
        notifyDataSetChanged()
        onMessagesPublished?.invoke(ChatPublicationKind.REPLACE, hasPendingPublications())
        // A prepend may have completed while the replacement was still preparing. Re-enter the
        // next barrier explicitly so that its completion is not stranded behind the replacement.
        maybePublishPrepends()
        publishReadyPrefix()
    }

    private fun maybePublishPrepends() {
        while (true) {
            val entries = publicationQueue.takeReadyPrepend() ?: return
            entries.forEach(inFlightDisplayEntries::remove)
            val published = entries.map { entry ->
                check(entry.state == PublicationState.READY) { "Prepend entry is not ready" }
                entry.value
            }
            messages.addAll(0, published)
            published.forEach { message -> promoteReadyRender(message, activeConfiguration) }
            notifyItemRangeInserted(0, published.size)
            onMessagesPublished?.invoke(ChatPublicationKind.PREPEND, hasPendingPublications())
            publishReadyPrefix()
        }
    }

    private fun hasPendingPublications(): Boolean = hasPendingPublications(
        pendingReplacement = publicationQueue.replacementEntries != null,
        pendingPrepends = publicationQueue.hasPendingPrepends,
        pendingAppends = publicationQueue.hasPendingAppends,
    )

    private fun promoteReadyRender(message: ChatMessage, configuration: ChatRenderConfiguration) {
        val key = createRenderKey(message, configuration)
        check(cachedRender(key) != null) { "Published chat message has no complete render" }
        readyRenderKeys[message] = key
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
        renderGeneration++
        inFlightDisplayEntries.clear()
        publicationQueue.clear()
        messages.clear()
        messages += message
    }

    /** Direct/combined chat uses the same READY handoff, without entering RecyclerView. */
    suspend fun prepareDirectMessage(message: ChatMessage) {
        while (true) {
            try {
                val configuration = withContext(Dispatchers.Main.immediate) {
                    pendingConfiguration ?: activeConfiguration
                }
                val key = prepareCurrentRender(message, configuration)
                val promoted = withContext(Dispatchers.Main.immediate) {
                    check(cachedRender(key) != null) { "Direct chat message has no complete render" }
                    if (!isActiveConfiguration(configuration)) {
                        if (pendingConfiguration === configuration) {
                            return@withContext false
                        }
                        throw StaleRenderConfigurationException()
                    }
                    directReadyRenderKeys[message] = key
                    directReadyOrder.remove(message)
                    directReadyOrder.addLast(message)
                    while (directReadyOrder.size > MAX_DIRECT_READY_KEYS) {
                        directReadyRenderKeys.remove(directReadyOrder.removeFirst())
                    }
                    true
                }
                if (promoted) return
                yield()
            } catch (_: StaleRenderConfigurationException) {
                // The direct Combined Chat request follows the newly authoritative configuration.
            }
        }
    }

    /** Cancel image work when this adapter is used to render into a non-RecyclerView TextView. */
    fun releaseDirectViewHolder(holder: ViewHolder) {
        holder.imageRequests.cancel()
        holder.cancelBind()
        detachDirectViewHolder(holder)
    }

    /** Forwards Combined Chat's attachment lifecycle without starting any render work. */
    fun attachDirectViewHolder(holder: ViewHolder) {
        holder.reattachDrawables()
        if (animateGifs && !animationsPaused) setAnimations(holder.textView, start = true)
    }

    fun detachDirectViewHolder(holder: ViewHolder) {
        if (animateGifs) setAnimations(holder.textView, start = false)
        holder.detachDrawables()
    }

    /**
     * Re-renders a mutable message without exposing its new key until the complete result exists.
     * Staged messages remain outside RecyclerView and simply converge in scheduleDisplayEntry.
     */
    fun updateMessageContent(chatMessage: ChatMessage) {
        inFlightDisplayEntries.firstOrNull { it.value === chatMessage }?.let { entry ->
            entry.state = PublicationState.QUEUED
            scheduleDisplayEntry(entry, pendingConfiguration ?: activeConfiguration, entry.generation)
            return
        }
        val position = positionOfMessage(chatMessage)
        if (position == RecyclerView.NO_POSITION) return
        renderScope.launch {
            while (isActive) {
                try {
                    val configuration = withContext(Dispatchers.Main.immediate) {
                        pendingConfiguration ?: activeConfiguration
                    }
                    prepareCurrentRender(chatMessage, configuration)
                    withContext(Dispatchers.Main.immediate) {
                        val currentPosition = positionOfMessage(chatMessage)
                        val currentKey = createRenderKey(chatMessage, configuration)
                        if (pendingConfiguration === configuration) {
                            // The cache is useful to the atomic configuration commit, but its
                            // key must not become bindable before that configuration is active.
                            return@withContext
                        }
                        if (!isActiveConfiguration(configuration)) {
                            throw StaleRenderConfigurationException()
                        }
                        if (currentPosition != RecyclerView.NO_POSITION && cachedRender(currentKey) != null) {
                            readyRenderKeys[chatMessage] = currentKey
                            notifyItemChanged(currentPosition)
                        }
                    }
                    return@launch
                } catch (_: StaleRenderConfigurationException) {
                    // Retry against the current configuration rather than losing the update.
                }
            }
        }
    }

    fun createMessageClickedChatAdapter(
        sourceMessages: List<ChatMessage>? = null,
        selectedMessageOverride: ChatMessage? = selectedMessage,
        v2Rows: List<ChatRowUiModel>? = null,
        v2Assets: ChatAssetRepository? = null,
        v2EmoteClick: ((ChatEmoteInteraction) -> Unit)? = null,
        v2GifClick: ((ChatGifInteraction) -> Unit)? = null,
    ): MessageClickedChatAdapter {
        return MessageClickedChatAdapter(
            sourceMessages ?: messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, replyMessage,
            { chatMessage -> selectedMessage = chatMessage; replyClickListener?.invoke() },
            { url, name, format, isAnimated, source, thirdParty, emoteId -> imageClickListener?.invoke(url, name, format, isAnimated, source, thirdParty, emoteId) },
            useRandomColors, useReadableColors, isLightTheme, nameDisplay, useBoldNames, showNamePaints, showBadges, showSTVBadges, showPersonalEmotes,
            showSystemMessageEmotes, chatUrl, fragment, dialogBackgroundColor, imageLibrary, messageTextSize, emoteSize, badgeSize, inlineIconSize,
            emoteQuality, animateGifs, enableOverlayEmotes, translateAllMessages, translateMessage, showLanguageDownloadDialog, random, userColors,
            savedColors, savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes, loggedInUser, selectedMessageOverride,
            v2Rows, v2Assets, v2EmoteClick, v2GifClick,
        )
    }

    fun createReplyClickedChatAdapter(): ReplyClickedChatAdapter {
        return ReplyClickedChatAdapter(
            messages, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes, namePaints, stvBadges, personalEmoteSets,
            stvUsers, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg, redeemedChatMsg, redeemedNoMsg, replyMessage,
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

    fun positionOfMessage(message: ChatMessage): Int = identityPosition(messages, message)

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.reattachDrawables()
        if (holder.catalogRevision < catalogRevision) {
            holder.postCatalogRefresh()
            return
        }
        if (animateGifs && !animationsPaused) setAnimations(holder.textView, start = true)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (animateGifs) setAnimations(holder.textView, start = false)
        holder.detachDrawables()
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
        clearClipPreviewObservers()
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
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        directBinding = false
        ensureRenderWorkers()
        attachedRecyclerView = recyclerView
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnScrollListener(prewarmScrollListener)
        // RecyclerView may detach and later reattach the same adapter. Render jobs are
        // deliberately cancelled on detach, so make every still-pending entry runnable again.
        val generation = renderGeneration
        inFlightDisplayEntries.toList().forEach { entry ->
            entry.generation = generation
            entry.state = PublicationState.QUEUED
            scheduleDisplayEntry(entry, activeConfiguration, generation)
        }
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
            if (!prewarmPosted) {
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
            if (!animateGifs) return
            resumeAnimationsPosted = true
            recyclerView.postOnAnimation(resumeAnimationsRunnable)
        }
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
        pendingConfiguration = configuration
        configurationJob = renderScope.launch {
            while (true) {
                val result = withContext(Dispatchers.Main.immediate) {
                    if (pendingConfiguration !== configuration) {
                        ConfigurationSwitchResult.Aborted
                    } else {
                        val currentMessages = messages.toList()
                        val missing = currentMessages.filter { message ->
                            cachedRender(createRenderKey(message, configuration)) == null
                        }
                        if (missing.isNotEmpty()) {
                            ConfigurationSwitchResult.NeedsPreparation(missing)
                        } else {
                            // This block has no suspension point: validation and promotion are
                            // one Main.immediate transaction against mutable message callbacks.
                            val promotions = currentMessages.map { message ->
                                val key = createRenderKey(message, configuration)
                                check(cachedRender(key) != null) {
                                    "Configuration commit has no current render for visible message"
                                }
                                message to key
                            }
                            activeConfiguration = configuration
                            pendingConfiguration = null
                            configurationJob = null
                            prefetchCatalogAssets()
                            synchronized(renderCache) {
                                renderCache.keys.removeIf { key ->
                                    key.catalogRevision != configuration.revision ||
                                        key.translateAllMessages != configuration.translateAllMessages
                                }
                            }
                            val currentPublicationGeneration = renderGeneration
                            inFlightDisplayEntries.toList().forEach { entry ->
                                entry.generation = currentPublicationGeneration
                                entry.state = PublicationState.QUEUED
                                scheduleDisplayEntry(entry, configuration, currentPublicationGeneration)
                            }
                            readyRenderKeys.clear()
                            promotions.forEach { (message, key) -> readyRenderKeys[message] = key }
                            visiblePositions().forEach { position ->
                                if (messages.getOrNull(position) != null) notifyItemChanged(position)
                            }
                            ConfigurationSwitchResult.Committed
                        }
                    }
                }
                when (result) {
                    is ConfigurationSwitchResult.NeedsPreparation ->
                        prepareCurrentRenders(result.messages, configuration)
                    ConfigurationSwitchResult.Committed,
                    ConfigurationSwitchResult.Aborted -> return@launch
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
        (textView.text as? Spanned)?.let { view ->
            view.getSpans<ImageSpan>().forEach { span ->
                visitAnimatables(span.drawable, action)
            }
            view.getSpans<NamePaintImageSpan>().forEach { span ->
                visitAnimatables(span.drawable, action)
            }
        }
        if (start && !foundAnimatable) runningAnimationTextViews.remove(textView)
    }

    private fun visitAnimatables(drawable: Drawable, action: (Animatable) -> Unit) {
        (drawable as? Animatable)?.let(action)
        (drawable as? LayerDrawable)?.let { layers ->
            for (i in 0 until layers.numberOfLayers) visitAnimatables(layers.getDrawable(i), action)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView = itemView as TextView
        val imageRequests = ChatAdapterUtils.ImageRequestBag()
        private var boundMessage: ChatMessage? = null
        private var boundRenderKey: RenderCacheKey? = null
        private var boundReplyMessage: Boolean? = null
        private var bindGeneration = 0
        private var catalogRefreshPosted = false
        private val boundDrawables = Collections.newSetFromMap(IdentityHashMap<Drawable, Boolean>())
        private val drawableCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (itemView.isAttachedToWindow) textView.invalidate()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                if (itemView.isAttachedToWindow) textView.postDelayed(what, (`when` - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L))
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                textView.removeCallbacks(what)
            }
        }
        var catalogRevision = 0
            private set

        init {
            textView.textSize = messageTextSize
            textView.setOnClickListener(if (profilePopoutGesture.allowsTap) {
                View.OnClickListener {
                    if (textView.selectionStart == -1 && textView.selectionEnd == -1) {
                        val message = boundMessage ?: return@OnClickListener
                        selectedMessage = if (message.type == ChatMessage.REPLY_MESSAGE) message.replyParent else message
                        messageClickListener?.invoke(channelId)
                    }
                }
            } else null)
            textView.setOnLongClickListener(if (profilePopoutGesture.allowsHold) {
                View.OnLongClickListener {
                    val message = boundMessage ?: return@OnLongClickListener false
                    selectedMessage = if (message.type == ChatMessage.REPLY_MESSAGE) message.replyParent else message
                    messageClickListener?.invoke(channelId)
                    true
                }
            } else null)
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
            detachDrawables()
            imageRequests.cancel()
            itemView.removeCallbacks(catalogRefreshRunnable)
            catalogRefreshPosted = false
            this.catalogRevision = catalogRevision
            bindGeneration++
            return bindGeneration
        }

        fun isCurrentBind(generation: Int): Boolean = generation == bindGeneration

        internal fun isAlreadyBoundTo(message: ChatMessage, key: RenderCacheKey): Boolean =
            boundMessage === message && boundRenderKey == key && itemView.isAttachedToWindow

        fun canAnimate(generation: Int): Boolean = isCurrentBind(generation) && itemView.isAttachedToWindow && !animationsPaused

        fun cancelBind() {
            detachDrawables()
            itemView.removeCallbacks(catalogRefreshRunnable)
            catalogRefreshPosted = false
            bindGeneration++
            boundMessage = null
            boundRenderKey = null
        }

        fun postCatalogRefresh() {
            if (catalogRefreshPosted) return
            catalogRefreshPosted = true
            itemView.post(catalogRefreshRunnable)
        }

        internal fun bind(chatMessage: ChatMessage, cacheKey: RenderCacheKey, result: ChatAdapterUtils.MessageResult) {
            if (result.backgroundColor != null) itemView.setBackgroundColor(result.backgroundColor)
            else setChatMessageBackground(itemView, result.backgroundResource)
            applyNamePaintBackground(result.builder, itemView.background)
            val specialPadding = if (chatMessage.isHighlightedMessage() ||
                chatMessage.isWatchStreakNotice() ||
                chatMessage.isFirst ||
                chatMessage.isSubscriptionNotice()
            ) {
                (6f * textView.resources.displayMetrics.density).roundToInt()
            } else 0
            val specialStartPadding = if (chatMessage.isFirst || chatMessage.isSubscriptionNotice()) {
                (6f * textView.resources.displayMetrics.density).roundToInt()
            } else 0
            textView.setPadding(specialStartPadding, specialPadding, 0, specialPadding)
            boundRenderKey = cacheKey
            bindContent(chatMessage, result.builder, result.accessibilityDescription)
            attachDrawables(result.builder)
        }

        private fun attachDrawables(content: SpannableStringBuilder) {
            content.getSpans<ImageSpan>().forEach { span -> attachDrawable(span.drawable) }
            content.getSpans<NamePaintImageSpan>().forEach { span -> attachDrawable(span.drawable) }
        }

        fun reattachDrawables() {
            (textView.text as? Spanned)?.let { content ->
                content.getSpans<ImageSpan>().forEach { span -> attachDrawable(span.drawable) }
                content.getSpans<NamePaintImageSpan>().forEach { span -> attachDrawable(span.drawable) }
            }
        }

        private fun attachDrawable(drawable: Drawable) {
            if (boundDrawables.add(drawable)) drawable.callback = drawableCallback
            if (drawable is LayerDrawable) {
                for (i in 0 until drawable.numberOfLayers) attachDrawable(drawable.getDrawable(i))
            }
        }

        fun detachDrawables() {
            boundDrawables.forEach { it.callback = null }
            boundDrawables.clear()
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
                        maxLines = 1
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
        const val MAX_RENDER_WORKERS = 2
        const val MAX_DIRECT_READY_KEYS = 2048
        const val VISIBLE_RENDER_QUEUE_CAPACITY = 32
        const val PREWARM_QUEUE_CAPACITY = 64
        const val PREWARM_BEFORE = 8
        const val PREWARM_AFTER = 24
        const val PREWARM_DEBOUNCE_MS = 100L
        const val MAX_CATALOG_BADGES_PER_SOURCE = 32
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
        } catch (e: CancellationException) {
            throw e
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

    private fun startRenderWorkers(): List<Job> = List(MAX_RENDER_WORKERS) {
        renderScope.launch {
            while (isActive) {
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
        var waitersCompleted = false
        try {
            if (!isKnownConfiguration(request.configuration)) return
            if (cachedRender(cacheKey) != null) return
            val prepared = prepareMessage(
                chatMessage,
                request.context,
                null,
                request.configuration.indexes,
                request.cacheKey.translateAllMessages,
                request.configuration.highlightSettings,
                offMain = true,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isKnownConfiguration(request.configuration) && currentRenderKey(chatMessage, request.configuration) == cacheKey) {
                    synchronized(renderCache) { renderCache[cacheKey] = prepared; trimRenderCache() }
                    completeRenderWaiters(cacheKey)
                    waitersCompleted = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A permanent renderer failure still needs a complete logical row. This terminal
            // representation is built on the worker and contains no unresolved images.
            val prepared = runCatching {
                ChatAdapterUtils.prepareTerminalFailureRender(
                    chatMessage = chatMessage,
                    context = request.context,
                    enableTimestamps = enableTimestamps,
                    timestampFormat = timestampFormat,
                    firstMsgVisibility = firstMsgVisibility,
                    nameDisplay = nameDisplay,
                    useReadableColors = useReadableColors,
                    isLightTheme = isLightTheme,
                    savedColors = savedColors,
                    highlightSettings = request.configuration.highlightSettings,
                )
            }.getOrElse { prepareLastResortTerminalFailureRender(chatMessage, request.configuration.highlightSettings) }
            withContext(Dispatchers.Main.immediate) {
                if (isKnownConfiguration(request.configuration) &&
                    currentRenderKey(chatMessage, request.configuration) == cacheKey
                ) {
                    synchronized(renderCache) { renderCache[cacheKey] = prepared; trimRenderCache() }
                    completeRenderWaiters(cacheKey)
                    waitersCompleted = true
                }
            }
        } finally {
            synchronized(renderJobs) {
                renderJobs.remove(cacheKey)
                prewarmRenderJobs.remove(cacheKey)
                visibleRenderJobs.remove(cacheKey)
            }
            if (!waitersCompleted) completeRenderWaiters(cacheKey)
        }
    }

    /**
     * Last line of defense for a renderer failure. This intentionally has no context/resource,
     * image, or catalog dependency, so a corrupt message cannot keep the publication cursor
     * blocked. It is still a complete terminal row and is never upgraded asynchronously.
     */
    private fun prepareLastResortTerminalFailureRender(
        message: ChatMessage,
        highlightSettings: ChatHighlightSettings,
    ): ChatAdapterUtils.MessageResult {
        val builder = SpannableStringBuilder()
        if (enableTimestamps) {
            runCatching { message.timestamp?.let { TwitchApiHelper.getTimestamp(it, timestampFormat) } }
                .getOrNull()?.let {
                    builder.append(it)
                    builder.append(' ')
                }
        }
        run {
            val name = runCatching { message.displayName(nameDisplay) }.getOrNull()
            if (!name.isNullOrBlank() && message.type != ChatMessage.SYSTEM_MESSAGE) {
                val start = builder.length
                builder.append(name)
                builder.setSpan(
                    ForegroundColorSpan(Color.GRAY),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (message.isAction) builder.append(' ') else builder.append(": ")
            }
            val content = message.reply?.message
                ?: message.message
                ?: message.systemMsg
                ?: message.fullMsg
                ?: message.reward?.title
                ?: ""
            builder.append(content)
            message.translatedMessage?.let {
                builder.append('\n')
                builder.append(it)
            }
        }
        var background = runCatching {
            when {
                message.isHighlightedMessage() -> R.drawable.bg_chat_highlight
                message.isWatchStreakNotice() -> R.drawable.bg_chat_watch_streak
                else -> chatMessageBackgroundResource(
                    message,
                    firstMsgVisibility,
                    shouldHighlightLegacyChatMessage(message, highlightSettings),
                )
            }
        }.getOrDefault(0)
        var backgroundColor: Int? = null
        if (background == R.color.chatMessageMention) {
            backgroundColor = highlightSettings.color
            background = 0
        }
        return ChatAdapterUtils.MessageResult(
            builder = builder,
            images = ArrayList(),
            imagePaint = null,
            userName = message.userName ?: message.userLogin,
            userNameStartIndex = null,
            translated = message.translatedMessage != null,
            backgroundResource = background,
            backgroundColor = backgroundColor,
        )
    }

    /** Returns only after the render for the message's current mutable key is cached. */
    private suspend fun prepareCurrentRender(
        message: ChatMessage,
        configuration: ChatRenderConfiguration,
    ): RenderCacheKey {
        while (true) {
            if (!isKnownConfiguration(configuration)) throw StaleRenderConfigurationException()
            val requestedKey = createRenderKey(message, configuration)
            if (cachedRender(requestedKey) != null) return requestedKey
            prepareForDisplay(listOf(message), configuration)
            if (!isKnownConfiguration(configuration)) throw StaleRenderConfigurationException()
            val currentKey = createRenderKey(message, configuration)
            if (currentKey == requestedKey && cachedRender(currentKey) != null) return currentKey
        }
    }

    private suspend fun prepareCurrentRenders(
        messages: List<ChatMessage>,
        configuration: ChatRenderConfiguration,
    ) {
        messages.forEach { message -> prepareCurrentRender(message, configuration) }
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

    private fun prefetchCatalogAssets() {
        val images = ArrayList<Image>(MAX_CATALOG_BADGES_PER_SOURCE * 3)

        fun addBadge(badge: TwitchBadge) {
            images += Image(
                url1x = badge.url1x,
                url2x = badge.url2x,
                url3x = badge.url3x,
                url4x = badge.url4x,
                kind = ImageKind.BADGE,
                start = 0,
                end = 1,
            )
        }

        fun addStvBadge(badge: STVBadge) {
            images += Image(
                url1x = badge.url1x,
                url2x = badge.url2x,
                url3x = badge.url3x,
                url4x = badge.url4x,
                format = badge.format,
                isAnimated = true,
                kind = ImageKind.BADGE,
                thirdParty = true,
                start = 0,
                end = 1,
            )
        }

        synchronized(globalBadges) {
            globalBadges.take(MAX_CATALOG_BADGES_PER_SOURCE).forEach(::addBadge)
        }
        synchronized(channelBadges) {
            channelBadges.take(MAX_CATALOG_BADGES_PER_SOURCE).forEach(::addBadge)
        }
        synchronized(stvBadges) {
            stvBadges.take(MAX_CATALOG_BADGES_PER_SOURCE).forEach(::addStvBadge)
        }

        val context = fragment.context ?: return
        ChatAdapterUtils.prefetchImages(
            context,
            images.distinctBy { it.url4x ?: it.url3x ?: it.url2x ?: it.url1x },
            imageLibrary,
            emoteQuality,
            emoteSize,
            badgeSize,
            inlineIconSize,
            imagePrefetchTracker,
        )
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
        clipPreviews = clipLinksOf(message.message).map { link -> clipPreviewRepository()?.peek(link.slug) },
        highlightSettings = configuration.highlightSettings,
    )

    private fun clipPreviewRepository(): ChatClipPreviewRepository? =
        (fragment.context?.applicationContext as? XtraApp)?.xtraModule?.chatClipPreviewRepository

    /** One shared observer per clip slug; loaded metadata re-renders every row linking it. */
    private val clipPreviewObservers = HashMap<String, () -> Unit>()

    private fun ensureClipSlugObserved(slug: String) {
        val repository = clipPreviewRepository() ?: return
        val listener: () -> Unit = { onClipSlugLoaded(slug) }
        val registered = synchronized(clipPreviewObservers) {
            if (clipPreviewObservers.containsKey(slug)) {
                false
            } else {
                clipPreviewObservers[slug] = listener
                true
            }
        }
        if (registered) repository.observe(slug, listener)
    }

    private fun onClipSlugLoaded(slug: String) {
        // One-shot: the shared listener is done whether the load succeeded or not.
        // A failed load stays retryable because the next ensureClipSlugObserved()
        // registers a fresh listener, which the repository loads again.
        removeClipSlugObserver(slug)
        if (clipPreviewRepository()?.peek(slug) == null) return
        mainHandler.post { refreshClipPreviewRenders(slug) }
    }

    private fun removeClipSlugObserver(slug: String) {
        val listener = synchronized(clipPreviewObservers) {
            clipPreviewObservers.remove(slug)
        } ?: return
        clipPreviewRepository()?.removeObserver(slug, listener)
    }

    private fun clearClipPreviewObservers() {
        val repository = clipPreviewRepository()
        val entries = synchronized(clipPreviewObservers) {
            clipPreviewObservers.entries.toList().also { clipPreviewObservers.clear() }
        }
        entries.forEach { (entrySlug, listener) -> repository?.removeObserver(entrySlug, listener) }
    }

    private fun refreshClipPreviewRenders(slug: String) {
        if (!fragment.isAdded) return
        messages.toList().forEach { message ->
            if (clipLinksOf(message.message).any { it.slug.equals(slug, ignoreCase = true) }) {
                updateMessageContent(message)
            }
        }
    }

    private fun isActiveConfiguration(configuration: ChatRenderConfiguration): Boolean =
        configuration.revision == activeConfiguration.revision &&
            configuration.indexes === activeConfiguration.indexes &&
            configuration.translateAllMessages == activeConfiguration.translateAllMessages &&
            configuration.highlightSettings == activeConfiguration.highlightSettings

    private fun isKnownConfiguration(configuration: ChatRenderConfiguration): Boolean =
        isActiveConfiguration(configuration) ||
            pendingConfiguration?.let {
                configuration.revision == it.revision &&
                    configuration.indexes === it.indexes &&
                    configuration.translateAllMessages == it.translateAllMessages &&
                    configuration.highlightSettings == it.highlightSettings
            } == true

    private fun newRenderScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(MAX_RENDER_WORKERS),
    )

    private suspend fun prepareMessage(
        chatMessage: ChatMessage,
        context: android.content.Context,
        itemView: View?,
        indexes: ChatAdapterUtils.ChatCatalogIndexes,
        translateAllMessages: Boolean,
        highlightSettings: ChatHighlightSettings,
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
        val result = ChatAdapterUtils.prepareChatMessage(
            chatMessage, context, itemView, enableTimestamps, timestampFormat, firstMsgVisibility, firstChatMsg,
            redeemedChatMsg, redeemedNoMsg, replyMessage, null, useRandomColors, random, useReadableColors, isLightTheme,
            nameDisplay, useBoldNames, showNamePaints, namePaints, showBadges, showSTVBadges, stvBadges, showPersonalEmotes, personalEmoteSets, stvUsers,
            enableOverlayEmotes, showSystemMessageEmotes, loggedInUser, chatUrl, userColors, savedColors, translateAllMessages,
            deferredTranslate, deferredLanguageDialog, true, localTwitchEmotes, thirdPartyEmotes, globalBadges, channelBadges, cheerEmotes,
            savedLocalTwitchEmotes, savedLocalBadges, savedLocalCheerEmotes, savedLocalEmotes,
            catalogIndexes = indexes,
            includeAccessibilityDescription = true,
            highlightSettings = highlightSettings,
        )
        val clipLinks = clipLinksOf(chatMessage.message)
        if (clipLinks.isNotEmpty()) {
            installLegacyClipLinkClicks(result.builder)
            val repository = clipPreviewRepository()
            appendLegacyClipEmbeds(
                context,
                result.builder,
                result.images,
                clipLinks,
                clipLinks.map { repository?.peek(it.slug) },
            )
            clipLinks.forEach { ensureClipSlugObserved(it.slug) }
        }
        val (resolvedImages, resolvedImagePaint) = ChatAdapterUtils.resolveChatImages(
            context, result.images, result.imagePaint, imageLibrary, emoteQuality,
            emoteSize, badgeSize, inlineIconSize,
        )
        return ChatAdapterUtils.MessageResult(
            builder = result.builder,
            images = result.images,
            imagePaint = result.imagePaint,
            userName = result.userName,
            userNameStartIndex = result.userNameStartIndex,
            translated = result.translated,
            backgroundResource = result.backgroundResource,
            backgroundColor = result.backgroundColor,
            accessibilityDescription = result.accessibilityDescription,
            resolvedImages = resolvedImages,
            resolvedImagePaint = resolvedImagePaint,
        )
    }

}

internal fun requireReadyRender(result: ChatAdapterUtils.MessageResult?): ChatAdapterUtils.MessageResult =
    checkNotNull(result) { "Chat item became bindable without a ready render" }.copyForBind()

internal fun <T> contiguousReadyPrefix(pending: List<T>, ready: Set<T>): List<T> =
    pending.takeWhile { it in ready }

internal fun <T> identityPosition(items: List<T>, target: T): Int =
    items.indexOfFirst { it === target }

internal fun adapterRowsToRemoveForTrim(
    messages: List<ChatMessage>,
    trimCount: Int,
): Int {
    if (trimCount <= 0) return 0
    var realRows = 0
    var adapterRows = 0
    while (adapterRows < messages.size && realRows < trimCount) {
        realRows++
        adapterRows++
    }
    return adapterRows
}

internal fun renderRequestCanBecomeReady(
    requestedKey: Any,
    currentKey: Any,
    hasCurrentCache: Boolean,
): Boolean = requestedKey == currentKey && hasCurrentCache
