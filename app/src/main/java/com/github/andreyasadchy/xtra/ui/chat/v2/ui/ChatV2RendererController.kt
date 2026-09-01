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
import java.text.DateFormat
import java.util.Date
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
    showTimestamps: Boolean = false,
    readableUsernameColors: Boolean = true,
    backgroundColor: Int = 0xFF101010.toInt(),
    private val onStateChanged: (ChatViewportState) -> Unit = {},
) {
    private val adapter = ChatTimelineAdapter(assets)
    private val viewport = ChatViewportController(recyclerView, initialState)
    private val presentation = ChatPresentationResolver(
        ChatRowCompiler(
            colors = ChatColorResolver(
                readable = readableUsernameColors,
                background = backgroundColor,
            ),
            emoteHeightPx = emoteHeightPx,
            badgeHeightPx = badgeHeightPx,
            timestampText = if (showTimestamps) {
                { timestamp -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp)) }
            } else {
                { null }
            },
            background = { backgroundColor },
        ),
    )
    private var collectionJob: Job? = null
    private var currentKey: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey? = null
    private var previousIds: Set<ChatMessageId>? = null
    private var latestRows: List<ChatRowUiModel> = emptyList()
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
