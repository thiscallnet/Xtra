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
        DiagnosticsFieldKey.GAME_ID,
        DiagnosticsFieldKey.CAMPAIGN_ID,
        DiagnosticsFieldKey.DROP_ID,
        DiagnosticsFieldKey.REWARD_ID,
    )
    private val booleanKeys = setOf(
        DiagnosticsFieldKey.CLAIMABLE,
        DiagnosticsFieldKey.CLAIMED,
        DiagnosticsFieldKey.CACHED,
        DiagnosticsFieldKey.ACTIVE,
        DiagnosticsFieldKey.AUTHENTICATED,
        DiagnosticsFieldKey.LIVE,
    )
    private val numberKeys = setOf(
        DiagnosticsFieldKey.COUNT,
        DiagnosticsFieldKey.ATTEMPT,
        DiagnosticsFieldKey.RECONNECT_DELAY_MS,
    )
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val numberPattern = Regex("-?[0-9]+(?:\\.[0-9]+)?")
    private val progressPattern = Regex("[0-9]+(?:\\.[0-9]+)?/(?:[0-9]+(?:\\.[0-9]+)?|\\?)")
    private val statePattern = Regex("[A-Za-z][A-Za-z0-9_.:-]{0,63}")

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
            fields = fields,
        )
    }

    fun field(field: DiagnosticsField): DiagnosticsField? {
        val value = field.value
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .trim()
            .take(MAX_FIELD_LENGTH)
        if (value.isBlank() || containsSensitiveMarker(value) || !isValidFieldValue(field.key, value)) return null
        return field.copy(value = value)
    }

    private fun isValidFieldValue(key: DiagnosticsFieldKey, value: String): Boolean = when {
        key in idKeys -> idPattern.matches(value)
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
