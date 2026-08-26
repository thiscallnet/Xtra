package com.github.andreyasadchy.xtra.repository

data class TwitchWebOperation(
    val operationName: String,
    val sha256Hash: String? = null,
)

/**
 * Current first-party web documents. Twitch does not publish this API. Keep this registry
 * deliberately small so frontend changes have one maintenance boundary.
 * Verified against twitch.tv on 2026-08-26.
 */
object TwitchPrivateGqlOperations {
    val notificationsList = TwitchWebOperation("OnsiteNotifications_ListNotifications")
    val notificationsView = TwitchWebOperation("OnsiteNotifications_View")
    val notificationsRead = TwitchWebOperation("OnsiteNotifications_ReadNotifications")
    val notificationsDelete = TwitchWebOperation("OnsiteNotifications_DeleteNotification")
    val notificationsSummary = TwitchWebOperation("OnsiteNotifications_Summary")

    val whisperThreads = TwitchWebOperation("Whispers_Whispers_UserWhisperThreads", "9d4bf15288a0b4d96492c97dafa17222aa000528adcad4f8d1652441d9132d62")
    val whisperThread = TwitchWebOperation("Whispers_Thread_WhisperThread")
    val whisperMarkRead = TwitchWebOperation("Whispers_MarkThreadMessageRead")
    // The current unauthenticated probe recognizes this APQ hash but cannot authorize a send.
    // Re-qualify with an authenticated session if Twitch rotates the web operation.
    val sendWhisper = TwitchWebOperation("SendWhisper", "3bbd599e7891aaf3ab6a4f5788fd008f21ad0d64f6c47ea6081979f87e406c08")
}

internal object TwitchPrivateGqlDocuments {
    const val notificationsList = """
        query OnsiteNotifications_ListNotifications(${'$'}first: Int!, ${'$'}after: Cursor, ${'$'}language: String, ${'$'}displayType: OnsiteNotificationDisplayType) {
          currentUser {
            id
            notifications(first: ${'$'}first, after: ${'$'}after, language: ${'$'}language, displayType: ${'$'}displayType) {
              edges {
                cursor
                node {
                  id
                  type
                  body
                  renderStyle
                  thumbnailURL
                  createdAt
                  updatedAt
                  isRead
                  category
                  displayType
                  destinationType
                  extra {
                    __typename
                    ... on User { id login displayName profileImageURL(width: 70) }
                    ... on Video { id }
                    ... on Clip { id slug }
                    ... on Game { id name }
                  }
                  actions { id label modalID body type url }
                }
              }
              pageInfo { hasNextPage }
            }
          }
        }
    """

    const val notificationsView = """
        mutation OnsiteNotifications_View {
          viewedNotifications {
            user {
              id
              notifications { summary { unseenCount lastSeenAt } }
            }
          }
        }
    """

    const val notificationsRead = """
        mutation OnsiteNotifications_ReadNotifications(${'$'}input: ReadNotificationsInput!) {
          readNotifications(input: ${'$'}input) { notifications { id isRead } count }
        }
    """

    const val notificationsDelete = """
        mutation OnsiteNotifications_DeleteNotification(${'$'}input: DeleteNotificationInput!) {
          deleteNotification(input: ${'$'}input) { notification { id } }
        }
    """

    const val notificationsSummary = """
        query OnsiteNotifications_Summary {
          currentUser {
            id
            notifications {
              summary {
                unseenCount
                lastSeenAt
                viewerUnreadSummary { unreadCount lastReadAllAt }
                creatorUnreadSummary { unreadCount }
                safetyUnreadSummary { unreadCount }
              }
            }
          }
        }
    """

    const val whisperThreads = """
        query Whispers_Whispers_UserWhisperThreads(${'$'}cursor: Cursor) {
          currentUser {
            id
            login
            whisperSettings { isWhispersDisabledByParent }
            whisperThreads(first: 10, after: ${'$'}cursor) {
              edges {
                cursor
                node {
                  id
                  participants { id displayName login profileImageURL(width: 70) }
                  messages(first: 20) {
                    edges {
                      cursor
                      node {
                        id nonce content { content } from { id } sentAt deletedAt
                      }
                    }
                  }
                  userLastMessageRead { id sentAt }
                  unreadMessagesCount
                }
              }
            }
          }
        }
    """

    const val whisperThread = """
        query Whispers_Thread_WhisperThread(${'$'}id: ID!, ${'$'}cursor: Cursor) {
          whisperThread(id: ${'$'}id) {
            id
            participants {
              id displayName login profileImageURL(width: 70)
              self { whisperPermissions { receive isStrangerBlocked } }
            }
            messages(first: 20, after: ${'$'}cursor) {
              edges {
                cursor
                node {
                  id nonce content { content } from { id } sentAt editedAt deletedAt
                }
              }
            }
            unreadMessagesCount
          }
          currentUser { id login displayName profileImageURL(width: 70) }
        }
    """

    const val whisperMarkRead = """
        mutation Whispers_MarkThreadMessageRead(${'$'}input: UpdateWhisperThreadInput!) {
          updateWhisperThread(input: ${'$'}input) {
            thread { id unreadMessagesCount }
          }
        }
    """

    const val sendWhisper = """
        mutation SendWhisper(${'$'}input: SendWhisperInput!) {
          sendWhisper(input: ${'$'}input) { __typename }
        }
    """
}
