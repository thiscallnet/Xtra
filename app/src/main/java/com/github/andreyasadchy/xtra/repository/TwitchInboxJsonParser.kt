package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.twitchinbox.CursorPage
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationPage
import com.github.andreyasadchy.xtra.model.twitchinbox.NotificationUnreadSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessage
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperMessagePreview
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThread
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadDetails
import com.github.andreyasadchy.xtra.model.twitchinbox.WhisperThreadPage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.time.Instant
import java.util.Locale

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String) = this[name]?.jsonArray
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()
private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
private val kotlinx.serialization.json.JsonPrimitive.booleanOrNull: Boolean?
    get() = contentOrNull?.toBooleanStrictOrNull()

private fun JsonElement.instantOrNull(): Instant? = runCatching { (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(Instant::parse) }.getOrNull()
private fun JsonObject.instant(name: String): Instant? = this[name]?.instantOrNull()

private fun requiredObject(parent: JsonObject, name: String, operation: String): JsonObject =
    parent.obj(name) ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation))

private fun dataObject(root: JsonObject, operation: String): JsonObject =
    requiredObject(root, "data", operation)

internal fun parseNotificationPage(root: JsonObject): TwitchNotificationPage {
    val operation = TwitchPrivateGqlOperations.notificationsList.operationName
    val currentUser = requiredObject(dataObject(root, operation), "currentUser", operation)
    val notifications = requiredObject(currentUser, "notifications", operation)
    val edges = notifications.array("edges") ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation))
    val items = edges.mapNotNull { edge ->
        val edgeObject = edge as? JsonObject ?: return@mapNotNull null
        val node = edgeObject.obj("node") ?: return@mapNotNull null
        parseNotification(node)
    }.distinctBy { it.id }
    val pageInfo = notifications.obj("pageInfo")
    val nextCursor = edges.lastOrNull()?.let { (it as? JsonObject)?.string("cursor") }
    val hasNextPage = pageInfo?.bool("hasNextPage") ?: (nextCursor != null && edges.size >= 20)
    return TwitchNotificationPage(items, nextCursor, hasNextPage, null)
}

internal fun parseNotification(node: JsonObject): TwitchNotification? {
    val id = node.string("id") ?: return null
    val body = node.string("body") ?: node.string("bodyMarkdown") ?: return null
    val actions = node.array("actions").orEmpty().mapNotNull { it as? JsonObject }
    val clickUrl = actions.firstOrNull {
        it.string("type")?.equals("click", ignoreCase = true) == true
    }?.string("url")
    // Twitch has returned usable notification URLs with more than one action type.
    // Prefer the click action, but do not discard a safe URL when its type changes.
    val actionUrl = clickUrl ?: actions.asSequence()
        .mapNotNull { it.string("url") }
        .firstOrNull()
    val structuredValues = listOf(
        node.string("type"),
        node.string("category"),
        node.string("destinationType"),
    )
    val action = if (
        actionUrl?.let(::isDropsTwitchUrl) == true || structuredValues.any(::isStructuredDropsValue)
    ) {
        TwitchNotificationAction.Drops(extractCampaignIdIfClearlyAvailable(actionUrl))
    } else {
        mapNotificationExtraAction(node.obj("extra"))
            ?: actionUrl?.let(::mapNotificationAction)
    }
    return TwitchNotification(
        id = id,
        type = node.string("type"),
        title = node.string("title"),
        body = body,
        createdAt = node.instant("createdAt") ?: node.instant("updatedAt"),
        imageUrl = node.string("thumbnailURL") ?: node.string("imageUrl"),
        isUnread = !(node.bool("isRead") ?: node.bool("read") ?: true),
        // The current list is rendered by Twitch's PersistentNotification component, but
        // deletion has not been authenticated against an account yet. Keep the affordance
        // hidden until that capability is qualified rather than showing a guaranteed-to-fail X.
        canDismiss = false,
        action = action,
    )
}

private fun mapNotificationExtraAction(extra: JsonObject?): TwitchNotificationAction? = when (extra?.string("__typename")) {
    "User" -> TwitchNotificationAction.Channel(
        id = extra.string("id"),
        login = extra.string("login"),
        displayName = extra.string("displayName"),
        imageUrl = extra.string("profileImageURL"),
    )
    "Video" -> extra.string("id")?.let(TwitchNotificationAction::Video)
    "Clip" -> (extra.string("slug") ?: extra.string("id"))?.let(TwitchNotificationAction::Clip)
    "Game" -> TwitchNotificationAction.Game(extra.string("id"), extra.string("name"))
    else -> null
}

internal fun parseNotificationSummary(root: JsonObject): NotificationUnreadSummary {
    val operation = TwitchPrivateGqlOperations.notificationsSummary.operationName
    val summary = requiredObject(
        requiredObject(requiredObject(dataObject(root, operation), "currentUser", operation), "notifications", operation),
        "summary",
        operation,
    )
    val count = summary.obj("unseenCount")?.int("value") ?: summary.int("unseenCount")
    val aggregate = listOf("viewerUnreadSummary", "creatorUnreadSummary", "safetyUnreadSummary")
        .mapNotNull { summary.obj(it)?.int("unreadCount") }
        .takeIf { it.isNotEmpty() }
        ?.sum()
    return NotificationUnreadSummary(count, (count ?: 0) > 0 || (aggregate ?: 0) > 0)
}

