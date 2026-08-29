package com.github.andreyasadchy.xtra.repository

import android.content.Context
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadDetails
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadPage
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom

class WhispersRepository(
    private val context: Context,
    private val privateGqlClient: TwitchPrivateGqlClient,
    private val graphQLRepository: GraphQLRepository,
    private val metadataCache: MetadataCache? = null,
) {
    private val secureRandom = SecureRandom()
    private val cacheCommitGate = MetadataCacheCommitGate()

    suspend fun getThreads(cursor: String? = null): WhisperThreadPage = fetchThreads(cursor, cacheResult = true)

    private suspend fun fetchThreads(cursor: String?, cacheResult: Boolean): WhisperThreadPage {
        val key = requireAccount()
        val generationAtStart = if (cacheResult) cacheCommitGate.generationAtStart() else null
        val variables = buildJsonObject { cursor?.let { put("cursor", it) } }
        val result = if (cursor == null) {
            try {
                privateGqlClient.executePersisted(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.whisperThreads, variables)
            } catch (error: TwitchInboxException) {
                if (error.error is com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError.PrivateApiChanged) {
                    privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.whisperThreads.operationName, TwitchPrivateGqlDocuments.whisperThreads, variables)
                } else {
                    throw error
                }
            }
        } else {
            privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.whisperThreads.operationName, TwitchPrivateGqlDocuments.whisperThreads, variables)
        }
        checkAccount(key)
        val page = parseWhisperThreadPage(result, key)
        if (generationAtStart != null) {
            cacheCommitGate.commitFetch(generationAtStart) {
                runCatching { metadataCache?.writeWhisperThreads(key, page, replace = cursor == null) }
            }
        }
        return page
    }

    suspend fun getCachedThreads(): WhisperThreadPage? =
        metadataCache?.readWhisperThreads(currentUserId())

    suspend fun getUnreadSummary(): WhisperUnreadSummary {
        return scanWhisperUnreadPages(MAX_SUMMARY_THREAD_PAGES) { cursor -> fetchThreads(cursor, cacheResult = false) }
    }

    suspend fun getThread(threadId: String, cursor: String? = null): WhisperThreadDetails {
        val key = requireAccount()
        val result = privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.whisperThread.operationName, TwitchPrivateGqlDocuments.whisperThread, buildJsonObject {
            put("id", threadId)
            cursor?.let { put("cursor", it) }
        })
        checkAccount(key)
        return parseWhisperThreadDetails(result, key)
    }

    suspend fun markThreadRead(threadId: String, lastReadMessageId: String) {
        val key = requireAccount()
        privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.whisperMarkRead.operationName, TwitchPrivateGqlDocuments.whisperMarkRead, buildJsonObject {
            putJsonObject("input") {
                put("threadID", threadId)
                put("lastReadMessageID", lastReadMessageId)
            }
        })
        checkAccount(key)
        cacheCommitGate.commitMutation {
            runCatching { metadataCache?.markWhisperThreadRead(key, threadId) }
        }
    }

    fun createWhisperNonce(): String = generateWhisperNonce(secureRandom)

    suspend fun sendWhisper(recipientUserId: String, message: String, nonce: String = createWhisperNonce()): SendWhisperResult {
        val key = requireAccount()
        val variables = buildSendWhisperVariables(recipientUserId, message, nonce)
        try {
            val result = privateGqlClient.executePersisted(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.sendWhisper, variables)
            requireSendWhisperResult(result)
        } catch (error: TwitchInboxException) {
            if (error.error is TwitchInboxError.PrivateApiChanged) {
                val result = privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.sendWhisper.operationName, TwitchPrivateGqlDocuments.sendWhisper, variables)
                requireSendWhisperResult(result)
            } else {
                throw error
            }
        }
        checkAccount(key)
        return SendWhisperResult(nonce)
    }

    suspend fun searchUsers(query: String): List<TwitchUserSummary> {
        val key = requireAccount()
        val response = graphQLRepository.loadQuerySearchChannels(networkLibrary(), webHeaders(), query, 20, null)
        checkAccount(key)
        return response.data?.searchUsers?.edges.orEmpty().mapNotNull { edge ->
            val node = edge.node ?: return@mapNotNull null
            val id = node.id ?: return@mapNotNull null
            TwitchUserSummary(id, node.login.orEmpty(), node.displayName.orEmpty().ifBlank { node.login.orEmpty() }, node.profileImageURL)
        }
    }

    suspend fun findThreadByPeer(peerId: String): String? {
        var cursor: String? = null
        repeat(20) {
            val page = fetchThreads(cursor, cacheResult = false)
            page.threads.firstOrNull { it.peer.id == peerId }?.let { return it.id }
            if (!page.hasNextPage || page.nextCursor == null || page.nextCursor == cursor) return null
            cursor = page.nextCursor
        }
        return null
    }

    suspend fun findRecentThreadByPeer(peerId: String): String? =
        fetchThreads(cursor = null, cacheResult = false).threads.firstOrNull { it.peer.id == peerId }?.id

    fun clearAccountState() = Unit

    private fun requireSendWhisperResult(result: kotlinx.serialization.json.JsonObject) {
        val data = result["data"] as? kotlinx.serialization.json.JsonObject
        if (data?.get("sendWhisper") == null) {
            throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(TwitchPrivateGqlOperations.sendWhisper.operationName))
        }
    }

    fun currentUserId(): String? = context.tokenPrefs().getString(C.USER_ID, null)

    private fun webHeaders() = TwitchApiHelper.getWebGQLHeaders(context, includeToken = true)
    private fun networkLibrary() = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    private fun requireAccount(): String {
        val token = webHeaders()[C.HEADER_TOKEN]
        val userId = context.tokenPrefs().getString(C.USER_ID, null)
        if (token.isNullOrBlank() || userId.isNullOrBlank()) throw TwitchInboxException(TwitchInboxError.SignedOut)
        return userId
    }

    private fun checkAccount(expected: String) {
        if (context.tokenPrefs().getString(C.USER_ID, null) != expected) throw TwitchInboxException(TwitchInboxError.SignedOut)
    }
}

internal fun generateWhisperNonce(random: SecureRandom): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

internal fun buildSendWhisperVariables(recipientUserId: String, message: String, nonce: String) = buildJsonObject {
    putJsonObject("input") {
        put("message", message)
        put("nonce", nonce)
        put("recipientUserID", recipientUserId)
    }
}

data class WhisperUnreadSummary(val count: Int?, val hasUnread: Boolean)

data class SendWhisperResult(val nonce: String)

private const val MAX_SUMMARY_THREAD_PAGES = 5

internal suspend fun scanWhisperUnreadPages(
    maxPages: Int,
    loadPage: suspend (String?) -> WhisperThreadPage,
): WhisperUnreadSummary {
    var cursor: String? = null
    var unreadThreads = 0
    repeat(maxPages) {
        val page = loadPage(cursor)
        unreadThreads += page.threads.count { it.isUnread }
        val nextCursor = page.nextCursor
        val canLoadNextPage = page.hasNextPage && !nextCursor.isNullOrBlank() && nextCursor != cursor
        if (unreadThreads > 0) {
            return if (canLoadNextPage) {
                WhisperUnreadSummary(null, true)
            } else {
                WhisperUnreadSummary(unreadThreads, true)
            }
        }
        if (!canLoadNextPage) return WhisperUnreadSummary(null, false)
        cursor = nextCursor
    }
    return WhisperUnreadSummary(null, unreadThreads > 0)
}
