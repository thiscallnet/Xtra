package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.os.SystemClock
import android.os.Trace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogState
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatMetadataSettlement
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationLabels
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatTimelineDelta
import com.github.andreyasadchy.xtra.ui.chat.ChatRenderStyle
import com.github.andreyasadchy.xtra.ui.chat.ChatProfilePopoutGesture
import com.github.andreyasadchy.xtra.ui.chat.resolveChatHighlightSettings
import com.github.andreyasadchy.xtra.util.ChatBatchingPreferences
import com.github.andreyasadchy.xtra.util.ChatRenderDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationCatalog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Keep a small animated tail alive while preventing a busy chat from driving every visible row.
private const val DEFAULT_ANIMATION_BUDGET = 4

internal fun countNewLiveMessages(
    previousIds: Set<ChatMessageId>?,
    previousTailId: ChatMessageId?,
    messages: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>,
): Int {
    if (previousIds == null || previousTailId == null) return 0
    val previousTailIndex = messages.indexOfFirst { it.id == previousTailId }
    if (previousTailIndex == -1) return 0
    return messages.asSequence()
        .drop(previousTailIndex + 1)
        .count { it.id !in previousIds }
}

/**
 * Bridges a playback-owned v2 session to one disposable RecyclerView renderer.
 *
 * The collector is lifecycle-owned by the Fragment view. The session, timeline,
 * and asset repository are not. When the view is absent this class has no active
 * collection, no snapshot materialization, and no drawable callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatV2RendererController(
    private val recyclerView: RecyclerView,
    private val activeSessions: Flow<ActiveChatSession?>,
    private val assets: ChatAssetRepository,
    private val expectedChannelId: String,
    private val expectedChannelLogin: String,
    private val initialState: ChatViewportState = ChatViewportState(),
    emoteHeightPx: Int = 28,
    badgeHeightPx: Int = 18,
    messageTextSizeSp: Float = 14f,
    animateGifs: Boolean = true,
    gifDisplayMode: com.github.andreyasadchy.xtra.ui.chat.ChatGifDisplayMode = com.github.andreyasadchy.xtra.ui.chat.ChatGifDisplayMode.LARGE,
    showBadges: Boolean = true,
    enableOverlayEmotes: Boolean = true,
    firstMessageVisibility: Int = 0,
    boldNames: Boolean = false,
    private val nameDisplay: String = "0",
    private val randomUsernameColors: Boolean = false,
    private val showSystemMessageEmotes: Boolean = true,
    private val showNamePaints: Boolean = true,
    private val showThirdPartyBadges: Boolean = true,
    private val showPersonalEmotes: Boolean = true,
    private val translation: (ChatMessage) -> String? = { null },
    private val onTranslateMessage: (ChatMessage) -> Unit = {},
    translateAllMessages: Boolean = false,
    timestampFormat: String? = "0",
    showTimestamps: Boolean = false,
    private val readableUsernameColors: Boolean = true,
    private val backgroundColor: Int = 0xFF101010.toInt(),
    private val presentationLabels: ChatPresentationLabels = ChatPresentationLabels(),
    animationBudget: Int = DEFAULT_ANIMATION_BUDGET,
    private val onStateChanged: (ChatViewportState) -> Unit = {},
    private val onMessageLongClick: (ChatMessage) -> Unit = {},
    private val profilePopoutGesture: ChatProfilePopoutGesture = ChatProfilePopoutGesture.HOLD,
    private val rewardCatalog: Flow<ChatRewardCatalog> = flowOf(ChatRewardCatalog()),
    private val rewardCatalogSettled: Flow<Boolean> = flowOf(true),
    private val decorationCatalog: Flow<ChatDecorationSnapshot> = flowOf(ChatDecorationSnapshot()),
    private val onEmoteClick: (ChatEmoteInteraction) -> Unit = {},
    private val onGifClick: (ChatGifInteraction) -> Unit = {},
    private val onPublicationChanged: (List<ChatMessage>, List<ChatRowUiModel>) -> Unit = { _, _ -> },
) {
    private var renderStyle = ChatRenderStyle(
        textSizeSp = messageTextSizeSp,
        emoteHeightPx = emoteHeightPx,
        badgeHeightPx = badgeHeightPx,
        animateGifs = animateGifs,
        showBadges = showBadges,
        enableOverlayEmotes = enableOverlayEmotes,
        firstMessageVisibility = firstMessageVisibility,
        boldNames = boldNames,
        showTimestamps = showTimestamps,
            timestampFormat = timestampFormat,
            gifDisplayMode = gifDisplayMode,
    )
    private val adapter = ChatTimelineAdapter(
        assets,
        renderStyle.textSizeSp,
        renderStyle.animateGifs,
        onMessageLongClick = if (profilePopoutGesture.allowsHold) {
            { id -> latestMessages.firstOrNull { it.id == id }?.let(onMessageLongClick) }
        } else null,
        onEmoteClick = onEmoteClick,
        onGifClick = onGifClick,
        onMessageClick = if (profilePopoutGesture.allowsTap) {
            { id -> latestMessages.firstOrNull { it.id == id }?.let(onMessageLongClick) }
        } else null,
    )
    init {
        adapter.setAnimationBudget(animationBudget)
    }
    private val viewport = ChatViewportController(recyclerView, initialState)
    private var highlightSettings = resolveChatHighlightSettings(recyclerView.context)
    private val presentation = createPresentation(readableUsernameColors, backgroundColor, renderStyle)
    private var collectionJob: Job? = null
    private var styleRefreshJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var currentKey: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey? = null
    private val previousIds = HashSet<ChatMessageId>()
    private var hasPreviousIds = false
    private var previousTailId: ChatMessageId? = null
    private val latestMessages = ArrayDeque<ChatMessage>()
    private val latestRows = ArrayList<ChatRowUiModel>()
    private var latestPublication: PresentationPublication? = null
    private var committedPublication: PresentationPublication? = null
    private val reuseIndex = ChatPresentationReuseIndex()
    private val presentationSnapshot = ChatPresentationSnapshot()
    private val rendererVisible = MutableStateFlow(true)
    private var translateAllMessages = translateAllMessages
    private val requestedTranslationIds = HashSet<ChatMessageId>()

    init {
        recyclerView.itemAnimator = null
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter
    }

    val state: ChatViewportState
        get() = viewport.state

    internal fun currentMessages(): List<ChatMessage> = latestMessages.toList()
    internal fun currentRows(): List<ChatRowUiModel> = latestRows

    fun attach(owner: LifecycleOwner) {
        collectionJob?.cancel()
        lifecycleOwner = owner
        collectionJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateRenderingActive(rendererVisible.value)
                try {
                    rendererVisible
                        .flatMapLatest { visible ->
                            if (!visible) {
                                emptyFlow()
                            } else {
                                activeSessions.flatMapLatest { active ->
                                    if (active == null ||
                                        active.spec.channelId != expectedChannelId ||
                                        !active.spec.channelLogin.equals(expectedChannelLogin, ignoreCase = true)
                                    ) {
                                        emptyFlow()
                                    } else {
                                        active.presentationFlow()
                                    }
                                }
                            }
                        }
                        .collect { publication -> publish(publication) }
                } finally {
                    updateRenderingActive(false)
                }
            }
        }
    }

    /** Hides only rendering; the playback-owned session and canonical timeline continue. */
    fun setVisible(visible: Boolean) {
        if (rendererVisible.value == visible) return
        rendererVisible.value = visible
        updateRenderingActive(visible && lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true)
    }

    private fun updateRenderingActive(active: Boolean) {
        adapter.renderingActive = active
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildAt(index) as? ChatMessageTextView)?.setRenderingActive(active)
        }
    }

    fun detach() {
        collectionJob?.cancel()
        collectionJob = null
        styleRefreshJob?.cancel()
        styleRefreshJob = null
        lifecycleOwner = null
        rendererVisible.value = false
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildAt(index) as? ChatMessageTextView)?.setRenderingActive(false)
        }
        recyclerView.adapter = null
        adapter.dispose()
        latestMessages.clear()
        latestRows.clear()
        committedPublication = null
        previousIds.clear()
        hasPreviousIds = false
        previousTailId = null
        currentKey = null
        reuseIndex.clear()
        presentationSnapshot.clear()
        requestedTranslationIds.clear()
    }

    fun onUserScroll() {
        viewport.onUserScroll()
        onStateChanged(viewport.state)
    }

    internal fun refreshStyle(style: ChatRenderStyle) {
        val nextHighlightSettings = resolveChatHighlightSettings(recyclerView.context)
        if (style == renderStyle && nextHighlightSettings == highlightSettings) return
        renderStyle = style
        highlightSettings = nextHighlightSettings
        presentation.replaceCompiler(createPresentationCompiler(style))
        adapter.setMessageTextSizeSp(style.textSizeSp)
        adapter.setAnimateGifs(style.animateGifs)
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildAt(index) as? ChatMessageTextView)?.apply {
                setMessageTextSizeSp(style.textSizeSp)
                setAnimateGifs(style.animateGifs)
            }
        }
        if (latestPublication == null || lifecycleOwner == null) return
        invalidatePresentation()
    }

    fun setTranslateAllMessages(enabled: Boolean) {
        if (translateAllMessages == enabled) return
        translateAllMessages = enabled
        if (enabled) requestTranslations(latestMessages)
    }

    /** Recompiles the current snapshot after an external presentation-only update. */
    fun invalidatePresentation() {
        val publication = latestPublication ?: return
        val owner = lifecycleOwner ?: return
        val currentMessages = latestMessages.toList()
        presentation.invalidate()
        styleRefreshJob?.cancel()
        styleRefreshJob = owner.lifecycleScope.launch {
            val compiled = compileCurrent(publication, fullMessages = currentMessages)
            val rows = compiled.result.rows
            withContext(Dispatchers.Main.immediate) {
                if (!rendererVisible.value || latestPublication !== publication) return@withContext
                val uiChanged = !sameRows(latestRows, rows)
                latestRows.clear()
                latestRows.addAll(rows)
                reuseIndex.replace(currentMessages, rows)
                ChatRenderDiagnostics.recordReuseIndexUpdate(incremental = false)
                ChatRenderDiagnostics.recordPublication(
                    messageCount = currentMessages.size,
                    changed = compiled.result.messagesChanged,
                    compiled = compiled.result.rowsCompiled,
                    reused = compiled.result.rowsReused,
                    visited = compiled.result.rowsVisited,
                    allocated = compiled.result.rowsAllocated,
                    compileNanos = compiled.durationNanos,
                    fullRebuild = compiled.fullRebuild,
                    uiChanged = uiChanged,
                )
                if (uiChanged) {
                    onPublicationChanged(currentMessages, rows)
                    adapter.replaceAll(rows)
                }
            }
        }
    }

    fun jumpToNewest() {
        viewport.jumpToNewest(latestRows)
        onStateChanged(viewport.state)
    }

    private suspend fun publish(publication: PresentationPublication) {
        if (!rendererVisible.value) return
        val committed = committedPublication
        if (styleRefreshJob?.isActive != true && committed != null && committed.key == publication.key &&
            committed.timelineVersion == publication.timelineVersion &&
            committed.forceRefreshRevision == publication.forceRefreshRevision &&
            committed.metadataSettlement == publication.metadataSettlement &&
            publication.metadataSettlement.let { it.structuralSettled && it.badgesSettled && it.rewardsSettled }
        ) {
            // Settled rows deliberately freeze their metadata. A new 7TV entitlement
            // affects future messages, so it must not rescan the entire visible history.
            latestPublication = publication
            committedPublication = publication
            return
        }
        val previousPublication = latestPublication
        val previousRows = latestRows
        val previousMessageCount = latestMessages.size
        withContext(Dispatchers.Main.immediate) {
            if (!rendererVisible.value) return@withContext
            latestPublication = publication
        }
        styleRefreshJob?.cancel()
        var preparedPublication = materializeFullPublicationIfNeeded(
            publication = publication,
            previousPublication = previousPublication,
            previousRows = previousRows,
            previousMessageCount = previousMessageCount,
        )
        var fullMessages = preparedPublication.messages.takeIf {
            preparedPublication.timelineDelta !is ChatTimelineDelta.Append
        }
        var compiled = compileCurrent(
            publication = preparedPublication,
            previousPublication = previousPublication,
            previousRows = previousRows,
            previousMessageCount = previousMessageCount,
            fullMessages = fullMessages,
        )
        if (fullMessages == null && compiled.appendInfo == null) {
            preparedPublication = preparedPublication.copy(
                messages = preparedPublication.fullSnapshot(),
                timelineDelta = ChatTimelineDelta.Full,
            )
            fullMessages = preparedPublication.messages
            compiled = compileCurrent(
                publication = preparedPublication,
                previousPublication = previousPublication,
                previousRows = previousRows,
                previousMessageCount = previousMessageCount,
                fullMessages = fullMessages,
            )
        }
        val rows = compiled.result.rows
        if (!rendererVisible.value) return
        withContext(Dispatchers.Main.immediate) {
            if (!rendererVisible.value) return@withContext
            if (latestPublication !== publication) return@withContext
            latestPublication = preparedPublication
            if (currentKey != preparedPublication.key) {
                if (currentKey != null) viewport.resetForNewSession()
                currentKey = preparedPublication.key
                hasPreviousIds = false
                previousIds.clear()
                previousTailId = null
                reuseIndex.clear()
            }
            val delta = compiled.appendInfo?.let {
                preparedPublication.timelineDelta as? ChatTimelineDelta.Append
            }
            val currentMessages = fullMessages
            requestTranslations(delta?.messages ?: currentMessages.orEmpty())
            val oldIds = previousIds
            val previousAnchor = if (!hasPreviousIds) null else viewport.captureAnchor(adapter)
            // Reconciliation can insert older messages into the middle/front of the timeline.
            // Only messages newer than the previous tail are live appends.
            val appendInfo = compiled.appendInfo ?: previousPublication
                ?.takeIf { it.key == preparedPublication.key }
                ?.let { currentMessages?.let { messages -> findChatAppendInfo(latestMessages, messages) } }
            val appendedCount = compiled.appendInfo?.appendedCount ?: countNewLiveMessages(
                oldIds.takeIf { hasPreviousIds },
                previousTailId,
                currentMessages.orEmpty(),
            )
            if (appendInfo != null && hasPreviousIds) {
                repeat(appendInfo.evictedCount) {
                    latestMessages.getOrNull(it)?.id?.let(previousIds::remove)
                }
                (delta?.messages ?: currentMessages.orEmpty().takeLast(appendInfo.appendedCount))
                    .forEach { previousIds += it.id }
            } else {
                previousIds.clear()
                currentMessages.orEmpty().forEach { previousIds += it.id }
            }
            hasPreviousIds = true
            val incrementallyUpdated = if (delta != null) {
                reuseIndex.appendDelta(
                    appendedMessages = delta.messages,
                    appendedRows = rows,
                    evictedCount = delta.evictedCount,
                    expectedSize = delta.resultingSize,
                )
            } else {
                appendInfo?.let {
                    reuseIndex.append(
                        messages = requireNotNull(currentMessages),
                        rows = rows,
                        appendedCount = it.appendedCount,
                        evictedCount = it.evictedCount,
                    )
                } == true
            }
            if (!incrementallyUpdated) reuseIndex.replace(requireNotNull(currentMessages), rows)
            ChatRenderDiagnostics.recordReuseIndexUpdate(incrementallyUpdated)
            val uiChanged: Boolean
            if (delta != null) {
                latestRows.subList(0, delta.evictedCount).clear()
                latestRows.addAll(rows)
                uiChanged = delta.evictedCount > 0 || rows.isNotEmpty()
            } else {
                uiChanged = !sameRows(previousRows, rows)
                latestRows.clear()
                latestRows.addAll(rows)
            }
            if (delta != null) {
                repeat(delta.evictedCount) { latestMessages.removeFirst() }
                latestMessages.addAll(delta.messages)
            } else {
                latestMessages.clear()
                latestMessages.addAll(requireNotNull(currentMessages))
            }
            previousTailId = latestMessages.lastOrNull()?.id
            committedPublication = preparedPublication
            ChatRenderDiagnostics.recordPublication(
                messageCount = latestMessages.size,
                changed = compiled.result.messagesChanged,
                compiled = compiled.result.rowsCompiled,
                reused = compiled.result.rowsReused,
                visited = compiled.result.rowsVisited,
                allocated = compiled.result.rowsAllocated,
                compileNanos = compiled.durationNanos,
                fullRebuild = compiled.fullRebuild,
                uiChanged = uiChanged,
            )
            if (uiChanged) {
                onPublicationChanged(latestMessages, latestRows)
                val appliedIncrementally = if (delta != null) {
                    adapter.appendDelta(
                        appendedRows = rows,
                        evictedHeadCount = delta.evictedCount,
                        expectedSize = delta.resultingSize,
                    )
                } else {
                    appendInfo?.let {
                        adapter.append(
                            newRows = rows,
                            evictedHeadCount = it.evictedCount,
                            appendedCount = it.appendedCount,
                        )
                    } == true
                }
                if (appliedIncrementally) {
                    viewport.onSnapshotCommitted(previousAnchor, latestRows, appendedCount)
                    onStateChanged(viewport.state)
                } else {
                    adapter.replaceAll(latestRows) {
                        viewport.onSnapshotCommitted(previousAnchor, latestRows, appendedCount)
                        onStateChanged(viewport.state)
                    }
                }
            }
        }
    }

    private suspend fun materializeFullPublicationIfNeeded(
        publication: PresentationPublication,
        previousPublication: PresentationPublication?,
        previousRows: List<ChatRowUiModel>,
        previousMessageCount: Int,
    ): PresentationPublication {
        if (publication.timelineDelta !is ChatTimelineDelta.Append ||
            canApplyTimelineAppend(publication, previousPublication, previousRows, previousMessageCount)
        ) {
            return publication
        }
        return publication.copy(
            messages = publication.fullSnapshot(),
            timelineDelta = ChatTimelineDelta.Full,
        )
    }

    private fun canApplyTimelineAppend(
        publication: PresentationPublication,
        previousPublication: PresentationPublication?,
        previousRows: List<ChatRowUiModel>,
        previousMessageCount: Int,
    ): Boolean {
        val delta = publication.timelineDelta as? ChatTimelineDelta.Append ?: return false
        val forceCatalogUpgrade = previousPublication?.let { previous ->
            publication.forceRefreshRevision != previous.forceRefreshRevision
        } == true
        val metadataSettlementChanged = previousPublication?.metadataSettlement !=
            publication.metadataSettlement
        return previousPublication != null &&
            previousPublication.key == publication.key &&
            publication.timelineVersion > previousPublication.timelineVersion &&
            !forceCatalogUpgrade &&
            !metadataSettlementChanged &&
            previousRows.size == previousMessageCount &&
            reuseIndex.size == previousMessageCount &&
            delta.evictedCount <= previousMessageCount &&
            delta.resultingSize == previousMessageCount - delta.evictedCount + delta.messages.size
    }

    private data class CompiledRows(
        val result: ChatRowCompileResult,
        val durationNanos: Long,
        val fullRebuild: Boolean,
        val appendInfo: ChatAppendInfo? = null,
    )

    private suspend fun compileCurrent(
        publication: PresentationPublication,
        previousPublication: PresentationPublication? = null,
        previousRows: List<ChatRowUiModel> = latestRows,
        previousMessageCount: Int = latestMessages.size,
        fullMessages: List<ChatMessage>? = publication.messages,
    ): CompiledRows {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        while (true) {
            val compiler = presentation.snapshot()
            val forceCatalogUpgrade = previousPublication?.let { previous ->
                publication.forceRefreshRevision != previous.forceRefreshRevision
            } == true
            val metadataSettlementChanged = previousPublication?.metadataSettlement !=
                    publication.metadataSettlement
            val reusablePublication = previousPublication?.takeIf {
                !forceCatalogUpgrade &&
                        !metadataSettlementChanged &&
                        it.key == publication.key
            }
            val timelineAppend = publication.timelineDelta as? ChatTimelineDelta.Append
            val appendInfo = timelineAppend
                ?.takeIf { delta ->
                    canApplyTimelineAppend(
                        publication = publication,
                        previousPublication = previousPublication,
                        previousRows = previousRows,
                        previousMessageCount = previousMessageCount,
                    ) && delta.resultingSize == previousMessageCount - delta.evictedCount + delta.messages.size
                }
                ?.let { delta -> ChatAppendInfo(delta.messages.size, delta.evictedCount) }
            val appendCatalogs = appendInfo?.let {
                val delta = requireNotNull(timelineAppend)
                if (delta.messages.isEmpty() && delta.evictedCount == 0) {
                    emptyList()
                } else {
                    presentationSnapshot.catalogsForAppend(
                        key = publication.key,
                        appendedMessages = delta.messages,
                        evictedCount = delta.evictedCount,
                        newMessageCount = delta.resultingSize,
                        catalog = publication.catalog,
                        captureBadges = renderStyle.showBadges,
                        structuralSettled = publication.metadataSettlement.structuralSettled,
                        badgesSettled = publication.metadataSettlement.badgesSettled,
                        rewardsSettled = publication.metadataSettlement.rewardsSettled,
                    )
                }
            }
            val rows = withContext(Dispatchers.Default) {
                if (BuildConfig.PERF_DIAGNOSTICS) Trace.beginSection("Xtra.ChatV2.compileCurrent")
                try {
                    if (appendInfo != null && appendCatalogs != null) {
                        val delta = requireNotNull(timelineAppend)
                        val startIndex = delta.resultingSize - delta.messages.size
                        compileChatRowAppend(
                            messages = delta.messages,
                            startIndex = startIndex,
                            retainedRows = previousRows.size - delta.evictedCount,
                            resolve = { message, index -> compiler.resolve(message, appendCatalogs[index - startIndex]) },
                        )
                    } else {
                        val messages = requireNotNull(fullMessages)
                        val catalogs = presentationSnapshot.catalogsFor(
                            publication.key,
                            messages,
                            publication.catalog,
                            captureBadges = renderStyle.showBadges,
                            structuralSettled = publication.metadataSettlement.structuralSettled,
                            badgesSettled = publication.metadataSettlement.badgesSettled,
                            rewardsSettled = publication.metadataSettlement.rewardsSettled,
                            forceUpgrade = forceCatalogUpgrade,
                        )
                        compileChatRows(
                            messages = messages,
                            reuseIndex = reuseIndex.takeIf { reusablePublication != null },
                            resolve = { message, index -> compiler.resolve(message, catalogs[index]) },
                        )
                    }
                } finally {
                    if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
                }
            }
            if (presentation.isCurrent(compiler)) {
                return CompiledRows(
                    result = rows,
                    durationNanos = SystemClock.elapsedRealtimeNanos() - startedAt,
                    fullRebuild = appendInfo == null && reusablePublication == null,
                    appendInfo = appendInfo?.takeIf { appendCatalogs != null },
                )
            }
        }
    }

    private fun requestTranslations(messages: List<ChatMessage>) {
        if (!translateAllMessages) return
        messages.forEach { message ->
            if (translation(message).isNullOrBlank() && requestedTranslationIds.add(message.id)) {
                onTranslateMessage(message)
            }
        }
    }

    private fun sameRows(previous: List<ChatRowUiModel>, current: List<ChatRowUiModel>): Boolean {
        if (previous === current) return true
        if (previous.size != current.size) return false
        return previous.indices.all { index ->
            val before = previous[index]
            val after = current[index]
            before === after || before == after
        }
    }

    private fun createPresentation(readable: Boolean, background: Int, style: ChatRenderStyle) =
        ChatPresentationResolver(createPresentationCompiler(style, readable, background))

    private fun createPresentationCompiler(style: ChatRenderStyle, readable: Boolean = readableUsernameColors, background: Int = backgroundColor) =
        ChatRowCompiler(
            colors = ChatColorResolver(
                readable = readable,
                randomFallback = randomUsernameColors,
                neutralFallback = !randomUsernameColors,
                background = background,
            ),
            emoteHeightPx = style.emoteHeightPx,
            badgeHeightPx = style.badgeHeightPx,
            showBadges = style.showBadges,
            enableOverlayEmotes = style.enableOverlayEmotes,
            firstMessageVisibility = style.firstMessageVisibility,
            boldNames = style.boldNames,
            nameDisplay = nameDisplay,
            showSystemMessageEmotes = showSystemMessageEmotes,
            showNamePaints = showNamePaints,
            showThirdPartyBadges = showThirdPartyBadges,
            showPersonalEmotes = showPersonalEmotes,
            translation = translation,
            timestampText = if (style.showTimestamps) {
                { timestamp -> com.github.andreyasadchy.xtra.util.TwitchApiHelper.getTimestamp(timestamp, style.timestampFormat) }
            } else {
                { null }
            },
            background = { background },
            labels = presentationLabels,
            gifDisplayMode = style.gifDisplayMode,
            highlightSettings = highlightSettings,
        )

    private fun ActiveChatSession.presentationFlow() = flow {
        val presentationCatalog = ChatPresentationCatalog()
        emitAll(combine(
            session.attachUi(
                batchIntervalMsFlow = ChatBatchingPreferences.intervalMs(recyclerView.context),
            ),
            catalog.state,
            rewardCatalog,
            rewardCatalogSettled,
            decorationCatalog,
        ) { snapshot, catalogState, rewards, rewardsSettled, decorations ->
            if (!isReadyForChatPublication(
                    catalogState = catalogState,
                    showBadges = renderStyle.showBadges,
                    messages = snapshot.messages,
                    rewardCatalogSettled = rewardsSettled,
                )
            ) {
                null
            } else {
                PresentationPublication(
                    key,
                    snapshot.messages,
                    fullSnapshot = session::snapshot,
                    timelineVersion = snapshot.version,
                    timelineDelta = snapshot.delta,
                    metadataSettlement = ChatMetadataSettlement(
                        structuralSettled = catalogState.structuralCatalogSettled,
                        badgesSettled = !renderStyle.showBadges || catalogState.badgesSettled,
                        rewardsSettled = rewardsSettled,
                    ),
                    forceRefreshRevision = catalogState.forceRefreshRevision,
                    presentationCatalog.resolve(catalogState.snapshot, rewards, decorations),
                )
            }
        }.filter { it != null }.map { it!! })
    }

    private data class PresentationPublication(
        val key: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey,
        val messages: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>,
        val fullSnapshot: suspend () -> List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>,
        val timelineVersion: Long,
        val timelineDelta: ChatTimelineDelta?,
        val metadataSettlement: ChatMetadataSettlement,
        val forceRefreshRevision: Long,
        val catalog: com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot,
    )
}

internal fun isReadyForChatPublication(
    catalogState: ChatCatalogState,
    showBadges: Boolean,
    messages: List<ChatMessage>,
    rewardCatalogSettled: Boolean,
): Boolean = catalogState.hydrated
