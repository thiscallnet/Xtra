package com.github.andreyasadchy.xtra.repository.auth

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A short-lived, redacted-in-logs snapshot of headers observed in Twitch web GQL traffic. */
internal class TwitchWebGqlHeaderState private constructor(
    val headers: Map<String, String>,
    val accountId: String?,
    val capturedAtMillis: Long,
    val integrityExpiresAtMillis: Long?,
) {

    fun isUsable(currentAccountId: String?, nowMillis: Long): Boolean {
        if (currentAccountId.isNullOrBlank() || currentAccountId != accountId) return false
        if (headers[CLIENT_INTEGRITY].isNullOrBlank()) return false
        if (nowMillis - capturedAtMillis > MAX_AGE_MILLIS) return false
        if (integrityExpiresAtMillis != null && integrityExpiresAtMillis <= nowMillis + EXPIRY_SAFETY_WINDOW_MILLIS) return false
        return true
    }

    fun matchesAccessToken(accessToken: String): Boolean {
        val authorization = headers[AUTHORIZATION] ?: return false
        return authorization == "OAuth $accessToken" || authorization == "Bearer $accessToken"
    }

    fun withAccount(accountId: String): TwitchWebGqlHeaderState = TwitchWebGqlHeaderState(
        headers = headers,
        accountId = accountId,
        capturedAtMillis = capturedAtMillis,
        integrityExpiresAtMillis = integrityExpiresAtMillis,
    )

    companion object {
        const val AUTHORIZATION = "Authorization"
        const val ACCEPT = "Accept"
        const val ACCEPT_ENCODING = "Accept-Encoding"
        const val ACCEPT_LANGUAGE = "Accept-Language"
        const val CLIENT_ID = "Client-Id"
        const val CLIENT_INTEGRITY = "Client-Integrity"
        const val CLIENT_SESSION_ID = "Client-Session-Id"
        const val CLIENT_VERSION = "Client-Version"
        const val CONTENT_LENGTH = "Content-Length"
        const val CONTENT_TYPE = "Content-Type"
        const val DEVICE_ID = "X-Device-Id"
        const val ORIGIN = "Origin"
        const val PRIORITY = "Priority"
        const val REFERER = "Referer"
        const val SEC_CH_UA = "Sec-Ch-Ua"
        const val SEC_CH_UA_MOBILE = "Sec-Ch-Ua-Mobile"
        const val SEC_CH_UA_PLATFORM = "Sec-Ch-Ua-Platform"
        const val SEC_FETCH_DEST = "Sec-Fetch-Dest"
        const val SEC_FETCH_MODE = "Sec-Fetch-Mode"
        const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        const val USER_AGENT = "User-Agent"

        private const val MAX_AGE_MILLIS = 5 * 60 * 1_000L
        private const val EXPIRY_SAFETY_WINDOW_MILLIS = 5 * 1_000L

        fun capture(
            headers: Map<String, String>,
            accountId: String?,
            capturedAtMillis: Long,
        ): TwitchWebGqlHeaderState? {
            val capturedHeaders = headers.filterValues { it.isNotBlank() }
            if (capturedHeaders[CLIENT_INTEGRITY].isNullOrBlank()) return null
            return TwitchWebGqlHeaderState(
                headers = capturedHeaders,
                accountId = accountId,
                capturedAtMillis = capturedAtMillis,
                integrityExpiresAtMillis = parseExpiryMillis(capturedHeaders[CLIENT_INTEGRITY]),
            )
        }

        private fun parseExpiryMillis(value: String?): Long? {
            val payload = value?.split('.')?.getOrNull(1) ?: return null
            return runCatching {
                val decoded = Base64.getUrlDecoder().decode(payload)
                val expirySeconds = JSONObject(String(decoded, StandardCharsets.UTF_8)).optLong("exp", 0L)
                expirySeconds.takeIf { it > 0L }?.times(1_000L)
            }.getOrNull()
        }
    }
}
