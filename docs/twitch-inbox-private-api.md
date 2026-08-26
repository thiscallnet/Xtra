# Twitch inbox private API notes

Verified: 2026-08-26 against the public `twitch.tv` JavaScript bundles, authenticated Xtra read-only flows, and requests to `https://gql.twitch.tv/gql`.

This is an undocumented first-party web surface. The implementation deliberately keeps the requests in `TwitchPrivateGqlClient` and maps responses before they reach Android UI code. The existing debug emulator supplied an authenticated Twitch web session for read-only qualification. Notifications, the whisper thread list, thread history, user search, and mark-read behavior were exercised without recording account data. No account data, tokens, cookies, messages, or notification text are included here. A real message was not sent because no recipient/two-account test authorization was available.

## Authentication and transport

All private calls use `TwitchApiHelper.getWebGQLHeaders(context, includeToken = true)`. The headers come from Xtra's persistent `TwitchWebSessionManager` session and include the existing cookie, web OAuth token, client session, device, origin, referer, and user agent context.

The endpoint is:

```text
https://gql.twitch.tv/gql
```

The client routes through Xtra's configured HTTP Engine, Cronet, or OkHttp backend. No Helix endpoint, EventSub subscription, or embedded Twitch page is used.

## Capability verification

| Capability | Current operation | Method | Verified |
| --- | --- | --- | --- |
| Notification list | `OnsiteNotifications_ListNotifications` | Full document | Authenticated read-only UI |
| Notification view/read | `OnsiteNotifications_View`, `OnsiteNotifications_ReadNotifications` | Full document | Authenticated read-only UI |
| Notification delete | `OnsiteNotifications_DeleteNotification` | Full document | Schema/bundle probe; not invoked against the user account |
| Whisper threads | `Whispers_Whispers_UserWhisperThreads` | APQ with full-document fallback | Authenticated read-only UI |
| Whisper history | `Whispers_Thread_WhisperThread` | Full document | Authenticated read-only UI |
| Whisper mark-read | `Whispers_MarkThreadMessageRead` | Full document | Authenticated read-only UI |
| Whisper send | `SendWhisper` | APQ with full-document fallback | Disabled in UI pending authorized two-account qualification |
| User search | Existing `SearchChannels` / `searchUsers` | Existing generated GQL | Authenticated UI smoke test |
| Realtime | None | — | Not implemented in V1 |

## Notifications

### OnsiteNotifications_ListNotifications

Verified: 2026-08-26 from the current Onsite Notifications bundle's variables and response access paths.

Method: full GraphQL document. The older candidate persisted hash `11cdb54a2706c2c0b2969769907675680f02a6e77d8afe79a749180ad16bfea6` returned `PersistedQueryNotFound` during the unauthenticated probe and is not shipped.

Variables:

```json
{
  "first": 20,
  "after": "Cursor?",
  "language": "en",
  "displayType": "VIEWER"
}
```

The current validated field uses the Relay-style `first`/`after` cursor contract. The older generated metadata used `limit`, `cursor`, and `shouldLoadLastBroadcast`; those arguments are not part of the current schema. `language` and `displayType` remain accepted and are sent for the viewer feed.

The current public schema accepts the notification connection as `currentUser.notifications(first:, after:, language:, displayType:)`. Xtra requests the current connection document and reads `edges { cursor node { id type body renderStyle thumbnailURL createdAt updatedAt isRead category displayType destinationType extra actions { ... } } }` plus `pageInfo.hasNextPage`. The older generated metadata listed `limit`, `cursor`, and `shouldLoadLastBroadcast`; those arguments are not part of the current schema, so Xtra does not send them. The server-provided pageInfo/cursor is normalized and guarded against repeated cursors.

Pagination sends the previous page's final edge cursor as `after`. The client deduplicates notification IDs and stops on a repeated cursor, a missing cursor, or `hasNextPage == false`.

### OnsiteNotifications_View

Verified: 2026-08-26. Current bundle document:

```graphql
mutation OnsiteNotifications_View {
  viewedNotifications {
    user { id notifications { summary { unseenCount lastSeenAt } } }
  }
}
```

The candidate hash `e8e06193f8df73d04a1260df318585d1bd7a7bb447afa058e52095513f2bfa4f` was recognized by Twitch during the unauthenticated probe, but the implementation uses the current document.

Opening the native Notifications screen invokes this operation and refreshes the toolbar summary.

### OnsiteNotifications_ReadNotifications

Verified: 2026-08-26 from the current bundle.

```graphql
mutation OnsiteNotifications_ReadNotifications($input: ReadNotificationsInput!) {
  readNotifications(input: $input) { notifications { id isRead } count }
}
```

Variables:

```json
{"input":{"ids":["notification-id"]}}
```

### OnsiteNotifications_DeleteNotification

Verified: 2026-08-26. Current bundle document:

```graphql
mutation OnsiteNotifications_DeleteNotification($input: DeleteNotificationInput!) {
  deleteNotification(input: $input) { notification { id } }
}
```

Variables:

```json
{"input":{"id":"notification-id"}}
```

The candidate hash `13d463c831f28ffe17dccf55b3148ed8b3edbbd0ebadd56352f1ff0160616816` was recognized and reported the expected missing-input validation error during the unauthenticated probe. The current web UI associates deletion with persistent notifications, but the list response exposes no reliable dismissibility field and the authenticated delete mutation has not been invoked during qualification. Xtra therefore hides the dismiss affordance until that mutation is verified against a real account.