private fun mapNotificationAction(url: String?): TwitchNotificationAction {
    val safeUrl = url?.takeIf { isSafeTwitchUrl(it) } ?: return TwitchNotificationAction.None
    return TwitchNotificationAction.TwitchWebUrl(safeUrl)
}

internal fun isDropsTwitchUrl(value: String): Boolean {
    if (!isSafeTwitchUrl(value)) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
    return path == "/drops" || path == "/drops/inventory" || path.startsWith("/drops/")
}

internal fun isStructuredDropsValue(value: String?): Boolean =
    value?.uppercase(Locale.ROOT)?.contains("DROP") == true

private fun extractCampaignIdIfClearlyAvailable(value: String?): String? = runCatching {
    val query = URI(value ?: return@runCatching null).rawQuery.orEmpty()
    query.split('&').asSequence()
        .mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && (pair[0] == "campaignId" || pair[0] == "dropId")) pair[1] else null
        }
        .firstOrNull { it.isNotBlank() }
}.getOrNull()

internal fun isSafeTwitchUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    (uri.scheme.equals("https", true) && (uri.host.equals("twitch.tv", true) || uri.host?.endsWith(".twitch.tv", true) == true))
}.getOrDefault(false)

internal fun parseWhisperThreadPage(root: JsonObject, currentUserId: String): WhisperThreadPage {
    val operation = TwitchPrivateGqlOperations.whisperThreads.operationName
    val currentUser = requiredObject(dataObject(root, operation), "currentUser", operation)
    val threads = requiredObject(currentUser, "whisperThreads", operation)
    val edges = threads.array("edges") ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation))
    val items = edges.mapNotNull { edge ->
        val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
        parseWhisperThread(node, currentUserId)
    }.distinctBy { it.id }
    val nextCursor = edges.lastOrNull()?.let { (it as? JsonObject)?.string("cursor") }
    return WhisperThreadPage(items, nextCursor, nextCursor != null && edges.size >= 10, items.count { it.isUnread }.takeIf { nextCursor == null })
}

private fun parseWhisperThread(node: JsonObject, currentUserId: String): WhisperThread? {
    val id = node.string("id") ?: return null
    val peer = parsePeer(node.array("participants"), currentUserId) ?: return null
    val preview = node.obj("messages")?.array("edges").orEmpty().mapNotNull { edge ->
        parseWhisperMessage(edge as? JsonObject, currentUserId)
    }.firstOrNull()
    val unreadCount = node.int("unreadMessagesCount")
    return WhisperThread(id, peer, preview?.let { WhisperMessagePreview(it.text, it.senderId, it.sentAt) }, unreadCount, unreadCount?.let { it > 0 } == true, preview?.sentAt)
}

internal fun parseWhisperThreadDetails(root: JsonObject, currentUserId: String): WhisperThreadDetails {
    val operation = TwitchPrivateGqlOperations.whisperThread.operationName
    val data = dataObject(root, operation)
    val thread = requiredObject(data, "whisperThread", operation)
    val peer = parsePeer(thread.array("participants"), currentUserId)
        ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation))
    val edges = thread.obj("messages")?.array("edges")
        ?: throw TwitchInboxException(TwitchInboxError.PrivateApiChanged(operation))
    val messages = edges.mapNotNull { edge -> parseWhisperMessage(edge as? JsonObject, currentUserId) }
        .sortedWith(compareBy<WhisperMessage> { it.sentAt ?: Instant.MIN }.thenBy { it.id })
        .distinctBy { it.id }
    val nextCursor = edges.lastOrNull()?.let { (it as? JsonObject)?.string("cursor") }
    return WhisperThreadDetails(peer, messages, nextCursor, nextCursor != null && edges.isNotEmpty(), thread.int("unreadMessagesCount"))
}

private fun parsePeer(participants: kotlinx.serialization.json.JsonArray?, currentUserId: String): TwitchUserSummary? =
    participants.orEmpty().mapNotNull { element ->
        val participant = element as? JsonObject ?: return@mapNotNull null
        val id = participant.string("id") ?: return@mapNotNull null
        if (id == currentUserId) return@mapNotNull null
        TwitchUserSummary(id, participant.string("login").orEmpty(), participant.string("displayName").orEmpty().ifBlank { participant.string("login").orEmpty() }, participant.string("profileImageURL"))
    }.firstOrNull()

private fun parseWhisperMessage(edge: JsonObject?, currentUserId: String): WhisperMessage? {
    edge ?: return null
    val node = edge.obj("node") ?: return null
    val id = node.string("id") ?: node.string("nonce") ?: return null
    val senderId = node.obj("from")?.string("id") ?: return null
    val deleted = node.string("deletedAt") != null
    return WhisperMessage(
        id = id,
        nonce = node.string("nonce"),
        senderId = senderId,
        text = if (deleted) "" else node.obj("content")?.string("content").orEmpty(),
        sentAt = node.instant("sentAt"),
        isMine = senderId == currentUserId,
        cursor = edge.string("cursor"),
    )
}
