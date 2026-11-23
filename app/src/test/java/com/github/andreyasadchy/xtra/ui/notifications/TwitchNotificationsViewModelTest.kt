package com.github.andreyasadchy.xtra.ui.notifications

import androidx.lifecycle.ViewModelStore
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationPage
import com.github.andreyasadchy.xtra.repository.TwitchNotificationsDataSource
import java.time.Instant
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

@OptIn(ExperimentalCoroutinesApi::class)
class TwitchNotificationsViewModelTest {
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
    fun backgroundRefreshDoesNotPretendReadBeforeServerConfirmsIt() {
        val item = testNotification(isUnread = true)

        val refreshed = applyLocalNotificationChanges(
            items = listOf(item),
            locallyDismissedIds = emptySet(),
        )

        assertTrue(refreshed.single().isUnread)
    }

    @Test
    fun backgroundRefreshPreservesLocalDismissal() {
        val item = testNotification(isUnread = true)

        val refreshed = applyLocalNotificationChanges(
            items = listOf(item),
            locallyDismissedIds = setOf(item.id),
        )

        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun markAllAsReadFinishesAfterTheNotificationViewModelIsCleared() = runTest(mainDispatcher) {
        val repository = FakeNotificationsDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = TwitchNotificationsViewModel(repository, mutationScope)
        val viewModelStore = ViewModelStore().apply { put("notifications", viewModel) }
        var successCallbacks = 0

        try {
            runCurrent()
            viewModel.markAllAsRead { successCallbacks++ }
            runCurrent()
            assertTrue(repository.markAllStarted.isCompleted)

            viewModelStore.clear()
            repository.markAllResult.complete(setOf(repository.item.id))
            runCurrent()

            assertTrue(repository.markAllCompleted)
            assertEquals(1, successCallbacks)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    @Test
    fun markingNotificationsViewedFinishesAfterNavigationClearsTheNotificationViewModel() = runTest(mainDispatcher) {
        val repository = FakeNotificationsDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = TwitchNotificationsViewModel(repository, mutationScope)
        val viewModelStore = ViewModelStore().apply { put("notifications", viewModel) }

        try {
            runCurrent()
            assertTrue(repository.markViewedStarted.isCompleted)

            viewModelStore.clear()
            repository.markViewedResult.complete(Unit)
            runCurrent()

            assertTrue(repository.markViewedCompleted)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    @Test
    fun markReadFinishesAfterNavigationClearsTheNotificationViewModel() = runTest(mainDispatcher) {
        val repository = FakeNotificationsDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = TwitchNotificationsViewModel(repository, mutationScope)
        val viewModelStore = ViewModelStore().apply { put("notifications", viewModel) }
        var successCallbacks = 0

        try {
            runCurrent()
            viewModel.markRead(repository.item) { successCallbacks++ }
            runCurrent()
            assertTrue(repository.markReadStarted.isCompleted)

            viewModelStore.clear()
            repository.markReadResult.complete(Unit)
            runCurrent()

            assertTrue(repository.markReadCompleted)
            assertEquals(1, successCallbacks)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    @Test
    fun dismissFinishesAfterNavigationClearsTheNotificationViewModel() = runTest(mainDispatcher) {
        val repository = FakeNotificationsDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = TwitchNotificationsViewModel(repository, mutationScope)
        val viewModelStore = ViewModelStore().apply { put("notifications", viewModel) }
        var successCallbacks = 0

        try {
            runCurrent()
            viewModel.dismiss(repository.item) { successCallbacks++ }
            runCurrent()
            assertTrue(repository.dismissStarted.isCompleted)

            viewModelStore.clear()
            repository.dismissResult.complete(Unit)
            runCurrent()

            assertTrue(repository.dismissCompleted)
            assertEquals(1, successCallbacks)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    @Test
    fun pendingReadDoesNotFinishAfterAccountSwitch() = runTest(mainDispatcher) {
        val repository = FakeNotificationsDataSource()
        val mutationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = TwitchNotificationsViewModel(repository, mutationScope)
        val viewModelStore = ViewModelStore().apply { put("notifications", viewModel) }
        var successCallbacks = 0

        try {
            runCurrent()
            viewModel.markAllAsRead { successCallbacks++ }
            runCurrent()
            assertTrue(repository.markAllStarted.isCompleted)

            repository.activeUserId = "user-2"
            repository.markAllResult.complete(setOf(repository.item.id))
            runCurrent()

            assertFalse(repository.markAllCompleted)
            assertEquals(0, successCallbacks)
            assertEquals(TwitchInboxError.SignedOut, viewModel.uiState.value.error)
        } finally {
            viewModelStore.clear()
            mutationScope.cancel()
        }
    }

    private class FakeNotificationsDataSource : TwitchNotificationsDataSource {
        val item = testNotification(isUnread = true)
        val markViewedStarted = CompletableDeferred<Unit>()
        val markViewedResult = CompletableDeferred<Unit>()
        val markAllStarted = CompletableDeferred<Unit>()
        val markAllResult = CompletableDeferred<Set<String>>()
        val markReadStarted = CompletableDeferred<Unit>()
        val markReadResult = CompletableDeferred<Unit>()
        val dismissStarted = CompletableDeferred<Unit>()
        val dismissResult = CompletableDeferred<Unit>()
        var activeUserId = "user-1"
        var markViewedCompleted = false
        var markAllCompleted = false
        var markReadCompleted = false
        var dismissCompleted = false

        override fun currentUserId(): String = activeUserId

        override suspend fun getCachedNotifications(): TwitchNotificationPage? = null

        override suspend fun getNotifications(cursor: String?, limit: Int) = TwitchNotificationPage(
            notifications = listOf(item),
            nextCursor = null,
            hasNextPage = false,
            unreadCount = 1,
        )

        override suspend fun markNotificationsViewed(expectedUserId: String) {
            checkAccount(expectedUserId)
            markViewedStarted.complete(Unit)
            markViewedResult.await()
            checkAccount(expectedUserId)
            markViewedCompleted = true
        }

        override suspend fun markAllNotificationsRead(expectedUserId: String): Set<String> {
            checkAccount(expectedUserId)
            markAllStarted.complete(Unit)
            val readIds = markAllResult.await()
            checkAccount(expectedUserId)
            markAllCompleted = true
            return readIds
        }

        override suspend fun markNotificationsRead(ids: List<String>, expectedUserId: String) {
            checkAccount(expectedUserId)
            markReadStarted.complete(Unit)
            markReadResult.await()
            checkAccount(expectedUserId)
            markReadCompleted = true
        }

        override suspend fun dismissNotification(id: String, expectedUserId: String) {
            checkAccount(expectedUserId)
            dismissStarted.complete(Unit)
            dismissResult.await()
            checkAccount(expectedUserId)
            dismissCompleted = true
        }

        private fun checkAccount(expectedUserId: String) {
            if (activeUserId != expectedUserId) throw TwitchInboxException(TwitchInboxError.SignedOut)
        }
    }
}

private fun testNotification(isUnread: Boolean) = TwitchNotification(
    id = "notification-1",
    type = "SUBSCRIPTION",
    title = "A sub",
    body = "Someone subscribed",
    createdAt = Instant.EPOCH,
    imageUrl = null,
    isUnread = isUnread,
    canDismiss = true,
    action = null,
)
