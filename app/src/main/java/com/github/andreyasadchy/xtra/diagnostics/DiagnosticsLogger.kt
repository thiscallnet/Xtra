package com.github.andreyasadchy.xtra.diagnostics

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class DiagnosticsEntryRing(
    private val maxEntries: Int,
) {
    private val entries = ArrayDeque<DiagnosticsEntry>(maxEntries)

    @Synchronized
    fun append(entry: DiagnosticsEntry) {
        if (entries.size == maxEntries) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshotNewestFirst(): List<DiagnosticsEntry> = entries.toList().asReversed()
}

internal class DiagnosticsCorrelationIdGenerator {
    private val nextId = AtomicLong()

    fun next(): String = "d${nextId.incrementAndGet().toString().padStart(6, '0')}"
}

internal fun diagnosticsRequestSucceeded(
    httpStatus: Int?,
    graphQlError: Boolean,
    malformedResponse: Boolean,
): Boolean = httpStatus != null && httpStatus in 200..299 && !graphQlError && !malformedResponse

class DiagnosticsLogger(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    private val preferences = context.applicationContext.prefs()
    private val entries = DiagnosticsEntryRing(MAX_ENTRIES)
    private val nextSequence = AtomicLong()
    private val nextCorrelationId = DiagnosticsCorrelationIdGenerator()
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var enabled = preferences.getBoolean(C.DIAGNOSTICS_ENABLED, false)

    val changes: SharedFlow<Unit> = _changes.asSharedFlow()
    val isEnabled: Boolean get() = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        preferences.edit { putBoolean(C.DIAGNOSTICS_ENABLED, value) }
        if (!value) clear()
        else _changes.tryEmit(Unit)
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
        _changes.tryEmit(Unit)
    }

    fun snapshot(filter: DiagnosticsFilter = DiagnosticsFilter()): List<DiagnosticsEntry> =
        entries.snapshotNewestFirst().filter(filter::accepts)

    /** Starts a request record without accepting a request object, body, headers, or variables. */
    fun beginRequest(
        category: DiagnosticsCategory,
        transport: DiagnosticsTransport,
        operation: String,
        fields: List<DiagnosticsField> = emptyList(),
    ): RequestToken? {
        if (!enabled) return null
        val token = RequestToken(
            correlationId = nextCorrelationId.next(),
            startedAtElapsedMs = elapsedRealtime(),
            category = category,
            transport = transport,
            operation = operation,
        )
        append(
            category = category,
            severity = DiagnosticsSeverity.DEBUG,
            transport = transport,
            operation = operation,
            event = "request",
            phase = DiagnosticsPhase.REQUEST,
            correlationId = token.correlationId,
            fields = fields,
        )
        return token
    }

    fun finishRequest(
        token: RequestToken?,
        successful: Boolean,
        httpStatus: Int? = null,
        code: String? = null,
        fields: List<DiagnosticsField> = emptyList(),
    ) {
        token ?: return
        if (!token.tryFinish()) return
        append(
            category = token.category,
            severity = if (successful) DiagnosticsSeverity.INFO else DiagnosticsSeverity.WARN,
            transport = token.transport,
            operation = token.operation,
            event = if (successful) "completed" else "rejected",
            phase = if (successful) DiagnosticsPhase.RESULT else DiagnosticsPhase.ERROR,
            httpStatus = httpStatus,
            code = code,
            elapsedMs = elapsedRealtime().minus(token.startedAtElapsedMs).coerceAtLeast(0L),
            correlationId = token.correlationId,
            fields = fields,
        )
    }

    fun failRequest(
        token: RequestToken?,
        code: String,
        fields: List<DiagnosticsField> = emptyList(),
    ) {
        token ?: return
        if (!token.tryFinish()) return
        append(
            category = token.category,
            severity = DiagnosticsSeverity.ERROR,
            transport = token.transport,
            operation = token.operation,
            event = "failed",
            phase = DiagnosticsPhase.ERROR,
            code = code,
            elapsedMs = elapsedRealtime().minus(token.startedAtElapsedMs).coerceAtLeast(0L),
            correlationId = token.correlationId,
            fields = fields,
        )
    }

    fun event(
        category: DiagnosticsCategory,
        severity: DiagnosticsSeverity = DiagnosticsSeverity.INFO,
        transport: DiagnosticsTransport,
        operation: String,
        event: String,
        phase: DiagnosticsPhase = DiagnosticsPhase.EVENT,
        httpStatus: Int? = null,
        code: String? = null,
        elapsedMs: Long? = null,
        correlationId: String? = null,
        fields: List<DiagnosticsField> = emptyList(),
    ) {
        if (!enabled) return
        append(
            category = category,
            severity = severity,
            transport = transport,
            operation = operation,
            event = event,
            phase = phase,
            httpStatus = httpStatus,
            code = code,
            elapsedMs = elapsedMs,
            correlationId = correlationId,
            fields = fields,
        )
    }

    private fun append(
        category: DiagnosticsCategory,
        severity: DiagnosticsSeverity,
        transport: DiagnosticsTransport,
        operation: String,
        event: String,
        phase: DiagnosticsPhase,
        httpStatus: Int? = null,
        code: String? = null,
        elapsedMs: Long? = null,
        correlationId: String? = null,
        fields: List<DiagnosticsField> = emptyList(),
    ) {
        if (!enabled) return
        val entry = DiagnosticsSanitizer.entry(
            DiagnosticsEntry(
                sequence = nextSequence.incrementAndGet(),
                timestampMs = wallClock(),
                category = category,
                severity = severity,
                transport = transport,
                operation = operation,
                event = event,
                phase = phase,
                httpStatus = httpStatus,
                code = code,
                elapsedMs = elapsedMs,
                correlationId = correlationId,
                fields = fields,
            ),
        )
        synchronized(entries) {
            if (!enabled) return
            entries.append(entry)
        }
        _changes.tryEmit(Unit)
    }

    class RequestToken internal constructor(
        val correlationId: String,
        val startedAtElapsedMs: Long,
        val category: DiagnosticsCategory,
        val transport: DiagnosticsTransport,
        val operation: String,
    ) {
        private val terminal = AtomicBoolean(false)

        internal fun tryFinish(): Boolean = terminal.compareAndSet(false, true)
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
