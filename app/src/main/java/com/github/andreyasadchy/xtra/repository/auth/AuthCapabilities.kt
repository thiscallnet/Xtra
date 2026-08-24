package com.github.andreyasadchy.xtra.repository.auth

/** The scopes Xtra requests for its official Helix account capabilities. */
val REQUIRED_OFFICIAL_SCOPES = setOf(
    "channel:edit:commercial",
    "channel:manage:broadcast",
    "channel:manage:moderators",
    "channel:manage:raids",
    "channel:manage:vips",
    "channel:moderate",
    "channel:read:polls",
    "channel:read:predictions",
    "chat:edit",
    "chat:read",
    "moderator:manage:announcements",
    "moderator:manage:banned_users",
    "moderator:manage:chat_messages",
    "moderator:manage:chat_settings",
    "moderator:read:chatters",
    "moderator:read:followers",
    "user:manage:chat_color",
    "user:manage:whispers",
    "user:edit",
    "user:read:blocked_users",
    "user:manage:blocked_users",
    "user:read:chat",
    "user:read:emotes",
    "user:read:follows",
    "user:write:chat",
)

val REAUTHORIZATION_ACCOUNT_SCOPES = REQUIRED_OFFICIAL_SCOPES

fun missingOfficialScopes(scopes: Collection<String>): Set<String> =
    REQUIRED_OFFICIAL_SCOPES - scopes.toSet()

fun hasRequiredOfficialScopes(scopes: Collection<String>): Boolean =
    missingOfficialScopes(scopes).isEmpty()

fun hasRequiredReauthorizationScopes(scopes: Collection<String>): Boolean =
    hasRequiredOfficialScopes(scopes)

fun isReauthorizationUserAllowed(
    reauthorize: Boolean,
    previousUserId: String?,
    newUserId: String?,
): Boolean =
    !reauthorize || (!previousUserId.isNullOrBlank() && previousUserId == newUserId)
