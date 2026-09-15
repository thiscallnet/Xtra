package com.github.andreyasadchy.xtra.repository

import android.content.Context
import com.github.andreyasadchy.xtra.model.gql.chat.WatchStreakResponse
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationAction
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

data class WatchStreakReminder(
    val notificationId: String,
    val channelId: String,
    val channelLogin: String?,
    val channelName: String?,
    val channelImageUrl: String?,
    val title: String?,
    val body: String,
    val createdAt: Instant?,
    val imageUrl: String?,
    val actionUrl: String?,
)

data class WatchStreakReminderPollResult(
    val reminders: List<WatchStreakReminder>,
    val observedCount: Int,
)

class WatchStreakReminderRepository(
    private val context: Context,
    private val twitchNotificationsRepository: TwitchNotificationsRepository,
    private val notificationsRepository: NotificationsRepository,
    private val graphQLRepository: GraphQLRepository,
    private val stateStore: WatchStreakReminderStateStore = WatchStreakReminderStateStore(context),
) {

    suspend fun poll(minimumStreak: Int): WatchStreakReminderPollResult {
        val accountId = context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
            ?: return WatchStreakReminderPollResult(emptyList(), 0)
        val firstPage = twitchNotificationsRepository.getNotifications(limit = NOTIFICATION_PAGE_SIZE)
        val currentState = stateStore.read()
        val currentIds = firstPage.notifications.map { it.id }
        if (currentState == null || currentState.accountId != accountId || !currentState.initialized) {
            stateStore.save(
                WatchStreakReminderState(
                    accountId = accountId,
                    initialized = true,
                    observedNotificationIds = currentIds.takeLast(MAX_OBSERVED_IDS),
                ),
            )
            return WatchStreakReminderPollResult(emptyList(), currentIds.size)
        }

        val observedIds = currentState.observedNotificationIds.toMutableSet()
        val unseen = loadUnseenNotifications(firstPage, observedIds)
            .sortedWith(compareBy<TwitchNotification> { it.createdAt ?: Instant.MIN }.thenBy { it.id })
        val reminders = mutableListOf<WatchStreakReminder>()
        var followedChannelIds: Set<String>? = null
        val effectiveMinimum = minimumStreak.coerceAtLeast(1)
        unseen.forEach { notification ->
            try {
                if (notification.isWatchStreakRecoveryCandidate()) {
                    val channel = notification.action as? TwitchNotificationAction.Channel
                    val channelId = channel?.id?.takeIf { it.isNotBlank() }
                    if (channelId != null) {
                        val followed = followedChannelIds ?: notificationsRepository.loadFollowedChannelIds(
                            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(context, includeToken = true),
                            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
                            userId = accountId,
                        ).also { followedChannelIds = it }
                        if (channelId in followed) {
                            val response = graphQLRepository.loadWatchStreak(
                                networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                headers = TwitchApiHelper.getGQLHeaders(context, includeToken = true),
                                channelId = channelId,
                                includeAllSuspendedStreaks = true,
                            )
                            response.errors?.firstOrNull()?.let {
                                throw GraphQLApiException(it.message ?: "Watch streak response failed", operation = "RewardList")
                            }
                            val streak = response.watchStreakCount()
                            if (streak != null && streak >= effectiveMinimum) {
                                reminders += notification.toReminder(channel)
                            }
                        }
                    }
                }
                stateStore.observe(accountId, notification.id)
                observedIds += notification.id
            } catch (e: CancellationException) {
                throw e
            }
        }
        return WatchStreakReminderPollResult(reminders, observedIds.size)
    }

    private suspend fun loadUnseenNotifications(
        firstPage: com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotificationPage,
        observedIds: Set<String>,
    ): List<TwitchNotification> {
        val unseen = firstPage.notifications.filterNot { it.id in observedIds }.toMutableList()
        var page = firstPage
        val seenCursors = mutableSetOf<String>()
        var pageCount = 1
        while (
            page.hasNextPage &&
            page.notifications.none { it.id in observedIds } &&
            pageCount < MAX_NOTIFICATION_PAGES
        ) {
            val nextCursor = nextNotificationCursorOrThrow(page, seenCursors) ?: break
            page = twitchNotificationsRepository.getNotifications(nextCursor, limit = NOTIFICATION_PAGE_SIZE)
            unseen += page.notifications.filterNot { it.id in observedIds }
            pageCount += 1
        }
        return unseen.distinctBy { it.id }
    }

    private fun TwitchNotification.toReminder(channel: TwitchNotificationAction.Channel): WatchStreakReminder =
        WatchStreakReminder(
            notificationId = id,
            channelId = requireNotNull(channel.id),
            channelLogin = channel.login,
            channelName = channel.displayName,
            channelImageUrl = channel.imageUrl,
            title = title,
            body = body,
            createdAt = createdAt,
            imageUrl = imageUrl,
            actionUrl = recoveryActionUrl(channel),
        )

    private fun TwitchNotification.recoveryActionUrl(channel: TwitchNotificationAction.Channel): String? =
        actionUrl?.takeIf(::isSafeTwitchUrl)
            ?: when (val action = action) {
                is TwitchNotificationAction.TwitchWebUrl -> action.url.takeIf(::isSafeTwitchUrl)
                is TwitchNotificationAction.Video -> "https://www.twitch.tv/videos/${action.id}"
                is TwitchNotificationAction.Clip -> "https://clips.twitch.tv/${action.slug}"
                else -> channel.login?.takeIf { it.isNotBlank() }?.let { "https://www.twitch.tv/$it" }
                    ?: "https://www.twitch.tv"
            }

    private fun WatchStreakResponse.watchStreakCount(): Int? =
        data?.channel?.self?.watchStreakMilestone?.watchStreakMilestone?.value?.jsonPrimitive?.content?.toIntOrNull()

    companion object {
        private const val NOTIFICATION_PAGE_SIZE = 20
        private const val MAX_NOTIFICATION_PAGES = 3
        private const val MAX_OBSERVED_IDS = 128
    }
}
