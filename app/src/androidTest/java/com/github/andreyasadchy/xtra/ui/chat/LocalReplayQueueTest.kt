package com.github.andreyasadchy.xtra.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalReplayQueueTest {
    @Test
    fun replacingAnInFlightLiveLoadWithVodChatPublishesOnlyTheNewQueueInOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val completed = CompletableDeferred<Unit>()
        val received = mutableListOf<String?>()
        val manager = ChatReplayManagerLocal(1_000L, { 0L }, { 1f }, scope,
            object : ChatReplayManager.Listener {
                override suspend fun onChatMessage(message: ChatMessage) {
                    received.add(message.id)
                    if (received.size == 5_000) completed.complete(Unit)
                }
            })
        try {
            val live = List(10_000) { ChatMessage(id = "old-$it", timestamp = 1_000L) }
            val replay = List(5_000) { VideoChatMessage("new-$it", 0, null, null, null, null, "Message $it", null, null, null, null) }
            withContext(Dispatchers.Main) {
                manager.setMessages(live, emptyList(), 1_000L)
                manager.startLoad()
                manager.setMessages(emptyList(), replay, 0L)
            }
            withTimeout(5_000L) { completed.await() }
            assertEquals(List(5_000) { "new-$it" }, received)
        } finally {
            withContext(Dispatchers.Main) { manager.stop() }
            scope.cancel()
        }
    }
}
