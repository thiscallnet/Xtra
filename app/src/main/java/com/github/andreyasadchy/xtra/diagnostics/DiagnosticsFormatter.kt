package com.github.andreyasadchy.xtra.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsFormatter {
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun formatTimestamp(timestampMs: Long): String =
        timeFormat.get()!!.format(Date(timestampMs))

    fun formatEntry(entry: DiagnosticsEntry, includeAccountContext: Boolean = false): String {
        val safeEntry = DiagnosticsSanitizer.entry(entry).let {
            if (includeAccountContext) it else DiagnosticsSanitizer.withoutAccountContext(it)
        }
        val value = buildString {
            append(formatTimestamp(safeEntry.timestampMs))
            append("  ")
            append(safeEntry.severity.name)
            append("  ")
            append(safeEntry.category.name)
            append("/")
            append(safeEntry.transport.name)
            append("  ")
            append(safeEntry.operation)
            append("  ")
            append(safeEntry.event)
            append("  phase=")
            append(safeEntry.phase.name)
            safeEntry.httpStatus?.let { append(" http=").append(it) }
            safeEntry.code?.let { append(" code=").append(it) }
            safeEntry.elapsedMs?.let { append(" elapsedMs=").append(it) }
            safeEntry.correlationId?.let { append(" correlation=").append(it) }
            safeEntry.parentCorrelationId?.let { append(" parentCorrelation=").append(it) }
            if (safeEntry.fields.isNotEmpty()) {
                append("\n")
                safeEntry.fields.forEachIndexed { index, field ->
                    if (index > 0) append(" ")
                    append(field.key.name.lowercase(Locale.US))
                    append("=")
                    append(field.value)
                }
            }
        }
        return DiagnosticsSanitizer.capExport(value.take(DiagnosticsSanitizer.MAX_ENTRY_LENGTH))
    }

    fun formatAll(
        entries: List<DiagnosticsEntry>,
        environment: DiagnosticsEnvironment? = null,
        includeAccountContext: Boolean = false,
    ): String {
        val header = buildString {
            append("Xtra diagnostics")
            environment?.let {
                append("\napp=").append(DiagnosticsSanitizer.label(it.appVersion, 64))
                append(" build=").append(DiagnosticsSanitizer.label(it.appBuild, 32))
                append(" androidApi=").append(it.androidApi)
                append(" device=").append(DiagnosticsSanitizer.label(it.deviceModel, 96))
                it.appearance?.let { appearance ->
                    append("\nappearance=").append(DiagnosticsSanitizer.label(appearance, 512))
                }
            }
            append("\n\n")
        }
        return DiagnosticsSanitizer.capExport(
            entries.joinToString("\n\n", prefix = header) {
                formatEntry(it, includeAccountContext)
            },
        )
    }
}