### OnsiteNotifications_Summary

Verified: 2026-08-26 from the current bundle. It reads `currentUser.notifications.summary.unseenCount` and the viewer, creator, and safety unread summaries. Xtra uses `unseenCount` for the notification badge and does not derive a count from the first page. The native summary also tracks whether Twitch explicitly reported unread state; a missing count with no unread evidence is treated as unknown/no badge, not as an unread dot.

## Whispers

### Whispers_Whispers_UserWhisperThreads

Verified: 2026-08-26 from the current Whispers bundle. The web operation is:

```graphql
query Whispers_Whispers_UserWhisperThreads($cursor: Cursor) {
  currentUser {
    id
    login
    whisperSettings { isWhispersDisabledByParent }
    whisperThreads(first: 10, after: $cursor) { edges { cursor node { ... } } }
  }
}
```

The current candidate persisted hash is:

```text
9d4bf15288a0b4d96492c97dafa17222aa000528adcad4f8d1652441d9132d62
```

The unauthenticated probe did not return `PersistedQueryNotFound`, so Xtra prefers this APQ request for the first page and falls back to the current full document if Twitch rotates the hash. Later pages use the full document so the cursor contract remains explicit.

The current web selection includes participant summaries, the first 20 message edges, `userLastMessageRead`, and `unreadMessagesCount`. The current operation does not expose a separate `pageInfo` field, so Xtra treats ten returned edges as a possible next page and stops safely on an empty or repeated-cursor response. Opening a new recipient does not search existing threads; after a successful first send, Xtra retries a first-page lookup because the new thread should be recent.

### Whispers_Thread_WhisperThread

Verified: 2026-08-26 from the current thread bundle.

```graphql
query Whispers_Thread_WhisperThread($id: ID!, $cursor: Cursor) {
  whisperThread(id: $id) {
    participants { id displayName login profileImageURL(width: 70) ... }
    messages(first: 20, after: $cursor) { edges { cursor node { id nonce content { content } from { id } sentAt editedAt deletedAt } } }
    unreadMessagesCount
  }
  currentUser { id login ... }
}
```

The first request returns the newest message window. The web client requests older history using the last returned message-edge cursor. Xtra stores `WhisperThreadDetails.nextCursor` separately from sorted messages and passes that opaque value to the next request, preserving the RecyclerView anchor when older messages are prepended. Twitch may return fewer than 20 messages while still providing a cursor, so any non-empty cursor page is treated as having older history. Canonical history is deduplicated by server message ID only because nonce values are not unique across all historical messages; nonce is reserved for reconciling optimistic local sends.

### Whispers_MarkThreadMessageRead

Verified: 2026-08-26 from the current thread bundle.

```graphql
mutation Whispers_MarkThreadMessageRead($input: UpdateWhisperThreadInput!) {
  updateWhisperThread(input: $input) { thread { id unreadMessagesCount } }
}
```

Variables:

```json
{"input":{"threadID":"thread-id","lastReadMessageID":"message-id"}}
```

This is the same semantic acknowledgement sent when the current Twitch web thread is focused. Xtra performs it after loading a thread with messages.

### SendWhisper

The current public bundle did not expose the composer mutation document in the loaded chunks. The web-compatible mutation boundary is kept isolated and uses the historically stable input shape. The current unauthenticated probe recognized the candidate APQ hash below but returned an integrity/authentication error; it therefore still requires authenticated two-account qualification before this feature can be called production-verified:

```text
Candidate APQ hash: 3bbd599e7891aaf3ab6a4f5788fd008f21ad0d64f6c47ea6081979f87e406c08
```

```graphql
mutation SendWhisper($input: SendWhisperInput!) {
  sendWhisper(input: $input) { __typename }
}
```

Variables:

```json
{"input":{"message":"...","nonce":"32-hex-characters","recipientUserID":"user-id"}}
```

The nonce is generated with `SecureRandom` as 16 bytes encoded to 32 lowercase hexadecimal characters before the optimistic native message is rendered. Xtra tries the candidate APQ request first and only falls back to the full document if Twitch reports persisted-query drift. The repository returns the nonce in `SendWhisperResult`; the ViewModel keeps the optimistic item visible until a later history response contains the same nonce, then replaces it with the canonical server message. The composer is exposed for maintainer-run two-account qualification; this workspace has not sent a message and Twitch has not published this mutation schema.

The toolbar Whisper summary scans at most five thread pages. It stops as soon as an unread thread is found on a page that may have more results, returning an unknown count so the UI shows a dot. An exact count is returned only when the connection end is known; if the bounded no-unread scan ends first, no badge is shown.

## User search

Xtra reuses its existing generated `SearchChannels` GraphQL operation, which calls Twitch GQL `searchUsers` and maps only `id`, `login`, `displayName`, and `profileImageURL`. It does not use Helix search.

## Failure handling

HTTP 200 GraphQL errors are classified by operation. Authentication errors become `RequiresReauth`; persisted-query drift becomes `PrivateApiChanged`; rate limits and server errors remain distinct. Missing required response roots are also `PrivateApiChanged`, never an empty successful feed. Private payload bodies are not logged.

## Realtime

Not implemented in V1. The current web bundle still contains private notification/pubsub integration, but a private transport would be too brittle to make core inbox behavior depend on it. Foreground refresh, pull-to-refresh, opening a screen, and post-send refresh remain authoritative.
