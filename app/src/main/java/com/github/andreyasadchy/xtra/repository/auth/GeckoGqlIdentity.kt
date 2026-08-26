package com.github.andreyasadchy.xtra.repository.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The request identity captured from one authenticated GeckoView GQL request.
 *
 * Cookies are deliberately not part of this snapshot because the live Gecko
 * cookie jar can change without changing the account identity.
 */
data class GeckoGqlIdentity(
    val authorization: String,
    val clientId: String,
    val clientIntegrity: String,
    val xDeviceId: String,
    val clientSessionId: String?,
    val userId: String,
    val authTokenFingerprint: String,
    val capturedAt: Long,
) {
    internal fun accessTokenOrNull(): String? = authorization
        .takeIf { it.startsWith("OAuth ", ignoreCase = true) }
        ?.substring("OAuth ".length)
        ?.takeIf { it.isNotBlank() }

    /** The browser cookie jar must still represent this exact OAuth session. */
    internal fun matchesCookieHeader(cookieHeader: String?): Boolean {
        val cookieAccessToken = cookieHeader
            ?.split(';')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = part.substring(0, separator).trim()
                if (!name.equals("auth-token", ignoreCase = true)) return@mapNotNull null
                part.substring(separator + 1).trim()
            }
            ?.firstOrNull()
            ?: return false
        return accessTokenOrNull() == cookieAccessToken
    }

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        capturedAt <= 0L || nowMillis < capturedAt ||
            nowMillis - capturedAt >= MAX_AGE_MILLIS

    fun canProtectMutations(nowMillis: Long = System.currentTimeMillis()): Boolean =
        !isExpired(nowMillis) && authorization.isNotBlank() && clientId.isNotBlank() &&
            clientIntegrity.isNotBlank() && xDeviceId.isNotBlank() &&
            userId.isNotBlank() && authTokenFingerprint.isNotBlank()

    companion object {
        const val MAX_AGE_MILLIS = 16 * 60 * 60 * 1_000L

        fun fingerprintForAccessToken(accessToken: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(accessToken.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** A request-ready view of one captured identity and its current same-session cookies. */
data class GeckoGqlRequest(
    val identity: GeckoGqlIdentity,
    val headers: Map<String, String>,
)

/** Compare the exact snapshot used by a request before invalidating it. */
internal fun isCurrentGeckoGqlIdentity(
    current: GeckoGqlIdentity?,
    identityUsed: GeckoGqlIdentity,
): Boolean = current == identityUsed
