package com.github.andreyasadchy.xtra.repository.datasource

import android.os.SystemClock
import android.util.LruCache
import com.github.andreyasadchy.xtra.model.helix.user.User
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.TwitchApiException
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.TimeoutException

/**
 * Fills broadcaster type from Twitch's documented Helix Get Users endpoint.
 * Cached status is only displayed while fresh. Lookups are batched, time-bounded,
 * and failure-backed-off so optional metadata cannot hold up a feed indefinitely.
 */
internal suspend fun List<Stream>.withHelixBroadcasterTypes(
    networkLibrary: String?,
    headers: Map<String, String>,
    helixRepository: HelixRepository,
): List<Stream> {
    if (isEmpty()) return this

    val nowElapsedMs = SystemClock.elapsedRealtime()
    val nowEpochMs = System.currentTimeMillis()

    // Values restored from the feed DB keep their original Helix timestamp.
    // Never renew their lifetime simply because a cached page was read again.
    forEach { stream ->
        val channelId = stream.channelId ?: return@forEach
        val type = stream.broadcasterType ?: return@forEach
        val fetchedAtEpochMs = stream.broadcasterTypeFetchedAtEpochMs ?: return@forEach
        BroadcasterTypeCache.rememberIfFresh(
            channelId,
            type,
            fetchedAtEpochMs,
            nowElapsedMs,
            nowEpochMs,
        )
    }

    val channelIds = mapNotNull(Stream::channelId).distinct()
    if (channelIds.isEmpty()) return map { it.withBroadcasterType(null) }

    val requestIdentity = BroadcasterTypeCache.requestIdentity(headers)
    val idsNeedingRefresh = channelIds.filter { id ->
        BroadcasterTypeCache.needsRefresh(id, requestIdentity, nowElapsedMs, nowEpochMs)
    }
    if (!headers[C.HEADER_TOKEN].isNullOrBlank() && idsNeedingRefresh.isNotEmpty()) {
        var failure: Exception? = null
        try {
            val completed = withTimeoutOrNull(HELIX_LOOKUP_TIMEOUT_MS) {
                // Twitch permits at most 100 IDs in one Get Users request.
                for (ids in idsNeedingRefresh.chunked(MAX_USER_IDS_PER_REQUEST)) {
                    val users = try {
                        helixRepository.getUsers(networkLibrary, headers, ids = ids).data
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failure = error
                        break
                    }

                    val returnedIds = users.mapNotNull(User::id).toSet()
                    rememberHelixBroadcasterTypes(users)
                    // A successful response that omits a requested ID is a
                    // successful negative lookup; don't repeat it on every feed load.
                    ids.asSequence()
                        .filterNot(returnedIds::contains)
                        .forEach { BroadcasterTypeCache.put(it, "", nowElapsedMs, nowEpochMs) }
                }
                true
            }
            if (completed == null) failure = TimeoutException("Helix Partner lookup timed out")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error
        }

        failure?.let { BroadcasterTypeCache.recordFailure(requestIdentity, it, nowElapsedMs, nowEpochMs) }
    }

    val resultElapsedMs = SystemClock.elapsedRealtime()
    val resultEpochMs = System.currentTimeMillis()
    return map { stream ->
        val channelId = stream.channelId
        val cached = channelId?.let {
            BroadcasterTypeCache.getFresh(it, resultElapsedMs, resultEpochMs)
        }
        stream.withBroadcasterType(cached?.broadcasterType, cached?.fetchedAtEpochMs)
    }
}

/** Reuses user records when a caller already fetched them for profile images. */
internal fun rememberHelixBroadcasterTypes(users: List<User>) {
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val nowEpochMs = System.currentTimeMillis()
    users.forEach { user ->
        val id = user.id ?: return@forEach
        BroadcasterTypeCache.put(id, user.broadcasterType.orEmpty(), nowElapsedMs, nowEpochMs)
    }
}

internal fun isBroadcasterTypeFresh(
    fetchedAtEpochMs: Long?,
    nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
    val fetchedAt = fetchedAtEpochMs ?: return false
    val ageMs = nowEpochMs - fetchedAt
    return ageMs in 0 until CACHE_TTL_MS
}

