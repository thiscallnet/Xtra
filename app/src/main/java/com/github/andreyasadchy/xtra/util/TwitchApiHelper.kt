package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.icu.number.Notation
import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.text.CompactDecimalFormat
import android.os.Build
import android.text.format.DateUtils
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

/** Header set returned while the authenticated GeckoView integrity context is refreshing. */
internal class ProtectedGqlHeadersUnavailable(
    private val delegate: Map<String, String>,
) : Map<String, String> by delegate

internal class TwitchIntegrityUnavailableException : IllegalStateException(
    "Twitch protected request is waiting for the authenticated GeckoView integrity context",
)

object TwitchApiHelper {

    private val imageSizeRegex = Regex("-\\d+x\\d+.")
    private val webGqlIdentityLock = Any()
    var checkedValidation = false
    var checkedUpdates = false
    val defaultQualityList = listOf("chunked", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "audio_only")
    val vodDomains = listOf(
        "https://vod-secure.twitch.tv",
        "https://vod-metro.twitch.tv",
        "https://vod-pop-secure.twitch.tv",
        "https://d2e2de1etea730.cloudfront.net",
        "https://dqrpb9wgowsf5.cloudfront.net",
        "https://ds0h3roq6wcgc.cloudfront.net",
        "https://d2nvs31859zcd8.cloudfront.net",
        "https://d2aba1wr3818hz.cloudfront.net",
        "https://d3c27h4odz752x.cloudfront.net",
        "https://dgeft87wbj63p.cloudfront.net",
        "https://d1m7jfoe9zdc1j.cloudfront.net",
        "https://d3vd9lfkzbru3h.cloudfront.net",
        "https://d2vjef5jvl6bfs.cloudfront.net",
        "https://d1ymi26ma8va5x.cloudfront.net",
        "https://d1mhjrowxxagfy.cloudfront.net",
        "https://ddacn6pr5v0tl.cloudfront.net",
        "https://d3aqoihi2n8ty8.cloudfront.net",
        "https://d3fi1amfgojobc.cloudfront.net",
        "https://d3stzm2eumvgb4.cloudfront.net",
        "https://d2vi6trrdongqn.cloudfront.net",
        "https://d1ndex63qxojbr.cloudfront.net",
    )

    fun getStreamThumbnail(url: String?): String? {
        return when {
            url.isNullOrBlank() -> "https://static-cdn.jtvnw.net/ttv-static/404_preview-440x248.jpg"
            url.contains("{width}x{height}") -> url.replace("{width}", "1280").replace("{height}", "720")
            else -> url.replace(imageSizeRegex, "-1280x720.")
        }
    }

    fun getVideoThumbnail(url: String?): String? {
        return when {
            url.isNullOrBlank() || url.startsWith("https://vod-secure.twitch.tv/_404/404_processing") -> {
                "https://vod-secure.twitch.tv/_404/404_processing_320x180.png"
            }
            url.contains("{width}x{height}") -> url.replace("{width}", "1280").replace("{height}", "720")
            url.contains("%{width}x%{height}") -> url.replace("%{width}", "1280").replace("%{height}", "720")
            else -> url.replace(imageSizeRegex, "-1280x720.")
        }
    }

    fun getClipThumbnail(url: String?): String? {
        return url?.replace(imageSizeRegex, "-1280x720.")
    }

    fun getGameBoxArt(url: String?): String? {
        return when {
            url.isNullOrBlank() -> "https://static-cdn.jtvnw.net/ttv-static/404_boxart.jpg"
            url.contains("{width}x{height}") -> url.replace("{width}", "285").replace("{height}", "380")
            else -> url.replace(imageSizeRegex, "-285x380.")
        }
    }

    fun getProfileImage(url: String?): String? {
        return url?.replace(imageSizeRegex, "-300x300.")
    }

