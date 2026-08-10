package com.github.andreyasadchy.xtra.util

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.BuildConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
data class UpdateInfo(
    val version: String,
    val title: String,
    val body: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val size: Long?,
)

/** Persists the latest release so update UI remains useful between app launches. */
object UpdateState {

    const val DEFAULT_FREQUENCY_DAYS = 1
    private const val DAY_MILLIS = 86_400_000L
    private val versionRegex = Regex("\\d+(?:\\.\\d+)+")

    fun fromResponse(response: JsonObject, fallbackUrl: String): UpdateInfo? {
        val asset = response["assets"]?.jsonArray?.firstOrNull { asset ->
            asset.jsonObject["content_type"]?.jsonPrimitive?.contentOrNull ==
                    "application/vnd.android.package-archive"
        }?.jsonObject ?: return null
        val version = response["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: response["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val releaseUrl = response["html_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: fallbackUrl
        val title = response["name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: version
        val body = response["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        return UpdateInfo(
            version = version,
            title = title,
            body = body,
            releaseUrl = releaseUrl,
            downloadUrl = downloadUrl,
            size = asset["size"]?.jsonPrimitive?.longOrNull,
        )
    }

    fun read(context: Context): UpdateInfo? {
        val preferences = context.tokenPrefs()
        val version = preferences.getString(C.UPDATE_AVAILABLE_VERSION, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val releaseUrl = preferences.getString(C.UPDATE_AVAILABLE_URL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadUrl = preferences.getString(C.UPDATE_AVAILABLE_DOWNLOAD_URL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return UpdateInfo(
            version = version,
            title = preferences.getString(C.UPDATE_AVAILABLE_TITLE, null).orEmpty().ifBlank { version },
            body = preferences.getString(C.UPDATE_AVAILABLE_BODY, null).orEmpty(),
            releaseUrl = releaseUrl,
            downloadUrl = downloadUrl,
            size = preferences.getLong(C.UPDATE_AVAILABLE_SIZE, -1L).takeIf { it >= 0L },
        )
    }

    fun save(context: Context, info: UpdateInfo) {
        context.tokenPrefs().edit {
            putString(C.UPDATE_AVAILABLE_VERSION, info.version)
            putString(C.UPDATE_AVAILABLE_TITLE, info.title)
            putString(C.UPDATE_AVAILABLE_BODY, info.body)
            putString(C.UPDATE_AVAILABLE_URL, info.releaseUrl)
            putString(C.UPDATE_AVAILABLE_DOWNLOAD_URL, info.downloadUrl)
            if (info.size != null) {
                putLong(C.UPDATE_AVAILABLE_SIZE, info.size)
            } else {
                remove(C.UPDATE_AVAILABLE_SIZE)
            }
            remove(C.UPDATE_DOWNLOADED_VERSION)
        }
    }

    fun clear(context: Context) {
        context.tokenPrefs().edit {
            remove(C.UPDATE_AVAILABLE_VERSION)
            remove(C.UPDATE_AVAILABLE_TITLE)
            remove(C.UPDATE_AVAILABLE_BODY)
            remove(C.UPDATE_AVAILABLE_URL)
            remove(C.UPDATE_AVAILABLE_DOWNLOAD_URL)
            remove(C.UPDATE_AVAILABLE_SIZE)
            remove(C.UPDATE_DOWNLOADED_VERSION)
        }
    }

    fun ignore(context: Context) {
        read(context)?.let { info ->
            context.tokenPrefs().edit {
                putString(C.UPDATE_IGNORED_VERSION, info.version)
            }
        }
    }

    fun markDownloaded(context: Context, version: String) {
        context.tokenPrefs().edit {
            putString(C.UPDATE_DOWNLOADED_VERSION, version)
        }
    }

    fun isPending(context: Context): Boolean {
        val info = read(context) ?: return false
        val preferences = context.tokenPrefs()
        return isNewerThanInstalled(info.version) &&
                preferences.getString(C.UPDATE_IGNORED_VERSION, null) != info.version &&
                preferences.getString(C.UPDATE_DOWNLOADED_VERSION, null) != info.version
    }

    fun isIgnored(context: Context): Boolean {
        val info = read(context) ?: return false
        return context.tokenPrefs().getString(C.UPDATE_IGNORED_VERSION, null) == info.version
    }

    fun isDownloaded(context: Context): Boolean {
        val info = read(context) ?: return false
        return context.tokenPrefs().getString(C.UPDATE_DOWNLOADED_VERSION, null) == info.version
    }

    fun isNewerThanInstalled(version: String): Boolean {
        val remote = versionRegex.find(version)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
        val installed = versionRegex.find(BuildConfig.VERSION_NAME)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }
        if (remote != null && installed != null) {
            val length = maxOf(remote.size, installed.size)
            for (index in 0 until length) {
                val remotePart = remote.getOrElse(index) { 0 }
                val installedPart = installed.getOrElse(index) { 0 }
                if (remotePart != installedPart) {
                    return remotePart > installedPart
                }
            }
            return false
        }
        return version != BuildConfig.VERSION_NAME
    }

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val frequencyDays = context.prefs()
            .getString(C.UPDATE_CHECK_FREQUENCY, DEFAULT_FREQUENCY_DAYS.toString())
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?: DEFAULT_FREQUENCY_DAYS.toLong()
        val lastChecked = context.tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0L)
        return lastChecked <= 0L || now - lastChecked >= frequencyDays * DAY_MILLIS
    }

    fun markChecked(context: Context, now: Long = System.currentTimeMillis()) {
        context.tokenPrefs().edit {
            putLong(C.UPDATE_LAST_CHECKED, now)
        }
    }
}
