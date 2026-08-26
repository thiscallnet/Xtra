package com.github.andreyasadchy.xtra.repository

import android.content.Context
import com.github.andreyasadchy.xtra.model.twitchinbox.NotificationUnreadSummary
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxException
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationPage
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

class TwitchNotificationsRepository(
    private val context: Context,
    private val privateGqlClient: TwitchPrivateGqlClient,
) {
    private var accountKey: String? = null

    suspend fun getNotifications(cursor: String? = null, limit: Int = 20): TwitchNotificationPage {
        val key = requireAccount()
        val result = privateGqlClient.executeDocument(
            networkLibrary(),
            webHeaders(),
            TwitchPrivateGqlOperations.notificationsList.operationName,
            TwitchPrivateGqlDocuments.notificationsList,
            buildNotificationVariables(cursor, limit, notificationLanguage()),
        )
        checkAccount(key)
        return parseNotificationPage(result)
    }

    suspend fun markNotificationsViewed() {
        val key = requireAccount()
        privateGqlClient.executeDocument(
            networkLibrary(),
            webHeaders(),
            TwitchPrivateGqlOperations.notificationsView.operationName,
            TwitchPrivateGqlDocuments.notificationsView,
        )
        checkAccount(key)
    }

    suspend fun markAllNotificationsRead() {
        val ids = buildList {
            var cursor: String? = null
            while (true) {
                val page = getNotifications(cursor, limit = 50)
                addAll(page.notifications.filter { it.isUnread }.map { it.id })
                val next = page.nextCursor?.takeIf { page.hasNextPage && it.isNotBlank() && it != cursor }
                if (next == null) break
                cursor = next
            }
        }
        markNotificationsRead(ids)
    }

    suspend fun markNotificationsRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val key = requireAccount()
        privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.notificationsRead.operationName, TwitchPrivateGqlDocuments.notificationsRead, buildJsonObject {
            putJsonObject("input") { putJsonArray("ids") { ids.forEach { add( kotlinx.serialization.json.JsonPrimitive(it)) } } }
        })
        checkAccount(key)
    }

    suspend fun dismissNotification(id: String) {
        val key = requireAccount()
        privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.notificationsDelete.operationName, TwitchPrivateGqlDocuments.notificationsDelete, buildJsonObject {
            putJsonObject("input") { put("id", id) }
        })
        checkAccount(key)
    }

    suspend fun getUnreadSummary(): NotificationUnreadSummary {
        val key = requireAccount()
        val result = privateGqlClient.executeDocument(networkLibrary(), webHeaders(), TwitchPrivateGqlOperations.notificationsSummary.operationName, TwitchPrivateGqlDocuments.notificationsSummary)
        checkAccount(key)
        return parseNotificationSummary(result)
    }

    fun clearAccountState() {
        accountKey = null
    }

    private fun webHeaders() = TwitchApiHelper.getWebGQLHeaders(context, includeToken = true)
    private fun networkLibrary() = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    private fun notificationLanguage(): String = Locale.getDefault().language.lowercase(Locale.ROOT).takeIf { it in SUPPORTED_NOTIFICATION_LANGUAGES } ?: "en"
    private fun requireAccount(): String {
        val token = webHeaders()[C.HEADER_TOKEN]
        val userId = context.tokenPrefs().getString(C.USER_ID, null)
        if (token.isNullOrBlank() || userId.isNullOrBlank()) throw TwitchInboxException(TwitchInboxError.SignedOut)
        accountKey = userId
        return userId
    }

    private fun checkAccount(expected: String) {
        val actual = context.tokenPrefs().getString(C.USER_ID, null)
        if (actual != expected) throw TwitchInboxException(TwitchInboxError.SignedOut)
    }

}

internal fun buildNotificationVariables(cursor: String?, limit: Int, language: String) = buildJsonObject {
    put("first", limit.coerceIn(1, 50))
    cursor?.let { put("after", it) }
    put("language", language)
    put("displayType", "VIEWER")
}

private val SUPPORTED_NOTIFICATION_LANGUAGES = setOf(
    "ar", "cs", "de", "en", "es", "fr", "id", "it", "ja", "ko", "pl", "pt", "ru", "sk", "tr", "zh",
)
