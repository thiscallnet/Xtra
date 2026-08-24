package com.github.andreyasadchy.xtra.repository.auth

/** Describes the health of the single Gecko-backed Twitch account session. */
enum class AuthHealth {
    SIGNED_OUT,
    HEALTHY,
    REAUTH_REQUIRED,
    UNKNOWN;

    val requiresUserAction: Boolean
        get() = this == REAUTH_REQUIRED
}
