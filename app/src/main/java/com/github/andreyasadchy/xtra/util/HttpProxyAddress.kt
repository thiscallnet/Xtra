package com.github.andreyasadchy.xtra.util

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale

/** A validated HTTP forward-proxy endpoint with no credentials embedded in its URL. */
class HttpProxyAddress private constructor(
    val host: String,
    val port: Int,
) {
    fun toProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))

    override fun equals(other: Any?): Boolean =
        other is HttpProxyAddress && host == other.host && port == other.port

    override fun hashCode(): Int = 31 * host.hashCode() + port

    override fun toString(): String {
        val formattedHost = if (host.contains(':')) "[$host]" else host
        return "http://$formattedHost:$port"
    }

    companion object {
        const val MAX_ENTRIES = 10
        private const val MAX_ADDRESS_LENGTH = 2048

        fun parse(value: String): HttpProxyAddress? {
            val input = value.trim()
            if (input.isEmpty() || input.length > MAX_ADDRESS_LENGTH) return null
            val uri = runCatching { URI(input) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.rawUserInfo != null) return null
            if (uri.rawPath.orEmpty() !in setOf("", "/") || uri.rawQuery != null || uri.rawFragment != null) return null

            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)
                ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            return HttpProxyAddress(host, port)
        }

        fun fromHostAndPort(host: String?, port: Int?): HttpProxyAddress? {
            val endpointHost = host?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val endpointPort = port?.takeIf { it in 1..65535 } ?: return null
            val formattedHost = if (endpointHost.contains(':') && !endpointHost.startsWith('[')) {
                "[$endpointHost]"
            } else endpointHost
            return parse("http://$formattedHost:$endpointPort")
        }

        /** Ignores invalid legacy rows while keeping the first occurrence and user-defined order. */
        fun parseList(value: String?): List<HttpProxyAddress> {
            return value.orEmpty()
                .lineSequence()
                .mapNotNull(::parse)
                .distinct()
                .take(MAX_ENTRIES)
                .toList()
        }

        /** Strict form used for user edits and imported settings. */
        fun parseListStrict(value: String): List<HttpProxyAddress> {
            val entries = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(entries.size <= MAX_ENTRIES) { "Too many HTTP proxy backups" }
            val parsed = entries.map { entry ->
                parse(entry) ?: throw IllegalArgumentException("Invalid HTTP proxy endpoint")
            }
            require(parsed.distinct().size == parsed.size) { "Duplicate HTTP proxy endpoint" }
            return parsed
        }
    }
}
