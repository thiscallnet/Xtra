package com.github.andreyasadchy.xtra.repository.auth

val REAUTHORIZATION_ACCOUNT_SCOPES = setOf(
    "user:edit",
    "user:read:blocked_users",
    "user:manage:blocked_users",
)

fun hasRequiredReauthorizationScopes(scopes: Collection<String>): Boolean =
    REAUTHORIZATION_ACCOUNT_SCOPES.all(scopes::contains)

fun isReauthorizationUserAllowed(
    reauthorize: Boolean,
    previousUserId: String?,
    newUserId: String?,
): Boolean =
    !reauthorize || (!previousUserId.isNullOrBlank() && previousUserId == newUserId)
