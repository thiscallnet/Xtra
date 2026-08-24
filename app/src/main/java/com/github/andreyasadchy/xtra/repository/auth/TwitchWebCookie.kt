package com.github.andreyasadchy.xtra.repository.auth

import java.net.URI

/** A cookie record copied from the Xtra-owned Gecko profile. */
data class TwitchWebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val hostOnly: Boolean,
    val expirationDateMillis: Long?,
)

/** Applies the browser's host, path, secure, and expiry rules to a URL. */
object TwitchWebCookiePolicy {
    fun headerFor(
        url: String,
        cookies: Collection<TwitchWebCookie>,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trim('.') ?: return null
        val requestPath = uri.path?.takeIf { it.isNotEmpty() } ?: "/"
        val isHttps = uri.scheme.equals("https", ignoreCase = true)

        val selected = cookies
            .asSequence()
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .filter { isHttps || !it.secure }
            .filter { it.expirationDateMillis == null || it.expirationDateMillis > nowMillis }
            .filter { domainMatches(host, it) }
            .filter { pathMatches(requestPath, it.path) }
            .sortedWith(
                compareByDescending<TwitchWebCookie> { normalizedPath(it.path).length }
                    .thenByDescending { it.hostOnly },
            )
            .toList()

        val header = selected
            .joinToString("; ") { "${it.name}=${it.value}" }
        return header.takeIf { it.isNotBlank() }
    }

    private fun domainMatches(host: String, cookie: TwitchWebCookie): Boolean {
        val domain = cookie.domain.lowercase().trimStart('.')
        return if (cookie.hostOnly) host == domain else host == domain || host.endsWith(".$domain")
    }

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        val path = normalizedPath(cookiePath)
        if (requestPath == path) return true
        if (!requestPath.startsWith(path)) return false
        return path.endsWith('/') || requestPath.getOrNull(path.length) == '/'
    }

    private fun normalizedPath(path: String): String = path.takeIf { it.startsWith('/') } ?: "/"
}
