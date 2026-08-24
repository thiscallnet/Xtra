package com.github.andreyasadchy.xtra.repository.auth

/** Describes the official account session and its optional enhanced capability. */
enum class AuthHealth {
    SIGNED_OUT,
    HEALTHY,
    ENHANCED_FEATURES_UNAVAILABLE,
    REAUTH_REQUIRED,
    UNKNOWN;

    val requiresUserAction: Boolean
        get() = this == REAUTH_REQUIRED || this == ENHANCED_FEATURES_UNAVAILABLE
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
    else -> AuthHealth.ENHANCED_FEATURES_UNAVAILABLE
}
