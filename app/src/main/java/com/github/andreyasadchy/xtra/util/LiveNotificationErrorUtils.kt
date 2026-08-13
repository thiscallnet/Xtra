package com.github.andreyasadchy.xtra.util

private val headersDumpPattern = Regex("(?i)(?:request\\s+)?headers?\\s*[=:]\\s*\\{[^}]*\\}")
private val sensitiveHeaderPattern = Regex(
    "(?i)((?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|client-id|client_id|client-secret|client_secret|access_token|oauth_token|refresh_token|token)\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^}\\r\\n]*)"
)
private val jsonCredentialPattern = Regex(
    "(?i)((?:\"(?:access_token|refresh_token|oauth_token|client_secret|authorization|cookie|set-cookie)\"|'(?:access_token|refresh_token|oauth_token|client_secret|authorization|cookie|set-cookie)')\\s*:\\s*)(\"[^\"]*\"|'[^']*'|[^,}\\s]+)"
)
private val bearerPattern = Regex("(?i)\\b(?:Bearer|OAuth)\\s+[^\\s,;}]+")
private val tokenQueryPattern = Regex(
    "(?i)([?&](?:access_token|oauth_token|refresh_token|token|client_secret)=)[^&\\s]+"
)

/**
 * Removes credentials and request-header values before an error is shown or copied.
 * Keep this deliberately conservative: diagnostics should remain useful without
 * becoming a second place where secrets can escape.
 */
fun sanitizeLiveNotificationTechnicalMessage(message: String?): String? {
    if (message.isNullOrBlank()) {
        return null
    }
    val sanitized = message
        .replace(headersDumpPattern, "headers=<redacted>")
        .replace(sensitiveHeaderPattern) {
            "${it.groupValues[1]}<redacted>"
        }
        .replace(jsonCredentialPattern) {
            "${it.groupValues[1]}<redacted>"
        }
        .replace(bearerPattern, "<redacted>")
        .replace(tokenQueryPattern, "$1<redacted>")
        .trim()
        .take(500)
    return sanitized.ifBlank { null }
}
