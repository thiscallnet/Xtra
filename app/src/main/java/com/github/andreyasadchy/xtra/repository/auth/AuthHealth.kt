package com.github.andreyasadchy.xtra.repository.auth

/** Describes whether the signed-in account has all credentials Xtra can maintain. */
enum class AuthHealth {
    SIGNED_OUT,
    HEALTHY,
    REAUTH_REQUIRED,
    UNKNOWN;

    val requiresUserAction: Boolean
        get() = this == REAUTH_REQUIRED
}

internal fun classifyAuthHealth(
    officialState: OfficialAuthState,
    compatibilityState: CompatibilityAuthState,
    officialSessionComplete: Boolean,
    structuredCompatibilityPresent: Boolean,
    compatibilityUserMatches: Boolean,
    legacyCredentialPresent: Boolean,
    storedAccountIdentityPresent: Boolean = false,
): AuthHealth = when {
    officialState == OfficialAuthState.REAUTHORIZATION_REQUIRED -> AuthHealth.REAUTH_REQUIRED
    compatibilityState == CompatibilityAuthState.REAUTHORIZATION_REQUIRED -> AuthHealth.REAUTH_REQUIRED
    officialState == OfficialAuthState.TRANSIENT_FAILURE ||
        compatibilityState == CompatibilityAuthState.TRANSIENT_FAILURE -> AuthHealth.UNKNOWN
    officialState == OfficialAuthState.IDLE -> {
        if (legacyCredentialPresent || structuredCompatibilityPresent || storedAccountIdentityPresent) {
            AuthHealth.REAUTH_REQUIRED
        } else {
            AuthHealth.SIGNED_OUT
        }
    }
    !officialSessionComplete -> AuthHealth.REAUTH_REQUIRED
    compatibilityState == CompatibilityAuthState.AVAILABLE &&
        structuredCompatibilityPresent &&
        compatibilityUserMatches -> AuthHealth.HEALTHY
    else -> AuthHealth.REAUTH_REQUIRED
}
