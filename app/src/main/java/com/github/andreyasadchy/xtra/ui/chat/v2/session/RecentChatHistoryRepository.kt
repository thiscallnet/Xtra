package com.github.andreyasadchy.xtra.ui.chat.v2.session

import android.util.Log
import com.github.andreyasadchy.xtra.model.gql.video.VideoMessagesResponse
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant

fun interface RecentChatHistorySource {
    suspend fun load(spec: LiveChatSessionSpec): List<ChatMessage>
}

data class RecentChatHistoryPage<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
    val nextCursor: String?,
)

data class RecentChatPaginationResult<T>(
    val items: List<T>,
    val reachedLiveEdge: Boolean,
)

/** Follows the forward-only replay cursor until the live edge or a bounded page limit. */
suspend fun <T> paginateRecentChatPages(
    initialOffsetSeconds: Int,
    maxPages: Int = 20,
    load: suspend (offsetSeconds: Int?, cursor: String?) -> RecentChatHistoryPage<T>,
    isAtLiveEdge: (T) -> Boolean,
): RecentChatPaginationResult<T> {
    val result = ArrayList<T>()
    var offsetSeconds: Int? = initialOffsetSeconds
    var cursor: String? = null
    repeat(maxPages.coerceAtLeast(0)) {
        val page = load(offsetSeconds, cursor)
        result += page.items
        if (page.items.isEmpty() || !page.hasNextPage || page.items.any(isAtLiveEdge)) {
            return RecentChatPaginationResult(result, reachedLiveEdge = true)
        }
        cursor = page.nextCursor ?: return RecentChatPaginationResult(result, reachedLiveEdge = false)
        offsetSeconds = null
    }
    return RecentChatPaginationResult(result, reachedLiveEdge = false)
}

/** Twitch-first history with a deliberately quiet fallback. History is best effort. */
class RecentChatHistoryRepository(
    private val twitch: RecentChatHistorySource,
    private val robotty: RecentChatHistorySource,
    private val enabled: () -> Boolean = { true },
    private val log: (String) -> Unit = { Log.d(TAG, it) },
    private val twitchTimeoutMs: Long = 15_000,
    private val robottyTimeoutMs: Long = 10_000,
) : RecentChatHistorySource {
    override suspend fun load(spec: LiveChatSessionSpec): List<ChatMessage> {
        if (!enabled()) return emptyList()
        try {
            val result = withTimeout(twitchTimeoutMs) { twitch.load(spec) }
            log("history source=twitch count=${result.size}")
            if (result.isNotEmpty()) return result
            log("history fallback=twitch-empty")
        } catch (error: TimeoutCancellationException) {
            log("history fallback=twitch-timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("history fallback=twitch-failed reason=${error.javaClass.simpleName}")
        }
        return try {
            val result = withTimeout(robottyTimeoutMs) { robotty.load(spec) }
            log("history source=robotty count=${result.size}")
            result
        } catch (error: TimeoutCancellationException) {
            log("history unavailable reason=robotty-timeout")
            emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("history unavailable reason=${error.javaClass.simpleName}")
            emptyList()
        }
    }

    companion object { private const val TAG = "RecentChatHistory" }
}

/** Converts one GQL replay comment into the same v2 domain used by live EventSub/IRC. */
fun VideoMessagesResponse.Comment.toV2(
    channelId: String,
    vodStartTimestampMs: Long? = null,
    fallbackTimestamp: Long = System.currentTimeMillis(),
): ChatMessage? {
    val id = id?.takeIf(String::isNotBlank) ?: return null
    val fragments = message?.fragments.orEmpty()
    val segments = fragments.mapNotNull { fragment ->
        val text = fragment.text ?: return@mapNotNull null
        val emoteId = fragment.emote?.emoteID
        if (emoteId == null) {
            ChatSegment.Text(text)
        } else {
            ChatSegment.Emote(
                asset = ChatAssetSpec(ChatAssetKey("https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/3.0"), 56, 56, 28),
                fallbackText = text,
                animated = true,
                interaction = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction(
                    id = emoteId,
                    name = text,
                    url = "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/3.0",
                    animated = true,
                    provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.TWITCH,
                    scope = null,
                ),
            )
        }
    }
    val body = fragments.joinToString("") { it.text.orEmpty() }
    val timestamp = createdAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        ?: contentOffsetSeconds?.times(1000L)?.let { offset -> vodStartTimestampMs?.plus(offset) }
        ?: fallbackTimestamp
    return ChatMessage(
        id = ChatMessageId(id), channelId = channelId, timestampMs = timestamp,
        user = ChatUser(
            commenter?.id,
            commenter?.login,
            commenter?.displayName,
            message?.userColor?.removePrefix("#")?.toLongOrNull(16)?.toInt(),
        ),
        badges = message?.userBadges.orEmpty().mapNotNull { badge ->
            if (badge.setID != null && badge.version != null) ChatBadgeRef(badge.setID, badge.version) else null
        },
        segments = segments, rawText = body,
        kind = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.CHAT,
    )
}
