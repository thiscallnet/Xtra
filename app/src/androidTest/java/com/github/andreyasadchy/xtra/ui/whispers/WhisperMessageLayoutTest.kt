package com.github.andreyasadchy.xtra.ui.whispers

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentWhisperThreadBinding
import com.github.andreyasadchy.xtra.model.twitchinbox.LocalSendState
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures production rows with local fixtures, without sending authenticated messages. */
@RunWith(AndroidJUnit4::class)
class WhisperMessageLayoutTest {
    @Test
    fun composerUpdatesDoNotRebindMessages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        lateinit var binding: FragmentWhisperThreadBinding
        lateinit var adapter: WhisperMessagesAdapter
        val peer = TwitchUserSummary("fixture", "fixture", "Local layout fixtures", null)
        val messages = listOf(
            WhisperMessage("1", null, "fixture", "Short incoming message", null, false),
            WhisperMessage("2", null, "fixture", "Short outgoing message", null, true),
            WhisperMessage("3", null, "fixture", "A long outgoing message wraps beside the avatar, even on a narrow phone. ".repeat(3), null, true),
            WhisperMessage("4", null, "fixture", "A failed message keeps its retry label inside the row.", null, false,
                localState = LocalSendState.FAILED, sendError = "Temporary failure"),
        )
        try {
            instrumentation.runOnMainSync {
                binding = FragmentWhisperThreadBinding.inflate(activity.layoutInflater)
                activity.setContentView(binding.root)
                binding.toolbar.title = "Whisper layout verification"
                binding.peerName.text = peer.displayName
                binding.peerLogin.text = "No network messages are sent"
                binding.progress.visibility = View.GONE
                binding.send.isEnabled = false
                adapter = WhisperMessagesAdapter(peer, peer, {}, {})
                binding.recyclerView.layoutManager = LinearLayoutManager(activity)
                binding.recyclerView.adapter = adapter
                adapter.submitList(messages)
            }
            instrumentation.waitForIdleSync()
            var notifications = 0
            instrumentation.runOnMainSync {
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { notifications++ }
                    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { notifications++ }
                })
            }
            val text = "Typing leaves existing message rows alone"
            for (length in 1..text.length) {
                instrumentation.runOnMainSync {
                    binding.composer.setText(text.take(length))
                    adapter.submitList(messages)
                }
                if (InstrumentationRegistry.getArguments().getString("captureEvidence") == "true") SystemClock.sleep(80L)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, notifications)
            if (InstrumentationRegistry.getArguments().getString("captureEvidence") == "true") {
                val file = File(activity.getExternalFilesDir(null), "whisper-layout-verification.png")
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    screenshot.recycle()
                }
                SystemClock.sleep(8_000L)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun longIncomingAndOutgoingRowsFitNarrowPhonesAndRtl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.runOnMainSync {
                val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val peer = TwitchUserSummary("fixture", "fixture", "Fixture", null)
                val adapter = WhisperMessagesAdapter(peer, peer, {}, {})
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    parent.layoutDirection = direction
                    for (widthDp in listOf(240, 320, 600)) {
                        for (mine in listOf(false, true)) {
                            val holder = adapter.onCreateViewHolder(parent, if (mine) 1 else 0)
                            holder.bind(WhisperMessage("fixture", null, "fixture",
                                "A long message that must wrap inside the available width. ".repeat(8),
                                null, mine, localState = LocalSendState.FAILED, sendError = "Temporary failure"))
                            val root = holder.itemView
                            parent.addView(root)
                            val width = (widthDp * context.resources.displayMetrics.density).toInt()
                            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            parent.layout(0, 0, width, parent.measuredHeight)
                            val row = root.findViewById<View>(R.id.messageRow)
                            val avatar = root.findViewById<View>(R.id.avatar)
                            val column = root.findViewById<View>(R.id.messageColumn)
                            assertTrue("Column clipped at $widthDp dp, mine=$mine, direction=$direction",
                                column.left >= 0 && column.right <= row.width)
                            assertTrue("Avatar clipped", avatar.left >= 0 && avatar.right <= row.width)
                            assertTrue("Avatar overlaps message", column.right <= avatar.left || avatar.right <= column.left)
                            parent.removeAllViews()
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
