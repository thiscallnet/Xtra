package com.github.andreyasadchy.xtra.ui.chat

import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatReplayManagerTest {
    @Test
    fun stalePageReleasedAfterSeekCannotModifyCurrentReplay() = runBlocking {
        val ownerExecutor = Executors.newSingleThreadExecutor()
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val newDelivered = CompletableDeferred<Unit>()
        val loader = BlockingPageLoader(oldStarted, releaseOld, oldReturned)
        val deliveredIds = CopyOnWriteArrayList<String>()
        val repositoryExecutor = Executors.newSingleThreadExecutor()
        var currentPosition = 0L

        val manager = ChatReplayManager(
            networkLibrary = null,
            gqlHeaders = emptyMap(),
            graphQLRepository = emptyGraphQlRepository(repositoryExecutor),
            json = Json.Default,
            videoId = "video",
            createdAt = null,
            startTime = 0,
            getCurrentPosition = { currentPosition },
            getCurrentSpeed = { 1f },
            coroutineScope = scope,
            listener = object : ChatReplayManager.Listener {
                override suspend fun onChatMessage(message: ChatMessage) {
                    deliveredIds += message.id
                    if (message.id == "new") {
                        newDelivered.complete(Unit)
                    }
                }
            },
            pageLoaderOverride = loader,
        )

        try {
            withContext(ownerDispatcher) {
                manager.start()
            }
            withTimeout(1_000) { oldStarted.await() }

            currentPosition = 30_000L
            withContext(ownerDispatcher) {
                manager.updatePosition(30_000L)
            }
            withTimeout(1_000) { newDelivered.await() }

            releaseOld.complete(Unit)
            withTimeout(1_000) { oldReturned.await() }
            delay(100)

            assertEquals(
                listOf(
                    ChatReplayPageRequest.Offset(0),
                    ChatReplayPageRequest.Offset(30),
                ),
                loader.requests.toList(),
            )
            assertEquals(listOf("new"), deliveredIds.toList())
        } finally {
            withContext(ownerDispatcher) {
                manager.stop()
            }
            scope.cancel()
            ownerDispatcher.close()
            ownerExecutor.shutdownNow()
            repositoryExecutor.shutdownNow()
            repositoryExecutor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private class BlockingPageLoader(
        private val oldStarted: CompletableDeferred<Unit>,
        private val releaseOld: CompletableDeferred<Unit>,
        private val oldReturned: CompletableDeferred<Unit>,
    ) : ChatReplayPageLoader {
        val requests = CopyOnWriteArrayList<ChatReplayPageRequest>()

        override suspend fun loadGraphQl(request: ChatReplayPageRequest): ChatReplayPage {
            requests += request
            if (request == ChatReplayPageRequest.Offset(0)) {
                oldStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseOld.await()
                }
                oldReturned.complete(Unit)
                return page("old", 0)
            }
            return page("new", 30)
        }

        override suspend fun loadLegacy(request: ChatReplayPageRequest): ChatReplayPage =
            error("legacy fallback was not expected")

        private fun page(id: String, offsetSeconds: Int) = ChatReplayPage(
            messages = listOf(
                VideoChatMessage(
                    id = id,
                    offsetSeconds = offsetSeconds,
                    createdAt = null,
                    userId = null,
                    userLogin = null,
                    userName = null,
                    message = id,
                    color = null,
                    emotes = null,
                    badges = null,
                    fullMsg = null,
                ),
            ),
            hasNextPage = false,
            nextCursor = null,
        )
    }

    private fun emptyGraphQlRepository(executor: java.util.concurrent.ExecutorService) = GraphQLRepository(
        httpEngine = lazyOf<HttpEngine?>(null),
        cronetEngine = lazyOf<CronetEngine?>(null),
        cronetExecutor = lazyOf(executor),
        okHttpClient = lazyOf(OkHttpClient()),
        json = Json.Default,
    )
}
