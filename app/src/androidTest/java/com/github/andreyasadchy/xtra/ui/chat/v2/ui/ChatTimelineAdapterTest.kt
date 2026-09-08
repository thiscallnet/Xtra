package com.github.andreyasadchy.xtra.ui.chat.v2.ui

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.ui.chat.v2.ChatMessageTextViewTestActivity
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoader
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatImageHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatTimelineAdapterTest {
    private class ExtraLayoutLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun getExtraLayoutSpace(state: RecyclerView.State): Int = 200
    }

    @Test
    fun appendOnlyNotifiesHeadRemovalAndTailInsertion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            submitAndWait(adapter, listOf(row("a"), row("b"), row("c")))
            val observer = RecordingObserver(adapter)
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
    fun replaceAllReplacesRowsForReconciliation() {
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
    fun replaceAllCommitsBeforeAHighVolumeDeltaBurst() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            onMain { adapter.replaceAll((0 until 600).map { row("initial-$it") }) }
            repeat(100) { index ->
                var applied = false
                onMain {
                    applied = adapter.appendDelta(
                        appendedRows = listOf(row("tail-$index")),
                        evictedHeadCount = 1,
                        expectedSize = 600,
                    )
                }
                assertTrue(applied)
            }
            assertEquals(600, adapter.itemCount)
            assertEquals("tail-99", adapter.currentList.last().id.value)
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun rejectedDeltaLeavesRowsUntouchedAndTheNextDeltaApplies() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            onMain { adapter.replaceAll(listOf(row("a"), row("b"), row("c"))) }
            var applied = true
            onMain {
                applied = adapter.appendDelta(
                    appendedRows = listOf(row("d")),
                    evictedHeadCount = 1,
                    expectedSize = 99,
                )
            }
            assertFalse(applied)
            assertEquals(listOf("a", "b", "c"), adapter.currentList.map { it.id.value })

            onMain {
                applied = adapter.appendDelta(
                    appendedRows = listOf(row("d")),
                    evictedHeadCount = 1,
                    expectedSize = 3,
                )
            }
            assertTrue(applied)
            assertEquals(listOf("b", "c", "d"), adapter.currentList.map { it.id.value })
        } finally {
            dispose(adapter, scope)
        }
    }

    @Test
    fun rangeNotificationsSeeTheMatchingIntermediateSnapshots() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = adapter(scope)
        try {
            onMain { adapter.replaceAll(listOf(row("a"), row("b"), row("c"))) }
            val observer = RecordingObserver(adapter)
            onMain { adapter.registerAdapterDataObserver(observer) }

            onMain { adapter.appendDelta(listOf(row("d")), evictedHeadCount = 1, expectedSize = 3) }

            assertEquals(listOf("b,c", "b,c,d"), observer.snapshots.toList())
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

    @Test
    fun animationBudgetUsesNewestViewportVisibleAnimatedRowsOnly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val drawables = ConcurrentHashMap<String, RecordingAnimatedDrawable>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
            ChatImageHandle { RecordingAnimatedDrawable().also { drawables[key.value] = it } }
        })
        val timelineAdapter = ChatTimelineAdapter(repository, textSizeSp = 14f, animateGifs = true)
        val rows = (0 until 14).map { index -> animatedRow(index, animated = index % 2 == 0) }
        val scenario = ActivityScenario.launch<ChatMessageTextViewTestActivity>(
            Intent().setComponent(
                ComponentName(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    ChatMessageTextViewTestActivity::class.java,
                ),
            ),
        )
        lateinit var recyclerView: RecyclerView
        scenario.onActivity { activity ->
            recyclerView = RecyclerView(activity).apply {
                layoutManager = ExtraLayoutLinearLayoutManager(activity)
                adapter = timelineAdapter
            }
            activity.root.addView(recyclerView, FrameLayout.LayoutParams(320, 160))
            timelineAdapter.setAnimationBudget(2)
            timelineAdapter.replaceAll(rows)
        }
        try {
            awaitUiIdle()
            val visiblePositions = onMainResult { visiblePositions(recyclerView) }
            assertTrue(visiblePositions.size >= 2)
            awaitDrawables(drawables, visiblePositions.map { "budget-$it" })
            val expectedRunning = visiblePositions
                .filter { it % 2 == 0 }
                .takeLast(2)
                .map { "budget-$it" }
                .toSet()
            val running = drawables.filterValues { it.isRunning }.keys
            assertEquals(expectedRunning, running)
            val attachedClipped = onMainResult {
                attachedPositions(recyclerView).filterNot(visiblePositions::contains)
            }
            assertTrue(attachedClipped.isNotEmpty())
            assertTrue(attachedClipped.all { drawables["budget-$it"]?.isRunning != true })
            onMain { timelineAdapter.setAnimationBudget(0) }
            awaitUiIdle()
            assertTrue(drawables.values.none { it.isRunning })
        } finally {
            scenario.close()
            scope.cancel()
        }
    }

    @Test
    fun animationBudgetReassignsNewestVisibleRowsAfterScroll() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val drawables = ConcurrentHashMap<String, RecordingAnimatedDrawable>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
            ChatImageHandle { RecordingAnimatedDrawable().also { drawables[key.value] = it } }
        })
        val timelineAdapter = ChatTimelineAdapter(repository, textSizeSp = 14f, animateGifs = true)
        val rows = (0 until 20).map { index -> animatedRow(index) }
        val scenario = ActivityScenario.launch<ChatMessageTextViewTestActivity>(
            Intent().setComponent(
                ComponentName(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    ChatMessageTextViewTestActivity::class.java,
                ),
            ),
        )
        lateinit var recyclerView: RecyclerView
        scenario.onActivity { activity ->
            recyclerView = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = timelineAdapter
            }
            activity.root.addView(recyclerView, FrameLayout.LayoutParams(320, 160))
            timelineAdapter.setAnimationBudget(2)
            timelineAdapter.replaceAll(rows)
        }
        try {
            awaitUiIdle()
            onMain { recyclerView.scrollToPosition(10) }
            awaitUiIdle()
            val visiblePositions = onMainResult { visiblePositions(recyclerView) }
            assertTrue(visiblePositions.isNotEmpty())
            awaitDrawables(drawables, visiblePositions.map { "budget-$it" })
            val expectedRunning = visiblePositions.takeLast(2).map { "budget-$it" }.toSet()
            val running = drawables.filterValues { it.isRunning }.keys
            assertEquals(expectedRunning, running)
        } finally {
            scenario.close()
            scope.cancel()
        }
    }

    private fun adapter(scope: CoroutineScope) = ChatTimelineAdapter(
        assets = ChatAssetRepository(scope, ChatAssetLoader { null }),
        textSizeSp = 14f,
        animateGifs = false,
    )

    private fun animatedRow(index: Int, animated: Boolean = true) = ChatRowUiModel(
        id = ChatMessageId("row-$index"),
        channelId = "channel",
        timestampText = null,
        pieces = listOf(
            ChatPiece.Text("message-$index "),
            ChatPiece.Emote(
                asset = ChatAssetSpec(ChatAssetKey("budget-$index"), 20, 20, 28),
                fallback = "EMOTE",
                animated = animated,
            ),
        ),
        background = 0,
        accessibilityText = "message-$index",
        reply = null,
        source = null,
        isAction = false,
    )

    private fun visiblePositions(recyclerView: RecyclerView): List<Int> {
        val layoutManager = checkNotNull(recyclerView.layoutManager)
        val bounds = Rect()
        val left = recyclerView.paddingLeft
        val top = recyclerView.paddingTop
        val right = recyclerView.width - recyclerView.paddingRight
        val bottom = recyclerView.height - recyclerView.paddingBottom
        return (0 until recyclerView.childCount).mapNotNull { index ->
            val child = recyclerView.getChildAt(index)
            layoutManager.getDecoratedBoundsWithMargins(child, bounds)
            if (
                bounds.left < right && bounds.right > left &&
                bounds.top < bottom && bounds.bottom > top
            ) recyclerView.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION } else null
        }.sorted()
    }

    private fun attachedPositions(recyclerView: RecyclerView): List<Int> =
        (0 until recyclerView.childCount).mapNotNull { index ->
            recyclerView.getChildAdapterPosition(recyclerView.getChildAt(index))
                .takeIf { it != RecyclerView.NO_POSITION }
        }.sorted()

    private fun awaitDrawables(
        drawables: Map<String, RecordingAnimatedDrawable>,
        expectedKeys: Collection<String>,
    ) {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline) {
            awaitUiIdle()
            if (drawables.keys.containsAll(expectedKeys)) return
            Thread.sleep(10)
        }
        assertTrue(drawables.keys.containsAll(expectedKeys))
    }

    private fun awaitUiIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(40)
    }

    private fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        onMain { result = block() }
        return checkNotNull(result)
    }

    private fun submitAndWait(adapter: ChatTimelineAdapter, rows: List<ChatRowUiModel>) {
        val committed = CountDownLatch(1)
        onMain { adapter.replaceAll(rows) { committed.countDown() } }
        assertTrue(committed.await(1, TimeUnit.SECONDS))
    }

    private fun dispose(adapter: ChatTimelineAdapter, scope: CoroutineScope) {
        onMain { adapter.dispose() }
        scope.cancel()
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class RecordingAnimatedDrawable : Drawable(), Animatable {
        private var running = false

        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun isRunning(): Boolean = running
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

    private class RecordingObserver(private val adapter: ChatTimelineAdapter) : RecyclerView.AdapterDataObserver() {
        val events = ConcurrentLinkedQueue<String>()
        val snapshots = ConcurrentLinkedQueue<String>()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            events += "remove:$positionStart:$itemCount"
            snapshots += adapter.currentList.joinToString(",") { it.id.value }
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            events += "insert:$positionStart:$itemCount"
            snapshots += adapter.currentList.joinToString(",") { it.id.value }
        }
    }
}
