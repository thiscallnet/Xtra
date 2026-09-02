package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPresentationResolver
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionManager
import com.github.andreyasadchy.xtra.ui.chat.ChatRenderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

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
    showBadges: Boolean = true,
    enableOverlayEmotes: Boolean = true,
    timestampFormat: String? = "0",
    showTimestamps: Boolean = false,
    private val readableUsernameColors: Boolean = true,
    private val backgroundColor: Int = 0xFF101010.toInt(),
    private val onStateChanged: (ChatViewportState) -> Unit = {},
) {
    private var renderStyle = ChatRenderStyle(
        textSizeSp = messageTextSizeSp,
        emoteHeightPx = emoteHeightPx,
        badgeHeightPx = badgeHeightPx,
        animateGifs = animateGifs,
        showBadges = showBadges,
        enableOverlayEmotes = enableOverlayEmotes,
        showTimestamps = showTimestamps,
        timestampFormat = timestampFormat,
    )
    private val adapter = ChatTimelineAdapter(assets, renderStyle.textSizeSp, renderStyle.animateGifs)
    private val viewport = ChatViewportController(recyclerView, initialState)
    private val presentation = createPresentation(readableUsernameColors, backgroundColor, renderStyle)
    private var collectionJob: Job? = null
    private var currentKey: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey? = null
    private var previousIds: Set<ChatMessageId>? = null
    private var latestRows: List<ChatRowUiModel> = emptyList()
    private var latestPublication: PresentationPublication? = null
    private val rendererVisible = MutableStateFlow(true)

    init {
        recyclerView.itemAnimator = null
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter
    }

    val state: ChatViewportState
        get() = viewport.state

    fun attach(owner: LifecycleOwner) {
        collectionJob?.cancel()
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
        rendererVisible.value = false
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildAt(index) as? ChatMessageTextView)?.setRenderingActive(false)
        }
        recyclerView.adapter = null
        adapter.submitList(emptyList())
        latestRows = emptyList()
        previousIds = null
        currentKey = null
    }

    fun onUserScroll() {
        viewport.onUserScroll()
        onStateChanged(viewport.state)
    }

    internal fun refreshStyle(style: ChatRenderStyle) {
        renderStyle = style
        presentation.replaceCompiler(createPresentationCompiler(style))
        adapter.setMessageTextSizeSp(style.textSizeSp)
        adapter.setAnimateGifs(style.animateGifs)
        val publication = latestPublication ?: return
        val rows = publication.messages.map { presentation.resolve(it, publication.catalog) }
        latestRows = rows
        adapter.submitList(rows.toList())
    }

    fun jumpToNewest() {
        viewport.jumpToNewest(latestRows)
        onStateChanged(viewport.state)
    }

    private suspend fun publish(publication: PresentationPublication) {
        val rows = withContext(Dispatchers.Default) {
            publication.messages.map { presentation.resolve(it, publication.catalog) }
        }
        if (!rendererVisible.value) return
        withContext(Dispatchers.Main.immediate) {
            if (!rendererVisible.value) return@withContext
            if (currentKey != publication.key) {
                if (currentKey != null) viewport.resetForNewSession()
                currentKey = publication.key
                previousIds = null
            }
            latestPublication = publication
            val oldIds = previousIds
            val previousAnchor = if (oldIds == null) null else viewport.captureAnchor(adapter)
            val appendedCount = oldIds?.let { ids -> rows.count { it.id !in ids } } ?: 0
            previousIds = rows.asSequence().map(ChatRowUiModel::id).toSet()
            latestRows = rows
            adapter.submitList(rows.toList()) {
                viewport.onSnapshotCommitted(previousAnchor, rows, appendedCount)
                onStateChanged(viewport.state)
            }
        }
    }

    private fun createPresentation(readable: Boolean, background: Int, style: ChatRenderStyle) =
        ChatPresentationResolver(createPresentationCompiler(style, readable, background))

    private fun createPresentationCompiler(style: ChatRenderStyle, readable: Boolean = readableUsernameColors, background: Int = backgroundColor) =
        ChatRowCompiler(
            colors = ChatColorResolver(readable = readable, background = background),
            emoteHeightPx = style.emoteHeightPx,
            badgeHeightPx = style.badgeHeightPx,
            showBadges = style.showBadges,
            enableOverlayEmotes = style.enableOverlayEmotes,
            timestampText = if (style.showTimestamps) {
                { timestamp -> com.github.andreyasadchy.xtra.util.TwitchApiHelper.getTimestamp(timestamp, style.timestampFormat) }
            } else {
                { null }
            },
            background = { background },
        )

    private fun ActiveChatSession.presentationFlow() =
        combine(
            session.attachUi(),
            catalog.state,
        ) { snapshot, catalogState ->
            if (!catalogState.hydrated) null else PresentationPublication(key, snapshot.messages, catalogState.snapshot)
        }.filter { it != null }.map { it!! }

    private data class PresentationPublication(
        val key: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey,
        val messages: List<com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage>,
        val catalog: com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot,
    )
}
