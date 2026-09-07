package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoader
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatTimelineAdapterTest {
    @Test
    fun appendOnlyNotifiesHeadRemovalAndTailInsertion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            submitAndWait(adapter, listOf(row("a"), row("b"), row("c")))
            val observer = RecordingObserver()
            onMain { adapter.registerAdapterDataObserver(observer) }

            var applied = false
            onMain { applied = adapter.append(listOf(row("b"), row("c"), row("d")), 1, 1) }

            assertTrue(applied)
            assertEquals(listOf("b", "c", "d"), adapter.currentList.map { it.id.value })
            assertEquals(listOf("remove:0:1", "insert:2:1"), observer.events.toList())
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun deltaAppendDoesNotValidateRetainedRows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            submitAndWait(adapter, listOf(row("a"), row("b"), row("c")))

            var applied = false
            onMain { applied = adapter.appendDelta(listOf(row("d")), evictedHeadCount = 1, expectedSize = 3) }

            assertTrue(applied)
            assertEquals(listOf("b", "c", "d"), adapter.currentList.map { it.id.value })
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun retainedRowMutationRejectsDirectAppendAndUsesFallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            submitAndWait(adapter, listOf(row("a", "old"), row("b")))
            val next = listOf(row("a", "changed"), row("c"))
            var applied = true
            onMain { applied = adapter.append(next, evictedHeadCount = 1, appendedCount = 1) }

            assertFalse(applied)
            submitAndWait(adapter, next)
            assertEquals(next, adapter.currentList)
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun reconciliationFallbackReplacesRowsByStableId() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            submitAndWait(adapter, listOf(row("a"), row("b")))
            submitAndWait(adapter, listOf(row("b"), row("a")))
            assertEquals(listOf("b", "a"), adapter.currentList.map { it.id.value })
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun staleFallbackDiffCannotOverwriteNewerSubmission() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = ManualDispatcher()
        val adapter = adapter(scope, dispatcher)
        try {
            val oldRows = (0 until 600).map { row("old-$it") }
            val newRows = listOf(row("new"))
            val committed = CountDownLatch(1)
            onMain { adapter.submitList(oldRows) }
            onMain { adapter.submitList(newRows) { committed.countDown() } }

            dispatcher.runNext()
            dispatcher.runNext()
            assertTrue(committed.await(1, TimeUnit.SECONDS))
            assertEquals(newRows, adapter.currentList)
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun viewportKeepsTheExistingAnchorWhenAppendingWhileScrolledUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        val anchor = ChatViewportAnchor(ChatMessageId("b"), 37)
        val controller = ChatViewportController(
            recyclerView,
            ChatViewportState(FollowMode.USER_SCROLLED_UP, anchor = anchor),
        )
        val rows = listOf(row("a"), row("b"), row("c"), row("d"))

        onMain { controller.onSnapshotCommitted(anchor, rows, appendedCount = 1) }

        assertEquals(anchor, controller.state.anchor)
        assertEquals(1, controller.state.newMessageCount)
        assertEquals(FollowMode.USER_SCROLLED_UP, controller.state.followMode)
    }

    private fun adapter(
        scope: CoroutineScope,
        diffDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = ChatTimelineAdapter(
        assets = ChatAssetRepository(scope, ChatAssetLoader { null }),
        textSizeSp = 14f,
        animateGifs = false,
        diffDispatcher = diffDispatcher,
    )

    private fun submitAndWait(adapter: ChatTimelineAdapter, rows: List<ChatRowUiModel>) {
        val committed = CountDownLatch(1)
        onMain { adapter.submitList(rows) { committed.countDown() } }
        assertTrue(committed.await(1, TimeUnit.SECONDS))
    }

    private fun dispose(adapter: ChatTimelineAdapter, scope: CoroutineScope) {
        onMain { adapter.dispose() }
        scope.cancel()
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun row(id: String, text: String = id) = ChatRowUiModel(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampText = null,
        pieces = listOf(ChatPiece.Text(text)),
        background = 0,
        accessibilityText = text,
        reply = null,
        source = null,
        isAction = false,
    )

    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        val events = ConcurrentLinkedQueue<String>()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            events += "remove:$positionStart:$itemCount"
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            events += "insert:$positionStart:$itemCount"
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue += block
        }

        fun runNext() {
            val task = checkNotNull(queue.poll()) { "No queued diff" }
            task.run()
        }
    }
}
