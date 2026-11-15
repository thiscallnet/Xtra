package com.github.andreyasadchy.xtra.diagnostics

enum class DiagnosticsCategory {
    GQL,
    HERMES,
    INTEGRITY,
    CHANNEL_POINTS,
    PROGRESSION,
    DROPS,
    AUTH,
    PLAYBACK,
    UPDATER,
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
    PLAYER,
    UPDATER,
    AUTH,
    LOCAL,
}

enum class DiagnosticsPhase {
    REQUEST,
    RESULT,
    ERROR,
    EVENT,
}

/** Lifecycle events used by the request helpers. Custom subsystem events remain labels. */
enum class DiagnosticsLifecycleEvent(val value: String) {
    REQUEST_STARTED("request_started"),
    REQUEST_COMPLETED("request_completed"),
    REQUEST_REJECTED("request_rejected"),
    REQUEST_FAILED("request_failed"),
    REQUEST_RETRY("request_retry"),
    CACHE_HIT("cache_hit"),
    CACHE_MISS("cache_miss"),
    STATE_CHANGED("state_changed"),
}

/** Keys intentionally limited to values that are safe and useful in a support report. */
enum class DiagnosticsFieldKey {
    CHANNEL_ID,
    STREAM_ID,
    GAME_ID,
    CAMPAIGN_ID,
    DROP_ID,
    REWARD_ID,
    ACCOUNT_ID,
    ACCOUNT_LOGIN,
    PROGRESS,
    TARGET,
    COUNT,
    RESULT_COUNT,
    ERROR_COUNT,
    ATTEMPT,
    RETRY,
    REQUEST_BYTES,
    RESPONSE_BYTES,
    QUEUE_WAIT_MS,
    MESSAGE_BYTES,
    SUBSCRIPTION_COUNT,
    RECONNECT_COUNT,
    KEEPALIVE_SEC,
    NETWORK_LIBRARY,
    REQUEST_TYPE,
    HOST,
    PLAY_WHEN_READY,
    BUFFER_AHEAD_MS,
    LIVE_OFFSET_MS,
    BUFFERING_DURATION_MS,
    TAP_SOURCE,
    MEDIA_SOURCE,
    URL_WARM,
    PREVIEW_ALREADY_PLAYING,
    TAP_TO_URL_MS,
    TAP_TO_START_MS,
    TAP_TO_PREPARE_MS,
    TAP_TO_READY_MS,
    TAP_TO_FIRST_FRAME_MS,
    TAP_TO_FAILURE_MS,
    TAP_TO_STALL_MS,
    PREPARE_TO_READY_MS,
    PREPARE_TO_FIRST_FRAME_MS,
    PREPARE_TO_FAILURE_MS,
    PREPARE_TO_STALL_MS,
    PROXY_ROUTE,
    BANDWIDTH_ESTIMATE_BPS,
    VIDEO_BITRATE_BPS,
    VIDEO_HEIGHT,
    VIDEO_CODEC,
    VIDEO_DECODER,
    VIDEO_DECODER_HARDWARE,
    DROPPED_VIDEO_FRAMES,
    LOW_LATENCY,
    BACKGROUND_PLAYBACK,
    CASTING,
    PROCESS_EXIT_REASON,
    PROCESS_IMPORTANCE,
    EXIT_TIMESTAMP_MS,
    EXIT_STATUS_CODE,
    PLAYBACK_SURFACE,
    RESOLUTION_REASON,
    ANDROID_API,
    CACHE_STATE,
    PRIVATE,
    ACCOUNT_CHANGED,
    STATE,
    SESSION_STATE,
    RECONNECT_DELAY_MS,
    CLAIMABLE,
    CLAIMED,
    CACHED,
    ACTIVE,
    AUTHENTICATED,
    LIVE,
}

data class DiagnosticsEnvironment(
    val appVersion: String,
    val appBuild: String,
    val androidApi: Int,
    val deviceModel: String,
    val appearance: String? = null,
)

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
    val parentCorrelationId: String? = null,
    val fields: List<DiagnosticsField> = emptyList(),
)

data class DiagnosticsFilter(
    val categories: Set<DiagnosticsCategory> = DiagnosticsCategory.entries.toSet(),
    val severities: Set<DiagnosticsSeverity> = DiagnosticsSeverity.entries.toSet(),
) {
    fun accepts(entry: DiagnosticsEntry): Boolean =
        entry.category in categories && entry.severity in severities
}
