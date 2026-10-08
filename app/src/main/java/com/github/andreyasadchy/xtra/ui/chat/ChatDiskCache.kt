package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.InflaterOutputStream

/** Disk cache for emote responses and badges, used to avoid refetching on metered networks. */
internal class ChatDiskCache(private val applicationContext: Context) {

    internal fun isActiveNetworkMetered(): Boolean {
        return (applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.isActiveNetworkMetered == true
    }

    internal suspend fun readCachedEmoteResponse(fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = emoteResponseFile(fileName)
            val compressedBytes = FileInputStream(file).use { it.readBytes() }
            val decompressedStream = ByteArrayOutputStream()
            InflaterOutputStream(decompressedStream).use {
                it.write(compressedBytes)
            }
            decompressedStream.toByteArray().decodeToString()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    internal suspend fun invalidateEmoteResponseCache(fileName: String) = withContext(Dispatchers.IO) {
        try {
            emoteResponseFile(fileName).delete()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    internal fun emoteResponseFile(fileName: String): File {
        return File(
            File(applicationContext.cacheDir, "emote_responses"),
            File(fileName).name,
        )
    }

    internal fun isFreshCache(file: File): Boolean {
        val lastModified = file.lastModified()
        return lastModified > 0L &&
            (System.currentTimeMillis() - lastModified).coerceAtLeast(0L) <= METERED_CACHE_MAX_AGE_MS
    }

    internal suspend fun loadCachedOrFetchEmoteResponse(
        fileName: String,
        request: suspend () -> String,
        validate: suspend (String) -> Unit,
    ): Pair<String?, Boolean> {
        var cachedResponse = readCachedEmoteResponse(fileName)
        if (cachedResponse != null && isActiveNetworkMetered() && isFreshCache(emoteResponseFile(fileName))) {
            if (isValidEmoteResponse(cachedResponse, validate)) {
                return cachedResponse to false
            }
            invalidateEmoteResponseCache(fileName)
            cachedResponse = null
        }
        return try {
            request().also { response ->
                if (!isValidEmoteResponse(response, validate)) {
                    throw IllegalStateException("Invalid emote response")
                }
            } to true
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            if (cachedResponse != null && isValidEmoteResponse(cachedResponse, validate)) {
                cachedResponse to false
            } else {
                cachedResponse?.let { invalidateEmoteResponseCache(fileName) }
                null to false
            }
        }
    }

    internal suspend fun isValidEmoteResponse(
        response: String,
        validate: suspend (String) -> Unit,
    ): Boolean {
        return try {
            validate(response)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    internal fun badgeCacheFile(scope: String, quality: String): File {
        val safeScope = scope.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeQuality = quality.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(
            File(applicationContext.cacheDir, "chat_badges"),
            "$safeScope-$safeQuality.json",
        )
    }

    internal suspend fun readBadgeCache(scope: String, quality: String): List<TwitchBadge>? = withContext(Dispatchers.IO) {
        try {
            val file = badgeCacheFile(scope, quality)
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val setId = item.optString("setId").takeIf { it.isNotBlank() } ?: continue
                    val version = item.optString("version").takeIf { it.isNotBlank() } ?: continue
                    add(
                        TwitchBadge(
                            setId = setId,
                            version = version,
                            url1x = item.optString("url1x").takeIf { it.isNotBlank() },
                            url2x = item.optString("url2x").takeIf { it.isNotBlank() },
                            url3x = item.optString("url3x").takeIf { it.isNotBlank() },
                            url4x = item.optString("url4x").takeIf { it.isNotBlank() },
                            title = item.optString("title").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    internal suspend fun writeBadgeCache(scope: String, quality: String, badges: List<TwitchBadge>) = withContext(Dispatchers.IO) {
        try {
            val file = badgeCacheFile(scope, quality)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            badges.forEach { badge ->
                array.put(JSONObject().apply {
                    put("setId", badge.setId)
                    put("version", badge.version)
                    badge.url1x?.let { put("url1x", it) }
                    badge.url2x?.let { put("url2x", it) }
                    badge.url3x?.let { put("url3x", it) }
                    badge.url4x?.let { put("url4x", it) }
                    badge.title?.let { put("title", it) }
                })
            }
            file.writeText(array.toString())
            val files = file.parentFile?.listFiles().orEmpty()
            val excess = (files.size - MAX_BADGE_CACHE_FILES).coerceAtLeast(0)
            if (excess > 0) {
                files.filter { it != file }
                    .sortedBy { it.lastModified() }
                    .take(excess)
                    .forEach(File::delete)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    internal suspend fun loadCachedOrFetchBadges(
        scope: String,
        quality: String,
        request: suspend () -> List<TwitchBadge>,
    ): Pair<List<TwitchBadge>, Boolean> {
        val cachedBadges = readBadgeCache(scope, quality)
        if (cachedBadges != null && isActiveNetworkMetered() && isFreshCache(badgeCacheFile(scope, quality))) {
            return cachedBadges to false
        }
        return try {
            request() to true
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            if (cachedBadges != null) {
                cachedBadges to false
            } else {
                throw e
            }
        }
    }

    private companion object {
        const val METERED_CACHE_MAX_AGE_MS = 604_800_000L
        const val MAX_BADGE_CACHE_FILES = 100
    }
}
