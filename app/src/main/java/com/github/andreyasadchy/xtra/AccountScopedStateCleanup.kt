package com.github.andreyasadchy.xtra

internal suspend fun clearAccountScopedState(
    disableScheduler: () -> Unit,
    disableNotifications: () -> Unit,
    clearNotificationState: suspend () -> Unit,
    clearAccountMetadata: suspend () -> Unit,
    clearTwitchInboxState: suspend () -> Unit = {},
) {
    disableScheduler()
    disableNotifications()
    clearNotificationState()
    clearTwitchInboxState()
    clearAccountMetadata()
}
