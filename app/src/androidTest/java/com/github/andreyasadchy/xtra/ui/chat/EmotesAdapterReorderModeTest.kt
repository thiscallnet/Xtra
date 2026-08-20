package com.github.andreyasadchy.xtra.ui.chat

import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.model.chat.Emote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmotesAdapterReorderModeTest {

    @Test
    fun doneWithoutMoveRebindsTheVisibleItems() {
        val adapter = adapterWithItems(listOf("A", "B"))
        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)

        onMain { adapter.setReorderMode(true) }
        observer.reset()
        onMain { adapter.setReorderMode(false) }

        assertTrue("Done must rebind items after leaving reorder mode", observer.changed)
        assertEquals(0, observer.moveEvents)
    }

    @Test
    fun doneDoesNotReplayACompletedMove() {
        val adapter = adapterWithItems(listOf("A", "B"))

        onMain { adapter.setReorderMode(true) }
        onMain { assertTrue(adapter.moveItem(0, 1)) }
        assertEquals(listOf("B", "A"), currentNames(adapter))

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)
        onMain { adapter.setReorderMode(false) }

        assertEquals(listOf("B", "A"), currentNames(adapter))
        assertTrue("Done must rebind items after a move", observer.changed)
        assertEquals("Done must not replay the completed move", 0, observer.moveEvents)
    }

    @Test
    fun externalUpdateStaysCoherentWhenLeavingReorderMode() {
        val adapter = adapterWithItems(listOf("A", "B", "C"))

        onMain { adapter.setReorderMode(true) }
        onMain { adapter.submitList(listOf(Emote(name = "C"), Emote(name = "A"), Emote(name = "B"))) }
        assertEquals(listOf("C", "A", "B"), currentNames(adapter))

        val observer = RecordingObserver()
        adapter.registerAdapterDataObserver(observer)
        onMain { adapter.setReorderMode(false) }

        assertEquals(listOf("C", "A", "B"), currentNames(adapter))
        assertTrue("Done must rebind after an external update", observer.changed)
        assertEquals("Done must not diff and replay an external reorder", 0, observer.moveEvents)
    }

    private fun adapterWithItems(names: List<String>): EmotesAdapter {
        val adapter = EmotesAdapter(
            fragment = Fragment(),
            clickListener = {},
            emoteQuality = "4",
            imageLibrary = "0",
            reorderable = true,
        )
        onMain { adapter.submitList(names.map(::Emote)) }
        waitForItemCount(adapter, names.size)
        return adapter
    }

    private fun waitForItemCount(adapter: EmotesAdapter, expected: Int) {
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (adapter.itemCount == expected) return
            SystemClock.sleep(10)
        }
        assertEquals(expected, adapter.itemCount)
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    private fun currentNames(adapter: EmotesAdapter): List<String?> {
        return onMainResult { adapter.currentItems().map { it.name } }
    }

    private fun <T : Any> onMainResult(action: () -> T): T {
        lateinit var result: T
        onMain { result = action() }
        return result
    }

    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        @Volatile var changed = false
        @Volatile var moveEvents = 0

        fun reset() {
            changed = false
            moveEvents = 0
        }

        override fun onChanged() {
            changed = true
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            changed = true
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            changed = true
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            moveEvents++
        }
    }
}
