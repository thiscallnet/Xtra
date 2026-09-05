package com.github.andreyasadchy.xtra.ui.chat.v2.ui

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
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.requiresInitialRewardMetadata
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogState
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.isReadyForChatPublication
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationLabels
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionManager
import com.github.andreyasadchy.xtra.ui.chat.ChatRenderStyle
import com.github.andreyasadchy.xtra.ui.chat.ChatProfilePopoutGesture
import com.github.andreyasadchy.xtra.ui.chat.resolveChatHighlightSettings
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

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
    private val manager: ChatSessionManager,
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
            { id -> latestPublication?.messages?.firstOrNull { it.id == id }?.let(onMessageLongClick) }
        } else null,
        onEmoteClick = onEmoteClick,
        onGifClick = onGifClick,
        onMessageClick = if (profilePopoutGesture.allowsTap) {
            { id -> latestPublication?.messages?.firstOrNull { it.id == id }?.let(onMessageLongClick) }
        } else null,
    )
    private val viewport = ChatViewportController(recyclerView, initialState)
    private var highlightSettings = resolveChatHighlightSettings(recyclerView.context)
    private val presentation = createPresentation(readableUsernameColors, backgroundColor, renderStyle)
    private var collectionJob: Job? = null
    private var styleRefreshJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var currentKey: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey? = null
    private var previousIds: Set<ChatMessageId>? = null
    private var previousTailId: ChatMessageId? = null
    private var latestRows: List<ChatRowUiModel> = emptyList()
    private var latestPublication: PresentationPublication? = null
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

    internal fun currentMessages(): List<ChatMessage> = latestPublication?.messages.orEmpty()
    internal fun currentRows(): List<ChatRowUiModel> = latestRows

    fun attach(owner: LifecycleOwner) {
        collectionJob?.cancel()
        lifecycleOwner = owner
        collectionJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rendererVisible
                    .flatMapLatest { visible ->
                        if (!visible) {
                            emptyFlow()
                        } else {
                            manager.active.flatMapLatest { active ->
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
            }
        }
    }

    /** Hides only rendering; the playback-owned session and canonical timeline continue. */
    fun setVisible(visible: Boolean) {
        if (rendererVisible.value == visible) return
        rendererVisible.value = visible
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildAt(index) as? ChatMessageTextView)?.setRenderingActive(visible)
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
        adapter.submitList(emptyList())
        latestRows = emptyList()
        previousIds = null
        previousTailId = null
        currentKey = null
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
        if (enabled) latestPublication?.let { requestTranslations(it.messages) }
    }

    /** Recompiles the current snapshot after an external presentation-only update. */
    fun invalidatePresentation() {
        val publication = latestPublication ?: return
        val owner = lifecycleOwner ?: return
        presentation.invalidate()
        styleRefreshJob?.cancel()
        styleRefreshJob = owner.lifecycleScope.launch {
            val rows = compileCurrent(publication)
            withContext(Dispatchers.Main.immediate) {
                if (!rendererVisible.value || latestPublication !== publication) return@withContext
                latestRows = rows
                onPublicationChanged(publication.messages, rows)
                adapter.submitList(rows)
            }
        }
    }

    fun jumpToNewest() {
        viewport.jumpToNewest(latestRows)
        onStateChanged(viewport.state)
    }

    private suspend fun publish(publication: PresentationPublication) {
        if (!rendererVisible.value) return
        withContext(Dispatchers.Main.immediate) {
            if (!rendererVisible.value) return@withContext
            latestPublication = publication
        }
        val rows = compileCurrent(publication)
        if (!rendererVisible.value) return
        withContext(Dispatchers.Main.immediate) {
            if (!rendererVisible.value) return@withContext
            if (latestPublication !== publication) return@withContext
            if (currentKey != publication.key) {
                if (currentKey != null) viewport.resetForNewSession()
                currentKey = publication.key
                previousIds = null
                previousTailId = null
            }
            latestPublication = publication
            requestTranslations(publication.messages)
            val oldIds = previousIds
            val previousAnchor = if (oldIds == null) null else viewport.captureAnchor(adapter)
            // Reconciliation can insert older messages into the middle/front of the timeline.
            // Only messages newer than the previous tail are live appends.
            val appendedCount = countNewLiveMessages(oldIds, previousTailId, publication.messages)
            previousIds = rows.asSequence().map(ChatRowUiModel::id).toSet()
            previousTailId = publication.messages.lastOrNull()?.id
            latestRows = rows
            onPublicationChanged(publication.messages, rows)
            adapter.submitList(rows) {
                viewport.onSnapshotCommitted(previousAnchor, rows, appendedCount)
                onStateChanged(viewport.state)
            }
        }
    }

    private suspend fun compileCurrent(publication: PresentationPublication): List<ChatRowUiModel> {
        while (true) {
            val compiler = presentation.snapshot()
            val catalogs = presentationSnapshot.catalogsFor(
                publication.key,
                publication.messages,
                publication.catalog,
                captureBadges = renderStyle.showBadges,
            )
            val rows = withContext(Dispatchers.Default) {
                if (BuildConfig.PERF_DIAGNOSTICS) Trace.beginSection("Xtra.ChatV2.compileCurrent")
                try {
                    publication.messages.zip(catalogs).map { (message, catalog) ->
                        compiler.resolve(message, catalog)
                    }
                } finally {
                    if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
                }
            }
            if (presentation.isCurrent(compiler)) return rows
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

    private fun ActiveChatSession.presentationFlow() =
        combine(
            session.attachUi(),
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
                    catalogState.snapshot.copy(
                        channelPointRewards = rewards.byId,
                        automaticChannelPointRewards = rewards.automaticByType,
                        channelPointRewardsRevision = rewards.hashCode(),
                        // The v2 catalog owns live 7TV updates. Keep the legacy snapshot as a
                        // compatibility fallback without allowing it to erase newer v2 data.
                        userDecorations = decorations.users + catalogState.snapshot.userDecorations,
                        namePaints = decorations.paints + catalogState.snapshot.namePaints,
                        sevenTvBadges = decorations.badges + catalogState.snapshot.sevenTvBadges,
                    ),
                )
            }
        }.filter { it != null }.map { it!! }

    private data class PresentationPublication(
        val key: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey,
        val messages: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>,
        val catalog: com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot,
    )
}

internal fun isReadyForChatPublication(
    catalogState: ChatCatalogState,
    showBadges: Boolean,
    messages: List<ChatMessage>,
    rewardCatalogSettled: Boolean,
): Boolean = catalogState.isReadyForChatPublication(showBadges) &&
    (rewardCatalogSettled || messages.none { it.requiresInitialRewardMetadata() })