    fun getType(context: Context, type: String?): String? {
        return when (type?.lowercase()) {
            "archive" -> context.getString(R.string.video_type_archive)
            "highlight" -> context.getString(R.string.video_type_highlight)
            "upload" -> context.getString(R.string.video_type_upload)
            else -> null
        }
    }

    fun getDuration(duration: String): Int {
        val h = duration.substringBefore("h", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        val m = duration.substringBefore("m", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        val s = duration.substringBefore("s", "0").takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
        return (h * 3600) + (m * 60) + s
    }

    fun getDurationFromSeconds(context: Context, input: String?): String? {
        return input?.toIntOrNull()?.let { duration ->
            val days = (duration / 86400)
            val hours = ((duration % 86400) / 3600)
            val minutes = (((duration % 86400) % 3600) / 60)
            val seconds = (duration % 60)
            buildString {
                if (days > 0) {
                    append("$days${context.getString(R.string.days)}")
                }
                if (hours > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$hours${context.getString(R.string.hours)}")
                }
                if (minutes > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$minutes${context.getString(R.string.minutes)}")
                }
                if (seconds > 0) {
                    if (isNotBlank()) {
                        append(" ")
                    }
                    append("$seconds${context.getString(R.string.seconds)}")
                }
            }
        }
    }

    fun getMinutesLeft(hour: Int, minute: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentDate = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.systemDefault())
            val date = currentDate.withHour(hour).withMinute(minute).let {
                if (it < currentDate) it.plusDays(1) else it
            }
            return ChronoUnit.MINUTES.between(currentDate, date).toInt()
        } else {
            val currentDate = Calendar.getInstance()
            val date = Calendar.getInstance()
            date.set(Calendar.HOUR_OF_DAY, hour)
            date.set(Calendar.MINUTE, minute)
            if (date < currentDate) {
                date.add(Calendar.DAY_OF_YEAR, 1)
            }
            return ((date.timeInMillis - currentDate.timeInMillis) / 60000).toInt()
        }
    }

