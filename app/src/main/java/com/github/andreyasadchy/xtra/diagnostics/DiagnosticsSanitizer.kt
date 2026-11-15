package com.github.andreyasadchy.xtra.diagnostics

internal object DiagnosticsSanitizer {
    const val MAX_OPERATION_LENGTH = 96
    const val MAX_EVENT_LENGTH = 96
    const val MAX_CODE_LENGTH = 96
    const val MAX_FIELD_LENGTH = 256
    const val MAX_ENTRY_LENGTH = 2 * 1024
    const val MAX_EXPORT_LENGTH = 512 * 1024

    private val idKeys = setOf(
        DiagnosticsFieldKey.CHANNEL_ID,
        DiagnosticsFieldKey.STREAM_ID,
        DiagnosticsFieldKey.GAME_ID,
        DiagnosticsFieldKey.CAMPAIGN_ID,
        DiagnosticsFieldKey.DROP_ID,
        DiagnosticsFieldKey.REWARD_ID,
        DiagnosticsFieldKey.ACCOUNT_ID,
    )
    private val labelKeys = setOf(
        DiagnosticsFieldKey.ACCOUNT_LOGIN,
        DiagnosticsFieldKey.NETWORK_LIBRARY,
        DiagnosticsFieldKey.REQUEST_TYPE,
        DiagnosticsFieldKey.HOST,
        DiagnosticsFieldKey.CACHE_STATE,
        DiagnosticsFieldKey.SESSION_STATE,
        DiagnosticsFieldKey.TAP_SOURCE,
        DiagnosticsFieldKey.MEDIA_SOURCE,
        DiagnosticsFieldKey.PROXY_ROUTE,
        DiagnosticsFieldKey.PROCESS_EXIT_REASON,
        DiagnosticsFieldKey.PLAYBACK_SURFACE,
        DiagnosticsFieldKey.RESOLUTION_REASON,
    )
    private val playbackFormatKeys = setOf(
        DiagnosticsFieldKey.VIDEO_CODEC,
        DiagnosticsFieldKey.VIDEO_DECODER,
    )
    private val loginPattern = Regex("[a-z0-9_]{1,30}")
    private val accountIdPattern = Regex("[0-9]{1,32}")
    private val booleanKeys = setOf(
        DiagnosticsFieldKey.CLAIMABLE,
        DiagnosticsFieldKey.CLAIMED,
        DiagnosticsFieldKey.CACHED,
        DiagnosticsFieldKey.RETRY,
        DiagnosticsFieldKey.PRIVATE,
        DiagnosticsFieldKey.ACCOUNT_CHANGED,
        DiagnosticsFieldKey.ACTIVE,
        DiagnosticsFieldKey.AUTHENTICATED,
        DiagnosticsFieldKey.LIVE,
        DiagnosticsFieldKey.PLAY_WHEN_READY,
        DiagnosticsFieldKey.URL_WARM,
        DiagnosticsFieldKey.PREVIEW_ALREADY_PLAYING,
        DiagnosticsFieldKey.VIDEO_DECODER_HARDWARE,
        DiagnosticsFieldKey.LOW_LATENCY,
        DiagnosticsFieldKey.BACKGROUND_PLAYBACK,
        DiagnosticsFieldKey.CASTING,
    )
    private val numberKeys = setOf(
        DiagnosticsFieldKey.COUNT,
        DiagnosticsFieldKey.RESULT_COUNT,
        DiagnosticsFieldKey.ERROR_COUNT,
        DiagnosticsFieldKey.ATTEMPT,
        DiagnosticsFieldKey.REQUEST_BYTES,
        DiagnosticsFieldKey.RESPONSE_BYTES,
        DiagnosticsFieldKey.QUEUE_WAIT_MS,
        DiagnosticsFieldKey.MESSAGE_BYTES,
        DiagnosticsFieldKey.SUBSCRIPTION_COUNT,
        DiagnosticsFieldKey.RECONNECT_COUNT,
        DiagnosticsFieldKey.KEEPALIVE_SEC,
        DiagnosticsFieldKey.ANDROID_API,
        DiagnosticsFieldKey.RECONNECT_DELAY_MS,
        DiagnosticsFieldKey.BUFFER_AHEAD_MS,
        DiagnosticsFieldKey.LIVE_OFFSET_MS,
        DiagnosticsFieldKey.BUFFERING_DURATION_MS,
        DiagnosticsFieldKey.TAP_TO_URL_MS,
        DiagnosticsFieldKey.TAP_TO_START_MS,
        DiagnosticsFieldKey.TAP_TO_PREPARE_MS,
        DiagnosticsFieldKey.TAP_TO_READY_MS,
        DiagnosticsFieldKey.TAP_TO_FIRST_FRAME_MS,
        DiagnosticsFieldKey.TAP_TO_FAILURE_MS,
        DiagnosticsFieldKey.TAP_TO_STALL_MS,
        DiagnosticsFieldKey.PREPARE_TO_READY_MS,
        DiagnosticsFieldKey.PREPARE_TO_FIRST_FRAME_MS,
        DiagnosticsFieldKey.PREPARE_TO_FAILURE_MS,
        DiagnosticsFieldKey.PREPARE_TO_STALL_MS,
        DiagnosticsFieldKey.BANDWIDTH_ESTIMATE_BPS,
        DiagnosticsFieldKey.VIDEO_BITRATE_BPS,
        DiagnosticsFieldKey.VIDEO_HEIGHT,
        DiagnosticsFieldKey.DROPPED_VIDEO_FRAMES,
        DiagnosticsFieldKey.PROCESS_IMPORTANCE,
        DiagnosticsFieldKey.EXIT_TIMESTAMP_MS,
        DiagnosticsFieldKey.EXIT_STATUS_CODE,
    )
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val numberPattern = Regex("-?[0-9]+(?:\\.[0-9]+)?")
    private val playbackFormatPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:,/+ -]{0,63}")
    private val progressPattern = Regex("[0-9]+(?:\\.[0-9]+)?/(?:[0-9]+(?:\\.[0-9]+)?|\\?)")
    private val statePattern = Regex("[A-Za-z][A-Za-z0-9_.:_-]{0,63}")

    fun entry(entry: DiagnosticsEntry): DiagnosticsEntry {
        val operation = safeLabel(entry.operation, MAX_OPERATION_LENGTH)
        val event = safeLabel(entry.event, MAX_EVENT_LENGTH)
        val code = entry.code?.let { safeLabel(it, MAX_CODE_LENGTH) }
        var remaining = (MAX_ENTRY_LENGTH - operation.length - event.length - (code?.length ?: 0) - 128)
            .coerceAtLeast(0)
        val fields = entry.fields.mapNotNull { original ->
            if (remaining <= 0) return@mapNotNull null
            val safe = field(original) ?: return@mapNotNull null
            val overhead = safe.key.name.length + 1
            val valueLength = (remaining - overhead).coerceAtLeast(0).coerceAtMost(MAX_FIELD_LENGTH)
            if (valueLength == 0) return@mapNotNull null
            val result = safe.copy(value = safe.value.take(valueLength))
            remaining -= overhead + result.value.length
            result
        }
        return entry.copy(
            operation = operation,
            event = event,
            code = code,
            correlationId = entry.correlationId?.let { safeLabel(it, MAX_CODE_LENGTH) },
            parentCorrelationId = entry.parentCorrelationId?.let { safeLabel(it, MAX_CODE_LENGTH) },
            fields = fields,
        )
    }

    fun withoutAccountContext(entry: DiagnosticsEntry): DiagnosticsEntry = entry.copy(
        fields = entry.fields.filterNot { it.key == DiagnosticsFieldKey.ACCOUNT_ID || it.key == DiagnosticsFieldKey.ACCOUNT_LOGIN },
    )

    fun field(field: DiagnosticsField): DiagnosticsField? {
        val value = field.value
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .trim()
            .take(MAX_FIELD_LENGTH)
        if (value.isBlank() || containsSensitiveMarker(value) || !isValidFieldValue(field.key, value)) return null
        return field.copy(value = value)
    }

    private fun isValidFieldValue(key: DiagnosticsFieldKey, value: String): Boolean = when {
        key == DiagnosticsFieldKey.ACCOUNT_ID -> accountIdPattern.matches(value)
        key in idKeys -> idPattern.matches(value)
        key == DiagnosticsFieldKey.ACCOUNT_LOGIN -> loginPattern.matches(value.lowercase())
        key in labelKeys -> statePattern.matches(value)
        key in playbackFormatKeys -> playbackFormatPattern.matches(value)
        key in booleanKeys -> value == "true" || value == "false"
        key in numberKeys -> numberPattern.matches(value)
        key == DiagnosticsFieldKey.PROGRESS -> progressPattern.matches(value)
        key == DiagnosticsFieldKey.TARGET -> numberPattern.matches(value)
        key == DiagnosticsFieldKey.STATE -> statePattern.matches(value)
        else -> false
    }

    fun label(value: String, maxLength: Int): String = value
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .trim()
        .take(maxLength)

    private fun safeLabel(value: String, maxLength: Int): String {
        val sanitized = label(value, maxLength)
        return if (containsSensitiveMarker(sanitized)) "[redacted]" else sanitized
    }

    fun capExport(value: String): String {
        if (value.length <= MAX_EXPORT_LENGTH) return value
        val marker = "\n[output truncated]"
        return value.take(MAX_EXPORT_LENGTH - marker.length) + marker
    }

    private fun containsSensitiveMarker(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.contains("oauth ") ||
            normalized.contains("bearer ") ||
            normalized.contains("client-integrity") ||
            normalized.contains("authorization:") ||
            normalized.contains("cookie:") ||
            normalized.contains("set-cookie") ||
            normalized.contains("access_token") ||
            normalized.contains("auth-token") ||
            normalized.contains("refresh_token") ||
            normalized.contains("viewer_id") ||
            normalized.contains("session_id") ||
            normalized.contains("login") ||
            normalized.contains("display_name") ||
            normalized.contains("graphql variables") ||
            normalized.contains("raw response") ||
            normalized.contains("raw payload")
    }
}
