package com.github.andreyasadchy.xtra.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {
    @Test
    fun sensitiveMarkersAreRemovedFromFieldsAndFormattedOutput() {
        val entry = DiagnosticsEntry(
            sequence = 1,
            timestampMs = 0,
            category = DiagnosticsCategory.GQL,
            severity = DiagnosticsSeverity.ERROR,
            transport = DiagnosticsTransport.GQL,
            operation = "Authorization: Bearer secret",
            event = "raw response",
            phase = DiagnosticsPhase.ERROR,
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.STATE, "OAuth very-secret-token"),
                DiagnosticsField(DiagnosticsFieldKey.COUNT, "3"),
            ),
        )

        val formatted = DiagnosticsFormatter.formatEntry(entry)
        assertFalse(formatted.contains("secret"))
        assertFalse(formatted.contains("Authorization:"))
        assertFalse(formatted.contains("raw response"))
        assertTrue(formatted.contains("count=3"))
    }

    @Test
    fun entryAndExportStayWithinTheirCaps() {
        val entry = DiagnosticsEntry(
            sequence = 1,
            timestampMs = 0,
            category = DiagnosticsCategory.DROPS,
            severity = DiagnosticsSeverity.INFO,
            transport = DiagnosticsTransport.LOCAL,
            operation = "o".repeat(500),
            event = "e".repeat(500),
            phase = DiagnosticsPhase.EVENT,
            fields = DiagnosticsFieldKey.entries.map {
                DiagnosticsField(it, "x".repeat(500))
            },
        )

        val safe = DiagnosticsSanitizer.entry(entry)
        assertTrue(DiagnosticsFormatter.formatEntry(safe).length <= DiagnosticsSanitizer.MAX_ENTRY_LENGTH)
        assertTrue(DiagnosticsSanitizer.capExport("x".repeat(DiagnosticsSanitizer.MAX_EXPORT_LENGTH + 10)).length <= DiagnosticsSanitizer.MAX_EXPORT_LENGTH)
    }
}