    fun getTimestamp(input: Long, timestampFormat: String?): String? {
        val pattern = when (timestampFormat) {
            "0" -> "H:mm"
            "1" -> "H:mm:ss"
            "2" -> "h:mm a"
            "3" -> "h:mm:ss a"
            else -> "H:mm"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(input), ZoneOffset.systemDefault())
                DateTimeFormatter.ofPattern(pattern).format(date)
            } else {
                val format = SimpleDateFormat(pattern, Locale.getDefault())
                format.format(Date(input))
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatDate(context: Context, time: Long): String {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentYear = Year.now().value
            val year = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.UTC).year
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val year = Calendar.getInstance().let {
                it.timeInMillis = time
                it.get(Calendar.YEAR)
            }
            if (year == currentYear) {
                DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE
            }
        }
        return DateUtils.formatDateTime(context, time, format)
    }

    fun formatCount(count: Int, compact: Boolean): String {
        return if (compact) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    NumberFormatter.withLocale(Locale.getDefault())
                        .notation(Notation.compactShort())
                        .precision(Precision.maxFraction(1))
                        .roundingMode(RoundingMode.DOWN)
                        .format(count)
                        .toString()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val format = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                    format.maximumFractionDigits = 1
                    format.roundingMode = RoundingMode.DOWN.ordinal
                    format.format(count)
                }
                else -> {
                    if (count > 1000) {
                        val divider: Int
                        val suffix = if (count.toString().length < 7) {
                            divider = 1000
                            "K"
                        } else {
                            divider = 1_000_000
                            "M"
                        }
                        val truncated = count / (divider / 10)
                        val hasDecimal = truncated / 10.0 != (truncated / 10).toDouble()
                        if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
                    } else {
                        count.toString()
                    }
                }
            }
        } else {
            NumberFormat.getInstance().format(count)
        }
    }

    fun addTokenPrefixGQL(token: String) = "OAuth $token"
    fun addTokenPrefixHelix(token: String) = "Bearer $token"

    fun getGQLHeaders(context: Context, includeToken: Boolean = false): Map<String, String> {
        val webToken = context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
            ?.takeIf { it.isNotBlank() }
        return buildGQLHeaders(
            clientId = context.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
                ?: C.DEFAULT_GQL_CLIENT_ID_WEB,
            accessToken = webToken?.takeIf { includeToken },
            cookieHeader = if (includeToken && webToken != null) {
                liveWebCookieHeader(context, "https://gql.twitch.tv/gql")
            } else {
                null
            },
        )
    }

    internal fun buildGQLHeaders(
        clientId: String,
        accessToken: String?,
        cookieHeader: String? = null,
    ): Map<String, String> = buildMap {
        put(C.HEADER_CLIENT_ID, clientId)
        accessToken?.takeIf { it.isNotBlank() }?.let { put(C.HEADER_TOKEN, addTokenPrefixGQL(it)) }
        cookieHeader?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    /** Headers for private operations used by the Twitch web channel page. */
    fun getWebGQLHeaders(context: Context, includeToken: Boolean = true): Map<String, String> {
        val clientId = context.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
            ?: C.DEFAULT_GQL_CLIENT_ID_WEB
        val accessToken = if (includeToken) context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null) else null
        val cookieHeader = if (includeToken) liveWebCookieHeader(context, "https://gql.twitch.tv/gql") else null
        val capturedHeaders = if (includeToken) liveCapturedWebGqlHeaders(context) else null
        if (!capturedHeaders.isNullOrEmpty()) {
            return buildCapturedWebGQLHeaders(
                fallbackClientId = clientId,
                fallbackAccessToken = accessToken,
                cookieHeader = cookieHeader,
                browserHeaders = capturedHeaders,
            )
        }

        val identity = getStableWebGqlIdentity(context)
        return buildWebGQLHeaders(
            clientId = clientId,
            accessToken = accessToken,
            deviceId = identity.deviceId,
            clientSessionId = identity.clientSessionId,
            cookieHeader = cookieHeader,
        )
    }

    fun getProtectedGQLHeaders(context: Context): Map<String, String> =
        markUnavailableProtectedGQLHeaders(getWebGQLHeaders(context, includeToken = true))

    internal fun markUnavailableProtectedGQLHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers[C.HEADER_TOKEN].isNullOrBlank() || !headers["Client-Integrity"].isNullOrBlank()) {
            return headers
        }
        return ProtectedGqlHeadersUnavailable(headers)
    }

    internal data class WebGqlIdentity(
        val deviceId: String,
        val clientSessionId: String,
    )

    internal fun buildWebGQLHeaders(
        clientId: String,
        accessToken: String?,
        deviceId: String,
        clientSessionId: String,
        cookieHeader: String? = null,
    ): Map<String, String> = buildMap {
        put(C.HEADER_CLIENT_ID, clientId)
        accessToken?.takeIf { it.isNotBlank() }?.let { put(C.HEADER_TOKEN, addTokenPrefixGQL(it)) }
        cookieHeader?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        put("Client-Session-Id", clientSessionId)
        put("X-Device-Id", deviceId)
        put("Origin", "https://www.twitch.tv")
        put("Referer", "https://www.twitch.tv/")
        put("User-Agent", WEB_GQL_USER_AGENT)
    }

    /** Reuses the exact browser metadata when GeckoView has observed a live integrity request. */
    internal fun buildCapturedWebGQLHeaders(
        fallbackClientId: String,
        fallbackAccessToken: String?,
        cookieHeader: String?,
        browserHeaders: Map<String, String>,
    ): Map<String, String> = buildMap {
        val transportManagedHeaders = setOf(
            "Accept-Encoding",
            "Content-Length",
            "Content-Type",
        )
        put(C.HEADER_CLIENT_ID, browserHeaders[C.HEADER_CLIENT_ID] ?: fallbackClientId)
        val browserAuthorization = browserHeaders[C.HEADER_TOKEN]?.takeIf { it.isNotBlank() }
        if (browserAuthorization != null) {
            put(C.HEADER_TOKEN, browserAuthorization)
        } else {
            fallbackAccessToken?.takeIf { it.isNotBlank() }?.let { put(C.HEADER_TOKEN, addTokenPrefixGQL(it)) }
        }
        cookieHeader?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        browserHeaders.forEach { (name, value) ->
            if (name != C.HEADER_CLIENT_ID &&
                name != C.HEADER_TOKEN &&
                name !in transportManagedHeaders &&
                !name.startsWith(":")) {
                put(name, value)
            }
        }
    }

    private fun getStableWebGqlIdentity(context: Context): WebGqlIdentity = synchronized(webGqlIdentityLock) {
        val accountId = context.tokenPrefs().getString(C.GQL_TOKEN_WEB_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }
            ?: ANONYMOUS_ACCOUNT_ID
        val preferences = context.prefs()
        val storedAccountId = preferences.getString(C.TWITCH_GQL_CONTEXT_ACCOUNT_ID, null)
        val storedDeviceId = preferences.getString(C.TWITCH_GQL_DEVICE_ID, null)
        val storedClientSessionId = preferences.getString(C.TWITCH_GQL_CLIENT_SESSION_ID, null)
        if (storedAccountId == accountId && !storedDeviceId.isNullOrBlank() && !storedClientSessionId.isNullOrBlank()) {
            return WebGqlIdentity(storedDeviceId, storedClientSessionId)
        }
        val identity = WebGqlIdentity(
            deviceId = Uuid.random().toHexString(),
            clientSessionId = Uuid.random().toString(),
        )
        preferences.edit()
            .putString(C.TWITCH_GQL_CONTEXT_ACCOUNT_ID, accountId)
            .putString(C.TWITCH_GQL_DEVICE_ID, identity.deviceId)
            .putString(C.TWITCH_GQL_CLIENT_SESSION_ID, identity.clientSessionId)
            .apply()
        return identity
    }

    /** Headers for the authenticated PersonalSections request. */
    fun getPersonalizedRecommendationGQLHeaders(
        context: Context,
        clientId: String?,
        accessToken: String?,
        clientSessionId: String,
    ): Map<String, String> = getRecommendationGQLHeaders(
        context = context,
        clientId = clientId,
        accessToken = accessToken,
        clientSessionId = clientSessionId,
    )

    /** Headers for the public recommendation fallback; never includes Authorization. */
    fun getPublicRecommendationGQLHeaders(
        context: Context,
        clientSessionId: String,
    ): Map<String, String> = getRecommendationGQLHeaders(
        context = context,
        clientId = null,
        accessToken = null,
        clientSessionId = clientSessionId,
    )

    /** Common recommendation headers with an optional private-GQL identity. */
    fun getRecommendationGQLHeaders(
        context: Context,
        clientId: String?,
        accessToken: String?,
        clientSessionId: String,
    ): Map<String, String> {
        val deviceId = context.prefs().getString(C.RECOMMENDATIONS_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: Uuid.random().toHexString().also {
                context.prefs().edit().putString(C.RECOMMENDATIONS_DEVICE_ID, it).apply()
            }
        return buildRecommendationGQLHeaders(
            clientId = clientId
                ?.takeIf { it.isNotBlank() }
                ?: context.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB),
            accessToken = accessToken,
            deviceId = deviceId,
            clientSessionId = clientSessionId,
        )
    }

    internal fun buildRecommendationGQLHeaders(
        clientId: String?,
        accessToken: String?,
        deviceId: String,
        clientSessionId: String,
    ): Map<String, String> = buildMap {
        clientId?.takeIf { it.isNotBlank() }?.let { put(C.HEADER_CLIENT_ID, it) }
        accessToken?.takeIf { it.isNotBlank() }?.let { put(C.HEADER_TOKEN, addTokenPrefixGQL(it)) }
        put("Client-Session-Id", clientSessionId)
        put("X-Device-Id", deviceId)
        put("Origin", "https://www.twitch.tv")
        put("Referer", "https://www.twitch.tv/")
        put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
    }

    /**
     * Returns Helix headers for the web session. Mutating/account-specific callers can pass the
     * scopes they require; the web token is withheld when validation did not grant them.
     */
    fun getHelixHeaders(
        context: Context,
        requiredScopes: Set<String> = emptySet(),
    ): Map<String, String> {
        return mutableMapOf<String, String>().apply {
            val webToken = context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
                ?.takeIf { it.isNotBlank() }
            val grantedScopes = webSessionScopes(context)
            if (webToken != null && requiredScopes.all(grantedScopes::contains)) {
                put(C.HEADER_CLIENT_ID, context.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
                    ?: C.DEFAULT_GQL_CLIENT_ID_WEB)
                put(C.HEADER_TOKEN, addTokenPrefixHelix(webToken))
                liveWebCookieHeader(context, "https://api.twitch.tv/helix/")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("Cookie", it) }
                return@apply
            }
            put(
                C.HEADER_CLIENT_ID,
                context.prefs().getString(C.GQL_CLIENT_ID_WEB, C.DEFAULT_GQL_CLIENT_ID_WEB)
                    ?: C.DEFAULT_GQL_CLIENT_ID_WEB,
            )
        }
    }

    fun webSessionScopes(context: Context): Set<String> =
        context.tokenPrefs().getString(C.TOKEN_SCOPES, null)
            ?.split(' ')
            ?.filter(String::isNotBlank)
            ?.toSet()
            ?: emptySet()

    private fun liveWebCookieHeader(context: Context, url: String): String? =
        runCatching {
            (context.applicationContext as? XtraApp)
                ?.xtraModule
                ?.twitchWebSessionManager
                ?.cookieHeaderFor(url)
        }.getOrNull()
            ?: context.tokenPrefs().getString(C.TWITCH_WEB_COOKIE_HEADER, null)

    private fun liveCapturedWebGqlHeaders(context: Context): Map<String, String>? =
        runCatching {
            (context.applicationContext as? XtraApp)
                ?.xtraModule
                ?.twitchWebSessionManager
                ?.capturedGqlHeadersForCurrentAccount()
        }.getOrNull()

    fun isSessionValidationDue(context: Context): Boolean {
        if (context.tokenPrefs().getString(C.GQL_TOKEN_WEB, null).isNullOrBlank()) return false
        val lastValidatedAt = context.tokenPrefs().getLong(C.TOKEN_VALIDATED_AT, 0)
        return !checkedValidation || lastValidatedAt <= 0 ||
            System.currentTimeMillis() - lastValidatedAt >= SESSION_VALIDATION_INTERVAL_MILLIS
    }

    private const val SESSION_VALIDATION_INTERVAL_MILLIS = 60 * 60 * 1_000L
    private const val ANONYMOUS_ACCOUNT_ID = "anonymous"
    private const val WEB_GQL_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun getVideoUrlsFromPreview(url: String, type: String?, list: List<String>?): Map<String, String> {
        val qualityList = list ?: listOf("chunked", "1080p60", "1080p30", "720p60", "720p30", "480p30", "360p30", "160p30", "144p30", "high", "medium", "low", "mobile", "audio_only")
        return qualityList.associate { quality ->
            val name = if (quality == "chunked") {
                "source"
            } else {
                quality
            }
            val url = url
                .replace("storyboards", quality)
                .replaceAfterLast("/",
                    if (type?.lowercase() == "highlight") {
                        "highlight-${url.substringAfterLast("/").substringBefore("-")}.m3u8"
                    } else {
                        "index-dvr.m3u8"
                    }
                )
            name to url
        }
    }

    fun getMessageIdString(context: Context, msgId: String?): String? {
        return when (msgId) {
            "highlighted-message" -> context.getString(R.string.irc_msgid_highlighted_message)
            "announcement" -> context.getString(R.string.irc_msgid_announcement)
            else -> null
        }
    }
}
