package com.github.andreyasadchy.xtra.ui.whispers

import androidx.lifecycle.ViewModelStore
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadDetails
import com.github.andreyasadchy.xtra.repository.SendWhisperResult
import com.github.andreyasadchy.xtra.repository.WhisperThreadDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperThreadViewModelTest {
    private val mainDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun useTestMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun markingThreadReadFinishesAfterTheThreadViewModelIsCleared() = runTest(mainDispatcher) {
        val repository = FakeWhisperThreadDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var invalidations = 0
        var receiptDeliveredAfterClear = false
        val viewModel = WhisperThreadViewModel(repository, testPeer, "thread-1", mutationScope) { _, viewModelCleared ->
            invalidations++
            receiptDeliveredAfterClear = viewModelCleared
        }
        val viewModelStore = ViewModelStore().apply { put("whisper-thread", viewModel) }

        try {
            runCurrent()
            viewModel.setReadEligible(true)
            runCurrent()
            assertTrue(repository.markReadStarted.isCompleted)

            viewModelStore.clear()
            repository.markReadResult.complete(Unit)
            runCurrent()

            assertTrue(repository.markReadCompleted)
            assertEquals(1, invalidations)
            assertTrue(receiptDeliveredAfterClear)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    @Test
    fun pendingThreadReadDoesNotCommitAfterAccountSwitch() = runTest(mainDispatcher) {
        val repository = FakeWhisperThreadDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = WhisperThreadViewModel(repository, testPeer, "thread-1", mutationScope) { _, _ -> }
        val viewModelStore = ViewModelStore().apply { put("whisper-thread", viewModel) }

        try {
            runCurrent()
            viewModel.setReadEligible(true)
            runCurrent()
            assertTrue(repository.markReadStarted.isCompleted)

            repository.activeUserId = "user-2"
            repository.markReadResult.complete(Unit)
            runCurrent()

            assertFalse(repository.markReadCompleted)
            assertEquals(TwitchInboxError.SignedOut, viewModel.uiState.value.error)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    private class FakeWhisperThreadDataSource : WhisperThreadDataSource {
        val markReadStarted = CompletableDeferred<Unit>()
        val markReadResult = CompletableDeferred<Unit>()
        var activeUserId = "user-1"
        var markReadCompleted = false

        override fun currentUserId(): String = activeUserId

        override suspend fun getThread(threadId: String, cursor: String?) = WhisperThreadDetails(
            peer = testPeer,
            messages = listOf(testMessage),
            nextCursor = null,
            hasOlderMessages = false,
            unreadCount = 1,
        )

        override suspend fun markThreadRead(threadId: String, lastReadMessageId: String, expectedUserId: String) {
            checkAccount(expectedUserId)
            markReadStarted.complete(Unit)
            markReadResult.await()
            checkAccount(expectedUserId)
            markReadCompleted = true
        }

        override fun createWhisperNonce() = "fixture-nonce"

        override suspend fun sendWhisper(recipientUserId: String, message: String, nonce: String) = SendWhisperResult(nonce)

        override suspend fun findRecentThreadByPeer(peerId: String): String? = "thread-1"

        private fun checkAccount(expectedUserId: String) {
            if (activeUserId != expectedUserId) throw TwitchInboxException(TwitchInboxError.SignedOut)
        }
    }

    private companion object {
        val testPeer = TwitchUserSummary("peer-1", "peer", "Peer", null)
        val testMessage = WhisperMessage(
            id = "message-1",
            nonce = null,
            senderId = "peer-1",
            text = "Hello",
            sentAt = Instant.EPOCH,
            isMine = false,
        )
    }
}
