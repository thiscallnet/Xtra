package com.github.andreyasadchy.xtra.ui.notifications

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.ui.inbox.runCatchingInboxRequest
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production notification rows and rollback logic, with local callbacks and no account actions. */
@RunWith(AndroidJUnit4::class)
class NotificationRollbackLayoutTest {
    @Test
    fun failedDismissRestoresItsPlaceAcrossConcurrentListChanges() {
        val items = fixtures()
        val refreshed = listOf(items[0].copy(id = "new"), items[0], items[2])
        assertEquals(listOf("new", "0", "1", "2"), restoreDismissedNotification(refreshed, items, items[1]).map { it.id })
        assertEquals(items, restoreDismissedNotification(items, items, items[1]))
        assertEquals(listOf(items[0], items[1]), restoreDismissedNotification(listOf(items[0]), items, items[1]))
    }

    @Test
    fun canceledInboxRequestsDoNotRunFailureCallbacks() = runBlocking {
        var failureCalled = false
        try {
            runCatchingInboxRequest { throw CancellationException("Screen closed") }.onFailure { failureCalled = true }
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(!failureCalled)
        }
    }

    @Test
    fun dismissalAndRollbackKeepProductionRowsInOrderWithoutRedrawingUnchangedLists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val original = fixtures()
        var current = original
        lateinit var recycler: RecyclerView
        lateinit var adapter: TwitchNotificationsAdapter
        var notifications = 0
        try {
            instrumentation.runOnMainSync {
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 64, 24, 24)
                }
                root.addView(TextView(activity).apply { text = "Notification rollback\nLocal fixtures, no account actions"; textSize = 20f })
                recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                adapter = TwitchNotificationsAdapter({}, {}, {}, { item ->
                    current = current.filterNot { it.id == item.id }
                    adapter.submitList(current)
                })
                recycler.adapter = adapter
                root.addView(recycler, LinearLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                adapter.submitList(current)
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { notifications++ }
                })
                repeat(20) { adapter.submitList(current.toList()) }
                assertEquals(0, notifications)
            }
            instrumentation.waitForIdleSync()
            val evidence = InstrumentationRegistry.getArguments().getString("captureEvidence") == "true"
            if (evidence) SystemClock.sleep(1_000L)
            instrumentation.runOnMainSync {
                val holder = recycler.findViewHolderForAdapterPosition(1) ?: error("Middle notification missing")
                assertTrue(holder.itemView.findViewById<View>(R.id.dismiss).performClick())
                assertEquals(listOf("0", "2"), current.map { it.id })
            }
            if (evidence) SystemClock.sleep(1_500L)
            instrumentation.runOnMainSync {
                current = restoreDismissedNotification(current, original, original[1])
                adapter.submitList(current)
                assertEquals(original, current)
            }
            instrumentation.waitForIdleSync()
            if (evidence) {
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(activity.getExternalFilesDir(null), "notification-rollback.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    screenshot.recycle()
                }
                SystemClock.sleep(4_000L)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun fixtures() = List(3) { index ->
        TwitchNotification(index.toString(), null, null,
            listOf("First notification stays above the restored row.", "Middle notification returns here after a failed dismissal.", "Last notification stays below the restored row.")[index],
            null, null, false, true, null)
    }
}
