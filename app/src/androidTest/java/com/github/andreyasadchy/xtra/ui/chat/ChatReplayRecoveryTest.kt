package com.github.andreyasadchy.xtra.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real HTTP repository/parser with intercepted local responses and no credentials. */
@RunWith(AndroidJUnit4::class)
class ChatReplayRecoveryTest {
    @Test
    fun failedPrimaryAndFallbackRecoverThenAdvancePastAnEmptyPage() = runBlocking {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = requests.incrementAndGet()
            if (request <= 2) throw IOException("Injected offline response")
            val edges = if (request == 3) {
                """[{"cursor":"next-page","node":null}]"""
            } else {
                """[{"cursor":"last-page","node":{"__typename":"VideoComment","id":"message-1",
                    "contentOffsetSeconds":0,"createdAt":"2026-10-07T00:00:00Z","commenter":null,
                    "message":{"__typename":"VideoCommentMessage","fragments":[{"__typename":"VideoCommentMessageFragment","text":"Recovered replay message","emote":null}],
                    "userBadges":[],"userColor":null}}}]"""
            }
            val body = """{"data":{"video":{"__typename":"Video","comments":{"__typename":"VideoCommentConnection",
                "edges":$edges,"pageInfo":{"__typename":"PageInfo","hasNextPage":${request == 3}}}}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val json = Json { ignoreUnknownKeys = true }
        val repository = GraphQLRepository(lazy { null }, lazy { null }, lazy { error("Unused transport") }, lazy { client }, json)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val received = CompletableDeferred<ChatMessage>()
        val manager = ChatReplayManager(C.OKHTTP, emptyMap(), repository, json, "local-fixture", null, 0L,
            { 0L }, { 1f }, scope, object : ChatReplayManager.Listener {
                override suspend fun onChatMessage(message: ChatMessage) { received.complete(message) }
            })
        try {
            withContext(Dispatchers.Main) { manager.start() }
            assertEquals("Recovered replay message", withTimeout(5_000L) { received.await() }.message)
            assertEquals(4, requests.get())
        } finally {
            withContext(Dispatchers.Main) { manager.stop() }
            scope.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