private data class CachedBroadcasterType(
    val broadcasterType: String,
    val fetchedAtElapsedMs: Long,
    val fetchedAtEpochMs: Long,
)

private data class HelixRequestIdentity(
    val clientId: String?,
    val authorizationFingerprint: String,
)

private object BroadcasterTypeCache {
    private val entries = LruCache<String, CachedBroadcasterType>(MAX_CACHED_USERS)
    private val retryAfterElapsedMs = LruCache<HelixRequestIdentity, Long>(MAX_CACHED_IDENTITIES)
    private val retryLock = Any()

    fun requestIdentity(headers: Map<String, String>): HelixRequestIdentity? {
        val authorization = headers.header(C.HEADER_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val clientId = headers.header(C.HEADER_CLIENT_ID)?.trim()?.takeIf { it.isNotEmpty() }
        return HelixRequestIdentity(clientId, authorization.fingerprint())
    }

    fun getFresh(id: String, nowElapsedMs: Long, nowEpochMs: Long): CachedBroadcasterType? {
        val entry = entries.get(id) ?: return null
        val elapsedAge = nowElapsedMs - entry.fetchedAtElapsedMs
        val epochAge = nowEpochMs - entry.fetchedAtEpochMs
        return entry.takeIf {
            elapsedAge in 0 until CACHE_TTL_MS && epochAge in 0 until CACHE_TTL_MS
        }
    }

    fun needsRefresh(
        id: String,
        requestIdentity: HelixRequestIdentity?,
        nowElapsedMs: Long,
        nowEpochMs: Long,
    ): Boolean {
        if (getFresh(id, nowElapsedMs, nowEpochMs) != null) return false
        val retryAfter = requestIdentity?.let {
            synchronized(retryLock) { retryAfterElapsedMs.get(it) ?: 0L }
        } ?: 0L
        return retryAfter <= nowElapsedMs
    }

    fun rememberIfFresh(
        id: String,
        type: String,
        fetchedAtEpochMs: Long,
        nowElapsedMs: Long,
        nowEpochMs: Long,
    ) {
        val ageMs = nowEpochMs - fetchedAtEpochMs
        if (ageMs !in 0 until CACHE_TTL_MS) return
        put(id, type, nowElapsedMs - ageMs, fetchedAtEpochMs)
    }

    fun put(id: String, type: String, nowElapsedMs: Long, nowEpochMs: Long) {
        entries.put(id, CachedBroadcasterType(type, nowElapsedMs, nowEpochMs))
    }

    fun recordFailure(
        requestIdentity: HelixRequestIdentity?,
        error: Exception,
        nowElapsedMs: Long,
        nowEpochMs: Long,
    ) {
        if (requestIdentity == null) return
        val retryAfter = nowElapsedMs + failureBackoffMs(error, nowEpochMs)
        synchronized(retryLock) {
            val existingRetryAfter = retryAfterElapsedMs.get(requestIdentity) ?: 0L
            retryAfterElapsedMs.put(requestIdentity, maxOf(existingRetryAfter, retryAfter))
        }
    }

    private fun failureBackoffMs(error: Exception, nowEpochMs: Long): Long = when (error) {
        is TwitchApiException -> when (error.statusCode) {
            401, 403 -> AUTH_FAILURE_BACKOFF_MS
            429 -> error.rateLimitResetEpochSeconds
                ?.let { it * 1_000L - nowEpochMs }
                ?.takeIf { it > 0L }
                ?: RATE_LIMIT_BACKOFF_MS
            else -> NETWORK_FAILURE_BACKOFF_MS
        }
        else -> NETWORK_FAILURE_BACKOFF_MS
    }
}

private fun Map<String, String>.header(name: String): String? = entries
    .firstOrNull { it.key.equals(name, ignoreCase = true) }
    ?.value

private fun String.fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private const val MAX_CACHED_USERS = 2048
private const val MAX_CACHED_IDENTITIES = 32
private const val MAX_USER_IDS_PER_REQUEST = 100
private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
private const val HELIX_LOOKUP_TIMEOUT_MS = 800L
private const val NETWORK_FAILURE_BACKOFF_MS = 30 * 1_000L
private const val AUTH_FAILURE_BACKOFF_MS = 15 * 60 * 1_000L
private const val RATE_LIMIT_BACKOFF_MS = 60 * 1_000L
