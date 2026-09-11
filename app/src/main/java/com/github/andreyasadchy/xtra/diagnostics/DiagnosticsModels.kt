package com.github.andreyasadchy.xtra.diagnostics

enum class DiagnosticsCategory {
    GQL,
    HERMES,
    INTEGRITY,
    CHANNEL_POINTS,
    PROGRESSION,
    DROPS,
}

enum class DiagnosticsSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class DiagnosticsTransport {
    GQL,
    HELIX,
    HERMES,
    WATCH_CREDIT,
    SPADE,
    LOCAL,
}

enum class DiagnosticsPhase {
    REQUEST,
    RESULT,
    ERROR,
    EVENT,
}

/** Keys intentionally limited to values that are safe and useful in a support report. */
enum class DiagnosticsFieldKey {
    CHANNEL_ID,
    GAME_ID,
    CAMPAIGN_ID,
    DROP_ID,
    REWARD_ID,
    PROGRESS,
    TARGET,
    COUNT,
    ATTEMPT,
    STATE,
    RECONNECT_DELAY_MS,
    CLAIMABLE,
    CLAIMED,
    CACHED,
    ACTIVE,
    AUTHENTICATED,
    LIVE,
}

data class DiagnosticsField(
    val key: DiagnosticsFieldKey,
    val value: String,
)

data class DiagnosticsEntry(
    val sequence: Long,
    val timestampMs: Long,
    val category: DiagnosticsCategory,
    val severity: DiagnosticsSeverity,
    val transport: DiagnosticsTransport,
    val operation: String,
    val event: String,
    val phase: DiagnosticsPhase,
    val httpStatus: Int? = null,
    val code: String? = null,
    val elapsedMs: Long? = null,
    val correlationId: String? = null,
    val fields: List<DiagnosticsField> = emptyList(),
)

data class DiagnosticsFilter(
    val categories: Set<DiagnosticsCategory> = DiagnosticsCategory.entries.toSet(),
    val severities: Set<DiagnosticsSeverity> = DiagnosticsSeverity.entries.toSet(),
) {
    fun accepts(entry: DiagnosticsEntry): Boolean =
        entry.category in categories && entry.severity in severities
}
